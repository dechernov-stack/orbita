// Маршруты исследования полноты (ЗНАНИЯ-V2, мера ПМИ-6 п. 2.4).
//
// Контур ВНЕШНИЙ: вопросы собираются из среза поля подстановкой, промпт
// уходит человеку файлом, исследование выполняет внешний контур (поиск, Deep
// Research, назначенный аналитик). Ни один адрес здесь не зовёт модель —
// мера прямая: после формулирования и запуска журнал ИИ не прирастает.
//
// Домена здесь нет: пять классов вопросов, сцены входа, коды RT, ранг
// результата и правило подтверждения источников знает :core:v2:knowledge.
// Роутер переводит HTTP в порт и обратно — и ни одного правила не повторяет.
package orbita.api.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.kernel.api.EntityStore
import orbita.kernel.api.KnowledgeFlag
import orbita.knowledge.api.Research
import orbita.knowledge.api.ResearchTask
import orbita.knowledge.api.ResearchTrigger

class ResearchRoutes(
    private val store: EntityStore,
    private val research: Research,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    private val одна = Regex("/v2/research/(RT-[0-9]+)")
    private val файлПромпта = Regex("/v2/research/(RT-[0-9]+)/prompt\\.md")
    private val шаг = Regex("/v2/research/(RT-[0-9]+)/(launch|result|parse|sources|next)")

    /**
     * Ворота флага стоят ДО разбора пути: на проекте без поля знаний v2
     * контура исследования нет целиком, и неизвестный подадрес под
     * `/v2/research` там честнее встретить тем же отказом, чем «маршрута
     * нет» — выключено не место, а вся дверь.
     */
    fun handle(method: String, path: String, query: Map<String, String>, body: String?): V2Router.Ответ? {
        if (path != "/v2/research" && !path.startsWith("/v2/research/")) return null
        val проект = query["project"]?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("укажите ?project=<код проекта>")
        if (!KnowledgeFlag.on(store, проект)) return выключено()
        return try {
            маршрут(method, path, query, body, проект)
        } catch (e: IllegalStateException) {
            // Состояние цикла — конфликт, а не поломка: «по сцене 3
            // исследование уже заведено» человек чинит сам, и код 500 сказал
            // бы ему неправду о том, что сломалось изделие.
            V2Router.Ответ(
                409,
                mapper.createObjectNode()
                    .put("error", e.message ?: "в этом состоянии исследование так не ведут")
                    .put("what_to_do", "посмотрите состояние задачи: GET /v2/research"),
            )
        }
    }

    private fun маршрут(
        method: String,
        path: String,
        query: Map<String, String>,
        body: String?,
        проект: String,
    ): V2Router.Ответ? = when {
        method == "GET" && path == "/v2/research" -> список(проект)

        method == "POST" && path == "/v2/research" -> разобрать(body).let { тело ->
            V2Router.Ответ(201, вид(research.formulate(проект, место(тело, query), автор(тело), вопросы(тело))))
        }

        // Промпт разбирается ДО общего адреса задачи: «RT-0001/prompt.md»
        // иначе читается как код задачи (та же ловушка, что у подсказок
        // документов).
        method == "GET" && файлПромпта.matches(path) ->
            промпт(проект, файлПромпта.matchEntire(path)!!.groupValues[1])

        method == "GET" && одна.matches(path) ->
            V2Router.Ответ(200, вид(research.task(проект, одна.matchEntire(path)!!.groupValues[1])))

        method == "POST" && шаг.matches(path) -> {
            val (код, что) = шаг.matchEntire(path)!!.destructured
            шагЦикла(проект, код, что, разобрать(body))
        }

        else -> null
    }

    private fun список(проект: String): V2Router.Ответ {
        val узел = mapper.createObjectNode()
        val массив = узел.putArray("items")
        research.list(проект).forEach { массив.add(вид(it)) }
        return V2Router.Ответ(200, узел)
    }

    private fun шагЦикла(проект: String, код: String, что: String, тело: ObjectNode): V2Router.Ответ = when (что) {
        "launch" -> V2Router.Ответ(
            200,
            вид(research.launch(проект, код, автор(тело), тело.path("note").asText(""))),
        )

        "result" -> V2Router.Ответ(
            201,
            вид(
                research.result(
                    проект, код,
                    name = тело.path("name").asText(""),
                    text = тело.path("text").asText(""),
                    author = автор(тело),
                ),
            ),
        )

        "sources" -> V2Router.Ответ(
            200,
            вид(
                research.confirmSources(
                    проект, код,
                    facts = тело.path("facts").map { it.asText() }.filter { it.isNotBlank() },
                    author = автор(тело),
                ),
            ),
        )

        "next" -> V2Router.Ответ(201, вид(research.next(проект, код, автор(тело), вопросы(тело))))

        else -> разборРезультата(проект, код)
    }

    /**
     * «Разобрать» у задачи нет и быть не должно: разбор результата — ОБЩИЙ
     * путь загрузки, а статус «разобрано» задача вычисляет по фактам своего
     * материала. Адрес отвечает отказом с адресом разбора: второй путь
     * атомизации завёл бы в поле знаний факты мимо канона и рангов.
     */
    private fun разборРезультата(проект: String, код: String): V2Router.Ответ {
        val материал = research.task(проект, код).resultMaterial
        return V2Router.Ответ(
            409,
            mapper.createObjectNode()
                .put("error", "отдельного разбора у исследования нет: результат разбирается общим путём загрузки")
                .put(
                    "what_to_do",
                    if (материал.isNullOrBlank()) "сначала примите результат: POST /v2/research/$код/result"
                    else "разберите материал результата: POST /v2/intake/atomize {\"material\":\"$материал\"}",
                ),
        )
    }

    /**
     * Промпт — единственный ответ этого файла не в JSON: он уходит человеку
     * файлом .md, и гриф среза проверен портом ДО сборки (classification_rule).
     */
    private fun промпт(проект: String, код: String): V2Router.Ответ = V2Router.Ответ(
        200, mapper.createObjectNode(),
        binary = research.prompt(проект, код).toByteArray(),
        contentType = "text/markdown; charset=utf-8",
        fileName = "ИССЛЕДОВАНИЕ-$код.md",
    )

    /**
     * Место входа — ровно одно из двух (истина схем `research_task.trigger_scene`).
     * Союз держит Kotlin: JSON Schema «ровно одно из двух» не стережёт, и
     * запрос с обоими полями обязан отказать здесь, а не разойтись молча.
     */
    private fun место(тело: JsonNode, query: Map<String, String>): ResearchTrigger {
        val сцена = тело.path("scene").asText("").ifBlank { query["scene"].orEmpty() }.trim()
        val точка = тело.path("gate").asText("").ifBlank { query["gate"].orEmpty() }.trim()
        require(сцена.isBlank() || точка.isBlank()) {
            "исследование заводится от ОДНОГО места: названы и сцена «$сцена», и точка «$точка» — оставьте одно"
        }
        require(сцена.isNotBlank() || точка.isNotBlank()) {
            "место входа не названо: {\"scene\":\"3\"} либо {\"gate\":\"MCR\"} — вопросы собираются от места"
        }
        return if (сцена.isNotBlank()) ResearchTrigger.Scene(сцена) else ResearchTrigger.Gate(точка)
    }

    /** Вопросы инженера: снятое и дописанное. Пусто — собирает поле по пяти классам. */
    private fun вопросы(тело: JsonNode): List<String> =
        тело.path("questions").map { it.asText() }.filter { it.isNotBlank() }

    /**
     * Автор — человек и только он: пустого автора порт не принимает ни в
     * одном шаге, поэтому умолчания «инженер» здесь нет. Наружу ходит
     * названный человек, а не служба.
     */
    private fun автор(тело: JsonNode): String = тело.path("author").asText("").trim()

    /** Карточка задачи — ЕДИНСТВЕННЫЙ сериализатор вида исследования в маршрутах. */
    private fun вид(задача: ResearchTask): ObjectNode {
        val узел = mapper.createObjectNode()
            .put("id", задача.id)
            // Место словами («сцена 3» · «точка MCR»): на экране строка
            // состояния цикла показывает его, а не код.
            .put("place", задача.trigger.word)
            .put("slice_fingerprint", задача.sliceFingerprint)
            .put("prompt_version", задача.promptVersion)
            .put("iteration", задача.iteration)
            .put("status", задача.status)
            .put("result_material", задача.resultMaterial ?: "")
            .put("note", задача.note)
        val место = узел.putObject("trigger")
        when (val откуда = задача.trigger) {
            is ResearchTrigger.Scene -> место.put("scene", откуда.scene)
            is ResearchTrigger.Gate -> место.put("gate", откуда.gate)
        }
        узел.putArray("questions").also { а -> задача.questions.forEach { а.add(it) } }
        узел.putObject("accepted")
            .put("stakeholders", задача.accepted.stakeholders)
            .put("programs", задача.accepted.programs)
            .put("norms", задача.accepted.norms)
            .put("applications", задача.accepted.applications)
            .put("analogs", задача.accepted.analogs)
        узел.put("accepted_total", задача.accepted.total)
        // Классы, по которым не принято ничего: ими идёт следующий цикл, и
        // человек видит их словами, а не кодами.
        узел.putArray("open").also { а -> задача.accepted.open().forEach { к -> а.add(к.word) } }
        return узел
    }

    private fun выключено(): V2Router.Ответ = V2Router.Ответ(
        409,
        mapper.createObjectNode()
            .put("error", "поле знаний v2 на этом проекте выключено: исследование полноты здесь не ведётся")
            .put(
                "what_to_do",
                "ведите исследование на проекте с полем знаний v2 — оно включается при открытии проекта: " +
                    "POST /v2/projects {\"knowledge_v2\":true}",
            ),
    )

    private fun разобрать(body: String?): ObjectNode =
        (mapper.readTree(body ?: "{}") as? ObjectNode) ?: mapper.createObjectNode()
}
