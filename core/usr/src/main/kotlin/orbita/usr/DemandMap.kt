// Сборка карты спроса (TZ-USR-004, Р8/ADR-008). Поведение — эталон
// spec/demand_semantics.py::build_demand_map, один в один: три слоя, веса
// нормированы и пропорциональны площади. Результат неизменяемый — ловушка
// поверхностного копирования (STEP-2 №2) исключена конструкцией.
package orbita.usr

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.security.MessageDigest

/** Ячейка слоя населения: плотность × площадь × «терминалов на жителя». */
data class PopulationCell(
    val id: String,
    val lat: Double,
    val popDensityPerKm2: Double,
    val terminalsPerCapita: Double,
    val msgsPerTerminalDay: Double,
    val klass: String,
    val lon: Double = 0.0,
)

/** Единичный объект или сценарный вклад: терминалы вне корреляции с населением. */
data class SeedObject(
    val cellId: String,
    val lat: Double,
    val terminals: Double,
    val msgsPerTerminalDay: Double,
    val klass: String,
    val lon: Double = 0.0,
)

data class DemandCell(
    val id: String,
    val lat: Double,
    val terminals: Map<String, Double>,
    val msgsPerDay: Map<String, Double>,
    val areaKm2: Double,
    val weight: Double,
    val lon: Double = 0.0,
)

object DemandMapBuilder {

    /** Карта строится при наличии одного слоя населения; слои 2–3 добавляются поверх. */
    fun build(
        population: List<PopulationCell>,
        pointObjects: List<SeedObject> = emptyList(),
        scenarios: List<SeedObject> = emptyList(),
    ): Map<String, DemandCell> {
        data class Acc(
            val id: String, val lat: Double, val lon: Double, val areaKm2: Double,
            val terminals: MutableMap<String, Double> = mutableMapOf(),
            val msgsPerDay: MutableMap<String, Double> = mutableMapOf(),
        )

        val cells = LinkedHashMap<String, Acc>()
        for (c in population) {
            val area = cellAreaKm2(c.lat)
            val terminals = c.popDensityPerKm2 * area * c.terminalsPerCapita
            val acc = Acc(c.id, c.lat, c.lon, area)
            acc.terminals[c.klass] = terminals
            acc.msgsPerDay[c.klass] = terminals * c.msgsPerTerminalDay
            cells[c.id] = acc
        }
        for (seed in pointObjects + scenarios) {
            val acc = cells.getOrPut(seed.cellId) {
                Acc(seed.cellId, seed.lat, seed.lon, cellAreaKm2(seed.lat))
            }
            acc.terminals.merge(seed.klass, seed.terminals, Double::plus)
            acc.msgsPerDay.merge(seed.klass, seed.terminals * seed.msgsPerTerminalDay, Double::plus)
        }
        val total = cells.values.sumOf { it.msgsPerDay.values.sum() }
        return cells.mapValues { (_, a) ->
            DemandCell(
                id = a.id, lat = a.lat, lon = a.lon, areaKm2 = a.areaKm2,
                terminals = a.terminals.toMap(), msgsPerDay = a.msgsPerDay.toMap(),
                weight = if (total > 0) a.msgsPerDay.values.sum() / total else 0.0,
            )
        }
    }

    /**
     * Версия карты: детерминированная свёртка содержимого (TZ-USR-004:
     * версия фиксируется и входит в input_versions сценария).
     */
    fun version(cells: Map<String, DemandCell>, libraryVersion: String? = null): String {
        val md = MessageDigest.getInstance("SHA-256")
        cells.keys.sorted().forEach { id ->
            val c = cells.getValue(id)
            md.update(buildString {
                append(id).append('|').append(c.lat).append('|').append(c.areaKm2)
                    .append('|').append(c.weight)
                c.msgsPerDay.entries.sortedBy { it.key }.forEach { (k, v) ->
                    append('|').append(k).append('=').append(v)
                }
            }.toByteArray())
        }
        libraryVersion?.let { md.update(it.toByteArray()) }
        return md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    /** Сериализация в нормативный контракт contracts/demand-map (TZ-MOD-001). */
    fun toContractJson(
        mapId: String,
        cells: Map<String, DemandCell>,
        terminalsPerCapita: Double,
        dataset: String = "population-grid",
        scenarioLibraryIds: List<String> = emptyList(),
        libraryVersion: String? = null,
        mapper: ObjectMapper = ObjectMapper(),
    ): ObjectNode {
        val root = mapper.createObjectNode()
        root.put("id", mapId)
        root.put("version", version(cells, libraryVersion))
        root.putObject("grid").put("type", "equal_area_lat").put("nominal_cell_km", 100.0)
        val layers = root.putObject("layers")
        layers.putObject("population").put("dataset", dataset).put("terminals_per_capita", terminalsPerCapita)
        if (scenarioLibraryIds.isNotEmpty()) {
            val sl = layers.putArray("scenario_library")
            scenarioLibraryIds.forEach(sl::add)
        }
        val arr = root.putArray("cells")
        cells.keys.sorted().forEach { id ->
            val c = cells.getValue(id)
            val cn = arr.addObject()
            cn.put("cell_id", c.id).put("lat_deg", c.lat).put("lon_deg", c.lon).put("area_km2", c.areaKm2)
            val demand = cn.putArray("demand")
            c.msgsPerDay.entries.sortedBy { it.key }.forEach { (klass, msgs) ->
                demand.addObject()
                    .put("terminal_profile_ref", klass)
                    .put("count", (c.terminals[klass] ?: 0.0).toInt())
                    .put("uplink_msgs_per_day", msgs)
                    .put("weight", c.weight)
            }
        }
        return root
    }
}

/** Метрики и качество по классам считаются раздельно (TZ-USR-007); суммарный трафик ячейки. */
fun DemandCell.totalMsgsPerDay(): Double = msgsPerDay.values.sum()

fun JsonNode.asDoubleOrNull(): Double? = if (isNumber) asDouble() else null
