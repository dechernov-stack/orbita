// Объекты ядра: интервальная версионность и процедура изменения Baseline
// (TZ-COM-003, TZ-MOD-007, ADR-011). Ровно одна текущая версия на ID —
// частичный уникальный индекс objects_current в DDL.
package orbita.mod.store

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.model.Lifecycle
import java.sql.Connection
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset

data class StoredObject(
    val pk: Long,
    val id: String,
    val type: String,
    val version: String,
    val status: Lifecycle,
    val doc: JsonNode,
    val validFrom: OffsetDateTime,
    val validTo: OffsetDateTime?,
    val supersedes: Long?,
    val changeRef: String?,
    val createdBy: String,
)

class ObjectStore(private val conn: Connection, private val mapper: ObjectMapper = ObjectMapper()) {

    /** Создание объекта. Повторное использование ID отклоняется (TZ-MOD-007). */
    fun create(
        id: String,
        type: String,
        doc: JsonNode,
        status: Lifecycle = Lifecycle.Draft,
        version: String = "1",
        createdBy: String = "system",
        validFrom: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
    ): StoredObject = mappingConstraints {
        conn.prepareStatement(
            """INSERT INTO objects(id, type, version, status, doc, valid_from, created_by)
               VALUES (?, ?::object_type, ?, ?::lifecycle, ?::jsonb, ?, ?)
               RETURNING $COLUMNS"""
        ).use { ps ->
            ps.setString(1, id)
            ps.setString(2, type)
            ps.setString(3, version)
            ps.setString(4, status.name)
            ps.setString(5, mapper.writeValueAsString(doc))
            ps.setObject(6, validFrom)
            ps.setString(7, createdBy)
            ps.executeQuery().use { rs -> rs.next(); rs.toStoredObject() }
        }
    }

    /** Текущая версия объекта (valid_to IS NULL). */
    fun current(id: String): StoredObject? =
        conn.prepareStatement("SELECT $COLUMNS FROM objects WHERE id = ? AND valid_to IS NULL").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toStoredObject() else null }
        }

    /** Все версии объекта, от ранних к поздним. */
    fun history(id: String): List<StoredObject> =
        conn.prepareStatement(
            "SELECT $COLUMNS FROM objects WHERE id = ? ORDER BY valid_from, pk"
        ).use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> rs.collect() }
        }

    /** Срез модели на дату (db/queries.sql, запрос 5; TZ-OUT-003). */
    fun sliceAt(at: OffsetDateTime): List<StoredObject> =
        conn.prepareStatement(
            "SELECT $COLUMNS FROM objects WHERE valid_from <= ? AND (valid_to IS NULL OR valid_to > ?) ORDER BY id"
        ).use { ps ->
            ps.setObject(1, at)
            ps.setObject(2, at)
            ps.executeQuery().use { rs -> rs.collect() }
        }

    /**
     * Процедура изменения (TZ-COM-003): интервал текущей версии закрывается,
     * создаётся новая версия со ссылкой supersedes. Для Baseline-объекта
     * обязательно основание (change_ref); прямой UPDATE дополнительно пресекает
     * триггер objects_baseline_guard в DDL (STEP-1, ловушка 3).
     */
    fun change(
        id: String,
        newDoc: JsonNode,
        changeRef: String? = null,
        createdBy: String = "system",
        at: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
    ): StoredObject {
        val cur = current(id) ?: throw NoSuchElementException("object '$id' has no current version")
        if (cur.status == Lifecycle.Baseline && changeRef.isNullOrBlank()) {
            throw BaselineChangeException(
                "TZ-COM-003: changing baseline object '$id' requires a change basis (change_ref)"
            )
        }
        return mappingConstraints {
            conn.tx {
                conn.prepareStatement(
                    "UPDATE objects SET valid_to = ?, change_ref = COALESCE(?, change_ref) WHERE pk = ?"
                ).use { ps ->
                    ps.setObject(1, at)
                    ps.setString(2, changeRef)
                    ps.setLong(3, cur.pk)
                    ps.executeUpdate()
                }
                conn.prepareStatement(
                    """INSERT INTO objects(id, type, version, status, doc, valid_from, supersedes, change_ref, created_by)
                       VALUES (?, ?::object_type, ?, 'Draft'::lifecycle, ?::jsonb, ?, ?, ?, ?)
                       RETURNING $COLUMNS"""
                ).use { ps ->
                    ps.setString(1, cur.id)
                    ps.setString(2, cur.type)
                    ps.setString(3, bumpVersion(cur.version))
                    ps.setString(4, mapper.writeValueAsString(newDoc))
                    ps.setObject(5, at)
                    ps.setLong(6, cur.pk)
                    ps.setString(7, changeRef)
                    ps.setString(8, createdBy)
                    ps.executeQuery().use { rs -> rs.next(); rs.toStoredObject() }
                }
            }
        }
    }

    /** «1» → «2», «0.1» → «0.2»: увеличивается последний числовой сегмент версии. */
    internal fun bumpVersion(version: String): String {
        val parts = version.split('.').toMutableList()
        val last = parts.last().toIntOrNull()
            ?: throw ModelViolationException("cannot bump non-numeric version '$version'")
        parts[parts.lastIndex] = (last + 1).toString()
        return parts.joinToString(".")
    }

    private fun ResultSet.collect(): List<StoredObject> =
        buildList { while (next()) add(toStoredObject()) }

    private fun ResultSet.toStoredObject() = StoredObject(
        pk = getLong("pk"),
        id = getString("id"),
        type = getString("type"),
        version = getString("version"),
        status = Lifecycle.valueOf(getString("status")),
        doc = mapper.readTree(getString("doc")),
        validFrom = getObject("valid_from", OffsetDateTime::class.java),
        validTo = getObject("valid_to", OffsetDateTime::class.java),
        supersedes = getLong("supersedes").let { if (wasNull()) null else it },
        changeRef = getString("change_ref"),
        createdBy = getString("created_by"),
    )

    private companion object {
        const val COLUMNS =
            "pk, id, type::text AS type, version, status::text AS status, doc::text AS doc, " +
                "valid_from, valid_to, supersedes, change_ref, created_by"
    }
}
