// Бюджет радиолинии и зоны обслуживания (TZ-KA-007, TZ-KA-005).
// Эталон spec/spacecraft_semantics.py, один в один.
//
// Требуемое Eb/N0 и оверхед приходят ТОЛЬКО из адаптера протокола (core/net):
// в этом файле нет ни одной константы, специфичной для LoRaWAN (ловушка 3).
// Бюджеты участков «терминал→КА» и «КА→НС» считаются РАЗДЕЛЬНО; сквозной
// бюджет не вычисляется вовсе — следствие regenerative (Р1/ADR-001).
package orbita.ka

import orbita.bal.RE_KM
import orbita.bal.slantRangeKm
import kotlin.math.abs
import kotlin.math.log10

const val C_LIGHT_MS = 299_792_458.0
const val K_BOLTZ_DBW = -228.6          // дБВт/К/Гц

fun fsplDb(rangeKm: Double, freqHz: Double): Double =
    20 * log10(4 * Math.PI * rangeKm * 1000 * freqHz / C_LIGHT_MS)

/** Участок радиолинии: одно направление одного плеча (Р1: сквозной бюджет не считается). */
data class LinkLeg(
    val id: String,
    val eirpDbw: Double,
    val altKm: Double,
    val freqHz: Double,
    val gOverTDbk: Double,
    val bitrateBps: Double,
    /** Из адаптера протокола (core/net), не задаётся вручную. */
    val requiredEbn0Db: Double,
    val extraLossesDb: Double = 2.0,
)

/** Запас линии на заданном угле места, дБ. */
fun linkMarginDb(leg: LinkLeg, elevDeg: Double): Double {
    val d = slantRangeKm(leg.altKm, elevDeg)
    val cn0 = leg.eirpDbw - fsplDb(d, leg.freqHz) - leg.extraLossesDb + leg.gOverTDbk - K_BOLTZ_DBW
    val ebn0 = cn0 - 10 * log10(leg.bitrateBps)
    return ebn0 - leg.requiredEbn0Db
}

/** Полный бюджет участка на угле места — для документа service-zone. */
data class LinkBudgetBreakdown(
    val eirpDbw: Double,
    val fsplDb: Double,
    val extraLossesDb: Double,
    val cOverN0DbHz: Double,
    val requiredEbn0Db: Double,
    val marginDb: Double,
)

fun linkBudget(leg: LinkLeg, elevDeg: Double): LinkBudgetBreakdown {
    val d = slantRangeKm(leg.altKm, elevDeg)
    val fspl = fsplDb(d, leg.freqHz)
    val cn0 = leg.eirpDbw - fspl - leg.extraLossesDb + leg.gOverTDbk - K_BOLTZ_DBW
    return LinkBudgetBreakdown(
        eirpDbw = leg.eirpDbw, fsplDb = fspl, extraLossesDb = leg.extraLossesDb,
        cOverN0DbHz = cn0, requiredEbn0Db = leg.requiredEbn0Db,
        marginDb = cn0 - 10 * log10(leg.bitrateBps) - leg.requiredEbn0Db,
    )
}

/**
 * Угол места, при котором запас равен требуемому, — граница ЗОНЫ ОБСЛУЖИВАНИЯ.
 * null — линия не замыкается нигде, зоны нет вовсе.
 */
fun serviceElevationDeg(leg: LinkLeg, requiredMarginDb: Double, minElevDeg: Double = 5.0): Double? {
    var lo = minElevDeg
    var hi = 90.0
    if (linkMarginDb(leg, hi) < requiredMarginDb) return null
    if (linkMarginDb(leg, lo) >= requiredMarginDb) return minElevDeg   // ограничивает геометрия
    repeat(60) {
        val mid = (lo + hi) / 2
        if (linkMarginDb(leg, mid) < requiredMarginDb) lo = mid else hi = mid
    }
    return hi
}

/** Ограничивающий фактор зоны обязателен (TZ-KA-005): сужение footprint имеет причину. */
fun limitingFactor(serviceElevDeg: Double, minElevDeg: Double): String =
    if (abs(serviceElevDeg - minElevDeg) < 1e-6) "geometry" else "link_margin"

/**
 * Доплеровский сдвиг и скорость его изменения для сравнения с полосой захвата
 * приёмника (TZ-KA-007). Оценка по круговой орбите: максимум сдвига — на входе
 * в зону (радиальная скорость максимальна), максимум скорости изменения — в надире.
 */
data class Doppler(val maxShiftHz: Double, val maxRateHzS: Double, val withinCapture: Boolean)

fun doppler(altKm: Double, freqHz: Double, minElevDeg: Double, toleranceHz: Double): Doppler {
    val r = RE_KM + altKm
    val vOrbKmS = Math.sqrt(orbita.bal.MU_KM3_S2 / r)
    val vGroundKmS = vOrbKmS * (RE_KM / r)                      // скорость подспутниковой точки
    val cosMax = Math.cos(Math.toRadians(minElevDeg))           // ракурс у края зоны
    val maxShift = freqHz * (vGroundKmS * 1000.0 * cosMax) / C_LIGHT_MS
    // производная сдвига в надире: f0·v²/(c·h)
    val maxRate = freqHz * (vGroundKmS * 1000.0) * (vGroundKmS * 1000.0) / (C_LIGHT_MS * altKm * 1000.0)
    return Doppler(maxShift, maxRate, withinCapture = maxShift <= toleranceHz)
}
