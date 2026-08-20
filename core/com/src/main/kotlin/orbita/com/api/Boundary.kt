// Граница модуля (TZ-MOD-002, TZ-COM-007): каждый вход валидируется по схеме
// целевого типа; невалидные данные отклоняются с путём до поля, правилом и,
// для нарушений Р1–Р9, идентификатором ADR (TZ-MOD-003). Объекты ядра шага 2
// принимаются через контур требований: связи выводятся из документов.
package orbita.com.api

import com.fasterxml.jackson.databind.JsonNode
import orbita.mod.model.CoreType
import orbita.mod.model.Lifecycle
import orbita.mod.schema.SchemaRegistry
import orbita.mod.schema.ValidationError
import orbita.mod.store.LinkStore
import orbita.mod.store.ObjectStore
import orbita.mod.store.ParamStore
import orbita.mod.store.ResultStore
import orbita.mod.store.StoredObject
import orbita.bal.VisibilityPrecompute
import orbita.net.LoRaWanAdapter
import orbita.net.validateAdapterContract
import orbita.out.Matrices
import orbita.out.MaturityReports
import orbita.out.ScreenViews
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
        CoreType.Scenario -> {
            val doc = parse(json)
            registry.require(type.schemaName, doc)
            val lifecycle = doc.path("lifecycle")
            objects.create(
                id = doc["id"].asText(),
                type = type.dbType,
                doc = doc,
                status = Lifecycle.valueOf(lifecycle.path("status").asText(Lifecycle.Draft.name)),
                version = lifecycle.path("version").asText("1"),
                createdBy = createdBy,
            )
        }
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
