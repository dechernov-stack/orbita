// Спрос-взвешенное качество (TZ-BAL-005): интеграл по ячейкам карты спроса
// по зонам ОБСЛУЖИВАНИЯ, не по геометрическому footprint, раздельно по классам.
// Эталон spec/ballistics_semantics.py, один в один.
package orbita.bal

/** Ячейка с весом спроса — вход интеграла качества (карта спроса шага 2). */
data class WeightedCell(val lat: Double, val weight: Double)

/** Интеграл по весам спроса; inService — принадлежность зоне обслуживания [0..1]. */
fun demandWeightedScore(cells: List<WeightedCell>, inService: (WeightedCell) -> Double): Double =
    cells.sumOf { it.weight * inService(it) }

/** Раздельно по классам (Р9): усреднение между классами не выполняется. */
fun demandWeightedScoreByClass(
    cells: List<WeightedCell>,
    zoneByClass: Map<String, (WeightedCell) -> Double>,
): Map<String, Double> = zoneByClass.mapValues { (_, zone) -> demandWeightedScore(cells, zone) }
