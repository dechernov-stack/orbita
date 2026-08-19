// Метрики покрытия ячейки из предрасчитанного расписания пролётов
// (TZ-BAL-005, TZ-BAL-006): разрывы, период повторного обзора, кратность.
// Считаются постобработкой геометрии, пропагатор здесь не вызывается.
package orbita.bal

/**
 * Разрывы покрытия и период повторного обзора.
 *
 * Разрыв — интервал БЕЗ видимости между двумя соседними окнами доступа;
 * период повторного обзора — интервал между НАЧАЛАМИ соседних окон. Первый
 * отвечает на вопрос «сколько ждать связи», второй — «как часто появляется
 * возможность»; при длинных пролётах это разные числа.
 *
 * Краевые интервалы (до первого окна и после последнего) в статистику не
 * входят: они усечены горизонтом прогона и занижают среднее.
 */
data class CoverageMetrics(
    val meanGapS: Double?,
    val maxGapS: Double?,
    val revisitS: Double?,
    /** Среднее число аппаратов в зоне видимости, взвешенное по времени. */
    val multiplicityMean: Double,
    /** Доля времени, когда виден хотя бы один аппарат. */
    val availability: Double,
    val accessWindows: Int,
)

/** Объединение перекрывающихся окон: сколько аппаратов видно — здесь неважно. */
fun mergeWindows(windows: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
    if (windows.isEmpty()) return emptyList()
    val sorted = windows.sortedBy { it.first }
    val merged = mutableListOf(sorted.first())
    sorted.drop(1).forEach { (start, end) ->
        val (curStart, curEnd) = merged.last()
        if (start <= curEnd) {
            merged[merged.lastIndex] = curStart to maxOf(curEnd, end)
        } else {
            merged += start to end
        }
    }
    return merged
}

fun coverageMetrics(windows: List<Pair<Double, Double>>, durationS: Double): CoverageMetrics {
    require(durationS > 0) { "duration must be positive" }
    val merged = mergeWindows(windows)
    val gaps = merged.zipWithNext { (_, prevEnd), (nextStart, _) -> nextStart - prevEnd }
    val starts = merged.map { it.first }
    val revisits = starts.zipWithNext { a, b -> b - a }
    return CoverageMetrics(
        meanGapS = gaps.takeIf { it.isNotEmpty() }?.average(),
        maxGapS = gaps.maxOrNull(),
        revisitS = revisits.takeIf { it.isNotEmpty() }?.average(),
        multiplicityMean = windows.sumOf { it.second - it.first } / durationS,
        availability = merged.sumOf { it.second - it.first } / durationS,
        accessWindows = merged.size,
    )
}

/** Метрики покрытия по каждой цели из документа contracts/visibility. */
fun coverageByTarget(
    visibility: com.fasterxml.jackson.databind.JsonNode,
    durationS: Double,
    serviceZoneOnly: Boolean = false,
): Map<String, CoverageMetrics> {
    val byTarget = linkedMapOf<String, MutableList<Pair<Double, Double>>>()
    visibility["passes"].forEach { p ->
        if (serviceZoneOnly && !p.path("in_service_zone").asBoolean(false)) return@forEach
        byTarget.getOrPut(p["target_ref"].asText()) { mutableListOf() } +=
            p["start_s"].asDouble() to p["end_s"].asDouble()
    }
    return byTarget.mapValues { (_, w) -> coverageMetrics(w, durationS) }
}
