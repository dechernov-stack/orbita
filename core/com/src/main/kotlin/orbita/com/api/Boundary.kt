// Граница модуля (TZ-MOD-002, TZ-COM-007): каждый вход валидируется по схеме
// целевого типа; невалидные данные отклоняются с путём до поля, правилом и,
// для нарушений Р1–Р9, идентификатором ADR (TZ-MOD-003).
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
import java.sql.Connection

class Boundary(private val registry: SchemaRegistry, conn: Connection) {

    val objects = ObjectStore(conn)
    val links = LinkStore(conn)
    val params = ParamStore(conn)
    val results = ResultStore(conn)

    /**
     * Приём объекта ядра: валидация по схеме типа, затем сохранение текущей версией.
     * Статус и версия берутся из блока lifecycle документа (для scenario его нет —
     * применяются Draft/«1»).
     */
    fun ingest(type: CoreType, json: String, createdBy: String = "api"): StoredObject {
        val doc = parse(json)
        registry.require(type.schemaName, doc)
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

    /** Валидация контракта между модулями без сохранения (TZ-COM-007). */
    fun validateContract(schemaName: String, json: String): List<ValidationError> =
        registry.validate(schemaName, parse(json))

    fun schemaNames(): List<String> = registry.names

    private fun parse(json: String): JsonNode = registry.parse(json)
}
