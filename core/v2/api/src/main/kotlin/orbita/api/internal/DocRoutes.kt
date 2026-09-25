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
    /** Связный текст и печать фоновыми заданиями (шип 4 §4); null — только синхронно. */
    private val jobs: DocumentJobs? = null,
    /**
     * Шаблон фазы: в нём объявлены обязательные документы. Без него
     * маршрут завёл бы только отчёт о концепции, и Draft ConOps, который
     * питает сцена 9, не появился бы в проекте никогда. Последним параметром —
     * чтобы вызовы с лямбдой в хвосте остались как были.
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
            требуется(query, "project").let { проект ->
                // Ступень по умолчанию — БЛИЖАЙШАЯ НЕПРОЙДЕННАЯ точка: считать
                // полноту к пройденной MCR значило бы мерить документ обзором,
                // который позади (владелец 21.09: «3 из 5 — непонятно»).
                один(проект, path.removePrefix("/v2/documents/"), query["gate"] ?: documents.gateAhead(проект))
            }

        method == "POST" && path.matches(Regex("/v2/documents/[a-z_]+/statement")) ->
            тезис(
                требуется(query, "project"),
                path.removePrefix("/v2/documents/").removeSuffix("/statement"),
                разобрать(body),
            )

        method == "GET" && path.matches(Regex("/v2/documents/[a-z_]+/print")) ->
            печать(требуется(query, "project"), path.removePrefix("/v2/documents/").removeSuffix("/print"), query["engine"].orEmpty(), query["job"])

        // Печать фоновым заданием: вид собирается сразу, байты — в фоне; забрать — тем же /print с job=.
        method == "POST" && path.matches(Regex("/v2/documents/[a-z_]+/print/jobs")) ->
            печатьФоном(требуется(query, "project"), path.removePrefix("/v2/documents/").removeSuffix("/print/jobs"), разобрать(body))

        method == "GET" && path.matches(Regex("/v2/documents/[a-z_]+/jobs/DJ-[0-9]+")) ->
            задание(требуется(query, "project"), path.substringAfterLast("/jobs/"))

        // Рецензия патчами: правка изложения поверх текста модели; «принять как есть».
        method == "POST" && path.matches(Regex("/v2/documents/[a-z_]+/review")) ->
            рецензия(требуется(query, "project"), path.removePrefix("/v2/documents/").removeSuffix("/review"), разобрать(body))

        method == "POST" && path.matches(Regex("/v2/documents/[a-z_]+/renderings/accept")) ->
            принятьТекст(требуется(query, "project"), path.removePrefix("/v2/documents/").removeSuffix("/renderings/accept"), разобрать(body))

        // Базирование документа и расхождение с базовой линией.
        method == "POST" && path.matches(Regex("/v2/documents/[a-z_]+/baseline")) ->
            базировать(
                требуется(query, "project"),
                path.removePrefix("/v2/documents/").removeSuffix("/baseline"),
                разобрать(body),
            )

        method == "GET" && path.matches(Regex("/v2/documents/[a-z_]+/baselines")) ->
            линии(требуется(query, "project"), path.removePrefix("/v2/documents/").removeSuffix("/baselines"))

        // «Написать связно»: раздел прозой живой модели.
        method == "POST" && path.matches(Regex("/v2/documents/[a-z_]+/write")) ->
            написать(
                требуется(query, "project"),
                path.removePrefix("/v2/documents/").removeSuffix("/write"),
                разобрать(body),
            )

        method == "GET" && path.matches(Regex("/v2/documents/[a-z_]+/renderings")) ->
            тексты(требуется(query, "project"), path.removePrefix("/v2/documents/").removeSuffix("/renderings"))

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

    private fun имяПроекта(project: String): String =
        store.byCode(Area.Project(project), project)?.doc?.path("name")?.asText(project)
            ?: store.list(Area.Project(project), "project").firstOrNull()?.doc?.path("name")?.asText(project)
            ?: project

    /** Печать: движком по запросу (typst · pdfbox · auto) либо готовым фоновым заданием (`job=`). */
    private fun печать(project: String, code: String, engine: String, job: String?): V2Router.Ответ {
        val напечатано = if (job != null) {
            val очередь = jobs ?: throw IllegalStateException("фоновая печать на этом стенде не подключена")
            val з = очередь.poll(project, job) ?: throw NoSuchElementException("задания печати «$job» нет")
            if (з.status == "failed") throw IllegalStateException(з.error ?: "печать не состоялась")
            очередь.printed(project, job) ?: throw IllegalStateException("печать ещё идёт: ${з.elapsedSeconds} с — спросите задание позже")
        } else documents.printWith(project, code, имяПроекта(project), engine)
        return V2Router.Ответ(
            200, mapper.createObjectNode().put("engine", напечатано.engine),
            binary = напечатано.bytes, contentType = "application/pdf", fileName = напечатано.fileName,
        )
    }

    private fun печатьФоном(project: String, code: String, тело: ObjectNode): V2Router.Ответ {
        val очередь = jobs ?: throw IllegalStateException("фоновая печать на этом стенде не подключена")
        val з = очередь.startPrint(project, code, имяПроекта(project), тело.path("engine").asText("auto"))
        return V2Router.Ответ(202, заданиеВид(з))
    }

    private fun задание(project: String, id: String): V2Router.Ответ {
        val очередь = jobs ?: throw IllegalStateException("фоновые задания документов на этом стенде не подключены")
        val з = очередь.poll(project, id) ?: throw NoSuchElementException("задания «$id» нет в проекте $project")
        return V2Router.Ответ(200, заданиеВид(з))
    }

    private fun заданиеВид(з: DocumentJobs.View): ObjectNode {
        val узел = mapper.createObjectNode()
            .put("job", з.id).put("document", з.document).put("kind", з.kind).put("section", з.section)
            .put("status", з.status).put("started_at", з.startedAt).put("elapsed_seconds", з.elapsedSeconds)
            .put("engine", з.engine).put("size", з.size).put("error", з.error)
        з.result?.let { узел.set<ObjectNode>("result", KindJson.текст(mapper, it)) }
        return узел
    }

    private fun рецензия(project: String, code: String, тело: ObjectNode): V2Router.Ответ {
        val текст = documents.review(
            project, code,
            section = тело.path("section").asText(""),
            text = тело.path("text").asText(""),
            author = тело.path("author").asText(""),
        )
        return V2Router.Ответ(if (текст.accepted) 201 else 422, KindJson.текст(mapper, текст))
    }

    private fun принятьТекст(project: String, code: String, тело: ObjectNode): V2Router.Ответ {
        val узел = mapper.createObjectNode()
        val массив = узел.putArray("items")
        documents.acceptRendering(project, code, тело.path("author").asText(""), тело.path("section").asText("").ifBlank { null })
            .forEach { массив.add(KindJson.текст(mapper, it)) }
        return V2Router.Ответ(200, узел)
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
        // Шаблон — чтобы сцена фазы находила СВОЙ документ (SEMP, ConOps, SMA, Project Plan, FA), а не угадывала код.
        .put("template", вид.template)
        .put("title", вид.title)
        .put("standard", вид.standard)
        .put("complete", вид.complete)
        .put("total", вид.total)
        .put("gate", вид.gate)
        .put("not_due_yet", вид.notDueYet)
        .put("baselines", вид.baselines)
        .put("baseline_name", вид.baselineName)
        .put("baseline_at", вид.baselineAt)

    private fun полностью(вид: DocumentView): ObjectNode {
        val узел = кратко(вид)
        val разделы = узел.putArray("sections")
        вид.sections.forEach { разделы.add(разделВид(it)) }
        return узел
    }

    private fun написать(project: String, code: String, тело: ObjectNode): V2Router.Ответ {
        // Фоновой задачей (шип 4 §4): ответ сразу — задание со статусом; ответ из
        // журнала применяется тут же и приходит с результатом.
        if (тело.path("background").asBoolean(false) && jobs != null) {
            val з = jobs.startWrite(project, code, тело.path("section").asText(""), тело.path("author").asText("Иванов И."))
            return V2Router.Ответ(if (з.status == "running") 202 else 201, заданиеВид(з))
        }
        val текст = documents.write(
            project, code,
            section = тело.path("section").asText(""),
            author = тело.path("author").asText("Иванов И."),
        )
        // Отклонённый текст возвращается вместе с причинами — но кодом 422,
        // а не 201: принять его нельзя, а показать человеку нужно, иначе
        // непонятно, что именно модель сочинила.
        return V2Router.Ответ(if (текст.accepted) 201 else 422, KindJson.текст(mapper, текст))
    }

    private fun тексты(project: String, code: String): V2Router.Ответ {
        val узел = mapper.createObjectNode()
        val массив = узел.putArray("items")
        documents.renderings(project, code).forEach { массив.add(KindJson.текст(mapper, it)) }
        return V2Router.Ответ(200, узел)
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
        узел.put("empty_ok_with_statement", раздел.emptyOkWithStatement)
        узел.putArray("scenes").also { а -> раздел.scenes.forEach { а.add(it) } }
        узел.putArray("waiting").also { а -> раздел.waiting.forEach { а.add(it) } }
        val элементы = узел.putArray("elements")
        раздел.elements.forEach { э ->
            val у = элементы.addObject()
            у.put("code", э.code)
            у.put("kind", э.kind.name.lowercase())
            у.put("title", э.title)
            у.put("select", э.select)
            у.put("min_rows", э.minRows)
            у.put("satisfied", э.satisfied)
            у.put("empty_ok_with_statement", э.emptyOkWithStatement)
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
