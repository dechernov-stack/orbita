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
    /**
     * Заведён инженером руками, а не разбором источника. Полноправный
     * факт — диспозиции, связи, промпт, — но доля знаний ИЗ ИСТОЧНИКОВ
     * считается без него: ручное видно отдельно (замечание прохода 08.09).
     */
    val manual: Boolean = false,
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
    /** Выведено из фактов ИСТОЧНИКОВ — это и есть доля знаний. */
    val fromFacts: Int,
    /** Выведено из фактов, заведённых инженером руками: видно отдельно. */
    val fromManualFacts: Int,
    /** Заведено без всякого факта. */
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
enum class Disposition {
    FREE, NOTED, ASSUMED, ADOPTED, REJECTED, CONTESTED, SUPERSEDED,
    ;

    companion object {
        /** Неизвестное значение читается как «решения нет», а не падает. */
        fun of(текст: String?): Disposition =
            entries.firstOrNull { it.name.equals(текст?.trim(), ignoreCase = true) } ?: FREE
    }
}

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
     * Итог приёма плана: что заведено и о чём система обязана сказать.
     *
     * `notes` — не украшение: разбор называет соседнюю сущность ИМЕНЕМ, и
     * если такого имени в проекте нет, связь не встанет. Молча оставить
     * нужду без носителя нельзя — она повиснет на выходе сцены, и человек
     * узнает об этом на воротах, а не при приёме.
     */
    data class Accepted(val codes: List<String>, val notes: List<String>)

    /**
     * Принять план: выбранные действия выполняются, снятые — нет.
     *
     * Выполнение заводит сущности со связью `derived_from_fact` и ставит
     * их фактам диспозицию `adopted`; факты снятых действий остаются
     * `noted` — их рассмотрели и не взяли, и это тоже решение.
     *
     * Поля плана, называющие другую сущность (носитель нужды), уходят в
     * СВЯЗЬ, а не в документ: имя в документе связью не является.
     */
    fun accept(project: String, task: String, chosen: List<Int>, author: String): Accepted

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

    /**
     * Тема руками: инженер называет предмет, о котором знания копятся
     * («Опыт ЛИ Гонец-Д1М»), не дожидаясь разбора. Повтор метки — та же
     * тема, не вторая.
     */
    fun addTopic(project: String, label: String, author: String): Topic

    /**
     * Факт руками. Полноправный: диспозиции, связи, промпт. Метка [И],
     * провенанс manual, источник — «инженер, дата» либо материал, если он
     * назван. Правило честности то же: величина без единицы — не факт.
     * Якоря нет: якорь — место в документе, а у руки документа нет.
     */
    fun addFact(
        project: String,
        subject: String,
        predicate: String,
        value: String,
        unit: String?,
        kind: String,
        topic: String?,
        material: String?,
        author: String,
        /** Помета достоверности: И — наш материал, В — внешний, П — допущение (семантика владельца). */
        mark: String = "И",
    ): Fact
}

/** Итог приёма фактов: что принято, что отклонено и почему. */
data class FactIntake(
    val accepted: List<Fact>,
    val refused: List<String>,
    val topics: List<Topic>,
    val note: String,
    /**
     * Задание с планом, собранным этим разбором; пусто — действий план не
     * дал. По нему план ложится на акцепт ТУТ ЖЕ, в поле знаний, а не на
     * другом экране (замечание прохода 08.09: загрузка во вкладке).
     */
    val task: String? = null,
)
