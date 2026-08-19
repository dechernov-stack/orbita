// Данные для отображения (TZ-BAL-007): на этом шаге — только выдача данных,
// сами экраны в web/ появятся позже. Серии розы KPI, широтный профиль качества
// с весом спроса, сетка тепловой карты доступности на трёх горизонтах
// усреднения, CZML-поток для 3D.
package orbita.bal

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

object VizData {

    /** Серии розы KPI: нормированные оси варианта. Значения без данных пропускаются. */
    fun kpiRose(v: KpiVector, mapper: ObjectMapper = ObjectMapper()): ArrayNode {
        val arr = mapper.createArrayNode()
        fun axis(name: String, value: Double?) {
            value?.takeIf { it.isFinite() }?.let { arr.addObject().put("axis", name).put("value", it) }
        }
        axis("quality", v.quality.demandWeightedScore)
        axis("economics", 1.0 / v.economics.launchCampaigns)
        axis("energy", v.energy.allowedPayloadDutyCycle)
        axis("reliability", v.degradationCurve.lastOrNull()?.second)
        axis("environment", v.environment?.deorbitCompliant?.let { if (it) 1.0 else 0.0 })
        return arr
    }

    /** Широтный профиль качества с весом спроса — диагностика эффекта ССО. */
    fun latitudeProfile(v: KpiVector, mapper: ObjectMapper = ObjectMapper()): ArrayNode {
        val arr = mapper.createArrayNode()
        v.quality.latitudeProfile.forEach { (band, score, weight) ->
            arr.addObject().put("lat_band_deg", band).put("score", score).put("demand_weight", weight)
        }
        return arr
    }

    /**
     * Сетка тепловой карты доступности на трёх горизонтах усреднения
     * (виток, сутки, весь прогон). Средняя доля от окна усреднения не зависит —
     * от него зависит ХУДШЕЕ окно, и именно оно показывает провалы покрытия,
     * которые среднее по прогону скрывает. Поэтому на каждом горизонте
     * выводится и среднее, и минимум по окнам.
     */
    fun availabilityHeatmap(
        visibility: ObjectNode,
        durationS: Double,
        altKm: Double,
        mapper: ObjectMapper = ObjectMapper(),
    ): ObjectNode {
        val byCell = linkedMapOf<String, MutableList<Pair<Double, Double>>>()
        visibility["passes"].forEach { p ->
            byCell.getOrPut(p["target_ref"].asText()) { mutableListOf() } +=
                p["start_s"].asDouble() to p["end_s"].asDouble()
        }
        val orbitS = orbitalPeriodS(altKm)
        val root = mapper.createObjectNode()
        root.putObject("horizons").put("orbit_s", orbitS).put("day_s", 86400.0).put("run_s", durationS)
        val cells = root.putArray("cells")
        byCell.forEach { (cellId, windows) ->
            val merged = mergeWindows(windows)
            val runFraction = merged.sumOf { it.second - it.first } / durationS
            val node = cells.addObject()
                .put("cell_id", cellId)
                .put("availability_run", runFraction)
                .put("mean_seconds_per_orbit", runFraction * orbitS)
            listOf("orbit" to orbitS, "day" to 86400.0).forEach { (name, windowS) ->
                val perWindow = windowedAvailability(merged, durationS, windowS)
                node.put("availability_$name", perWindow.average())
                node.put("availability_${name}_worst", perWindow.min())
            }
        }
        return root
    }

    /** Доступность по каждому окну усреднения; неполное последнее окно отбрасывается. */
    private fun windowedAvailability(
        merged: List<Pair<Double, Double>>,
        durationS: Double,
        windowS: Double,
    ): List<Double> {
        val count = Math.floor(durationS / windowS).toInt()
        if (count < 1) return listOf(merged.sumOf { it.second - it.first } / durationS)
        return (0 until count).map { i ->
            val from = i * windowS
            val to = from + windowS
            merged.sumOf { (s, e) -> (minOf(e, to) - maxOf(s, from)).coerceAtLeast(0.0) } / windowS
        }
    }

    /**
     * CZML-поток для 3D-глобуса: пакет документа и по пакету на аппарат
     * с позициями в фиксированной СК. Формат — только выдача данных;
     * отображение делает клиент (CesiumJS).
     */
    fun czml(
        config: ConstellationConfig,
        epochIso: String,
        durationS: Double,
        samples: Map<String, List<Triple<Double, Double, Double>>>,  // satId → (t, lat, lon)
        mapper: ObjectMapper = ObjectMapper(),
    ): ArrayNode {
        val arr = mapper.createArrayNode()
        arr.addObject()
            .put("id", "document")
            .put("name", "orbita-constellation")
            .put("version", "1.0")
            .putObject("clock")
            .put("interval", "$epochIso/${epochIso}")
            .put("multiplier", 60)
        samples.forEach { (satId, points) ->
            val sat = arr.addObject()
            sat.put("id", satId)
            val pos = sat.putObject("position")
            pos.put("epoch", epochIso)
            pos.put("interpolationAlgorithm", "LAGRANGE")
            pos.put("interpolationDegree", 2)
            val carto = pos.putArray("cartographicDegrees")
            points.forEach { (t, lat, lon) ->
                carto.add(t); carto.add(lon); carto.add(lat); carto.add(config.altKm * 1000.0)
            }
        }
        return arr
    }
}
