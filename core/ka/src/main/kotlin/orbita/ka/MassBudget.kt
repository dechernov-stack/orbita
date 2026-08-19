// Массовый бюджет с резервами по зрелости (TZ-KA-003).
// Эталон spec/spacecraft_semantics.py, один в один. Сухая и заправленная масса
// ВЫЧИСЛЯЮТСЯ и помечаются provenance=computed; превышение верхней границы Р2
// с учётом резервов отклоняет конфигурацию (ADR-002).
package orbita.ka

import orbita.mod.model.Provenance
import orbita.mod.model.Quantity
import orbita.mod.store.ModelViolationException

const val KA_MODULE_VERSION = "0.1"

enum class Maturity(val marginPct: Double) {
    New(25.0), Modified(15.0), Existing(5.0);

    companion object {
        fun of(name: String): Maturity = when (name) {
            "new" -> New; "modified" -> Modified; "existing" -> Existing
            else -> throw IllegalArgumentException("unknown maturity '$name'")
        }
    }
}

data class MassItem(val name: String, val massKg: Double, val maturity: Maturity)

/** Диапазон платформ Р2/ADR-002: 12U … 100 кг. */
const val PLATFORM_MIN_KG = 12.0
const val PLATFORM_MAX_KG = 100.0

fun withinPlatformRange(massKg: Double): Boolean = massKg in PLATFORM_MIN_KG..PLATFORM_MAX_KG

/** Сухая масса: резерв по зрелости на каждый элемент плюс системный резерв. */
fun dryMassKg(items: List<MassItem>, systemMarginPct: Double = 10.0): Double {
    val base = items.sumOf { it.massKg * (1 + it.maturity.marginPct / 100.0) }
    return base * (1 + systemMarginPct / 100.0)
}

fun dryMassQuantity(items: List<MassItem>, systemMarginPct: Double = 10.0): Quantity =
    Quantity(
        value = dryMassKg(items, systemMarginPct), unit = "kg",
        provenance = Provenance.Computed(module = "spacecraft", moduleVersion = KA_MODULE_VERSION),
    )

/**
 * Заправленная масса: сухая плюс топливо на потребный ΔV (Циолковский).
 * ΔV увода приходит из баллистики (TZ-BAL-009) — здесь не вычисляется.
 */
fun wetMassKg(dryKg: Double, deltaVMs: Double, ispS: Double = 220.0, g0: Double = 9.80665): Double {
    if (deltaVMs <= 0) return dryKg
    return dryKg * Math.exp(deltaVMs / (ispS * g0))
}

/** Проверка конфигурации по Р2 с учётом резервов; нарушение отклоняет конфигурацию. */
fun requireWithinPlatformRange(massKg: Double, what: String = "dry mass") {
    if (!withinPlatformRange(massKg)) {
        throw ModelViolationException(
            "TZ-KA-002 (Р2/ADR-002): $what %.2f kg is outside the 12–100 kg platform range".format(massKg)
        )
    }
}
