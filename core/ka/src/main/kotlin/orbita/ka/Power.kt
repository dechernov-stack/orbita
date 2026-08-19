// Энергетическая модель аппарата (TZ-KA-004): СБ, АБ, циклограмма режимов.
// Стыкуется с энергетикой витка баллистики (TZ-BAL-004). Потребление маяка
// эфемерид входит в циклограмму обязательно (TZ-KA-006, ловушка 5).
package orbita.ka

import orbita.bal.allowedDutyCycle
import orbita.bal.eclipseFraction
import orbita.bal.orbitEnergyWh
import orbita.bal.orbitalPeriodS
import orbita.mod.model.Provenance
import orbita.mod.model.Quantity

data class SolarArray(
    val areaM2: Double,
    val efficiency: Double,
    val degradationPctPerYear: Double = 2.0,
    val mounting: String = "body_fixed",
)

data class Battery(val capacityWh: Double, val maxDod: Double = 0.3)

/** Режим циклограммы: доля витка и потребление. */
data class ModeSlot(val name: String, val fraction: Double, val powerW: Double)

data class PowerModel(
    val sa: SolarArray,
    val battery: Battery,
    val busPowerW: Double,
    val payloadPowerW: Double,
    val modes: List<ModeSlot> = emptyList(),
) {
    /** Генерация за виток с учётом деградации СБ к концу срока службы. */
    fun generatedWh(altKm: Double, betaDeg: Double, yearsInOrbit: Double = 0.0): Double {
        val degradation = Math.pow(1.0 - sa.degradationPctPerYear / 100.0, yearsInOrbit)
        return orbitEnergyWh(altKm, betaDeg, sa.areaM2, sa.efficiency * degradation)
    }

    /**
     * Потребление за виток: шина + циклограмма режимов + маяк.
     * [beaconWh] — обязательное слагаемое, а не опция (TZ-KA-006).
     */
    fun consumedWh(altKm: Double, beaconWh: Double, payloadDuty: Double = 0.0): Double {
        val tH = orbitalPeriodS(altKm) / 3600.0
        val modesWh = modes.sumOf { it.powerW * it.fraction * tH }
        return busPowerW * tH + modesWh + payloadPowerW * payloadDuty * tH + beaconWh
    }

    /**
     * Допустимая скважность ПН — ВЫЧИСЛЯЕТСЯ (ловушка 4), с учётом маяка:
     * энергия маяка вычитается из располагаемой до расчёта скважности.
     */
    fun allowedPayloadDutyCycle(altKm: Double, betaDeg: Double, beaconWh: Double, yearsInOrbit: Double = 0.0): Quantity {
        val tH = orbitalPeriodS(altKm) / 3600.0
        val modesWh = modes.sumOf { it.powerW * it.fraction * tH }
        val available = generatedWh(altKm, betaDeg, yearsInOrbit) - beaconWh - modesWh
        return Quantity(
            value = allowedDutyCycle(available, busPowerW, payloadPowerW, altKm),
            unit = "1",
            provenance = Provenance.Computed(module = "spacecraft", moduleVersion = KA_MODULE_VERSION),
        )
    }

    /** Глубина разряда АБ на теневом участке худшего витка. */
    fun batteryDod(altKm: Double, betaDeg: Double, loadW: Double): Double {
        val eclipseWh = loadW * orbitalPeriodS(altKm) * eclipseFraction(altKm, betaDeg) / 3600.0
        return eclipseWh / battery.capacityWh
    }

    /** Баланс худшего витка: отрицательный помечает конфигурацию несостоятельной. */
    fun worstOrbitBalanceWh(altKm: Double, worstBetaDeg: Double, beaconWh: Double, payloadDuty: Double): Double =
        generatedWh(altKm, worstBetaDeg) - consumedWh(altKm, beaconWh, payloadDuty)
}
