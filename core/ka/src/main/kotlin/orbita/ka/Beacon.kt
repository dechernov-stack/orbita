// Маяк эфемерид (TZ-KA-006, Р5/ADR-005). Эталон spec/spacecraft_semantics.py.
// Маяк грузит нисходящую линию везде, где есть терминалы, включая ячейки без
// спроса на данные, и его потребление ОБЯЗАТЕЛЬНО входит в циклограмму
// (ловушка 5): забыть его — занизить потребление на заметную величину.
package orbita.ka

import kotlin.math.max

enum class BeaconFormat { PassSchedule, FullAlmanac, OrbitModel }

/** Доля занятости нисходящей линии маяком. */
fun beaconDownlinkLoad(
    periodS: Double,
    payloadBytes: Int,
    overheadBytes: Int,
    bitrateBps: Double,
): Double {
    val bits = (payloadBytes + overheadBytes) * 8.0
    return (bits / bitrateBps) / periodS
}

/** Энергия маяка за виток, Вт·ч — слагаемое циклограммы, не опция. */
fun beaconEnergyWh(
    periodS: Double,
    payloadBytes: Int,
    overheadBytes: Int,
    bitrateBps: Double,
    txPowerW: Double,
    orbitS: Double,
): Double = txPowerW * beaconDownlinkLoad(periodS, payloadBytes, overheadBytes, bitrateBps) * orbitS / 3600.0

/**
 * Согласованность периода маяка с допустимым возрастом альманаха профиля
 * терминала: терминал обновляет альманах не реже допустимого (TZ-KA-006).
 */
fun almanacOk(beaconPeriodS: Double, maxAlmanacAgeS: Double, passesPerDay: Double): Boolean {
    if (passesPerDay <= 0) return false
    val interval = 86400.0 / passesPerDay
    return max(interval, beaconPeriodS) <= maxAlmanacAgeS
}

/** Несогласованность маяка и профилей терминалов выявляется отчётом (TZ-KA-006). */
data class BeaconMismatch(val terminalProfileRef: String, val maxAlmanacAgeS: Double, val passesPerDay: Double)

fun beaconMismatches(
    beaconPeriodS: Double,
    profiles: List<Triple<String, Double, Double>>,   // (ref, maxAlmanacAgeS, passesPerDay)
): List<BeaconMismatch> = profiles
    .filterNot { (_, age, passes) -> almanacOk(beaconPeriodS, age, passes) }
    .map { (ref, age, passes) -> BeaconMismatch(ref, age, passes) }

/** Типовой размер полезной нагрузки маяка по формату, байт. */
fun beaconPayloadBytes(format: BeaconFormat): Int = when (format) {
    BeaconFormat.OrbitModel -> 24      // компактная динамическая модель: терминал считает пролёты сам
    BeaconFormat.PassSchedule -> 40    // расписание ближайших пролётов
    BeaconFormat.FullAlmanac -> 120    // полный альманах группировки
}
