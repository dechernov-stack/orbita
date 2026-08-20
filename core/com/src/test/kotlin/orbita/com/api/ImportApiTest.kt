// Импорт по HTTP (шаг 14, ADR-024): по одной записи, черновик с замечаниями,
// повторный импорт — обновление, источник без правового режима — отказ.
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
class ImportApiTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val server = HttpApi(boundary).start(0)
    private val base = "http://127.0.0.1:${server.address.port}/api"
    private val client = HttpClient.newHttpClient()

    @BeforeAll
    fun reset() = TestDb.truncateAll()

    @AfterAll
    fun stop() = server.stop(0)

    private fun post(path: String, body: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create("$base$path"))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private val record = """
        {"source":"lorawan-devices","dataset_version":"2026-08-01",
         "retrieved_at":"2026-08-20","item_ref":"vendor/x/dev-a",
         "device":{"name":"Sensor A","battery":"AA"},
         "profile":{"macVersion":"1.0.3","maxEIRP":14,"region":"EU863-870","supportsClassC":false}}
    """

    @Test
    @DisplayName("шаг 14: запись каталога отображается в черновик с происхождением")
    fun `импорт одной записи даёт черновик`() {
        val r = post("/import/terminal-profile", record)
        assertEquals(200, r.statusCode())
        val body = mapper.readTree(r.body())
        assertEquals("added", body.path("action").asText())
        val draft = body.path("draft")
        assertEquals("A_prime", draft.path("consumer_class").asText())
        assertEquals("EU868", draft.path("regulatory_region").asText())
        assertEquals("imported", draft.path("provenance").path("source").asText())
        assertTrue("sui generis" in draft.path("provenance").path("import").path("terms").asText())
        // источник не знает параметров генерации — черновик, а не хранимый объект
        assertTrue(!draft.has("generation"))
    }

    @Test
    @DisplayName("шаг 14: источник без описанного правового режима отклоняется")
    fun `неописанный источник отклонён`() {
        val r = post("/import/terminal-profile", record.replace("lorawan-devices", "random-catalog"))
        assertEquals(422, r.statusCode())
        assertTrue("правовой режим неизвестен" in mapper.readTree(r.body()).path("error").asText())
    }

    @Test
    @DisplayName("шаг 14: запись с непонятым регионом возвращается с замечанием, не с догадкой")
    fun `непонятый регион — замечание`() {
        val r = post("/import/terminal-profile", record.replace("EU863-870", "XX999"))
        assertEquals(200, r.statusCode())
        val body = mapper.readTree(r.body())
        assertTrue(body.path("draft").path("regulatory_region").isNull)
        assertTrue(body.path("issues").any { "регион" in it.asText() })
    }
}
