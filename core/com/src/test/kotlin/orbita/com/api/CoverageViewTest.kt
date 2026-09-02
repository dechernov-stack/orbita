// Карта покрытия (шаг 16 §2.2): путь данных «хранимый объект → маршрут»,
// а не вызов функции напрямую — именно так шаг и принимается (§0).
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
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
class CoverageViewTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val server = HttpApi(boundary).start(0)
    private val base = "http://127.0.0.1:${server.address.port}/api"
    private val client = HttpClient.newHttpClient()

    @BeforeAll
    fun seed() {
        TestDb.truncateAll()
        boundary.objects.create(
            "CN-0900", "constellation",
            mapper.createObjectNode().apply {
                put("id", "CN-0900")
                putObject("walker").apply {
                    put("inclination_deg", 53.0)
                    put("total", 4)
                    put("planes", 2)
                    put("phasing", 1)
                    put("altitude_km", 550.0)
                }
            },
        )
        boundary.objects.create(
            "DM-0900", "demand_map",
            mapper.createObjectNode().apply {
                put("id", "DM-0900")
                putArray("cells").apply {
                    // средняя широта — покрывается наклонением 53°
                    addObject().put("cell_id", "cell-mid").put("lat_deg", 45.0).put("lon_deg", 20.0)
                    // полюс — вне трасс: ячейка обязана остаться на карте с классом gap
                    addObject().put("cell_id", "cell-pole").put("lat_deg", 89.0).put("lon_deg", 0.0)
                }
            },
        )
        boundary.objects.create(
            "SC-0900", "scenario",
            mapper.createObjectNode().apply {
                put("id", "SC-0900")
                put("constellation_ref", "CN-0900")
                put("demand_map_ref", "DM-0900")
                // БД требует все пять ссылок и версии входов (V008):
                // сценарий без них невоспроизводим и не принимается
                put("carrier_ref", "CU-0900")
                put("ground_stations_ref", "GS-0900")
                put("protocol_adapter_ref", "PA-0900")
                put("epoch", "2026-03-20T00:00:00.000Z")
                put("duration_s", 7200.0)
                putObject("input_versions").apply {
                    put("CN-0900", "1")
                    put("DM-0900", "1")
                    put("CU-0900", "1")
                    put("GS-0900", "1")
                    put("PA-0900", "1")
                }
            },
        )
    }

    @AfterAll
    fun stop() = server.stop(0)

    private fun get(path: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create("$base$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `без параметра scenario — 400 с объяснением, где взять сценарий`() {
        val r = get("/views/coverage")
        assertEquals(400, r.statusCode())
        assertTrue(r.body().contains("scenario")) { r.body() }
    }

    @Test
    fun `неизвестный горизонт — 400 с перечнем допустимых`() {
        val r = get("/views/coverage?scenario=SC-0900&horizon=week")
        assertEquals(400, r.statusCode())
        assertTrue(r.body().contains("orbit, day, run")) { r.body() }
    }

    @Test
    fun `отсутствующий сценарий — 409 с шагом мастера, а не 404`() {
        val r = get("/views/coverage?scenario=SC-9999")
        assertEquals(409, r.statusCode())
        val body = mapper.readTree(r.body())
        assertEquals(5, body["wizard_step"].asInt())
        assertTrue(body["error"].asText().contains("Входы моделирования")) { r.body() }
    }

    @Test
    fun `карта считается от хранимых объектов, непокрытая ячейка не исчезает`() {
        val r = get("/views/coverage?scenario=SC-0900&horizon=orbit")
        assertEquals(200, r.statusCode()) { r.body() }
        val body = mapper.readTree(r.body())
        assertEquals("SC-0900", body["scenario_ref"].asText())
        val cells = body["cells"].associateBy { it["cell_id"].asText() }
        // обе ячейки карты спроса на месте — включая ту, где пролётов нет
        assertEquals(setOf("cell-mid", "cell-pole"), cells.keys)
        val pole = cells.getValue("cell-pole") as ObjectNode
        assertEquals(0.0, pole["availability_mean"].asDouble())
        assertEquals("gap", pole["class"].asText())
        assertEquals(0, pole["access_windows"].asInt())
        // класс и значения приходят с сервера по каждой ячейке
        cells.values.forEach { c ->
            assertTrue(c.hasNonNull("availability_mean") && c.hasNonNull("availability_worst"))
            assertTrue(c["class"].asText() in setOf("ok", "degraded", "gap"))
        }
    }
}
