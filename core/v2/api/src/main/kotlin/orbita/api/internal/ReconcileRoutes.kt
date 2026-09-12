// Маршруты сверки ввода (СВЕРКА-РУЧНОГО-ВВОДА): один вход и для руки
// эксперта, и для предложений синтеза.
//
// Домена здесь нет ни строки: четыре вопроса — дубль · противоречие ·
// соединение · нехватка — задаёт модуль знаний, семантику надстраивает
// служба, а модель меняет ТОЛЬКО `apply` и только по решению человека.
// Роутер переводит HTTP в порт и обратно; сам он не пишет в модель ничего —
// поэтому снимок принятых сущностей до и после POST /v2/reconcile совпадает.
//
// Флаг: на проекте без `knowledge_v2` сверки нет вовсе — отказ словами, а не
// тихая работа. Один стенд держит проект прохода ПМИ-5 (прежний порядок:
// ввод сохраняется без сверки) и новый проект одновременно.
package orbita.api.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.kernel.api.EntityStore
import orbita.kernel.api.KnowledgeFlag
import orbita.knowledge.api.Action
import orbita.knowledge.api.Applied
import orbita.knowledge.api.Candidate
import orbita.knowledge.api.CandidateOrigin
import orbita.knowledge.api.FactSource
import orbita.knowledge.api.Finding
import orbita.knowledge.api.Intake
import orbita.knowledge.api.MustLinkMissing
import orbita.knowledge.api.Reconcile
import orbita.knowledge.api.ReconcileItem
import orbita.knowledge.api.ReconcileRun
import orbita.knowledge.schema.GeneratedOntology

/**
 * @param store нужен ровно за одним — прочитать флаг проекта; записей этот
 *   маршрут не делает
 * @param intake доля знаний после решения: считает её сервер, на клиенте
 *   расчётов нет
 */
class ReconcileRoutes(
    private val store: EntityStore,
    private val reconcile: Reconcile,
    private val intake: Intake,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    // Код запуска сверки — вид `synthesis_run`: механизм один на синтез и
    // сверку, и второго вида истина схем не заводит.
    private val запускПуть = Regex("/v2/reconcile/(SR-[0-9]+)")
    private val применитьПуть = Regex("/v2/reconcile/(SR-[0-9]+)/apply")

    fun handle(method: String, path: String, query: Map<String, String>, body: String?): V2Router.Ответ? = when {
        method == "POST" && path == "/v2/reconcile" -> подФлагом(query) { сверить(it, разобрать(body)) }

        method == "POST" && применитьПуть.matches(path) -> подФлагом(query) {
            применить(it, применитьПуть.matchEntire(path)!!.groupValues[1], разобрать(body))
        }

        method == "GET" && path == "/v2/reconcile" -> подФлагом(query) { открытые(it, query["status"]) }

        // Чтение по коду идемпотентно и живого вызова не делает: находки уже
        // записаны запуском, и повторный взгляд на них токенов не стоит.
        method == "GET" && запускПуть.matches(path) -> подФлагом(query) {
            V2Router.Ответ(200, запуск(reconcile.run(it, запускПуть.matchEntire(path)!!.groupValues[1])))
        }

        else -> null
    }

    /**
     * Проект и флаг — до всякой работы.
     *
     * Отказ по флагу отдаётся кодом 409 и словами «что делать»: молчаливое
     * выполнение на проекте прохода переписало бы его порядок работы, а
     * пустой ответ читался бы как «сверка ничего не нашла».
     */
    private fun подФлагом(query: Map<String, String>, дело: (String) -> V2Router.Ответ): V2Router.Ответ {
        val проект = query["project"]
            ?: throw IllegalArgumentException("нужен параметр «project»: сверка идёт по полю знаний проекта")
        if (!KnowledgeFlag.on(store, проект)) {
            return V2Router.Ответ(
                409,
                mapper.createObjectNode()
                    .put("error", "сверка ввода на этом проекте выключена")
                    .put(
                        "what_to_do",
                        "поле знаний v2 включается при открытии проекта (knowledge_v2); " +
                            "на проекте прохода ввод сохраняется прежним порядком, без сверки",
                    ),
            )
        }
        return try {
            дело(проект)
        } catch (нехватка: MustLinkMissing) {
            // Незакрытая обязательная связь — не общий отказ: маршрут обязан
            // назвать её имя, иначе человеку нечего выбирать.
            V2Router.Ответ(
                422,
                mapper.createObjectNode()
                    .put("error", нехватка.message ?: "обязательная связь понятия не закрыта: сущности не будет")
                    .put("missing", нехватка.missing)
                    .put("what_to_do", "назовите сторону в «target» и повторите решение"),
            )
        }
    }

    // --- показать находки, ничего не меняя ---------------------------------

    /**
     * Батч кандидатов — ОДНИМ запуском: десять вводов подряд дают один
     * `synthesis_run` и одну запись в журнале ИИ. Разбивать батч на вызовы
     * здесь нельзя — на этом стоит токенный бюджет (мера ПМИ-6).
     */
    private fun сверить(project: String, тело: JsonNode): V2Router.Ответ {
        val кандидаты = тело.path("candidates").map { кандидат(it) }
        require(кандидаты.isNotEmpty()) {
            "сверять нечего: в теле нет ни одного кандидата — нужен массив «candidates»"
        }
        val итог = reconcile.preview(
            project, кандидаты,
            тело.path("author").asText(""), тело.path("role").asText(""),
            тело.path("semantic").asBoolean(false),
        )
        return V2Router.Ответ(201, запуск(итог))
    }

    private fun кандидат(узел: JsonNode): Candidate {
        val номер = узел.path("local_id").asText("").trim()
        require(номер.isNotBlank()) { "у кандидата нет местного номера «local_id»: находке некуда вернуться" }
        val понятие = узел.path("concept").asText("").trim()
        // Понятие вне онтологии не образуется. Отказ здесь, а не на середине
        // сверки: иначе человек прочёл бы «внутренняя ошибка» вместо выбора.
        require(понятие in GeneratedOntology.byCode) {
            "понятие «$понятие» не описано в онтологии формирования: " +
                "известны ${GeneratedOntology.byCode.keys.joinToString(" · ")}"
        }
        val содержимое = узел.path("payload") as? ObjectNode
            ?: throw IllegalArgumentException("кандидат «$номер» без содержимого «payload»: сверять нечего")
        return Candidate(номер, понятие, содержимое, происхождение(узел.path("origin").asText("")))
    }

    /** Происхождение различает не механику — она одна, — а провенанс: рука или служба. */
    private fun происхождение(имя: String): CandidateOrigin {
        val значение = имя.trim().lowercase()
        if (значение.isBlank()) return CandidateOrigin.MANUAL
        return CandidateOrigin.entries.firstOrNull { it.name.lowercase() == значение }
            ?: throw IllegalArgumentException(
                "происхождение «$имя» неизвестно: " +
                    CandidateOrigin.entries.joinToString(" · ") { "${it.name.lowercase()} — ${it.word}" },
            )
    }

    // --- единственный путь изменения модели --------------------------------

    private fun применить(project: String, run: String, тело: JsonNode): V2Router.Ответ {
        val номер = тело.path("local_id").asText("").trim()
        require(номер.isNotBlank()) { "решение без кандидата не ставится: назовите «local_id»" }
        require(тело.path("finding").isInt) {
            "нужен номер находки «finding»: решение относится к находке, а не к кандидату вообще"
        }
        // Причину спрашивает и порт; здесь она названа полем ТЕЛА — отказ
        // должен говорить о запросе, который человек может исправить.
        val причина = тело.path("reason").asText("").trim()
        require(причина.isNotBlank()) { "решение без причины не ставится: назовите «reason» — почему так решили" }
        val итог = reconcile.apply(
            project, run, номер, тело.path("finding").asInt(),
            действие(тело.path("action").asText("")),
            тело.path("target").asText("").trim().ifBlank { null },
            причина, тело.path("author").asText(""),
        )
        return V2Router.Ответ(201, применённое(project, итог))
    }

    /** Действие человека: список предложен находкой, выбирает его человек. */
    private fun действие(имя: String): Action =
        Action.entries.firstOrNull { it.name.equals(имя.trim(), ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "действие «$имя» сверке неизвестно: " +
                    Action.entries.joinToString(" · ") { "${it.name.lowercase()} — ${it.word}" },
            )

    private fun применённое(project: String, итог: Applied): ObjectNode {
        val узел = mapper.createObjectNode()
        узел.putArray("created").also { а -> итог.created.forEach { а.add(it) } }
        узел.putArray("updated").also { а -> итог.updated.forEach { а.add(it) } }
        узел.putArray("links").also { а -> итог.links.forEach { а.add(it) } }
        узел.putArray("facts").also { а -> итог.facts.forEach { а.add(it) } }
        узел.put("note", итог.note)
        // Долю знаний считает сервер: в клиенте расчётов нет.
        узел.put("coverage", Math.round(intake.coverage(project).share * 100).toInt())
        return узел
    }

    // --- что ждёт человека -------------------------------------------------

    private fun открытые(project: String, status: String?): V2Router.Ответ {
        val выбор = status?.trim()?.lowercase().orEmpty()
        require(выбор.isBlank() || выбор == ОТКРЫТЫЕ) {
            "статус «$status» сверке неизвестен: спросить можно только «$ОТКРЫТЫЕ» — " +
                "запуски с нерешёнными кандидатами"
        }
        val список = reconcile.open(project)
        val узел = mapper.createObjectNode()
        узел.putArray("items").also { а -> список.forEach { а.add(запуск(it)) } }
        узел.put("count", список.size)
        return V2Router.Ответ(200, узел)
    }

    // --- один сериализатор на вид ------------------------------------------

    /** Запуск сверки: и ответ на POST, и список, и повторное чтение по коду — отсюда. */
    private fun запуск(з: ReconcileRun): ObjectNode = mapper.createObjectNode()
        .put("run", з.id)
        .put("status", з.status)
        .put("slice_fingerprint", з.sliceFingerprint)
        .put("ontology_version", з.ontologyVersion)
        // Живой вызов виден отдельным полем: по нему мера сверяется с журналом ИИ.
        .put("ai_called", з.aiCalled)
        .put("open", з.open)
        .put("note", з.note)
        .also { у -> у.putArray("items").also { а -> з.items.forEach { а.add(предмет(it)) } } }

    private fun предмет(п: ReconcileItem): ObjectNode = mapper.createObjectNode()
        .put("local_id", п.localId)
        .put("concept", п.concept)
        .put("candidate_fact", п.candidateFact)
        .put("authority", п.authority)
        .put("verdict", п.verdict.name.lowercase())
        .put("verdict_word", п.verdict.word)
        .put("decided", п.decided?.name?.lowercase())
        .put("note", п.note)
        .also { у ->
            у.set<JsonNode>("source", источник(п.source))
            у.putArray("blocking").also { а -> п.blocking.forEach { а.add(it) } }
            у.putArray("findings").also { а -> п.findings.forEach { а.add(находка(it)) } }
        }

    /** Источник кандидата — ровно одна ветка союза: документ с якорем ЛИБО эксперт. */
    private fun источник(и: FactSource): ObjectNode = when (и) {
        is FactSource.FromMaterial -> mapper.createObjectNode().put("material", и.material).put("anchor", и.anchor)
        is FactSource.FromExpert ->
            mapper.createObjectNode().put("account", и.account).put("role", и.role).put("at", и.at)
    }

    private fun находка(н: Finding): ObjectNode = mapper.createObjectNode()
        .put("question", н.question.name.lowercase())
        .put("question_word", н.question.word)
        .put("verdict", н.verdict.name.lowercase())
        .put("target", н.target)
        .put("match", н.match.word)
        .put("confidence", н.confidence)
        .put("blocking", н.blocking)
        .put("missing", н.missing)
        .also { у ->
            у.putArray("compared_fields").also { а -> н.comparedFields.forEach { а.add(it) } }
            у.putArray("basis").also { а -> н.basis.forEach { а.add(it) } }
            // Предложения, а не план: одно действие на карточку выбирает человек.
            у.putArray("offers").also { а -> н.offers.forEach { д -> а.add(д.name.lowercase()) } }
            // «Похоже» без «чем именно» — брак: отличие идёт человеку словами.
            н.difference?.let { о ->
                у.putObject("difference")
                    .put("comparison", о.comparison.word)
                    .put("field", о.field)
                    .put("mine", о.mine)
                    .put("theirs", о.theirs)
                    .put("reason", о.reason)
            }
        }

    private fun разобрать(body: String?): JsonNode =
        if (body.isNullOrBlank()) mapper.createObjectNode() else mapper.readTree(body)

    private companion object {

        /** Единственный статус, о котором спрашивают список: остальное — выдумка клиента. */
        const val ОТКРЫТЫЕ = "open"
    }
}
