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

        method == "POST" && path.matches(Regex("/v2/facts/[A-ZА-Я0-9-]+/disposition")) ->
            диспозиция(
                требуется(query, "project"),
                path.removePrefix("/v2/facts/").removeSuffix("/disposition"),
                разобрать(body),
            )

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

    private fun итог(и: FactIntake): ObjectNode {
        val узел = mapper.createObjectNode()
        узел.put("note", и.note)
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

    private fun фактВид(ф: Fact): ObjectNode = mapper.createObjectNode()
        .put("id", ф.id)
        .put("kind", ф.kind)
        .put("subject", ф.subject)
        .put("predicate", ф.predicate)
        .put("value", ф.value)
        .put("unit", ф.unit)
        .put("anchor", ф.anchor)
        .put("mark", ф.mark.name)
        .put("confidence", ф.confidence)
        .put("material", ф.material)
        .put("topic", ф.topic)
        .put("disposition", ф.disposition.name.lowercase())

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
