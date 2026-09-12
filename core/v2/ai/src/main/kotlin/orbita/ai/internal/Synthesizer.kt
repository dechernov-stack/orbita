// Синтез постановки из ПОЛЯ (ПОЛЕ-ЗНАНИЙ-V2-СИНТЕЗ §3): срез поля →
// предложения понятий с основаниями.
//
// Синтез идёт по всему полю, а не по последнему материалу: иначе постановка
// рассыпается по документам, и одна и та же нужда из записки, ТЗ и норматива
// заводится трижды. Отсюда срез: факты, которые человек ОСТАВИЛ в поле
// (принял или отметил), их темы и уже принятые понятия постановки — против
// принятого и выносится вердикт.
//
// Цена вопроса — токены, поэтому порядок здесь жёсткий:
//   · срез ограничен потолком онтологии (50 фактов и сущностей ВМЕСТЕ), и
//     урезание идёт по РАНГУ: обязательное вытесняет справочное, а не наоборот;
//   · сомнительное (результат исследования до подтверждения источников) в срез
//     не попадает вовсе, пока человек не принял такой факт явным решением;
//   · один живой вызов на срез: промпт детерминирован, и повтор того же среза
//     возвращает ответ из журнала (`cached = true`), не звоня.
//
// Чего служба не делает: не меняет принятое и не выбирает победителя в
// противоречии. Наружу идёт ПРЕДЛОЖЕНИЕ с основаниями и названным полем
// отличия; единственный путь изменения модели — сверка (knowledge `Reconcile`).
// Метка достоверности и ранги берутся НАШИ, из фактов-оснований: модели здесь
// верить нечему — она видит только то, что мы ей дали.
//
// ADR-069: чтение среза и запись предложений — на потоке запросов; в фоне
// живёт только сеть (BackgroundSynthesizer).
package orbita.ai.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.ai.api.AiService
import orbita.ai.api.Answer
import orbita.ai.api.Basis
import orbita.ai.api.Diff
import orbita.ai.api.FieldDrift
import orbita.ai.api.FormationProposal
import orbita.ai.api.ProviderUnavailable
import orbita.ai.api.SynthesisRun
import orbita.ai.api.Verdict
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.KnowledgeFlag
import orbita.kernel.api.Provenance
import orbita.knowledge.api.Authority
import orbita.knowledge.api.Disposition
import orbita.knowledge.api.Fact
import orbita.knowledge.api.FactSource
import orbita.knowledge.api.Intake
import orbita.knowledge.api.SourceMark
import orbita.knowledge.schema.Concept
import orbita.knowledge.schema.GeneratedOntology
import java.security.MessageDigest

class Synthesizer(
    private val store: EntityStore,
    private val intake: Intake,
    private val service: AiService,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    /**
     * Срез поля: что ушло в промпт, чем он помечен и сколько поля не влезло.
     *
     * Готовится ЦЕЛИКОМ на потоке запросов: дальше он самодостаточен, и
     * сетевой вызов можно делать где угодно (ADR-069).
     *
     * @property total сколько элементов поля было до урезания
     * @property note словами о срезе — «срез урезан: N из M»
     */
    class Slice internal constructor(
        val fingerprint: String,
        val size: Int,
        val total: Int,
        val note: String,
        val prompt: String,
        internal val facts: Map<String, Fact>,
        internal val entities: Map<String, Entity>,
    )

    // --- живой путь ---------------------------------------------------------

    /**
     * Синтез одним вызовом: срез → живой вызов → предложения.
     *
     * Прямой путь нужен ручной команде и тестам; на стенде синтез идёт фоновой
     * задачей, потому что считается минутами (BackgroundSynthesizer).
     */
    fun synthesize(project: String, trigger: String, author: String): SynthesisRun {
        val срез = prepare(project)
        val запуск = open(project, trigger, author, срез)
        val ответ = try {
            service.ask(project, KIND, срез.prompt, maxTokens = БЮДЖЕТ_СИНТЕЗА)
        } catch (e: ProviderUnavailable) {
            // Отказ канала — состояние запуска, а не потерянный вызов: запись
            // остаётся в поле с названной причиной.
            return fail(project, запуск.id, "синтез недоступен: ${e.message}")
        }
        return apply(project, запуск.id, author, срез, ответ, journaled = true)
    }

    /** Ответ на этот срез уже в журнале — вызова не будет. */
    fun cached(project: String, slice: Slice): Answer? = service.cached(project, slice.prompt)

    // --- срез: чтение базы, всегда на потоке запросов ------------------------

    fun prepare(project: String): Slice {
        // Развилка поведения одна на систему (kernel): на проекте прохода
        // ПМИ-5 синтеза нет вовсе, и фоновая задача по событию поля там не
        // заводится даже случайно.
        require(KnowledgeFlag.on(store, project)) { ВЫКЛЮЧЕН }
        val поле = отбор(project)
        return Slice(
            fingerprint = отпечаток(project, поле.записи),
            size = поле.записи.size,
            total = поле.всего,
            note = заметка(поле),
            prompt = промпт(project, поле),
            facts = поле.факты.associate { (факт, _) -> факт.id to факт },
            entities = поле.сущности.associateBy { it.code },
        )
    }

    /**
     * Изменилось ли поле с последнего запуска — «поле изменилось: N».
     *
     * Считает СЕРВЕР: клиент решений не принимает и ничего не считает. Промпт
     * здесь не собирается — дрейф спрашивают часто, а строить текст ради
     * счётчика незачем.
     */
    fun drift(project: String): FieldDrift {
        val поле = отбор(project)
        val слепок = отпечаток(project, поле.записи)
        val последний = запуски(Area.Project(project)).lastOrNull()
            ?: return FieldDrift(поле.записи.size, null, слепок)
        if (последний.doc.path("slice_fingerprint").asText("") == слепок) {
            return FieldDrift(0, последний.code, слепок)
        }
        // Что именно изменилось, видно по времени: элементы среза, тронутые
        // после запуска. Совпасть с нулём при разном отпечатке это может лишь
        // тогда, когда из поля что-то УБЫЛО, — и это тоже изменение.
        val тронуто = поле.записи.count { it.updatedAt.isAfter(последний.createdAt) }
        return FieldDrift(тронуто.coerceAtLeast(1), последний.code, слепок)
    }

    /**
     * Отбор поля под потолок среза.
     *
     * Почему принятые понятия берут свою долю потолка, а не встают в общую
     * очередь по рангу: ранга у сущности нет (он живёт у факта), а без
     * принятого всякое предложение вышло бы «новым» — синтез завёл бы
     * постановку заново поверх готовой. Поэтому половина потолка отдана
     * принятому, и обе половины добирают друг за другом, когда одна не полна.
     */
    private fun отбор(project: String): Отбор {
        val область = Area.Project(project)
        val карточки = store.list(область, "fact")
            .filter { it.status !in СНЯТЫЕ }
            .associateBy { it.code }
        val факты = intake.facts(project)
            .mapNotNull { факт -> карточки[факт.id]?.let { факт to it } }
            .filter { (факт, _) -> факт.disposition in ВИДНЫЕ && вСрез(факт) }
            // Урезание по рангу: обязательное вытесняет справочное. Порядок
            // внутри ранга — по коду, иначе отпечаток среза плясал бы от
            // порядка выдачи хранилища и кэш не срабатывал бы никогда.
            .sortedWith(compareBy<Pair<Fact, Entity>>({ Authority.weight(it.first.authority) }, { it.first.id }))
        val сущности = ВИДЫ_ПОНЯТИЙ
            .flatMap { вид -> store.list(область, вид) }
            .filter { it.status !in СНЯТЫЕ }
            .sortedBy { it.code }

        val доляПринятого = minOf(сущности.size, ПОТОЛОК / 2)
        val взятыеФакты = факты.take(ПОТОЛОК - доляПринятого)
        val взятыеСущности = сущности.take(ПОТОЛОК - взятыеФакты.size)
        val темы = intake.topics(project)
            .filter { тема -> взятыеФакты.any { (факт, _) -> факт.topic == тема.id } }
            .associate { it.id to it.label }
        return Отбор(
            факты = взятыеФакты,
            сущности = взятыеСущности,
            темы = темы,
            всего = факты.size + сущности.size,
        )
    }

    /**
     * Сомнительное в срез само не входит.
     *
     * Ранг `doubtful` носит результат внешнего исследования до подтверждения
     * источников человеком (research_rule). Пускать такое в постановку нельзя,
     * но и запирать навсегда незачем: факт входит, когда инженер ПРИНЯЛ его
     * явным решением — диспозиция `adopted` ставится только рукой и с поводом.
     */
    private fun вСрез(факт: Fact): Boolean =
        факт.authority != Authority.DOUBTFUL || факт.disposition == Disposition.ADOPTED

    private fun заметка(отбор: Отбор): String =
        if (отбор.всего > отбор.записи.size) "срез урезан: ${отбор.записи.size} из ${отбор.всего}"
        else "срез: ${отбор.записи.size} из ${отбор.всего} элементов поля"

    /**
     * Отпечаток среза: проект · правила образования · версия промпта · состав
     * среза версиями. Тот же срез — тот же отпечаток — ответ из журнала.
     */
    private fun отпечаток(project: String, записи: List<Entity>): String {
        val слепок = buildString {
            append(project).append('\n')
            append(GeneratedOntology.ontologyVersion).append('\n')
            append(ВЕРСИЯ_ПРОМПТА).append('\n')
            записи.sortedBy { it.code }.forEach { append(it.code).append(':').append(it.version).append('\n') }
        }
        return хеш(слепок)
    }

    // --- запись запуска: тоже только на потоке запросов ----------------------

    /** Завести запуск: код SR-N появляется сразу, до всякой сети. */
    fun open(project: String, trigger: String, author: String, slice: Slice): SynthesisRun {
        require(trigger.isNotBlank()) { "запуск синтеза без причины запуска" }
        require(author.isNotBlank()) { "у запуска синтеза нет автора: решение принимает человек" }
        val область = Area.Project(project)
        val документ = mapper.createObjectNode()
        // Перечень причин запуска стережёт схема вида: второй копии перечня в
        // коде нет — она разошлась бы с истиной молча.
        документ.put("trigger", trigger)
        документ.put("slice_fingerprint", slice.fingerprint)
        документ.put("slice_size", slice.size)
        документ.putArray("proposals")
        пустойДиф(документ)
        документ.put("ontology_version", GeneratedOntology.ontologyVersion)
        документ.put("cached", false)
        // Вид synthesis_run один на синтез и сверку (истина схем второго не
        // заводит), а различает их метка: без неё список запусков синтеза
        // собирал бы заодно и сверки ручного ввода.
        документ.putArray("tags").add(МЕТКА)
        документ.put("notes", slice.note)
        val запуск = store.create(
            следующий(область, "synthesis_run", "SR"), "synthesis_run", область,
            // Сцены у запуска нет: синтез идёт по всему полю, а не в сцене.
            null, документ, Provenance(Channel.SERVICE, author, fingerprint = slice.fingerprint),
            status = "queued",
        )
        return вид(запуск)
    }

    /**
     * Применить ответ службы: журнал (если ещё не записан), карточки
     * предложений и диф запуска. Всё — на потоке запросов.
     *
     * Брак модели не уносит весь запуск: негодное предложение отклоняется
     * поимённо с причиной и попадает в примечание, годные остаются.
     */
    fun apply(
        project: String,
        run: String,
        author: String,
        slice: Slice,
        answer: Answer,
        journaled: Boolean = false,
    ): SynthesisRun {
        if (!journaled && !answer.cached) service.record(project, KIND, slice.prompt, answer)
        val область = Area.Project(project)
        val запись = запуск(область, run)
        val корень = разобрать(answer.text)
            ?: return fail(project, run, "ответ службы не разобран: синтез ждёт JSON с полем proposals")

        val отказы = mutableListOf<String>()
        val принятые = mutableListOf<ObjectNode>()
        val коды = mutableListOf<String>()
        корень.path("proposals").forEachIndexed { номер, узел ->
            runCatching { предложение(slice, узел) }.onSuccess { предмет ->
                val код = карточка(область, author, slice, предмет)
                коды += код
                принятые += вЗапись(предмет, код)
            }.onFailure { беда ->
                отказы += "предложение $номер отклонено: ${беда.message}"
            }
        }

        val документ = запись.doc.deepCopy<JsonNode>() as ObjectNode
        // Срез окна накопления пересобирается по каждому событию поля, а
        // отпечаток в запуске записан по первому: не переписать его здесь
        // значит сказать полю, что оно изменилось, сразу после синтеза —
        // индикатор «поле изменилось» показывал бы дрейф на ровном месте.
        документ.put("slice_fingerprint", slice.fingerprint)
        документ.put("slice_size", slice.size)
        val массив = документ.putArray("proposals")
        коды.forEach { массив.add(it) }
        val диф = пустойДиф(документ)
        принятые.forEach { узел ->
            (диф.get(узел.path("verdict").asText()) as ArrayNode).add(узел)
        }
        документ.put("cached", answer.cached)
        документ.put("notes", примечание(slice, принятые, отказы, answer.cached))
        val готово = store.update(
            запись.id, документ,
            Provenance(Channel.SERVICE, author, fingerprint = slice.fingerprint),
            status = "done",
        )
        return вид(готово)
    }

    /**
     * Вызов ушёл в канал — запуск идёт.
     *
     * Статус пишется ЗДЕСЬ, на потоке запросов: фоновый поток базу не трогает
     * (ADR-069), а «в очереди» на экране всё время работы службы читается как
     * «ничего не происходит».
     */
    fun running(project: String, run: String): SynthesisRun {
        val запись = запуск(Area.Project(project), run)
        if (запись.status != "queued") return вид(запись)
        return вид(store.update(запись.id, запись.doc, запись.provenance, status = "running"))
    }

    /** Отказ запуска: причина называется, запись остаётся в поле. */
    fun fail(project: String, run: String, reason: String): SynthesisRun {
        require(reason.isNotBlank()) { "отказ запуска синтеза без названной причины" }
        val запись = запуск(Area.Project(project), run)
        val документ = запись.doc.deepCopy<JsonNode>() as ObjectNode
        документ.put("error", reason)
        документ.put("notes", (документ.path("notes").asText("") + " · " + reason).trim(' ', '·'))
        return вид(store.update(запись.id, документ, запись.provenance, status = SynthesisRun.ERROR))
    }

    /** Запуск по коду; чужой (сверка ручного ввода) сюда не попадает. */
    fun view(project: String, run: String): SynthesisRun? =
        store.byCode(Area.Project(project), run)?.takeIf { it.kind == SynthesisRun.KIND && наш(it) }?.let { вид(it) }

    fun list(project: String): List<SynthesisRun> = запуски(Area.Project(project)).map { вид(it) }

    // --- разбор ответа службы ------------------------------------------------

    /**
     * Предложение из ответа модели. Всё, чему верить нельзя, берётся не у неё:
     * происхождение, ранги и метка достоверности — из НАШИХ фактов, нехватка
     * связей — из правил образования.
     */
    private fun предложение(slice: Slice, узел: JsonNode): FormationProposal {
        val код = узел.path("concept").asText("").trim()
        val правило = GeneratedOntology.byCode[код]
            ?: error(
                "понятие «${код.ifBlank { "без имени" }}» вне правил образования — " +
                    "образуются только ${GeneratedOntology.concepts.joinToString(" · ") { it.code }}"
            )
        val факты = основания(узел).map { основание ->
            slice.facts[основание] ?: error("основание «$основание» вне среза: придуманных кодов фактов не бывает")
        }
        require(факты.isNotEmpty()) {
            "понятие «$код» без факта-основания — предложений без оснований не бывает"
        }
        val вердикт = вердиктПоКоду(узел.path("verdict").asText(""))
        val мишень = узел.path("target").asText("").trim().ifBlank { null }
        if (вердикт != Verdict.NEW) {
            require(мишень != null && slice.entities.containsKey(мишень)) {
                "вердикт «${вердикт.code}» назвал «${мишень ?: "ничего"}» — такого принятого понятия в срезе нет"
            }
        }
        val содержимое = полезное(узел.path("payload"))
        val опоры = факты.map { основание(it) }
        return FormationProposal(
            concept = код,
            payload = содержимое,
            basis = опоры,
            verdict = вердикт,
            sourceMark = метка(факты),
            targetRef = if (вердикт == Verdict.NEW) null else мишень,
            diffField = узел.path("diff_field").asText("").trim(),
            confidence = узел.path("confidence").takeIf { it.isNumber }?.asDouble(),
            missing = нехватка(правило, содержимое),
            // Ранги в противоречии — подсказка человеку, а не выбор победителя:
            // поля «победитель» нет ни у одного типа этого контура.
            rankHint = if (вердикт == Verdict.CONTRADICT) подсказкаРангов(опоры) else "",
        )
    }

    /** Коды оснований: строками либо объектами `{"fact": "F-0007"}`. */
    private fun основания(узел: JsonNode): List<String> =
        узел.path("basis").mapNotNull { основание ->
            val код = if (основание.isTextual) основание.asText() else основание.path("fact").asText("")
            код.trim().ifBlank { null }
        }.distinct()

    private fun вердиктПоКоду(текст: String): Verdict = Verdict.entries.firstOrNull { it.code == текст.trim().lowercase() }
        ?: error(
            "вердикт «${текст.ifBlank { "без имени" }}» неизвестен — " +
                "их четыре: ${Verdict.entries.joinToString(" · ") { it.code }}"
        )

    private fun полезное(узел: JsonNode): Map<String, String> {
        if (!узел.isObject) return emptyMap()
        val поля = linkedMapOf<String, String>()
        узел.fields().forEach { (имя, значение) ->
            val текст = if (значение.isValueNode) значение.asText("") else значение.toString()
            if (текст.isNotBlank()) поля[имя] = текст
        }
        return поля
    }

    private fun основание(факт: Fact): Basis = Basis(
        factId = факт.id,
        material = факт.material.ifBlank { null },
        anchor = факт.anchor,
        authority = факт.authority,
        mark = факт.mark,
        source = факт.source,
    )

    /**
     * Метка достоверности предложения — самая слабая из меток оснований:
     * утверждение не бывает крепче своего слабейшего основания. Умолчания
     * здесь нет: метка берётся у фактов, а не приписывается службой.
     */
    private fun метка(факты: List<Fact>): SourceMark = when {
        факты.any { it.mark == SourceMark.П } -> SourceMark.П
        факты.any { it.mark == SourceMark.В } -> SourceMark.В
        else -> SourceMark.И
    }

    /** Ранги оснований словами: «обязательный против справочного». */
    private fun подсказкаРангов(основания: List<Basis>): String {
        val ранги = основания.mapNotNull { it.authority }.distinct()
        return when {
            ранги.isEmpty() -> "ранги оснований не назначены — доверие называет человек"
            ранги.size == 1 -> "оба основания: ${Authority.word(ранги.single())}"
            else -> ранги.sortedBy { Authority.weight(it) }.joinToString(" против ") { Authority.word(it) }
        }
    }

    /**
     * Незакрытые обязательные связи понятия («owns→stakeholder»).
     *
     * С ними предложение остаётся предложением С ПОМЕТОЙ и сущностью не
     * становится — это инвариант онтологии, а не совет.
     */
    private fun нехватка(понятие: Concept, содержимое: Map<String, String>): List<String> =
        понятие.mustLink.map { it.trim() }.filter { токен ->
            токен.isNotBlank() && требуется(токен, содержимое) && содержимое[поле(понятие, токен)].isNullOrBlank()
        }

    /**
     * Условие обязательности («normative_basis if obligation»): связь нужна
     * только такому источнику. Онтология сама сопоставляет источник и тип —
     * «obligation→regulatory».
     */
    private fun требуется(токен: String, содержимое: Map<String, String>): Boolean {
        val условие = токен.substringAfter(" if ", "").trim()
        if (условие.isBlank()) return true
        return содержимое["kind"] == условие || (условие == "obligation" && содержимое["type"] == "regulatory")
    }

    /**
     * Каким полем понятия называется связь. Истина — `fields` того же понятия:
     * онтология сама говорит, что нужда несёт сторону полем `stakeholder`, а
     * цель — нужды полем `needs`.
     */
    private fun поле(понятие: Concept, токен: String): String {
        val голова = токен.substringBefore(" if ").trim()
        if (!голова.contains("→")) return голова
        val связь = голова.substringBefore("→").trim()
        val вид = голова.substringAfter("→").substringBefore(">=").trim()
        return понятие.fields.entries.firstOrNull { it.value.contains(связь) }?.key
            ?: понятие.fields.keys.firstOrNull { it == вид || it == "${вид}s" }
            ?: вид
    }

    /** Ответ модели деревом; не разобрался — запуск отказывает с причиной. */
    private fun разобрать(raw: String): JsonNode? = runCatching {
        mapper.readTree(raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
    }.getOrNull()?.takeIf { it.isObject }

    // --- карточки предложений и диф ------------------------------------------

    /**
     * Карточка предложения (вид `proposal`): предложение живёт записью, а не
     * строкой на экране — по нему человек принимает решение, и решение видно
     * в самой карточке.
     */
    private fun карточка(область: Area, author: String, slice: Slice, предложение: FormationProposal): String {
        val документ = mapper.createObjectNode()
        документ.put("package_kind", ПАКЕТ)
        документ.putObject("source").put("prompt", slice.fingerprint)
        val предмет = документ.putArray("items").addObject()
        предмет.put("class", предложение.concept)
        val содержимое = предмет.putObject("payload")
        предложение.payload.forEach { (имя, значение) -> содержимое.put(имя, значение) }
        val якоря = предмет.putArray("anchors")
        предложение.basis.forEach { основание ->
            якоря.add(listOfNotNull(основание.factId, основание.material, основание.anchor).joinToString(" "))
        }
        предмет.put("source_mark", предложение.sourceMark.name)
        // Решения нет, пока его не принял человек: карточка рождается открытой.
        предмет.put("decision", "pending")
        предмет.put("reason", причина(предложение))
        предложение.targetRef?.let { предмет.put("target_ref", it) }
        документ.put("fingerprint", slice.fingerprint)
        return store.create(
            следующий(область, "proposal", "PR"), "proposal", область, null, документ,
            Provenance(Channel.SERVICE, author, fingerprint = slice.fingerprint), status = "pending",
        ).code
    }

    /** Почему предложение такое — словами, до нажатия. */
    private fun причина(предложение: FormationProposal): String {
        val мишень = предложение.targetRef.orEmpty()
        val основа = when (предложение.verdict) {
            Verdict.NEW -> "нового понятия среди принятых нет; оснований — ${предложение.basis.size}"
            Verdict.AUGMENT -> "дополняет «$мишень» по полю «${предложение.diffField}»"
            Verdict.CONTRADICT ->
                "расходится с «$мишень» по полю «${предложение.diffField}»: ${предложение.rankHint}; " +
                    "оба значения показываются, победителя выбирает человек"
            Verdict.CONFIRM -> "подтверждает «$мишень» по полю «${предложение.diffField}» из другого материала"
        }
        if (предложение.missing.isEmpty()) return основа
        return "$основа · не закрыто: ${предложение.missing.joinToString(", ")} — " +
            "пока связь не названа, это предложение, а не сущность"
    }

    private fun вЗапись(предложение: FormationProposal, карточка: String): ObjectNode {
        val узел = mapper.createObjectNode()
        узел.put("proposal", карточка)
        узел.put("concept", предложение.concept)
        узел.put("verdict", предложение.verdict.code)
        val содержимое = узел.putObject("payload")
        предложение.payload.forEach { (имя, значение) -> содержимое.put(имя, значение) }
        val основания = узел.putArray("basis")
        предложение.basis.forEach { основание ->
            val запись = основания.addObject()
            запись.put("fact", основание.factId)
            основание.material?.let { запись.put("material", it) }
            основание.anchor?.let { запись.put("anchor", it) }
            основание.authority?.let { запись.put("authority", it) }
            основание.mark?.let { запись.put("mark", it.name) }
            (основание.source as? FactSource.FromExpert)?.let { источник ->
                запись.put("account", источник.account)
                запись.put("role", источник.role)
                запись.put("at", источник.at)
            }
        }
        предложение.targetRef?.let { узел.put("target_ref", it) }
        if (предложение.diffField.isNotBlank()) узел.put("diff_field", предложение.diffField)
        предложение.confidence?.let { узел.put("confidence", it) }
        if (предложение.missing.isNotEmpty()) {
            val нехватка = узел.putArray("missing")
            предложение.missing.forEach { нехватка.add(it) }
        }
        узел.put("source_mark", предложение.sourceMark.name)
        if (предложение.rankHint.isNotBlank()) узел.put("rank_hint", предложение.rankHint)
        return узел
    }

    private fun изЗаписи(узел: JsonNode): FormationProposal = FormationProposal(
        concept = узел.path("concept").asText(""),
        payload = полезное(узел.path("payload")),
        basis = узел.path("basis").map { основаниеИзЗаписи(it) },
        verdict = вердиктПоКоду(узел.path("verdict").asText("")),
        sourceMark = runCatching { SourceMark.valueOf(узел.path("source_mark").asText("И")) }
            .getOrDefault(SourceMark.И),
        targetRef = узел.path("target_ref").asText("").ifBlank { null },
        diffField = узел.path("diff_field").asText(""),
        confidence = узел.path("confidence").takeIf { it.isNumber }?.asDouble(),
        missing = узел.path("missing").map { it.asText() },
        rankHint = узел.path("rank_hint").asText(""),
    )

    private fun основаниеИзЗаписи(узел: JsonNode): Basis {
        val учётка = узел.path("account").asText("")
        val материал = узел.path("material").asText("").ifBlank { null }
        val якорь = узел.path("anchor").asText("").ifBlank { null }
        return Basis(
            factId = узел.path("fact").asText(""),
            material = материал,
            anchor = якорь,
            authority = узел.path("authority").asText("").ifBlank { null },
            mark = runCatching { SourceMark.valueOf(узел.path("mark").asText("И")) }.getOrNull(),
            source = when {
                учётка.isNotBlank() -> FactSource.FromExpert(
                    учётка, узел.path("role").asText(""), узел.path("at").asText(""),
                )
                материал != null -> FactSource.FromMaterial(материал, якорь)
                else -> null
            },
        )
    }

    private fun примечание(
        slice: Slice,
        принятые: List<ObjectNode>,
        отказы: List<String>,
        изЖурнала: Boolean,
    ): String {
        val части = mutableListOf(slice.note)
        val счёт = Verdict.entries.joinToString(", ") { вердикт ->
            "${словоВердикта(вердикт)} ${принятые.count { it.path("verdict").asText() == вердикт.code }}"
        }
        части += "предложений ${принятые.size} ($счёт)"
        if (отказы.isNotEmpty()) части += "отклонено ${отказы.size}: ${отказы.joinToString("; ")}"
        if (изЖурнала) части += "ответ взят из журнала: тот же срез уже синтезировался"
        return части.joinToString(" · ")
    }

    private fun словоВердикта(вердикт: Verdict): String = when (вердикт) {
        Verdict.NEW -> "новых"
        Verdict.AUGMENT -> "дополнить"
        Verdict.CONTRADICT -> "противоречий"
        Verdict.CONFIRM -> "подтверждений"
    }

    // --- промпт --------------------------------------------------------------

    /**
     * Промпт синтеза. Детерминирован по составу среза: ни времени, ни
     * счётчиков — иначе один живой вызов на срез превратился бы в вызов на
     * каждое нажатие.
     */
    private fun промпт(project: String, отбор: Отбор): String {
        val область = Area.Project(project)
        val паспорт = store.list(область, "project").firstOrNull()
        val ограничения = store.list(область, "constraint")
            .joinToString("\n") { "  · ${it.code}: ${it.doc.path("text").asText("")}" }
            .ifBlank { "  (ограничений в проекте пока нет)" }
        val принятое = отбор.сущности.joinToString("\n") { строкаПонятия(it) }
            .ifBlank { "  (принятого в проекте пока нет: всякое узнанное понятие ново)" }
        val темы = отбор.темы.values.joinToString("\n") { "  · «$it»" }
            .ifBlank { "  (темы не заведены)" }
        val факты = отбор.факты.joinToString("\n") { (факт, _) -> строкаФакта(факт, отбор.темы) }
            .ifBlank { "  (фактов в поле нет — предлагать нечего)" }

        return """
Ты формируешь ПОСТАНОВКУ задачи инженерного проекта из его ПОЛЯ ЗНАНИЙ.

## Проект
Название: ${паспорт?.doc?.path("name")?.asText(project) ?: project}
Ограничения проекта (рамки, с которыми постановка обязана быть сверена):
$ограничения

## Правила образования понятий — версия ${GeneratedOntology.ontologyVersion.take(12)}
Понятий вне этого перечня не бывает: придумывать новые виды запрещено.
${правилаОбразования()}

## Уже принятое в проекте — о нём и выносится вердикт
$принятое

## Темы поля
$темы

## Срез поля: ${отбор.записи.size} из ${отбор.всего} элементов
$факты

$ПРАВИЛА

$ФОРМАТ
""".trimIndent()
    }

    private fun правилаОбразования(): String = GeneratedOntology.concepts.joinToString("\n") { понятие ->
        buildString {
            append("  · ${понятие.code}")
            понятие.note?.let { append(" — $it") }
            append("\n    поля: ")
            append(понятие.fields.entries.joinToString(" · ") { "${it.key} ← ${it.value}" })
            append("\n    узнаётся по: ${понятие.identity.key.joinToString(", ")}")
            append("; по смыслу: ${понятие.identity.semantic}")
            if (понятие.conflictOn.isNotEmpty()) {
                append("\n    противоречие по полям: ${понятие.conflictOn.joinToString(", ")}")
            }
            if (понятие.mustLink.isNotEmpty()) {
                append("\n    без этих связей не принимается: ${понятие.mustLink.joinToString(", ")}")
            }
        }
    }

    private fun строкаФакта(факт: Fact, темы: Map<String, String>): String {
        val величина = listOfNotNull(факт.value.ifBlank { null }, факт.unit).joinToString(" ")
        val происхождение = when (val источник = факт.source) {
            is FactSource.FromExpert -> "эксперт ${источник.account}, ${источник.role}, ${источник.at}"
            is FactSource.FromMaterial ->
                listOfNotNull(источник.material.ifBlank { null }, источник.anchor).joinToString(" ")
            null -> listOfNotNull(факт.material.ifBlank { null }, факт.anchor).joinToString(" ")
        }.ifBlank { "источник не назван" }
        val тема = факт.topic?.let { темы[it] ?: it }
        return buildString {
            append("  · ${факт.id} [${факт.kind}] ранг ")
            append(Authority.word(факт.authority).ifBlank { "не назначен" })
            append(" · метка ${факт.mark} · $происхождение")
            тема?.let { append(" · тема «$it»") }
            append("\n    ${факт.subject} — ${факт.predicate}")
            if (величина.isNotBlank()) append(": $величина")
        }
    }

    private fun строкаПонятия(сущность: Entity): String {
        val имя = ИМЕНА.firstNotNullOfOrNull { поле -> сущность.doc.path(поле).asText("").ifBlank { null } }
            ?: сущность.code
        val поля = ПОЛЯ.mapNotNull { поле ->
            сущность.doc.path(поле).asText("").ifBlank { null }?.let { "$поле: $it" }
        }
        return "  · ${сущность.code} [${сущность.kind}] «$имя»" +
            if (поля.isEmpty()) "" else " · ${поля.joinToString(" · ")}"
    }

    // --- хранилище -----------------------------------------------------------

    private fun вид(запуск: Entity): SynthesisRun {
        val документ = запуск.doc
        val предложения = Verdict.entries.flatMap { вердикт ->
            документ.path("diff").path(вердикт.code).map { изЗаписи(it) }
        }
        return SynthesisRun(
            id = запуск.code,
            trigger = документ.path("trigger").asText(""),
            sliceFingerprint = документ.path("slice_fingerprint").asText(""),
            sliceSize = документ.path("slice_size").asInt(0),
            ontologyVersion = документ.path("ontology_version").asText(""),
            status = запуск.status,
            diff = Diff.of(предложения),
            proposals = документ.path("proposals").map { it.asText() },
            cached = документ.path("cached").asBoolean(false),
            note = документ.path("notes").asText("").ifBlank { null },
            error = документ.path("error").asText("").ifBlank { null },
        )
    }

    private fun пустойДиф(документ: ObjectNode): ObjectNode {
        val диф = документ.putObject("diff")
        Verdict.entries.forEach { диф.putArray(it.code) }
        return диф
    }

    private fun запуски(область: Area): List<Entity> =
        store.list(область, SynthesisRun.KIND).filter { наш(it) }.sortedBy { it.code }

    /** Запуск синтеза, а не сверки ручного ввода: вид один, метки разные. */
    private fun наш(запись: Entity): Boolean = запись.doc.path("tags").any { it.asText() == МЕТКА }

    private fun запуск(область: Area, код: String): Entity =
        store.byCode(область, код)?.takeIf { it.kind == SynthesisRun.KIND && наш(it) }
            ?: throw IllegalArgumentException("запуска синтеза «$код» в проекте нет")

    private fun следующий(область: Area, вид: String, префикс: String): String {
        val занято = store.list(область, вид).mapNotNull {
            Regex("^$префикс-(\\d+)$").find(it.code)?.groupValues?.get(1)?.toIntOrNull()
        }
        return "%s-%04d".format(префикс, (занято.maxOrNull() ?: 0) + 1)
    }

    /** Отобранное поле: факты с карточками, принятые понятия и их темы. */
    private class Отбор(
        val факты: List<Pair<Fact, Entity>>,
        val сущности: List<Entity>,
        val темы: Map<String, String>,
        val всего: Int,
    ) {
        /** Записи среза для отпечатка и счёта дрейфа. */
        val записи: List<Entity> = факты.map { it.second } + сущности
    }

    companion object {

        /** Вид вызова в журнале ИИ: по нему видно, за что заплачено. */
        const val KIND: String = "synthesis"

        /** Отказ на проекте без поля знаний v2 — теми же словами, что в маршруте. */
        const val ВЫКЛЮЧЕН: String = "синтез выключен на этом проекте"

        /** Метка запуска синтеза: вид общий со сверкой, а списки — разные. */
        const val МЕТКА: String = "synthesis"

        /** Вид пакета карточек предложений: по нему они собираются на экран. */
        const val ПАКЕТ: String = "formation_synthesis"

        /**
         * Потолок ответа. Полсотни фактов дают десятки предложений с
         * основаниями; обрыв на полуслове — не ответ (поймано на разборе).
         */
        const val БЮДЖЕТ_СИНТЕЗА: Int = 32_000

        /**
         * Потолок среза — из истины онтологии («срез ≤ 50 фактов и
         * сущностей»): числа в коде нет, оно читается оттуда же, откуда
         * читаются пороги идентичности.
         */
        val ПОТОЛОК: Int = Regex("≤\\s*(\\d+)")
            .find(GeneratedOntology.reconciliation["batching"].orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: error("потолок среза не назван в ОНТОЛОГИЯ-ФОРМИРОВАНИЯ (reconciliation.batching)")

        /** Диспозиции, при которых факт остаётся в поле: человек его не снял. */
        val ВИДНЫЕ: Set<Disposition> = setOf(Disposition.ADOPTED, Disposition.NOTED)

        /** Состояния, в которых запись из поля выбыла. */
        val СНЯТЫЕ: Set<String> = setOf("cancelled", "rejected")

        /** Виды принятых понятий постановки — те, о которых выносится вердикт. */
        val ВИДЫ_ПОНЯТИЙ: List<String> = listOf("stakeholder", "need", "goal", "service", "constraint")

        /** Чем понятие называется на экране — первое непустое. */
        val ИМЕНА: List<String> = listOf("name", "statement", "text", "label", "designation")

        /** Поля принятого, по которым видно отличие предложения. */
        val ПОЛЯ: List<String> = listOf("role", "influence", "qos_class", "category", "year", "metric")

        private val ПРАВИЛА: String = """
## Что сделать
Для каждого понятия, которое СЛЕДУЕТ из фактов среза, дай одно предложение с
вердиктом:
  new — понятия с таким смыслом среди принятых нет;
  augment — понятие узнано, и у него появились новые поля или связи;
  contradict — понятие узнано, но различается в поле противоречия;
  confirm — то же самое из ДРУГОГО материала: свидетельство, не дубль.

## Правила — нарушение делает ответ непригодным
1. Предложение без факта-основания не существует. В `basis` идут КОДЫ фактов
   среза, не менее одного. Придумывать коды нельзя: принимаются только те,
   что есть в срезе выше.
2. Вердикт о принятом называет ПОЛЕ отличия (`diff_field`): чем именно
   дополняет, чем расходится, что подтверждает. «Похоже» без «чем именно» —
   брак, такое предложение отбрасывается целиком.
3. Противоречие показывает ОБА значения: в `basis` идут факты обеих сторон.
   Победителя не выбираешь — ранг документа лишь подсказывает человеку.
4. Принятое ты не меняешь: наружу идёт предложение, решает человек.
5. Понятия вне правил образования выше не бывает; поля берутся из тех, что
   у понятия названы.
6. Чего в фактах нет, того нет: не обобщай, не додумывай, не переноси
   значения между понятиями.
7. Метку достоверности, ранги и незакрытые связи проставляет система по
   фактам-основаниям — в ответе их не нужно.
        """.trimIndent()

        private val ФОРМАТ: String = """
## Формат ответа — ТОЛЬКО JSON, без пояснений вокруг
{
  "proposals": [
    {
      "concept": "need",
      "payload": {"statement": "связь в Арктике без наземной инфраструктуры", "stakeholder": "имя стороны"},
      "basis": ["F-0007", "F-0012"],
      "verdict": "new|augment|contradict|confirm",
      "target": "код принятого понятия — пусто только у new",
      "diff_field": "поле, о котором вердикт — обязательно, кроме new",
      "confidence": 0.0,
      "why": "одной строкой: почему именно так"
    }
  ]
}
        """.trimIndent()

        /**
         * Версия промпта: входит в отпечаток среза. Правка текста правил или
         * формата меняет её сама — иначе новый промпт молча брал бы из журнала
         * ответ, полученный на старый.
         */
        val ВЕРСИЯ_ПРОМПТА: String = "synthesis/v1+" + хеш(ПРАВИЛА + ФОРМАТ).take(8)

        private fun хеш(текст: String): String =
            MessageDigest.getInstance("SHA-256").digest(текст.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
