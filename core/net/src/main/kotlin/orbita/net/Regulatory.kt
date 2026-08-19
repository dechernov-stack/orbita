// Регуляторные ограничения (TZ-NET-005): предел мощности, duty cycle, частотный
// план по региону. Применяются и к терминалам, и к нисходящим передачам КА.
// Регион терминала неизменен на протяжении трека (Р7/ADR-007).
package orbita.net

data class RegionRules(
    val region: String,
    val freqPlanHz: ClosedRange<Double>,
    val maxEirpDbm: Double,
    val dutyCycleLimit: Double?,   // null — регион ограничивает dwell time, а не скважность
    val dwellTimeLimitS: Double?,
)

/** Типовые правила регионов; значения проектные, уточняются по актуальным регламентам. */
object Regions {
    val EU868 = RegionRules("EU868", 863.0e6..870.0e6, maxEirpDbm = 16.0, dutyCycleLimit = 0.01, dwellTimeLimitS = null)
    val RU864 = RegionRules("RU864", 864.0e6..870.0e6, maxEirpDbm = 16.0, dutyCycleLimit = 0.01, dwellTimeLimitS = null)
    val US915 = RegionRules("US915", 902.0e6..928.0e6, maxEirpDbm = 30.0, dutyCycleLimit = null, dwellTimeLimitS = 0.4)
    val AS923 = RegionRules("AS923", 915.0e6..928.0e6, maxEirpDbm = 16.0, dutyCycleLimit = 0.01, dwellTimeLimitS = null)

    fun byName(name: String): RegionRules = when (name) {
        "EU868" -> EU868; "RU864" -> RU864; "US915" -> US915; "AS923" -> AS923
        else -> throw IllegalArgumentException("unknown regulatory region '$name'")
    }
}

/** Суммарная занятость эфира популяцией — доля суток (эталон spacecraft_semantics). */
fun populationDutyCycle(terminals: Int, msgsPerDay: Double, timeOnAirS: Double): Double =
    terminals * msgsPerDay * timeOnAirS / 86400.0

/** Нарушения регуляторных ограничений популяцией; выявляются при сборке сценария. */
fun validatePopulation(
    region: RegionRules,
    terminals: Int,
    msgsPerDay: Double,
    timeOnAirS: Double,
    eirpDbm: Double,
): List<String> = buildList {
    region.dutyCycleLimit?.let { limit ->
        val duty = populationDutyCycle(terminals, msgsPerDay, timeOnAirS)
        if (duty > limit) {
            add("TZ-NET-005: population duty cycle %.4f exceeds %s limit %.4f".format(duty, region.region, limit))
        }
    }
    region.dwellTimeLimitS?.let { limit ->
        if (timeOnAirS > limit) {
            add("TZ-NET-005: time on air %.2fs exceeds %s dwell limit %.2fs".format(timeOnAirS, region.region, limit))
        }
    }
    if (eirpDbm > region.maxEirpDbm) {
        add("TZ-NET-005: EIRP %.1f dBm exceeds %s limit %.1f dBm".format(eirpDbm, region.region, region.maxEirpDbm))
    }
}
