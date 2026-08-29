// Ф-07: замысел миссии собирается ИЗ ДОКУМЕНТОВ. Владелец: «табличка из
// четырёх полей рядом с разобранной запиской — убожество; постановка уже
// приложена и разобрана». Форма рукой остаётся запасным путём, основной —
// сборка по урожаю Д2 и блокам канона.
//
// Здесь: сборка входа для службы (что она видит), приём предложения пакетом
// и укладка принятого в паспорт — вместе с якорями происхождения каждого
// поля, чтобы потом было видно, из какого места документа замысел вырос.
package orbita.com.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

object MissionIntentDraft {

    const val KIND = "mission_intent_from_docs"

    private val mapper = ObjectMapper()
    private val FIELDS = listOf("for_whom", "what", "where", "horizon")

    /**
     * Есть ли из чего собирать: нужен хотя бы один разобранный документ
     * проекта. Без разбора кнопка «собрать из документов» не активна —
     * обещать сборку из ничего нельзя.
     */
    fun readiness(boundary: Boundary, filesDir: String, projectId: String): ObjectNode {
        val docs = boundary.objects.listCurrent(projectId)
            .filter { it.type == "source_document" && it.status.name != "Cancelled" }
        val parsed = docs.filter { DocumentParseStore.mapOf(filesDir, it.id) != null }
        val harvested = parsed.filter { DocumentHarvest.of(filesDir, it.id) != null }
        val out = mapper.createObjectNode()
        out.put("documents", docs.size)
        out.put("parsed", parsed.size)
        out.put("harvested", harvested.size)
        out.put("can_compose", parsed.isNotEmpty())
        out.put(
            "why",
            when {
                docs.isEmpty() -> "материалов в проекте нет — замысел пишется рукой"
                parsed.isEmpty() -> "документы не разобраны — сначала разбор"
                harvested.isEmpty() -> "смыслового разбора нет: соберём по блокам канона, урожай усилит результат"
                else -> "есть разбор и принятый урожай — соберём замысел по ним"
            },
        )
        val list = out.putArray("sources")
        parsed.forEach { sd ->
            list.addObject()
                .put("document", sd.id)
                .put("name", sd.doc.path("name").asText(sd.id))
                .put("harvest", DocumentHarvest.of(filesDir, sd.id) != null)
        }
        return out
    }

    /**
     * Вход операции: карточки документов, их урожай и канон блоками. Служба
     * получает выжимку, а не файлы, — как и весь поток Д1/Д2.
     */
    fun statementOf(boundary: Boundary, filesDir: String, projectId: String): String = buildString {
        val docs = boundary.objects.listCurrent(projectId)
            .filter { it.type == "source_document" && it.status.name != "Cancelled" }
            .filter { DocumentParseStore.mapOf(filesDir, it.id) != null }
        appendLine("МАТЕРИАЛЫ ПРОЕКТА (разобранные):")
        docs.forEach { sd ->
            appendLine("— ${sd.id} «${sd.doc.path("name").asText(sd.id)}», тип ${sd.doc.path("kind").asText("")}")
        }
        docs.forEach { sd ->
            val harvest = DocumentHarvest.of(filesDir, sd.id)
            if (harvest != null) {
                appendLine()
                appendLine("УРОЖАЙ РАЗБОРА ${sd.id} (кандидаты с координатами):")
                harvest.path("items").forEach { item ->
                    val text = item.path("statement").asText("").ifBlank { item.path("name").asText("") }
                    val blocks = DocumentHarvest.blocksOf(item).joinToString(", ")
                    val mark = item.path("source_mark").asText("")
                    appendLine(
                        "— ${item.path("class").asText()}: $text" +
                            (if (mark.isNotBlank()) " [$mark]" else "") + " [$blocks]",
                    )
                }
            }
            val canon = DocumentParseStore.canonOf(filesDir, sd.id)
            if (canon != null) {
                appendLine()
                appendLine("КАНОН ${sd.id} (блоки с якорями):")
                appendLine(canon)
            }
        }
    }

    /** Принятое предложение → поля паспорта: текст и якоря происхождения. */
    fun applyTo(passport: JsonNode, draft: JsonNode): ObjectNode {
        val intent = mapper.createObjectNode()
        val sources = mapper.createObjectNode()
        FIELDS.forEach { field ->
            val node = draft.path("intent").path(field)
            val text = node.path("text").asText("").trim()
            if (text.isNotEmpty()) {
                intent.put(field, text)
                val anchors = node.path("anchors").mapNotNull { it.asText().takeIf(String::isNotBlank) }
                if (anchors.isNotEmpty()) {
                    val arr = sources.putArray(field)
                    anchors.forEach { arr.add(it) }
                }
            }
        }
        // прежний замысел не затирается молча: незаполненные поля остаются
        passport.path("mission_intent").takeIf { it.isObject }?.let { old ->
            FIELDS.forEach { field ->
                if (!intent.has(field)) {
                    old.path(field).asText("").takeIf { it.isNotBlank() }?.let { intent.put(field, it) }
                }
            }
            old.path("text").asText("").takeIf { it.isNotBlank() }?.let { intent.put("text", it) }
        }
        if (!sources.isEmpty) intent.set<ObjectNode>("sources", sources)
        return intent
    }

    /** Проверка предложения нормативной схемой — чужая форма внутрь не идёт. */
    fun problems(boundary: Boundary, draft: JsonNode): List<String> =
        boundary.schemaProblems("core/mission-intent-draft", draft).map { "${it.path}: ${it.message}" }

    fun toJson(intent: ObjectNode): ObjectNode = intent.deepCopy()

    fun anchorsOf(draft: JsonNode): ArrayNode {
        val out = mapper.createArrayNode()
        FIELDS.forEach { field ->
            draft.path("intent").path(field).path("anchors").forEach { out.add(it.asText()) }
        }
        return out
    }
}
