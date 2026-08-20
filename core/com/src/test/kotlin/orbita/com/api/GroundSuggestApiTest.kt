// Рекомендательное размещение станций по HTTP (шаг 12.1).
// Ручные станции демо-проекта не переписываются; предложение помечено.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.schema.SchemaRegistry
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GroundSuggestApiTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val server = HttpApi(boundary).start(0)
    private val base = "http://127.0.0.1:${server.address.port}/api"
    private val client = HttpClient.newHttpClient()

    @BeforeAll
    fun seed() {
        TestDb.truncateAll()
        DemoProject.seed(boundary)
    }

    @AfterAll
    fun stop() = server.stop(0)

    private fun post(path: String, body: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create("$base$path"))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    @DisplayName("§12.1: предложение поверх ручных станций демо-проекта")
    fun `предложение не переписывает ручное`() {
        val r = post(
            "/ground/suggest",
            """{"k":1,"inclination_deg":53.0,"alt_km":550.0,"candidates":[
                {"id":"GS-CAND-1","name":"Владивосток","lat_deg":43.1,"lon_deg":131.9},
                {"id":"GS-CAND-2","name":"Химки (рядом с Москвой)","lat_deg":55.9,"lon_deg":37.4}
            ]}""",
        )
        assertEquals(200, r.statusCode())
        val body = mapper.readTree(r.body())
        // все три ручные станции демо-проекта сохранены
        assertEquals(3, body.path("manual_kept").asInt())
        assertEquals(1, body.path("suggested").size())
        val suggestion = body.path("suggested")[0]
        assertEquals("suggested", suggestion.path("placement").asText())
        // жадный выбор берёт наибольший прирост: Владивосток, а не сосед Москвы
        assertEquals("GS-CAND-1", suggestion.path("id").asText())
        assertTrue(body.path("coverage_after").asDouble() >= body.path("coverage_before").asDouble())
    }
}
