// ADR-049: StrictDoc-канал без службы отказывает честно (503 с причиной), а не
// отдаёт пустой файл; импорт в модель сам не пишет.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.CoreType
import orbita.mod.schema.SchemaRegistry
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
class SdocChannelTest {
    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val server = HttpApi(boundary).start(0)
    private val base = "http://127.0.0.1:${server.address.port}/api"
    private val client = HttpClient.newHttpClient()
    private val project = "PJ-2801"

    @BeforeAll
    fun setup() {
        TestDb.truncateAll()
        boundary.ingest(CoreType.Project, """{"id":"$project","name":"StrictDoc","phase":"pre_phase_a","milestones":[{"gate":"MCR"}],"lifecycle":{"status":"Draft","version":"1"}}""", "test", project)
    }

    private fun send(method: String, path: String, body: String = ""): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI.create("$base$path" + (if ("?" in path) "&" else "?") + "project=$project"))
        val req = if (method == "GET") b.GET().build() else b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build()
        return client.send(req, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `без службы StrictDoc канал отказывает с причиной, не заглушкой`() {
        assertTrue(System.getenv("ORBITA_STRICTDOC_URL").isNullOrBlank(), "тест ждёт выключенную службу")
        for ((m, p) in listOf("GET" to "/export/sdoc", "GET" to "/export/sdoc/reqif", "POST" to "/export/sdoc/baseline", "POST" to "/import/sdoc")) {
            val r = send(m, p, "{}")
            assertEquals(503, r.statusCode(), "$m $p: ${r.body()}")
            val j = mapper.readTree(r.body())
            assertTrue(j.path("error").asText().contains("ORBITA_STRICTDOC_URL"), r.body())
            assertEquals("ADR-049", j.path("adr").asText())
        }
    }
}
