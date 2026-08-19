// Связи трассировки: направление хранится один раз, обратный обход вычисляется
// (ADR-011, STEP-1 ловушка 1). Запросы 1–4 из db/queries.sql (TZ-COM-002, TZ-REQ-003/005).
package orbita.mod.store

import java.sql.Connection

/** Узел обхода трассировки: идентификатор и минимальная глубина от исходного объекта. */
data class TraceHop(val id: String, val depth: Int)

/**
 * Связь цифровой нити. Класс потребителя обязателен для «сервис → требование»
 * (Р9, TZ-REQ-003); вид распределения и обоснование — для allocation (CR-001/ADR-017).
 */
data class Link(
    val fromId: String,
    val toId: String,
    val kind: String,
    val consumerClass: String?,
    val allocationKind: String? = null,
    val rationale: String? = null,
)

class LinkStore(private val conn: Connection) {

    fun add(
        fromId: String,
        toId: String,
        kind: String = "trace",
        consumerClass: String? = null,
        allocationKind: String? = null,
        rationale: String? = null,
    ): Unit = mappingConstraints {
        conn.prepareStatement(
            """INSERT INTO links(from_id, to_id, kind, consumer_class, allocation_kind, rationale)
               VALUES (?, ?, ?::link_kind, ?, ?, ?)"""
        ).use { ps ->
            ps.setString(1, fromId)
            ps.setString(2, toId)
            ps.setString(3, kind)
            ps.setString(4, consumerClass)
            ps.setString(5, if (kind == "allocation") allocationKind else null)
            ps.setString(6, rationale)
            ps.executeUpdate()
        }
    }

    fun linksFrom(id: String, kind: String? = null): List<Link> = select("from_id", id, kind)

    fun linksTo(id: String, kind: String? = null): List<Link> = select("to_id", id, kind)

    fun list(kind: String? = null): List<Link> =
        conn.prepareStatement(
            "SELECT from_id, to_id, kind::text, consumer_class, allocation_kind, rationale FROM links" +
                (if (kind != null) " WHERE kind = ?::link_kind" else "") + " ORDER BY from_id, to_id"
        ).use { ps ->
            if (kind != null) ps.setString(1, kind)
            ps.executeQuery().use { rs -> rs.collectLinks() }
        }

    private fun java.sql.ResultSet.collectLinks(): List<Link> = buildList {
        while (next()) {
            add(Link(getString(1), getString(2), getString(3), getString(4), getString(5), getString(6)))
        }
    }

    private fun select(column: String, id: String, kind: String?): List<Link> =
        conn.prepareStatement(
            "SELECT from_id, to_id, kind::text, consumer_class, allocation_kind, rationale FROM links WHERE $column = ?" +
                (if (kind != null) " AND kind = ?::link_kind" else "") + " ORDER BY from_id, to_id"
        ).use { ps ->
            ps.setString(1, id)
            if (kind != null) ps.setString(2, kind)
            ps.executeQuery().use { rs -> rs.collectLinks() }
        }

    fun count(): Long = conn.createStatement().use { st ->
        st.executeQuery("SELECT count(*) FROM links").use { rs -> rs.next(); rs.getLong(1) }
    }

    /** Запрос 1: предки объекта — обход вверх до нужд. Один запрос на весь обход (TZ-COM-002). */
    fun ancestors(id: String): List<TraceHop> = traverse(id, up = true)

    /** Запрос 2: потомки объекта — обход вниз до свидетельств. */
    fun descendants(id: String): List<TraceHop> = traverse(id, up = false)

    private fun traverse(id: String, up: Boolean): List<TraceHop> {
        // Направления различаются стартовым условием и стороной соединения;
        // защита от циклов — ограничением глубины, как в db/queries.sql.
        val sql = if (up) """
            WITH RECURSIVE up AS (
                SELECT from_id, to_id, 1 AS depth FROM links WHERE to_id = ? AND kind = 'trace'
                UNION ALL
                SELECT l.from_id, l.to_id, up.depth + 1
                  FROM links l JOIN up ON l.to_id = up.from_id
                 WHERE l.kind = 'trace' AND up.depth < 32
            )
            SELECT DISTINCT from_id AS node, min(depth) AS depth FROM up GROUP BY from_id ORDER BY depth, node
        """ else """
            WITH RECURSIVE down AS (
                SELECT from_id, to_id, 1 AS depth FROM links WHERE from_id = ? AND kind = 'trace'
                UNION ALL
                SELECT l.from_id, l.to_id, down.depth + 1
                  FROM links l JOIN down ON l.from_id = down.to_id
                 WHERE l.kind = 'trace' AND down.depth < 32
            )
            SELECT DISTINCT to_id AS node, min(depth) AS depth FROM down GROUP BY to_id ORDER BY depth, node
        """
        return conn.prepareStatement(sql).use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(TraceHop(rs.getString("node"), rs.getInt("depth"))) }
            }
        }
    }

    /** Запрос 3: разрывы трассировки — требования без источника (TZ-REQ-003). */
    fun traceBreaks(): List<String> = conn.createStatement().use { st ->
        st.executeQuery(
            """SELECT o.id FROM objects o
                WHERE o.valid_to IS NULL AND o.type = 'requirement' AND o.status <> 'Cancelled'
                  AND NOT EXISTS (SELECT 1 FROM links l WHERE l.to_id = o.id AND l.kind = 'trace')
                ORDER BY o.id"""
        ).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }

    /** Запрос 4: нераспределённые системные требования (TZ-REQ-005). */
    fun unallocatedSystemRequirements(): List<String> = conn.createStatement().use { st ->
        st.executeQuery(
            """SELECT o.id FROM objects o
                WHERE o.valid_to IS NULL AND o.type = 'requirement' AND o.doc->>'level' = 'system'
                  AND NOT EXISTS (SELECT 1 FROM links l WHERE l.from_id = o.id AND l.kind = 'allocation')
                ORDER BY o.id"""
        ).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }
}
