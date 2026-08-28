// Данные для отображения (TZ-BAL-007): на этом шаге — только выдача данных,
// сами экраны в web/ появятся позже. Серии розы KPI, широтный профиль качества
// с весом спроса, сетка тепловой карты доступности на трёх горизонтах
// усреднения, CZML-поток для 3D.
package orbita.bal

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

/** Горизонт усреднения тепловой карты доступности (STEP-6 §1.2). */
enum class Horizon(val code: String) {
    Instant("instant"),   // мгновенный срез
    Daily("daily"),       // среднесуточное со взвешиванием профилем активности
    Period("period");     // среднее за период

    companion object {
        fun of(code: String): Horizon = entries.firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("неизвестный горизонт: $code")
    }
}

/** Ячейка тепловой карты: ряд доступности по шагам времени. */
data class HeatCell(val id: String, val series: List<Double>)

object VizData {

    // Отображение KpiVector в вход розы ушло вместе с kpiRose (Шаг 16 §2.1):
    // экран сравнения собирает RadarOption из ХРАНИМОГО результата, беря оси,
    // которые в нём фактически есть. Второе отображение расходилось бы с ним.

    // Розы KPI здесь больше нет (Шаг 16 §2.1): нормировка идёт единственным
    // входом radarSeries, к которому обращается экран сравнения. Две реализации
    // нормировки разошлись бы на первом изменении набора осей.

    /**
     * Доступность по горизонтам усреднения. Среднесуточная ВЗВЕШИВАЕТСЯ
     * профилем активности: если пик спроса приходится на провал покрытия,
     * простое среднее по часам завышает качество сервиса (ловушка 3).
     */
    fun availability(
        cells: List<HeatCell>,
        horizon: Horizon,
        diurnal: List<Double>? = null,
    ): Map<String, Double> = when (horizon) {
        Horizon.Instant -> cells.associate { it.id to it.series.first() }
        Horizon.Period -> cells.associate { it.id to it.series.average() }
        Horizon.Daily -> cells.associate { c ->
            val w = diurnal ?: List(c.series.size) { 1.0 }
            val total = w.take(c.series.size).sum()
            c.id to if (total > 0) {
                c.series.mapIndexed { i, v -> v * w[i] }.sum() / total
            } else 0.0
        }
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

    /** Станция на глобусе — из ХРАНИМОГО набора ground_stations. */
    data class GlobeStation(val id: String, val name: String, val latDeg: Double, val lonDeg: Double)

    /** Ячейка карты спроса на глобусе; вес показывается прозрачностью. */
    data class GlobeCell(val id: String, val latDeg: Double, val lonDeg: Double, val weight: Double)

    /**
     * CZML-поток для 3D-глобуса: пакет документа и по пакету на аппарат
     * с позициями в фиксированной СК. Формат — только выдача данных;
     * отображение делает клиент (CesiumJS).
     */
    fun czml(
        altBySat: Map<String, Double>,
        epochIso: String,
        durationS: Double,
        samples: Map<String, List<Triple<Double, Double, Double>>>,  // satId → (t, lat, lon)
        stations: List<GlobeStation> = emptyList(),
        demandCells: List<GlobeCell> = emptyList(),
        /** Радиус зоны обслуживания; null — зоны не рисуются (нет модели КА). */
        serviceRadiusKm: Double? = null,
        mapper: ObjectMapper = ObjectMapper(),
    ): ArrayNode {
        val arr = mapper.createArrayNode()
        // Интервал часов — от эпохи до конца прогона. Вырожденный интервал
        // «эпоха/эпоха» клиент принимает без ошибки, но время не идёт и
        // траектории не отображаются: дефект найден показом экрана, а не тестом.
        val endIso = java.time.Instant.parse(epochIso)
            .plusMillis((durationS * 1000).toLong()).toString()
        // След на один виток: длиннее — трассы сливаются в клубок;
        // для смеси высот — по самой низкой (короткий виток)
        val trailS = minOf(durationS, orbitalPeriodS(altBySat.values.min()))
        arr.addObject()
            .put("id", "document")
            .put("name", "orbita-constellation")
            .put("version", "1.0")
            .putObject("clock")
            .put("interval", "$epochIso/$endIso")
            .put("currentTime", epochIso)
            .put("range", "LOOP_STOP")
            .put("multiplier", 60)
        samples.forEach { (satId, points) ->
            val sat = arr.addObject()
            sat.put("id", satId)
            sat.put("name", satId)
            // Пакет с одной лишь позицией невидим: у сущности нет графики,
            // и клиенту нечего рисовать. Точка и след — часть выдачи данных,
            // а не оформление на стороне клиента.
            sat.putObject("point").apply {
                put("pixelSize", 8.0)
                putObject("color").putArray("rgba").apply { add(11); add(95); add(255); add(255) }
                putObject("outlineColor").putArray("rgba").apply { add(255); add(255); add(255); add(180) }
                put("outlineWidth", 1.0)
            }
            sat.putObject("path").apply {
                put("width", 1.5)
                put("leadTime", 0.0)
                put("trailTime", trailS)
                put("resolution", 60.0)
                putObject("material").putObject("solidColor").putObject("color")
                    .putArray("rgba").apply { add(11); add(95); add(255); add(140) }
            }
            // Зона обслуживания — движущийся круг у поверхности под аппаратом
            // (шаг 16 §2.3). Радиус посчитан сервером по границе обслуживания,
            // а не видимости (TZ-MOD-006): где линия не замыкается, зоны нет.
            serviceRadiusKm?.let { radiusKm ->
                sat.putObject("ellipse").apply {
                    put("semiMajorAxis", radiusKm * 1000.0)
                    put("semiMinorAxis", radiusKm * 1000.0)
                    put("height", 0.0)
                    put("granularity", 0.05)
                    putObject("material").putObject("solidColor").putObject("color")
                        .putArray("rgba").apply { add(11); add(95); add(255); add(36) }
                }
            }
            val pos = sat.putObject("position")
            pos.put("epoch", epochIso)
            pos.put("interpolationAlgorithm", "LAGRANGE")
            pos.put("interpolationDegree", 2)
            val carto = pos.putArray("cartographicDegrees")
            val altM = (altBySat[satId] ?: altBySat.values.min()) * 1000.0
            points.forEach { (t, lat, lon) ->
                carto.add(t); carto.add(lon); carto.add(lat); carto.add(altM)
            }
        }
        stations.forEach { st ->
            val n = arr.addObject()
            n.put("id", "gs-${st.id}")
            n.put("name", st.name.ifBlank { st.id })
            n.putObject("position").putArray("cartographicDegrees")
                .apply { add(st.lonDeg); add(st.latDeg); add(0.0) }
            n.putObject("point").apply {
                put("pixelSize", 9.0)
                putObject("color").putArray("rgba").apply { add(255); add(209); add(102); add(255) }
                putObject("outlineColor").putArray("rgba").apply { add(0); add(0); add(0); add(200) }
                put("outlineWidth", 1.0)
            }
            n.putObject("label").apply {
                put("text", st.name.ifBlank { st.id })
                put("font", "12px sans-serif")
                putObject("pixelOffset").putArray("cartesian2").apply { add(12); add(-10) }
                putObject("fillColor").putArray("rgba").apply { add(255); add(226); add(160); add(255) }
            }
        }
        // Ячейки карты спроса: вес — прозрачностью, доля от максимума посчитана
        // ЗДЕСЬ; нормировки в клиенте нет (ловушка 2). Полградуса с четвертью
        // в каждую сторону — размер отображения, не физика ячейки.
        val maxWeight = demandCells.maxOfOrNull { it.weight } ?: 0.0
        demandCells.forEach { c ->
            val alpha = if (maxWeight > 0.0) (30 + 130 * c.weight / maxWeight).toInt() else 30
            val n = arr.addObject()
            n.put("id", "dm-${c.id}")
            n.put("name", c.id)
            n.putObject("rectangle").apply {
                putObject("coordinates").putArray("wsenDegrees").apply {
                    add(c.lonDeg - 0.75); add(c.latDeg - 0.75)
                    add(c.lonDeg + 0.75); add(c.latDeg + 0.75)
                }
                put("height", 0.0)
                putObject("material").putObject("solidColor").putObject("color")
                    .putArray("rgba").apply { add(255); add(209); add(102); add(alpha) }
            }
        }
        return arr
    }
}
