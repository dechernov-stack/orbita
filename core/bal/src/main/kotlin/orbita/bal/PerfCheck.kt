// Регрессия производительности (TZ-COM-004, STEP-3 §0.2): замер предрасчёта
// геометрии эталонной конфигурации из spec/reference/perf_geometry.json.
// Превышение бюджета времени останавливает сборку. Событие видимости берётся
// из предрасчёта, не пересчитывается в цикле (ADR-013) — это условие бюджета.
package orbita.bal

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import kotlin.system.exitProcess

fun main() {
    val mapper = ObjectMapper()
    val cfgFile = RepoPaths.repoRoot().resolve("spec/reference/perf_geometry.json").toFile()
    val cfg = mapper.readTree(cfgFile)
    val c = cfg["constellation"]
    val config = ConstellationConfig(
        incDeg = c["inc_deg"].asDouble(),
        total = c["total"].asInt(),
        planes = c["planes"].asInt(),
        phasing = c["phasing"].asInt(),
        altKm = c["alt_km"].asDouble(),
    )
    val budgetS = cfg["budget_seconds"].asDouble()

    val t0 = System.nanoTime()
    val doc = VisibilityPrecompute(mapper).scheduleOnCoarseGrid(
        config = config,
        epochIso = cfg["epoch"].asText(),
        durationS = cfg["duration_s"].asDouble(),
        minElevDeg = cfg["min_elevation_deg"].asDouble(),
        requestedCellKm = cfg["coarse_cell_km"].asDouble(),
        scenarioRef = "SC-0000",
    )
    val elapsedS = (System.nanoTime() - t0) / 1e9
    val passes = doc["passes"].size()

    println(
        "TZ-COM-004: предрасчёт геометрии %s: %d пролётов за %.1f с (бюджет %.0f с)"
            .format("${config.total}/${config.planes}@${config.altKm.toInt()}км", passes, elapsedS, budgetS),
    )
    if (passes == 0) {
        println("TZ-COM-004: ОШИБКА — предрасчёт не дал ни одного пролёта, замер недостоверен")
        exitProcess(1)
    }
    if (elapsedS > budgetS) {
        println("TZ-COM-004: ПРЕВЫШЕНИЕ бюджета — сборка остановлена")
        exitProcess(1)
    }
}
