// Замыкание вектора KPI результатами моделирования (TZ-BAL-005, TZ-BAL-006).
//
// Направление зависимости: баллистика не знает о ядре потоков, поэтому
// перенос делает flw. Пустые поля шага 3 заполняются РЕЗУЛЬТАТАМИ, а не
// правдоподобными числами: если прогона нет, поля остаются пустыми.
package orbita.flw

import orbita.bal.ClassMetric
import orbita.bal.CoverageMetrics
import orbita.bal.QualityKpi

/**
 * Метрика качества класса — та, что для этого класса имеет смысл (Р9):
 *   A' — вероятность доставки за сутки,
 *   B' — вероятность доставки за отведённое число попыток и пролётов,
 *   C' — вероятность уложиться в требуемое время реакции.
 * Одной метрики «доставка» на все классы не существует: они измеряют разное.
 */
fun ClassResult.kpiMetric(): ClassMetric = when (consumerClass) {
    "C_prime" -> ClassMetric(
        consumerClass, "reaction_time_probability",
        reactionWithinRequired
            ?: throw IllegalStateException("TZ-FLW-006: у класса C' нет P(T ≤ T_треб)"),
    )
    "B_prime" -> ClassMetric(consumerClass, "delivery_probability_n_attempts", deliveryProbability)
    else -> ClassMetric(consumerClass, "delivery_probability_daily", deliveryProbability)
}

/**
 * Блок качества вектора KPI: спрос-взвешенная оценка по ячейкам, метрики
 * классов из прогона и метрики покрытия из постобработки геометрии.
 */
fun qualityFromRun(
    result: FlowRunResult,
    populations: List<PopulationSlice>,
    coverage: CoverageMetrics? = null,
    latitudeProfile: List<Triple<Double, Double, Double>> = emptyList(),
): QualityKpi {
    val quality = QualityKpi(
        demandWeightedScore = demandWeightedDelivery(result, populations).coerceIn(0.0, 1.0),
        latitudeProfile = latitudeProfile,
        byClass = result.byClass.map { it.kpiMetric() },
    )
    return coverage?.let { quality.withCoverage(it) } ?: quality
}
