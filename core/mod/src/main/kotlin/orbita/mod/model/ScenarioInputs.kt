// Входы моделирования как хранимые объекты (CR-005, ADR-021).
//
// Поведение — эталон spec/scenario_inputs_semantics.py, один в один.
//
// ГЛАВНОЕ ПРАВИЛО: требуемый тип диктует ПОЛЕ сценария, а не префикс ссылки.
// Проверка «префикс соответствует какому-то типу» пропускает подстановку объекта
// другого вида в чужое поле: префикс и объект согласованы между собой, а поле —
// нет. Именно на этом сломалась первая версия эталонной проверки.
package orbita.mod.model

import com.fasterxml.jackson.databind.JsonNode
import kotlin.math.abs

/** Поля сценария, ведущие на хранимые входы, и требуемый тип каждого. */
val SCENARIO_REF_FIELDS: Map<String, CoreType> = linkedMapOf(
    "constellation_ref" to CoreType.Constellation,
    "carrier_ref" to CoreType.ComponentUsage,
    "demand_map_ref" to CoreType.DemandMap,
    "ground_stations_ref" to CoreType.GroundStations,
    "protocol_adapter_ref" to CoreType.ProtocolAdapter,
)

/** Тип по префиксу ссылки; null — префикс не соответствует ни одному типу. */
fun refType(ref: String): CoreType? =
    CoreType.byIdPrefix(ref.substringBefore('-')).takeIf { ObjectId.PATTERN.matches(ref) }

/**
 * Замечания к ссылкам сценария. Пустой список — все ссылки разрешаются
 * в хранимые объекты нужного типа.
 *
 * [lookup] возвращает тип хранимого объекта по идентификатору либо null,
 * если объекта в модели нет.
 */
fun resolveScenario(scenario: JsonNode, lookup: (String) -> CoreType?): List<String> = buildList {
    for ((field, want) in SCENARIO_REF_FIELDS) {
        val ref = scenario.path(field).asText("")
        if (ref.isBlank()) {
            add("$field: ссылка не задана")
            continue
        }
        val byPrefix = refType(ref)
        if (byPrefix == null) {
            add("$field: «$ref» не соответствует ни одному типу объекта")
            continue
        }
        if (byPrefix != want) {
            add("$field: ссылка $ref ведёт на ${byPrefix.dbType}, ожидался ${want.dbType}")
            continue
        }
        val stored = lookup(ref)
        if (stored == null) {
            add("$field: объект $ref в модели отсутствует")
        } else if (stored != want) {
            add("$field: $ref имеет тип ${stored.dbType}, ожидался ${want.dbType}")
        }
    }
}

/** Версии фиксируются для всех входов: иначе результат невоспроизводим (TZ-COM-006). */
fun inputVersionsComplete(scenario: JsonNode): List<String> {
    val versions = scenario.path("input_versions")
    return SCENARIO_REF_FIELDS.keys.mapNotNull { field ->
        val ref = scenario.path(field).asText("")
        if (ref.isNotBlank() && versions.path(ref).isMissingNode) {
            "версия входа не зафиксирована: $ref"
        } else {
            null
        }
    }
}

/**
 * Ключ воспроизводимости результата: ссылки ВМЕСТЕ С ВЕРСИЯМИ, зерно ГПСЧ
 * и версии модулей. Ссылок без версий недостаточно: тот же объект в другой
 * версии даёт другой результат.
 */
fun resultKey(scenario: JsonNode): String {
    val versions = scenario.path("input_versions")
    val parts = SCENARIO_REF_FIELDS.keys.map { field ->
        val ref = scenario.path(field).asText("")
        val version = versions.path(ref).asText("?").ifBlank { "?" }
        "$ref@$version"
    }.toMutableList()
    parts += "seed=${scenario.path("rng_seed").asText("")}"
    val modules = scenario.path("module_versions")
    modules.fieldNames().asSequence().sorted().forEach { name ->
        parts += "$name=${modules.path(name).asText("")}"
    }
    return parts.joinToString("|")
}

/** Изменение версии любого входа обесценивает результат. */
fun becomesStale(scenario: JsonNode, changedRef: String, newVersion: String): Boolean {
    val before = resultKey(scenario)
    val copy = scenario.deepCopy<JsonNode>()
    val versions = copy.path("input_versions") as com.fasterxml.jackson.databind.node.ObjectNode
    versions.put(changedRef, newVersion)
    return resultKey(copy) != before
}

// ---------- CR-006: ведомость масс ----------

/** Резерв по зрелости позиции ведомости (TZ-KA-002). */
val MATURITY_MARGIN: Map<String, Double> = mapOf(
    "new" to 0.25, "modified" to 0.15, "existing" to 0.05,
)

class MissingMelException(message: String) : IllegalArgumentException(message)

/**
 * Сухая масса по ведомости. ОТСУТСТВИЕ ВЕДОМОСТИ — ОШИБКА, А НЕ НОЛЬ:
 * молчаливый ноль выглядит как результат (CR-006).
 */
fun melDryMass(mel: List<JsonNode>, systemMargin: Double = 0.10): Double {
    if (mel.isEmpty()) {
        throw MissingMelException("ведомость масс не задана: политику резервов не к чему применить")
    }
    val base = mel.sumOf { item ->
        val maturity = item.path("maturity").asText("")
        val margin = MATURITY_MARGIN[maturity]
            ?: throw IllegalArgumentException("неизвестная зрелость «$maturity»")
        item.path("mass_kg").asDouble() * item.path("quantity").asInt(1) * (1 + margin)
    }
    return base * (1 + systemMargin)
}

/** Разбивка массы по подсистемам: кратность учитывается, резерв — нет. */
fun melBySubsystem(mel: List<JsonNode>): Map<String, Double> {
    val out = linkedMapOf<String, Double>()
    for (item in mel) {
        val subsystem = item.path("subsystem").asText("")
        val mass = item.path("mass_kg").asDouble() * item.path("quantity").asInt(1)
        out[subsystem] = (out[subsystem] ?: 0.0) + mass
    }
    return out
}

// ---------- CR-007: доля витка ----------

class MissingOrbitFractionException(message: String) : IllegalArgumentException(message)

/**
 * Энергобаланс витка. Доля витка в режиме обязана быть задана: равномерное
 * деление — молчаливое допущение, дающее правдоподобное, но бессмысленное
 * число (CR-007). Это продолжение находки про баланс, равный нулю
 * по построению: и там, и здесь число существовало, но ни о чём не говорило.
 */
fun orbitEnergyBalance(generatedWh: Double, modes: List<JsonNode>, orbitH: Double): Double {
    val missing = modes.filter { it.path("orbit_fraction").isMissingNode || it.path("orbit_fraction").isNull }
        .map { it.path("name").asText("") }
    if (missing.isNotEmpty()) {
        throw MissingOrbitFractionException("доля витка не задана для режимов: $missing")
    }
    val total = modes.sumOf { it.path("orbit_fraction").asDouble() }
    if (abs(total - 1.0) > 1e-6) {
        throw IllegalArgumentException("доли витка в сумме дают $total, а не 1")
    }
    val consumed = modes.sumOf { it.path("power_w").asDouble() * it.path("orbit_fraction").asDouble() * orbitH }
    return generatedWh - consumed
}
