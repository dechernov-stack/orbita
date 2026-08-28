// Справочник единиц (решение ранга ADR): границы переводят. Пачка (импорт,
// акцепт предложений, канал пакетов Б-01) — граница: несистемная единица →
// конверсия в канон с записью происхождения («переведено из 30 min»);
// неизвестная → отказ с предложением открыть справочник и добавить; тихих
// строк мимо словаря нет. Правки единиц — правкой справочника, не кода.
package orbita.com.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/** Неизвестная единица: отказ границы с именем и адресом починки. */
class UnknownUnitException(val unit: String) : RuntimeException(
    "единица '$unit' вне справочника единиц — откройте полку «Справочник единиц» " +
        "(область LIB) и добавьте запись; тихих строк мимо словаря нет",
)

/** Курсовая единица без курса: конверсия обязана фиксировать курс и дату. */
class RateUnitException(val unit: String) : RuntimeException(
    "единица '$unit' — курсовая: конверсия фиксирует курс и дату; " +
        "вносите значение в каноне (справочник единиц) либо добавьте курс записью справочника",
)

/** Индекс справочника: канон по единице, коэффициенты входов. */
class UnitRegistryIndex(doc: JsonNode) {

    private data class Input(val canon: String, val factor: Double?, val shift: Double?, val rate: Boolean)

    private val canons = mutableSetOf<String>()
    private val inputs = mutableMapOf<String, Input>()

    init {
        doc.path("dimensions").forEach { d ->
            val canon = d.path("canon").asText()
            val conversion = d.path("conversion").asText("linear")
            canons += canon
            d.path("inputs").forEach { i ->
                val unit = i.path("unit").asText()
                val factor = i.path("factor").takeIf { it.isNumber }?.asDouble()
                inputs[unit] = Input(
                    canon = canon,
                    factor = factor,
                    shift = i.path("shift").takeIf { it.isNumber }?.asDouble(),
                    // курсовая: linear-размерность, вход без коэффициента
                    rate = conversion == "rate" || (conversion == "linear" && factor == null),
                )
            }
        }
    }

    val empty: Boolean get() = canons.isEmpty()

    fun known(unit: String): Boolean = unit in canons || unit in inputs

    /**
     * Единица значения → канон. null — уже канон либо конверсии нет по
     * определению (log/none: dBm, U — известные единицы без пересчёта).
     */
    fun toCanon(value: Double, unit: String): Pair<Double, String>? {
        if (unit in canons) return null
        val i = inputs[unit] ?: throw UnknownUnitException(unit)
        if (i.rate) throw RateUnitException(unit)
        if (i.factor == null) return null // log/none: известна, не переводится
        return value * i.factor + (i.shift ?: 0.0) to i.canon
    }
}

object UnitBoundary {

    /** Справочник — один на систему, полка LIB; до сида — границы молчат. */
    fun registryOf(boundary: Boundary): UnitRegistryIndex? =
        boundary.objects.listCurrent(orbita.mod.store.ObjectStore.LIBRARY_PROJECT)
            .firstOrNull { it.type == "unit_registry" && it.status.name != "Cancelled" }
            ?.let { UnitRegistryIndex(it.doc) }

    /**
     * Рекурсивная нормализация величин документа: узел {value, unit[, …]} —
     * величина; несистемная единица приводится к канону, происхождение
     * дополняется записью «переведено из …».
     */
    fun normalize(doc: JsonNode, registry: UnitRegistryIndex) {
        when {
            doc.isObject -> {
                val o = doc as ObjectNode
                val unitNode = o.path("unit")
                val valueNode = o.path("value")
                if (unitNode.isTextual && valueNode.isNumber) {
                    val unit = unitNode.asText()
                    val converted = registry.toCanon(valueNode.asDouble(), unit)
                    if (converted != null) {
                        val (canonValue, canonUnit) = converted
                        o.put("value", canonValue)
                        o.put("unit", canonUnit)
                        val prov = if (o.path("provenance").isObject) {
                            o.path("provenance") as ObjectNode
                        } else o.putObject("provenance").put("source", "manual")
                        prov.put("converted_from", "${valueNode.asDouble().toBigDecimal().stripTrailingZeros().toPlainString()} $unit")
                    }
                }
                o.properties().forEach { (_, v) -> normalize(v, registry) }
            }
            doc.isArray -> doc.forEach { normalize(it, registry) }
        }
    }
}
