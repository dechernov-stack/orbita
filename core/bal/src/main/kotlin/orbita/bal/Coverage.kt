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

/**
 * Метрики покрытия по целям из документа contracts/visibility.
 *
 * Цели передаются СПИСКОМ, а не выводятся из пролётов: цель без единого
 * пролёта в пролётах не встречается и молча исчезла бы из выдачи, а
 * непокрытая ячейка — главное, что карта покрытия обязана показать
 * (эталон: «непокрытая цель остаётся в выдаче с нулевой доступностью»).
 */
fun coverageByTarget(
    visibility: com.fasterxml.jackson.databind.JsonNode,
    durationS: Double,
    targets: Collection<String> = emptyList(),
    serviceZoneOnly: Boolean = false,
): Map<String, CoverageMetrics> {
    val byTarget = linkedMapOf<String, MutableList<Pair<Double, Double>>>()
    targets.forEach { byTarget[it] = mutableListOf() }
    visibility["passes"].forEach { p ->
        if (serviceZoneOnly && !p.path("in_service_zone").asBoolean(false)) return@forEach
        byTarget.getOrPut(p["target_ref"].asText()) { mutableListOf() } +=
            p["start_s"].asDouble() to p["end_s"].asDouble()
    }
    return byTarget.mapValues { (_, w) -> coverageMetrics(w, durationS) }
}

/**
 * Доля покрытия по часам прогона — вход суточного взвешивания профилем
 * активности (VizData.availability, Daily): пик спроса, пришедшийся на провал
 * покрытия, простое среднее по часам скрывает. Неполный последний час
 * отбрасывается, как неполное окно усреднения в тепловой карте.
 */
fun hourlySeries(windows: List<Pair<Double, Double>>, durationS: Double): List<Double> {
    val merged = mergeWindows(windows)
    val hours = Math.floor(durationS / 3600.0).toInt()
    return (0 until hours).map { h ->
        val from = h * 3600.0
        val to = from + 3600.0
        merged.sumOf { (s, e) -> (minOf(e, to) - maxOf(s, from)).coerceAtLeast(0.0) } / 3600.0
    }
}

/** Класс ячейки карты покрытия. Код уходит клиенту: клиент красит, не считает. */
enum class CoverageClass(val code: String) {
    Ok("ok"), Degraded("degraded"), Gap("gap")
}

/**
 * Класс ячейки считает СЕРВЕР — клиент только красит (шаг 16, ловушка 2).
 *
 * gap — есть окно горизонта вообще без связи: провал, который среднее скрывает;
 * degraded — худшее окно хуже половины среднего: покрытие неровное;
 * ok — остальное. Порог половины — правило представления, не физика: он делит
 * ровное и рваное покрытие, а не годное и негодное.
 */
fun coverageClass(meanAvail: Double, worstAvail: Double): CoverageClass = when {
    worstAvail <= 0.0 -> CoverageClass.Gap
    worstAvail < meanAvail / 2.0 -> CoverageClass.Degraded
    else -> CoverageClass.Ok
}
