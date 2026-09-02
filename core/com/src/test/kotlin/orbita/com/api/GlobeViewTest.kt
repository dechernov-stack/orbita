// Глобус от модели проекта (шаг 16 §2.3): конфигурация, станции и ячейки —
// хранимые объекты по ссылкам сценария; умолчаний нет. Путь данных через HTTP.
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
class GlobeViewTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val server = HttpApi(boundary).start(0)
    private val base = "http://127.0.0.1:${server.address.port}/api"
    private val client = HttpClient.newHttpClient()

    @BeforeAll
    fun seed() {
        TestDb.truncateAll()
        boundary.objects.create(
            "CN-0901", "constellation",
            mapper.createObjectNode().apply {
                put("id", "CN-0901")
                putObject("walker").apply {
                    put("inclination_deg", 53.0)
                    put("total", 2)
                    put("planes", 1)
                    put("phasing", 0)
                    put("altitude_km", 550.0)
                }
            },
        )
        boundary.objects.create(
            "DM-0901", "demand_map",
            mapper.createObjectNode().apply {
                put("id", "DM-0901")
                putArray("cells").apply {
                    addObject().put("cell_id", "c-heavy").put("lat_deg", 45.0).put("lon_deg", 20.0)
                        .putArray("demand").addObject().put("weight", 2.0)
                    addObject().put("cell_id", "c-light").put("lat_deg", 15.0).put("lon_deg", 30.0)
                        .putArray("demand").addObject().put("weight", 1.0)
                }
            },
        )
        boundary.objects.create(
            "GS-0901", "ground_stations",
            mapper.createObjectNode().apply {
                put("id", "GS-0901")
                putArray("stations").addObject()
                    .put("id", "GS-01").put("name", "Мурманск")
                    .put("lat_deg", 68.9).put("lon_deg", 33.1)
            },
        )
        boundary.objects.create(
            "SC-0901", "scenario",
            mapper.createObjectNode().apply {
                put("id", "SC-0901")
                put("constellation_ref", "CN-0901")
                put("demand_map_ref", "DM-0901")
                put("ground_stations_ref", "GS-0901")
                put("carrier_ref", "CU-0901")
                put("protocol_adapter_ref", "PA-0901")
                put("epoch", "2026-03-20T00:00:00.000Z")
                // сутки, не пара часов: за 1,25 витка два аппарата одной плоскости
                // могут честно не пройти над целями, и расписание будет пусто
                put("duration_s", 86400.0)
                putObject("input_versions").apply {
                    put("CN-0901", "1"); put("DM-0901", "1"); put("GS-0901", "1")
                    put("CU-0901", "1"); put("PA-0901", "1")
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
    fun `без сценария — 400, умолчаний нет`() {
        val r = get("/views/globe")
        assertEquals(400, r.statusCode())
        assertTrue(r.body().contains("scenario")) { r.body() }
    }

    @Test
    fun `отсутствующий сценарий — 409 с шагом мастера`() {
        val r = get("/views/globe?scenario=SC-9999")
        assertEquals(409, r.statusCode())
        assertEquals(5, mapper.readTree(r.body())["wizard_step"].asInt())
    }

    @Test
    fun `поток собран от модели - трассы, зоны, станции, ячейки и расписание пролётов`() {
        val r = get("/views/globe?scenario=SC-0901")
        assertEquals(200, r.statusCode()) { r.body() }
        val body = mapper.readTree(r.body())
        assertEquals("SC-0901", body["scenario_ref"].asText())

        val ids = body["czml"].map { it["id"].asText() }
        // группировка из хранимой модели: 2 аппарата, не зашитые 8;
        // составное построение даёт префикс подгруппы (G1-SAT-…)
        assertEquals(2, ids.count { it.contains("SAT-") }) { ids.toString() }
        assertTrue("gs-GS-01" in ids) { "станции нет в потоке: $ids" }
        assertTrue("dm-c-heavy" in ids && "dm-c-light" in ids) { "ячеек спроса нет в потоке" }
        // зона обслуживания у каждого аппарата
        val sats = body["czml"].filter { it.has("ellipse") }
        assertEquals(2, sats.size) { "зоны обслуживания не у всех аппаратов" }

        // расписание пролётов: аппарат, цель, начало и конец в UTC, длительность
        val passes = body["passes"]
        assertTrue(passes.size() > 0) { "расписание пусто" }
        // обрезка не тихая: полное число объявлено
        assertEquals(body["passes_total"].asInt() > passes.size(), body["passes_truncated"].asBoolean())
        passes.forEach { p ->
            assertTrue(p.hasNonNull("satellite") && p.hasNonNull("target_ref"))
            assertTrue(p["start_utc"].asText().startsWith("2026-03-20T"))
            assertTrue(p["duration_s"].asDouble() > 0)
        }
        // отсортировано по началу: строка синхронна со шкалой времени
        val starts = passes.map { it["start_utc"].asText() }
        assertEquals(starts.sorted(), starts)
    }
}
