// Результаты моделирования: привязка к сценарию, версиям входов и зерну ГПСЧ
// (TZ-COM-006). Результат без зерна невоспроизводим и не сохраняется (NOT NULL в DDL).
package orbita.mod.store

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.Connection
import java.time.OffsetDateTime

data class StoredResult(
    val pk: Long,
    val scenarioId: String,
    val kind: String,
    val payload: JsonNode,
    val inputVersions: Map<String, String>,
    val moduleVersion: String,
    val rngSeed: Long,
    val stale: Boolean,
    val computedAt: OffsetDateTime,
)

class ResultStore(private val conn: Connection, private val mapper: ObjectMapper = ObjectMapper()) {

    fun insert(
        scenarioId: String,
        kind: String,
        payload: JsonNode,
        inputVersions: Map<String, String>,
        moduleVersion: String,
        rngSeed: Long,
    ): StoredResult = mappingConstraints {
        conn.prepareStatement(
            """INSERT INTO results(scenario_id, kind, payload, input_versions, module_version, rng_seed)
               VALUES (?, ?, ?::jsonb, ?::jsonb, ?, ?)
               RETURNING $COLUMNS"""
        ).use { ps ->
            ps.setString(1, scenarioId)
            ps.setString(2, kind)
            ps.setString(3, mapper.writeValueAsString(payload))
            ps.setString(4, mapper.writeValueAsString(inputVersions))
            ps.setString(5, moduleVersion)
            ps.setLong(6, rngSeed)
            ps.executeQuery().use { rs -> rs.next(); rs.toResult() }
        }
    }

    fun byPk(pk: Long): StoredResult? =
        conn.prepareStatement("SELECT $COLUMNS FROM results WHERE pk = ?").use { ps ->
            ps.setLong(1, pk)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toResult() else null }
        }

    /** Актуальные (не stale) результаты сценария — вход последующих модулей. */
    fun activeForScenario(scenarioId: String, kind: String? = null): List<StoredResult> =
        conn.prepareStatement(
            "SELECT $COLUMNS FROM results WHERE scenario_id = ? AND NOT stale" +
                (if (kind != null) " AND kind = ?" else "") + " ORDER BY pk"
        ).use { ps ->
            ps.setString(1, scenarioId)
            if (kind != null) ps.setString(2, kind)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toResult()) } }
        }

    /** Отчёт «устаревшие результаты» (TZ-MOD-005). */
    fun staleReport(): List<StoredResult> = conn.createStatement().use { st ->
        st.executeQuery("SELECT $COLUMNS FROM results WHERE stale ORDER BY pk").use { rs ->
            buildList { while (rs.next()) add(rs.toResult()) }
        }
    }

    private fun java.sql.ResultSet.toResult() = StoredResult(
        pk = getLong("pk"),
        scenarioId = getString("scenario_id"),
        kind = getString("kind"),
        payload = mapper.readTree(getString("payload")),
        inputVersions = mapper.readTree(getString("input_versions")).let { node ->
            buildMap { node.fields().forEach { (k, v) -> put(k, v.asText()) } }
        },
        moduleVersion = getString("module_version"),
        rngSeed = getLong("rng_seed"),
        stale = getBoolean("stale"),
        computedAt = getObject("computed_at", OffsetDateTime::class.java),
    )

    private companion object {
        const val COLUMNS = "pk, scenario_id, kind, payload::text AS payload, " +
            "input_versions::text AS input_versions, module_version, rng_seed, stale, computed_at"
    }
}
