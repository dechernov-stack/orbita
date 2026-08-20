// Конфигуратор группировок (TZ-BAL-003): Walker Delta i:T/P/F разворачивается
// в перечень орбит; произвольный перечень тоже принимается; ССО без LTAN
// отклоняется. Эталон spec/ballistics_semantics.py, один в один.
package orbita.bal

import orbita.mod.store.ModelViolationException

/** Версия расчётного модуля — входит в input_versions результатов (TZ-COM-006). */
const val BAL_MODULE_VERSION = "0.2"

data class OrbitSlot(
    val plane: Int,
    val raanDeg: Double,
    val maDeg: Double,
    val incDeg: Double,
    val altKm: Double,
    val satId: String = "SAT-p$plane-${"%.1f".format(maDeg)}",
)

/** Walker Delta i:T/P/F → перечень орбит (RAAN, средняя аномалия). */
fun walkerDelta(incDeg: Double, t: Int, p: Int, f: Int, altKm: Double): List<OrbitSlot> {
    if (t % p != 0) throw ModelViolationException("TZ-BAL-003: T=$t не делится на P=$p")
    val s = t / p
    return buildList {
        for (plane in 0 until p) {
            val raan = 360.0 * plane / p
            for (slot in 0 until s) {
                val ma = (360.0 * slot / s + 360.0 * f * plane / t).mod(360.0)
                add(OrbitSlot(plane, raan, ma, incDeg, altKm))
            }
        }
    }
}

/** Прокси-экономика: число уникальных пар «наклонение × высота» = число кампаний. */
fun launchCampaigns(sats: List<OrbitSlot>): Int =
    sats.map { "%.3f".format(it.incDeg) to "%.3f".format(it.altKm) }.toSet().size

/**
 * Прокси срока развёртывания, сут: месяц на пусковую кампанию плюс разведение
 * плоскостей прецессией (дёшево, но медленно) — полмесяца на каждую плоскость
 * сверх первой. Прокси задокументирован; уточняется на шаге экономики.
 */
fun deploymentTimeDaysProxy(campaigns: Int, planes: Int): Double =
    30.0 * campaigns + 15.0 * (planes - 1)

/** Конфигурация группировки; ССО без LTAN отклоняется (TZ-BAL-003). */
data class ConstellationConfig(
    val incDeg: Double,
    val total: Int,
    val planes: Int,
    val phasing: Int,
    val altKm: Double,
    val sso: Boolean = false,
    val ltanH: Double? = null,
) {
    init {
        if (sso && ltanH == null) {
            throw ModelViolationException(
                "TZ-BAL-003: SSO configuration requires LTAN — eclipse pattern depends on it"
            )
        }
    }

    /** Для ССО наклонение определяется высотой, а не задаётся вручную. */
    fun effectiveIncDeg(): Double = if (sso) ssoInclinationDeg(altKm) else incDeg

    fun expand(): List<OrbitSlot> = walkerDelta(effectiveIncDeg(), total, planes, phasing, altKm)
}
