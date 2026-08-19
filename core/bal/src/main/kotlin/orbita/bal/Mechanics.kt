// Базовая механика и геометрия видимости (TZ-BAL-001, TZ-BAL-002).
// Замкнутые формулы — эталон spec/ballistics_semantics.py, один в один.
// Они НЕ заменяют Orekit (пропагация и события — только им, ADR-010), а служат
// внешней проверкой: реализация на Orekit обязана сходиться с ними в допусках.
package orbita.bal

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

const val MU_KM3_S2 = 398600.4418      // гравитационный параметр Земли
const val RE_KM = 6378.137             // экваториальный радиус
const val J2 = 1.08262668e-3
const val YEAR_S = 365.2422 * 86400    // тропический год, с

fun orbitalPeriodS(altKm: Double): Double {
    val a = RE_KM + altKm
    return 2 * Math.PI * sqrt(a * a * a / MU_KM3_S2)
}

fun meanMotionRadS(altKm: Double): Double {
    val a = RE_KM + altKm
    return sqrt(MU_KM3_S2 / (a * a * a))
}

/** Наклонение солнечно-синхронной орбиты: прецессия ВДУ равна 360°/год. */
fun ssoInclinationDeg(altKm: Double): Double {
    val a = RE_KM + altKm
    val raanRate = 2 * Math.PI / YEAR_S
    val n = meanMotionRadS(altKm)
    val cosI = -raanRate * a * a / (1.5 * J2 * RE_KM * RE_KM * n)
    return Math.toDegrees(acos(cosI))
}

/** Центральный угол зоны видимости при минимальном угле места. */
fun centralAngleDeg(altKm: Double, minElevDeg: Double): Double {
    val e = Math.toRadians(minElevDeg)
    val r = RE_KM + altKm
    return Math.toDegrees(acos(RE_KM / r * cos(e)) - e)
}

fun footprintRadiusKm(altKm: Double, minElevDeg: Double): Double =
    RE_KM * Math.toRadians(centralAngleDeg(altKm, minElevDeg))

/** Наклонная дальность до точки на поверхности под углом места elev. */
fun slantRangeKm(altKm: Double, elevDeg: Double): Double {
    val e = Math.toRadians(elevDeg)
    return -RE_KM * sin(e) + sqrt((RE_KM * sin(e)) * (RE_KM * sin(e)) + altKm * altKm + 2 * RE_KM * altKm)
}

/** Длительность пролёта через зенит — верхняя оценка длительности сеанса. */
fun maxPassDurationS(altKm: Double, minElevDeg: Double): Double {
    val lam = Math.toRadians(centralAngleDeg(altKm, minElevDeg))
    return orbitalPeriodS(altKm) * lam / Math.PI
}
