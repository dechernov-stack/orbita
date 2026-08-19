// Время баллистического существования и увод (TZ-BAL-009).
// Эталон spec/ballistics_semantics.py, один в один: грубая оценка по
// экспоненциальной атмосфере — достаточно для отбраковки вариантов на Phase A.
package orbita.bal

import kotlin.math.exp
import kotlin.math.sqrt

/** Оценка времени существования, лет; бесконечность — торможение пренебрежимо. */
fun decayYears(altKm: Double, massKg: Double, areaM2: Double, cd: Double = 2.2): Double {
    val hScaleKm = 60.0
    val rho0 = 1.0e-12          // кг/м³ на опорной высоте
    val h0Km = 400.0
    val rho = rho0 * exp(-(altKm - h0Km) / hScaleKm)
    val aM = (RE_KM + altKm) * 1000.0
    val bc = massKg / (cd * areaM2)                    // баллистический коэффициент
    val daPerRevM = -2 * Math.PI * rho * aM * aM / bc  // м за виток
    if (daPerRevM >= 0) return Double.POSITIVE_INFINITY
    val revs = (altKm - 100.0) * 1000.0 / -daPerRevM
    return revs * orbitalPeriodS(altKm) / YEAR_S
}

/** Соответствие нормам увода (25 лет, NASA-STD-8719.14 и национальные аналоги). */
fun deorbitCompliant(altKm: Double, massKg: Double, areaM2: Double, limitYears: Double = 25.0): Boolean =
    decayYears(altKm, massKg, areaM2) <= limitYears

/**
 * Потребный ΔV увода, м/с: импульс в апогее для снижения перигея до высоты
 * гарантированного входа (vis-viva, переход Хомана половинного эллипса).
 * Передаётся в массовый бюджет аппарата (TZ-KA-003).
 */
fun deorbitDeltaVMs(altKm: Double, targetPerigeeKm: Double = 100.0): Double {
    if (altKm <= targetPerigeeKm) return 0.0
    val r1 = (RE_KM + altKm)
    val rp = (RE_KM + targetPerigeeKm)
    val vCirc = sqrt(MU_KM3_S2 / r1)
    val vTransferApo = sqrt(MU_KM3_S2 * (2.0 / r1 - 2.0 / (r1 + rp)))
    return (vCirc - vTransferApo) * 1000.0
}
