// ADR-044: модель аппарата собирается из поддерева узла КА. Проверяется, что
// дерево, разложенное так, как это делает миграция V038, собирается ровно в
// тот контракт, который читали расчёты, а недостающее и чужие единицы —
// претензии, не тихие нули.
package orbita.out

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CarrierAssemblyTest {
    private val mapper = ObjectMapper()

    private fun q(name: String, value: Double, unit: String) =
        """{"name":"$name","quantity":{"value":$value,"unit":"$unit","provenance":{"source":"manual","author":"t"}}}"""

    private fun node(id: String, name: String, kind: String, parent: String?, profile: String, params: List<String>): JsonNode =
        mapper.readTree(
            """{"id":"$id","name":"$name","kind":"$kind"${parent?.let { ""","parent":"$it"""" } ?: ""},
                "profile":$profile,"parameters":[${params.joinToString(",")}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
        )

    private val tree = listOf(
        node(
            "CM-0002", "КА", "element", "CM-0001",
            """{"role":"spacecraft","preset":"cubesat_16u","modes":[{"name":"standby","power_w":6.0,"orbit_fraction":0.4},{"name":"rx","power_w":9.0,"orbit_fraction":0.6}]}""",
            emptyList(),
        ),
        node(
            "CM-0011", "Платформа", "subsystem", "CM-0002", """{"role":"platform"}""",
            listOf(q("dry_mass", 30.0, "kg"), q("design_life", 5.0, "a"), q("sa_area", 0.18, "m2"),
                q("sa_efficiency", 0.29, "1"), q("battery_energy", 120.0, "Wh"), q("attitude_accuracy", 1.0, "deg")),
        ),
        node("CM-0012", "Корпус", "component", "CM-0011", """{"role":"subsystem","subsystem":"structure","maturity":"existing"}""", listOf(q("mass", 8.0, "kg"))),
        node("CM-0013", "Маховики", "component", "CM-0011", """{"role":"subsystem","subsystem":"adcs","maturity":"existing"}""", listOf(q("mass", 1.2, "kg"), q("quantity", 3.0, "pcs"))),
        node(
            "CM-0014", "Полезная нагрузка", "subsystem", "CM-0002",
            """{"role":"payload","maturity":"new","architecture":"regenerative",
                "links":[{"id":"RL-UP","role":"user_uplink","band_hz":868000000,"tx_power_w":0.1,"antenna":{"type":"patch","gain_dbi":6}}],
                "onboard":{"priority_policy":["A_prime"]},"ephemeris_beacon":{"enabled":true,"period_s":60,"format":"orbit_model"}}""",
            listOf(q("mass", 6.5, "kg"), q("buffer_size", 64.0, "MB")),
        ),
        // чужой узел того же проекта — в сборку не попадает
        node("CM-0006", "Станция", "element", "CM-0005", """{"role":"ground_station"}""", listOf(q("mass", 999.0, "kg"))),
    )

    @Test
    fun `дерево собирается в контракт аппарата без претензий`() {
        val a = CarrierAssembly.assemble(tree[0], tree, mapper)
        assertEquals(emptyList<String>(), a.problems)
        val d = a.doc
        assertEquals("CM-0002", d.path("id").asText())
        assertEquals("cubesat_16u", d.path("preset").asText())
        assertEquals(30.0, d.path("platform").path("dry_mass_kg").asDouble())
        assertEquals(5.0, d.path("platform").path("design_life_years").asDouble())
        assertEquals(0.18, d.path("platform").path("power").path("sa_area_m2").asDouble())
        assertEquals(120.0, d.path("platform").path("power").path("battery_wh").asDouble())
        assertEquals(1.0, d.path("platform").path("attitude").path("pointing_accuracy_deg").asDouble())
        val mel = d.path("platform").path("mel")
        assertEquals(listOf("Корпус", "Маховики", "Полезная нагрузка"), mel.map { it.path("name").asText() })
        assertEquals(3, mel[1].path("quantity").asInt())
        assertEquals("payload", mel[2].path("subsystem").asText())
        assertEquals(6.5, mel[2].path("mass_kg").asDouble())
        assertEquals("regenerative", d.path("payload").path("architecture").asText())
        assertEquals(64.0, d.path("payload").path("onboard").path("buffer_mb").asDouble())
        assertEquals(listOf("A_prime"), d.path("payload").path("onboard").path("priority_policy").map { it.asText() })
        assertEquals(1, d.path("payload").path("links").size())
        assertEquals("standby", d.path("modes")[0].path("name").asText())
        assertEquals(listOf("CM-0002", "CM-0011", "CM-0012", "CM-0013", "CM-0014"), a.nodes)
        // расчёт читает собранный документ так же, как раньше хранимый
        val view = SpacecraftViews().build(d, SpacecraftConditions())
        assertEquals("CM-0002", view.id)
        assertTrue(view.mass.dryMassKg > 0.0, "ведомость масс из подсистем дерева читается расчётом")
    }

    @Test
    fun `чужая единица и отсутствие узла — претензии, не тихие значения`() {
        val broken = tree.map { n ->
            if (n.path("id").asText() != "CM-0011") n
            else node("CM-0011", "Платформа", "subsystem", "CM-0002", """{"role":"platform"}""", listOf(q("dry_mass", 30000.0, "g")))
        }.filter { it.path("id").asText() != "CM-0014" }
        val a = CarrierAssembly.assemble(broken[0], broken, mapper)
        assertTrue(a.doc.path("platform").path("dry_mass_kg").isMissingNode, "грамм в килограммы молча не переводится")
        assertTrue(a.problems.any { "dry_mass" in it && "«g»" in it }, a.problems.toString())
        assertTrue(a.problems.any { "полезной нагрузки" in it }, a.problems.toString())
        assertTrue(a.problems.any { "sa_area" in it }, "обязательное поле названо поимённо: ${a.problems}")
    }
}
