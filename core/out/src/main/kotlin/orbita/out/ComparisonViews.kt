// Представления экранов 7 (сравнение вариантов) и 12 (система в целом),
// STEP-7-9 §8.
//
// Нормировка розы KPI и раскраска матрицы рисков считаются ЗДЕСЬ. Соблазн
// пересчитать их в клиенте велик именно на этих экранах — и именно там
// появилась бы вторая реализация правила (STEP-7-9, ловушка 2).
package orbita.out

import orbita.bal.KpiAxes
import orbita.bal.RadarChart
import orbita.bal.RadarOption
import orbita.bal.paretoFrontByAxes
import orbita.bal.radarSeries
import orbita.req.Criticality
import orbita.req.RegisterSummary
import orbita.req.criticality

/** Экран 7: сравнение вариантов построения. */
data class ComparisonView(
    val options: List<RadarOption>,
    val radar: RadarChart,
    /** Недоминируемые варианты; направления осей берутся из того же перечня. */
    val paretoFront: List<String>,
    val axes: List<String>,
)

/**
 * Сравнение строится по НАБОРУ вариантов: одного мало — нормировать не по чему.
 * Состав набора уходит наружу вместе с диаграммой, иначе её сравнят с чужой
 * и получат ложный вывод (STEP-6, ловушка 2).
 */
fun comparisonView(
    options: List<RadarOption>,
    axes: List<String> = listOf("quality", "cost", "reliability"),
    directions: KpiAxes = KpiAxes.default,
): ComparisonView {
    require(options.size >= 2) { "сравнение требует не менее двух вариантов: нормировать не по чему" }
    return ComparisonView(
        options = options,
        radar = radarSeries(options, axes, directions),
        paretoFront = paretoFrontByAxes(options, directions = directions),
        axes = axes,
    )
}

/** Клетка матрицы рисков 5×5 с уже вычисленной критичностью. */
data class RiskCell(val probability: Int, val impact: Int, val criticality: String, val risks: List<String>)

/** Экран 12: система в целом — бюджеты, верификация, риски, проблемы. */
data class SystemOverview(
    val requirements: Int,
    val components: Int,
    /** Состояние верификации: сколько требований в каждом состоянии. */
    val verification: Map<String, Int>,
    /** Бюджеты с превышением — то, ради чего экран и открывают. */
    val budgets: Map<String, BudgetBar>,
    val budgetsOverrun: List<String>,
    val riskSummary: RegisterSummary,
    /** Матрица рисков 5×5: критичность каждой клетки посчитана сервером. */
    val riskMatrix: List<RiskCell>,
    val problems: List<String>,
)

/**
 * Матрица рисков как навигация по критичности. Раскраска — следствие
 * критичности клетки, а не отдельное правило в интерфейсе: иначе матрица
 * и реестр однажды покрасят один риск по-разному.
 */
fun riskMatrix(risks: List<Pair<String, Pair<Int, Int>>>): List<RiskCell> =
    (5 downTo 1).flatMap { impact ->
        (1..5).map { probability ->
            RiskCell(
                probability = probability,
                impact = impact,
                criticality = criticality(probability, impact).code,
                risks = risks.filter { it.second == probability to impact }.map { it.first }.sorted(),
            )
        }
    }

// cellCriticality здесь больше нет (Шаг 16 §2.1): обёртка над criticality без
// собственного содержания — второй вход в то же правило. Матрица рисков зовёт
// criticality напрямую, и раскраска берётся из правила по построению.
