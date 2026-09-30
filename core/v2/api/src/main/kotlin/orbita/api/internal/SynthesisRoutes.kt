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
    /**
     * Шаблон EARS по формулировке (`ears_pattern: accept:auto`). Правило живёт
     * в модуле требований — сюда приходит функцией.
     */
    private val ears: ((String) -> String)? = null,
    /**
     * Знает ли справочник единиц такое написание (граница справочника, ADR).
     *
     * Без него предложение показателя ловит мусор: в «P95 не более 180 мин»
     * первое число — 95, а «единицей» за ним стоит слово «не». Перечня единиц
     * в коде нет и быть не может — их ведёт справочник (полка LIB).
     */
    private val units: ((String) -> Boolean)? = null,
) {

    private val запуск = Regex("/v2/synthesis/runs/(SR-[0-9]+)")
    private val акцепт = Regex("/v2/synthesis/runs/(SR-[0-9]+)/accept")
    private val отмена = Regex("/v2/synthesis/runs/(SR-[0-9]+)/undo")
    // Решение по предложению, кроме приёма: отклонить или отложить (шип 2,
    // экран 12; ЗАДАНИЕ-ЗНАНИЯ-V3 шип 3 — «R отклонить, L отложить»).
    private val решение = Regex("/v2/synthesis/runs/(SR-[0-9]+)/decline")

    fun handle(method: String, path: String, query: Map<String, String>, body: String?): V2Router.Ответ? {
        if (path !in АДРЕСА && !запуск.matches(path) && !акцепт.matches(path) &&
            !отмена.matches(path) && !решение.matches(path)
        ) {
            return null
        }
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
                method == "POST" && решение.matches(path) ->
                    отклонить(проект, решение.matchEntire(path)!!.groupValues[1], разобрать(body))
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
        val роль = тело.path("role").asText("").ifBlank { "инженер" }
        val карта = предложения(проект, итог)
        val выбранные = тело.path("chosen").map { it.asText() }
        require(выбранные.isNotEmpty()) {
            "не выбрано ни одного предложения: отметьте строки дифа — синтез сам ничего не заводит"
        }
        // Обмен ссылается на функцию ЛОКАЛЬНЫМ КЛЮЧОМ: её кода до приёма нет
        // (CODE-30-09 §1). Поэтому пакет принимается в два захода ОДНИМ
        // нажатием: сначала простые понятия (функции), их ключи запоминаются
        // кодами; потом ссылающиеся (обмены), у них ключ разрешается в код.
        // Владелец видит один прогон и один приём — работа-то одна.
        val ссылочные = выбранные.filter { карта[it]?.refs?.isNotEmpty() == true }
        val простые = выбранные.filterNot { it in ссылочные }
        val правки = тело.path("edits").takeIf { it.isObject }
        val кандидаты = простые.map { имя ->
            val предложение = карта[имя]
                ?: throw IllegalArgumentException("предложения «$имя» в «${итог.id}» нет: обновите диф")
            Candidate(
                имя, предложение.concept,
                сПравкой(предложение, правки?.path(имя)), CandidateOrigin.SYNTHESIS,
                // Кандидат сверяется по факту-основанию, если он есть. У основания-
                // сущности и разрыва (диф 28.09) факта нет — им кандидат сверяется
                // без ссылки на факт (Candidate.basis допускает пусто).
                basis = предложение.basis.filterIsInstance<Basis.Fact>().firstOrNull()?.factId,
            )
        }
        // Выбранный обмен без карточки — ошибка сразу: карта знает выбранное.
        ссылочные.firstOrNull { карта[it] == null }?.let {
            throw IllegalArgumentException("предложения «$it» в «${итог.id}» нет: обновите диф")
        }
        // Нехватка связи СЧИТАЕТСЯ ДО заведения, то есть против проекта, каким
        // он был до пакета. Нужда, чья сторона предложена ЭТИМ ЖЕ пакетом,
        // выглядела незакрытой — и одна такая строка уводила в отказ все
        // тридцать (прогон владельца 15.09). Поэтому пакет не отбивается
        // целиком: понятия заводятся по порядку — сначала те, у кого
        // обязательных связей нет, и связь закрывается уже заведённым
        // соседом. Что не закрылось и после этого — названо поимённо.
        val сверка = if (кандидаты.isNotEmpty()) reconcile.preview(проект, кандидаты, автор, роль) else null
        return V2Router.Ответ(201, заведённое(проект, сверка, итог.id, автор, тело, карта, ссылочные, роль))
    }

    private fun заведённое(
        проект: String, сверка: ReconcileRun?, синтез: String, автор: String, тело: JsonNode,
        карта: Map<String, FormationProposal>, ссылочные: List<String>, роль: String,
    ): ObjectNode {
        val узел = mapper.createObjectNode().put("run", сверка?.id ?: синтез).put("from", синтез)
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
        сверка?.refused?.forEach { брак ->
            ждут.addObject()
                .put("proposal", брак.substringBefore(":").trim())
                .put("verdict", "не разобрано")
                .put("why", брак.substringAfter(":").trim().ifBlank { брак })
        }
        // Бюджет и связь деривации при ПРИЁМЕ (диф 29.09-b): отказ доли сверх
        // потолка — ДО заведения (иначе сущность уже создана), связь derives —
        // после. Порт бюджета — единый источник суммы; сумма долей растёт и по
        // уже принятым в ЭТОЙ партии (реестр связей ещё не знает о них).
        val бюджеты = links?.let { orbita.knowledge.api.KnowledgeFactory.budgets(store, it, mapper) }
        val мерыДоли = orbita.knowledge.api.KnowledgeFactory.measures(store, mapper)
        val вПартии = mutableMapOf<String, Double>()
        var принято = 0
        // Локальный ключ функции → её код: им обмен разрешает ссылку (§2.2b).
        val кодыФункций = mutableMapOf<String, String>()
        // Порядок: сначала понятия без обязательных связей (сторона), затем
        // те, чья связь на них и указывает (нужда), затем прочие. Внутри
        // группы — как пришли: порядок ответа модели уже осмысленный.
        val поПорядку = сверка?.items?.sortedBy { кандидат ->
            GeneratedOntology.byCode[кандидат.concept]?.mustLink?.size ?: 0
        }.orEmpty()
        поПорядку.forEach { кандидат ->
            val созд = завестиКандидата(
                проект, сверка!!, кандидат, автор, причина, карта,
                созданные, связи, факты, ждут, бюджеты, мерыДоли, вПартии,
            )
            if (созд.isNotEmpty()) {
                принято += 1
                // Ключ функции → её код: по нему обмен второго захода сошлётся.
                карта[кандидат.localId]?.localKey?.let { ключ ->
                    кодВида(проект, созд, "function")?.let { кодыФункций[ключ] = it }
                }
            }
        }
        // Обмены — вторым заходом ТОГО ЖЕ нажатия: ключи в коды функций
        // (CODE-30-09 §1). Конец не принят → обмен откладывается, не отказ.
        if (ссылочные.isNotEmpty()) {
            принято += обмены(
                проект, ссылочные, карта, автор, причина, роль, кодыФункций,
                созданные, связи, факты, ждут, мерыДоли,
            )
        }
        // Пакет записывается ЦЕЛИКОМ: без него «Отменить пакет» нечего
        // отменять — человек принял 74 строки одним нажатием и должен иметь
        // право вернуть их одним же.
        запомнитьПакет(проект, синтез, автор, созданные)
        return узел.put("accepted", принято).put("note", сверка?.note ?: "принято обменами пакета")
    }

    /**
     * Завести ОДНОГО кандидата приёмом: ворота бюджета (для выводного
     * требования) — ДО заведения, `reconcile.apply`, связь derives и учёт доли
     * — после. Возвращает коды заведённого; пусто — отложен (узнанное принятое,
     * перебор бюджета или незакрытая связь), причина уже уложена в `ждут`.
     * Общий и для простых понятий, и для обменов второго захода.
     */
    private fun завестиКандидата(
        проект: String, сверка: ReconcileRun, кандидат: ReconcileItem, автор: String, причина: String,
        карта: Map<String, FormationProposal>,
        созданные: ArrayNode, связи: ArrayNode, факты: ArrayNode, ждут: ArrayNode,
        бюджеты: orbita.knowledge.api.Budgets?, меры: orbita.knowledge.api.Measures,
        вПартии: MutableMap<String, Double>,
    ): List<String> {
        val номер = кандидат.findings.indexOfFirst { Action.ACCEPT_NEW in it.offers }
        if (номер < 0 || кандидат.verdict != ReconcileVerdict.NEW) {
            // Узнанное принятое решает человек: слить · уточнить · оспорить —
            // действия сверки, а не автоматика акцепта. Причина называется ВСЕГДА.
            ждут.addObject().put("proposal", кандидат.localId)
                .put("verdict", кандидат.verdict.word).put("why", почемуЖдёт(кандидат))
            return emptyList()
        }
        // Основание-родитель предложения (диф 28.09): у деривации требования
        // оно есть, у функции и обмена — нет. По нему идут ворота бюджета и связь.
        val родитель = карта[кандидат.localId]?.basis
            ?.filterIsInstance<Basis.Entity>()?.firstOrNull { it.kind == "requirement" }?.code
        val перебор = родитель?.let { бюджетныйОтказ(проект, it, кандидат.localId, карта, бюджеты, меры, вПартии) }
        if (перебор != null) {
            ждут.addObject().put("proposal", кандидат.localId).put("verdict", "сверх бюджета").put("why", перебор)
            return emptyList()
        }
        val сделано = runCatching {
            reconcile.apply(
                проект, сверка.id, кандидат.localId, номер, Action.ACCEPT_NEW,
                reason = причина, author = автор,
            )
        }.getOrElse { беда ->
            // Не закрылась связь даже после заведённых соседей — строка ждёт
            // человека, а пакет идёт дальше: половина выбранного лучше отказа всему.
            ждут.addObject().put("proposal", кандидат.localId)
                .put("verdict", кандидат.verdict.word).put("why", беда.message ?: "связь понятия не закрыта")
            return emptyList()
        }
        сделано.created.forEach { созданные.add(it) }
        сделано.links.forEach { связи.add(it) }
        сделано.facts.forEach { факты.add(it) }
        родитель?.let { код ->
            связьДеривации(проект, сделано.created, код, автор, причина)?.let { связи.add(it) }
            доляВБюджет(проект, код, кандидат.localId, карта, бюджеты, меры)
                ?.let { вПартии.merge(код, it, Double::plus) }
        }
        // Шаг сценария получает свою функцию (§2 CODE-30-09): проза остаётся.
        шагуФункцию(проект, кандидат.localId, карта, сделано.created)
        return сделано.created
    }

    /**
     * Приём обменов вторым заходом (§2.2b, CODE-30-09 §1): концы обмена —
     * ЛОКАЛЬНЫЕ КЛЮЧИ функций — разрешаются в коды заведённых в этом пакете
     * функций (или коды уже принятых функций/сторон). Конец не разрешился —
     * обмен ОТЛОЖЕН, а не отказ: вернётся, когда его функцию примут. Заводится
     * всё той же сверкой — ни одной записи сущности мимо приёма.
     */
    private fun обмены(
        проект: String, ссылочные: List<String>, карта: Map<String, FormationProposal>,
        автор: String, причина: String, роль: String, кодыФункций: Map<String, String>,
        созданные: ArrayNode, связи: ArrayNode, факты: ArrayNode, ждут: ArrayNode,
        меры: orbita.knowledge.api.Measures,
    ): Int {
        val область = Area.Project(проект)
        val готовые = mutableListOf<Pair<String, Map<String, String>>>()
        ссылочные.forEach { имя ->
            val предложение = карта[имя] ?: run {
                ждут.addObject().put("proposal", имя).put("verdict", "не разобрано")
                    .put("why", "предложения нет: обновите диф")
                return@forEach
            }
            val payload = предложение.payload.toMutableMap()
            // Первая неразрешённая ссылка (поле → ключ) — она и откладывает
            // обмен; попутно разрешённые ложатся кодами в payload. `for` вместо
            // forEach: нужен break, и переменную не захватывает вложенное замыкание.
            var нераскрытый: Pair<String, String>? = null
            for ((поле, ключ) in предложение.refs) {
                val код = кодыФункций[ключ] ?: разрешитьКод(область, ключ)
                if (код == null) {
                    нераскрытый = поле to ключ
                    break
                }
                payload[поле] = код
            }
            if (нераскрытый != null) {
                val (поле, ключ) = нераскрытый
                ждут.addObject().put("proposal", имя).put("verdict", "отложено")
                    .put("why", "отложено: ${рольСсылки(поле)} «$ключ» не принята — обмен вернётся, когда её примут")
            } else {
                готовые += имя to payload
            }
        }
        if (готовые.isEmpty()) return 0
        // Разрешённые обмены — той же сверкой; ссылки уже коды, ворота
        // `must_link` (source_function · target) закрыты значениями.
        val кандидаты = готовые.map { (имя, payload) ->
            Candidate(имя, карта.getValue(имя).concept, содержимоеИз(payload), CandidateOrigin.SYNTHESIS)
        }
        val сверка = reconcile.preview(проект, кандидаты, автор, роль)
        сверка.refused.forEach { брак ->
            ждут.addObject().put("proposal", брак.substringBefore(":").trim())
                .put("verdict", "не разобрано").put("why", брак.substringAfter(":").trim().ifBlank { брак })
        }
        var принято = 0
        сверка.items.forEach { кандидат ->
            val созд = завестиКандидата(
                проект, сверка, кандидат, автор, причина, карта,
                созданные, связи, факты, ждут, null, меры, mutableMapOf(),
            )
            if (созд.isNotEmpty()) принято += 1
        }
        return принято
    }

    /** Код уже принятой функции или стороны по коду-ключу; null — такой сущности нет. */
    private fun разрешитьКод(область: Area, ключ: String): String? =
        store.byCode(область, ключ)?.takeIf { it.kind in ССЫЛОЧНЫЕ_ВИДЫ }?.code

    /** Роль ссылки словами — для «отложено: функция-источник … не принята». */
    private fun рольСсылки(поле: String): String = when (поле) {
        "source_function" -> "функция-источник"
        "target" -> "функция-получатель"
        else -> "ссылка «$поле»"
    }

    /** Содержимое кандидата из готовых полей (ссылки уже разрешены в коды). */
    private fun содержимоеИз(поля: Map<String, String>): ObjectNode =
        mapper.createObjectNode().also { узел ->
            поля.forEach { (имя, значение) -> узел.set<JsonNode>(имя, значениеУзлом(значение)) }
        }

    /** Код заведённого нужного вида среди созданных (функция среди созданного). */
    private fun кодВида(проект: String, созданные: List<String>, вид: String): String? {
        val область = Area.Project(проект)
        return созданные.firstOrNull { store.byCode(область, it)?.kind == вид }
    }

    /**
     * Шаг сценария получает свою функцию (§2 CODE-30-09): основание принятой
     * функции — шаг сценария (`Basis.Entity` functional_chain, anchor = что
     * шага). При приёме шаг получает ссылку на заведённую функцию полем
     * `function`; проза шага (`what`/`actor`/`component`) остаётся. Так цепочка
     * становится SA по истине, и 2.3 (стыки из обменов) читает готовую цепочку.
     *
     * Форму шага истина схем пока называет плоским `functional_chain.steps:
     * ref[] function`; поле шага `function` при сохранённой прозе — вопрос формы
     * шага, вынесен в NEXT.md диффом владельцу. Здесь — по имени, что владелец
     * назвал (`поле шага function`); экран без него работает.
     */
    private fun шагуФункцию(
        проект: String, localId: String, карта: Map<String, FormationProposal>, созданные: List<String>,
    ) {
        val предложение = карта[localId] ?: return
        if (предложение.concept != "function") return
        val основа = предложение.basis.filterIsInstance<Basis.Entity>()
            .firstOrNull { it.kind == "functional_chain" } ?: return
        val область = Area.Project(проект)
        val кодФункции = созданные.firstOrNull { store.byCode(область, it)?.kind == "function" } ?: return
        val цепочка = store.byCode(область, основа.code) ?: return
        val документ = цепочка.doc.deepCopy<JsonNode>() as ObjectNode
        val шаги = документ.path("steps") as? ArrayNode ?: return
        // Шаг основания — по якорю (что шага); первый ещё не привязанный.
        val якорь = основа.anchor?.trim().orEmpty()
        val шаг = шаги.firstOrNull {
            it.isObject && it.path("function").asText("").isBlank() &&
                (якорь.isBlank() || it.path("what").asText("").trim() == якорь)
        } as? ObjectNode ?: return
        шаг.put("function", кодФункции)
        store.update(цепочка.id, документ, цепочка.provenance, status = цепочка.status)
    }

    /** Доля предложения в каноне размерности бюджета; null — не бюджет, доли нет или не той размерности. */
    private fun доляВБюджет(
        проект: String,
        кодРодителя: String,
        localId: String,
        карта: Map<String, FormationProposal>,
        бюджеты: orbita.knowledge.api.Budgets?,
        меры: orbita.knowledge.api.Measures,
    ): Double? {
        val state = бюджеты?.forCeiling(проект, кодРодителя) ?: return null
        val мера = карта[localId]?.payload?.get("measure") ?: return null
        val баунд = runCatching { меры.bound(mapper.readTree(мера)) }.getOrNull() ?: return null
        return баунд.takeIf { it.dimension == state.dimension }?.value
    }

    /**
     * Отказ доли сверх бюджета словами (диф 29.09-b), либо null — родитель не
     * бюджет, у доли нет меры или сумма в пределах потолка. Сумма растёт и по
     * принятым в этой партии (`вПартии`): реестр связей их ещё не знает.
     */
    private fun бюджетныйОтказ(
        проект: String,
        кодРодителя: String,
        localId: String,
        карта: Map<String, FormationProposal>,
        бюджеты: orbita.knowledge.api.Budgets?,
        меры: orbita.knowledge.api.Measures,
        вПартии: Map<String, Double>,
    ): String? {
        val state = бюджеты?.forCeiling(проект, кодРодителя) ?: return null
        val доля = доляВБюджет(проект, кодРодителя, localId, карта, бюджеты, меры) ?: return null
        val принято = state.accepted + (вПартии[кодРодителя] ?: 0.0)
        if (принято + доля <= state.cap + 1e-6) return null
        return "доля ${число(доля)} ${state.unit} сверх бюджета «$кодРодителя»: принято ${число(принято)} + доля = " +
            "${число(принято + доля)} при потолке ${число(state.cap)}"
    }

    /** Связь derives_from дочернего требования на родителя; null — реестра нет или требование не создано. */
    private fun связьДеривации(проект: String, созданные: List<String>, кодРодителя: String, автор: String, причина: String): String? {
        val реестр = links ?: return null
        val область = Area.Project(проект)
        val дитя = созданные.mapNotNull { store.byCode(область, it) }.firstOrNull { it.kind == "requirement" } ?: return null
        val родитель = store.byCode(область, кодРодителя) ?: return null
        реестр.link("derives_from", дитя.id, родитель.id, Provenance(Channel.SERVICE, автор), rationale = причина, subtype = "derivation")
        return "derives_from ${дитя.code}→${родитель.code}"
    }

    private fun число(d: Double): String =
        if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()

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
    /**
     * Отклонить или отложить выбранные предложения — решение человека, не
     * приём. Отклонённое больше не предлагается, отложенное ждёт и видно
     * счётчиком: «рассмотрено 118 из 130, отложено 12». Решение обратимо:
     * то же действие с `decision: "pending"` возвращает строку в работу.
     */
    private fun отклонить(проект: String, код: String, тело: JsonNode): V2Router.Ответ {
        val область = Area.Project(проект)
        val запись = store.byCode(область, код)
            ?: throw NoSuchElementException("запуска «$код» в проекте нет")
        val выбранные = тело.path("chosen").map { it.asText() }.filter { it.isNotBlank() }
        require(выбранные.isNotEmpty()) {
            "не выбрано ни одного предложения: отметьте строки — решение принимается по отмеченным"
        }
        val что = тело.path("decision").asText("rejected")
        require(что in РЕШЕНИЯ) {
            "решение «$что» не из перечня: rejected — отклонить, deferred — отложить, pending — вернуть в работу"
        }
        val известные = предложения(проект, задание(проект, код)).keys
        val чужие = выбранные.filterNot { it in известные }
        require(чужие.isEmpty()) { "предложений ${чужие.joinToString(", ")} в «$код» нет: обновите диф" }

        val автор = автор(тело)
        val причина = тело.path("reason").asText("").trim()
        val документ = запись.doc.deepCopy<JsonNode>() as ObjectNode
        val решения = документ.path(РЕШЕНИЯ_ПОЛЕ) as? ObjectNode ?: документ.putObject(РЕШЕНИЯ_ПОЛЕ)
        выбранные.forEach { имя ->
            if (что == "pending") {
                решения.remove(имя)
            } else {
                решения.putObject(имя)
                    .put("decision", что).put("by", автор)
                    .put("at", java.time.OffsetDateTime.now().withNano(0).toString())
                    .put("reason", причина)
            }
        }
        store.update(запись.id, документ, Provenance(Channel.MANUAL, автор, source = код), status = запись.status)

        val отложено = решения.properties().count { it.value.path("decision").asText() == "deferred" }
        val отклонено = решения.properties().count { it.value.path("decision").asText() == "rejected" }
        val ответ = mapper.createObjectNode().put("run", код).put("decision", что)
            .put("touched", выбранные.size).put("deferred", отложено).put("rejected", отклонено)
        массив(ответ, "chosen", выбранные)
        ответ.put(
            "note",
            when (что) {
                "rejected" -> "отклонено ${выбранные.size}: больше не предлагается; решение обратимо — «вернуть в работу»"
                "deferred" -> "отложено ${выбранные.size}: воротам не мешает, видно счётчиком"
                else -> "возвращено в работу ${выбранные.size}"
            },
        )
        return V2Router.Ответ(200, ответ)
    }

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
        // Снятие ВЫБОРКОЙ (шип 2, экран 12: «снять принятие» для выборки):
        // названы коды — снимаются только они, пакет остаётся с остальными.
        // Без кодов — прежнее поведение: пакет отменяется целиком.
        val выборка = тело.path("chosen").map { it.asText() }.filter { it.isNotBlank() }
        val созданы = последний.path("created").map { it.asText() }
        val чужие = выборка.filterNot { it in созданы }
        require(чужие.isEmpty()) {
            "в последнем пакете запуска «$код» нет ${чужие.joinToString(", ")}: снимать можно только принятое им"
        }
        созданы.filter { выборка.isEmpty() || it in выборка }.forEach { имя ->
            val сущность = store.byCode(область, имя)
            if (сущность == null || сущность.status == СНЯТО) {
                неснято += имя
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
        val осталось = созданы.filterNot { выборка.isEmpty() || it in выборка }
        if (осталось.isEmpty()) {
            (пакеты as ArrayNode).remove(пакеты.size() - 1)
        } else {
            // Пакет живёт дальше — в нём остаются те, кого не снимали: отмена
            // остатка по-прежнему обратима одним действием.
            val обновлён = (последний as ObjectNode).deepCopy()
            обновлён.putArray("created").also { м -> осталось.forEach { м.add(it) } }
            (пакеты as ArrayNode).set(пакеты.size() - 1, обновлён)
        }
        store.update(запись.id, документ, запись.provenance, status = запись.status)

        val ответ = mapper.createObjectNode().put("run", код).put("undone", снято.size)
            .put("left_in_batch", осталось.size)
        массив(ответ, "cancelled", снято)
        массив(ответ, "already_gone", неснято)
        ответ.put(
            "note",
            (if (выборка.isEmpty()) "пакет отменён: снято с учёта ${снято.size}"
            else "снято с учёта ${снято.size}; в пакете осталось ${осталось.size}") +
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
                    // Русское имя поля и стадия обязательности — истина схем
                    // 18.09: экран показывает label, а спрашивает только то,
                    // что названо `accept` и само не подставляется.
                    val подписи = спец.putObject("labels")
                    вид.labels.forEach { (поле, имя) -> подписи.put(поле, имя) }
                    val стадии = спец.putObject("required_at")
                    вид.requiredAt.forEach { (поле, правило) -> стадии.put(поле, правило) }
                    val перечни = спец.putObject("enums")
                    вид.enums.forEach { (поле, значения) -> массив(перечни, поле, значения) }
                    // Русские ЗНАЧЕНИЯ перечислений (истина 19.09,
                    // `enum_labels`): «enum → селект русскими значениями».
                    // Своих переводов у экрана нет и быть не может.
                    val значенияПолей = спец.putObject("enum_labels")
                    (вид.fields + вид.enums.keys + "status").distinct().forEach { поле ->
                        val метки = orbita.kernel.schema.Enums.значения(вид.code, поле)
                        if (метки.isEmpty()) return@forEach
                        val узел = значенияПолей.putObject(поле)
                        метки.forEach { (значение, имя) -> узел.put(значение, имя) }
                    }
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
        val решения = store.byCode(Area.Project(проект), итог.id)?.doc?.path(РЕШЕНИЯ_ПОЛЕ)?.takeIf { it.isObject }
        предложения(проект, итог).forEach { (имя, предложение) ->
            (диф.get(предложение.verdict.code) as ArrayNode).add(видПредложения(имя, предложение, решения))
        }
        // «Рассмотрено N из M, отложено K» — счёт решений ведёт сервер.
        val отложено = решения?.properties()?.count { it.value.path("decision").asText() == "deferred" } ?: 0
        val отклонено = решения?.properties()?.count { it.value.path("decision").asText() == "rejected" } ?: 0
        счёт.put("deferred", отложено).put("rejected", отклонено)
        return узел
    }

    private fun видПредложения(имя: String, п: FormationProposal, решения: JsonNode? = null): ObjectNode {
        val узел = mapper.createObjectNode()
            .put("proposal", имя).put("concept", п.concept)
            .put("verdict", п.verdict.code).put("mark", п.sourceMark.name)
        п.targetRef?.let { узел.put("target_ref", it) }
        if (п.diffField.isNotBlank()) узел.put("diff_field", п.diffField)
        п.confidence?.let { узел.put("confidence", it) }
        if (п.rankHint.isNotBlank()) узел.put("rank_hint", п.rankHint)
        узел.set<JsonNode>("payload", содержимое(п))
        массив(узел, "missing", п.missing)
        // Решение человека по строке: отклонено или отложено. Пусто — строка в работе.
        решения?.path(имя)?.takeIf { it.isObject }?.let { р ->
            узел.put("decision", р.path("decision").asText(""))
            узел.put("decision_by", р.path("by").asText(""))
            узел.put("decision_at", р.path("at").asText("").take(10))
            узел.put("decision_reason", р.path("reason").asText(""))
        }
        val основания = узел.putArray("basis")
        п.basis.forEach { основания.add(видОснования(it)) }
        return узел
    }

    private fun видОснования(о: Basis): ObjectNode {
        val узел = mapper.createObjectNode()
        // `type` и `words` — общие для всех оснований (диф 28.09): экран рисует
        // основание словами со ссылкой «к месту», не зная про факт/сущность/разрыв.
        узел.put("type", о.kindTag).put("words", о.words())
        о.rank?.let { узел.put("rank", it).put("rank_word", Authority.word(it)) }
        when (о) {
            is Basis.Fact -> {
                узел.put("fact", о.factId)
                о.material?.let { узел.put("material", it) }
                о.anchor?.let { узел.put("anchor", it) }
                о.mark?.let { узел.put("mark", it.name) }
                (о.source as? FactSource.FromExpert)?.let {
                    узел.put("account", it.account).put("role", it.role).put("at", it.at)
                }
            }
            is Basis.Entity -> {
                узел.put("kind", о.kind).put("code", о.code)
                о.anchor?.let { узел.put("anchor", it) }
            }
            is Basis.Gap -> узел.put("kind", о.kind).put("node", о.node).put("facet", о.facet).put("point", о.point)
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
    /**
     * Подстановка по стадии (`required_at` истины схем, 18.09).
     *
     * Истина называет не только «обязательно», но и КОГДА и ЧЕМ: `accept:system`
     * — ставит система, `accept:from_basis` — берётся из оснований,
     * `accept:auto` — определяется само, `accept:default(x)` — умолчание.
     * Форма приёма спрашивает у человека только то, что названо `accept` и
     * само не подставляется (журнал ПМИ-7, З-09: «все поля обязательны» —
     * работать нельзя).
     */
    private fun поСтадии(п: FormationProposal, содержимое: ObjectNode): ObjectNode {
        val вид = GeneratedOntology.byCode[п.concept]?.targetKindCode
            ?.let { orbita.kernel.schema.GeneratedKinds.byCode[it] } ?: return содержимое
        вид.requiredAt.forEach { (поле, правило) ->
            val стадия = правило.substringBefore(":").trim()
            val чем = правило.substringAfter(":", "").trim()
            if (стадия != ПРИЁМ || чем.isBlank()) return@forEach
            if (содержимое.path(поле).asText("").isNotBlank() || содержимое.path(поле).isArray) return@forEach
            when {
                // Код даёт хранилище при заведении — здесь его не выдумывают.
                чем == "system" -> Unit
                чем == "from_basis" -> основанияПолем(п)?.let { содержимое.set<JsonNode>(поле, it) }
                чем == "auto" -> авто(поле, содержимое)?.let { содержимое.put(поле, it) }
                чем.startsWith("default(") -> умолчание(чем)?.let { содержимое.put(поле, it) }
            }
        }
        // Показатель истина требует к базированию, а ПРЕДЛОЖИТЬ его из
        // формулировки можно сразу (журнал ПМИ-7, З-12) — как шаблон EARS.
        вид.measures.forEach { поле ->
            if (!содержимое.path(поле).isObject) показатель(содержимое)?.let { содержимое.set<JsonNode>(поле, it) }
        }
        return содержимое
    }

    /**
     * Источник записи — из оснований предложения (диф 28.09, model_basis_rule):
     * факт ложится видом «material» (документ/якорь), сущность — своим видом
     * (`requirement.source` kind requirement|goal|need|constraint), разрыв в
     * source не ложится — он идёт в ссылку риска на свой разрыв.
     */
    private fun основанияПолем(п: FormationProposal): JsonNode? {
        if (п.basis.isEmpty()) return null
        val массив = mapper.createArrayNode()
        п.basis.forEach { основание ->
            when (основание) {
                is Basis.Fact -> {
                    val узел = массив.addObject().put("kind", "material")
                    узел.put("ref", основание.material ?: основание.factId)
                    основание.anchor?.takeIf { it.isNotBlank() }?.let { узел.put("anchor", it) }
                }
                is Basis.Entity -> {
                    val узел = массив.addObject().put("kind", основание.kind)
                    узел.put("ref", основание.code)
                    основание.anchor?.takeIf { it.isNotBlank() }?.let { узел.put("anchor", it) }
                }
                is Basis.Gap -> Unit
            }
        }
        return массив.takeIf { it.size() > 0 }
    }

    /** Автоопределение: шаблон EARS — по форме самой формулировки. */
    private fun авто(поле: String, содержимое: ObjectNode): String? {
        if (поле != ШАБЛОН_EARS) return null
        val формулировка = содержимое.path("statement").asText("").trim().ifBlank { return null }
        return ears?.invoke(формулировка)
    }

    /**
     * Показатель из формулировки (журнал ПМИ-7, З-12).
     *
     * «100 % объектов … к 2033 году» — мера в тексте есть, а поле показателя
     * стояло прочерком: инженер перебивал руками то, что видно глазом. Число с
     * единицей рядом становится ПРЕДЛОЖЕНИЕМ величины; оператор берётся из слов
     * («не менее» → ≥, «не более» → ≤); год — горизонт, а не показатель, и в
     * величину не идёт. Не нашлось — поле пустое: обязательность стоит стадией
     * `baseline|tbr`, а выдумывать меру нельзя.
     */
    private fun показатель(содержимое: ObjectNode): ObjectNode? {
        val формулировка = содержимое.path("statement").asText("").trim()
        if (формулировка.isBlank()) return null
        val найдено = ЧИСЛО_С_ЕДИНИЦЕЙ.findAll(формулировка)
            .map { it.groupValues[1].replace(",", ".") to it.groupValues[2] }
            .firstOrNull { (число, единица) ->
                единица !in ГОД && число.toDoubleOrNull() != null && units?.invoke(единица) == true
            }
            ?: return null
        val величина = mapper.createObjectNode()
        // Оператор — КОД истины («>=»), а не типографский знак: знак «≥» схема
        // величины не знает, и запись стала бы невалидной (поймано 19.09).
        when {
            НЕ_МЕНЕЕ.containsMatchIn(формулировка) -> величина.put("op", ">=")
            НЕ_БОЛЕЕ.containsMatchIn(формулировка) -> величина.put("op", "<=")
        }
        величина.put("value", найдено.first.toDouble())
        величина.put("unit", найдено.second)
        return величина
    }

    /** «default(project на сцене 8)» → project: первое слово — значение. */
    private fun умолчание(чем: String): String? =
        чем.substringAfter("(").substringBeforeLast(")").trim().substringBefore(" ").trim().ifBlank { null }

    private fun сПравкой(п: FormationProposal, правка: JsonNode?): ObjectNode {
        val содержимое = поСтадии(п, содержимое(п))
        if (правка == null || !правка.isObject || правка.isEmpty) return содержимое
        val вид = GeneratedOntology.byCode[п.concept]?.targetKindCode
            ?.let { orbita.kernel.schema.GeneratedKinds.byCode[it] }
        val известные = (вид?.fields.orEmpty() + GeneratedOntology.byCode[п.concept]?.fields?.keys.orEmpty()).toSortedSet()
        правка.properties().forEach { (поле, значение) ->
            require(известные.isEmpty() || поле in известные) {
                "поля «$поле» у понятия «${п.concept}» нет: истина знает ${известные.joinToString(" · ")}"
            }
            val перечень = вид?.enums?.get(поле).orEmpty()
            var текст = if (значение.isValueNode) значение.asText("").trim() else ""
            // Экран показывает русские значения (истина 19.09) — значит и
            // возвращает их: код узнаётся картой владельца, а не переводом в
            // коде. Пришёл уже кодом — кодом и остаётся.
            if (вид != null && текст.isNotBlank() && текст !in перечень) {
                orbita.kernel.schema.Enums.код(вид.code, поле, текст)?.let { текст = it }
            }
            require(перечень.isEmpty() || значение.isObject || текст.isBlank() || текст in перечень) {
                "значение «$текст» полю «$поле» не подходит: перечень истины — ${
                    перечень.joinToString(" · ") { orbita.kernel.schema.Enums.метка(вид?.code ?: "", поле, it) ?: it }
                }"
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
        /**
         * Число с единицей рядом: «100 %», «24 ч», «180 мин», «50 устройств».
         * Единица — знак процента или слово сразу после числа.
         */
        private val ЧИСЛО_С_ЕДИНИЦЕЙ = Regex("(\\d+(?:[.,]\\d+)?)\\s*(%|[а-яёА-ЯЁ]{1,12}|[A-Za-z]{1,6})")

        /** Год — горизонт, а не показатель: «к 2033 году» мерой не становится. */
        private val ГОД = setOf("г", "год", "году", "года", "гг")

        private val НЕ_МЕНЕЕ = Regex("не\\s+менее|не\\s+ниже|не\\s+меньше", RegexOption.IGNORE_CASE)
        private val НЕ_БОЛЕЕ = Regex("не\\s+более|не\\s+позднее|не\\s+выше|не\\s+превыша", RegexOption.IGNORE_CASE)

        /** Стадия «при приёме предложения» из истины схем (`required_at`). */
        const val ПРИЁМ: String = "accept"
        const val ШАБЛОН_EARS: String = "ears_pattern"

        val АДРЕСА: Set<String> = setOf(
            "/v2/synthesis/runs", "/v2/synthesis/diff", "/v2/synthesis/pending", "/v2/ontology/formation",
        )

        /** Состояния, из которых запуск ещё выйдет: экран продолжает опрос. */
        val ИДЁТ: Set<String> = setOf("queued", "running")

        const val ГОТОВ: String = "done"

        /** Принятые пакеты запуска: по ним «Отменить пакет» возвращает модель. */
        const val ПАКЕТЫ: String = "accepted_batches"
        const val РЕШЕНИЯ_ПОЛЕ: String = "decisions"
        val РЕШЕНИЯ: Set<String> = setOf("rejected", "deferred", "pending")

        /** Статус снятого с учёта: сущность остаётся в истории, из модели уходит. */
        const val СНЯТО: String = "cancelled"

        /** Виды, на которые обмен ссылается концом: функция или сторона (§2.2b). */
        val ССЫЛОЧНЫЕ_ВИДЫ: Set<String> = setOf("function", "stakeholder")
    }
}
