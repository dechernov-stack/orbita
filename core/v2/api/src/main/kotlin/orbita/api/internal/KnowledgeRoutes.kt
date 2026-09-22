// Маршруты живого поля знаний (ЗАДАНИЕ-ПОЛЕ-ЗНАНИЙ).
//
// Тонкий файл: канон, промпт, вызов и ворота фактов живут в модулях.
// Здесь перевод HTTP в порты — и ни одного правила предметной области.
package orbita.api.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.ai.api.AiService
import orbita.ai.api.Atomize
import orbita.ai.api.ProviderUnavailable
import orbita.kernel.api.Area
import orbita.kernel.api.EntityStore
import orbita.kernel.api.KnowledgeFlag
import orbita.knowledge.api.Authority
import orbita.knowledge.api.Disposition
import orbita.knowledge.api.Fact
import orbita.knowledge.api.FactIntake
import orbita.knowledge.api.Intake

class KnowledgeRoutes(
    private val intake: Intake,
    private val atomize: Atomize,
    private val service: AiService,
    private val mapper: ObjectMapper = ObjectMapper(),
    /** Разбор фоновой задачей (ADR-069); null — только синхронный разбор. */
    private val jobs: orbita.ai.api.AtomizeJobs? = null,
    /** Чтение документа в постановку; null — контур на стенде не включён. */
    private val read: orbita.ai.api.ReadDocument? = null,
    /** Эталон постановки предложениями; null — не включён. */
    private val importStatement: orbita.ai.api.ImportStatement? = null,
    /** Раздача нужд по целям и сервисам (решение владельца 17.09); null — не включена. */
    private val distribute: orbita.ai.api.DistributeNeeds? = null,
    /**
     * Раздача ТРЕБОВАНИЙ по целям (20.09): тот же порядок, что у нужд, но
     * записывается источник требования — на него смотрит условие сцены 8.
     */
    private val distributeGoals: orbita.ai.api.DistributeNeeds? = null,
    /**
     * Хранилище — ровно за флагом проекта (`knowledge_v2`): ворота сверки
     * ставятся только там, где поле знаний v2 включено. `null` — прежняя
     * сборка: ворот нет вовсе, и ручной факт сохраняется как до перестройки.
     */
    private val store: EntityStore? = null,
    /** Сценарии сцены 9 предложением из сервисов и цепочек Arcadia (шип 1, п. 1.7); null — не включено. */
    private val proposeScenarios: orbita.ai.api.ProposeScenarios? = null,
) {

    fun handle(method: String, path: String, query: Map<String, String>, body: String?): V2Router.Ответ? = when {
        // Д1: канон материала — блоки с якорями; по ним факт проверяется
        method == "GET" && path == "/v2/intake/canon" ->
            канон(требуется(query, "project"), требуется(query, "material"))

        // Д2б: живой разбор. Один вызов на версию документа — повтор той же
        // версии возвращает записанный ответ и не звонит.
        method == "GET" && path == "/v2/intake/jobs" -> задания(требуется(query, "project"))
        method == "GET" && path.matches(Regex("/v2/intake/jobs/JOB-[0-9]+")) ->
            задание(требуется(query, "project"), path.removePrefix("/v2/intake/jobs/"))
        method == "POST" && path == "/v2/intake/atomize" ->
            разбор(требуется(query, "project"), разобрать(body))

        // Чтение документа в постановку одним вызовом (РЕШЕНИЕ-ЧИТАТЬ-СМЫСЛ,
        // 15.09). Прочитанное ложится запуском постановки: тот же экран и тот
        // же акцепт, что у синтеза из поля.
        method == "POST" && path == "/v2/intake/read" ->
            чтение(требуется(query, "project"), разобрать(body))

        // Эталон постановки предложениями (ПМИ-7): добор того, чего чтение
        // не дало, — тем же экраном и тем же акцептом.
        method == "POST" && path == "/v2/intake/import-statement" ->
            эталон(требуется(query, "project"), разобрать(body))

        // Раздача нужд по целям и сервисам одним вызовом (решение владельца
        // 17.09): карта связей предложениями, приём массовый и обратимый.
        method == "POST" && path == "/v2/intake/distribute" ->
            раздать(требуется(query, "project"), разобрать(body))
        method == "GET" && path == "/v2/intake/distribute" -> последняяРаздача(требуется(query, "project"))
        method == "GET" && РАЗДАЧА.matches(path) ->
            V2Router.Ответ(200, видРаздачи(раздача().view(требуется(query, "project"), РАЗДАЧА.matchEntire(path)!!.groupValues[1])))
        method == "POST" && ПРИЁМ_РАЗДАЧИ.matches(path) ->
            принятьРаздачу(требуется(query, "project"), ПРИЁМ_РАЗДАЧИ.matchEntire(path)!!.groupValues[1], разобрать(body))
        method == "POST" && ОТМЕНА_РАЗДАЧИ.matches(path) ->
            отменитьРаздачу(требуется(query, "project"), ОТМЕНА_РАЗДАЧИ.matchEntire(path)!!.groupValues[1], разобрать(body))

        // Раздача требований по целям (просьба владельца 20.09): «целей без
        // требования: 8» закрывается картой «требование → цели» с причиной.
        method == "POST" && path == "/v2/requirements/coverage" ->
            раздатьЦели(требуется(query, "project"), разобрать(body))
        method == "GET" && path == "/v2/requirements/coverage" -> последняяРаздачаЦелей(требуется(query, "project"))
        method == "POST" && ПРИЁМ_ЦЕЛЕЙ.matches(path) ->
            принятьРаздачуЦелей(требуется(query, "project"), ПРИЁМ_ЦЕЛЕЙ.matchEntire(path)!!.groupValues[1], разобрать(body))
        method == "POST" && ОТМЕНА_ЦЕЛЕЙ.matches(path) ->
            отменитьРаздачуЦелей(требуется(query, "project"), ОТМЕНА_ЦЕЛЕЙ.matchEntire(path)!!.groupValues[1], разобрать(body))

        // Сценарии сцены 9 предложением из сервисов и цепочек Arcadia (шип 1, п. 1.7).
        method == "POST" && path == "/v2/scenarios/propose" ->
            предложитьСценарии(требуется(query, "project"), разобрать(body))
        method == "GET" && path == "/v2/scenarios/propose" -> последнееПредложение(требуется(query, "project"))
        method == "POST" && ПРИЁМ_СЦЕНАРИЕВ.matches(path) ->
            принятьСценарии(требуется(query, "project"), ПРИЁМ_СЦЕНАРИЕВ.matchEntire(path)!!.groupValues[1], разобрать(body))
        method == "POST" && ОТМЕНА_СЦЕНАРИЕВ.matches(path) ->
            отменитьСценарии(требуется(query, "project"), ОТМЕНА_СЦЕНАРИЕВ.matchEntire(path)!!.groupValues[1], разобрать(body))

        method == "GET" && path == "/v2/topics" -> темы(требуется(query, "project"))

        // Ручные тема и факт (замечание прохода 08.09): инженер заводит
        // предмет и утверждение, не дожидаясь разбора. Ручной факт —
        // полноправный, но в доле знаний из источников он не участвует.
        method == "POST" && path == "/v2/topics" ->
            тема(требуется(query, "project"), разобрать(body))

        method == "POST" && path == "/v2/facts" ->
            ручнойФакт(требуется(query, "project"), разобрать(body))

        // Слияние тем: один предмет — одна тема, как бы его ни звали
        // документы. ИИ видит тождество и предлагает его связью `same_as`,
        // но щелчок делает инженер — поэтому автор обязателен.
        method == "POST" && path.matches(Regex("/v2/topics/[A-Za-z0-9-]+/merge")) ->
            слитьТему(
                требуется(query, "project"),
                path.removePrefix("/v2/topics/").removeSuffix("/merge"),
                разобрать(body),
            )

        method == "POST" && path.matches(Regex("/v2/topics/[A-Za-z0-9-]+/resolve")) ->
            разрешитьТему(
                требуется(query, "project"),
                path.removePrefix("/v2/topics/").removeSuffix("/resolve"),
                разобрать(body),
            )

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
        // Фоновой задачей (ПМИ-5, ADR-069): ответ сразу — задание со статусом;
        // готовый ответ из журнала применяется тут же.
        if (тело.path("background").asBoolean(false) && jobs != null) {
            val з = jobs.start(project, материал, намерение, автор)
            return V2Router.Ответ(if (з.status == "running") 202 else 201, заданиеВид(з))
        }
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

    /**
     * Факт руками — кандидат-факт ЭКСПЕРТА (manual_input_rule).
     *
     * Источник у него не документ, а человек: учётка · роль · дата. Учётка и
     * роль приходят либо полями верхнего уровня, либо ветвью союза
     * `source{account,role,at}` — экран называет их так же, как истина схем;
     * дату ставит приём, и это дата ввода, а не выдуманная.
     */
    private fun ручнойФакт(project: String, тело: ObjectNode): V2Router.Ответ {
        // На проекте поля знаний v2 ручной ввод идёт через сверку: введённое
        // не попадает в модель напрямую. Понятия у факта нет — нехватку
        // обязательных связей ворота здесь не проверяют.
        ReconcileGate.ворота(store, mapper, project, concept = null, тело = тело)?.let { return it }
        val источник = тело.path("source")
        val роль = тело.path("role").asText("").ifBlank { источник.path("role").asText("") }.trim()
        val ф = intake.addFact(
            project,
            subject = тело.path("subject").asText(""),
            predicate = тело.path("predicate").asText(""),
            value = тело.path("value").asText(""),
            unit = тело.path("unit").asText("").ifBlank { null },
            kind = тело.path("kind").asText("framing"),
            topic = тело.path("topic").asText("").ifBlank { null },
            material = тело.path("material").asText("").ifBlank { null },
            author = тело.path("author").asText("").ifBlank { источник.path("account").asText("") }
                .ifBlank { "инженер" },
            mark = тело.path("mark").asText("").ifBlank { "И" },
            role = роль.ifBlank { null },
            // Ранг не выдумывается маршрутом: умолчание ручного факта —
            // экспертный, и ставит его приём знаний.
            authority = тело.path("authority").asText("").trim().ifBlank { null },
        )
        return V2Router.Ответ(201, фактВид(ф))
    }

    /**
     * Слить тему в другую. В ответе — ГОЛОВА цепочки: тот адрес, по которому
     * теперь читаются факты обеих.
     *
     * Автор спрашивается здесь же, а не только приёмом: отказ обязан
     * доходить до экрана ответом 400, а не падением обвязки, — иначе человек
     * прочтёт «внутренняя ошибка» вместо того, чего от него ждут.
     */
    private fun слитьТему(project: String, topic: String, тело: ObjectNode): V2Router.Ответ {
        val автор = тело.path("author").asText("").trim()
        if (автор.isBlank()) {
            return V2Router.Ответ(
                400,
                mapper.createObjectNode()
                    .put("error", "слияние тем без автора не ставится: ИИ предлагает — сливает человек")
                    .put("what_to_do", "назовите «author» — кто сливает"),
            )
        }
        val голова = intake.mergeTopic(
            project, topic,
            into = тело.path("into").asText("").trim(),
            author = автор,
            reason = тело.path("reason").asText(""),
        )
        return V2Router.Ответ(
            200,
            KindJson.тема(mapper, голова)
                .put("merged", topic)
                .put("merged_into", голова.id),
        )
    }

    private fun задания(project: String): V2Router.Ответ {
        val массив = mapper.createArrayNode()
        jobs?.list(project)?.forEach { массив.add(заданиеВид(it)) }
        return V2Router.Ответ(200, mapper.createObjectNode().set("items", массив))
    }

    private fun задание(project: String, id: String): V2Router.Ответ {
        val з = jobs?.poll(project, id)
            ?: return V2Router.Ответ(404, mapper.createObjectNode().put("error", "задания разбора «$id» нет: стенд перезапускался — повторите разбор"))
        return V2Router.Ответ(200, заданиеВид(з))
    }

    /**
     * Прочитать документ в постановку. Канал недоступен — это состояние, а не
     * пустая постановка: отказ называется словами.
     */
    /** Эталон постановки → предложения. Тело: material · statement (JSON эталона) · author. */
    private fun эталон(project: String, тело: JsonNode): V2Router.Ответ {
        val импорт = importStatement ?: return V2Router.Ответ(
            501, mapper.createObjectNode().put("error", "импорт эталона на этом стенде не включён"),
        )
        val материал = тело.path("material").asText("").trim()
        require(материал.isNotBlank()) { "укажите material — документ, чьи якоря несёт эталон" }
        val эталон = тело.path("statement")
        require(эталон.isObject) { "нужно statement: объект эталона ПОСТАНОВКА-ИЗ-ЗАПИСКИ" }
        val запуск = импорт.import(project, материал, эталон, тело.path("author").asText("внешний контур"))
        return V2Router.Ответ(
            201,
            mapper.createObjectNode().put("run", запуск.id).put("material", материал)
                .put("note", запуск.note ?: "").put("proposals", запуск.diff.size),
        )
    }

    // --- раздача нужд ------------------------------------------------------

    private fun раздача(): orbita.ai.api.DistributeNeeds =
        distribute ?: throw IllegalStateException("раздача нужд на этом стенде не включена")

    private fun раздать(project: String, тело: JsonNode): V2Router.Ответ {
        val автор = тело.path("author").asText("инженер")
        return try {
            V2Router.Ответ(201, видРаздачи(раздача().distribute(project, автор)))
        } catch (e: ProviderUnavailable) {
            V2Router.Ответ(
                503,
                mapper.createObjectNode()
                    .put("error", "канал службы недоступен: ${e.message}")
                    .put("what_to_do", "повторите раздачу позже — нужды, цели и сервисы на месте"),
            )
        }
    }

    private fun последняяРаздача(project: String): V2Router.Ответ {
        val последняя = раздача().latest(project)
            ?: return V2Router.Ответ(200, mapper.createObjectNode().put("run", "")
                .put("note", "раздачи нужд на проекте ещё не было — нажмите «Раздать нужды по целям и сервисам»"))
        return V2Router.Ответ(200, видРаздачи(последняя))
    }

    private fun принятьРаздачу(project: String, run: String, тело: JsonNode): V2Router.Ответ {
        val выбранные = тело.path("chosen").map { it.asText() }
        val итог = раздача().accept(
            project, run, выбранные, тело.path("author").asText("инженер"), тело.path("reason").asText(""),
        )
        return V2Router.Ответ(201, видПриёмаРаздачи(итог))
    }

    /** Один сериализатор приёма на оба контура раздачи (правило 2 владельца). */
    private fun видПриёмаРаздачи(итог: orbita.ai.api.DistributionAccepted): JsonNode {
        val узел = mapper.createObjectNode().put("run", итог.run).put("linked", итог.linked)
            .put("classes", итог.classes).put("note", итог.note)
        узел.putArray("skipped").also { м -> итог.skipped.forEach { м.add(it) } }
        return узел
    }

    /** Один сериализатор отмены — на оба контура раздачи. */
    private fun видОтменыРаздачи(итог: orbita.ai.api.DistributionUndone): JsonNode =
        mapper.createObjectNode().put("run", итог.run).put("unlinked", итог.unlinked).put("note", итог.note)

    private fun отменитьРаздачу(project: String, run: String, тело: JsonNode): V2Router.Ответ =
        V2Router.Ответ(200, видОтменыРаздачи(раздача().undo(project, run, тело.path("author").asText("инженер"))))

    // --- раздача требований по целям ---------------------------------------

    private fun раздачаЦелей(): orbita.ai.api.DistributeNeeds =
        distributeGoals ?: throw IllegalStateException("раздача требований по целям на этом стенде не включена")

    private fun раздатьЦели(project: String, тело: JsonNode): V2Router.Ответ {
        val автор = тело.path("author").asText("инженер")
        return try {
            V2Router.Ответ(201, видРаздачи(раздачаЦелей().distribute(project, автор)))
        } catch (e: ProviderUnavailable) {
            V2Router.Ответ(
                503,
                mapper.createObjectNode()
                    .put("error", "канал службы недоступен: ${e.message}")
                    .put("what_to_do", "повторите раздачу позже — цели и требования на месте"),
            )
        }
    }

    private fun последняяРаздачаЦелей(project: String): V2Router.Ответ {
        val последняя = раздачаЦелей().latest(project)
            ?: return V2Router.Ответ(
                200,
                mapper.createObjectNode().put("run", "")
                    .put("note", "раздачи целей на проекте ещё не было — нажмите «Предложить моделью»"),
            )
        return V2Router.Ответ(200, видРаздачи(последняя))
    }

    private fun принятьРаздачуЦелей(project: String, run: String, тело: JsonNode): V2Router.Ответ {
        val выбранные = тело.path("chosen").map { it.asText() }
        val итог = раздачаЦелей().accept(
            project, run, выбранные, тело.path("author").asText("инженер"), тело.path("reason").asText(""),
        )
        return V2Router.Ответ(201, видПриёмаРаздачи(итог))
    }

    private fun отменитьРаздачуЦелей(project: String, run: String, тело: JsonNode): V2Router.Ответ =
        V2Router.Ответ(200, видОтменыРаздачи(раздачаЦелей().undo(project, run, тело.path("author").asText("инженер"))))

    // --- сценарии предложением (шип 1, п. 1.7) ------------------------------

    private fun сценарии(): orbita.ai.api.ProposeScenarios =
        proposeScenarios ?: throw IllegalStateException("предложение сценариев на этом стенде не включено")

    private fun предложитьСценарии(project: String, тело: JsonNode): V2Router.Ответ {
        val автор = тело.path("author").asText("инженер")
        return try {
            V2Router.Ответ(201, видПредложения(сценарии().propose(project, автор)))
        } catch (e: ProviderUnavailable) {
            V2Router.Ответ(
                503,
                mapper.createObjectNode()
                    .put("error", "канал службы недоступен: ${e.message}")
                    .put("what_to_do", "повторите позже — сервисы и состав на месте; сценарий можно записать и руками"),
            )
        }
    }

    private fun последнееПредложение(project: String): V2Router.Ответ {
        val последнее = сценарии().latest(project)
            ?: return V2Router.Ответ(
                200,
                mapper.createObjectNode().put("run", "")
                    .put("note", "сценарии на проекте ещё не предлагались — нажмите «Предложить из сервисов»"),
            )
        return V2Router.Ответ(200, видПредложения(последнее))
    }

    private fun принятьСценарии(project: String, run: String, тело: JsonNode): V2Router.Ответ {
        val итог = сценарии().accept(project, run, тело.path("chosen").map { it.asText() }, тело.path("author").asText("инженер"))
        val узел = mapper.createObjectNode().put("run", итог.run).put("note", итог.note)
        узел.putArray("created").also { м -> итог.created.forEach { м.add(it) } }
        узел.putArray("skipped").also { м -> итог.skipped.forEach { м.add(it) } }
        return V2Router.Ответ(201, узел)
    }

    private fun отменитьСценарии(project: String, run: String, тело: JsonNode): V2Router.Ответ {
        val итог = сценарии().undo(project, run, тело.path("author").asText("инженер"))
        return V2Router.Ответ(200, mapper.createObjectNode().put("run", итог.run).put("cancelled", итог.cancelled).put("note", итог.note))
    }

    private fun видПредложения(п: orbita.ai.api.ScenarioProposalRun): ObjectNode {
        val узел = mapper.createObjectNode().put("run", п.id).put("status", п.status)
            .put("cached", п.cached).put("note", п.note).put("accepted", п.scenarios.count { it.accepted })
        val массив = узел.putArray("scenarios")
        п.scenarios.forEach { с ->
            val у = массив.addObject().put("id", с.id).put("name", с.name).put("mode", с.mode)
                .put("reason", с.reason).put("exists", с.exists).put("accepted", с.accepted)
            у.putArray("services").also { м -> с.services.forEach { м.add(it) } }
            у.putArray("unresolved").also { м -> с.unresolved.forEach { м.add(it) } }
            val шаги = у.putArray("steps")
            с.steps.forEach { ш ->
                шаги.addObject().put("participant", ш.participant).put("kind", ш.participantKind)
                    .put("ref", ш.ref).put("what", ш.what)
            }
        }
        узел.putArray("refused").also { м -> п.refused.forEach { м.add(it) } }
        return узел
    }

    private fun видРаздачи(р: orbita.ai.api.DistributionRun): ObjectNode {
        val узел = mapper.createObjectNode().put("run", р.id).put("status", р.status)
            .put("cached", р.cached).put("note", р.note).put("accepted", р.links.count { it.accepted })
        val связи = узел.putArray("links")
        р.links.forEach { с ->
            связи.addObject().put("id", с.id).put("need", с.need).put("need_text", с.needText)
                .put("target", с.target).put("kind", с.targetKind).put("target_text", с.targetText)
                .put("qos_class", с.qosClass).put("reason", с.reason).put("exists", с.exists)
                .put("class_pending", с.classPending).put("accepted", с.accepted)
        }
        узел.putArray("unassigned").also { м -> р.unassigned.forEach { м.add(it) } }
        узел.putArray("refused").also { м -> р.refused.forEach { м.add(it) } }
        return узел
    }

    private fun чтение(project: String, тело: JsonNode): V2Router.Ответ {
        val читатель = read ?: return V2Router.Ответ(
            501,
            mapper.createObjectNode()
                .put("error", "чтение документа в постановку на этом стенде не включено")
                .put("what_to_do", "разберите документ на факты и соберите постановку из поля"),
        )
        val материал = тело.path("material").asText("").trim()
        require(материал.isNotBlank()) { "укажите material — какой документ читать" }
        val автор = тело.path("author").asText("инженер")
        return try {
            val запуск = читатель.read(project, материал, автор)
            V2Router.Ответ(
                201,
                mapper.createObjectNode()
                    .put("run", запуск.id)
                    .put("material", материал)
                    .put("note", запуск.note ?: "")
                    .put("proposals", запуск.diff.size),
            )
        } catch (e: ProviderUnavailable) {
            V2Router.Ответ(
                503,
                mapper.createObjectNode()
                    .put("error", "канал службы недоступен: ${e.message}")
                    .put("what_to_do", "повторите чтение позже — документ и его канон на месте"),
            )
        }
    }

    private fun заданиеВид(з: orbita.ai.api.AtomizeJob): ObjectNode {
        val узел = mapper.createObjectNode()
        узел.put("job", з.id).put("status", з.status).put("material", з.material)
            .put("started_at", з.startedAt).put("elapsed_seconds", з.elapsedSeconds)
        з.task?.let { узел.put("task", it) }
        з.note?.let { узел.put("note", it) }
        узел.put("accepted", з.accepted).put("refused", з.refused)
        узел.putArray("refusals").also { а -> з.refusals.forEach { а.add(it) } }
        з.error?.let { узел.put("error", it) }
        return узел
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
        intake.topics(project).forEach { массив.add(KindJson.тема(mapper, it)) }
        return V2Router.Ответ(200, узел)
    }

    private fun диспозиция(project: String, fact: String, тело: ObjectNode): V2Router.Ответ {
        val решение = Disposition.valueOf(тело.path("disposition").asText("noted").uppercase())
        val допущение = тело.path("assumption").takeIf { it.isObject }?.let {
            orbita.knowledge.api.Assumption(
                it.path("owner").asText(""), it.path("confirm_by").asText(""),
                it.path("validation").asText(""), it.path("impact_if_wrong").asText(""),
            )
        }
        val факт = intake.dispose(
            project, fact, решение,
            reason = тело.path("reason").asText(""),
            author = тело.path("author").asText("инженер"),
            assumption = допущение,
        )
        return V2Router.Ответ(200, фактВид(факт))
    }

    /** Тема разрешается в сущность: к точке принятые факты обязаны найти адрес. */
    private fun разрешитьТему(project: String, topic: String, тело: ObjectNode): V2Router.Ответ {
        val тема = intake.resolveTopic(project, topic, тело.path("entity").asText(""), тело.path("author").asText("инженер"))
        return V2Router.Ответ(200, KindJson.тема(mapper, тема))
    }

    private fun план(project: String, task: String): V2Router.Ответ {
        val задание = intake.task(project, task)
        val узел = mapper.createObjectNode()
        узел.put("task", задание.id)
        узел.put("material", задание.material)
        узел.put("note", задание.note)
        рангМатериала(project, задание.material, узел)
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
        // Оценка ТЗ против нужд — матрицей (шип E п. 1); есть только у ТЗ.
        задание.assessment?.let { о ->
            val оценка = узел.putObject("assessment")
            val строки = оценка.putArray("lines")
            о.lines.forEach { л ->
                val с = строки.addObject().put("fact", л.fact).put("requirement", л.requirement).put("verdict", л.verdict).put("note", л.note).put("text", л.text)
                с.putArray("needs").also { а -> л.needs.forEach { а.add(it) } }
            }
            // От нужды — с дырой словами (ответ владельца 09.09 п. 1).
            val нужды = оценка.putArray("needs")
            о.needs.forEach { н ->
                val с = нужды.addObject().put("need", н.need).put("verdict", н.verdict).put("gap", н.gap)
                с.putArray("requirements").also { а -> н.requirements.forEach { а.add(it) } }
            }
            оценка.putArray("gaps").also { а -> о.gaps.forEach { а.add(it) } }
            оценка.putArray("uncovered_needs").also { а -> о.uncoveredNeeds.forEach { а.add(it) } }
            оценка.putArray("orphan_requirements").also { а -> о.orphanRequirements.forEach { а.add(it) } }
        }
        return V2Router.Ответ(200, узел)
    }

    /**
     * Ранг материала задания — и судьба пакетного приёма — в ответ плана.
     *
     * Остановка ПМИ-6 (14.09): владелец нажал «принять всё» на плане
     * СОМНИТЕЛЬНОГО материала, не имея на экране ни одного знака, что
     * источник не подтверждён. Ранг живёт на карточке материала, и план его
     * не отдавал вовсе — экран не мог показать того, чего ему не сказали.
     *
     * Ранга нет ни у хранилища (прежняя сборка), ни у карточки (материал
     * заведён до перестройки) — маршрут молчит, а не выдумывает ранг:
     * правдоподобный «справочный» здесь хуже отсутствующего.
     */
    private fun рангМатериала(project: String, material: String, узел: ObjectNode) {
        val хранилище = store ?: return
        val карточка = хранилище.byCode(Area.Project(project), material)?.doc ?: return
        val ранг = карточка.path("authority").asText("").trim()
        if (Authority.known(ранг)) узел.put("authority", ранг).put("authority_word", Authority.word(ранг))
        // Ворота приёма стоят ровно там, где включено поле знаний v2: на
        // проекте прохода их нет, и гасить кнопку было бы неправдой об этом
        // проекте — экран обязан обещать то, чем ответит приём.
        val заперто = ранг == Authority.DOUBTFUL && KnowledgeFlag.on(хранилище, project)
        узел.put("batch_accept", !заперто)
        if (заперто) узел.put("batch_refusal", ОТКАЗ_СОМНИТЕЛЬНОГО)
    }

    private companion object {
        val РАЗДАЧА: Regex = Regex("/v2/intake/distribute/(SR-[0-9]+)")
        val ПРИЁМ_РАЗДАЧИ: Regex = Regex("/v2/intake/distribute/(SR-[0-9]+)/accept")
        val ОТМЕНА_РАЗДАЧИ: Regex = Regex("/v2/intake/distribute/(SR-[0-9]+)/undo")
        val ПРИЁМ_ЦЕЛЕЙ: Regex = Regex("/v2/requirements/coverage/(SR-[0-9]+)/accept")
        val ОТМЕНА_ЦЕЛЕЙ: Regex = Regex("/v2/requirements/coverage/(SR-[0-9]+)/undo")
        val ПРИЁМ_СЦЕНАРИЕВ: Regex = Regex("/v2/scenarios/propose/(SR-[0-9]+)/accept")
        val ОТМЕНА_СЦЕНАРИЕВ: Regex = Regex("/v2/scenarios/propose/(SR-[0-9]+)/undo")


        /**
         * Отказ ворот приёма плана сомнительного материала — ДОСЛОВНО те же
         * слова, которыми отвечает `EntityIntake.отказПлана`.
         *
         * Вторая формулировка того же отказа здесь не сочиняется: кнопка на
         * экране обязана называть причину теми же словами, что и приём, —
         * иначе человек услышит от кнопки одно, а от сервера другое.
         * Слово ранга берётся у `Authority`, а не переписывается.
         */
        val ОТКАЗ_СОМНИТЕЛЬНОГО: String =
            "источник не подтверждён (материал ранга «${Authority.word(Authority.DOUBTFUL)}») — " +
                "подтвердите источники, и строки станут предложениями"
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
        // Доля знаний с РАНГАМИ оснований (мера ПМИ-6 п. 1.8): без этого «80 %»
        // не отличает опору на записку заказчика от опоры на непроверенную справку.
        val ранги = узел.putObject("by_authority")
        п.byAuthority.forEach { (ранг, сколько) -> ранги.put(ранг, сколько) }
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
