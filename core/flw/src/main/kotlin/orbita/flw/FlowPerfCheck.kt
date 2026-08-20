// Регрессия производительности эталонного прогона (TZ-FLW-001 MOP, TZ-COM-004).
//
// Замеряется то, что и обещано в ТЗ: предрасчёт геометрии ПЛЮС ядро
// Монте-Карло на всей грубой сетке. Превышение бюджета останавливает сборку.
// Отдельно проверяется архитектурное условие бюджета: внутри цикла реализаций
// обращений к геометрии — ноль (ADR-013).
package orbita.flw

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.bal.ConstellationConfig
import orbita.bal.VisibilityPrecompute
import orbita.bal.applicableCoarseCellKm
import orbita.bal.coarseGrid
import orbita.mod.RepoPaths
import kotlin.system.exitProcess

fun main() {
    val mapper = ObjectMapper()
    // ORBITA_PERF_CONFIG выбирает сценарий: perf_flow_run.json — регрессия CI,
    // perf_full_run.json — полный эталонный масштаб TZ-COM-004 (шаг 13.1)
    val configName = System.getenv("ORBITA_PERF_CONFIG") ?: "perf_flow_run.json"
    val cfg = mapper.readTree(RepoPaths.repoRoot().resolve("spec/reference/$configName").toFile())
    val c = cfg["constellation"]
    val config = ConstellationConfig(
        incDeg = c["inc_deg"].asDouble(), total = c["total"].asInt(),
        planes = c["planes"].asInt(), phasing = c["phasing"].asInt(), altKm = c["alt_km"].asDouble(),
    )
    val durationS = cfg["duration_s"].asDouble()
    val minElevDeg = cfg["min_elevation_deg"].asDouble()
    val serviceElevDeg = cfg["service_elevation_deg"].asDouble()
    val budgetS = cfg["budget_seconds"].asDouble()
    val pop = cfg["population"]
    val ch = cfg["channel"]

    val t0 = System.nanoTime()

    val cellKm = applicableCoarseCellKm(cfg["coarse_cell_km"].asDouble(), config.altKm, minElevDeg)
    val visibility = VisibilityPrecompute(mapper).schedule(
        config = config, epochIso = cfg["epoch"].asText(), durationS = durationS,
        minElevDeg = minElevDeg, targets = coarseGrid(cellKm),
        scenarioRef = "SC-0000", serviceElevDeg = serviceElevDeg,
    )
    val geometryS = (System.nanoTime() - t0) / 1e9

    val passes = visibility["passes"].map {
        CellPass(
            cellId = it["target_ref"].asText(),
            startS = it["start_s"].asDouble(),
            endS = it["end_s"].asDouble(),
            inServiceZone = it.path("in_service_zone").asBoolean(false),
        )
    }
    val cells = passes.map { it.cellId }.distinct().sorted()

    // Популяции: A' на каждой ячейке, C' — на заданной доле ячеек.
    val controlEvery = Math.max(1, (1.0 / pop["control_loop_share"].asDouble()).toInt())
    val populations = cells.flatMapIndexed { i, cell ->
        val a = PopulationSlice(
            cellId = cell, consumerClass = "A_prime",
            terminals = pop["terminals_per_cell"].asDouble(),
            msgsPerTerminalDay = pop["msgs_per_terminal_day"].asDouble(),
            weight = pop["terminals_per_cell"].asDouble(),
            attemptsPerPass = 4, maxPasses = 2,
        )
        if (i % controlEvery != 0) listOf(a) else listOf(
            a,
            a.copy(
                consumerClass = "C_prime", terminals = a.terminals * 0.01, weight = a.terminals * 0.01,
                controlLoop = ControlLoop(
                    requiredReactionS = pop["required_reaction_time_s"].asDouble(),
                    detectionS = 2.0,
                    externalDecisionS = pop["external_decision_time_s"].asDouble(),
                    executionS = 3.0,
                ),
            ),
        )
    }

    val engine = MonteCarloEngine(mapper)
    val result = engine.run(
        scenarioRef = "SC-0000",
        populations = populations,
        userPasses = passes,
        relayContacts = passes.filter { it.inServiceZone }.take(16),
        channel = ChannelParams(
            capacityMsgsPerPass = ch["capacity_msgs_per_pass"].asDouble(),
            timeOnAirS = ch["time_on_air_s"].asDouble(),
            beaconPeriodS = ch["beacon_period_s"].asDouble(),
            maxAlmanacAgeS = ch["max_almanac_age_s"].asDouble(),
        ),
        horizonS = durationS,
        runs = cfg["monte_carlo_runs"].asInt(),
        rngSeed = cfg["rng_seed"].asLong(),
        parallelism = Runtime.getRuntime().availableProcessors(),
    )
    val elapsedS = (System.nanoTime() - t0) / 1e9

    println(
        ("TZ-COM-004: эталонный прогон %s: %d пролётов, %d популяций, %d реализаций " +
            "за %.1f с (геометрия %.1f с, бюджет %.0f с)").format(
            "${config.total}/${config.planes}@${config.altKm.toInt()}км",
            passes.size, populations.size, result.runs, elapsedS, geometryS, budgetS,
        ),
    )
    println("TZ-FLW-001: обращений к геометрии внутри цикла реализаций: ${engine.geometryLookupsInLoop}")

    if (passes.isEmpty() || result.offeredMsgs <= 0.0) {
        println("TZ-COM-004: ОШИБКА — прогон пуст, замер недостоверен")
        exitProcess(1)
    }
    if (engine.geometryLookupsInLoop != 0) {
        println("TZ-FLW-001: ОШИБКА — геометрия пересчитывается внутри цикла реализаций")
        exitProcess(1)
    }
    if (elapsedS > budgetS) {
        println("TZ-COM-004: ПРЕВЫШЕНИЕ бюджета — сборка остановлена")
        exitProcess(1)
    }
}
