// Словарь (шип 4 §2, истина `glossary_rule`): термины класса миссии (библиотека)
// и дельта проекта; кандидаты из документов с цитатой; решения человека —
// принять · отклонить · слить синонимом в принятый термин.
package orbita.api.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.kernel.api.Provenance
import orbita.kernel.schema.GeneratedKinds
import orbita.knowledge.api.Glossary

class GlossaryRoutes(
    private val store: EntityStore,
    private val links: LinkRegistry,
    private val glossary: Glossary,
    private val mapper: ObjectMapper,
) {
    private val решение = Regex("/v2/glossary/([A-Za-z0-9._-]+)/(accept|reject|merge)")

    fun handle(method: String, path: String, query: Map<String, String>, body: String?): V2Router.Ответ? = when {
        method == "GET" && path == "/v2/glossary" ->
            список(query["project"]?.ifBlank { null }, query["q"], query["class"], query["status"])
        method == "POST" && path == "/v2/glossary" -> завести(требуется(query), разобрать(body))
        // Стороны и узлы, заведённые до словаря, привязываются по кнопке: термин
        // по имени/коду либо кандидат — так словарь показывает кандидатов и на
        // проекте, где документы читались раньше него (КТ2, 24.09).
        method == "POST" && path == "/v2/glossary/link" -> {
            val итог = glossary.linkProject(Area.Project(требуется(query)), разобрать(body).path("author").asText("").ifBlank { "инженер" })
            V2Router.Ответ(200, mapper.createObjectNode().put("linked", итог.linked).put("candidates", итог.candidates)
                .also { у -> у.putArray("notes").also { м -> итог.notes.forEach { м.add(it) } } }
                .put("note", "привязано записей ${итог.linked}, новых кандидатов ${итог.candidates}"))
        }
        method == "POST" && решение.matches(path) -> {
            val м = решение.matchEntire(path)!!
            решить(требуется(query), м.groupValues[1], м.groupValues[2], разобрать(body))
        }
        else -> null
    }

    private val словаКлассов: Map<String, String> = GeneratedKinds.enumLabels["glossary_term.class"].orEmpty()

    private fun список(проект: String?, запрос: String?, класс: String?, статус: String?): V2Router.Ответ {
        val области = listOf(Area.Library) + listOfNotNull(проект?.let { Area.Project(it) })
        val игла = glossary.flat(запрос.orEmpty())
        val записи = области.flatMap { store.list(it, "glossary_term") }
            .filter { it.status != "cancelled" }
            .filter { класс.isNullOrBlank() || it.doc.path("class").asText() == класс }
            // Устаревшие и слитые — только по явному статусу: словарь показывает живое.
            .filter { if (статус.isNullOrBlank()) it.status != "obsolete" else it.status == статус }
            .filter { игла.isBlank() || совпадает(it, игла) }
            .sortedWith(compareBy({ it.status != "candidate" }, { it.doc.path("class").asText() }, { glossary.flat(it.doc.path("term_ru").asText()) }))
        val ответ = mapper.createObjectNode()
        ответ.putArray("items").also { м -> записи.forEach { м.add(термин(it)) } }
        ответ.putObject("classes").also { к -> словаКлассов.forEach { (код, слово) -> к.put(код, слово) } }
        ответ.put("candidates", записи.count { it.status == "candidate" })
        ответ.put(
            "note",
            if (записи.isEmpty() && игла.isBlank()) "словарь пуст: сид ложится загрузчиком полок (tools/v2/load_shelves.py)"
            else "термин ищется по каноническому имени, синонимам, коду объекта и определению",
        )
        return V2Router.Ответ(200, ответ)
    }

    private fun совпадает(з: Entity, игла: String): Boolean {
        val поля = listOf("term_ru", "term_en", "object_code", "definition").map { з.doc.path(it).asText("") } +
            з.doc.path("synonyms").map { it.asText("") }
        return поля.any { glossary.flat(it).contains(игла) }
    }

    /** Термин от человека: принятый сразу либо кандидат — как он сам назовёт. */
    private fun завести(проект: String, тело: JsonNode): V2Router.Ответ {
        val область = Area.Project(проект)
        val имя = тело.path("term_ru").asText("").trim()
        require(имя.isNotBlank()) { "термину нужно каноническое имя (term_ru)" }
        val класс = тело.path("class").asText("").trim().ifBlank { "se_concept" }
        require(класс in GeneratedKinds.of("glossary_term").enums.getValue("class")) {
            "класса «$класс» у термина нет: " + словаКлассов.entries.joinToString(" · ") { "${it.key} — ${it.value}" }
        }
        glossary.resolve(область, имя, null)?.let { уже ->
            return V2Router.Ответ(409, mapper.createObjectNode()
                .put("error", "термин уже есть: ${уже.code} «${уже.termRu}» — добавьте написание синонимом правкой на месте")
                .put("code", уже.code))
        }
        val статус = тело.path("status").asText("accepted").ifBlank { "accepted" }
        require(статус in setOf("accepted", "candidate")) { "статус нового термина — accepted либо candidate" }
        val документ = тело.deepCopy<ObjectNode>().apply {
            remove(listOf("author", "status", "project", "code", "reason"))
            put("term_ru", имя); put("class", класс)
            if (path("definition").asText("").isBlank()) put("definition", "определение уточняет человек")
        }
        val запись = store.create(следующий(область), "glossary_term", область, null, документ, Provenance(Channel.MANUAL, автор(тело)), status = статус)
        return V2Router.Ответ(201, термин(запись))
    }

    private fun решить(проект: String, код: String, действие: String, тело: JsonNode): V2Router.Ответ {
        val область = Area.Project(проект)
        val запись = store.byCode(область, код) ?: store.byCode(Area.Library, код)
            ?: throw NoSuchElementException("термина «$код» нет ни в проекте, ни в библиотеке")
        require(запись.kind == "glossary_term") { "«$код» — не термин словаря, а ${запись.kind}" }
        val провенанс = Provenance(Channel.MANUAL, автор(тело))
        val причина = тело.path("reason").asText("").trim()
        val итог = when (действие) {
            "accept" -> {
                val документ = запись.doc.deepCopy<ObjectNode>()
                тело.path("definition").asText("").trim().takeIf { it.isNotBlank() }?.let { документ.put("definition", it) }
                тело.path("class").asText("").trim().takeIf { it.isNotBlank() }?.let { документ.put("class", it) }
                store.update(запись.id, документ, провенанс, status = "accepted")
            }
            "reject" -> {
                require(причина.isNotBlank()) { "отклонение кандидата без причины не записывается" }
                val документ = запись.doc.deepCopy<ObjectNode>().put("notes", "отклонён: $причина")
                store.update(запись.id, документ, провенанс, status = "obsolete")
            }
            else -> слить(область, запись, тело.path("into").asText("").trim(), провенанс, причина)
        }
        return V2Router.Ответ(200, термин(итог).put("action", действие))
    }

    /** Кандидат становится синонимом принятого термина; связи «named_by» переезжают. */
    private fun слить(область: Area, кандидат: Entity, куда: String, провенанс: Provenance, причина: String): Entity {
        require(куда.isNotBlank()) { "слить не с чем: назовите принятый термин (into)" }
        val цель = store.byCode(область, куда) ?: store.byCode(Area.Library, куда)
            ?: throw NoSuchElementException("термина «$куда» нет ни в проекте, ни в библиотеке")
        require(цель.kind == "glossary_term" && цель.id != кандидат.id) { "слить можно только в другой термин словаря" }
        val написания = (цель.doc.path("synonyms").map { it.asText() } + кандидат.doc.path("term_ru").asText() +
            кандидат.doc.path("synonyms").map { it.asText() })
            .map { it.trim() }.filter { it.isNotBlank() && glossary.flat(it) != glossary.flat(цель.doc.path("term_ru").asText()) }
            .distinctBy { glossary.flat(it) }
        val документЦели = цель.doc.deepCopy<ObjectNode>()
        документЦели.putArray("synonyms").also { м -> написания.forEach { м.add(it) } }
        val обновлённая = store.update(цель.id, документЦели, провенанс)
        links.to(кандидат.id, "named_by").forEach { связь ->
            if (links.to(цель.id, "named_by").none { it.from == связь.from }) {
                links.link("named_by", связь.from, цель.id, провенанс, rationale = "слито в словаре: ${кандидат.code} → ${цель.code}")
            }
            links.unlink(связь.id, провенанс)
        }
        val документКандидата = кандидат.doc.deepCopy<ObjectNode>()
            .put("notes", "слито в ${цель.code} «${цель.doc.path("term_ru").asText()}»" + (if (причина.isBlank()) "" else ": $причина"))
        store.update(кандидат.id, документКандидата, провенанс, status = "obsolete")
        return обновлённая
    }

    private fun термин(з: Entity): ObjectNode {
        val у = з.doc.deepCopy<ObjectNode>()
        у.put("code", з.code).put("status", з.status).put("id", з.id)
        у.put("area", if (з.area is Area.Library) "library" else "project")
        у.put("class_word", словаКлассов[з.doc.path("class").asText()] ?: з.doc.path("class").asText())
        return у
    }

    private fun следующий(область: Area): String {
        val занятые = store.list(область, "glossary_term").map { it.code }.toSet()
        var n = занятые.size + 1
        while ("GT-P-" + n.toString().padStart(4, '0') in занятые) n += 1
        return "GT-P-" + n.toString().padStart(4, '0')
    }

    private fun автор(тело: JsonNode): String = тело.path("author").asText("").ifBlank { "инженер" }

    private fun разобрать(тело: String?): JsonNode =
        if (тело.isNullOrBlank()) mapper.createObjectNode() else mapper.readTree(тело)

    private fun требуется(query: Map<String, String>): String =
        query["project"]?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("нужен параметр project")
}
