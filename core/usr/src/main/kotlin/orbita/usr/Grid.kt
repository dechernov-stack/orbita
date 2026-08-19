// Равноплощадность сетки (TZ-USR-004, ловушка проекции STEP-2 №1).
// Поведение — эталон spec/demand_semantics.py::cell_area_km2, один в один.
package orbita.usr

import kotlin.math.sin

const val R_EARTH_KM = 6371.0

/**
 * Площадь равноугольной ячейки dlat×dlon на широте [latDeg]: убывает как
 * косинус широты — на 60° вдвое меньше экваториальной. Вес ячейки считается
 * по площади, а не по числу ячеек.
 */
fun cellAreaKm2(latDeg: Double, dlatDeg: Double = 1.0, dlonDeg: Double = 1.0): Double {
    val lat = Math.toRadians(latDeg)
    val dlat = Math.toRadians(dlatDeg)
    val dlon = Math.toRadians(dlonDeg)
    return R_EARTH_KM * R_EARTH_KM * dlon * (sin(lat + dlat / 2) - sin(lat - dlat / 2))
}
