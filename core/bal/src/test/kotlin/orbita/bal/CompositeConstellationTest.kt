// МВП-М1 (ЗАДАЧА-CODE-ПОСТРОЕНИЕ): составное построение и физика широт.
// Меры §4/§5: пример владельца (4 плоскости 52° + 3 ССО) разворачивается и
// суммируется; наклонение ССО — из высоты, сверка со справочником; Walker 52°
// выше широты видимости — ноль; ССО полюс/экватор по проходо-минутам ≥ 5;
// баланс: сумма по ячейкам = сумма длительностей пролётов.
package orbita.bal

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class CompositeConstellationTest {

    private val mapper = ObjectMapper()

    private fun ownerExample(): ParsedConstellation = parseConstellationDoc(
        mapper.readTree(
            """{"id":"CN-9001","kind":"composite","subgroups":[
                 {"name":"Наклонная","kind":"walker_delta","planes":4,"per_plane":2,
                  "altitude_km":600,"inclination_deg":52,"phasing":1},
                 {"name":"Полярная","kind":"sso","planes":3,"per_plane":2,
                  "altitude_km":600,"ltan_h":10.5}
               ]}""",
        ),
    )

    @Test
    fun `пример владельца - сумма КА, подгруппы, уникальные id`() {
        val parsed = ownerExample()
        assertEquals(14, parsed.totalSats) // 4×2 + 3×2 — сумма, не ввод
        assertEquals(2, parsed.subgroups.size)
        assertEquals(14, parsed.slots.map { it.satId }.toSet().size)
        // ССО-подгруппа получила вычисленное наклонение, не введённое
        val sso = parsed.slotsBySubgroup()[1].second
        assertTrue(sso.all { abs(it.incDeg - ssoInclinationDeg(600.0)) < 1e-9 })
        // кампании: две пары «наклонение × высота» — две кампании
        assertEquals(2, launchCampaigns(parsed.slots))
    }

    @Test
    fun `наклонение ССО из высоты - сверка со справочным значением`() {
        // справочник (Vallado, ф. sun-synchronous): h=600 км → i ≈ 97.79°
        assertTrue(abs(ssoInclinationDeg(600.0) - 97.79) < 0.05) {
            "600 км: ${ssoInclinationDeg(600.0)}"
        }
        // и вторая точка: h=800 км → i ≈ 98.6°
        assertTrue(abs(ssoInclinationDeg(800.0) - 98.60) < 0.05) {
            "800 км: ${ssoInclinationDeg(800.0)}"
        }
    }

    @Test
    fun `walker star - веер RAAN в полукруге`() {
        val star = walkerStar(86.0, 6, 3, 0, 780.0)
        assertEquals(setOf(0.0, 60.0, 120.0), star.map { it.raanDeg }.toSet())
    }

    @Test
    fun `миграция х1 - одиночный walker живёт без правок`() {
        val parsed = parseConstellationDoc(
            mapper.readTree(
                """{"id":"CN-9002","kind":"walker_delta",
                     "walker":{"inclination_deg":52,"total":8,"planes":4,
                               "phasing":1,"altitude_km":600}}""",
            ),
        )
        assertEquals(8, parsed.totalSats)
        assertEquals(1, parsed.subgroups.size)
        // тот же перечень орбит, что и прежний прямой expand
        val legacy = ConstellationConfig(52.0, 8, 4, 1, 600.0).expand()
        assertEquals(
            legacy.map { Triple(it.raanDeg, it.maDeg, it.incDeg) },
            parsed.slots.map { Triple(it.raanDeg, it.maDeg, it.incDeg) },
        )
    }

    @Test
    fun `широты - Walker 52 не видит полюс, ССО обслуживает его в разы плотнее экватора`() {
        val vis = VisibilityPrecompute()
        val targets = listOf(
            GridPoint("equator", 0.0, 40.0),
            GridPoint("mid", 50.0, 40.0),
            GridPoint("polar", 85.0, 40.0),
        )
        val day = 86400.0
        val epoch = "2026-03-20T00:00:00.000Z"

        fun passMinutes(doc: com.fasterxml.jackson.databind.JsonNode): Map<String, Double> {
            val acc = linkedMapOf("equator" to 0.0, "mid" to 0.0, "polar" to 0.0)
            doc["passes"].forEach { p ->
                val id = p["target_ref"].asText()
                acc[id] = (acc[id] ?: 0.0) + (p["end_s"].asDouble() - p["start_s"].asDouble()) / 60.0
            }
            return acc
        }

        // Walker 52°, зона до ~52+14=66°: полярная ячейка вне зоны — ноль
        val walker = parseConstellationDoc(
            mapper.readTree(
                """{"id":"CN-9003","kind":"composite","subgroups":[
                     {"name":"W","kind":"walker_delta","planes":4,"per_plane":2,
                      "altitude_km":600,"inclination_deg":52}]}""",
            ),
        )
        val wMin = passMinutes(vis.scheduleSlots(walker.slots, epoch, day, 10.0, targets))
        assertTrue(wMin.getValue("polar") == 0.0) { "полюс у Walker 52°: $wMin" }
        assertTrue(wMin.getValue("mid") > 0.0)

        // ССО: сходящиеся трассы — полюс в разы плотнее экватора (мера ≥ 5×)
        val sso = parseConstellationDoc(
            mapper.readTree(
                """{"id":"CN-9004","kind":"composite","subgroups":[
                     {"name":"S","kind":"sso","planes":3,"per_plane":2,
                      "altitude_km":600,"ltan_h":10.5}]}""",
            ),
        )
        val sMin = passMinutes(vis.scheduleSlots(sso.slots, epoch, day, 10.0, targets))
        val ratio = sMin.getValue("polar") / sMin.getValue("equator")
        assertTrue(ratio >= 5.0) {
            "полюс/экватор по проходо-минутам: $ratio (полюс ${sMin["polar"]}, экватор ${sMin["equator"]})"
        }

        // баланс: сумма по ячейкам = сумма длительностей всех пролётов
        val doc = vis.scheduleSlots(sso.slots, epoch, day, 10.0, targets)
        val total = doc["passes"].sumOf { (it["end_s"].asDouble() - it["start_s"].asDouble()) / 60.0 }
        assertTrue(abs(sMin.values.sum() - total) < 1e-6)

        // min ≠ max на реальном построении — шкале есть что показывать
        val composite = ownerExample()
        val cMin = passMinutes(vis.scheduleSlots(composite.slots, epoch, day, 10.0, targets))
        assertTrue(cMin.values.min() != cMin.values.max()) { cMin.toString() }
    }
}
