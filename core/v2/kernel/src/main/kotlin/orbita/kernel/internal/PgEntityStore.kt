// Хранилище сущностей на Postgres (схема orbita_kernel).
//
// Правка НЕ затирает прошлое: прежняя версия закрывается valid_to, новая
// становится текущей. Ровно одну текущую версию держит частичный уникальный
// индекс — правило живёт в базе, а не в договорённости с вызывающим.
package orbita.kernel.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.Provenance
import java.sql.Connection
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class PgEntityStore(
    private val conn: Connection,
    private val mapper: ObjectMapper = ObjectMapper(),
) : EntityStore {

    override fun create(
        code: String,
        kind: String,
        area: Area,
        bornIn: String?,
        doc: JsonNode,
        provenance: Provenance,
        status: String,
    ): Entity {
        val id = "$kind-${UUID.randomUUID().toString().take(8)}"
        return вставить(id, code, kind, area, bornIn, status, 1, doc, provenance)
    }

    override fun update(id: String, doc: JsonNode, provenance: Provenance, status: String?): Entity {
        val текущая = byId(id) ?: error("сущности «$id» нет — правка невозможна")
        conn.prepareStatement(
            "UPDATE orbita_kernel.entity SET valid_to = now() WHERE id = ? AND valid_to IS NULL",
        ).use { st ->
            st.setString(1, id)
            st.executeUpdate()
        }
        return вставить(
            id, текущая.code, текущая.kind, текущая.area, текущая.bornIn,
            status ?: текущая.status, текущая.version + 1, doc, provenance,
        )
    }

    private fun вставить(
        id: String,
        code: String,
        kind: String,
        area: Area,
        bornIn: String?,
        status: String,
        version: Int,
        doc: JsonNode,
        provenance: Provenance,
    ): Entity {
        conn.prepareStatement(
            """
            INSERT INTO orbita_kernel.entity
                (id, code, kind, area, born_in, status, version, doc,
                 prov_channel, prov_author, prov_source, prov_anchor, prov_fingerprint)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { st ->
            st.setString(1, id)
            st.setString(2, code)
            st.setString(3, kind)
            st.setString(4, area.asText())
            st.setString(5, bornIn)
            st.setString(6, status)
            st.setInt(7, version)
            st.setString(8, mapper.writeValueAsString(doc))
            st.setString(9, provenance.channel.name.lowercase())
            st.setString(10, provenance.author)
            st.setString(11, provenance.source)
            st.setString(12, provenance.anchor)
            st.setString(13, provenance.fingerprint)
            st.executeUpdate()
        }
        return byId(id) ?: error("сущность «$id» не сохранилась")
    }

    override fun byId(id: String): Entity? = выбрать(
        "SELECT * FROM orbita_kernel.entity WHERE id = ? AND valid_to IS NULL",
    ) { it.setString(1, id) }.firstOrNull()

    override fun byCode(area: Area, code: String): Entity? = выбрать(
        "SELECT * FROM orbita_kernel.entity WHERE area = ? AND code = ? AND valid_to IS NULL",
    ) { it.setString(1, area.asText()); it.setString(2, code) }.firstOrNull()

    override fun list(area: Area, kind: String?): List<Entity> =
        if (kind == null) {
            выбрать(
                "SELECT * FROM orbita_kernel.entity WHERE area = ? AND valid_to IS NULL ORDER BY code",
            ) { it.setString(1, area.asText()) }
        } else {
            выбрать(
                "SELECT * FROM orbita_kernel.entity WHERE area = ? AND kind = ? AND valid_to IS NULL ORDER BY code",
            ) { it.setString(1, area.asText()); it.setString(2, kind) }
        }

    override fun history(id: String): List<Entity> = выбрать(
        "SELECT * FROM orbita_kernel.entity WHERE id = ? ORDER BY version",
    ) { it.setString(1, id) }

    private fun выбрать(sql: String, параметры: (java.sql.PreparedStatement) -> Unit): List<Entity> =
        conn.prepareStatement(sql).use { st ->
            параметры(st)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(разобрать(rs)) }
            }
        }

    private fun разобрать(rs: ResultSet): Entity = Entity(
        id = rs.getString("id"),
        code = rs.getString("code"),
        kind = rs.getString("kind"),
        area = Area.of(rs.getString("area")),
        bornIn = rs.getString("born_in"),
        status = rs.getString("status"),
        version = rs.getInt("version"),
        provenance = Provenance(
            channel = Channel.valueOf(rs.getString("prov_channel").uppercase()),
            author = rs.getString("prov_author"),
            source = rs.getString("prov_source"),
            anchor = rs.getString("prov_anchor"),
            fingerprint = rs.getString("prov_fingerprint"),
        ),
        doc = mapper.readTree(rs.getString("doc")),
        createdAt = rs.getTimestamp("created_at").toInstant().atOffset(ZoneOffset.UTC),
        updatedAt = rs.getTimestamp("valid_from").toInstant().atOffset(ZoneOffset.UTC),
    )
}
