// Грубая сетка предрасчёта видимости (TZ-BAL-002, ADR-013).
// Применимость сетки ПРОВЕРЯЕТСЯ, а не принимается на веру (ловушка 1):
// грубая ячейка обязана быть меньше диаметра зоны; при нарушении сетка
// измельчается либо расчёт отклоняется с внятным сообщением.
package orbita.bal

import orbita.mod.store.ModelViolationException
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max

data class GridPoint(val id: String, val latDeg: Double, val lonDeg: Double)

/** Километров в градусе дуги большого круга. */
const val KM_PER_DEG = RE_KM * Math.PI / 180.0

/**
 * Квазиравноплощадная грубая сетка: широтные кольца с шагом [cellKm],
 * долготный шаг растёт как 1/cos(широты) — ячейки близки по площади
 * (равноплощадность — то же требование, что и у карты спроса, TZ-USR-004).
 */
fun coarseGrid(cellKm: Double = 800.0, maxAbsLatDeg: Double = 82.0): List<GridPoint> {
    val dLat = cellKm / KM_PER_DEG
    return buildList {
        var lat = -maxAbsLatDeg + dLat / 2
        while (lat <= maxAbsLatDeg) {
            val dLon = dLat / max(cos(Math.toRadians(lat)), 0.1)
            val nLon = max(1, ceil(360.0 / dLon).toInt())
            for (k in 0 until nLon) {
                val lon = -180.0 + 360.0 * (k + 0.5) / nLon
                add(GridPoint("g%+05.1f%+06.1f".format(lat, lon), lat, lon))
            }
            lat += dLat
        }
    }
}

/**
 * Проверка применимости грубой сетки (ADR-013): ячейка должна быть меньше
 * диаметра зоны с запасом [safety]. Возвращает применимый размер ячейки:
 * при нарушении сетка измельчается до половины диаметра зоны.
 * [strict]=true вместо измельчения отклоняет расчёт.
 */
fun applicableCoarseCellKm(
    requestedKm: Double,
    altKm: Double,
    serviceElevDeg: Double,
    safety: Double = 1.0,
    strict: Boolean = false,
): Double {
    val diameterKm = 2 * footprintRadiusKm(altKm, serviceElevDeg)
    if (requestedKm * safety < diameterKm) return requestedKm
    val refined = diameterKm / 2
    if (strict) {
        throw ModelViolationException(
            "ADR-013 (TZ-BAL-002): coarse cell %.0f km is not smaller than service zone diameter %.0f km at %.0f km / %.0f° — passes would be lost; refine the grid (e.g. to %.0f km) or lower the service elevation"
                .format(requestedKm, diameterKm, altKm, serviceElevDeg, refined)
        )
    }
    return refined
}
