// Наземный сегмент: рекомендательное размещение станций (шаг 12.1,
// Концепция 5.4). Поведение — эталон spec/ground_segment_semantics.py,
// один в один.
//
// Проверяются СВОЙСТВА, обязательные независимо от алгоритма подбора:
// добавление станции не ухудшает покрытие; станция рядом с существующей
// добавляет мало; совпадающая — ничего; жадный выбор берёт наибольший
// прирост; РУЧНОЕ РАЗМЕЩЕНИЕ НЕ ПЕРЕПИСЫВАЕТСЯ ПРЕДЛОЖЕНИЯМИ, предложенное
// помечается происхождением.
//
// Модель видимости упрощённая — полоса широт с сгущением трасс у широты
// наклонения. Этого достаточно для СРАВНЕНИЯ площадок между собой; абсолютные
// значения покрытия здесь не утверждаются и в отчёты не идут.
package orbita.bal

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val EARTH_R_KM = 6378.137

/** Площадка станции: координаты и происхождение размещения. */
data class StationSite(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    /** `manual` — размещена инженером; `suggested` — предложена подбором. */
    val placement: String = "manual",
)

/**
 * Доля витков, на которых станция видит аппарат: станция видна, если её
 * широта попадает в полосу трассы плюс радиус зоны. Ближе к краю полосы —
 * чаще пролёты (сгущение трасс у широты наклонения).
 */
fun visibleFraction(
    stationLat: Double,
    inclinationDeg: Double,
    altKm: Double,
    minElevDeg: Double = 5.0,
): Double {
    val lam = Math.toDegrees(
        acos(EARTH_R_KM / (EARTH_R_KM + altKm) * cos(Math.toRadians(minElevDeg))),
    ) - minElevDeg
    val edge = abs(if (inclinationDeg <= 90) inclinationDeg else 180 - inclinationDeg)
    val reach = edge + lam
    if (abs(stationLat) > reach) return 0.0
    val closeness = 1.0 - abs(abs(stationLat) - edge) / max(reach, 1e-9)
    return max(0.05, min(1.0, 0.3 + 0.7 * closeness))
}

/** Перекрытие зон двух станций: 1 — совпадают, 0 — не пересекаются. */
fun stationOverlap(a: StationSite, b: StationSite, altKm: Double): Double {
    val d = arcKm(a, b)
    val lam = Math.toDegrees(
        acos(EARTH_R_KM / (EARTH_R_KM + altKm) * cos(Math.toRadians(5.0))),
    ) - 5.0
    val reachKm = EARTH_R_KM * Math.toRadians(lam) * 2
    return if (reachKm > 0) max(0.0, 1.0 - d / reachKm) else 0.0
}

private fun arcKm(a: StationSite, b: StationSite): Double {
    val p1 = Math.toRadians(a.lat)
    val p2 = Math.toRadians(b.lat)
    val dl = Math.toRadians(b.lon - a.lon)
    val c = sin(p1) * sin(p2) + cos(p1) * cos(p2) * cos(dl)
    return EARTH_R_KM * acos(max(-1.0, min(1.0, c)))
}

/** Прирост покрытия от станции с учётом перекрытия с уже размещёнными. */
fun stationGain(
    existing: List<StationSite>,
    candidate: StationSite,
    inclinationDeg: Double,
    altKm: Double,
    minElevDeg: Double = 5.0,
): Double {
    val base = visibleFraction(candidate.lat, inclinationDeg, altKm, minElevDeg)
    if (existing.isEmpty()) return base
    val overlap = existing.maxOf { stationOverlap(candidate, it, altKm) }
    return base * (1.0 - overlap)
}

/** Суммарное покрытие набора станций с учётом перекрытий. */
fun coverage(
    stations: List<StationSite>,
    inclinationDeg: Double,
    altKm: Double,
    minElevDeg: Double = 5.0,
): Double {
    var total = 0.0
    val placed = mutableListOf<StationSite>()
    for (s in stations) {
        total += stationGain(placed, s, inclinationDeg, altKm, minElevDeg)
        placed += s
    }
    return min(1.0, total)
}

/** Итог подбора: предложенные площадки и весь набор после подбора. */
data class SuggestResult(val suggested: List<StationSite>, val placed: List<StationSite>)

/**
 * Жадный подбор: на каждом шаге — станция с наибольшим приростом.
 *
 * РУЧНЫЕ СТАНЦИИ НЕ ПЕРЕПИСЫВАЮТСЯ: они входят в набор как есть, повторно
 * не предлагаются, и подбор строится поверх них. Предложенные помечаются
 * `placement=suggested` — на карте и в модели их происхождение различимо.
 */
fun suggestStations(
    candidates: List<StationSite>,
    inclinationDeg: Double,
    altKm: Double,
    k: Int,
    existing: List<StationSite> = emptyList(),
): SuggestResult {
    val placed = existing.toMutableList()
    val manual = existing.map { it.lat to it.lon }.toSet()
    val pool = candidates.filter { (it.lat to it.lon) !in manual }.toMutableList()
    val out = mutableListOf<StationSite>()
    repeat(k) {
        if (pool.isEmpty()) return@repeat
        val best = pool.maxByOrNull { stationGain(placed, it, inclinationDeg, altKm) } ?: return@repeat
        if (stationGain(placed, best, inclinationDeg, altKm) <= 1e-9) return@repeat
        val chosen = best.copy(placement = "suggested")
        placed += chosen
        out += chosen
        pool -= best
    }
    return SuggestResult(out, placed)
}

/** Среднее время до сброса: без станций — бесконечность, а не ноль. */
fun meanTimeToDownlinkS(
    stations: List<StationSite>,
    inclinationDeg: Double,
    altKm: Double,
    orbitS: Double = 5736.0,
): Double {
    val cov = coverage(stations, inclinationDeg, altKm)
    return if (cov > 0) orbitS / max(cov, 1e-6) else Double.POSITIVE_INFINITY
}
