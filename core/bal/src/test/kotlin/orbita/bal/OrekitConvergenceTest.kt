// Сходимость Orekit с замкнутыми формулами эталона (TZ-BAL-001, TZ-BAL-002,
// TZ-BAL-008) и свойства предрасчёта: применимость сетки, кэш, уточнение границ.
// Эталон — внешняя проверка ядра: расхождение сверх допуска означает дефект
// реализации либо неверную настройку Orekit, а не «поправку» эталона.
package orbita.bal

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.schema.SchemaRegistry
import orbita.mod.schema.ValidationError
import orbita.mod.store.ModelViolationException
import org.orekit.frames.FramesFactory
import org.orekit.orbits.KeplerianOrbit
import org.orekit.orbits.PositionAngleType
import org.orekit.time.AbsoluteDate
import org.orekit.time.TimeScalesFactory
import org.orekit.utils.Constants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs

class OrekitConvergenceTest {

    private val mapper = ObjectMapper()
    private val registry = SchemaRegistry(RepoPaths.schemasDir())
    private val epochIso = "2026-03-20T00:00:00.000Z"

    private fun orekitPeriodS(altKm: Double, incDeg: Double): Double {
        OrekitSetup.ensureInitialized()
        val epoch = AbsoluteDate(epochIso, TimeScalesFactory.getUTC())
        val orbit = KeplerianOrbit(
            (RE_KM + altKm) * 1000.0, 0.0, Math.toRadians(incDeg), 0.0, 0.0, 0.0,
            PositionAngleType.MEAN, FramesFactory.getEME2000(), epoch, Constants.WGS84_EARTH_MU,
        )
        return orbit.keplerianPeriod
    }

    @Test
    fun `период Orekit сходится с замкнутой формулой эталона`() {
        listOf(400.0, 550.0, 700.0, 1200.0).forEach { alt ->
            val ok = orekitPeriodS(alt, 53.0)
            val ref = orbitalPeriodS(alt)
            // допуск 0,1%: разница только в значении гравитационного параметра
            assertTrue(abs(ok - ref) / ref < 1e-3) { "alt=$alt: Orekit=$ok, эталон=$ref" }
        }
    }

    @Test
    fun `версии Orekit и данных фиксируются для input_versions`() {
        val versions = OrekitSetup.inputVersions()
        assertTrue(versions["orekit"]!!.startsWith("12.")) { versions.toString() }
        assertTrue(versions["orekit-data"]!!.isNotBlank())
        assertTrue(versions["ballistics"] == BAL_MODULE_VERSION)
    }

    @Test
    fun `геометрия пролёта сходится с эталонным набором TAT-C`() {
        val ref = mapper.readTree(RepoPaths.repoRoot().resolve("spec/reference/coverage_reference.json").toFile())
        val tol = ref["thresholds"]["coverage_pct"].asDouble() / 100.0
        ref["cases"].forEach { c ->
            val alt = c["alt_km"].asDouble()
            val elev = c["min_elev_deg"].asDouble()
            val period = orekitPeriodS(alt, 53.0) / 60.0
            assertTrue(abs(period - c["period_min"].asDouble()) / c["period_min"].asDouble() < tol) {
                "период на $alt км: $period против ${c["period_min"]}"
            }
            val fp = footprintRadiusKm(alt, elev)
            assertTrue(
                abs(fp - c["footprint_radius_km"].asDouble()) / c["footprint_radius_km"].asDouble() < tol,
            ) { "радиус footprint на $alt км: $fp против ${c["footprint_radius_km"]}" }
            val pass = maxPassDurationS(alt, elev)
            assertTrue(
                abs(pass - c["max_pass_s"].asDouble()) / c["max_pass_s"].asDouble() < tol,
            ) { "пролёт на $alt км: $pass с против ${c["max_pass_s"]}" }
        }
    }

    @Test
    fun `предрасчёт даёт документ, валидный по схеме visibility`() {
        val doc = VisibilityPrecompute(mapper).schedule(
            config = ConstellationConfig(53.0, total = 4, planes = 2, phasing = 1, altKm = 550.0),
            epochIso = epochIso, durationS = 6000.0, minElevDeg = 10.0,
            targets = listOf(GridPoint("cell-55", 55.0, 37.0), GridPoint("cell-00", 0.0, 0.0)),
            scenarioRef = "SC-0001", serviceElevDeg = 25.0,
        )
        assertEquals(emptyList<ValidationError>(), registry.validate("contracts/visibility", doc)) {
            registry.validate("contracts/visibility", doc).toString()
        }
        assertTrue(doc["passes"].size() > 0)
        // признак зоны обслуживания проставлен и отличается от простой видимости
        assertTrue(doc["passes"].any { it["in_service_zone"].asBoolean() })
        assertTrue(doc["passes"].any { !it["in_service_zone"].asBoolean() })
    }

    @Test
    fun `длительность и максимальный угол места пролёта в пределах эталона`() {
        val doc = VisibilityPrecompute(mapper).schedule(
            config = ConstellationConfig(53.0, total = 8, planes = 2, phasing = 1, altKm = 550.0),
            epochIso = epochIso, durationS = 86400.0, minElevDeg = 10.0,
            targets = listOf(GridPoint("cell-45", 45.0, 20.0)),
            stepS = 10.0,
        )
        val durations = doc["passes"].map { it["end_s"].asDouble() - it["start_s"].asDouble() }
        val maxRef = maxPassDurationS(550.0, 10.0)
        assertTrue(durations.isNotEmpty())
        // ни один пролёт не длиннее зенитного (верхняя оценка эталона), с запасом на шаг сетки
        assertTrue(durations.max() <= maxRef + 2 * 10.0) { "макс ${durations.max()} против $maxRef" }
        assertTrue(doc["passes"].all { it["max_elevation_deg"].asDouble() >= 10.0 - 1.0 })
    }

    @Test
    fun `уточнение границ событиями Orekit согласуется с грубым проходом`() {
        val slot = OrbitSlot(0, 0.0, 0.0, 53.0, 550.0, satId = "SAT-test")
        val config = ConstellationConfig(53.0, total = 1, planes = 1, phasing = 0, altKm = 550.0)
        val target = GridPoint("cell-30", 30.0, 15.0)
        val precompute = VisibilityPrecompute(mapper)
        val coarse = precompute.schedule(
            config = config, epochIso = epochIso, durationS = 86400.0, minElevDeg = 10.0,
            targets = listOf(target), stepS = 30.0,
        )["passes"].first()
        val coarsePass = Pass(
            "SAT-test", target.id,
            coarse["start_s"].asDouble(), coarse["end_s"].asDouble(), coarse["max_elevation_deg"].asDouble(),
        )
        val refined = precompute.refinePass(
            slot.copy(satId = coarse["spacecraft_ref"].asText()),
            epochIso, target, coarsePass, elevDeg = 10.0,
        )
        assertTrue(refined != null) { "уточнение должно подтвердить грубый пролёт" }
        // уточнённые границы лежат в окне грубого прохода ± шаг сетки
        assertTrue(abs(refined!!.startS - coarsePass.startS) <= 60.0) { "${refined.startS} vs ${coarsePass.startS}" }
        assertTrue(abs(refined.endS - coarsePass.endS) <= 60.0)
        assertTrue(refined.maxElevDeg >= 10.0)
    }

    @Test
    fun `применимость грубой сетки проверяется, а не предполагается`() {
        // штатный случай: 800 км меньше диаметра зоны на 550 км / 10°
        assertEquals(800.0, applicableCoarseCellKm(800.0, 550.0, 10.0))
        // нарушение (400 км / 45°): сетка измельчается до половины диаметра зоны
        val refined = applicableCoarseCellKm(800.0, 400.0, 45.0)
        assertTrue(refined < 800.0) { "$refined" }
        assertTrue(abs(refined - footprintRadiusKm(400.0, 45.0)) < 1e-9)
        // строгий режим отклоняет расчёт с внятным сообщением
        val e = assertThrows<ModelViolationException> {
            applicableCoarseCellKm(800.0, 400.0, 45.0, strict = true)
        }
        assertTrue("ADR-013" in e.message!! && "passes would be lost" in e.message!!)
    }

    @Test
    fun `предрасчёт кэшируется и не повторяется при том же ключе`() {
        val precompute = VisibilityPrecompute(mapper)
        val config = ConstellationConfig(53.0, total = 2, planes = 1, phasing = 0, altKm = 550.0)
        val targets = listOf(GridPoint("cell-10", 10.0, 10.0))
        repeat(5) {
            precompute.schedule(config, epochIso, 3600.0, 10.0, targets)
        }
        assertEquals(1, precompute.computeCount) { "геометрия не должна пересчитываться в цикле (TZ-COM-004)" }
        // изменение конфигурации даёт новый ключ
        precompute.schedule(config.copy(altKm = 600.0), epochIso, 3600.0, 10.0, targets)
        assertEquals(2, precompute.computeCount)
    }

    @Test
    fun `ССО без LTAN отклоняется`() {
        assertThrows<ModelViolationException> {
            ConstellationConfig(0.0, total = 12, planes = 3, phasing = 1, altKm = 700.0, sso = true)
        }
        val sso = ConstellationConfig(0.0, 12, 3, 1, 700.0, sso = true, ltanH = 10.5)
        // наклонение ССО определяется высотой, а не вводится вручную
        assertEquals(ssoInclinationDeg(700.0), sso.effectiveIncDeg(), 1e-9)
    }
}
