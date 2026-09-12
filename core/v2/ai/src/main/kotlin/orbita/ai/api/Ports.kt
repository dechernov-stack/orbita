// Живой контур: вызов модели с журналом и кэшем (ЗАДАНИЕ-ПОЛЕ-ЗНАНИЙ §0).
//
// Решение владельца: разбор входного документа — ЖИВОЙ вызов, разрешён;
// один на версию документа, результат кэшируется отпечатком. Всё остальное
// по-прежнему пакетами. Отсюда устройство порта: служба спрашивает не
// «сделай», а «дай ответ на этот промпт», и сама решает, звонить ли —
// повтор той же версии вызова не делает.
package orbita.ai.api

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
     */
    fun ask(prompt: String, model: String?, maxTokens: Int?): Answer
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
    ): Answer

    /** Ответ из журнала по отпечатку промпта — без вызова; null, если такого не было. */
    fun cached(project: String, prompt: String): Answer?

    /**
     * Чистый вызов провайдера — только сеть, без чтения и записи базы:
     * его можно выполнять в фоновом потоке (ADR-069: разбор фоновой задачей).
     */
    fun askDetached(prompt: String, model: String? = null, maxTokens: Int? = null): Answer

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
//   · предложение без факта-основания не существует (≥1 основание);
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
 * Факт-основание предложения: откуда предложение взялось и чему верить.
 *
 * Плоские `material`/`anchor` и союз `source` лежат рядом ровно по той же
 * причине, что и у `Fact`: у документального основания есть место в каноне, а
 * у экспертного места нет — вместо якоря происхождение называет учётку, роль
 * и дату. Ранг здесь — свойство ОСНОВАНИЯ, а не приговор предложению.
 */
data class Basis(
    val factId: String,
    val material: String? = null,
    val anchor: String? = null,
    val authority: String? = null,
    val mark: SourceMark? = null,
    /** Ветка союза `fact.source`: документ с якорем либо эксперт с ролью и датой. */
    val source: FactSource? = null,
) {
    init {
        // Основание без кода факта непроверяемо: по нему нельзя дойти до
        // источника, а предложение без проверяемого основания — брак.
        require(factId.isNotBlank()) { "брак предложения: основание без кода факта" }
        require(authority == null || Authority.known(authority)) {
            "ранг основания «$authority» неизвестен: ${Authority.words()}"
        }
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
            "брак предложения: понятие «$concept» без факта-основания — предложений без оснований не бывает"
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
