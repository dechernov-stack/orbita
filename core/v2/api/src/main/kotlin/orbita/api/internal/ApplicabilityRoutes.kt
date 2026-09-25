// Применимость (шип 4 §5): матрица на Постановке и решение по возможности.
// Тонкие маршруты: считает модуль знаний.
package orbita.api.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.knowledge.api.Applicability
import orbita.knowledge.api.ApplicabilityMatrix
import orbita.knowledge.api.ExternalTargetRow
import orbita.knowledge.api.OpportunityRow

class ApplicabilityRoutes(
    private val applicability: Applicability,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    fun handle(method: String, path: String, query: Map<String, String>, body: String?): V2Router.Ответ? {
        РЕШЕНИЕ.matchEntire(path)?.let { if (method == "POST") return решение(требуется(query, "project"), it.groupValues[1], разобрать(body)) }
        return when {
            method == "GET" && path == "/v2/opportunities" -> V2Router.Ответ(200, видМатрицы(applicability.matrix(требуется(query, "project"))))
            else -> null
        }
    }

    private fun решение(project: String, code: String, тело: JsonNode): V2Router.Ответ = V2Router.Ответ(
        200,
        видСтроки(applicability.decide(project, code, тело.path("status").asText(""), тело.path("author").asText("").ifBlank { "инженер" }, тело.path("reason").asText(""))),
    )

    private fun видМатрицы(м: ApplicabilityMatrix): ObjectNode {
        val узел = mapper.createObjectNode().put("project", м.project).put("note", м.note)
        узел.putArray("rows").also { а -> м.rows.forEach { а.add(видСтроки(it)) } }
        узел.putObject("by_verdict").also { в -> м.byVerdict.forEach { (к, n) -> в.put(к, n) } }
        узел.putArray("external_targets").also { а -> м.externalTargets.forEach { а.add(видЧужойЦели(it)) } }
        узел.putArray("charter_boundaries").also { а ->
            м.charterBoundaries.forEach { г -> а.addObject().put("material", г.material).put("anchor", г.anchor).put("text", г.text) }
        }
        return узел
    }

    private fun видСтроки(с: OpportunityRow): ObjectNode {
        val узел = mapper.createObjectNode()
            .put("code", с.code)
            .put("status", с.status)
            .put("verdict", с.verdict)
            .put("verdict_word", с.verdictWord)
            .put("proposal", с.proposal)
            .put("proposal_word", с.proposalWord)
            .put("missing", с.missing)
            .put("rationale", с.rationale)
            .put("owner", с.owner)
            .put("external_item", с.externalItem)
            .put("scale", с.scale)
            .put("confidence", с.confidence)
        с.external?.let { ф ->
            узел.putObject("external")
                .put("code", ф.code)
                .put("subject", ф.subject)
                .put("predicate", ф.predicate)
                .put("value", ф.value)
                .put("quote", ф.quote)
                .put("anchor", ф.anchor)
                .put("material", ф.material)
                .put("material_name", ф.materialName)
                .put("role", ф.role)
        }
        узел.putArray("our_services").also { а -> с.ourServices.forEach { а.add(it) } }
        узел.putArray("our_service_names").also { а -> с.ourServiceNames.forEach { а.add(it) } }
        с.charterBoundary?.let { г -> узел.putObject("charter_boundary").put("material", г.material).put("anchor", г.anchor).put("text", г.text) }
        return узел
    }

    private fun видЧужойЦели(ц: ExternalTargetRow): ObjectNode = mapper.createObjectNode()
        .put("code", ц.code)
        .put("owner", ц.owner)
        .put("statement", ц.statement)
        .put("quote", ц.quote)
        .put("anchor", ц.anchor)
        .put("material", ц.material)
        .put("material_name", ц.materialName)
        .put("disposition", ц.disposition)
        .also { у -> у.putArray("grounds").also { а -> ц.grounds.forEach { а.add(it) } } }

    private fun разобрать(тело: String?): JsonNode =
        if (тело.isNullOrBlank()) mapper.createObjectNode() else mapper.readTree(тело)

    private fun требуется(query: Map<String, String>, имя: String): String =
        query[имя]?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("нужен параметр $имя")

    private companion object {
        val РЕШЕНИЕ: Regex = Regex("/v2/opportunities/([A-Za-z0-9._-]+)/decide")
    }
}
