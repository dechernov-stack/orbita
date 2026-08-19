// Вектор KPI, фронт Парето и данные визуализации (TZ-BAL-006, TZ-BAL-007).
// Ключевое требование шага: поля, зависящие от Монте-Карло, остаются пустыми,
// а не заполняются правдоподобными числами (ловушка 7).
package orbita.bal

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.schema.SchemaRegistry
import orbita.mod.schema.ValidationError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class KpiAndVizTest {

    private val mapper = ObjectMapper()
    private val registry = SchemaRegistry(RepoPaths.schemasDir())

    private fun kpi(
        scenario: String = "SC-0001",
        score: Double = 0.82,
        campaigns: Int = 1,
        config: ConstellationConfig = ConstellationConfig(53.0, 40, 5, 1, 550.0),
    ): KpiVector {
        val worstBeta = seasonBetaBoundsDeg(config.effectiveIncDeg()).first
        val bestBeta = seasonBetaBoundsDeg(config.effectiveIncDeg()).second
        return KpiVector(
            scenarioRef = scenario,
            constellation = config,
            quality = QualityKpi(
                demandWeightedScore = score,
                latitudeProfile = listOf(Triple(30.0, 0.9, 0.5), Triple(60.0, 0.6, 0.3)),
            ),
            economics = EconomicsKpi(campaigns, deploymentTimeDaysProxy(campaigns, config.planes)),
            energy = EnergyKpi(
                worstSeasonWhPerOrbit = orbitEnergyWh(config.altKm, worstBeta, 0.2, 0.3),
                bestSeasonWhPerOrbit = orbitEnergyWh(config.altKm, bestBeta, 0.2, 0.3),
                eclipseFractionWorst = eclipseFraction(config.altKm, worstBeta),
                allowedPayloadDutyCycle = allowedDutyCycle(
                    orbitEnergyWh(config.altKm, worstBeta, 0.2, 0.3), 15.0, 60.0, config.altKm,
                ),
            ),
            degradationCurve = listOf(0 to score, 4 to score * 0.9, 8 to score * 0.75),
            environment = EnvironmentKpi(
                lifetimeYears = decayYears(config.altKm, 50.0, 0.5),
                deorbitCompliant = deorbitCompliant(config.altKm, 50.0, 0.5),
            ),
        )
    }

    @Test
    fun `документ KPI валиден по нормативной схеме kpi-vector`() {
        val doc = kpi().toContractJson(mapper)
        assertEquals(emptyList<ValidationError>(), registry.validate("contracts/kpi-vector", doc)) {
            registry.validate("contracts/kpi-vector", doc).toString()
        }
    }

    @Test
    fun `без результата моделирования поля класса и покрытия остаются пустыми`() {
        val doc = kpi().toContractJson(mapper)
        // метрики классов приходят из ядра Монте-Карло: без прогона — пустой
        // массив, а не выдуманные числа (ловушка 7)
        assertTrue(doc["quality"]["by_class"].isEmpty)
        // покрытие без расписания пролётов не считается — блок отсутствует целиком
        assertFalse(doc["quality"].has("coverage"))
        // а реально посчитанные величины присутствуют
        assertTrue(doc["energy"].has("allowed_payload_duty_cycle"))
        assertTrue(doc["quality"]["latitude_profile"].size() == 2)
    }

    @Test
    fun `с результатом моделирования вектор заполняется полностью`() {
        val base = kpi()
        val coverage = coverageMetrics(
            listOf(0.0 to 400.0, 3_600.0 to 4_100.0, 9_000.0 to 9_500.0), durationS = 86_400.0,
        )
        val full = base.copy(
            quality = base.quality.withCoverage(coverage).copy(
                byClass = listOf(
                    ClassMetric("A_prime", "delivery_probability_daily", 0.97),
                    ClassMetric("B_prime", "delivery_probability_n_attempts", 0.91),
                    ClassMetric("C_prime", "reaction_time_probability", 0.42),
                ),
            ),
        )
        val doc = full.toContractJson(mapper)
        assertEquals(emptyList<ValidationError>(), registry.validate("contracts/kpi-vector", doc)) {
            registry.validate("contracts/kpi-vector", doc).toString()
        }
        assertEquals(3, doc["quality"]["by_class"].size())
        val cov = doc["quality"]["coverage"]
        listOf("mean_gap_s", "max_gap_s", "revisit_s", "multiplicity_mean").forEach {
            assertTrue(cov.has(it)) { "нет поля покрытия $it" }
        }
        // ни одно поле схемы не осталось пустым
        listOf("quality", "economics", "reliability", "energy", "environment").forEach {
            assertTrue(doc.has(it)) { "нет блока $it" }
        }
    }

    @Test
    fun `разрывы покрытия и период обзора считаются по расписанию пролётов`() {
        // три окна доступа: разрывы 3200 и 4900 с, интервалы начал 3600 и 5400 с
        val m = coverageMetrics(
            listOf(0.0 to 400.0, 3_600.0 to 4_100.0, 9_000.0 to 9_500.0), durationS = 86_400.0,
        )
        assertEquals(3, m.accessWindows)
        assertEquals(4_050.0, m.meanGapS!!, 1e-9)
        assertEquals(4_900.0, m.maxGapS!!, 1e-9)
        assertEquals(4_500.0, m.revisitS!!, 1e-9)
        // разрыв и период обзора — разные величины: пролёт не мгновенен
        assertTrue(m.revisitS!! > m.meanGapS!!)
        // перекрывающиеся окна двух аппаратов: доступность одна, кратность две
        val two = coverageMetrics(listOf(0.0 to 400.0, 100.0 to 500.0), durationS = 1_000.0)
        assertEquals(0.5, two.availability, 1e-9)
        assertEquals(0.8, two.multiplicityMean, 1e-9)
        assertEquals(1, two.accessWindows)
    }

    @Test
    fun `скважность ПН помечена как вычисленная величина`() {
        val q = allowedDutyCycleQuantity(
            genWh = orbitEnergyWh(550.0, 0.0, 0.2, 0.3), busW = 15.0, payloadW = 60.0, altKm = 550.0,
        )
        assertEquals("1", q.unit)
        assertTrue(q.provenance is orbita.mod.model.Provenance.Computed)
        assertEquals("ballistics", (q.provenance as orbita.mod.model.Provenance.Computed).module)
        assertEquals(emptyList<ValidationError>(), registry.validate("common/quantity", q.toJson(mapper)))
    }

    @Test
    fun `энергобаланс считается для худшего и лучшего сезона`() {
        val doc = kpi().toContractJson(mapper)
        val worst = doc["energy"]["worst_season_wh_per_orbit"].asDouble()
        val best = doc["energy"]["best_season_wh_per_orbit"].asDouble()
        assertTrue(best > worst) { "$worst / $best" }
        // для ССО с терминаторным LTAN худший сезон почти бестеневой
        val sso = ConstellationConfig(0.0, 12, 3, 1, 550.0, sso = true, ltanH = 6.0)
        val (w, _) = seasonBetaBoundsDeg(sso.effectiveIncDeg(), sso.ltanH)
        assertTrue(eclipseFraction(550.0, w) < eclipseFraction(550.0, 0.0)) { "beta_worst=$w" }
    }

    @Test
    fun `фронт Парето вычисляется и свёртка его не заменяет`() {
        val a = kpi("SC-A", score = 0.9, campaigns = 2)   // лучшее качество, дороже
        val b = kpi("SC-B", score = 0.7, campaigns = 1)   // дешевле, качество ниже
        val c = kpi("SC-C", score = 0.6, campaigns = 2)   // хуже A по обоим — доминируется
        val front = paretoFront(listOf(a, b, c)).map { it.scenarioRef }
        assertEquals(listOf("SC-A", "SC-B"), front)
        // свёртка требует явных весов и даёт лишь порядок внутри фронта
        val idxQuality = weightedIndex(a, qualityWeight = 1.0, economicsWeight = 0.0)
        val idxEcon = weightedIndex(b, qualityWeight = 0.0, economicsWeight = 1.0)
        assertTrue(idxQuality > 0 && idxEcon > 0)
        assertTrue(paretoFront(listOf(a, b, c)).size == 2) { "свёртка не сокращает фронт" }
    }

    @Test
    fun `данные визуализации выдаются без картинок`() {
        val v = kpi()
        val rose = VizData.kpiRose(v, mapper)
        assertTrue(rose.size() >= 4)
        assertTrue(rose.all { it.has("axis") && it["value"].isNumber })

        val profile = VizData.latitudeProfile(v, mapper)
        assertEquals(2, profile.size())

        val visibility = VisibilityPrecompute(mapper).schedule(
            config = ConstellationConfig(53.0, 4, 2, 1, 550.0),
            epochIso = "2026-03-20T00:00:00.000Z", durationS = 86400.0, minElevDeg = 10.0,
            targets = listOf(GridPoint("cell-45", 45.0, 20.0), GridPoint("cell-15", 15.0, 30.0)),
        )
        val heat = VizData.availabilityHeatmap(visibility, 86400.0, 550.0, mapper)
        assertTrue(heat["horizons"].has("orbit_s"))
        assertEquals(2, heat["cells"].size())
        heat["cells"].forEach { c ->
            val a = c["availability_run"].asDouble()
            assertTrue(a > 0 && a < 1) { "доступность вне (0,1): $a" }
            // три горизонта усреднения: среднее совпадает, худшее окно — нет
            assertEquals(a, c["availability_day"].asDouble(), 1e-9)
            assertEquals(a, c["availability_orbit"].asDouble(), 0.02)
            assertTrue(c["availability_orbit_worst"].asDouble() < a) {
                "провалы покрытия скрыты средним по прогону"
            }
        }

        val czml = VizData.czml(
            ConstellationConfig(53.0, 1, 1, 0, 550.0), "2026-03-20T00:00:00.000Z", 3600.0,
            mapOf("SAT-1" to listOf(Triple(0.0, 10.0, 20.0), Triple(60.0, 11.0, 22.0))), mapper,
        )
        assertEquals("document", czml[0]["id"].asText())
        assertEquals(8, czml[1]["position"]["cartographicDegrees"].size())
    }

    @Test
    fun `спрос-взвешенное качество по зонам обслуживания сохраняет контрольный случай`() {
        // тот же случай, что и на шаге 2: населённая карта, ССО не выигрывает
        val cells = listOf(
            WeightedCell(15.0, 0.20), WeightedCell(30.0, 0.35), WeightedCell(45.0, 0.35),
            WeightedCell(60.0, 0.08), WeightedCell(75.0, 0.02),
        )
        val polar = { c: WeightedCell -> if (abs(c.lat) >= 60) 1.0 else 0.35 }
        val midlat = { c: WeightedCell -> if (abs(c.lat) < 60) 0.9 else 0.4 }
        assertTrue(demandWeightedScore(cells, midlat) > demandWeightedScore(cells, polar))
        // раздельно по классам, без усреднения между ними
        val byClass = demandWeightedScoreByClass(cells, mapOf("A_prime" to midlat, "C_prime" to polar))
        assertEquals(setOf("A_prime", "C_prime"), byClass.keys)
        assertTrue(byClass.getValue("A_prime") != byClass.getValue("C_prime"))
    }
}
