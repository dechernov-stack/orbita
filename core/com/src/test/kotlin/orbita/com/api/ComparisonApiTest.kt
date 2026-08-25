// Сравнение вариантов по сценариям (находка живого прогона: маршрут ждал
// нескольких kpi-расчётов ОДНОГО сценария, а вариантность в процессе — это
// клоны сценариев; сравнение не работало никогда). Вариант = сценарий с
// выполненным прогоном; оси — из результата потоков.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.schema.SchemaRegistry
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ComparisonApiTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val server = HttpApi(boundary).start(0)
    private val base = "http://127.0.0.1:${server.address.port}/api"
    private val client = HttpClient.newHttpClient()

    private fun scenarioJson(id: String, name: String) =
        """{"id":"$id","name":"$name","epoch":"2026-03-20T00:00:00Z",
            "duration_s":21600,"rng_seed":7,"monte_carlo_runs":5,
            "delivery_mode":"store_and_forward",
            "constellation_ref":"CN-0001","spacecraft_ref":"SP-0001",
            "demand_map_ref":"DM-0001","ground_stations_ref":"GS-0001",
            "protocol_adapter_ref":"PA-0001",
            "input_versions":{"CN-0001":"1","DM-0001":"1"}}"""

    @BeforeAll
    fun setup() {
        TestDb.truncateAll()
        boundary.ingest(
            orbita.mod.model.CoreType.Project,
            """{"id":"PJ-1701","name":"Сравнение","phase":"phase_a",
                "milestones":[{"gate":"SRR"}],"lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1701",
        )
        DemoProject.seedModelingInputsFor(boundary, "PJ-1701")
        boundary.ingest(
            orbita.mod.model.CoreType.Scenario, scenarioJson("SC-1701", "База"), "test", "PJ-1701",
        )
        boundary.ingest(
            orbita.mod.model.CoreType.Scenario, scenarioJson("SC-1702", "Вариант"), "test", "PJ-1701",
        )
    }

    @AfterAll
    fun stop() = server.stop(0)

    private fun get(path: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create("$base$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun post(path: String, body: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create("$base$path"))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `один прогон — рабочее состояние с перечнем, два — роза по сценариям`() {
        // прогона нет ни у кого: отказ называет счёт сценариев
        val empty = get("/views/comparison?project=PJ-1701")
        assertEquals(409, empty.statusCode()) { empty.body() }
        assertTrue(empty.body().contains("ни одного")) { empty.body() }

        val first = post("/views/flows/run?project=PJ-1701", """{"scenario":"SC-1701"}""")
        assertEquals(201, first.statusCode()) { first.body() }
        val one = get("/views/comparison?project=PJ-1701")
        assertEquals(409, one.statusCode()) { one.body() }
        assertTrue(one.body().contains("SC-1701")) { one.body() }

        val second = post("/views/flows/run?project=PJ-1701", """{"scenario":"SC-1702"}""")
        assertEquals(201, second.statusCode()) { second.body() }
        val ok = get("/views/comparison?project=PJ-1701")
        assertEquals(200, ok.statusCode()) { ok.body() }
        val view = mapper.readTree(ok.body())
        val names = view["options"].map { it["name"].asText() }.toSet()
        assertEquals(setOf("SC-1701", "SC-1702"), names)
        // оси — из результата потоков, направления заданы в kpi-axes.json
        val axes = view["availableAxes"].map { it.asText() }.toSet()
        assertTrue("delivery_a_prime" in axes) { axes.toString() }
        assertTrue("retransmission_ratio" in axes) { axes.toString() }
        // роза нормирована по обоим вариантам — состав уходит наружу
        assertEquals(2, view["radar"]["normalizedOver"].size()) { ok.body() }
        // подписи показателей — русские, из реестра направлений
        assertEquals("Доставка A′", view["axisLabels"]["delivery_a_prime"].asText()) { ok.body() }
    }
}
