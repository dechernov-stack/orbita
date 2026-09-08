// Точки как экспертизы (шип D, ЗАДАНИЕ-CODE-БОЛЬШОЕ): что точка знает
// о себе сверх «пройдена / держится».
//
// Замечание — не флаг на сцене, а СОБЫТИЕ: пока оно открыто, движок
// заново держит сцену возврата условием выхода; закрыли — сцена прожита
// снова. Решение точки — запись: кем, когда, с каким исходом.
package orbita.process.api

/** Замечание обзора (RFA/RID) с возвратом в сцену. */
data class FindingView(
    val code: String,
    val text: String,
    /** Сцена возврата: там замечание и закрывается работой. */
    val scene: String,
    val gate: String,
    /** open · closed */
    val status: String,
    val author: String,
    /** finding · rfa · rid */
    val kind: String = "finding",
    /** Вопрос экспертизы, на который ответ «нет», если замечание из чек-листа. */
    val question: String? = null,
    val closedBy: String? = null,
)

/** Решение по точке: кем, когда, исход (approve · return · defer). */
data class DecisionView(val by: String, val at: String, val outcome: String, val note: String?)

/**
 * Позиция зрелости: артефакт с кодом зрелости к ЭТОЙ точке и нашей
 * проверкой по таблице зрелости. Одна и та же запись — и контрольная
 * позиция экспертизы (полка Романова), и строка матрицы комплекта
 * (тогда заполнены коды по всем точкам).
 *
 * @property passed null — позиция не сопоставлена с нашим артефактом и не
 *   проверяется: это видно, а не спрятано
 * @property codes код зрелости к каждой точке фазы (у строки матрицы)
 */
data class PositionView(
    val artifact: String,
    val maturity: String,
    val ourRef: String?,
    val check: String?,
    val passed: Boolean?,
    val why: String?,
    val blocking: Boolean,
    val codes: Map<String, String> = emptyMap(),
)

/** Экспертиза точки: цель, вопросы валидации, результаты, главный итог, позиции. */
data class ExpertiseView(
    val goal: String,
    val questions: List<String>,
    val results: List<String>,
    val mainOutcome: List<String>,
    val positions: List<PositionView>,
    /** Код экспертизы полки (EXP-…) либо пункт регламента. */
    val source: String?,
)

/** Точка держится блокирующим условием: отказ движка, не интерфейса. */
class GateHeldException(message: String) : IllegalArgumentException(message)

/** Решение по точке принимает не эта роль — отказ с именами. */
class RoleRefusedException(message: String) : RuntimeException(message)
