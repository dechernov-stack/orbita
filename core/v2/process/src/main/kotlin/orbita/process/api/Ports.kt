// Порты движка процесса (ТЗ-BACKEND §3, РЕШЕНИЕ-ДВИЖОК-ПРОЦЕССА).
//
// Домен в движке не живёт: там состояние процесса — какие сцены открыты,
// чьи задачи, какие точки пройдены. Условия ворот считает ВНЕШНИЙ оценщик,
// и это порт, который реализует readiness.
package orbita.process.api

/** Состояние сцены — вычисляется движком, ручного статуса нет. */
enum class SceneState {
    /** Вход не выполнен: сцена закрыта, и видно, чем именно. */
    LOCKED,

    /** Вход выполнен, выход — нет: с этой сценой работают сейчас. */
    OPEN,

    /** Выход закрыт: сцена прожита. */
    DONE,
}

/**
 * Условие сцены или точки: заголовок для человека, проверка для движка,
 * состояние и причина отказа.
 *
 * Отдельный вид нужен интерфейсу: панель условий показывает ВСЕ условия
 * (✓ выполненные и ☐ невыполненные), а не только то, чего не хватает —
 * иначе не видно, из чего сцена состоит.
 */
data class ConditionView(
    val title: String,
    val check: String,
    val passed: Boolean,
    val why: String?,
    /** Блокирующее условие держит точку; остальные — помета. */
    val blocking: Boolean = true,
)

data class SceneView(
    val key: String,
    val title: String,
    val order: Int,
    val role: String,
    val question: String,
    val state: SceneState,
    /** Чего не хватает, чтобы сцена открылась либо закрылась — словами. */
    val blockers: List<String>,
    val steps: List<StepView>,
    /** Условия входа целиком: чем сцена держится закрытой. */
    val entry: List<ConditionView> = emptyList(),
    /** Условия выхода целиком: что должно случиться, чтобы сцена прожилась. */
    val exit: List<ConditionView> = emptyList(),
    /** Что сцена даёт на выходе — словами (нить потока). */
    val output: String = "",
    /** Кто ждёт эту сцену: другие сцены и точки. */
    val awaitedBy: List<String> = emptyList(),
    /** Входные потоки процесса ЖЦ — данными полки, а не памятью. */
    val inputFlows: List<String> = emptyList(),
    /** Окно плана работ фазы: начало и конец. Пусто — «план не задан». */
    val window: Pair<String, String>? = null,
    /** Мероприятия сцены: единицы работы с входами и выходами. */
    val activities: List<ActivityView> = emptyList(),
    /** Чем сцена связана с другими (FS · SS · FF · INPUT) и почему — словами шаблона (шип G). */
    val links: List<SceneLinkView> = emptyList(),
    /** Ключ сцены шаблона, экземпляром которой является эта (аванпроект на узел); null — обычная сцена. */
    val instanceOf: String? = null,
    /** Код узла состава, на который развёрнут экземпляр. */
    val node: String? = null,
)

/**
 * Связь сцен из шаблона: FS — после конца, SS — после начала, FF — конец
 * вместе с концом, INPUT — вход из выхода. Обоснование обязательно: связь
 * без «почему» — произвол планировщика.
 */
data class SceneLinkView(val on: String, val type: String, val why: String)

data class StepView(val title: String, val place: String, val hint: String, val done: Boolean)

/** Состояние мероприятия — вычисляется, ручного «готово» нет. */
enum class ActivityState {
    /** Ещё не начиналось: выходов нет. */
    NOT_STARTED,

    /** Вход выполнен, работать можно. */
    AVAILABLE,

    /** Выходы появляются, но не все. */
    IN_PROGRESS,

    /** Все свои выходы есть и условие выполнено. */
    DONE,

    /** Вход не выполнен — с причиной. */
    BLOCKED,
}

/**
 * Выход мероприятия: именованный артефакт со счётчиком из ДАННЫХ.
 *
 * @property producedIn сцена, где артефакт рождается у нас, если это не
 *   текущая: тогда выход показывается стрелкой «→ сцена N», а не пустотой,
 *   и в условие завершения мероприятия не входит
 */
data class OutputView(
    val what: String,
    val kind: String,
    val min: Int,
    val count: Int,
    val producedIn: String?,
    val satisfied: Boolean,
)

/**
 * Мероприятие — ЕДИНИЦА РАБОТЫ (РЕШЕНИЕ-ПРОЦЕСС-НА-ЭКРАНЕ).
 *
 * Не «форма с кнопкой», а блок метода: цель, входы, выходы-счётчики и
 * рабочая поверхность. Сцена — группа мероприятий с потоками между ними.
 */
data class ActivityView(
    val code: String,
    val name: String,
    val goal: String,
    val role: String,
    val track: String,
    val methodGroup: String,
    val inputs: List<String>,
    val outputs: List<OutputView>,
    /** Маршрут фрагмента: где эта работа делается. */
    val surface: String,
    val state: ActivityState,
    /** Почему не начать — словами (когда BLOCKED). */
    val blockedBy: List<String>,
    /**
     * Что откроет это мероприятие: коды мероприятий, которые ждут его
     * выхода, и сцены, где рождаются его «чужие» выходы.
     *
     * Считается по ШАБЛОНУ (вход одного названо выходом другого), второй
     * карты зависимостей нет — расходиться нечему. Нужно рейке инженера:
     * «откроет 3» отвечает на вопрос «зачем я это делаю».
     */
    val opens: List<String> = emptyList(),
)

/** Дорожка схемы фазы: сцены (проектирование) либо мероприятия метода. */
data class LaneView(
    val key: String,
    val title: String,
    val of: String,
    val activities: List<ActivityView>,
)

data class GateView(
    val key: String,
    val title: String,
    val order: Int,
    val plannedDate: String?,
    val passed: Boolean,
    /** Блокирующие критерии, не выполненные сейчас. */
    val blocking: List<String>,
    /** Роль, которая принимает решение по точке (из шаблона фазы). */
    val role: String = "",
    /** Критерии целиком — ✓ и ☐ с причиной, как условия сцены. */
    val criteria: List<ConditionView> = emptyList(),
    val expertise: ExpertiseView? = null,
    /** Замечания обзора этой точки — открытые и закрытые. */
    val findings: List<FindingView> = emptyList(),
    val decision: DecisionView? = null,
    /** Матрица зрелости комплекта (артефакт × точки) — у точки решения. */
    val matrix: List<PositionView> = emptyList(),
    /** Фаза, которую открывает решение «approve» (KDP-A → Phase A). */
    val opensPhase: String? = null,
    /** Чек-лист — по критериям и вопросам ДРУГОЙ точки (внутренний обзор → MCR). */
    val checklistOf: String? = null,
    /** Помета о легенде кодов зрелости, пока её нет от владельца. */
    val legendNote: String? = null,
)

data class PhaseView(
    val project: String,
    val standard: String,
    val phase: String,
    val currentScene: String?,
    val scenes: List<SceneView>,
    val gates: List<GateView>,
    /** Дорожки схемы фазы: проектирование · моделирование · управление. */
    val lanes: List<LaneView> = emptyList(),
)

/**
 * Оценщик условий (порт). Движок спрашивает: «выполнено ли условие X в
 * проекте P?» — и получает ответ по данным домена вместе с причиной отказа.
 *
 * Реализация живёт в readiness: ворота остаются нашими правилами, движок
 * их только исполняет.
 */
interface GateEvaluator {
    /** @return null, если условие выполнено; иначе — причина словами. */
    fun why(project: String, check: String): String?
}

/**
 * Счётчик выходов (порт): «сколько сущностей вида K в проекте P».
 *
 * Движку нужны ЧИСЛА из данных, а домена в нём нет — поэтому счёт
 * спрашивается снаружи, как и условия ворот.
 */
fun interface OutputCounter {
    fun count(project: String, kind: String): Int
}

interface ProcessEngine {
    /** Завести фазу проекта по шаблону: сцены и точки — из полки. */
    fun openPhase(project: String, templateCode: String): PhaseView

    /** Пересчитать состояние: события домена меняют то, что открыто. */
    fun view(project: String): PhaseView

    /** Пройти точку. Блокирующее невыполненное условие — отказ движка. */
    fun passGate(project: String, gate: String, decidedBy: String): PhaseView

    /**
     * Решение по точке с исходом approve · return · defer.
     *
     * Роль решающего обязана быть ролью точки из шаблона — иначе
     * [RoleRefusedException] с именами; approve при блокирующем условии —
     * [GateHeldException] с критерием; return — только при открытых
     * замечаниях: вернуть без слов «куда и почему» нельзя.
     */
    fun decide(
        project: String,
        gate: String,
        by: String,
        roles: Set<String>,
        outcome: String,
        note: String?,
    ): PhaseView
}
