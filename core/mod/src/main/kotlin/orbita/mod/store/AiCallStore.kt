// Журнал вызовов службы ИИ (П5): задание, профиль@версия, транспорт, модель,
// промпт, ответ, стоимость, что отфильтровано и что акцептовано.
//
// Вызов, о котором нечего рассказать, неотличим от невыполненного: без журнала
// на вопрос «сколько и почём» ответить нечем, а «служба предложила» становится
// словами без следа.
package orbita.mod.store

import java.math.BigDecimal
import java.sql.Connection
import java.time.OffsetDateTime

data class AiCall(
    val pk: Long,
    val at: OffsetDateTime,
    val projectId: String,
    val kind: String,
    val profileId: String?,
    val profileVersion: String?,
    val transport: String,
    val model: String?,
    val prompt: String,
    val response: String?,
    val failure: String?,
    val tokensIn: Int?,
    val tokensOut: Int?,
    val costUsd: BigDecimal?,
    val proposed: Int,
    val filtered: Int,
    val noSource: Int,
    val accepted: Int,
    val acceptedBy: String?,
    val createdBy: String,
)

class AiCallStore(private val conn: Connection) {

    /** Запись вызова; возвращает pk — по нему акцепт дописывает свой счёт. */
    fun record(
        projectId: String,
        kind: String,
        transport: String,
        prompt: String,
        createdBy: String,
        profileId: String? = null,
        profileVersion: String? = null,
        model: String? = null,
        response: String? = null,
        failure: String? = null,
        tokensIn: Int? = null,
        tokensOut: Int? = null,
        costUsd: BigDecimal? = null,
        proposed: Int = 0,
        filtered: Int = 0,
        noSource: Int = 0,
    ): Long = conn.prepareStatement(
        """INSERT INTO ai_calls(project_id, kind, profile_id, profile_version, transport, model,
                                prompt, response, failure, tokens_in, tokens_out, cost_usd,
                                proposed, filtered, no_source, created_by)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING pk""",
    ).use { ps ->
        ps.setString(1, projectId)
        ps.setString(2, kind)
        ps.setString(3, profileId)
        ps.setString(4, profileVersion)
        ps.setString(5, transport)
        ps.setString(6, model)
        ps.setString(7, prompt)
        ps.setString(8, response)
        ps.setString(9, failure)
        setIntOrNull(ps, 10, tokensIn)
        setIntOrNull(ps, 11, tokensOut)
        ps.setBigDecimal(12, costUsd)
        ps.setInt(13, proposed)
        ps.setInt(14, filtered)
        ps.setInt(15, noSource)
        ps.setString(16, createdBy)
        ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
    }

    /** Акцепт дописывается к вызову: сколько из предложенного дошло до модели. */
    fun markAccepted(pk: Long, accepted: Int, by: String) {
        conn.prepareStatement(
            "UPDATE ai_calls SET accepted = accepted + ?, accepted_by = ? WHERE pk = ?",
        ).use { ps ->
            ps.setInt(1, accepted)
            ps.setString(2, by)
            ps.setLong(3, pk)
            ps.executeUpdate()
        }
    }

    /** Журнал проекта, свежие сверху. */
    fun list(projectId: String, limit: Int = 100): List<AiCall> =
        conn.prepareStatement(
            "SELECT $COLUMNS FROM ai_calls WHERE project_id = ? ORDER BY at DESC, pk DESC LIMIT ?",
        ).use { ps ->
            ps.setString(1, projectId)
            ps.setInt(2, limit)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toCall()) } }
        }

    /** Свод «сколько и почём» по проекту. */
    fun totals(projectId: String): Map<String, Number> =
        conn.prepareStatement(
            """SELECT count(*), coalesce(sum(proposed), 0), coalesce(sum(filtered), 0),
                      coalesce(sum(no_source), 0), coalesce(sum(accepted), 0),
                      coalesce(sum(tokens_in), 0), coalesce(sum(tokens_out), 0),
                      coalesce(sum(cost_usd), 0)
                 FROM ai_calls WHERE project_id = ?""",
        ).use { ps ->
            ps.setString(1, projectId)
            ps.executeQuery().use { rs ->
                rs.next()
                linkedMapOf(
                    "calls" to rs.getLong(1),
                    "proposed" to rs.getLong(2),
                    "filtered" to rs.getLong(3),
                    "no_source" to rs.getLong(4),
                    "accepted" to rs.getLong(5),
                    "tokens_in" to rs.getLong(6),
                    "tokens_out" to rs.getLong(7),
                    "cost_usd" to (rs.getBigDecimal(8) ?: BigDecimal.ZERO),
                )
            }
        }

    private fun setIntOrNull(ps: java.sql.PreparedStatement, index: Int, value: Int?) {
        if (value == null) ps.setNull(index, java.sql.Types.INTEGER) else ps.setInt(index, value)
    }

    private fun java.sql.ResultSet.toCall() = AiCall(
        pk = getLong("pk"),
        at = getObject("at", OffsetDateTime::class.java),
        projectId = getString("project_id"),
        kind = getString("kind"),
        profileId = getString("profile_id"),
        profileVersion = getString("profile_version"),
        transport = getString("transport"),
        model = getString("model"),
        prompt = getString("prompt"),
        response = getString("response"),
        failure = getString("failure"),
        tokensIn = getObject("tokens_in") as? Int,
        tokensOut = getObject("tokens_out") as? Int,
        costUsd = getBigDecimal("cost_usd"),
        proposed = getInt("proposed"),
        filtered = getInt("filtered"),
        noSource = getInt("no_source"),
        accepted = getInt("accepted"),
        acceptedBy = getString("accepted_by"),
        createdBy = getString("created_by"),
    )

    private companion object {
        const val COLUMNS =
            "pk, at, project_id, kind, profile_id, profile_version, transport, model, prompt, " +
                "response, failure, tokens_in, tokens_out, cost_usd, proposed, filtered, " +
                "no_source, accepted, accepted_by, created_by"
    }
}
