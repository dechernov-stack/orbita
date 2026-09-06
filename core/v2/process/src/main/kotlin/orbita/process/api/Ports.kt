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
data class ConditionView(val title: String, val check: String, val passed: Boolean, val why: String?)

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
)

data class StepView(val title: String, val place: String, val hint: String, val done: Boolean)

data class GateView(
    val key: String,
    val title: String,
    val order: Int,
    val plannedDate: String?,
    val passed: Boolean,
    /** Блокирующие критерии, не выполненные сейчас. */
    val blocking: List<String>,
)

data class PhaseView(
    val project: String,
    val standard: String,
    val phase: String,
    val currentScene: String?,
    val scenes: List<SceneView>,
    val gates: List<GateView>,
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

interface ProcessEngine {
    /** Завести фазу проекта по шаблону: сцены и точки — из полки. */
    fun openPhase(project: String, templateCode: String): PhaseView

    /** Пересчитать состояние: события домена меняют то, что открыто. */
    fun view(project: String): PhaseView

    /** Пройти точку. Блокирующее невыполненное условие — отказ движка. */
    fun passGate(project: String, gate: String, decidedBy: String): PhaseView
}
