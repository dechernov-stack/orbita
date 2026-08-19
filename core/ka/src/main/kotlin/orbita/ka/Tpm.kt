// Реестр отслеживаемых технических параметров (TZ-KA-010): масса, энергобаланс
// худшего витка, запас худшей линии, ёмкость абонентской линии, объём буфера, ΔV.
// Обновляются автоматически при изменении модели; тренд сохраняется с привязкой
// к обзорам; выход за требуемый резерв помечается.
package orbita.ka

import orbita.mod.model.Provenance
import orbita.mod.model.Quantity

data class TpmTrendPoint(val at: String, val review: String?, val value: Double)

data class Tpm(
    val name: String,
    val current: Quantity,
    val target: Quantity,
    val requiredMarginPct: Double,
    /** true — «не выше цели» (масса), false — «не ниже цели» (запас линии, баланс). */
    val lowerIsBetter: Boolean,
    val trend: List<TpmTrendPoint> = emptyList(),
) {
    /** Фактический резерв к цели, %. */
    val marginPct: Double
        get() = if (lowerIsBetter) (target.value - current.value) / target.value * 100.0
        else (current.value - target.value) / Math.abs(target.value) * 100.0

    /** Выход за требуемый резерв помечается (TZ-KA-010). */
    val breached: Boolean get() = marginPct < requiredMarginPct
}

/** Реестр TPM аппарата: пересобирается из модели, вручную не заполняется. */
class TpmRegistry {

    private val items = linkedMapOf<String, Tpm>()

    fun put(tpm: Tpm): TpmRegistry {
        // тренд накапливается: прежние точки сохраняются при обновлении значения
        val previous = items[tpm.name]
        items[tpm.name] = if (previous == null) tpm else tpm.copy(trend = previous.trend + tpm.trend)
        return this
    }

    fun get(name: String): Tpm? = items[name]

    fun all(): List<Tpm> = items.values.toList()

    fun breached(): List<Tpm> = items.values.filter { it.breached }

    companion object {
        fun computed(value: Double, unit: String): Quantity = Quantity(
            value, unit, Provenance.Computed(module = "spacecraft", moduleVersion = KA_MODULE_VERSION),
        )

        fun manual(value: Double, unit: String): Quantity =
            Quantity(value, unit, Provenance.Manual())
    }
}
