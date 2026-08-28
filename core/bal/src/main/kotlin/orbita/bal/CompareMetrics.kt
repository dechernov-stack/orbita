// Сравнение построений (МВП-М2, МЕТРИКИ-ПОСТРОЕНИЙ.md): 14 метрик четырьмя
// группами ИЗ ТОГО ЖЕ интеграла видимости §5 — второго расчётного движка нет
// (ловушка 4). Коэффициенты и таблицы — данными (compare-config.json; файл
// в ORBITA_FILES_DIR перекрывает дефолт — правка без пересборки, ловушка 5).
// Группа Г — прокси простой астрономии и таблиц, в выдаче помечена.
package orbita.bal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Квантиль по правилу ближайшего ранга: перцентиль вместо среднего (ловушка 2). */
fun percentile(values: List<Double>, p: Double): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val rank = Math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
    return sorted[rank - 1]
}

class CompareMetrics(
    private val visibility: VisibilityPrecompute,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    /** Конфиг данными: дефолт из ресурсов, файл стенда перекрывает целиком. */
    fun config(): JsonNode {
        val override = System.getenv("ORBITA_FILES_DIR")
            ?.let { Path.of(it, "compare-config.json") }
            ?.takeIf { Files.exists(it) }
        if (override != null) return mapper.readTree(Files.readString(override))
        return CompareMetrics::class.java.getResourceAsStream("/orbita/bal/compare-config.json")!!
            .use { mapper.readTree(it) }
    }

    data class DemandCell(
        val point: GridPoint,
        /** класс → вес (терминалы) и сообщения/сутки. */
        val byClass: Map<String, Pair<Double, Double>>,
    )

    /**
     * Полный расчёт метрик одного варианта построения. Интеграл видимости —
     * общий VisibilityPrecompute (кэш переживает варианты и повторные вызовы).
     */
    fun evaluate(
        variantId: String,
        variantName: String,
        constellationDoc: JsonNode,
        cells: List<DemandCell>,
        stations: List<GridPoint>,
        epochIso: String,
        durationS: Double,
    ): ObjectNode {
        val cfg = config()
        val parsed = parseConstellationDoc(constellationDoc)
        val targets = cells.map { it.point }
        val vis = visibility.scheduleSlots(
            parsed.slots, epochIso, durationS, minElevDeg = 10.0,
            targets = targets, scenarioRef = variantId, serviceElevDeg = 25.0,
        )
        val service = servicePassesByCell(vis)
        val out = mapper.createObjectNode()
        out.put("variant", variantId)
        out.put("name", variantName)
        out.put("total_sats", parsed.totalSats)
        val sgArr = out.putArray("subgroups")
        parsed.subgroups.forEach { g ->
            sgArr.addObject().put("name", g.name).put("kind", g.kind)
                .put("planes", g.planes).put("per_plane", g.perPlane)
                .put("altitude_km", g.altKm).put("inclination_deg", g.effectiveIncDeg())
        }

        // ---------- А. Обслуживание — из интеграла по ячейкам ----------
        val a = out.putObject("service")
        val classes = cells.flatMap { it.byClass.keys }.toSortedSet()
        val stationVis = if (stations.isEmpty()) null else visibility.scheduleSlots(
            parsed.slots, epochIso, durationS, minElevDeg = 10.0,
            targets = stations, scenarioRef = "$variantId-st",
        )
        val downlinkBySat = stationContactsBySat(stationVis)
        classes.forEach { cls ->
            val classCells = cells.filter { (it.byClass[cls]?.first ?: 0.0) > 0.0 }
            if (classCells.isEmpty()) return@forEach
            var wSum = 0.0
            var covW = 0.0
            var respW = 0.0
            var latW = 0.0
            var worstGap = 0.0
            val revisits = mutableListOf<Double>()
            var capacityMin: Double? = null
            classCells.forEach { c ->
                val w = c.byClass.getValue(cls).first
                val msgsDay = c.byClass.getValue(cls).second
                val windows = service[c.point.id].orEmpty()
                val merged = mergeWindows(windows.map { it.second })
                val covered = merged.sumOf { it.second - it.first }
                val gaps = gapsOf(merged, durationS)
                val maxGap = gaps.maxOrNull() ?: durationS
                if (maxGap > worstGap) worstGap = maxGap
                revisits += merged.map { it.first }.zipWithNext { s1, s2 -> s2 - s1 }
                // mean response time: E[ожидания до окна] = Σ gap²/2 / T
                val resp = gaps.sumOf { it * it / 2.0 } / durationS
                // латентность store-and-forward: ожидание пролёта + сброс
                // тем же КА на станцию (по расписаниям того же интеграла)
                val lat = resp + meanRelayDelay(windows, downlinkBySat, durationS)
                wSum += w
                covW += w * covered / durationS
                respW += w * resp
                latW += w * lat
                // запас ёмкости: проходо-минуты на сообщение спроса — минимум
                // по ячейкам класса (узкое место); канал появится — умножим
                if (msgsDay > 0) {
                    val margin = windows.sumOf { it.second.second - it.second.first } / 60.0 /
                        (msgsDay * durationS / 86400.0)
                    capacityMin = minOf(capacityMin ?: margin, margin)
                }
            }
            val n = a.putObject(cls)
            n.put("coverage_share", covW / wSum)
            n.put("max_gap_s", worstGap)
            percentile(revisits, 75.0)?.let { n.put("revisit_p75_s", it) }
            n.put("mean_response_s", respW / wSum)
            n.put("latency_s", latW / wSum)
            capacityMin?.let { n.put("capacity_margin_min_per_msg", it) }
        }

        // ---------- Б. Стоимость и логистика — из построения + конфиг ----------
        val b = out.putObject("logistics")
        val batches = launchCampaigns(parsed.slots)
        b.put("launch_batches", batches)
        val perPlane = cfg.path("deploy").path("days_per_plane_raan_drift").asDouble(15.0)
        val perCampaign = cfg.path("deploy").path("days_per_campaign").asDouble(30.0)
        val planesTotal = parsed.subgroups.sumOf { it.planes }.coerceAtLeast(1)
        b.put("deployment_days", perCampaign * batches + perPlane * (planesTotal - 1))
        val rideshare = cfg.path("cost").path("rideshare_factor")
        val costLaunch = parsed.subgroups.groupBy {
            "%.2f|%.2f".format(it.effectiveIncDeg(), it.altKm)
        }.values.sumOf { group ->
            val kind = group.first().kind
            cfg.path("cost").path("launch_batch_unit").asDouble(8.0) *
                rideshare.path(kind).asDouble(1.0)
        }
        b.put(
            "cost_proxy",
            parsed.totalSats * cfg.path("cost").path("platform_unit").asDouble(1.0) + costLaunch,
        )

        // ---------- В. Живучесть и эксплуатация ----------
        val v = out.putObject("resilience")
        v.put("degradation_dmax_gap_s", degradationDeltaMaxGap(parsed, targets, epochIso, durationS))
        val dvTable = cfg.path("station_keeping_dv_mps_year")
        val disposal = cfg.path("disposal")
        val dvYears = parsed.subgroups.maxOf { g ->
            dvTable.firstOrNull { g.altKm <= it.path("alt_max_km").asDouble() }
                ?.path("dv")?.asDouble() ?: 2.0
        }
        v.put("station_keeping_dv_mps_year", dvYears)
        val worstDisposal = parsed.subgroups.map { g ->
            val natural = decayYears(
                g.altKm, disposal.path("mass_kg").asDouble(80.0), disposal.path("area_m2").asDouble(0.5),
            )
            val limit = disposal.path("limit_years").asDouble(25.0)
            if (natural <= limit) "натурально (${"%.0f".format(natural)} лет)"
            else "увод Δv ${"%.0f".format(deorbitDeltaVMs(g.altKm))} м/с"
        }
        v.put("disposal", worstDisposal.joinToString("; "))

        // ---------- Г. Инженерные следствия — ПРОКСИ с пометкой ----------
        val g4 = out.putObject("orbit_proxy")
        g4.put("proxy", true)
        val shadow = g4.putArray("power_regime")
        parsed.subgroups.forEach { g ->
            val (betaMin, betaMax, worstShadow) = betaAndShadow(g, epochIso)
            shadow.addObject().put("name", g.name)
                .put("beta_min_deg", betaMin).put("beta_max_deg", betaMax)
                .put("worst_shadow_share", worstShadow)
        }
        val radiation = cfg.path("radiation_classes")
        val radWorst = parsed.subgroups.map { g ->
            radiation.firstOrNull {
                g.altKm <= it.path("alt_max_km").asDouble() &&
                    g.effectiveIncDeg() <= it.path("inc_max_deg").asDouble()
            }
        }.maxByOrNull { listOf("low", "mid", "high").indexOf(it?.path("class")?.asText() ?: "low") }
        g4.put("radiation_class", radWorst?.path("class")?.asText() ?: "low")
        g4.put("radiation_note", radWorst?.path("note")?.asText() ?: "")
        val stationsNeeded = stationsForLatency(parsed, cells, cfg, epochIso, durationS)
        g4.put("stations_for_latency", stationsNeeded.first)
        g4.put("stations_names", stationsNeeded.second.joinToString(", "))
        // окно и доплер — из геометрии
        val allDur = service.values.flatten().map { it.second.second - it.second.first }
        percentile(allDur, 50.0)?.let { g4.put("median_pass_s", it) }
        val vOrb = parsed.subgroups.maxOf {
            sqrt(MU_KM3_S2 / (RE_KM + it.altKm)) * 1000.0
        }
        g4.put(
            "doppler_max_hz",
            vOrb / 299_792_458.0 * cfg.path("doppler_ref_hz").asDouble(868e6),
        )
        return out
    }

    // ---- внутренности: всё из того же расписания пролётов ----

    /** Ячейка → пролёты зоны обслуживания (satId + окно). */
    private fun servicePassesByCell(vis: JsonNode): Map<String, List<Pair<String, Pair<Double, Double>>>> {
        val byCell = linkedMapOf<String, MutableList<Pair<String, Pair<Double, Double>>>>()
        vis["passes"].forEach { p ->
            if (!p.path("in_service_zone").asBoolean(false)) return@forEach
            byCell.getOrPut(p["target_ref"].asText()) { mutableListOf() } +=
                p["spacecraft_ref"].asText() to (p["start_s"].asDouble() to p["end_s"].asDouble())
        }
        return byCell
    }

    private fun stationContactsBySat(vis: JsonNode?): Map<String, List<Double>> {
        if (vis == null) return emptyMap()
        val bySat = linkedMapOf<String, MutableList<Double>>()
        vis["passes"].forEach { p ->
            bySat.getOrPut(p["spacecraft_ref"].asText()) { mutableListOf() } += p["start_s"].asDouble()
        }
        bySat.values.forEach { it.sort() }
        return bySat
    }

    private fun gapsOf(merged: List<Pair<Double, Double>>, durationS: Double): List<Double> {
        if (merged.isEmpty()) return listOf(durationS)
        val out = mutableListOf<Double>()
        if (merged.first().first > 0) out += merged.first().first
        out += merged.zipWithNext { a, b -> b.first - a.second }
        if (merged.last().second < durationS) out += durationS - merged.last().second
        return out.filter { it > 0 }
    }

    /** Среднее ожидание сброса: от конца пролёта ячейки до следующего контакта
     * ТОГО ЖЕ КА со станцией — из того же расписания. */
    private fun meanRelayDelay(
        cellPasses: List<Pair<String, Pair<Double, Double>>>,
        downlinkBySat: Map<String, List<Double>>,
        durationS: Double,
    ): Double {
        if (cellPasses.isEmpty() || downlinkBySat.isEmpty()) return durationS
        val delays = cellPasses.mapNotNull { (sat, w) ->
            val next = downlinkBySat[sat]?.firstOrNull { it >= w.second }
            next?.minus(w.second)
        }
        return if (delays.isEmpty()) durationS else delays.average()
    }

    /** В9: минус один КА худшей подгруппы — худший случай по подгруппам. */
    private fun degradationDeltaMaxGap(
        parsed: ParsedConstellation,
        targets: List<GridPoint>,
        epochIso: String,
        durationS: Double,
    ): Double {
        fun worstMaxGap(slots: List<OrbitSlot>, ref: String): Double {
            val vis = visibility.scheduleSlots(
                slots, epochIso, durationS, minElevDeg = 10.0,
                targets = targets, scenarioRef = ref, serviceElevDeg = 25.0,
            )
            val byCell = servicePassesByCell(vis)
            return targets.maxOf { t ->
                val merged = mergeWindows(byCell[t.id].orEmpty().map { it.second })
                gapsOf(merged, durationS).maxOrNull() ?: durationS
            }
        }
        val base = worstMaxGap(parsed.slots, "base")
        val groups = parsed.slotsBySubgroup().ifEmpty { return 0.0 }
        return groups.maxOf { (_, slots) ->
            if (slots.isEmpty()) return@maxOf 0.0
            val without = parsed.slots - slots.first()
            worstMaxGap(without, "deg-${slots.first().satId}") - base
        }
    }

    /**
     * Г11: β-угол и худшая доля тени за год — прямой астрономией (прокси).
     * sin β = cos i · sin δ☉ + sin i · cos δ☉ · sin(Ω − α☉); Ω прецессирует
     * J2, Солнце — круговой эклиптикой. Тень — цилиндр Земли.
     */
    private fun betaAndShadow(g: SubgroupConfig, epochIso: String): Triple<Double, Double, Double> {
        val inc = Math.toRadians(g.effectiveIncDeg())
        val eps = Math.toRadians(23.44)
        // прецессия ВДУ J2: Ω̇ = −1.5·J2·n·(RE/a)²·cos i (та же физика, что ССО)
        val aKm = RE_KM + g.altKm
        val raanRate = -1.5 * J2 * meanMotionRadS(g.altKm) *
            (RE_KM / aKm) * (RE_KM / aKm) * cos(inc)
        // опорный RAAN: для ССО — из LTAN (солнечная привязка), иначе 0
        val raan0 = Math.toRadians(((g.ltanH ?: 12.0) - 12.0) * 15.0)
        var betaMin = Double.MAX_VALUE
        var betaMax = -Double.MAX_VALUE
        var worstShadow = 0.0
        val ratio = RE_KM / (RE_KM + g.altKm)
        for (day in 0 until 365) {
            val ls = 2 * Math.PI * day / 365.2422           // эклиптическая долгота Солнца
            val ds = asin(sin(eps) * sin(ls))                // склонение
            val ra = kotlin.math.atan2(cos(eps) * sin(ls), cos(ls)) // прямое восхождение
            val raan = if (g.kind == "sso") ra + raan0 else raan0 + raanRate * day * 86400.0
            val sinBeta = cos(inc) * sin(ds) + sin(inc) * cos(ds) * sin(raan - ra)
            val beta = Math.toDegrees(asin(sinBeta.coerceIn(-1.0, 1.0)))
            if (beta < betaMin) betaMin = beta
            if (beta > betaMax) betaMax = beta
            val cb = cos(Math.toRadians(beta))
            val shadow = if (abs(ratio / cb) <= 1.0) {
                acos(sqrt(1 - ratio * ratio) / cb) / Math.PI
            } else 0.0
            if (shadow > worstShadow) worstShadow = shadow
        }
        return Triple(betaMin, betaMax, worstShadow)
    }

    /** Г13: жадный перебор каталога площадок до целевой латентности сброса. */
    private fun stationsForLatency(
        parsed: ParsedConstellation,
        cells: List<DemandCell>,
        cfg: JsonNode,
        epochIso: String,
        durationS: Double,
    ): Pair<Int, List<String>> {
        val target = cfg.path("latency_target_s").asDouble(1800.0)
        val catalog = cfg.path("stations_catalog").map {
            GridPoint(it.path("id").asText(), it.path("lat").asDouble(), it.path("lon").asDouble()) to
                it.path("name").asText()
        }
        if (catalog.isEmpty()) return 0 to emptyList()
        // контакты всех КА со ВСЕМИ площадками — один интеграл, дальше выборки
        val vis = visibility.scheduleSlots(
            parsed.slots, epochIso, durationS, minElevDeg = 10.0,
            targets = catalog.map { it.first }, scenarioRef = "stcat",
        )
        val perStation = linkedMapOf<String, MutableMap<String, MutableList<Double>>>()
        vis["passes"].forEach { p ->
            perStation.getOrPut(p["target_ref"].asText()) { linkedMapOf() }
                .getOrPut(p["spacecraft_ref"].asText()) { mutableListOf() } += p["start_s"].asDouble()
        }
        val cellVis = visibility.scheduleSlots(
            parsed.slots, epochIso, durationS, minElevDeg = 10.0,
            targets = cells.map { it.point }, scenarioRef = "stcat-cells", serviceElevDeg = 25.0,
        )
        val cellPasses = servicePassesByCell(cellVis).values.flatten()
        fun meanLatency(chosen: Set<String>): Double {
            val bySat = linkedMapOf<String, MutableList<Double>>()
            chosen.forEach { st ->
                perStation[st]?.forEach { (sat, times) ->
                    bySat.getOrPut(sat) { mutableListOf() } += times
                }
            }
            bySat.values.forEach { it.sort() }
            return meanRelayDelay(cellPasses, bySat, durationS)
        }
        val chosen = linkedSetOf<String>()
        var current = durationS
        while (chosen.size < catalog.size) {
            val best = catalog.map { it.first.id }.filter { it !in chosen }
                .minByOrNull { meanLatency(chosen + it) } ?: break
            val next = meanLatency(chosen + best)
            if (next >= current) break
            chosen += best
            current = next
            if (current <= target) break
        }
        val names = catalog.filter { it.first.id in chosen }.map { it.second }
        return chosen.size to names
    }
}
