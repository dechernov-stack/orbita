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
    private val требуютОбоснования = setOf(
        "derives_from", "satisfied_by", "realized_by", "verified_by", "conflicts_with",
        "external_identity", "deployed_on",
    )

    // Реестр типов из СХЕМЫ-ПОЛЕЙ-V2 (вид `link`). Произвольных связей не
    // бывает: чего нет в списке — того нет и в модели.
    private val известные = setOf(
        "owns", "covers", "derives_from", "allocated_to", "satisfied_by",
        "realized_by", "verified_by", "constrains", "refines", "instantiates",
        "allocation", "flows_over", "illustrated_by", "traced_to", "verifies",
        "conflicts_with", "snapshot_of", "input_of", "output_of", "pairs_with",
        "external_identity", "deployed_on", "uses_item", "depends_on_technology",
        "derived_from_fact", "about", "supports", "contradicts", "supersedes", "confirms",
    )

    /** Виды уточнения `derives_from`: декомпозиция, вывод, уточнение. */
    private val видыУточнения = setOf("decomposition", "derivation", "refinement")

    override fun link(
        type: String,
        from: String,
        to: String,
        provenance: Provenance,
        rationale: String?,
        subtype: String?,
    ): Link {
        require(type in известные) {
            "связь «$type» не описана в реестре связей — произвольных связей не бывает"
        }
        require(type !in требуютОбоснования || !rationale.isNullOrBlank()) {
            "связь «$type» требует обоснования: связь без причины неотличима от случайной"
        }
        require(subtype == null || subtype in видыУточнения) {
            "вид уточнения «$subtype» неизвестен: ожидается один из $видыУточнения"
        }
        val id = "lnk-${UUID.randomUUID().toString().take(8)}"
        conn.prepareStatement(
            """
            INSERT INTO orbita_kernel.link
                (id, link_type, from_id, to_id, rationale, prov_channel, prov_author, subtype)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { st ->
            st.setString(1, id)
            st.setString(2, type)
            st.setString(3, from)
            st.setString(4, to)
            st.setString(5, rationale)
            st.setString(6, provenance.channel.name.lowercase())
            st.setString(7, provenance.author)
            st.setString(8, subtype)
            st.executeUpdate()
        }
        return Link(id, type, from, to, rationale, provenance, subtype)
    }

    override fun byId(id: String): Link? =
        выбрать("SELECT * FROM orbita_kernel.link WHERE id = ? AND valid_to IS NULL", id, null).firstOrNull()

    override fun confirm(id: String, by: String): Link {
        conn.prepareStatement(
            "UPDATE orbita_kernel.link SET confirmed_by = ?, confirmed_at = now() " +
                "WHERE id = ? AND valid_to IS NULL",
        ).use { st ->
            st.setString(1, by)
            st.setString(2, id)
            require(st.executeUpdate() == 1) { "связи «$id» нет: подтверждать нечего" }
        }
        return byId(id)!!
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
        subtype = rs.getString("subtype"),
        confirmedBy = rs.getString("confirmed_by"),
        confirmedAt = rs.getTimestamp("confirmed_at")?.toInstant()?.toString(),
    )
}
