// Энергетика витка (TZ-BAL-004): тень, собранная энергия, допустимая скважность
// ПН. Эталон spec/ballistics_semantics.py, один в один. Скважность — величина
// ВЫЧИСЛЯЕМАЯ (ловушка 4): если она пришла входным параметром, баланс потерял смысл.
package orbita.bal

import orbita.mod.model.Provenance
import orbita.mod.model.Quantity
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Доля витка в тени (цилиндрическая модель); beta — угол Солнца к плоскости орбиты. */
fun eclipseFraction(altKm: Double, betaDeg: Double): Double {
    val r = RE_KM + altKm
    val b = Math.toRadians(abs(betaDeg))
    val denom = r * cos(b)
    val num = sqrt(r * r - RE_KM * RE_KM)
    if (num >= denom) return 0.0          // орбита не входит в тень
    return acos(num / denom) / Math.PI
}

/** Собранная за виток энергия, Вт·ч. */
fun orbitEnergyWh(
    altKm: Double,
    betaDeg: Double,
    saAreaM2: Double,
    saEff: Double,
    cosLoss: Double = 0.85,
    solarWm2: Double = 1361.0,
): Double {
    val t = orbitalPeriodS(altKm)
    val sunlit = 1.0 - eclipseFraction(altKm, betaDeg)
    val p = solarWm2 * saAreaM2 * saEff * cosLoss
    return p * t * sunlit / 3600.0
}

/** Допустимая скважность ПН — производная величина, а не задаваемая. */
fun allowedDutyCycle(genWh: Double, busW: Double, payloadW: Double, altKm: Double): Double {
    val tH = orbitalPeriodS(altKm) / 3600.0
    val spare = genWh - busW * tH
    if (spare <= 0 || payloadW <= 0) return 0.0
    return min(1.0, spare / (payloadW * tH))
}

/** Та же величина с обязательным происхождением «computed» (TZ-COM-005, TZ-BAL-004). */
fun allowedDutyCycleQuantity(genWh: Double, busW: Double, payloadW: Double, altKm: Double): Quantity =
    Quantity(
        value = allowedDutyCycle(genWh, busW, payloadW, altKm),
        unit = "1",
        provenance = Provenance.Computed(module = "ballistics", moduleVersion = BAL_MODULE_VERSION),
    )

/**
 * Диапазон углов beta за год (худший/лучший сезон, северное полушарие).
 * Для ССО средний beta определяется LTAN: терминаторная орбита (LTAN 6/18)
 * держит beta около 90° и почти не входит в тень; сезонное качание ±23.44°
 * задаёт наклон эклиптики. Для прочих орбит ВДУ прецессирует, и beta за год
 * проходит через ноль — худший сезон принимается beta=0.
 */
fun seasonBetaBoundsDeg(incDeg: Double, ssoLtanH: Double? = null): Pair<Double, Double> {
    val obliquity = 23.44
    return if (ssoLtanH != null) {
        val meanBeta = abs(ssoLtanH - 12.0) * 15.0     // отклонение от полуденной плоскости
        val worst = max(0.0, meanBeta - obliquity)
        val best = min(90.0, meanBeta + obliquity)
        worst to best
    } else {
        0.0 to min(90.0, incDeg + obliquity)
    }
}

/** Отрицательный баланс худшего витка помечает конфигурацию несостоятельной. */
fun worstOrbitFeasible(genWorstWh: Double, consumptionWh: Double): Boolean =
    genWorstWh - consumptionWh > 0
