// Маршруты живого поля знаний (ЗАДАНИЕ-ПОЛЕ-ЗНАНИЙ).
//
// Тонкий файл: канон, промпт, вызов и ворота фактов живут в модулях.
// Здесь перевод HTTP в порты — и ни одного правила предметной области.
package orbita.api.internal

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.ai.api.AiService
import orbita.ai.api.Atomize
import orbita.ai.api.ProviderUnavailable
import orbita.knowledge.api.Disposition
import orbita.knowledge.api.Fact
import orbita.knowledge.api.FactIntake
import orbita.knowledge.api.Intake

class KnowledgeRoutes(
    private val intake: Intake,
    private val atomize: Atomize,
    private val service: AiService,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    fun handle(method: String, path: String, query: Map<String, String>, body: String?): V2Router.Ответ? = when {
        // Д1: канон материала — блоки с якорями; по ним факт проверяется
        method == "GET" && path == "/v2/intake/canon" ->
            канон(требуется(query, "project"), требуется(query, "material"))

        // Д2б: живой разбор. Один вызов на версию документа — повтор той же
        // версии возвращает записанный ответ и не звонит.
        method == "POST" && path == "/v2/intake/atomize" ->
            разбор(требуется(query, "project"), разобрать(body))

        method == "GET" && path == "/v2/topics" -> темы(требуется(query, "project"))

        // Ручные тема и факт (замечание прохода 08.09): инженер заводит
        // предмет и утверждение, не дожидаясь разбора. Ручной факт —
        // полноправный, но в доле знаний из источников он не участвует.
        method == "POST" && path == "/v2/topics" ->
            тема(требуется(query, "project"), разобрать(body))

        method == "POST" && path == "/v2/facts" ->
            ручнойФакт(требуется(query, "project"), разобрать(body))

        method == "POST" && path.matches(Regex("/v2/facts/[A-ZА-Я0-9-]+/disposition")) ->
            диспозиция(
                требуется(query, "project"),
                path.removePrefix("/v2/facts/").removeSuffix("/disposition"),
                разобрать(body),
            )

        // Д2в: план задания — предпросмотр до нажатия
        method == "GET" && path.matches(Regex("/v2/intake/[A-Z-]+[0-9]+")) ->
            план(требуется(query, "project"), path.removePrefix("/v2/intake/"))

        method == "POST" && path.matches(Regex("/v2/intake/[A-Z-]+[0-9]+/accept")) ->
            принять(
                требуется(query, "project"),
                path.removePrefix("/v2/intake/").removeSuffix("/accept"),
                разобрать(body),
            )

        // Предложения сцены: сцена начинается не с пустой формы
        method == "GET" && path == "/v2/knowledge/suggestions" ->
            предложения(требуется(query, "project"), требуется(query, "scene"))

        // Мера поля знаний: доля сущностей, выведенных из фактов
        method == "GET" && path == "/v2/knowledge/coverage" -> покрытие(требуется(query, "project"))

        method == "GET" && path == "/v2/ai/journal" -> журнал(требуется(query, "project"))

        else -> null
    }

    private fun канон(project: String, material: String): V2Router.Ответ {
        val узел = mapper.createObjectNode()
        val блоки = узел.putArray("blocks")
        intake.canon(project, material).forEach { б ->
            блоки.addObject().put("anchor", б.anchor).put("kind", б.kind).put("text", б.text)
        }
        узел.put("fingerprint", intake.fingerprint(project, material))
        return V2Router.Ответ(200, узел)
    }

    private fun разбор(project: String, тело: ObjectNode): V2Router.Ответ {
        val материал = тело.path("material").asText("")
        require(материал.isNotBlank()) { "нужен материал: что разбираем" }
        val намерение = тело.path("intent").asText("разбери по сущностям")
        val автор = тело.path("author").asText("инженер")
        return try {
            V2Router.Ответ(201, итог(atomize.atomize(project, материал, намерение, автор)))
        } catch (e: ProviderUnavailable) {
            // Канал недоступен — это состояние, а не пустой урожай: пустой
            // список выглядел бы как «модель ничего не нашла».
            V2Router.Ответ(
                503,
                mapper.createObjectNode()
                    .put("error", "живой разбор недоступен: ${e.message}")
                    .put("what_to_do", "повторите позже либо вставьте разбор пакетом"),
            )
        }
    }

    private fun тема(project: String, тело: ObjectNode): V2Router.Ответ {
        val т = intake.addTopic(project, тело.path("label").asText(""), тело.path("author").asText("инженер"))
        return V2Router.Ответ(
            201,
            mapper.createObjectNode().put("id", т.id).put("label", т.label).put("facts", т.facts),
        )
    }

    private fun ручнойФакт(project: String, тело: ObjectNode): V2Router.Ответ {
        val ф = intake.addFact(
            project,
            subject = тело.path("subject").asText(""),
            predicate = тело.path("predicate").asText(""),
            value = тело.path("value").asText(""),
            unit = тело.path("unit").asText("").ifBlank { null },
            kind = тело.path("kind").asText("framing"),
            topic = тело.path("topic").asText("").ifBlank { null },
            material = тело.path("material").asText("").ifBlank { null },
            author = тело.path("author").asText("инженер"),
        )
        return V2Router.Ответ(201, фактВид(ф))
    }

    private fun итог(и: FactIntake): ObjectNode {
        val узел = mapper.createObjectNode()
        узел.put("note", и.note)
        // Задание с планом — тем же ответом: план ложится на акцепт прямо
        // в поле, а не за переходом на другой экран.
        узел.put("task", и.task ?: "")
        узел.put("accepted", и.accepted.size)
        узел.put("refused", и.refused.size)
        val факты = узел.putArray("facts")
        и.accepted.forEach { факты.add(фактВид(it)) }
        узел.putArray("refusals").also { а -> и.refused.forEach { а.add(it) } }
        val темы = узел.putArray("topics")
        и.topics.forEach { т ->
            темы.addObject().put("id", т.id).put("label", т.label).put("facts", т.facts)
        }
        return узел
    }

    private fun фактВид(ф: Fact): ObjectNode = KindJson.факт(mapper, ф)

    private fun темы(project: String): V2Router.Ответ {
        val узел = mapper.createObjectNode()
        val массив = узел.putArray("items")
        intake.topics(project).forEach { т ->
            массив.addObject()
                .put("id", т.id).put("label", т.label)
                .put("scene", т.scene).put("resolved_to", т.resolvedTo)
                .put("facts", т.facts)
        }
        return V2Router.Ответ(200, узел)
    }

    private fun диспозиция(project: String, fact: String, тело: ObjectNode): V2Router.Ответ {
        val решение = Disposition.valueOf(тело.path("disposition").asText("noted").uppercase())
        val факт = intake.dispose(
            project, fact, решение,
            reason = тело.path("reason").asText(""),
            author = тело.path("author").asText("инженер"),
        )
        return V2Router.Ответ(200, фактВид(факт))
    }

    private fun план(project: String, task: String): V2Router.Ответ {
        val задание = intake.task(project, task)
        val узел = mapper.createObjectNode()
        узел.put("task", задание.id)
        узел.put("material", задание.material)
        узел.put("note", задание.note)
        val действия = узел.putArray("actions")
        задание.plan.forEachIndexed { i, д ->
            val у = действия.addObject()
            у.put("index", i)
            у.put("kind", д.kind)
            у.put("target_kind", д.targetKind)
            у.put("scene", д.scene)
            у.put("title", д.title)
            у.put("preview", д.effect)
            у.putArray("facts").also { а -> д.factIds.forEach { ф -> а.add(ф) } }
            val содержимое = у.putObject("payload")
            д.payload.forEach { (к, в) -> содержимое.put(к, в) }
        }
        return V2Router.Ответ(200, узел)
    }

    private fun принять(project: String, task: String, тело: ObjectNode): V2Router.Ответ {
        val выбраны = тело.path("chosen").map { it.asInt() }
        val итог = intake.accept(project, task, выбраны, тело.path("author").asText("инженер"))
        val узел = mapper.createObjectNode()
        узел.put("created", итог.codes.size)
        узел.putArray("codes").also { а -> итог.codes.forEach { к -> а.add(к) } }
        // О незакрытом система говорит при приёме: имя соседа, которого в
        // проекте нет, связью не станет — и человек узнаёт это здесь.
        узел.putArray("notes").also { а -> итог.notes.forEach { з -> а.add(з) } }
        val п = intake.coverage(project)
        узел.put("coverage", Math.round(п.share * 100).toInt())
        return V2Router.Ответ(201, узел)
    }

    private fun предложения(project: String, scene: String): V2Router.Ответ {
        val п = intake.suggestions(project, scene)
        val узел = mapper.createObjectNode()
        узел.put("scene", п.scene)
        узел.put("task", п.task)
        узел.put("summary", п.summary)
        узел.putArray("indices").also { а -> п.indices.forEach { и -> а.add(и) } }
        val действия = узел.putArray("actions")
        п.actions.forEachIndexed { i, д ->
            val у = действия.addObject()
            у.put("index", п.indices.getOrElse(i) { -1 })
            у.put("target_kind", д.targetKind)
            у.put("title", д.title)
            у.put("preview", д.effect)
            у.putArray("facts").also { а -> д.factIds.forEach { ф -> а.add(ф) } }
            val содержимое = у.putObject("payload")
            д.payload.forEach { (к, в) -> содержимое.put(к, в) }
        }
        return V2Router.Ответ(200, узел)
    }

    private fun покрытие(project: String): V2Router.Ответ {
        val п = intake.coverage(project)
        val узел = mapper.createObjectNode()
        узел.put("total", п.total)
        узел.put("from_manual_facts", п.fromManualFacts).put("from_facts", п.fromFacts)
        узел.put("manual", п.manual)
        узел.put("share_percent", Math.round(п.share * 100).toInt())
        val виды = узел.putObject("by_kind")
        п.byKind.forEach { (вид, пара) ->
            виды.putObject(вид).put("total", пара.first).put("from_facts", пара.second)
        }
        return V2Router.Ответ(200, узел)
    }

    private fun журнал(project: String): V2Router.Ответ {
        val узел = mapper.createObjectNode()
        val массив = узел.putArray("items")
        service.journal(project).forEach { з ->
            массив.addObject()
                .put("kind", з.kind).put("model", з.model)
                .put("tokens_in", з.tokensIn).put("tokens_out", з.tokensOut)
                .put("at", з.at)
        }
        return V2Router.Ответ(200, узел)
    }

    private fun разобрать(body: String?): ObjectNode =
        (mapper.readTree(body ?: "{}") as? ObjectNode) ?: mapper.createObjectNode()

    private fun требуется(query: Map<String, String>, имя: String): String =
        query[имя] ?: throw IllegalArgumentException("нужен параметр «$имя»")
}
