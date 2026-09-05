// Реестр связей на Postgres. Тип связи известен заранее (МОДЕЛЬ-ДАННЫХ §4);
// часть типов требует обоснования — связь без причины неотличима от случайной.
package orbita.kernel.internal

import orbita.kernel.api.Link
import orbita.kernel.api.LinkRegistry
import orbita.kernel.api.Provenance
import orbita.kernel.api.Channel
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

class PgLinkRegistry(private val conn: Connection) : LinkRegistry {

    /** Типы связей и требование обоснования — из реестра связей модели данных. */
    private val требуютОбоснования = setOf("derives_from", "satisfied_by", "realized_by", "verified_by")

    private val известные = setOf(
        "owns", "covers", "derives_from", "allocated_to", "satisfied_by",
        "realized_by", "verified_by", "constrains", "refines", "instantiates",
    )

    override fun link(type: String, from: String, to: String, provenance: Provenance, rationale: String?): Link {
        require(type in известные) {
            "связь «$type» не описана в реестре связей — произвольных связей не бывает"
        }
        require(type !in требуютОбоснования || !rationale.isNullOrBlank()) {
            "связь «$type» требует обоснования: связь без причины неотличима от случайной"
        }
        val id = "lnk-${UUID.randomUUID().toString().take(8)}"
        conn.prepareStatement(
            """
            INSERT INTO orbita_kernel.link
                (id, link_type, from_id, to_id, rationale, prov_channel, prov_author)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { st ->
            st.setString(1, id)
            st.setString(2, type)
            st.setString(3, from)
            st.setString(4, to)
            st.setString(5, rationale)
            st.setString(6, provenance.channel.name.lowercase())
            st.setString(7, provenance.author)
            st.executeUpdate()
        }
        return Link(id, type, from, to, rationale, provenance)
    }

    override fun unlink(id: String, provenance: Provenance) {
        conn.prepareStatement(
            "UPDATE orbita_kernel.link SET valid_to = now() WHERE id = ? AND valid_to IS NULL",
        ).use { st ->
            st.setString(1, id)
            st.executeUpdate()
        }
    }

    override fun from(id: String, type: String?): List<Link> = выбрать(
        "SELECT * FROM orbita_kernel.link WHERE from_id = ? AND valid_to IS NULL" +
            if (type != null) " AND link_type = ?" else "",
        id, type,
    )

    override fun to(id: String, type: String?): List<Link> = выбрать(
        "SELECT * FROM orbita_kernel.link WHERE to_id = ? AND valid_to IS NULL" +
            if (type != null) " AND link_type = ?" else "",
        id, type,
    )

    private fun выбрать(sql: String, id: String, type: String?): List<Link> =
        conn.prepareStatement(sql).use { st ->
            st.setString(1, id)
            if (type != null) st.setString(2, type)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(разобрать(rs)) }
            }
        }

    private fun разобрать(rs: ResultSet) = Link(
        id = rs.getString("id"),
        type = rs.getString("link_type"),
        from = rs.getString("from_id"),
        to = rs.getString("to_id"),
        rationale = rs.getString("rationale"),
        provenance = Provenance(
            channel = Channel.valueOf(rs.getString("prov_channel").uppercase()),
            author = rs.getString("prov_author"),
        ),
    )
}
