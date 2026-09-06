// Знания: материал → факты → план действий (ИНТЕЛЛЕКТУАЛЬНАЯ-ЗАГРУЗКА).
//
// Ключевая мысль поставки: «тип — вывод, факт — атом, задание — намерение».
// Документ не раскладывается по типу на входе; из него добываются ФАКТЫ с
// якорями, а из задания пользователя строится план действий, который
// принимается целиком, а не по одному факту.
package orbita.knowledge.api

/** Достоверность утверждения — семантика владельца, дословно. */
enum class SourceMark {
    /** Внутренний документ: наш материал. */
    И,

    /** Внешний источник, проверенный на указанную дату. */
    В,

    /** Предлагаемая цель или инженерно-финансовое допущение, требующее подтверждения. */
    П,
}

/**
 * Факт — атом знания. Живёт после загрузки: на него ссылаются параметры,
 * требования и риски, а повторная загрузка источника пересчитывает факты
 * и помечает зависимое «источник обновлён».
 */
data class Fact(
    val id: String,
    val subject: String,
    val predicate: String,
    /** Значение как есть: величина парой либо текст. */
    val value: String,
    val unit: String?,
    /** Якорь блока канона: по нему факт проверяется, не выходя из системы. */
    val anchor: String?,
    val mark: SourceMark,
    val confidence: Double?,
    val material: String,
    /** Вид факта: величина · способность · обязательство · рамка · событие… */
    val kind: String = "framing",
    val disposition: Disposition = Disposition.FREE,
    /** Тема факта: предмет до разрешения в сущность. */
    val topic: String? = null,
)

/**
 * Действие плана: что именно система создаст или изменит, если план принять.
 *
 * @property targetKind вид сущности, которая появится (stakeholder, need…)
 * @property scene сцена, в которую действие кладёт свой результат
 * @property payload содержимое будущей сущности — то самое, что видно в
 *   предпросмотре ДО нажатия: план не обещает, а показывает
 */
data class PlannedAction(
    val kind: String,
    val title: String,
    /** Что появится в модели — словами, до нажатия. */
    val effect: String,
    val factIds: List<String>,
    /** Не выполняется само: план принимает человек. */
    val requiresDecision: Boolean = true,
    val targetKind: String = "",
    val scene: String = "",
    val payload: Map<String, String> = emptyMap(),
    /** Код созданной сущности после акцепта; пусто — ещё не выполнено. */
    val created: String? = null,
)

/** Предложения сцены из поля знаний: что можно принять одним нажатием. */
data class SceneSuggestions(
    val scene: String,
    val task: String?,
    val actions: List<PlannedAction>,
    /** Индексы действий в плане — их и принимает акцепт. */
    val indices: List<Int>,
    /** Одной строкой: «из записки: 5 сторон и 3 потребности с якорями». */
    val summary: String,
)

/** Доля знаний в проекте: сколько сущностей выведено из фактов. */
data class KnowledgeCoverage(
    val total: Int,
    val fromFacts: Int,
    val manual: Int,
    val share: Double,
    val byKind: Map<String, Pair<Int, Int>>,
)

/**
 * Задание загрузки: источник плюс намерение пользователя.
 *
 * Задание вне каталога не додумывается: система предлагает ближайшее и
 * спрашивает (правило поставки §3).
 */
data class IntakeTask(
    val id: String,
    val material: String,
    val intent: String,
    val facts: List<Fact>,
    val plan: List<PlannedAction>,
    /** Почему план такой — и что осталось непонятным. */
    val note: String,
)

/** Диспозиция факта: её меняет ЧЕЛОВЕК, служба только предлагает. */
enum class Disposition { FREE, NOTED, ASSUMED, ADOPTED, REJECTED, CONTESTED, SUPERSEDED }

/** Тема — предмет фактов до разрешения в сущность. */
data class Topic(val id: String, val label: String, val scene: String?, val resolvedTo: String?, val facts: Int)

/** Блок канона Д1: якорь и текст. Факт без якоря не существует. */
data class CanonBlock(val anchor: String, val kind: String, val text: String)

interface Intake {
    /** Положить материал: снимок и карточка. Разбор идёт следом. */
    fun putMaterial(project: String, name: String, kind: String, text: String, author: String): String

    /** Собрать задание: факты из материала и план действий из намерения. */
    fun plan(project: String, material: String, intent: String, author: String): IntakeTask

    /**
     * Принять план: выбранные действия выполняются, снятые — нет.
     *
     * Выполнение заводит сущности со связью `derived_from_fact` и ставит
     * их фактам диспозицию `adopted`; факты снятых действий остаются
     * `noted` — их рассмотрели и не взяли, и это тоже решение.
     */
    fun accept(project: String, task: String, chosen: List<Int>, author: String): List<String>

    /** Задание загрузки с планом: предпросмотр до нажатия. */
    fun task(project: String, task: String): IntakeTask

    /** Доля сущностей проекта, выведенных из фактов, — мера поля знаний. */
    fun coverage(project: String): KnowledgeCoverage

    /**
     * Предложения сцены: непринятые действия плана, адресованные ей.
     *
     * Сцена начинается НЕ с пустой формы: из записки уже известно, кто
     * пользователи миссии и что им нужно. Ручной ввод остаётся вторым
     * путём, а не единственным.
     */
    fun suggestions(project: String, scene: String): SceneSuggestions

    fun facts(project: String): List<Fact>

    /**
     * Канон Д1 материала: блоки с якорями. Детерминированный разбор — одна
     * и та же версия документа даёт те же якоря, иначе факт на них не
     * сошлёшься.
     */
    fun canon(project: String, material: String): List<CanonBlock>

    /** Отпечаток канона: по нему живой разбор делается ОДИН раз на версию. */
    fun fingerprint(project: String, material: String): String

    /**
     * Положить факты разбора. Правило честности §6.1 держится здесь: факт
     * без якоря и величина без единицы не принимаются — они не факты.
     * Возвращает коды принятых и перечень отклонённых с причиной.
     */
    fun putFacts(project: String, material: String, raw: String, author: String): FactIntake

    /** Диспозицию меняет человек: служба предлагает, решает инженер. */
    fun dispose(project: String, fact: String, disposition: Disposition, reason: String, author: String): Fact

    fun topics(project: String): List<Topic>
}

/** Итог приёма фактов: что принято, что отклонено и почему. */
data class FactIntake(
    val accepted: List<Fact>,
    val refused: List<String>,
    val topics: List<Topic>,
    val note: String,
)
