// Живой контур: вызов модели с журналом и кэшем (ЗАДАНИЕ-ПОЛЕ-ЗНАНИЙ §0).
//
// Решение владельца: разбор входного документа — ЖИВОЙ вызов, разрешён;
// один на версию документа, результат кэшируется отпечатком. Всё остальное
// по-прежнему пакетами. Отсюда устройство порта: служба спрашивает не
// «сделай», а «дай ответ на этот промпт», и сама решает, звонить ли —
// повтор той же версии вызова не делает.
package orbita.ai.api

import com.fasterxml.jackson.databind.JsonNode
import orbita.kernel.schema.GeneratedKinds
import orbita.knowledge.api.Authority
import orbita.knowledge.api.FactSource
import orbita.knowledge.api.SourceMark
import orbita.knowledge.schema.GeneratedOntology

/** Ответ провайдера: текст и учёт — журналу нужно «сколько и почём». */
data class Answer(
    val text: String,
    val model: String,
    val tokensIn: Int?,
    val tokensOut: Int?,
    /** true — ответ взят из журнала по отпечатку, вызова не было. */
    val cached: Boolean = false,
    /** Секунды живого вызова провайдера (ответ владельца 25.09: у каждого вызова — токены и секунды); у ответа из журнала — секунды исходного вызова. */
    val seconds: Double? = null,
)

/** Канал недоступен: ключа нет, провайдер перегружен, поток оборван. */
class ProviderUnavailable(reason: String) : RuntimeException(reason)

/**
 * Транспорт к модели. Подменяется в тестах: сеть в тесте не нужна, а
 * поведение службы (кэш, журнал, повтор) проверяется без неё.
 */
fun interface Transport {
    /**
     * @param maxTokens потолок ответа. Разбору документа его задаёт вызов:
     *   урожай в сто фактов не помещается в бюджет короткого ответа, а
     *   обрыв на полуслове — не ответ (поймано на записке в 36 тыс. знаков)
     * @param schema схема ответа. Задана — формат держит ПРОВАЙДЕР, а не
     *   уговор в тексте: ответ приходит объектом по схеме. Без неё формат
     *   держался словами промпта, и правка инструкций уводила модель с
     *   формата — список фактов приходил пустым (15.09, дважды).
     */
    fun ask(prompt: String, model: String?, maxTokens: Int?, schema: JsonNode?): Answer
}

/** Инструмент модели (шип 4 §2): имя, зачем он, схема входа — провайдер держит формат вызова. */
data class Tool(val name: String, val description: String, val inputSchema: JsonNode)

/** Исполнитель вызова инструмента: имя и вход → результат объектом; отказ — объектом с `error`. */
fun interface ToolHandler {
    fun call(name: String, input: JsonNode): JsonNode
}

/**
 * Транспорт с инструментами (РЕШЕНИЕ-СЛОВАРЬ-И-БАЗА-ЗНАНИЙ §4): знание не
 * подставляется в промпт, а берётся моделью по запросу — term · node ·
 * interface · facts · clauses · scene · similar. Цикл ходов: вызов
 * инструмента → результат → следующий ход, пока модель не отдаст ответ по
 * схеме.
 */
interface ToolTransport : Transport {
    fun askWithTools(
        prompt: String,
        model: String?,
        maxTokens: Int?,
        schema: JsonNode?,
        tools: List<Tool>,
        handler: ToolHandler,
    ): Answer
}

/** Запись журнала вызовов: по ней видно, за что заплачено. */
data class CallRecord(
    val kind: String,
    val model: String,
    val fingerprint: String,
    val tokensIn: Int?,
    val tokensOut: Int?,
    val at: String,
    val cached: Boolean,
    val seconds: Double? = null,
)

interface AiService {
    /**
     * Ответ модели на промпт. Один вызов на отпечаток: повтор того же
     * промпта в том же проекте возвращает записанный ответ и НЕ звонит.
     */
    fun ask(
        project: String,
        kind: String,
        prompt: String,
        model: String? = null,
        maxTokens: Int? = null,
        schema: JsonNode? = null,
    ): Answer

    /** Ответ из журнала по отпечатку промпта — без вызова; null, если такого не было. */
    fun cached(project: String, prompt: String): Answer?

    /**
     * Чистый вызов провайдера — только сеть, без чтения и записи базы:
     * его можно выполнять в фоновом потоке (ADR-069: разбор фоновой задачей).
     */
    fun askDetached(
        prompt: String,
        model: String? = null,
        maxTokens: Int? = null,
        schema: JsonNode? = null,
    ): Answer

    /**
     * Вызов с инструментами (шип 4 §2): журнал тот же — отпечаток промпта и
     * имён инструментов, ответ — итоговый. Транспорт без инструментов
     * отвечает обычным вызовом: инструменты тогда просто не предлагаются.
     */
    fun askWithTools(
        project: String,
        kind: String,
        prompt: String,
        tools: List<Tool>,
        handler: ToolHandler,
        model: String? = null,
        maxTokens: Int? = null,
        schema: JsonNode? = null,
    ): Answer = ask(project, kind, prompt, model, maxTokens, schema)

    /** Записать ответ в журнал вызовов (на потоке запросов; база — только здесь). */
    fun record(project: String, kind: String, prompt: String, answer: Answer)

    fun journal(project: String): List<CallRecord>
}

/** Состояние фонового разбора: идёт · готов (задание с планом) · не удался. */
data class AtomizeJob(
    val id: String,
    val project: String,
    val material: String,
    val status: String,
    val startedAt: String,
    val elapsedSeconds: Long,
    val task: String? = null,
    val note: String? = null,
    val accepted: Int = 0,
    val refused: Int = 0,
    val refusals: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * Разбор фоновой задачей: сеть — в фоне, база — на потоке запросов.
 * `start` возвращает задание сразу (либо готовый итог, если ответ уже в
 * журнале); `poll` применяет готовый ответ при следующем обращении.
 */
interface AtomizeJobs {
    fun start(project: String, material: String, intent: String, author: String): AtomizeJob
    fun poll(project: String, id: String): AtomizeJob?
    fun list(project: String): List<AtomizeJob>
}

// ——— Поле знаний v2: синтез постановки из поля (ПОЛЕ-ЗНАНИЙ-V2-СИНТЕЗ §3) ———
//
// Синтез идёт по ВСЕМУ полю, а не по последнему материалу: срез фактов, тем и
// принятых сущностей → предложения понятий постановки. Служба живёт здесь, в
// сквозном модуле, потому что ей нужны и знания (факты, онтология), и живой
// канал; вниз она смотреть вправе, знания на неё — нет.
//
// Границы службы названы инвариантами онтологии, и здесь их держат
// конструкторы, а не договорённость:
//   · предложение без основания не существует (≥1 основание: факт · сущность · разрыв);
//   · «похоже» без «чем именно» — брак: у вердикта о принятом обязано быть
//     названо ПОЛЕ отличия;
//   · ранг подсказывает в противоречии и никогда не выбирает победителя —
//     поля «победитель» нет ни у одного типа этого файла;
//   · принятое синтезом не меняется: наружу отдаётся ПРЕДЛОЖЕНИЕ, а
//     единственный путь изменения модели — сверка (knowledge `Reconcile`).

/**
 * Вердикт предложения — четыре группы дифа «поле → постановка».
 *
 * Что означает каждый вердикт, написано один раз в истине онтологии
 * (`GeneratedOntology.reconciliation`); здесь только имена для кода.
 */
enum class Verdict {
    /** Понятия с таким смыслом среди принятых нет. */
    NEW,

    /** Понятие узнано, и у него появились новые поля или связи. */
    AUGMENT,

    /** Понятие узнано, но различие в conflict_on — показать оба значения. */
    CONTRADICT,

    /** То же самое из независимого документа — свидетельство, не дубль. */
    CONFIRM;

    /** Код вердикта: ключ в дифе запуска и в истине онтологии. */
    val code: String get() = name.lowercase()
}

/**
 * Основание предложения — откуда предложение взялось и чему верить. Закрытый
 * тип из трёх (диф владельца 28.09, `formation.model_basis_rule`): предложение
 * растёт из ФАКТА (документ, якорь), из принятой СУЩНОСТИ (вид, код, поле или
 * шаг) либо из РАЗРЫВА (вид, узел, грань, точка). Сверка и приём принимают
 * любое; карточка предложения показывает основание словами (`words`) со
 * ссылкой «к месту».
 *
 * До 28.09 основание было только фактом, оттого `FormationProposal` требовал
 * факт-основание. Расширение понадобилось помощникам Phase A: требование
 * выводится из родительского требования (сущность), риск — из разрыва, а не
 * из факта. Путь предложений один (сверка) — меняется лишь тип основания.
 */
sealed interface Basis {
    /** Ранг доверия основания; у разрыва его нет. Подсказывает в противоречии, не выбирает. */
    val rank: String?

    /**
     * Место, куда ведёт ссылка «к месту»: у факта — якорь в документе, у
     * сущности — поле или шаг сценария. У разрыва отдельного якоря нет: его
     * место — узел·грань·точка целиком (см. `words`).
     */
    val anchor: String?

    /** Дискриминатор записи в прогон: fact · entity · gap. */
    val kindTag: String

    /** Основание словами — для карточки предложения, промпта и ссылки «к месту». */
    fun words(): String

    /**
     * Факт-основание: документ с якорем либо эксперт с ролью и датой. Плоские
     * `material`/`anchor` и союз `source` — как у самого факта (`Fact` знаний):
     * у документального основания есть место в каноне, у экспертного вместо
     * якоря учётка, роль и дата. Ранг — свойство основания, не приговор.
     */
    data class Fact(
        val factId: String,
        val material: String? = null,
        override val anchor: String? = null,
        override val rank: String? = null,
        val mark: SourceMark? = null,
        /** Ветка союза `fact.source`: документ с якорем либо эксперт с ролью и датой. */
        val source: FactSource? = null,
    ) : Basis {
        init {
            // Основание без кода факта непроверяемо: по нему нельзя дойти до
            // источника, а предложение без проверяемого основания — брак.
            require(factId.isNotBlank()) { "брак предложения: факт-основание без кода факта" }
            require(rank == null || Authority.known(rank)) {
                "ранг основания «$rank» неизвестен: ${Authority.words()}"
            }
        }

        override val kindTag: String get() = "fact"
        override fun words(): String = listOfNotNull(material ?: factId, anchor).joinToString(" · ")
    }

    /**
     * Сущность-основание: принятая сущность поля (вид и код). При приёме
     * ложится в `source` записи (`requirement.source` kind requirement|goal|
     * need|constraint), а не в ссылку на факт. `anchor` — поле сущности или
     * шаг сценария, к которому ведёт ссылка «к месту».
     */
    data class Entity(
        val kind: String,
        val code: String,
        override val anchor: String? = null,
        override val rank: String? = null,
    ) : Basis {
        init {
            require(kind.isNotBlank()) { "брак предложения: сущность-основание без вида" }
            require(code.isNotBlank()) { "брак предложения: сущность-основание без кода" }
            require(rank == null || Authority.known(rank)) {
                "ранг основания «$rank» неизвестен: ${Authority.words()}"
            }
        }

        override val kindTag: String get() = "entity"
        override fun words(): String = listOfNotNull("$kind $code", anchor).joinToString(" · ")
    }

    /**
     * Разрыв-основание: разрыв фазы (вид, узел, грань, точка), который машина
     * находит сама. При приёме ложится в ссылку риска на свой разрыв, не в
     * `source`. Ранга у разрыва нет — это не свидетельство, а факт модели.
     */
    data class Gap(
        val kind: String,
        val node: String,
        val facet: String,
        val point: String,
    ) : Basis {
        init {
            require(kind.isNotBlank() && node.isNotBlank() && facet.isNotBlank() && point.isNotBlank()) {
                "брак предложения: разрыв назван не полностью (вид · узел · грань · точка)"
            }
        }

        override val rank: String? get() = null
        override val anchor: String? get() = null
        override val kindTag: String get() = "gap"
        override fun words(): String = "разрыв $kind: $node · $facet · $point"
    }
}

/**
 * Предложение понятия постановки: что синтез предлагает сделать с полем.
 *
 * Предложение НЕ сущность: оно живёт до решения человека и само модель не
 * меняет. Отсюда обязательность основания и поля отличия — по ним человек
 * решает, не выходя из экрана.
 *
 * @property concept код понятия ОНТОЛОГИИ-ФОРМИРОВАНИЯ; понятия вне неё не
 *   образуются — придумывать их в коде запрещено
 * @property payload будущее содержимое понятия — то, что видно ДО нажатия
 * @property sourceMark достоверность утверждения [И]/[В]/[П] — берётся у
 *   фактов-оснований; умолчания здесь нет: правдоподобная метка хуже её
 *   отсутствия
 * @property targetRef принятое понятие, о котором вердикт; пусто только у NEW
 * @property diffField ЧЕМ именно отличается от принятого — поле, а не «похоже»
 * @property missing незакрытые must_link («owns→stakeholder»): с ними
 *   предложение остаётся предложением с пометой, сущностью не становится
 * @property rankHint подсказка рангов в противоречии словами
 *   («обязательный против справочного») — подсказка, не выбор
 */
data class FormationProposal(
    val concept: String,
    val payload: Map<String, String>,
    val basis: List<Basis>,
    val verdict: Verdict,
    val sourceMark: SourceMark,
    val targetRef: String? = null,
    val diffField: String = "",
    val confidence: Double? = null,
    val missing: List<String> = emptyList(),
    val rankHint: String = "",
) {
    init {
        // Понятие вне онтологии не образуется — отказ здесь, а не мусорная
        // сущность в поле (инвариант ОНТОЛОГИИ-ФОРМИРОВАНИЯ).
        GeneratedOntology.of(concept)
        require(basis.isNotEmpty()) {
            "брак предложения: понятие «$concept» без основания — предложений без оснований не бывает"
        }
        if (verdict != Verdict.NEW) {
            require(!targetRef.isNullOrBlank()) {
                "брак предложения: вердикт «${verdict.code}» о принятом не назвал, о каком именно понятии"
            }
            // «Похоже» без «чем именно» — брак (правило прав онтологии):
            // человек решает по НАЗВАННОМУ отличию, а не по проценту.
            require(diffField.isNotBlank()) {
                "брак предложения: вердикт «${verdict.code}» не назвал поле отличия"
            }
        }
        if (verdict == Verdict.CONTRADICT) {
            // Противоречие показывает ОБА значения с рангами; без подсказки
            // рангов экран показал бы различие без веса источников.
            require(rankHint.isNotBlank()) {
                "брак предложения: противоречие не назвало ранги обоих оснований"
            }
        }
        require(confidence == null || confidence in 0.0..1.0) {
            "уверенность предложения вне 0…1: $confidence"
        }
    }
}

/**
 * Диф «поле → постановка» четырьмя группами: новое · дополнить · противоречит ·
 * подтверждено. Группа — это вердикт, и предложение лежит только в своей.
 */
data class Diff(
    val new: List<FormationProposal> = emptyList(),
    val augment: List<FormationProposal> = emptyList(),
    val contradict: List<FormationProposal> = emptyList(),
    val confirm: List<FormationProposal> = emptyList(),
) {
    init {
        // Предложение в чужой группе рассыпает счётчики экрана молча:
        // «противоречит 0», пока противоречие лежит среди новых.
        проверить(new, Verdict.NEW)
        проверить(augment, Verdict.AUGMENT)
        проверить(contradict, Verdict.CONTRADICT)
        проверить(confirm, Verdict.CONFIRM)
    }

    private fun проверить(группа: List<FormationProposal>, вердикт: Verdict) {
        группа.firstOrNull { it.verdict != вердикт }?.let {
            error("предложение с вердиктом «${it.verdict.code}» лежит в группе «${вердикт.code}»")
        }
    }

    val size: Int get() = new.size + augment.size + contradict.size + confirm.size

    /** Счётчики групп — считает сервер: клиенту считать запрещено. */
    fun counts(): Map<String, Int> = mapOf(
        Verdict.NEW.code to new.size,
        Verdict.AUGMENT.code to augment.size,
        Verdict.CONTRADICT.code to contradict.size,
        Verdict.CONFIRM.code to confirm.size,
    )

    fun all(): List<FormationProposal> = new + augment + contradict + confirm

    companion object {
        /** Разложить предложения по группам — раскладка идёт по вердикту, а не по руке. */
        fun of(proposals: List<FormationProposal>): Diff {
            val по = proposals.groupBy { it.verdict }
            return Diff(
                new = по[Verdict.NEW].orEmpty(),
                augment = по[Verdict.AUGMENT].orEmpty(),
                contradict = по[Verdict.CONTRADICT].orEmpty(),
                confirm = по[Verdict.CONFIRM].orEmpty(),
            )
        }
    }
}

/**
 * Запуск синтеза: чем вызван, по какому срезу, по каким правилам и что вышло.
 *
 * Отпечаток среза — цена вопроса в токенах: тот же срез обязан вернуть тот же
 * ответ из журнала (`cached = true`), иначе каждое событие поля превращается в
 * живой вызов. Версия онтологии записывается вместе с запуском: по ней видно,
 * по каким правилам образования сделано предложение.
 *
 * @property id код запуска (SR-N)
 * @property trigger чем вызван: рукой либо событием поля; перечень значений
 *   стережёт схема вида при записи — второй копии перечня в коде нет
 * @property sliceSize сколько фактов и сущностей вошло в срез (потолок среза)
 * @property note словами о срезе — «срез урезан: N из M»
 * @property error причина отказа при status=error
 */
data class SynthesisRun(
    val id: String,
    val trigger: String,
    val sliceFingerprint: String,
    val sliceSize: Int,
    val ontologyVersion: String,
    val status: String,
    val diff: Diff = Diff(),
    /** Коды предложений (вид `proposal`) — сами предложения лежат в дифе. */
    val proposals: List<String> = emptyList(),
    val cached: Boolean = false,
    val note: String? = null,
    val error: String? = null,
) {
    init {
        require(id.isNotBlank()) { "запуск синтеза без кода" }
        require(trigger.isNotBlank()) { "запуск синтеза без причины запуска" }
        // Без отпечатка среза кэш не работает вовсе: повтор того же среза
        // уйдёт живым вызовом, и бюджет утечёт на каждом событии поля.
        require(sliceFingerprint.isNotBlank()) { "запуск синтеза без отпечатка среза" }
        require(sliceSize >= 0) { "размер среза отрицателен: $sliceSize" }
        require(ontologyVersion.isNotBlank()) { "запуск синтеза без версии правил образования" }
        require(status in statuses) { "статус запуска «$status» вне статусной модели: ${statuses.joinToString("|")}" }
        if (status == ERROR) {
            // Отказ без причины неотличим от пустого результата.
            require(!error.isNullOrBlank()) { "отказ запуска синтеза без названной причины" }
        }
    }

    companion object {
        const val KIND: String = "synthesis_run"
        const val ERROR: String = "error"

        /** Статусы берутся из истины схем: вторая копия статусной модели разошлась бы молча. */
        val statuses: List<String> = GeneratedKinds.of(KIND).statusModel
            ?.split("|")
            ?: error("у вида «$KIND» нет статусной модели — истина схем разошлась с кодом")
    }
}

/**
 * Дрейф поля с последнего запуска синтеза: «поле изменилось: N».
 *
 * Счёт делает сервер — клиент решений не принимает и ничего не считает
 * (tools/validate_web_no_math.py).
 *
 * @property since код последнего запуска, с которым сравнивается поле; пусто —
 *   синтеза на проекте ещё не было
 */
data class FieldDrift(val changed: Int, val since: String?, val fingerprint: String)

/** Синтез постановки из поля: срез поля → предложения с основаниями. */
fun interface Synthesize {
    fun synthesize(project: String, trigger: String, author: String): SynthesisRun
}

/**
 * Синтез фоновой задачей (ADR-069): сеть — в фоне, база — на потоке запросов.
 *
 * Устроен как разбор материала: `start` возвращает запуск сразу (либо готовый
 * итог, если ответ на этот срез уже в журнале), `poll` применяет готовый ответ
 * при следующем обращении. Иначе однопоточный стенд молчит все минуты синтеза.
 */
interface SynthesisJobs {
    fun start(project: String, trigger: String, author: String): SynthesisRun
    fun poll(project: String, id: String): SynthesisRun?
    fun list(project: String): List<SynthesisRun>

    /**
     * Изменилось ли поле с последнего запуска. Нужен индикатору «поле
     * изменилось: N»: без него экран либо звал бы синтез впустую, либо
     * считал бы разницу сам.
     */
    fun pending(project: String): FieldDrift
}

// --- Раздача нужд по целям и сервисам (решение владельца 17.09) ------------

/** Одна связь раздачи: нужда → цель или сервис, с причиной модели. */
data class DistributionLink(
    val id: String,
    val need: String,
    val needText: String,
    val target: String,
    /** goal · service */
    val targetKind: String,
    val targetText: String,
    /** Класс покрывшего сервиса — достаётся нужде без класса при приёме. */
    val qosClass: String?,
    val reason: String,
    /** Связь уже есть в модели: второй раз не заводится. */
    val exists: Boolean,
    /** Нужда покрыта, но класса не несёт: приём даст ей класс сервиса и без новой связи. */
    val classPending: Boolean,
    val accepted: Boolean,
)

/** Запуск раздачи: карта связей предложениями, не раздано и отбито — поимённо. */
data class DistributionRun(
    val id: String,
    val status: String,
    val cached: Boolean,
    val note: String,
    val links: List<DistributionLink>,
    val unassigned: List<String>,
    val refused: List<String>,
)

data class DistributionAccepted(val run: String, val linked: Int, val classes: Int, val skipped: List<String>, val note: String)

data class DistributionUndone(val run: String, val unlinked: Int, val note: String)

// --- Сценарии предложением (шип 1, п. 1.7; СЦЕНАРИИ-PRE-A-СЦЕНА-9) ---------

/** Шаг сценария: участник — что происходит; участник разрешён в узел, сторону или внешнюю систему. */
data class ProposedStep(
    val participant: String,
    /** node · side · external · unresolved */
    val participantKind: String,
    /** Код узла или стороны проекта, если участник разрешён в запись. */
    val ref: String?,
    val what: String,
)

data class ProposedScenario(
    val id: String,
    val name: String,
    /** nominal · off_nominal · alarm */
    val mode: String,
    /** Сервисы проекта, которые сценарий показывает, — кодами. */
    val services: List<String>,
    val reason: String,
    val steps: List<ProposedStep>,
    /** Участники, которых не удалось разрешить ни в узел, ни в сторону. */
    val unresolved: List<String>,
    /** Сценарий с таким именем в проекте уже есть: второй раз не заводится. */
    val exists: Boolean,
    val accepted: Boolean,
)

data class ScenarioProposalRun(
    val id: String,
    val status: String,
    val cached: Boolean,
    val note: String,
    val scenarios: List<ProposedScenario>,
    val refused: List<String>,
)

data class ScenariosAccepted(val run: String, val created: List<String>, val skipped: List<String>, val note: String)

data class ScenariosUndone(val run: String, val cancelled: Int, val note: String)
