// Граница модуля (TZ-MOD-002, TZ-COM-007): каждый вход валидируется по схеме
// целевого типа; невалидные данные отклоняются с путём до поля, правилом и,
// для нарушений Р1–Р9, идентификатором ADR (TZ-MOD-003). Объекты ядра шага 2
// принимаются через контур требований: связи выводятся из документов.
package orbita.com.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
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

    /** Импорт — третий канал в модель (шаг 14, ADR-024): правовой режим источников. */
    val importPolicy = orbita.mod.model.ImportPolicy()

    /**
     * Рабочий слой (шаг 15): ввод, правка и отмена через интерфейс. Правила —
     * те же, что у приёма и импорта; собственных у форм нет.
     */
    val editing: Editing by lazy { Editing(this) }

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
            if (doc is ObjectNode) stampInputVersions(doc)
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

    /**
     * Проверка документа БЕЗ сохранения — теми же функциями, что и приём:
     * схема вида плюс прикладные правила (`requireApplicationRules`), плюс
     * особенности отдельных видов. Используется правкой через интерфейс
     * (шаг 15 §1.3), чтобы у форм не завёлся собственный набор правил.
     */
    fun validate(type: CoreType, doc: JsonNode) {
        // Интерфейс схемы своего вида не имеет: в CoreType он делит схему
        // с элементом, а его id ей не соответствует — приём тоже её не требует.
        if (type != CoreType.Interface) registry.require(type.schemaName, doc)
        req.requireApplicationRules(type.dbType, doc)
        when (type) {
            CoreType.TerminalProfile -> terminalRules.validate(doc).takeIf { it.isNotEmpty() }
                ?.let { throw SchemaValidationException(type.schemaName, it) }

            CoreType.Scenario -> {
                if (doc is ObjectNode) stampInputVersions(doc)
                val problems = resolveScenario(doc) { ref -> objects.current(ref)?.let { CoreType.byDbType(it.type) } } +
                    inputVersionsComplete(doc)
                if (problems.isNotEmpty()) {
                    throw ModelViolationException(
                        "сценарий ${doc.path("id").asText()}: " + problems.joinToString("; ")
                    )
                }
            }

            else -> {}
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

    /** Схема вида как документ — правке нужны обязательные поля (шаг 15). */
    fun rawSchema(name: String): JsonNode = registry.raw(name)

    /** Допускает ли схема вида поле верхнего уровня — служебные поля правки
     *  навешиваются только тем, чья схема их несёт. */
    fun schemaAllows(type: CoreType, field: String): Boolean =
        registry.raw(type.schemaName).path("properties").has(field)

    /**
     * Схема с раскрытыми ссылками — для форм ввода (шаг 15 §2). Форма строится
     * ПО СХЕМЕ: перечень полей, обязательность и допустимые значения приходят
     * из нормативной структуры, а не переписываются в клиент, где разошлись бы
     * с ней молча. Клиенту незачем ходить за общими схемами отдельно.
     */
    fun bundledSchema(name: String): JsonNode = inline(registry.raw(name), mutableSetOf(name))

    private fun inline(node: JsonNode, seen: MutableSet<String>): JsonNode {
        if (node.isArray) {
            val arr = (node as com.fasterxml.jackson.databind.node.ArrayNode).deepCopy()
            for (i in 0 until arr.size()) arr.set(i, inline(arr[i], seen))
            return arr
        }
        if (!node.isObject) return node
        val obj = (node as com.fasterxml.jackson.databind.node.ObjectNode).deepCopy()
        val ref = obj.path("\$ref").asText("")
        if (ref.startsWith(SCHEMA_URI_PREFIX)) {
            val target = ref.removePrefix(SCHEMA_URI_PREFIX).removeSuffix(".schema.json")
            // Циклическая ссылка оставляется ссылкой: форма покажет её полем
            // структурного ввода, а не уйдёт в бесконечную подстановку.
            if (target in seen) return obj
            val resolved = inline(registry.raw(target), (seen + target).toMutableSet())
            val merged = (resolved as com.fasterxml.jackson.databind.node.ObjectNode).deepCopy()
            merged.remove(listOf("\$id", "\$schema"))
            // собственные поля ссылки (description и т.п.) важнее общих
            obj.properties().forEach { (k, v) -> if (k != "\$ref") merged.set<JsonNode>(k, v) }
            return merged
        }
        obj.properties().toList().forEach { (k, v) -> obj.set<JsonNode>(k, inline(v, seen)) }
        return obj
    }

    private companion object {
        const val SCHEMA_URI_PREFIX = "https://kis.local/schemas/"
    }

    private fun parse(json: String): JsonNode = registry.parse(json)
    /**
     * Фиксация версий входов сценария (V008, TZ-MOD-007): версию знает система,
     * а не инженер — форма ввода словаря версий и не нужна, и вредна: версия,
     * набранная из головы, разойдётся с хранилищем молча. Явно заданные версии
     * НЕ перетираются: это заявка «считаю по той версии», и расхождение с
     * текущей — предмет stale-каскада, а не тихой правки.
     */
    private fun stampInputVersions(doc: ObjectNode) {
        val versions = doc.withObject("/input_versions")
        orbita.mod.model.SCENARIO_REF_FIELDS.keys.forEach { field ->
            val ref = doc.path(field).asText("")
            if (ref.isNotBlank() && versions.path(ref).isMissingNode) {
                objects.current(ref)?.let { versions.put(ref, it.version) }
            }
        }
    }

}
