// ADR-044: один экран состава показывает КА с платформой и ПН поддеревом;
// построение 4×52° + 3×ССО — два вхождения КА (×4, ×3), свёртка массы
// группировки честна; «Модель аппарата» открывается по узлу КА, а прежний
// адрес модели отвечает, куда она растворена.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.CoreType
import orbita.mod.schema.SchemaRegistry
import orbita.mod.store.ModelViolationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompositionTreeTest {
    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val server = HttpApi(boundary).start(0)
    private val base = "http://127.0.0.1:${server.address.port}/api"
    private val client = HttpClient.newHttpClient()
    private val project = "PJ-2401"

    private fun q(name: String, value: Double, unit: String) =
        """{"name":"$name","quantity":{"value":$value,"unit":"$unit","provenance":{"source":"manual","author":"t"}}}"""

    private fun component(id: String, name: String, kind: String, parent: String?, extra: String, params: List<String> = emptyList()) {
        boundary.ingest(
            CoreType.Component,
            """{"id":"$id","name":"$name","kind":"$kind"${parent?.let { ""","parent":"$it"""" } ?: ""}$extra,
                "parameters":[${params.joinToString(",")}],"lifecycle":{"status":"Draft","version":"1"}}""",
            "test", project,
        )
    }

    private fun usage(id: String, def: String, qty: Int, parent: String? = null, extra: String = "") {
        boundary.ingest(
            CoreType.ComponentUsage,
            """{"id":"$id","definition_ref":"$def","quantity":$qty${parent?.let { ""","parent_usage":"$it"""" } ?: ""}$extra,
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", project,
        )
    }

    @BeforeAll
    fun setup() {
        TestDb.truncateAll()
        boundary.ingest(
            CoreType.Project,
            """{"id":"$project","name":"Одно дерево","phase":"pre_phase_a","milestones":[{"gate":"MCR"}],"lifecycle":{"status":"Draft","version":"1"}}""",
            "test", project,
        )
        component("CM-0001", "Космический сегмент", "segment", null, ""","segment":"space"""")
        component("CM-0002", "Космический аппарат", "element", "CM-0001",
            ""","profile":{"role":"spacecraft","preset":"cubesat_16u","modes":[{"name":"standby","power_w":6.0,"orbit_fraction":0.4},{"name":"rx","power_w":9.0,"orbit_fraction":0.6}]}""")
        component("CM-0003", "Платформа", "subsystem", "CM-0002", ""","profile":{"role":"platform"}""",
            listOf(q("dry_mass", 30.0, "kg"), q("sa_area", 0.18, "m2"), q("sa_efficiency", 0.29, "1"),
                q("battery_energy", 120.0, "Wh"), q("attitude_accuracy", 1.0, "deg")))
        component("CM-0004", "Корпус", "component", "CM-0003", ""","profile":{"role":"subsystem","subsystem":"structure","maturity":"existing"}""", listOf(q("mass", 8.0, "kg")))
        component("CM-0005", "Полезная нагрузка", "subsystem", "CM-0002",
            ""","profile":{"role":"payload","maturity":"new","architecture":"regenerative","links":[{"id":"RL-UP","role":"user_uplink","band_hz":868000000,"tx_power_w":0.1,"antenna":{"type":"patch","gain_dbi":6}}],"onboard":{"priority_policy":["A_prime"]}}""",
            listOf(q("mass", 6.5, "kg"), q("buffer_size", 64.0, "MB")))
        component("CM-0006", "Наземный сегмент", "segment", null, ""","segment":"ground"""")
        boundary.ingest(
            CoreType.Constellation,
            """{"id":"CN-0001","name":"4×52° + 3×ССО","kind":"walker_delta",
                "subgroups":[{"name":"Наклонная","kind":"walker_delta","planes":4,"per_plane":1,"altitude_km":550,"inclination_deg":52},
                             {"name":"ССО","kind":"sso","planes":3,"per_plane":1,"altitude_km":600,"ltan_h":10.5}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", project,
        )
        usage("CU-0001", "CM-0001", 1)
        usage("CU-0002", "CM-0006", 1)
        usage("CU-0003", "CM-0002", 4, "CU-0001", ""","constellation_ref":"CN-0001","subgroup":"Наклонная"""")
        usage("CU-0004", "CM-0002", 3, "CU-0001", ""","constellation_ref":"CN-0001","subgroup":"ССО"""")
    }

    private fun get(path: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create("$base$path" + (if ("?" in path) "&" else "?") + "project=$project")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `дерево состава показывает КА с платформой и ПН, построение — два вхождения x4 и x3`() {
        val r = get("/views/composition/tree")
        assertEquals(200, r.statusCode(), r.body())
        val tree = mapper.readTree(r.body())
        val rows = tree.path("rows")
        // корни — сегменты; КА под космическим сегментом двумя вхождениями построения не дублируется в корнях
        assertEquals(listOf("CU-0001", "CU-0002"), rows.filter { it.path("level").asInt() == 0 }.map { it.path("usage").asText() })
        // платформа и ПН заведены определениями под КА без вхождений — в дереве
        // они видны по определению, кратность унаследована от вхождения КА
        // (у каждого вхождения КА — своё поддерево: ×4 и ×3)
        val byDef = rows.filter { it.path("by_definition").asBoolean(false) }
        assertEquals(listOf("CM-0003", "CM-0004", "CM-0005", "CM-0003", "CM-0004", "CM-0005"), byDef.map { it.path("definition").asText() })
        assertEquals(listOf(4L, 4L, 4L, 3L, 3L, 3L), byDef.map { it.path("multiplier").asLong() })
        assertEquals(listOf(2, 3, 2, 2, 3, 2), byDef.map { it.path("level").asInt() })
        val cn = tree.path("constellations").single()
        assertEquals("CN-0001", cn.path("id").asText())
        assertEquals(7, cn.path("satellites").asInt())
        assertEquals(listOf(4, 3), cn.path("subgroups").map { it.path("quantity").asInt() })
        val carrier = tree.path("carriers").single()
        assertEquals("CM-0002", carrier.path("id").asText())
        assertEquals(0, carrier.path("problems").size(), carrier.path("problems").toString())
        assertEquals(listOf("CM-0002", "CM-0003", "CM-0004", "CM-0005"), carrier.path("nodes").map { it.asText() })
        // свёртка массы группировки честна: 7 × сухая масса КА из собранной модели
        val dry = carrier.path("dry_mass_kg").asDouble()
        assertTrue(dry > 0.0)
        assertEquals(7 * dry, cn.path("mass_total_kg").asDouble(), 1e-9)
    }

    @Test
    fun `модель аппарата открывается по узлу КА, прежний адрес называет, куда растворена`() {
        val ok = get("/views/spacecraft/CM-0002?alt_km=550")
        assertEquals(200, ok.statusCode(), ok.body())
        val view = mapper.readTree(ok.body())
        assertEquals("CM-0002", view.path("id").asText())
        assertEquals("cubesat_16u", view.path("preset").asText())
        val assembly = mapper.readTree(get("/views/spacecraft/CM-0002/assembly").body())
        assertEquals(30.0, assembly.path("spacecraft").path("platform").path("dry_mass_kg").asDouble())
        val gone = get("/views/spacecraft/SP-0001")
        assertEquals(404, gone.statusCode())
        assertTrue(gone.body().contains("растворена"), gone.body())
        // не-КА узел моделью аппарата не считается
        val notCarrier = get("/views/spacecraft/CM-0006")
        assertTrue(notCarrier.statusCode() >= 400, notCarrier.body())
    }

    @Test
    fun `отдельный объект spacecraft больше не принимается`() {
        val e = assertThrows(ModelViolationException::class.java) {
            boundary.ingest(CoreType.Spacecraft, """{"id":"SP-0009","platform":{},"payload":{}}""", "test", project)
        }
        assertTrue(e.message!!.contains("ADR-044"), e.message)
    }
}
