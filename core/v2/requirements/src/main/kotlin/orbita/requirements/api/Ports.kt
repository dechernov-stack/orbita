// Требования (МОДЕЛЬ-ТРЕБОВАНИЯ-И-КОМПОНЕНТЫ §2, §4, §6).
//
// Требование управляется здесь: Capella и ReqIF только читают его. Четыре
// свойства, ради которых модуль существует:
//   · формулировка идёт по шаблону EARS — линт проверяет форму, а не вкус;
//   · «без носителя — не требование», и носитель ТОЙ ЖЕ природы, что уровень:
//     интерфейсное — на стык, сценарное — на цепочку, системное — на узел;
//   · базирование — именованный СНИМОК набора (объект, версия), а не флаг:
//     незрелая технология не запрещает снимок, а делает элемент условным;
//   · связь после базирования становится ПОДОЗРИТЕЛЬНОЙ, если её конец
//     изменился, и остаётся такой, пока человек не подтвердит.
package orbita.requirements.api

/** Уровень иерархии требования; он же определяет природу носителя. */
enum class Level {
    PROJECT,
    SCENARIO,
    SYSTEM,
    SUBSYSTEM,
    INTERFACE,
    ;

    /** Какого вида обязан быть носитель требования этого уровня (инвариант 2). */
    fun carrierKinds(): Set<String> = when (this) {
        INTERFACE -> setOf("interface")
        SCENARIO -> setOf("functional_chain", "scenario")
        PROJECT, SYSTEM, SUBSYSTEM -> setOf("component")
    }

    fun carrierWords(): String = when (this) {
        INTERFACE -> "стык"
        SCENARIO -> "цепочку или сценарий"
        PROJECT, SYSTEM, SUBSYSTEM -> "узел состава"
    }

    companion object {
        fun of(text: String?): Level =
            entries.firstOrNull { it.name.equals(text?.trim(), ignoreCase = true) } ?: SYSTEM
    }
}

/** Шаблон формулировки EARS: у каждого своя форма и свой линт. */
enum class Ears {
    /** ‹Носитель› должен ‹действие› ‹объект› [‹показатель›]. */
    ALWAYS,

    /** Когда ‹событие›, ‹носитель› должен ‹действие› [в течение ‹T›]. */
    EVENT,

    /** Пока ‹состояние›, ‹носитель› должен ‹действие›. */
    STATE,

    /** Если ‹условие›, то ‹носитель› должен ‹парирование›. */
    UNWANTED,

    /** Где предусмотрен ‹элемент›, ‹носитель› должен …. */
    OPTIONAL,
    ;

    companion object {
        /** В схеме шаблон «всегда» зовётся `ubiquitous` — принимаем оба имени. */
        fun of(text: String?): Ears = when (text?.trim()?.lowercase()) {
            "ubiquitous", "always", "всегда" -> ALWAYS
            "event", "по событию" -> EVENT
            "state", "в состоянии" -> STATE
            "unwanted", "нежелательное" -> UNWANTED
            "optional", "опциональное" -> OPTIONAL
            else -> ALWAYS
        }
    }
}

/** Применимость экземпляра типового требования (tailoring, FA §6). */
enum class Applicability { APPLIED, APPLIED_WITH_DEVIATION, NOT_APPLICABLE }

/** Класс базовой линии CM: что именно фиксируется снимком. */
enum class BaselineKind {
    /** Функциональная: проектные, сценарные и системные требования. */
    FUNCTIONAL,

    /** Распределённая: плюс подсистемные и интерфейсные, узлы и параметры. */
    ALLOCATED,

    /** Изделия: плюс единицы конфигурации и версии изделия. */
    PRODUCT,
    ;

    fun kinds(): Set<String> = when (this) {
        FUNCTIONAL -> setOf("requirement")
        ALLOCATED -> setOf("requirement", "component", "component_usage", "parameter", "interface")
        PRODUCT -> setOf(
            "requirement", "component", "component_usage", "parameter", "interface",
            "configuration_item", "product_version",
        )
    }

    /** Уровни требований, попадающие в снимок этого класса. */
    fun levels(): Set<Level> = when (this) {
        FUNCTIONAL -> setOf(Level.PROJECT, Level.SCENARIO, Level.SYSTEM)
        ALLOCATED, PRODUCT -> Level.entries.toSet()
    }
}

/** Твёрдость элемента снимка: условный держится на незрелой технологии. */
enum class Firmness { FIRM, CONDITIONAL }

/** Замечание линта: что не так с формулировкой и почему это важно. */
data class LintNote(val rule: String, val what: String, val why: String)

/**
 * Помеха базированию: словами, с кодом объекта и правилом-источником.
 * Это не «нельзя», а «вот что мешает» — список закрываемых дел.
 */
data class Blocker(val code: String, val rule: String, val what: String)

data class RequirementView(
    val id: String,
    val code: String,
    val level: Level,
    val title: String,
    val statement: String,
    val category: String,
    val ears: Ears,
    /** Носитель: узел, стык или цепочка. Пусто — требование ничьё. */
    val carrier: String?,
    val carrierKind: String?,
    val measure: String?,
    val verificationMethod: String?,
    val sources: List<String>,
    val templateRef: String?,
    val applicability: Applicability?,
    val status: String,
    val version: Int,
    /** Версия выше зафиксированной в последнем снимке — «изменено после утверждения». */
    val afterBaselineChanged: Boolean,
    /** Замечания линта — пометы, а не запрет записи. */
    val notes: List<LintNote>,
)

/** Элемент снимка: объект, его версия и на чём он держится. */
data class BaselineItem(
    val ref: String,
    val code: String,
    val kind: String,
    val version: Int,
    val firmness: Firmness,
    /** Технология, из-за которой элемент условен. */
    val conditionalOn: String? = null,
    val why: String? = null,
)

/** Именованный снимок набора: «Baseline SRR» = список (объект, версия). */
data class Baseline(
    val name: String,
    val kind: BaselineKind,
    val gate: String,
    val items: List<BaselineItem>,
    val by: String,
    val at: String,
) {
    val conditional: List<BaselineItem> get() = items.filter { it.firmness == Firmness.CONDITIONAL }
}

/** Связь, ставшая подозрительной после правки конца. */
data class SuspectLink(
    val linkId: String,
    val type: String,
    val from: String,
    val to: String,
    /** Какой конец уехал и насколько. */
    val why: String,
)

/** Отказ базирования: помехи приходят СПИСКОМ, а не первой попавшейся. */
class BaselineRefused(val blockers: List<Blocker>) : RuntimeException(
    "базировать нельзя (${blockers.size}): " + blockers.joinToString("; ") { "${it.code} — ${it.what}" },
)

interface Requirements {
    fun lint(statement: String, ears: Ears): List<LintNote>
    fun list(project: String): List<RequirementView>
    fun byCode(project: String, code: String): RequirementView?

    /**
     * Взять типовое требование с полки в проект (§2.3).
     * Экземпляр помнит шаблон и применимость; отклонение — только с обоснованием.
     */
    fun instantiate(
        project: String,
        typicalCode: String,
        code: String,
        carrier: String,
        applicability: Applicability,
        author: String,
        statement: String? = null,
        deviationRationale: String? = null,
    ): RequirementView
}

interface Baselines {
    /** Что мешает базировать — до того, как владелец нажмёт. */
    fun blockers(project: String, kind: BaselineKind, gate: String): List<Blocker>

    /** Снимок набора. Помехи — отказом со списком, а не молчаливым пропуском. */
    fun baseline(project: String, name: String, kind: BaselineKind, gate: String, author: String): Baseline

    fun list(project: String): List<Baseline>

    /** Подозрительные связи относительно последнего базирования. */
    fun suspects(project: String): List<SuspectLink>

    /** Подтвердить связь: подозрение снимает человек, а не время. */
    fun confirm(project: String, linkId: String, author: String)
}
