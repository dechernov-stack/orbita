// Маршруты синтеза постановки из поля (план «Знания v2», группа G).
//
// Домена здесь нет: срез поля, его отпечаток, кэш живого вызова и окно
// накопления событий живут в службе синтеза (:core:v2:ai), правила образования
// понятий — в истине онтологии, а единственный путь изменения модели — сверка
// (:core:v2:knowledge). Отдельный файл — не по вкусу: KnowledgeRoutes.kt уже
// 326 строк при лимите ТЗ-BACKEND §3 в 300, и синтез в нём усугубил бы долг.
//
// Флаг проекта: на проекте без knowledge_v2 КАЖДЫЙ здешний адрес отвечает
// отказом словами (409), а не работает молча — один стенд держит оба порядка
// сразу, и проект прохода ПМИ-5 живёт прежним до конца прохода.
package orbita.api.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.ai.api.Basis
import orbita.ai.api.FormationProposal
import orbita.ai.api.SynthesisJobs
import orbita.ai.api.SynthesisRun
import orbita.ai.api.Verdict
import orbita.kernel.api.Area
import orbita.kernel.api.EntityStore
import orbita.kernel.api.KnowledgeFlag
import orbita.knowledge.api.Action
import orbita.knowledge.api.Authority
import orbita.knowledge.api.Candidate
import orbita.knowledge.api.CandidateOrigin
import orbita.knowledge.api.FactSource
import orbita.knowledge.api.MustLinkMissing
import orbita.knowledge.api.Reconcile
import orbita.knowledge.api.ReconcileItem
import orbita.knowledge.api.ReconcileRun
import orbita.knowledge.schema.GeneratedOntology
import orbita.knowledge.api.Verdict as ReconcileVerdict

/**
 * @param reconcile сверка: принятие предложения идёт ЧЕРЕЗ неё, а не мимо —
 *   механизм один и для руки, и для синтеза, а модель меняет только `apply`
 */
class SynthesisRoutes(
    private val store: EntityStore,
    private val jobs: SynthesisJobs,
    private val reconcile: Reconcile,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    private val запуск = Regex("/v2/synthesis/runs/(SR-[0-9]+)")
    private val акцепт = Regex("/v2/synthesis/runs/(SR-[0-9]+)/accept")

    fun handle(method: String, path: String, query: Map<String, String>, body: String?): V2Router.Ответ? {
        if (path !in АДРЕСА && !запуск.matches(path) && !акцепт.matches(path)) return null
        val проект = требуется(query, "project")
        выключено(проект)?.let { return it }
        return try {
            when {
                method == "POST" && path == "/v2/synthesis/runs" -> начать(проект, разобрать(body))
                method == "GET" && path == "/v2/synthesis/runs" -> список(проект)
                method == "GET" && path == "/v2/synthesis/diff" -> диф(проект)
                method == "GET" && path == "/v2/synthesis/pending" -> дрейф(проект)
                method == "GET" && path == "/v2/ontology/formation" -> онтология()
                method == "POST" && акцепт.matches(path) ->
                    принять(проект, акцепт.matchEntire(path)!!.groupValues[1], разобрать(body))
                method == "GET" && запуск.matches(path) ->
                    опрос(проект, запуск.matchEntire(path)!!.groupValues[1])
                else -> null
            }
        } catch (e: MustLinkMissing) {
            // Отказ называет ИМЯ незакрытой связи, а не общее «нельзя»: иначе человеку нечего чинить.
            V2Router.Ответ(
                422,
                mapper.createObjectNode().put("error", e.message).put("missing", e.missing)
                    .put("what_to_do", "выберите сторону и повторите: без обязательной связи сущности не будет"),
            )
        }
    }

    /** Рука окна накопления не ждёт: нажатие закрывает открытое окно немедленно. */
    private fun начать(проект: String, тело: JsonNode): V2Router.Ответ {
        val итог = jobs.start(проект, тело.path("trigger").asText("").ifBlank { "manual" }, автор(тело))
        // 202 — работа принята, ответа службы ещё нет: экран опрашивает запуск.
        return V2Router.Ответ(if (итог.status in ИДЁТ) 202 else 201, видЗапуска(проект, итог))
    }

    /** Журнал запусков без предложений: диф раскрывается по одному запуску. */
    private fun список(проект: String): V2Router.Ответ {
        val узел = mapper.createObjectNode()
        val строки = узел.putArray("items")
        jobs.list(проект).forEach { строки.add(видЗапуска(проект, it, сПредложениями = false)) }
        return V2Router.Ответ(200, узел)
    }

    /** Опрос: готовый ответ службы применяется здесь, на потоке запросов (ADR-069). */
    private fun опрос(проект: String, код: String): V2Router.Ответ =
        V2Router.Ответ(200, видЗапуска(проект, задание(проект, код)))

    /** Диф последнего доведённого запуска — то, что показывает вкладка постановки. */
    private fun диф(проект: String): V2Router.Ответ {
        val последний = jobs.list(проект).lastOrNull { it.status == ГОТОВ }
            ?: return V2Router.Ответ(200, mapper.createObjectNode().put("run", "")
                .put("note", "синтеза на проекте ещё не было — нажмите «Сформировать постановку из поля»"))
        return V2Router.Ответ(200, видЗапуска(проект, последний))
    }

    /** «Поле изменилось: N» — считает сервер: клиент не считает ничего. */
    private fun дрейф(проект: String): V2Router.Ответ {
        val итог = jobs.pending(проект)
        return V2Router.Ответ(200, mapper.createObjectNode().put("changed", итог.changed)
            .put("since", итог.since ?: "").put("fingerprint", итог.fingerprint))
    }

    /**
     * Принять выбранные предложения — ЧЕРЕЗ сверку: выбранное становится
     * кандидатом происхождения «предложение синтеза» и проходит те же четыре
     * вопроса, что и ручной ввод. Заводится только то, о чём сверка сказала
     * «новое»: узнанное принятое решает человек — служба не сливает сама.
     */
    private fun принять(проект: String, код: String, тело: JsonNode): V2Router.Ответ {
        val итог = задание(проект, код)
        val автор = автор(тело)
        val карта = предложения(проект, итог)
        val выбранные = тело.path("chosen").map { it.asText() }
        require(выбранные.isNotEmpty()) {
            "не выбрано ни одного предложения: отметьте строки дифа — синтез сам ничего не заводит"
        }
        val кандидаты = выбранные.map { имя ->
            val предложение = карта[имя]
                ?: throw IllegalArgumentException("предложения «$имя» в «${итог.id}» нет: обновите диф")
            Candidate(имя, предложение.concept, содержимое(предложение), CandidateOrigin.SYNTHESIS)
        }
        val сверка = reconcile.preview(проект, кандидаты, автор, тело.path("role").asText("").ifBlank { "инженер" })
        // Предложение с незакрытой обязательной связью — предложение с пометой, а
        // не сущность: отказ идёт ДО первого заведения, иначе половина выбранного
        // успела бы осесть в модели.
        val нехватка = сверка.items.filter { it.blocking.isNotEmpty() }
        if (нехватка.isNotEmpty()) return V2Router.Ответ(422, отказНехватки(сверка, нехватка))
        return V2Router.Ответ(201, заведённое(проект, сверка, итог.id, автор, тело))
    }

    private fun заведённое(
        проект: String, сверка: ReconcileRun, синтез: String, автор: String, тело: JsonNode,
    ): ObjectNode {
        val узел = mapper.createObjectNode().put("run", сверка.id).put("from", синтез)
        val созданные = узел.putArray("created")
        val связи = узел.putArray("links")
        val факты = узел.putArray("facts")
        val ждут = узел.putArray("pending")
        val причина = тело.path("reason").asText("").ifBlank { "принято из синтеза $синтез" }
        var принято = 0
        сверка.items.forEach { кандидат ->
            val номер = кандидат.findings.indexOfFirst { Action.ACCEPT_NEW in it.offers }
            if (номер < 0 || кандидат.verdict != ReconcileVerdict.NEW) {
                // Узнанное принятое решает человек: слить · уточнить · оспорить —
                // действия сверки, а не автоматика акцепта.
                ждут.addObject().put("proposal", кандидат.localId).put("verdict", кандидат.verdict.word)
                return@forEach
            }
            val сделано = reconcile.apply(
                проект, сверка.id, кандидат.localId, номер, Action.ACCEPT_NEW, reason = причина, author = автор,
            )
            принято += 1
            сделано.created.forEach { созданные.add(it) }
            сделано.links.forEach { связи.add(it) }
            сделано.facts.forEach { факты.add(it) }
        }
        return узел.put("accepted", принято).put("note", сверка.note)
    }

    private fun отказНехватки(сверка: ReconcileRun, нехватка: List<ReconcileItem>): ObjectNode {
        val узел = mapper.createObjectNode()
            .put("error", "у выбранного не закрыта обязательная связь — сущности не заведены")
            .put("run", сверка.id)
            .put("what_to_do", "выберите сторону и примените находку: POST /v2/reconcile/${сверка.id}/apply")
        массив(узел, "missing", нехватка.flatMap { it.blocking }.distinct())
        массив(узел, "proposals", нехватка.map { it.localId })
        return узел
    }

    /** Истина онтологии наружу: по ней экран объясняет, откуда взялось понятие. */
    private fun онтология(): V2Router.Ответ {
        val узел = mapper.createObjectNode().put("version", GeneratedOntology.ontologyVersion)
        val понятия = узел.putArray("concepts")
        GeneratedOntology.concepts.forEach { понятие ->
            val у = понятия.addObject().put("code", понятие.code).put("note", понятие.note ?: "")
            val поля = у.putObject("fields")
            понятие.fields.forEach { (имя, откуда) -> поля.put(имя, откуда) }
            массив(у, "must_link", понятие.mustLink)
            массив(у, "conflict_on", понятие.conflictOn)
            val узнаётся = у.putObject("identity")
                .put("semantic", понятие.identity.semantic).put("threshold", понятие.identity.threshold)
            массив(узнаётся, "key", понятие.identity.key)
        }
        return V2Router.Ответ(200, узел)
    }

    private fun видЗапуска(проект: String, итог: SynthesisRun, сПредложениями: Boolean = true): ObjectNode {
        val узел = mapper.createObjectNode()
            .put("id", итог.id).put("trigger", итог.trigger).put("status", итог.status)
            .put("slice_fingerprint", итог.sliceFingerprint).put("slice_size", итог.sliceSize)
            .put("ontology_version", итог.ontologyVersion).put("cached", итог.cached)
            .put("note", итог.note ?: "").put("error", итог.error ?: "")
        // Счётчики групп считает сервер: клиент решений не принимает.
        val счёт = узел.putObject("counts")
        итог.diff.counts().forEach { (группа, число) -> счёт.put(группа, число) }
        if (!сПредложениями) return узел
        val диф = узел.putObject("diff")
        Verdict.entries.forEach { диф.putArray(it.code) }
        предложения(проект, итог).forEach { (имя, предложение) ->
            (диф.get(предложение.verdict.code) as ArrayNode).add(видПредложения(имя, предложение))
        }
        return узел
    }

    private fun видПредложения(имя: String, п: FormationProposal): ObjectNode {
        val узел = mapper.createObjectNode()
            .put("proposal", имя).put("concept", п.concept)
            .put("verdict", п.verdict.code).put("source_mark", п.sourceMark.name)
        п.targetRef?.let { узел.put("target_ref", it) }
        if (п.diffField.isNotBlank()) узел.put("diff_field", п.diffField)
        п.confidence?.let { узел.put("confidence", it) }
        if (п.rankHint.isNotBlank()) узел.put("rank_hint", п.rankHint)
        узел.set<JsonNode>("payload", содержимое(п))
        массив(узел, "missing", п.missing)
        val основания = узел.putArray("basis")
        п.basis.forEach { основания.add(видОснования(it)) }
        return узел
    }

    private fun видОснования(о: Basis): ObjectNode {
        val узел = mapper.createObjectNode().put("fact", о.factId)
        о.material?.let { узел.put("material", it) }
        о.anchor?.let { узел.put("anchor", it) }
        о.authority?.let { узел.put("authority", it).put("authority_word", Authority.word(it)) }
        о.mark?.let { узел.put("mark", it.name) }
        (о.source as? FactSource.FromExpert)?.let {
            узел.put("account", it.account).put("role", it.role).put("at", it.at)
        }
        return узел
    }

    /**
     * Предложения запуска под кодами карточек: код — то, что человек отмечает в
     * дифе и присылает обратно. Порт `FormationProposal` кода не несёт, а в
     * записи запуска он лежит рядом с предложением, и порядок внутри группы
     * вердикта у записи и у порта один — поэтому код берётся по месту. Запись
     * без кода получает порядковое имя: выбор не должен ломаться молча.
     */
    private fun предложения(проект: String, итог: SynthesisRun): Map<String, FormationProposal> {
        val диф = store.byCode(Area.Project(проект), итог.id)?.doc?.path("diff")
        val карта = LinkedHashMap<String, FormationProposal>()
        listOf(
            Verdict.NEW to итог.diff.new, Verdict.AUGMENT to итог.diff.augment,
            Verdict.CONTRADICT to итог.diff.contradict, Verdict.CONFIRM to итог.diff.confirm,
        ).forEach { (вердикт, группа) ->
            группа.forEachIndexed { номер, предложение ->
                val код = диф?.path(вердикт.code)?.path(номер)?.path("proposal")?.asText("").orEmpty()
                карта[код.ifBlank { "${вердикт.code}#$номер" }] = предложение
            }
        }
        return карта
    }

    private fun содержимое(п: FormationProposal): ObjectNode =
        mapper.createObjectNode().also { узел -> п.payload.forEach { (имя, значение) -> узел.put(имя, значение) } }

    private fun массив(узел: ObjectNode, имя: String, строки: List<String>) =
        узел.putArray(имя).also { список -> строки.forEach { список.add(it) } }

    private fun задание(проект: String, код: String): SynthesisRun =
        jobs.poll(проект, код) ?: throw NoSuchElementException("запуска синтеза «$код» в проекте нет")

    /** Поле знаний v2 выключено — отказ словами и с указанием, что делать. */
    private fun выключено(проект: String): V2Router.Ответ? {
        if (KnowledgeFlag.on(store, проект)) return null
        return V2Router.Ответ(409, mapper.createObjectNode().put("error", "синтез выключен на этом проекте")
            .put("what_to_do", "поле знаний v2 включается у проекта при открытии — заведите новый проект"))
    }

    private fun автор(тело: JsonNode): String = тело.path("author").asText("").ifBlank { "инженер" }

    private fun требуется(query: Map<String, String>, имя: String): String =
        query[имя]?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("укажите ?$имя=<код проекта>")

    private fun разобрать(body: String?): JsonNode =
        if (body.isNullOrBlank()) mapper.createObjectNode() else mapper.readTree(body)

    private companion object {
        val АДРЕСА: Set<String> = setOf(
            "/v2/synthesis/runs", "/v2/synthesis/diff", "/v2/synthesis/pending", "/v2/ontology/formation",
        )

        /** Состояния, из которых запуск ещё выйдет: экран продолжает опрос. */
        val ИДЁТ: Set<String> = setOf("queued", "running")

        const val ГОТОВ: String = "done"
    }
}
