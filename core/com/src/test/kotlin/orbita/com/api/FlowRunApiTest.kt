// Прогон потоков от хранимых объектов (TZ-FLW-001, находка живого прогона:
// ядро было не подключено к API — «прогон не выполнялся» было вечным
// состоянием). Мини-сценарий: две ячейки спроса, одна станция, walker 4/2.
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
class FlowRunApiTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val server = HttpApi(boundary).start(0)
    private val base = "http://127.0.0.1:${server.address.port}/api"
    private val client = HttpClient.newHttpClient()

    @BeforeAll
    fun setup() {
        TestDb.truncateAll()
        boundary.ingest(
            orbita.mod.model.CoreType.Project,
            """{"id":"PJ-1601","name":"Потоки","phase":"phase_a",
                "milestones":[{"gate":"SRR"}],"lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1601",
        )
        // входы демо-проекта: те же генераторы, что у сеятеля — вторых копий нет
        DemoProject.seedModelingInputsFor(boundary, "PJ-1601")
        boundary.ingest(
            orbita.mod.model.CoreType.Scenario,
            """{"id":"SC-1601","name":"Мини-прогон","epoch":"2026-03-20T00:00:00Z",
                "duration_s":21600,"rng_seed":7,"monte_carlo_runs":5,
                "delivery_mode":"store_and_forward",
                "constellation_ref":"CN-0001","spacecraft_ref":"SP-0001",
                "demand_map_ref":"DM-0001","ground_stations_ref":"GS-0001",
                "protocol_adapter_ref":"PA-0001",
                "input_versions":{"CN-0001":"1","DM-0001":"1"}}""",
            "test", "PJ-1601",
        )
    }

    @AfterAll
    fun stop() = server.stop(0)

    private fun post(path: String, body: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create("$base$path"))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `прогон от хранимых входов пишет результат, узкие места оживают`() {
        val r = post("/views/flows/run?project=PJ-1601", """{"scenario":"SC-1601"}""")
        assertEquals(201, r.statusCode()) { r.body() }
        val n = mapper.readTree(r.body())
        assertEquals(5, n["runs"].asInt())
        assertTrue(n["passes"].asInt() > 0) { r.body() }
        assertTrue(n["populations"].asInt() > 0) { r.body() }
        // результат в хранилище результатов, свежий
        val stored = boundary.results.activeForScenario("SC-1601", "flow")
        assertEquals(1, stored.size)
        assertTrue(stored[0].payload.path("load").path("offered_msgs").asDouble() > 0.0) { stored[0].payload.toString() }

        // повторный прогон устаревает прежний — активный всегда один
        val r2 = post("/views/flows/run?project=PJ-1601", """{"scenario":"SC-1601"}""")
        assertEquals(201, r2.statusCode()) { r2.body() }
        assertEquals(1, boundary.results.activeForScenario("SC-1601", "flow").size)

        // узкие места читают прогон: «не выполнялся» перестало быть вечным
        val b = client.send(
            HttpRequest.newBuilder(URI.create("$base/views/bottlenecks?scenario=SC-1601&project=PJ-1601")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, b.statusCode()) { b.body() }
        assertTrue(mapper.readTree(b.body())["executed"].asBoolean()) { b.body() }
    }
}
