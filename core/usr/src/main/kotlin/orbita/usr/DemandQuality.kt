// Спрос-взвешенное качество и агрегация (TZ-USR-007). Поведение — эталон
// spec/demand_semantics.py: ячейка с нулевым спросом не влияет на интеграл;
// сумма весов поясов равна единице.
package orbita.usr

import kotlin.math.floor

/** Интеграл качества по весам спроса: «идеальный сервис у полюсов» получает вес спроса, а не сетки. */
fun demandWeightedQuality(cells: Map<String, DemandCell>, quality: (DemandCell) -> Double): Double =
    cells.values.sumOf { it.weight * quality(it) }

data class LatitudeBand(val band: Int, val weight: Double, val quality: Double)

/** Широтный профиль: качество и вес спроса по поясам [bandDeg] градусов. */
fun latitudeProfile(
    cells: Map<String, DemandCell>,
    quality: (DemandCell) -> Double,
    bandDeg: Int = 15,
): List<LatitudeBand> {
    data class Acc(var w: Double = 0.0, var qw: Double = 0.0)

    val bands = sortedMapOf<Int, Acc>()
    for (c in cells.values) {
        val b = (floor(c.lat / bandDeg) * bandDeg).toInt()
        val acc = bands.getOrPut(b) { Acc() }
        acc.w += c.weight
        acc.qw += c.weight * quality(c)
    }
    return bands.map { (b, a) -> LatitudeBand(b, a.w, if (a.w > 0) a.qw / a.w else 0.0) }
}
