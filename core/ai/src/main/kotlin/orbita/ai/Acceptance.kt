// Акцепт предложений и происхождение (TZ-AI-004). Эталон spec/ai_semantics.py.
//
// До акцепта предложение не участвует в расчётах, выборках и отчётах. Правило
// закреплено не только здесь, но и ограничением БД ai_needs_accept (V001+V006):
// код и хранилище держат его независимо друг от друга.
package orbita.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

const val SOURCE_AI_PROPOSED = "ai_proposed"

/** Пометка предложения источником: пакет, модель, признаки акцепта и правки. */
fun asProposal(
    item: JsonNode,
    packageId: String,
    llm: String,
    mapper: ObjectMapper = ObjectMapper(),
): ObjectNode {
    val out = item.deepCopy<ObjectNode>()
    val prov = out.putObject("provenance")
    prov.put("source", SOURCE_AI_PROPOSED)
    prov.putObject("ai")
        .put("prompt_package_id", packageId)
        .put("llm", llm)
        .put("accepted", false)
        .put("edited", false)
    return out
}

/**
 * Акцепт инженером. [edits] — правка перед акцептом: применяется к объекту
 * и помечается признаком edited. Массового акцепта без просмотра здесь нет
 * намеренно: это был бы обход управления конфигурацией (ловушка 2).
 */
fun accept(proposal: JsonNode, by: String, edits: JsonNode? = null): ObjectNode {
    require(by.isNotBlank()) { "TZ-AI-004: акцепт без автора не принимается" }
    val out = proposal.deepCopy<ObjectNode>()
    edits?.properties()?.forEach { (k, v) -> out.set<ObjectNode>(k, v) }
    val ai = out.path("provenance").path("ai") as ObjectNode
    ai.put("accepted", true)
    ai.put("accepted_by", by)
    ai.put("edited", edits != null && !edits.isEmpty)
    return out
}

/**
 * Участвует ли объект в расчётах, выборках и отчётах. Рукописный ввод —
 * всегда; предложение ИИ — только после акцепта.
 */
fun influencesCalculations(obj: JsonNode): Boolean {
    val prov = obj.path("provenance")
    if (prov.path("source").asText("") != SOURCE_AI_PROPOSED) return true
    return prov.path("ai").path("accepted").asBoolean(false)
}

/** Отчёт «объекты с неакцептованными предложениями» (TZ-AI-004, ACCEPTANCE 2). */
fun pendingProposals(objects: List<JsonNode>): List<String> =
    objects.filterNot { influencesCalculations(it) }
        .map { it.path("id").asText() }
        .sorted()
