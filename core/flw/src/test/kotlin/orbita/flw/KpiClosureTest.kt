// Замыкание вектора KPI результатами моделирования (TZ-BAL-005, TZ-BAL-006):
// поля, оставленные пустыми на шаге 3, заполняются прогоном, а Парето-фронт
// строится на полных векторах.
package orbita.flw

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.bal.ConstellationConfig
import orbita.bal.EconomicsKpi
import orbita.bal.EnergyKpi
import orbita.bal.EnvironmentKpi
import orbita.bal.KpiVector
import orbita.bal.QualityKpi
import orbita.bal.coverageMetrics
import orbita.bal.paretoFront
import orbita.mod.RepoPaths
import orbita.mod.schema.SchemaRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class KpiClosureTest {

    private val mapper = ObjectMapper()
    private val registry = SchemaRegistry(RepoPaths.schemasDir())
    private val horizonS = 86_400.0

    private fun passes(cellId: String, count: Int, durationS: Double = 420.0) =
        (0 until count).map { i ->
            val start = horizonS / count * i
            CellPass(cellId, start, start + durationS, inServiceZone = true)
        }

    private val channel = ChannelParams(
        capacityMsgsPerPass = 900.0, timeOnAirS = 1.5,
        beaconPeriodS = 600.0, maxAlmanacAgeS = 86_400.0,
    )

    private val populations = listOf(
        PopulationSlice("C-30", "A_prime", 3_000.0, 2.0, weight = 3_000.0),
        PopulationSlice(
            "C-30", "B_prime", 700.0, 4.0, weight = 700.0, attemptsPerPass = 4, maxPasses = 3,
        ),
        PopulationSlice(
            "C-60", "C_prime", 50.0, 6.0, weight = 50.0, attemptsPerPass = 4, maxPasses = 2,
            controlLoop = ControlLoop(3_600.0, 2.0, 20.0, 3.0),
        ),
    )

    private fun runScenario(
        passesByCell: List<CellPass> = passes("C-30", 14) + passes("C-60", 11),
    ) = MonteCarloEngine(mapper).run(
        scenarioRef = "SC-0001", populations = populations, userPasses = passesByCell,
        relayContacts = passes("GS-01", 8), channel = channel, horizonS = horizonS,
        runs = 500, rngSeed = 42,
    )

    private fun vector(quality: QualityKpi, scenario: String, campaigns: Int) = KpiVector(
        scenarioRef = scenario,
        constellation = ConstellationConfig(53.0, 40, 5, 1, 550.0),
        quality = quality,
        economics = EconomicsKpi(campaigns, deploymentTimeDays = 60.0 * campaigns, totalMassKg = 1_200.0),
        energy = EnergyKpi(
            worstSeasonWhPerOrbit = 190.0, bestSeasonWhPerOrbit = 240.0,
            eclipseFractionWorst = 0.36, allowedPayloadDutyCycle = 0.42, batteryDodWorst = 0.21,
        ),
        degradationCurve = listOf(0 to quality.demandWeightedScore, 8 to quality.demandWeightedScore * 0.8),
        environment = EnvironmentKpi(lifetimeYears = 12.4, deorbitCompliant = true),
    )

    @Test
    @DisplayName("TZ-BAL-005: вектор KPI заполнен целиком и валиден по схеме")
    fun `вектор KPI заполняется результатами прогона`() {
        val result = runScenario()
        val coverage = coverageMetrics(
            passes("C-30", 14).map { it.startS to it.endS }, durationS = horizonS,
        )
        val quality = qualityFromRun(
            result, populations, coverage,
            latitudeProfile = listOf(Triple(30.0, 0.94, 3_700.0), Triple(60.0, 0.61, 50.0)),
        )
        val doc = vector(quality, "SC-0001", campaigns = 1).toContractJson(mapper)

        assertTrue(registry.validate("contracts/kpi-vector", doc).isEmpty()) {
            registry.validate("contracts/kpi-vector", doc).toString()
        }
        // метрики классов заполнены и различаются по смыслу (Р9)
        val metrics = doc["quality"]["by_class"].associate {
            it["consumer_class"].asText() to it["metric"].asText()
        }
        assertEquals(
            mapOf(
                "A_prime" to "delivery_probability_daily",
                "B_prime" to "delivery_probability_n_attempts",
                "C_prime" to "reaction_time_probability",
            ),
            metrics,
        )
        assertTrue(doc["quality"]["coverage"].has("revisit_s"))
        assertTrue(doc["quality"]["demand_weighted_score"].asDouble() > 0.0)
    }

    @Test
    @DisplayName("TZ-BAL-005: без прогона поля класса остаются пустыми")
    fun `без прогона поля класса остаются пустыми`() {
        val doc = vector(QualityKpi(demandWeightedScore = 0.8), "SC-0002", campaigns = 1)
            .toContractJson(mapper)
        assertTrue(doc["quality"]["by_class"].isEmpty)
        assertFalse(doc["quality"].has("coverage"))
    }

    @Test
    @DisplayName("TZ-BAL-005: контрольный случай — полярное преимущество не выигрывает")
    fun `при населённой карте спроса полярное построение не выигрывает`() {
        // спрос сосредоточен в средних широтах: 3700 терминалов против 50
        val midLat = runScenario(passes("C-30", 14) + passes("C-60", 2))
        val polar = runScenario(passes("C-30", 2) + passes("C-60", 14))
        val scoreMid = demandWeightedDelivery(midLat, populations)
        val scorePolar = demandWeightedDelivery(polar, populations)
        assertTrue(scoreMid > scorePolar, "$scoreMid vs $scorePolar")
    }

    @Test
    @DisplayName("TZ-BAL-006: фронт Парето строится на полных векторах")
    fun `фронт Парето на полных векторах`() {
        val quality = qualityFromRun(runScenario(), populations)
        val rich = vector(quality, "SC-RICH", campaigns = 3)
        val lean = vector(
            quality.copy(demandWeightedScore = quality.demandWeightedScore * 0.8), "SC-LEAN", campaigns = 1,
        )
        val dominated = vector(
            quality.copy(demandWeightedScore = quality.demandWeightedScore * 0.7), "SC-BAD", campaigns = 3,
        )
        val front = paretoFront(listOf(rich, lean, dominated)).map { it.scenarioRef }
        assertEquals(listOf("SC-RICH", "SC-LEAN"), front)
        // фронт строится на векторах с заполненными метриками классов
        assertTrue(rich.quality.byClass.size == 3)
    }
}
