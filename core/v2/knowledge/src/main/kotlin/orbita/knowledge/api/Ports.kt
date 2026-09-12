// Знания: материал → факты → план действий (ИНТЕЛЛЕКТУАЛЬНАЯ-ЗАГРУЗКА).
//
// Ключевая мысль поставки: «тип — вывод, факт — атом, задание — намерение».
// Документ не раскладывается по типу на входе; из него добываются ФАКТЫ с
// якорями, а из задания пользователя строится план действий, который
// принимается целиком, а не по одному факту.
package orbita.knowledge.api

import com.fasterxml.jackson.databind.JsonNode
import orbita.knowledge.schema.GeneratedOntology

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
 * Ранг доверия источника: обязательный · экспертный · справочный · сомнительный.
 *
 * Перечень рангов здесь заново НЕ объявляется: истина одна —
 * ОНТОЛОГИЯ-ФОРМИРОВАНИЯ (`GeneratedOntology.authorityRanks`), а вторая копия
 * перечня в коде разошлась бы с ней молча. Здесь живут имена для кода и два
 * правила, которые ранг обязан соблюдать:
 *   · ранг ПОДСКАЗЫВАЕТ в противоречии и никогда не выбирает победителя
 *     (knowledge_field_rules: «показывает ОБА значения с рангами»);
 *   · доверие не выводится из типа файла — тип входного остаётся режимом
 *     разбора, доверие живёт отдельным полем (ловушка владельца: «тип
 *     документа как поле ввода запрещён»).
 */
object Authority {

    const val MANDATORY: String = "mandatory"
    const val EXPERT: String = "expert"
    const val REFERENCE: String = "reference"
    const val DOUBTFUL: String = "doubtful"

    /** Ранги по убыванию веса — порядком владеет онтология, не этот файл. */
    val ranks: List<String> get() = GeneratedOntology.authorityRanks

    /**
     * Ранг словами — так он называется человеку. Слова взяты из истины схем
     * (`material.authority`), чтобы отказ сервера звучал теми же словами, что
     * и колонка на экране.
     */
    private val слова: Map<String, String> = mapOf(
        MANDATORY to "обязательный",
        EXPERT to "экспертный",
        REFERENCE to "справочный",
        DOUBTFUL to "сомнительный",
    )

    init {
        // Расхождение с истиной онтологии молчать не имеет права: если перечень
        // рангов там изменится, код обязан упасть на сборке, а не подставить
        // отсутствующий ранг факту.
        require(ranks.containsAll(слова.keys)) {
            "ранги доверия разошлись с ОНТОЛОГИЯ-ФОРМИРОВАНИЯ: ${ranks.joinToString(" · ")}"
        }
    }

    fun known(rank: String?): Boolean = (rank?.trim() ?: "") in ranks

    /** Ранг словами; неизвестное значение возвращается как есть — не выдумываем. */
    fun word(rank: String?): String = слова[rank?.trim() ?: ""] ?: rank.orEmpty()

    /** Все ранги словами — для отказа: человеку показывается выбор, а не перечень кодов. */
    fun words(): String = ranks.joinToString(" · ") { word(it) }

    /** Ранг из строки: неизвестное значение — отказ, а не тихое умолчание. */
    fun of(rank: String?): String {
        val значение = rank?.trim().orEmpty()
        require(known(значение)) { "ранг доверия «$значение» неизвестен: ${words()}" }
        return значение
    }

    /**
     * Вес ранга: меньше — весомее (порядок перечня онтологии). Нужен подсказке
     * в противоречии и урезанию среза; ПОБЕДИТЕЛЯ вес не выбирает.
     */
    fun weight(rank: String?): Int = ranks.indexOf(rank?.trim() ?: "").takeIf { it >= 0 } ?: ranks.size

    /**
     * Ранг по прежнему типу входного — МИГРАЦИЯ, а не ввод.
     *
     * Проект, заведённый до перестройки, ранга не знает, но факты обязаны его
     * унаследовать, иначе поле знаний останется без рангов вовсе. Тип вне
     * перечня истины схем даёт `null`: правдоподобный ранг здесь не
     * выдумывается — материал получает сомнительный С ПОМЕТОЙ, и ранг
     * называет человек.
     */
    fun ofMaterialKind(kind: String?): String? = when (kind?.trim()?.lowercase()) {
        "mission_memo", "tor", "directive", "normative", "regulatory_decision" -> MANDATORY
        "analysis", "heritage", "datasheet", "quote", "external_icd", "external_model", "reference" -> REFERENCE
        else -> null
    }
}

/**
 * Источник факта — союз из истины схем (`fact.source`):
 * «{material?,anchor?} | {account*,role*,at*}».
 *
 * Ровно одна ветка: документ с якорем ЛИБО эксперт с учёткой, ролью и датой.
 * Генератор сворачивает союз в бестиповый object, и JSON Schema его не
 * стережёт — «ровно одно из двух» держит Kotlin: здесь типом, в воротах
 * приёма фактов проверкой.
 */
sealed interface FactSource {

    /**
     * Документ с якорем: место в каноне, по которому факт проверяется, не
     * выходя из системы.
     */
    data class FromMaterial(val material: String, val anchor: String?) : FactSource

    /**
     * Эксперт: учётка · роль · дата. Якоря нет — якорь есть место в документе,
     * а у руки документа нет; происхождение при этом обязано быть у каждого
     * факта, поэтому все три поля обязательны.
     */
    data class FromExpert(val account: String, val role: String, val at: String) : FactSource
}

/**
 * Профиль содержимого материала (Д2а): доли блоков — постановка · параметры ·
 * нормы · оценки.
 *
 * Ставит РАЗБОР, не инженер: тип документа полем ввода не бывает, а режим
 * атомизации выбирается по профилю БЛОКА, а не по расширению файла. Доли
 * могут не давать единицы: блок, не попавший ни в один класс, не приписывается
 * ни одному из них.
 */
data class ContentProfile(
    val statement: Double = 0.0,
    val params: Double = 0.0,
    val norms: Double = 0.0,
    val assessments: Double = 0.0,
) {
    init {
        val доли = mapOf(
            "statement" to statement, "params" to params,
            "norms" to norms, "assessments" to assessments,
        )
        // Доля вне 0…1 или сумма больше единицы — брак разбора, а не профиль:
        // по такому профилю режим атомизации выбирается наугад.
        доли.forEach { (имя, доля) ->
            require(доля in 0.0..1.0) { "доля блоков «$имя» вне 0…1: $доля" }
        }
        require(доли.values.sum() <= 1.0 + 1e-6) {
            "доли блоков в сумме больше единицы: ${доли.values.sum()}"
        }
    }
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
    /** Класс сущности ТЗ (правило атомизации): requirement · function · service · composition_node · stakeholder · constraint · milestone · normative_ref. */
    val entityClass: String? = null,
    /**
     * Заведён инженером руками, а не разбором источника. Полноправный
     * факт — диспозиции, связи, промпт, — но доля знаний ИЗ ИСТОЧНИКОВ
     * считается без него: ручное видно отдельно (замечание прохода 08.09).
     */
    val manual: Boolean = false,
    /** Ключ анкеты узла, если факт — параметр из даташита (шип E). */
    val paramKey: String? = null,
    /** Коды фактов с тем же субъектом и утверждением, но иным значением: показать оба, не выбирать. */
    val conflicts: List<String> = emptyList(),
    /** Допущение: без владельца, точки подтверждения и способа проверки допущение не ставится. */
    val assumption: Assumption? = null,
    /** Источник получил новую версию, и блок факта в ней изменился. */
    val sourceUpdated: String? = null,
    /**
     * Свидетельство: claimed (заявлено) · corroborated (подтверждено вторым
     * независимым источником сверкой) · measured · assumed. Поднимает сверка,
     * а не разбор: подтверждение — это встреча двух источников, и мера
     * ПМИ-6 «после подтверждения источников факты corroborated» читается
     * снаружи только через это поле.
     */
    val evidence: String? = null,
    /**
     * Ранг доверия: наследуется от материала, у ручного факта эксперта —
     * `expert`. Пусто — факт заведён до перестройки и ранга ещё не получил:
     * выдуманный ранг хуже отсутствующего, его проставит миграция.
     */
    val authority: String? = null,
    /**
     * Источник по истине схем — ровно одна ветка союза. Плоские `material` и
     * `anchor` остаются рядом до DTO из YAML: на них смотрят экспорт и
     * сериализатор вида.
     */
    val source: FactSource? = null,
    /**
     * Связи с другими фактами обоими концами. Пусто — связей нет; факт,
     * прочитанный без реестра связей, их тоже не знает.
     *
     * Ходят они ЧЕРЕЗ порт, а не мимо: сериализатор вида один
     * (`tools/validate_one_serializer.py`), и связь, добытая маршрутом в обход
     * порта, была бы вторым источником правды о том же знании.
     */
    val links: List<FactLink> = emptyList(),
)

/**
 * Связь факта с фактом: подтверждает · противоречит · уточняет · то же самое.
 *
 * Почему связью, а не полем: поле живёт ВНУТРИ документа одного факта и
 * корпуса не переживает, а связь держится концами за факты РАЗНЫХ материалов
 * (мера §3 задания — связи фактов между документами). Поле `conflicts`
 * остаётся вычисляемым представлением того же знания для экрана и экспорта.
 *
 * Перечень типов здесь заново НЕ объявляется: он живёт в приёме связей
 * (`FactLinks`), и вторая копия разошлась бы с ним молча. Сюда связь приходит
 * уже принятой — «похоже» связью не бывает, его отбивает приём.
 *
 * @property rationale чем именно факты друг другу приходятся: связь без
 *   причины неотличима от случайной
 */
data class FactLink(val type: String, val from: String, val to: String, val rationale: String = "") {
    init {
        require(type.isNotBlank()) { "связь фактов без типа не бывает: произвольных связей не существует" }
        require(from != to) { "оба конца связи — факт «$from»: факт не связывается сам с собой" }
    }
}

/** Допущение (истина схем `fact.assumption`): кто, к какой точке, чем проверит, что будет, если неверно. */
data class Assumption(val owner: String, val confirmBy: String, val validation: String, val impactIfWrong: String)

/**
 * Строка оценки ТЗ: требование ТЗ (факт) против нужд проекта.
 *
 * @property note почему вердикт такой: чего требованию не хватает до нужды
 *   (нет спецификации, число без обоснования) либо почему нужды нет
 */
data class TorLine(
    val fact: String,
    val requirement: String,
    val needs: List<String>,
    val verdict: String,
    val note: String = "",
    /** Слова самого факта («ТЗ п. 2.3.1: состав ОГ — не менее 180 МКА» [requirement]) — дыра читается без реестра. */
    val text: String = "",
)

/**
 * Нужда против ТЗ — взгляд ОТ НУЖДЫ (ответ владельца 09.09 п. 1): какие
 * требования её закрывают и чего в ТЗ нет. Дыра называется словами, а не
 * выводится из счётчиков: «Арктика без требования» — это gap, не «0 строк».
 *
 * @property verdict covered · partial · uncovered
 * @property gap чего в ТЗ нет для этой нужды; пусто у покрытой
 */
data class TorNeed(val need: String, val verdict: String, val gap: String, val requirements: List<String>)

/** Оценка ТЗ против нужд: матрица «нужды × требования ТЗ» (шип E п. 1). */
data class TorAssessment(
    val lines: List<TorLine>,
    /** Нужды, которых ни одно требование ТЗ не покрывает, — RFA заказчику. */
    val uncoveredNeeds: List<String>,
    /** Требования ТЗ без нужды — вопрос заказчику или новая нужда. */
    val orphanRequirements: List<String>,
    /** Строка по каждой нужде проекта — с названной дырой. */
    val needs: List<TorNeed> = emptyList(),
    /**
     * Дыры ТЗ одним списком — то, что должно быть видно первым: нужда без
     * требования, нужда с требованием без спецификации, требование без
     * нужды, число без привязки.
     */
    val gaps: List<String> = emptyList(),
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
    /**
     * Выведено только из фактов, которые свидетельством ещё не стали:
     * заведённых инженером руками И сомнительных — результат исследования до
     * подтверждения источников человеком. Видно отдельной строкой и в долю
     * знаний не идёт: иначе непроверенное само себя и зачло бы (мера §6).
     *
     * Имя поля осталось от времени, когда сюда шло только ручное; переименовать
     * его — правка сериализатора и клиента, и она идёт своим шагом.
     */
    val fromManualFacts: Int,
    /** Заведено без всякого факта. */
    val manual: Int,
    val share: Double,
    val byKind: Map<String, Pair<Int, Int>>,
    /**
     * Сколько ОСНОВАНИЙ каждого ранга: ключ — ранг (`Authority`), значение —
     * число нитей `derived_from_fact` на факт этого ранга. Мера ПМИ-6 п. 1.8
     * просит долю знаний «с рангами оснований»: без этого поля ранг участвует
     * в счёте, но наружу не виден, и «80 % из знаний» не отличить от «80 % из
     * справок».
     *
     * Одно основание — одна нить: тот же факт под двумя сущностями считается
     * дважды. Основание без ранга (факт, заведённый до перестройки) не
     * считается ни одним рангом — выдуманный ранг хуже отсутствующего, его
     * проставит миграция стенда.
     */
    val byAuthority: Map<String, Int> = emptyMap(),
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
    /** Оценка ТЗ против нужд — у задания по материалу типа `tor`. */
    val assessment: TorAssessment? = null,
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
    fun putMaterial(
        project: String,
        name: String,
        kind: String,
        text: String,
        author: String,
        /** Код прежней версии того же входного: диф канонов блоками, пометы «источник обновлён». */
        supersedes: String? = null,
        /**
         * Ранг доверия источника. На проекте поля знаний v2 обязателен и
         * приходит С ФОРМЫ: доверие называет человек. На проекте, где поле
         * знаний выключено, спрашивать некого — ранг выводится по прежнему
         * типу входного (`Authority.ofMaterialKind`), и данные не переписываются.
         */
        authority: String? = null,
        /** Профиль содержимого (Д2а): ставит разбор; при загрузке известен редко. */
        profile: ContentProfile? = null,
    ): String

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
    fun putFacts(
        project: String,
        material: String,
        raw: String,
        author: String,
        /** Задание инженера — режим даташита выбирает по нему узел и кандидата. */
        intent: String = "",
    ): FactIntake

    /** Анкета узла из задания («обнови параметры SC-PLT»): ключ · единица — для промпта даташита. */
    fun questionnaireKeys(project: String, intent: String): List<Pair<String, String>>

    /** Диспозицию меняет человек: служба предлагает, решает инженер. */
    fun dispose(
        project: String,
        fact: String,
        disposition: Disposition,
        reason: String,
        author: String,
        /** Обязательно при `assumed` (истина схем): владелец · точка · способ проверки · цена ошибки. */
        assumption: Assumption? = null,
    ): Fact

    /** Тема разрешается в сущность: принятые факты темы обязаны найти адрес к точке. */
    fun resolveTopic(project: String, topic: String, entity: String, author: String): Topic

    /**
     * Слить тему в другую: один предмет — одна тема, как бы его ни звали
     * документы. Возвращает ГОЛОВУ цепочки — адрес, по которому теперь
     * читаются факты обеих.
     *
     * Сливает ЧЕЛОВЕК: ИИ видит тождество и предлагает его связью `same_as`,
     * но щелчок делает инженер (ОНТОЛОГИЯ-ФОРМИРОВАНИЯ, rights). Факты при
     * этом не переписываются — они остаются при своих темах, а чтение и счёт
     * идут по цепочке `merged_into` до головы; отменить слияние поэтому
     * значит снять одно поле, а не собирать факты обратно.
     *
     * @param reason чем именно темы оказались одним предметом: уходит в
     *   обоснование связи — связь без причины неотличима от случайной
     */
    fun mergeTopic(project: String, topic: String, into: String, author: String, reason: String = ""): Topic

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
        /**
         * Роль автора в проекте. Ручной ввод — кандидат-факт эксперта
         * (manual_input_rule), и источником у него служат учётка · роль · дата:
         * якоря у руки нет. На проекте поля знаний v2 роль обязательна; на
         * проекте прохода её не спрашивают, и источником остаётся «инженер, дата».
         */
        role: String? = null,
        /** Ранг доверия; умолчание ручного факта — экспертный. */
        authority: String? = null,
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

// --- Сверка ввода и предложений (СВЕРКА-РУЧНОГО-ВВОДА) --------------------
//
// Механизм ОДИН и для руки эксперта, и для предложений синтеза: введённое не
// попадает в модель напрямую — оно становится кандидатом-фактом и проходит
// четыре вопроса (дубль · противоречие · соединение · нехватка). Решает
// человек: служба показывает находку с названным отличием и предлагает
// действия, а модель меняет только `apply`.

/**
 * Откуда пришёл кандидат.
 *
 * Разница не в механике — она одна, — а в происхождении заведённой сущности:
 * рука эксперта даёт провенанс человека, предложение синтеза — службы.
 */
enum class CandidateOrigin(val word: String) {
    MANUAL("ручной ввод"),
    SYNTHESIS("предложение синтеза"),
}

/**
 * Кандидат на сверку: понятие онтологии плюс то, что внесли.
 *
 * @property localId местный номер строки ввода — им находка адресует ответ
 *   обратно в ту же строку экрана («c1»), пока сущности ещё нет
 * @property payload содержимое будущей сущности как есть; текст не
 *   переписывается — сравнение идёт по нормализованным КЛЮЧАМ, а человеку
 *   показываются его собственные слова
 */
data class Candidate(
    val localId: String,
    val concept: String,
    val payload: JsonNode,
    val origin: CandidateOrigin = CandidateOrigin.MANUAL,
) {
    init {
        require(localId.isNotBlank()) { "у кандидата нет местного номера: находке некуда вернуться" }
        require(payload.isObject) { "кандидат «$localId» без содержимого: сверять нечего" }
        // Понятие вне онтологии не образуется — отказ здесь, а не тихий
        // пропуск на середине сверки (ОНТОЛОГИЯ-ФОРМИРОВАНИЯ).
        GeneratedOntology.of(concept)
    }
}

/** Четыре вопроса сверки — больше служба задавать не вправе. */
enum class Question(val word: String) {
    DUPLICATE("дубль"),
    CONTRADICTION("противоречие"),
    CONNECTION("соединение"),
    GAP("нехватка"),
}

/** Вердикт — группа дифа «поле → постановка»; слова истины онтологии. */
enum class Verdict(val word: String) {
    NEW("новое"),
    AUGMENT("дополнить"),
    CONTRADICT("противоречит"),
    CONFIRM("подтверждено"),
}

/**
 * Чем нашли: ключом идентичности (ступень 1, без токенов) или смыслом
 * (ступень 2, вызовом службы). Разделение видно человеку: «по ключу» — это
 * тождество данных, «по смыслу» — мнение с уверенностью.
 */
enum class Match(val word: String) {
    KEY("по ключу"),
    SEMANTIC("по смыслу"),
}

/** Как соотносятся два значения после приведения к канону. */
enum class Comparison(val word: String) {
    EQUAL("совпало"),
    DIFFERS("расходится"),
    INCOMPARABLE("не сравнимо"),
}

/**
 * Отличие словами: что сравнивали, что с чем и почему несравнимо.
 *
 * Обязательная часть находки: «похоже» без «чем именно» — брак
 * (ОНТОЛОГИЯ-ФОРМИРОВАНИЯ, reconciliation.rights).
 */
data class FieldDifference(
    val comparison: Comparison,
    val field: String,
    /** Значение кандидата — его собственными словами. */
    val mine: String,
    /** Значение принятого — его собственными словами. */
    val theirs: String,
    val reason: String = "",
)

/**
 * Что человек может сделать с находкой. Одно действие на карточку, и ни одно
 * не выполняется само: сливает, уточняет и оспаривает ЧЕЛОВЕК.
 */
enum class Action(val word: String) {
    ACCEPT_NEW("принять новым"),
    MERGE_INTO("слить"),
    REFINE("уточнение"),
    GENERALIZE("обобщить"),
    LINK_BASIS("привязать основание"),
    MARK_CONTESTED("оспорить"),
    FIX_INPUT("исправить ввод"),
    DISMISS("снять находку"),
}

/**
 * Находка сверки: что нашли, чем сравнивали и что предлагаем сделать.
 *
 * @property target код принятой сущности или факта, с которым сравнивали;
 *   пусто — сравнивать было не с чем (новое, нехватка)
 * @property comparedFields поля, по которым шло сравнение
 * @property basis факты-основания с якорями; у предложения их ≥ 1 либо
 *   помета «без документального основания»
 * @property blocking пока не закрыто — сущности не будет
 * @property missing имя незакрытой обязательной связи («owns→stakeholder»)
 */
data class Finding(
    val question: Question,
    val verdict: Verdict,
    val target: String? = null,
    val match: Match = Match.KEY,
    val confidence: Double = 1.0,
    val comparedFields: List<String> = emptyList(),
    val difference: FieldDifference? = null,
    val basis: List<String> = emptyList(),
    val offers: List<Action> = emptyList(),
    val blocking: Boolean = false,
    val missing: String? = null,
) {
    init {
        require(confidence in 0.0..1.0) { "уверенность находки вне 0…1: $confidence" }
        // Находка, назвавшая цель, обязана назвать и чем именно сравнивала:
        // иначе человек читает «похоже» и не может проверить.
        if (target != null) {
            require(comparedFields.isNotEmpty()) {
                "находка о «$target» не называет полей сравнения — «похоже» без «чем именно» брак"
            }
            requireNotNull(difference) {
                "находка о «$target» без отличия — сказать «похоже» и не сказать «чем» нельзя"
            }
        }
        if (question == Question.GAP) {
            require(!missing.isNullOrBlank()) { "нехватка без имени обязательной связи ничего не говорит" }
            require(blocking) { "нехватка по must_link не бывает необязательной: сущности без связи не будет" }
        }
        require(offers.isNotEmpty()) { "находка без предложенного действия человеку бесполезна" }
    }
}

/**
 * Кандидат после сверки: его факт, его находки и то, что мешает принять.
 *
 * @property candidateFact код кандидата-факта: ручной ввод — источник,
 *   равный документу по механике и отличный рангом
 * @property blocking незакрытые обязательные связи понятия
 * @property decided действие, которое человек уже применил; пусто — решение
 *   ещё не принято
 */
data class ReconcileItem(
    val localId: String,
    val concept: String,
    val candidateFact: String,
    val authority: String,
    val source: FactSource,
    val verdict: Verdict,
    val findings: List<Finding>,
    val blocking: List<String> = emptyList(),
    val decided: Action? = null,
    val note: String = "",
)

/**
 * Запуск сверки. Живёт видом `synthesis_run`: механизм один, и отдельного
 * вида под сверку истина схем не заводит.
 *
 * @property aiCalled был ли живой вызов службы. Детерминированная ступень
 *   отвечает `false` всегда — это и есть мера «дубль по ключу найден без
 *   живого вызова, журнал ИИ не прирастает»
 * @property open остались ли кандидаты без решения человека
 */
data class ReconcileRun(
    val id: String,
    val status: String,
    val sliceFingerprint: String,
    val ontologyVersion: String,
    val aiCalled: Boolean,
    val open: Boolean,
    val items: List<ReconcileItem>,
    val note: String = "",
)

/** Что изменилось в модели после решения человека. */
data class Applied(
    val created: List<String>,
    val updated: List<String>,
    val links: List<String>,
    val facts: List<String>,
    val note: String = "",
)

/**
 * Обязательная связь понятия не закрыта: принимать нечего, пока сторона не
 * выбрана. Отдельным типом — чтобы маршрут ответил 422 с именем недостающей
 * связи, а не общим «нельзя».
 */
class MustLinkMissing(val missing: String, message: String) : IllegalStateException(message)

/**
 * Сверка: ручной кандидат и предложение синтеза одним входом.
 *
 * Права службы (ОНТОЛОГИЯ-ФОРМИРОВАНИЯ, reconciliation.rights): предлагает
 * слить · уточнить · связать · пометить противоречие · принять новым. НЕ
 * вправе: сливать сама, менять принятое, выбирать победителя в противоречии,
 * придумывать основание.
 */
interface Reconcile {

    /**
     * Сверить кандидатов и показать находки. Модель НЕ меняется: заводятся
     * только кандидаты-факты и запись самого запуска — снимок принятых
     * сущностей до и после `preview` совпадает.
     *
     * @param role роль автора в проекте: у ручного факта источник — учётка ·
     *   роль · дата, якоря у руки нет
     * @param semantic просить ли вторую ступень. Детерминированная ступень
     *   работает всегда и бесплатно; семантику надстраивает служба ИИ
     */
    fun preview(
        project: String,
        candidates: List<Candidate>,
        author: String,
        role: String,
        semantic: Boolean = false,
    ): ReconcileRun

    /** Запуск по коду: идемпотентно, без нового вызова — находки уже записаны. */
    fun run(project: String, run: String): ReconcileRun

    /** Запуски с нерешёнными кандидатами: что ждёт человека. */
    fun open(project: String): List<ReconcileRun>

    /**
     * Применить решение человека — ЕДИНСТВЕННЫЙ путь изменения модели в
     * сверке.
     *
     * @param finding номер находки в кандидате: действие относится к
     *   конкретной находке, а не к кандидату вообще
     * @param target цель действия: сущность слияния, уточняемое понятие либо
     *   сторона, закрывающая нехватку
     * @param reason почему так решили; без причины решение не ставится
     */
    fun apply(
        project: String,
        run: String,
        localId: String,
        finding: Int,
        action: Action,
        target: String? = null,
        reason: String,
        author: String,
    ): Applied
}

// --- Исследование полноты (ВНЕШНЕЕ-ИССЛЕДОВАНИЕ-И-ВЕРИФИКАЦИЯ) ------------
//
// Контур, который замыкает поле знаний со стороны того, чего в нём НЕТ. На
// входе в сцены 3 · 4 · 5 · 7 · 12 и на точке система спрашивает, чего не
// хватает, и собирает вопросы из рамки и онтологии. Дальше — цикл: промпт →
// запуск вне продукта → результат документом → разбор → ещё раз по остаткам.
//
// Вызовов модели здесь НЕТ НИ ОДНОГО. Промпт собирается из среза поля
// детерминированно, исследование выполняет внешний контур (поиск, Deep
// Research, назначенный аналитик), и продукт на это токенов не тратит — мера
// прямая: журнал ИИ после формулирования и запуска не прирастает.
//
// Ничего найденного в модель мимо общего пути не попадает: результат
// возвращается МАТЕРИАЛОМ ранга `doubtful` и идёт тем же путём — атомизация,
// синтез, сверка, решение человека. Ранг поднимается до `reference` только
// после того, как источники подтвердил человек (research_rule).

/**
 * Класс вопроса исследования. Пять — ровно ключи `research_task.accepted_summary`
 * истины схем: шестого класса не бывает, и придумывать его в коде запрещено.
 *
 * @property code машинное имя класса
 * @property key ключ итога по классам в карточке задачи
 * @property word слово, которым класс назван человеку и с которого начинается
 *   строка вопроса: «сторона: …», «норма: …»
 */
enum class ResearchClass(val code: String, val key: String, val word: String) {
    STAKEHOLDER("stakeholder", "stakeholders", "сторона"),
    PROGRAM("program", "programs", "программа"),
    NORM("norm", "norms", "норма"),
    APPLICATION("application", "applications", "применение"),
    ANALOG("analog", "analogs", "аналог"),
    ;

    companion object {

        /** Класс по слову или машинному имени; неизвестное — `null`, а не догадка. */
        fun of(текст: String?): ResearchClass? {
            val значение = текст?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.word == значение || it.code == значение || it.key == значение }
        }

        /** Все классы словами — для отказа: человеку показывается выбор, а не перечень кодов. */
        fun words(): String = entries.joinToString(" · ") { it.word }
    }
}

/**
 * Откуда запущено исследование — истина схем `research_task.trigger_scene`:
 * «{scene?:ref scene, gate?:ref gate}, ровно одно из двух».
 *
 * Союз держит Kotlin типом: генератор схем сворачивает его в бестиповый
 * object, и JSON Schema «ровно одно из двух» не стережёт.
 */
sealed interface ResearchTrigger {

    /** Место словами: им задача называется на экране и в имени файла промпта. */
    val word: String

    /** Сцена входа: 3 стороны · 4 повестка · 5 нормы · 7 аналоги · 12 бенчмарки. */
    data class Scene(val scene: String) : ResearchTrigger {
        override val word: String get() = "сцена $scene"
    }

    /** Точка: перед воротами спрашивается то, чего не хватает по всем классам. */
    data class Gate(val gate: String) : ResearchTrigger {
        override val word: String get() = "точка $gate"
    }
}

/**
 * Что принято из результата по классам (истина схем `accepted_summary`).
 *
 * Считаются ПОДТВЕРЖДЁННЫЕ факты результата: пока источники не подтверждены
 * человеком, принятым не считается ничто — материал исследования сомнителен по
 * построению. По нулям этого итога собираются остатки следующего цикла.
 */
data class AcceptedSummary(
    val stakeholders: Int = 0,
    val programs: Int = 0,
    val norms: Int = 0,
    val applications: Int = 0,
    val analogs: Int = 0,
) {

    fun of(класс: ResearchClass): Int = when (класс) {
        ResearchClass.STAKEHOLDER -> stakeholders
        ResearchClass.PROGRAM -> programs
        ResearchClass.NORM -> norms
        ResearchClass.APPLICATION -> applications
        ResearchClass.ANALOG -> analogs
    }

    fun plus(класс: ResearchClass, сколько: Int): AcceptedSummary = when (класс) {
        ResearchClass.STAKEHOLDER -> copy(stakeholders = stakeholders + сколько)
        ResearchClass.PROGRAM -> copy(programs = programs + сколько)
        ResearchClass.NORM -> copy(norms = norms + сколько)
        ResearchClass.APPLICATION -> copy(applications = applications + сколько)
        ResearchClass.ANALOG -> copy(analogs = analogs + сколько)
    }

    /** Классы, по которым не принято ничего, — вопросы следующего цикла. */
    fun open(): List<ResearchClass> = ResearchClass.entries.filter { of(it) == 0 }

    val total: Int get() = ResearchClass.entries.sumOf { of(it) }
}

/**
 * Задача исследования полноты.
 *
 * @property status formulated · launched · received · parsed. Статус
 *   `parsed` ВЫЧИСЛЯЕМЫЙ: разбор результата — путь загрузки, а не
 *   исследования, и задача узнаёт о нём по фактам своего материала, а не по
 *   отдельному нажатию
 * @property sliceFingerprint срез поля, из которого собраны вопросы: по нему
 *   видно, что промпт собран по нынешнему полю, а не по вчерашнему
 * @property promptVersion версия сборки промпта — по ней запуск воспроизводится
 * @property iteration номер цикла по тому же месту
 * @property resultMaterial материал результата; ранг `doubtful` до
 *   подтверждения источников человеком
 */
data class ResearchTask(
    val id: String,
    val trigger: ResearchTrigger,
    val questions: List<String>,
    val sliceFingerprint: String,
    val promptVersion: String,
    val iteration: Int,
    val status: String,
    val resultMaterial: String? = null,
    val accepted: AcceptedSummary = AcceptedSummary(),
    val note: String = "",
)

/**
 * Исследование полноты: вопросы, промпт, результат, подтверждение источников,
 * следующий цикл.
 *
 * Ни один метод не зовёт модель. Служба здесь только СПРАШИВАЕТ и принимает
 * ответ; отвечает внешний контур, а решает — как и везде — человек.
 */
interface Research {

    /**
     * Сформулировать вопросы для места входа.
     *
     * @param questions вопросы инженера. Пусто — собираются по пяти классам из
     *   среза поля: снять и дописать их человек может, придумать шестой класс
     *   не может. Строка вопроса начинается классом: «сторона: …»
     */
    fun formulate(
        project: String,
        trigger: ResearchTrigger,
        author: String,
        questions: List<String> = emptyList(),
    ): ResearchTask

    fun task(project: String, task: String): ResearchTask

    fun list(project: String): List<ResearchTask>

    /**
     * Промпт исследования: контекст проекта · вопросы · требования к ответу.
     *
     * Уходит ЧЕЛОВЕКУ файлом, поэтому перед выдачей проверяется гриф среза:
     * `dsp` и `commercial` во внешний контур без явного разрешения не уходят,
     * а `state_secret` в поле знаний не попадает вовсе (classification_rule).
     */
    fun prompt(project: String, task: String): String

    /** Запущено вне продукта: задача ждёт результата. Вызова модели здесь нет. */
    fun launch(project: String, task: String, author: String, note: String = ""): ResearchTask

    /**
     * Принять результат файлом: материал ранга `doubtful` со связью `output_of`
     * на задачу. Разбор идёт общим путём загрузки — этот метод его не делает.
     */
    fun result(project: String, task: String, name: String, text: String, author: String): ResearchTask

    /**
     * Подтвердить источники фактов результата: они становятся
     * `evidence=corroborated`, а материал поднимается до `reference`.
     *
     * Подтверждает ЧЕЛОВЕК и только у своего результата: факт чужого материала
     * этой задачей не подтверждается.
     */
    fun confirmSources(project: String, task: String, facts: List<String>, author: String): ResearchTask

    /**
     * Следующий цикл по ОСТАТКАМ: классы, по которым ответа нет, плюс
     * дописанное инженером. Остановка цикла — решение человека: когда
     * остатков нет и добавить нечего, следующей задачи не заводится.
     */
    fun next(
        project: String,
        task: String,
        author: String,
        questions: List<String> = emptyList(),
    ): ResearchTask
}
