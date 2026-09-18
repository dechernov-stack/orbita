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
import orbita.kernel.api.Channel
import orbita.kernel.api.Provenance
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
    /** Реестр связей: нужен отмене пакета — заведённое снимается вместе со связями. */
    private val links: orbita.kernel.api.LinkRegistry? = null,
) {

    private val запуск = Regex("/v2/synthesis/runs/(SR-[0-9]+)")
    private val акцепт = Regex("/v2/synthesis/runs/(SR-[0-9]+)/accept")
    private val отмена = Regex("/v2/synthesis/runs/(SR-[0-9]+)/undo")

    fun handle(method: String, path: String, query: Map<String, String>, body: String?): V2Router.Ответ? {
        if (path !in АДРЕСА && !запуск.matches(path) && !акцепт.matches(path) && !отмена.matches(path)) return null
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
                method == "POST" && отмена.matches(path) ->
                    отменить(проект, отмена.matchEntire(path)!!.groupValues[1], разобрать(body))
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
        val правки = тело.path("edits").takeIf { it.isObject }
        val кандидаты = выбранные.map { имя ->
            val предложение = карта[имя]
                ?: throw IllegalArgumentException("предложения «$имя» в «${итог.id}» нет: обновите диф")
            Candidate(
                имя, предложение.concept,
                сПравкой(предложение, правки?.path(имя)), CandidateOrigin.SYNTHESIS,
                // Основание предложения — факт документа: им кандидат и сверяется.
                basis = предложение.basis.firstOrNull()?.factId,
            )
        }
        val сверка = reconcile.preview(проект, кандидаты, автор, тело.path("role").asText("").ifBlank { "инженер" })
        // Нехватка связи СЧИТАЕТСЯ ДО заведения, то есть против проекта, каким
        // он был до пакета. Нужда, чья сторона предложена ЭТИМ ЖЕ пакетом,
        // выглядела незакрытой — и одна такая строка уводила в отказ все
        // тридцать (прогон владельца 15.09). Поэтому пакет не отбивается
        // целиком: понятия заводятся по порядку — сначала те, у кого
        // обязательных связей нет, и связь закрывается уже заведённым
        // соседом. Что не закрылось и после этого — названо поимённо.
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
        val сПравками: List<String> = тело.path("edits").takeIf { it.isObject }
            ?.properties()?.map { it.key }.orEmpty()
        val причина = тело.path("reason").asText("").ifBlank { "принято из синтеза $синтез" } +
            (if (сПравками.isEmpty()) "" else "; с правкой инженера: ${сПравками.joinToString(" · ")}")
        // Кандидат, которого сверка не разобрала, обязан быть НАЗВАН. До 16.09
        // такие исчезали между выбором человека и ответом сервера: из 82
        // выбранных до сверки доходили 68, и на экране это выглядело как
        // «столько и было».
        сверка.refused.forEach { брак ->
            ждут.addObject()
                .put("proposal", брак.substringBefore(":").trim())
                .put("verdict", "не разобрано")
                .put("why", брак.substringAfter(":").trim().ifBlank { брак })
        }
        var принято = 0
        // Порядок: сначала понятия без обязательных связей (сторона), затем
        // те, чья связь на них и указывает (нужда), затем прочие. Внутри
        // группы — как пришли: порядок ответа модели уже осмысленный.
        val поПорядку = сверка.items.sortedBy { кандидат ->
            GeneratedOntology.byCode[кандидат.concept]?.mustLink?.size ?: 0
        }
        поПорядку.forEach { кандидат ->
            val номер = кандидат.findings.indexOfFirst { Action.ACCEPT_NEW in it.offers }
            if (номер < 0 || кандидат.verdict != ReconcileVerdict.NEW) {
                // Узнанное принятое решает человек: слить · уточнить · оспорить —
                // действия сверки, а не автоматика акцепта. Причина называется
                // ВСЕГДА: строка без причины на экране выглядит отказом без
                // объяснения (прогон владельца 15.09).
                ждут.addObject().put("proposal", кандидат.localId)
                    .put("verdict", кандидат.verdict.word)
                    .put("why", почемуЖдёт(кандидат))
                return@forEach
            }
            val сделано = runCatching {
                reconcile.apply(
                    проект, сверка.id, кандидат.localId, номер, Action.ACCEPT_NEW,
                    reason = причина, author = автор,
                )
            }.getOrElse { беда ->
                // Не закрылась связь даже после заведённых соседей — строка
                // ждёт человека, а пакет идёт дальше: половина выбранного,
                // осевшая в модели, лучше отказа всему.
                ждут.addObject()
                    .put("proposal", кандидат.localId)
                    .put("verdict", кандидат.verdict.word)
                    .put("why", беда.message ?: "связь понятия не закрыта")
                return@forEach
            }
            принято += 1
            сделано.created.forEach { созданные.add(it) }
            сделано.links.forEach { связи.add(it) }
            сделано.facts.forEach { факты.add(it) }
        }
        // Пакет записывается ЦЕЛИКОМ: без него «Отменить пакет» нечего
        // отменять — человек принял 74 строки одним нажатием и должен иметь
        // право вернуть их одним же.
        запомнитьПакет(проект, синтез, автор, созданные)
        return узел.put("accepted", принято).put("note", сверка.note)
    }

    /** След пакета в запуске: что заведено, кем и когда. */
    private fun запомнитьПакет(проект: String, синтез: String, автор: String, созданные: ArrayNode) {
        if (созданные.isEmpty()) return
        val область = Area.Project(проект)
        val запись = store.byCode(область, синтез) ?: return
        val документ = запись.doc.deepCopy<JsonNode>() as ObjectNode
        val пакеты = документ.path(ПАКЕТЫ) as? ArrayNode ?: документ.putArray(ПАКЕТЫ)
        val пакет = пакеты.addObject().put("by", автор).put("at", java.time.OffsetDateTime.now().toString())
        val коды = пакет.putArray("created")
        созданные.forEach { коды.add(it.asText()) }
        store.update(запись.id, документ, запись.provenance, status = запись.status)
    }

    /**
     * Отменить пакет: заведённое последним нажатием снимается с учёта.
     *
     * Приём обратим целиком — иначе человек не решится нажать «принять всё».
     * Сущности переводятся в «снято», связи с ними снимаются; факты-основания
     * остаются: они след документа, а не решение человека, и повторный приём
     * обопрётся на них же.
     */
    private fun отменить(проект: String, код: String, тело: JsonNode): V2Router.Ответ {
        val область = Area.Project(проект)
        val запись = store.byCode(область, код)
            ?: throw NoSuchElementException("запуска «$код» в проекте нет")
        val документ = запись.doc.deepCopy<JsonNode>() as ObjectNode
        val пакеты = документ.path(ПАКЕТЫ) as? ArrayNode
        val последний = пакеты?.lastOrNull()
            ?: return V2Router.Ответ(
                409,
                mapper.createObjectNode()
                    .put("error", "у запуска «$код» нет принятого пакета — отменять нечего")
                    .put("what_to_do", "примите предложения, и пакет станет обратимым"),
            )
        val автор = автор(тело)
        val снято = mutableListOf<String>()
        val неснято = mutableListOf<String>()
        последний.path("created").forEach { имя ->
            val сущность = store.byCode(область, имя.asText())
            if (сущность == null || сущность.status == СНЯТО) {
                неснято += имя.asText()
                return@forEach
            }
            links?.let { реестр ->
                (реестр.from(сущность.id) + реестр.to(сущность.id)).forEach { связь ->
                    runCatching { реестр.unlink(связь.id, Provenance(Channel.MANUAL, автор)) }
                }
            }
            store.update(
                сущность.id, сущность.doc,
                Provenance(Channel.MANUAL, автор, source = код), status = СНЯТО,
            )
            снято += сущность.code
        }
        (пакеты as ArrayNode).remove(пакеты.size() - 1)
        store.update(запись.id, документ, запись.provenance, status = запись.status)

        val ответ = mapper.createObjectNode().put("run", код).put("undone", снято.size)
        массив(ответ, "cancelled", снято)
        массив(ответ, "already_gone", неснято)
        ответ.put(
            "note",
            "пакет отменён: снято с учёта ${снято.size}" +
                (if (неснято.isEmpty()) "" else "; не найдено или уже снято ${неснято.size}") +
                "; факты-основания остались — они след документа, а не решение человека",
        )
        return V2Router.Ответ(200, ответ)
    }

    /**
     * Почему строка осталась ждать человека. Узнанное принятое называется
     * своим вердиктом и целью находки: «дополнить ПП РФ … № 2216» человеку
     * понятно, а «дополнить» без цели — нет.
     */
    private fun почемуЖдёт(кандидат: ReconcileItem): String {
        val цель = кандидат.findings.firstNotNullOfOrNull { it.target?.takeIf { код -> код.isNotBlank() } }
        val связь = кандидат.blocking.firstOrNull()
        return when {
            кандидат.verdict != ReconcileVerdict.NEW && цель != null ->
                "узнано принятое «$цель» — решение «${кандидат.verdict.word}» за человеком: слить · уточнить · оспорить"
            кандидат.verdict != ReconcileVerdict.NEW ->
                "вердикт «${кандидат.verdict.word}» — узнанное принятое сверка сама не сливает"
            связь != null -> "обязательная связь «$связь» не закрыта — выберите цель и повторите"
            else -> "сверка не предложила «завести новое»: решение за человеком"
        }
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
            // Кто ставит поле — истина словами: система в свой момент («ставит
            // система при принятии») или человек на своей сцене. Системные
            // экран к правке не предлагает: подставит их сама система.
            массив(у, "system_fields", понятие.systemFields)
            массив(у, "human_fields", понятие.humanFields)
            // Ключа идентичности у понятия может ещё не быть: экран показывает
            // это словами, а не падает на чтении онтологии.
            val узнаётся = у.putObject("identity")
                .put("semantic", понятие.identity?.semantic ?: "")
                .put("threshold", понятие.identity?.threshold ?: 0.0)
            массив(узнаётся, "key", понятие.identity?.key ?: emptyList())
            // Истина СХЕМ о виде: какие поля у него есть, какие обязательны,
            // какие значения перечислены. Без этого экран не мог дать правку
            // предложения — «нет редактируемых полей» (проход владельца 18.09).
            понятие.targetKindCode?.let { код ->
                orbita.kernel.schema.GeneratedKinds.byCode[код]?.let { вид ->
                    val спец = у.putObject("kind")
                    спец.put("code", вид.code).put("title", вид.title)
                    массив(спец, "fields", вид.fields)
                    массив(спец, "required", вид.requiredFields)
                    массив(спец, "measures", вид.measures)
                    массив(спец, "fact_refs", вид.factRefFields)
                    val перечни = спец.putObject("enums")
                    вид.enums.forEach { (поле, значения) -> массив(перечни, поле, значения) }
                }
            }
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

    /**
     * Содержимое предложения С ПРАВКОЙ ИНЖЕНЕРА, если он её сделал.
     *
     * Предложение — не приговор: до приёма человек правит поля прямо в дифе
     * (проход владельца 18.09: «во вкладке Постановка нет редактируемых
     * полей»). Правка проверяется истиной СХЕМ, а не доверием: поля вне вида
     * и значения вне перечня отбиваются словами. Пустое значение снимает
     * поле — кроме обязательного, его меняют, а не снимают.
     */
    private fun сПравкой(п: FormationProposal, правка: JsonNode?): ObjectNode {
        val содержимое = содержимое(п)
        if (правка == null || !правка.isObject || правка.isEmpty) return содержимое
        val вид = GeneratedOntology.byCode[п.concept]?.targetKindCode
            ?.let { orbita.kernel.schema.GeneratedKinds.byCode[it] }
        val известные = (вид?.fields.orEmpty() + GeneratedOntology.byCode[п.concept]?.fields?.keys.orEmpty()).toSortedSet()
        правка.properties().forEach { (поле, значение) ->
            require(известные.isEmpty() || поле in известные) {
                "поля «$поле» у понятия «${п.concept}» нет: истина знает ${известные.joinToString(" · ")}"
            }
            val перечень = вид?.enums?.get(поле).orEmpty()
            val текст = if (значение.isValueNode) значение.asText("").trim() else ""
            require(перечень.isEmpty() || значение.isObject || текст.isBlank() || текст in перечень) {
                "значение «$текст» полю «$поле» не подходит: перечень истины — ${перечень.joinToString(" · ")}"
            }
            if (значение.isNull || (значение.isValueNode && текст.isBlank())) {
                require(вид == null || поле !in вид.requiredFields) {
                    "поле «$поле» обязательно у вида «${вид?.code}» — его меняют, а не снимают"
                }
                содержимое.remove(поле)
            } else {
                содержимое.set<JsonNode>(поле, if (значение.isValueNode) значениеУзлом(текст) else значение)
            }
        }
        return содержимое
    }

    private fun содержимое(п: FormationProposal): ObjectNode =
        mapper.createObjectNode().also { узел ->
            п.payload.forEach { (имя, значение) -> узел.set<JsonNode>(имя, значениеУзлом(значение)) }
        }

    /**
     * Значение поля узлом: объект остаётся ОБЪЕКТОМ.
     *
     * Порт предложения носит поля строками, а величина истиной схем — пара
     * «значение · единица» (`measure{…}`). Пока пара ехала строкой, сверка
     * видела величину без единицы и отбивала десять целей из десяти (живое
     * чтение записки 16.09). Непохожее на JSON остаётся текстом как было.
     */
    private fun значениеУзлом(текст: String): JsonNode {
        val обрезано = текст.trim()
        if (!обрезано.startsWith("{") && !обрезано.startsWith("[")) return mapper.getNodeFactory().textNode(текст)
        return runCatching { mapper.readTree(обрезано) }.getOrElse { mapper.getNodeFactory().textNode(текст) }
    }

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

        /** Принятые пакеты запуска: по ним «Отменить пакет» возвращает модель. */
        const val ПАКЕТЫ: String = "accepted_batches"

        /** Статус снятого с учёта: сущность остаётся в истории, из модели уходит. */
        const val СНЯТО: String = "cancelled"
    }
}
