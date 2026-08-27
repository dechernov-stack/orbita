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
    /** Контейнер (ADR-022): идентификатор объекта вида project. */
    val projectId: String,
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
        projectId: String = DEFAULT_PROJECT,
    ): StoredObject = mappingConstraints {
        conn.prepareStatement(
            """INSERT INTO objects(id, type, version, status, doc, valid_from, created_by, project_id)
               VALUES (?, ?::object_type, ?, ?::lifecycle, ?::jsonb, ?, ?, ?)
               RETURNING $COLUMNS"""
        ).use { ps ->
            ps.setString(1, id)
            ps.setString(2, type)
            ps.setString(3, version)
            ps.setString(4, status.name)
            ps.setString(5, mapper.writeValueAsString(doc))
            ps.setObject(6, validFrom)
            ps.setString(7, createdBy)
            // объект вида project принадлежит сам себе (ADR-022)
            ps.setString(8, if (type == "project") id else projectId)
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

    /** Вся история объектов одного типа одним запросом (Т-1: флаги помет реестра). */
    fun historyByType(type: String, projectId: String? = null): List<StoredObject> =
        conn.prepareStatement(
            "SELECT $COLUMNS FROM objects WHERE type = ?::object_type" +
                (if (projectId != null) " AND project_id = ?" else "") + " ORDER BY id, valid_from, pk"
        ).use { ps ->
            ps.setString(1, type)
            if (projectId != null) ps.setString(2, projectId)
            ps.executeQuery().use { rs -> rs.collect() }
        }

    /** Текущие версии всех объектов; с [projectId] — только объекты проекта. */
    fun listCurrent(projectId: String? = null): List<StoredObject> =
        conn.prepareStatement(
            "SELECT $COLUMNS FROM objects WHERE valid_to IS NULL" +
                (if (projectId != null) " AND project_id = ?" else "") + " ORDER BY id"
        ).use { ps ->
            if (projectId != null) ps.setString(1, projectId)
            ps.executeQuery().use { rs -> rs.collect() }
        }

    /** Срез модели на дату (db/queries.sql, запрос 5; TZ-OUT-003). */
    fun sliceAt(at: OffsetDateTime, projectId: String? = null): List<StoredObject> =
        conn.prepareStatement(
            "SELECT $COLUMNS FROM objects WHERE valid_from <= ? AND (valid_to IS NULL OR valid_to > ?)" +
                (if (projectId != null) " AND project_id = ?" else "") + " ORDER BY id"
        ).use { ps ->
            ps.setObject(1, at)
            ps.setObject(2, at)
            if (projectId != null) ps.setString(3, projectId)
            ps.executeQuery().use { rs -> rs.collect() }
        }

    /** Идентификаторы проектов портфеля (ADR-022): контейнеры — объекты вида project. */
    fun projectIds(): List<String> =
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT id FROM objects WHERE type = 'project' AND valid_to IS NULL ORDER BY id"
            ).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
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
        baseVersion: String? = null,
    ): StoredObject {
        val cur = current(id) ?: throw NoSuchElementException("object '$id' has no current version")
        // Оптимистичная блокировка (шаг 15): правка несёт версию, на которой
        // основана. Расхождение — отказ с показом чужого значения и автора.
        // Отсутствие baseVersion сохраняет прежнее поведение внутренних вызовов.
        if (baseVersion != null && baseVersion != cur.version) {
            throw conflictWith(cur, baseVersion, newDoc)
        }
        // ADR-031: на утверждённом (Approved/Baseline) правка допустима
        // только с основанием; в Draft/Preliminary основание не обязательно
        if ((cur.status == Lifecycle.Baseline || cur.status == Lifecycle.Approved) &&
            changeRef.isNullOrBlank()
        ) {
            throw BaselineChangeException(
                "TZ-COM-003: changing approved object '$id' requires a change basis (change_ref)"
            )
        }
        return mappingConstraints {
            conn.tx {
                val closed = conn.prepareStatement(
                    "UPDATE objects SET valid_to = ?, change_ref = COALESCE(?, change_ref) " +
                        "WHERE pk = ? AND valid_to IS NULL"
                ).use { ps ->
                    ps.setObject(1, at)
                    ps.setString(2, changeRef)
                    ps.setLong(3, cur.pk)
                    ps.executeUpdate()
                }
                // Интервал закрыл кто-то другой между чтением и записью: та же
                // потерянная правка, что и расхождение версий, только в гонке.
                // Сравнение версий её не ловит — ловит условие valid_to IS NULL.
                if (closed == 0) {
                    val now = current(id) ?: throw NoSuchElementException("object '$id' has no current version")
                    throw conflictWith(now, baseVersion ?: cur.version, newDoc)
                }
                conn.prepareStatement(
                    // ADR-031: правка содержимого НАСЛЕДУЕТ статус (v+1 в том
                    // же статусе); смена статуса — только переходом (transition)
                    """INSERT INTO objects(id, type, version, status, doc, valid_from, supersedes, change_ref, created_by, project_id)
                       VALUES (?, ?::object_type, ?, ?::lifecycle, ?::jsonb, ?, ?, ?, ?, ?)
                       RETURNING $COLUMNS"""
                ).use { ps ->
                    ps.setString(1, cur.id)
                    ps.setString(2, cur.type)
                    ps.setString(3, bumpVersion(cur.version))
                    ps.setString(4, cur.status.name)
                    ps.setString(5, mapper.writeValueAsString(newDoc))
                    ps.setObject(6, at)
                    ps.setLong(7, cur.pk)
                    ps.setString(8, changeRef)
                    ps.setString(9, createdBy)
                    ps.setString(10, cur.projectId)
                    ps.executeQuery().use { rs -> rs.next(); rs.toStoredObject() }
                }
            }
        }
    }

    /**
     * Переход статуса (TZ-COM-003): интервал текущей версии закрывается, создаётся
     * строка с тем же содержанием и новым статусом — история переходов сохраняется
     * с автором и датой, срез на дату отдаёт статус на момент. Объект в Baseline
     * этим путём не меняется — только процедурой [change].
     */
    fun transition(
        id: String,
        target: Lifecycle,
        createdBy: String = "system",
        at: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
    ): StoredObject {
        val cur = current(id) ?: throw NoSuchElementException("object '$id' has no current version")
        // ADR-031: подтверждение — переход в ТОТ ЖЕ утверждённый статус —
        // законно (повторное базирование гасит помету); прочие уходы с
        // Baseline — только процедурой изменения
        if (cur.status == Lifecycle.Baseline && target != Lifecycle.Baseline) {
            throw BaselineChangeException(
                "TZ-COM-003: object '$id' is baselined; changes go through the change procedure"
            )
        }
        val newDoc = cur.doc.deepCopy<JsonNode>()
        // Блок lifecycle отражается в doc только там, где он уже есть: схема
        // вида со статусной моделью несёт его обязательным, а вид без него
        // (риск, замечание обзора, сценарий) получал сюда мусор, который его
        // же схема отклоняла при следующей правке — объект становился
        // нередактируемым (находка второго захода: «ответ не сохраняется»).
        (newDoc as? com.fasterxml.jackson.databind.node.ObjectNode)
            ?.takeIf { it.has("lifecycle") }
            ?.withObject("/lifecycle")?.put("status", target.name)
        return mappingConstraints {
            conn.tx {
                // основание в закрываемой строке — требование триггера при
                // закрытии Baseline-версии (подтверждение статуса)
                conn.prepareStatement(
                    "UPDATE objects SET valid_to = ?, change_ref = COALESCE(change_ref, ?) WHERE pk = ?"
                ).use { ps ->
                    ps.setObject(1, at)
                    ps.setString(2, if (cur.status == Lifecycle.Baseline) "повторное утверждение" else null)
                    ps.setLong(3, cur.pk)
                    ps.executeUpdate()
                }
                conn.prepareStatement(
                    """INSERT INTO objects(id, type, version, status, doc, valid_from, supersedes, created_by, project_id)
                       VALUES (?, ?::object_type, ?, ?::lifecycle, ?::jsonb, ?, ?, ?, ?)
                       RETURNING $COLUMNS"""
                ).use { ps ->
                    ps.setString(1, cur.id)
                    ps.setString(2, cur.type)
                    ps.setString(3, cur.version)
                    ps.setString(4, target.name)
                    ps.setString(5, mapper.writeValueAsString(newDoc))
                    ps.setObject(6, at)
                    ps.setLong(7, cur.pk)
                    ps.setString(8, createdBy)
                    ps.setString(9, cur.projectId)
                    ps.executeQuery().use { rs -> rs.next(); rs.toStoredObject() }
                }
            }
        }
    }

    /**
     * Отмена объекта (шаг 15): статус `Cancelled`, объект ОСТАЁТСЯ в хранилище.
     * Настоящего удаления нет: на объект могут ссылаться, и удаление рвёт нить
     * трассировки молча. В отличие от [transition], версия увеличивается —
     * отмена есть изменение содержания, а не только состояния.
     */
    fun cancel(
        id: String,
        createdBy: String = "system",
        at: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
        baseVersion: String? = null,
    ): StoredObject {
        val cur = current(id) ?: throw NoSuchElementException("object '$id' has no current version")
        if (baseVersion != null && baseVersion != cur.version) {
            throw conflictWith(cur, baseVersion, cur.doc)
        }
        if (cur.status == Lifecycle.Baseline) {
            throw BaselineChangeException(
                "TZ-COM-003: object '$id' is baselined; changes go through the change procedure"
            )
        }
        val newDoc = cur.doc.deepCopy<JsonNode>()
        val bumped = bumpVersion(cur.version)
        (newDoc as? com.fasterxml.jackson.databind.node.ObjectNode)?.withObject("/lifecycle")
            ?.put("status", Lifecycle.Cancelled.name)?.put("version", bumped)
        return mappingConstraints {
            conn.tx {
                conn.prepareStatement("UPDATE objects SET valid_to = ? WHERE pk = ? AND valid_to IS NULL")
                    .use { ps ->
                        ps.setObject(1, at)
                        ps.setLong(2, cur.pk)
                        ps.executeUpdate()
                    }
                conn.prepareStatement(
                    """INSERT INTO objects(id, type, version, status, doc, valid_from, supersedes, created_by, project_id)
                       VALUES (?, ?::object_type, ?, 'Cancelled'::lifecycle, ?::jsonb, ?, ?, ?, ?)
                       RETURNING $COLUMNS"""
                ).use { ps ->
                    ps.setString(1, cur.id)
                    ps.setString(2, cur.type)
                    ps.setString(3, bumped)
                    ps.setString(4, mapper.writeValueAsString(newDoc))
                    ps.setObject(5, at)
                    ps.setLong(6, cur.pk)
                    ps.setString(7, createdBy)
                    ps.setString(8, cur.projectId)
                    ps.executeQuery().use { rs -> rs.next(); rs.toStoredObject() }
                }
            }
        }
    }

    /**
     * Предыдущая версия объекта — вход для отмены действия из интерфейса
     * (шаг 15 §1.4). Единственная версия отмены не имеет: откатывать не к чему.
     */
    fun previous(id: String): StoredObject? = history(id).let { if (it.size < 2) null else it[it.size - 2] }

    /** Отказ с тем, что нужно инженеру: чужая версия, её автор и чужие значения полей. */
    private fun conflictWith(cur: StoredObject, base: String, attempted: JsonNode): VersionConflictException {
        val fields = attempted.properties().map { it.key }.filter { it != "id" }
        return VersionConflictException(
            id = cur.id,
            yourBase = base,
            currentVersion = cur.version,
            changedBy = cur.createdBy,
            theirValues = fields.associateWith { cur.doc.path(it) },
        )
    }

    /**
     * «1» → «2», «0.1» → «0.2»: увеличивается последний числовой сегмент версии.
     * Открыто наружу с шага 15: правка через интерфейс держит `lifecycle.version`
     * документа в согласии с колонкой версии, а не заводит второй счёт.
     */
    fun bumpVersion(version: String): String {
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
        projectId = getString("project_id"),
    )

    companion object {
        /**
         * Контейнер переходного режима (ADR-022): пока портфель состоит из одного
         * проекта, вызовы без явного проекта пишут в него. Граница API всегда
         * передаёт проект явно; умолчание — для внутренних вызовов и тестов.
         */
        const val DEFAULT_PROJECT = "PJ-0001"
        /** Область библиотеки (СТРУКТУРА-БИБЛИОТЕКИ §4): переиспользуемое
            между проектами; ссылки проект → библиотека законны. */
        const val LIBRARY_PROJECT = "LIB"

        private const val COLUMNS =
            "pk, id, type::text AS type, version, status::text AS status, doc::text AS doc, " +
                "valid_from, valid_to, supersedes, change_ref, created_by, project_id"
    }
}
