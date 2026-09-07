// Маршруты документов (ЗАДАНИЕ-ДОКУМЕНТЫ-СЕЙЧАС §4).
//
// Тонкий файл: полноту, запросы и печать считает модуль documents, здесь
// только перевод HTTP в порт. Печать уходит БАЙТАМИ — это единственный
// ответ роутера, который не JSON, и он объявлен явно.
package orbita.api.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.documents.api.DocumentBaseline
import orbita.documents.api.DocumentView
import orbita.documents.api.Documents
import orbita.documents.api.SectionView
import orbita.kernel.api.Area
import orbita.kernel.api.EntityStore

class DocRoutes(
    private val store: EntityStore,
    private val documents: Documents,
    private val mapper: ObjectMapper = ObjectMapper(),
    /**
     * Шаблон фазы: в нём объявлены обязательные документы. Без него
     * маршрут завёл бы только отчёт о концепции, и Draft ConOps, который
     * питает сцена 9, не появился бы в проекте никогда.
     */
    private val phaseTemplate: () -> JsonNode = { mapper.createObjectNode() },
) {

    fun handle(method: String, path: String, query: Map<String, String>, body: String?): V2Router.Ответ? = when {
        method == "GET" && path == "/v2/documents" -> список(требуется(query, "project"))

        method == "POST" && path == "/v2/documents" -> завести(требуется(query, "project"), разобрать(body))

        // Подсказка мероприятия разбирается ДО общего маршрута документа:
        // «hints» иначе читается как код шаблона (поймано тестом).
        method == "GET" && path == "/v2/documents/hints" ->
            подсказки(требуется(query, "project"), требуется(query, "scene"))

        method == "GET" && path.matches(Regex("/v2/documents/[a-z_]+")) ->
            один(
                требуется(query, "project"),
                path.removePrefix("/v2/documents/"),
                query["gate"] ?: "MCR",
            )

        method == "POST" && path.matches(Regex("/v2/documents/[a-z_]+/statement")) ->
            тезис(
                требуется(query, "project"),
                path.removePrefix("/v2/documents/").removeSuffix("/statement"),
                разобрать(body),
            )

        method == "GET" && path.matches(Regex("/v2/documents/[a-z_]+/print")) ->
            печать(требуется(query, "project"), path.removePrefix("/v2/documents/").removeSuffix("/print"))

        // Базирование документа и расхождение с базовой линией.
        method == "POST" && path.matches(Regex("/v2/documents/[a-z_]+/baseline")) ->
            базировать(
                требуется(query, "project"),
                path.removePrefix("/v2/documents/").removeSuffix("/baseline"),
                разобрать(body),
            )

        method == "GET" && path.matches(Regex("/v2/documents/[a-z_]+/baselines")) ->
            линии(требуется(query, "project"), path.removePrefix("/v2/documents/").removeSuffix("/baselines"))

        method == "GET" && path.matches(Regex("/v2/documents/[a-z_]+/diff")) ->
            расхождение(
                требуется(query, "project"),
                path.removePrefix("/v2/documents/").removeSuffix("/diff"),
                требуется(query, "from"),
                query["to"],
            )

        else -> null
    }

    /**
     * Обязательный документ фазы заводится САМ при первом чтении: MCReport
     * живёт с первой сцены (ЗАДАНИЕ-ДОКУМЕНТЫ §1), и требовать «нажмите
     * завести» — просить работу, которой не должно быть. Повтор ничего не
     * создаёт: `ensure` идемпотентен.
     */
    private fun обеспечить(project: String) {
        val есть = documents.list(project).map { it.template }.toSet()
        // Список обязательных документов — ДАННЫМИ шаблона фазы: добавить
        // документ должно быть можно поставкой, а не правкой продукта.
        val обязательные = phaseTemplate().path("documents")
            .map { it.path("template").asText("") }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("mcreport") }
        обязательные.filterNot { it in есть }
            .forEach { documents.ensure(project, it, "система") }
    }

    private fun список(project: String): V2Router.Ответ {
        обеспечить(project)
        val узел = mapper.createObjectNode()
        val массив = узел.putArray("items")
        documents.list(project).forEach { массив.add(кратко(it)) }
        return V2Router.Ответ(200, узел)
    }

    private fun завести(project: String, тело: ObjectNode): V2Router.Ответ {
        val шаблон = тело.path("template").asText("mcreport")
        val автор = тело.path("author").asText("система")
        return V2Router.Ответ(201, полностью(documents.ensure(project, шаблон, автор)))
    }

    private fun один(project: String, code: String, gate: String): V2Router.Ответ =
        V2Router.Ответ(200, полностью(documents.document(project, code, gate)))

    private fun тезис(project: String, code: String, тело: ObjectNode): V2Router.Ответ {
        val раздел = documents.addStatement(
            project, code,
            section = тело.path("section").asText(),
            text = тело.path("text").asText(""),
            supports = тело.path("supports").map { it.asText() },
            author = тело.path("author").asText("инженер"),
        )
        return V2Router.Ответ(201, разделВид(раздел))
    }

    private fun печать(project: String, code: String): V2Router.Ответ {
        val имя = store.byCode(Area.Project(project), project)?.doc?.path("name")?.asText(project)
            ?: store.list(Area.Project(project), "project").firstOrNull()?.doc?.path("name")?.asText(project)
            ?: project
        val байты = documents.print(project, code, имя)
        return V2Router.Ответ(
            200, mapper.createObjectNode(),
            binary = байты, contentType = "application/pdf", fileName = "$code.pdf",
        )
    }

    private fun подсказки(project: String, scene: String): V2Router.Ответ {
        обеспечить(project)
        val узел = mapper.createObjectNode()
        val массив = узел.putArray("items")
        documents.hintsForScene(project, scene).forEach { п ->
            массив.addObject()
                .put("document", п.document)
                .put("section", п.section)
                .put("section_title", п.sectionTitle)
                .put("elements", п.elements)
                .put("filled", п.filled)
        }
        return V2Router.Ответ(200, узел)
    }

    private fun кратко(вид: DocumentView): ObjectNode = mapper.createObjectNode()
        .put("code", вид.code)
        .put("title", вид.title)
        .put("standard", вид.standard)
        .put("complete", вид.complete)
        .put("total", вид.total)
        .put("gate", вид.gate)
        .put("not_due_yet", вид.notDueYet)

    private fun полностью(вид: DocumentView): ObjectNode {
        val узел = кратко(вид)
        val разделы = узел.putArray("sections")
        вид.sections.forEach { разделы.add(разделВид(it)) }
        return узел
    }

    private fun базировать(project: String, code: String, тело: ObjectNode): V2Router.Ответ {
        val линия = documents.baseline(
            project, code,
            name = тело.path("name").asText(""),
            author = тело.path("author").asText("Иванов И."),
        )
        return V2Router.Ответ(201, линияВид(линия))
    }

    private fun линии(project: String, code: String): V2Router.Ответ {
        val узел = mapper.createObjectNode()
        val массив = узел.putArray("items")
        documents.baselines(project, code).forEach { массив.add(линияВид(it)) }
        return V2Router.Ответ(200, узел)
    }

    private fun линияВид(линия: DocumentBaseline): ObjectNode = mapper.createObjectNode()
        .put("name", линия.name)
        .put("document", линия.document)
        .put("elements", линия.elements.size)
        .put("by", линия.by)
        .put("at", линия.at)
        .put("tag", линия.tag)
        .put("commit", линия.commit)
        // Отметки может не быть, и об этом говорится вслух: снимок
        // состоялся, а тег — нет, и человек обязан это видеть.
        .put("note", линия.note)

    private fun расхождение(
        project: String,
        code: String,
        from: String,
        to: String?,
    ): V2Router.Ответ {
        val изменения = documents.diff(project, code, from, to)
        val узел = mapper.createObjectNode()
        узел.put("from", from)
        узел.put("to", to ?: "текущее состояние")
        // Узлов, а не строк: «правка одного тезиса — расхождение в одном
        // узле» проверяется этим числом.
        узел.put("nodes", изменения.map { it.mid }.distinct().size)
        val массив = узел.putArray("items")
        изменения.forEach { и ->
            массив.addObject()
                .put("mid", и.mid).put("section", и.section).put("change", и.change)
                .put("field", и.field).put("was", и.was).put("now", и.now)
        }
        return V2Router.Ответ(200, узел)
    }

    private fun разделВид(раздел: SectionView): ObjectNode {
        val узел = mapper.createObjectNode()
        узел.put("no", раздел.no)
        узел.put("title", раздел.title)
        узел.put("complete", раздел.complete)
        узел.put("expected_by", раздел.expectedBy ?: "")
        узел.put("due_now", раздел.dueNow)
        узел.putArray("scenes").also { а -> раздел.scenes.forEach { а.add(it) } }
        узел.putArray("waiting").also { а -> раздел.waiting.forEach { а.add(it) } }
        val элементы = узел.putArray("elements")
        раздел.elements.forEach { э ->
            val у = элементы.addObject()
            у.put("code", э.code)
            у.put("kind", э.kind.name.lowercase())
            у.put("title", э.title)
            у.put("min_rows", э.minRows)
            у.put("satisfied", э.satisfied)
            э.text?.let { у.put("text", it) }
            у.putArray("columns").also { а -> э.columns.forEach { к -> а.add(к.title) } }
            val строки = у.putArray("rows")
            э.rows.forEach { строка -> строки.addArray().also { а -> строка.forEach { з -> а.add(з) } } }
            у.putArray("supports").also { а -> э.supports.forEach { о -> а.add(о) } }
            у.putArray("waiting_scenes").also { а -> э.waitingScenes.forEach { с -> а.add(с) } }
            у.putArray("notes").also { а -> э.notes.forEach { н -> а.add(н) } }
        }
        return узел
    }

    private fun разобрать(body: String?): ObjectNode =
        (mapper.readTree(body ?: "{}") as? ObjectNode) ?: mapper.createObjectNode()

    private fun требуется(query: Map<String, String>, имя: String): String =
        query[имя] ?: throw IllegalArgumentException("нужен параметр «$имя»")
}
