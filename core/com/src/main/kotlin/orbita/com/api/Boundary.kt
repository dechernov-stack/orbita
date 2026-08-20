// Граница модуля (TZ-MOD-002, TZ-COM-007): каждый вход валидируется по схеме
// целевого типа; невалидные данные отклоняются с путём до поля, правилом и,
// для нарушений Р1–Р9, идентификатором ADR (TZ-MOD-003). Объекты ядра шага 2
// принимаются через контур требований: связи выводятся из документов.
package orbita.com.api

import com.fasterxml.jackson.databind.JsonNode
import orbita.mod.model.CoreType
import orbita.mod.model.Lifecycle
import orbita.mod.model.inputVersionsComplete
import orbita.mod.model.resolveScenario
import orbita.mod.schema.SchemaRegistry
import orbita.mod.schema.SchemaValidationException
import orbita.mod.schema.ValidationError
import orbita.mod.store.ModelViolationException
import orbita.mod.store.LinkStore
import orbita.mod.store.ObjectStore
import orbita.mod.store.ParamStore
import orbita.mod.store.ResultStore
import orbita.mod.store.StoredObject
import orbita.bal.VisibilityPrecompute
import orbita.net.LoRaWanAdapter
import orbita.net.validateAdapterContract
import orbita.out.DemandViews
import orbita.out.Matrices
import orbita.out.MaturityReports
import orbita.out.ScreenViews
import orbita.out.SpacecraftViews
import orbita.ai.PromptPackageBuilder
import orbita.ai.ProposalScreening
import orbita.ai.ResponseParser
import orbita.out.WizardViews
import orbita.req.ReqService
import orbita.usr.TerminalRules
import java.sql.Connection

class Boundary(private val registry: SchemaRegistry, conn: Connection) {

    val objects = ObjectStore(conn)
    val links = LinkStore(conn)
    val params = ParamStore(conn)
    val results = ResultStore(conn)
    val req = ReqService(conn, registry)
    val matrices = Matrices(req)
    val maturity = MaturityReports(req)

    /** Готовые строки экранов: расчётов в клиенте нет (STEP-6 §3.2). */
    val screens = ScreenViews(req)

    /** Экраны мастера Ш1–Ш7 (STEP-7-9 §9.1). */
    val wizard = WizardViews(req)

    /** Экран 4: карта спроса — слои и веса считаются здесь (TZ-USR-004). */
    val demand = DemandViews()

    /** Экран 5: бюджеты аппарата — масса, энергетика, линии, маяк (TZ-KA). */
    val spacecraft = SpacecraftViews()

    /**
     * ИИ-контур (TZ-AI). Генерация происходит ВНЕ системы: инженер копирует
     * пакет во внешний интерфейс LLM и вставляет ответ обратно. Здесь —
     * сборка пакета, локальный разбор и структурный фильтр.
     */
    val packages = PromptPackageBuilder(registry = registry)
    val parser = ResponseParser()
    val screening = ProposalScreening()

    /** Расчётный контур шага 3: адаптер протокола и предрасчёт геометрии. */
    val protocolAdapter = LoRaWanAdapter()
    val visibility = VisibilityPrecompute()

    private val terminalRules = TerminalRules(registry)

    /**
     * Приём объекта ядра. Типы контура требований идут через ReqService —
     * прикладные правила (Р9, flow down) и связи из документа; сценарий
     * сохраняется напрямую после валидации по схеме.
     */
    fun ingest(type: CoreType, json: String, createdBy: String = "api"): StoredObject = when (type) {
        CoreType.Need -> req.ingestNeed(json, createdBy)
        CoreType.Service -> req.ingestService(json, createdBy)
        CoreType.Requirement -> req.ingestRequirement(json, createdBy)
        CoreType.Component -> req.ingestComponent(json, createdBy)
        // CR-003: свидетельства, валидации и интерфейсы — самостоятельные объекты
        CoreType.Evidence -> req.ingestEvidence(json, createdBy)
        CoreType.Validation -> req.ingestValidation(json, createdBy)
        CoreType.Interface -> req.ingestInterface(json, createdBy)
        CoreType.Risk -> req.ingestRisk(json, createdBy)
        CoreType.Scenario -> {
            val doc = parse(json)
            registry.require(type.schemaName, doc)
            // Ссылки разрешаются ДО сохранения: сценарий со ссылкой в никуда
            // не расчётный случай, а обещание невоспроизводимого результата.
            val problems = resolveScenario(doc) { ref -> objects.current(ref)?.let { CoreType.byDbType(it.type) } } +
                inputVersionsComplete(doc)
            if (problems.isNotEmpty()) {
                throw ModelViolationException("сценарий ${doc.path("id").asText()}: " + problems.joinToString("; "))
            }
            store(type, doc, createdBy)
        }
        // CR-005/ADR-021: входы моделирования. Прикладных правил связей у них
        // нет — только схема и статусная модель, поэтому общий путь сохранения.
        CoreType.Constellation, CoreType.Spacecraft, CoreType.DemandMap,
        CoreType.TerminalProfile, CoreType.GroundStations, CoreType.ProtocolAdapter -> {
            val doc = parse(json)
            registry.require(type.schemaName, doc)
            if (type == CoreType.TerminalProfile) {
                // класс терминала подчиняется правилам TZ-USR-001 сверх схемы
                terminalRules.validate(doc).takeIf { it.isNotEmpty() }
                    ?.let { throw SchemaValidationException(type.schemaName, it) }
            }
            store(type, doc, createdBy)
        }
    }

    /** Сохранение объекта, у которого прикладных правил связей нет: схема и статус. */
    private fun store(type: CoreType, doc: JsonNode, createdBy: String): StoredObject {
        val lifecycle = doc.path("lifecycle")
        return objects.create(
            id = doc["id"].asText(),
            type = type.dbType,
            doc = doc,
            status = Lifecycle.valueOf(lifecycle.path("status").asText(Lifecycle.Draft.name)),
            version = lifecycle.path("version").asText("1"),
            createdBy = createdBy,
        )
    }

    /**
     * Валидация контракта между модулями без сохранения (TZ-COM-007).
     * Профиль терминала дополнительно проходит правила классов (TZ-USR-001).
     */
    fun validateContract(schemaName: String, json: String): List<ValidationError> = when (schemaName) {
        "contracts/terminal-profile" -> terminalRules.validate(parse(json))
        // адаптер без нисходящего канала несовместим с Р5/Р6 — правило вне схемы (TZ-NET-002)
        "contracts/protocol-adapter" -> registry.validate(schemaName, parse(json)) +
            validateAdapterContract(parse(json)).map {
                ValidationError("/mac/downlink_supported", "compatibility", it, adr = "ADR-005 (Р5)")
            }
        else -> registry.validate(schemaName, parse(json))
    }

    fun schemaNames(): List<String> = registry.names

    private fun parse(json: String): JsonNode = registry.parse(json)
}
