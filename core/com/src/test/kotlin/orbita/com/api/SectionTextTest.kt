// В1.2: раздел документа = авторский текст + вставки. Текст хранится и
// версионируется; отпечаток вставок ставит сервер; расхождение при выпуске —
// помета «текст устарел» (второй вид пробела), не блокировка и не перезапись;
// выпуск фиксирует снимок целиком.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.CoreType
import orbita.mod.schema.SchemaRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SectionTextTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val server = HttpApi(boundary).start(0)
    private val base = "http://127.0.0.1:${server.address.port}/api"
    private val client = HttpClient.newHttpClient()

    @BeforeAll
    fun setup() {
        TestDb.truncateAll()
        DemoProject.seedTemplates(boundary)
        boundary.ingest(
            CoreType.Project,
            """{"id":"PJ-2201","name":"Тексты","phase":"pre_phase_a",
                "milestones":[{"gate":"MCR"}],"lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2201",
        )
    }

    private fun send(method: String, path: String, body: String? = null): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI.create("$base$path"))
            .header("Content-Type", "application/json; charset=utf-8")
        val req = when (method) {
            "GET" -> b.GET()
            "PUT" -> b.PUT(HttpRequest.BodyPublishers.ofString(body!!, Charsets.UTF_8))
            else -> b.POST(HttpRequest.BodyPublishers.ofString(body!!, Charsets.UTF_8))
        }.build()
        return client.send(req, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    @Order(1)
    fun `сохранённый текст входит в рендер, свежий отпечаток - пометы нет`() {
        val put = send(
            "PUT", "/export/documents/conops/sections/1/text?project=PJ-2201",
            """{"author":"инженер","text":"Система предназначена для сбора телеметрии IoT с territorio РФ."}""",
        )
        assertEquals(201, put.statusCode()) { put.body() }
        assertTrue(mapper.readTree(put.body())["inserts_fingerprint"].asText().isNotBlank())

        val doc = mapper.readTree(send("GET", "/export/documents/conops?project=PJ-2201").body())
        val sec1 = doc["body"]["sections"].first { it["number"].asInt() == 1 }
        assertTrue(sec1["text"].asText().startsWith("Система предназначена")) { sec1.toString() }
        val gaps = doc["gaps"].map { it["what"].asText() }
        assertFalse("текст устарел" in gaps) { gaps.toString() }
        // раздел с текстом без записей — больше не «раздел пуст»
        assertFalse(doc["gaps"].any { it["section"].asInt() == 1 && it["what"].asText() == "раздел пуст" })
    }

    @Test
    @Order(2)
    fun `модель уехала - помета появляется, пересохранение снимает`() {
        // вставки раздела 1 ConOps наполняются нуждами: заводим одну —
        // данные вставок уезжают из-под сохранённого текста
        boundary.ingest(
            CoreType.Need,
            """{"id":"ND-0001","statement":"Мониторинг перевозок опасных грузов на всей территории",
                "stakeholder":{"name":"Минтранс","role":"regulator"},
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2201",
        )
        val doc = mapper.readTree(send("GET", "/export/documents/conops?project=PJ-2201").body())
        val gaps = doc["gaps"].filter { it["what"].asText() == "текст устарел" }
        assertEquals(1, gaps.size) { doc["gaps"].toString() }
        assertEquals(1, gaps[0]["section"].asInt())

        val again = send(
            "PUT", "/export/documents/conops/sections/1/text?project=PJ-2201",
            """{"author":"инженер","text":"Система предназначена для сбора телеметрии IoT (уточнено после ND-0001)."}""",
        )
        assertEquals(200, again.statusCode()) { again.body() }
        val fresh = mapper.readTree(send("GET", "/export/documents/conops?project=PJ-2201").body())
        assertFalse(fresh["gaps"].any { it["what"].asText() == "текст устарел" })
    }

    @Test
    @Order(3)
    fun `выпуск фиксирует снимок текста и вставок`() {
        val issue = send(
            "POST", "/export/documents/conops/issue?project=PJ-2201",
            """{"issued_at":"2026-08-26","author":"инженер"}""",
        )
        assertEquals(201, issue.statusCode()) { issue.body() }
        val id = mapper.readTree(issue.body())["id"].asText()
        val stored = boundary.objects.current(id)!!
        val snapText = stored.doc.path("snapshot").path("sections")
            .first { it.path("number").asInt() == 1 }.path("text").asText()
        assertTrue(snapText.contains("уточнено после ND-0001")) { snapText }
        issueId = id
    }

    private var issueId = ""

    @Test
    @Order(4)
    fun `выпуск печатается docx и PDF со снимка`() {
        val docx = client.send(
            HttpRequest.newBuilder(
                URI.create("$base/export/documents/conops/print.docx?project=PJ-2201&issue=$issueId"),
            ).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        assertEquals(200, docx.statusCode())
        // docx — zip: магия PK
        assertEquals(0x50, docx.body()[0].toInt())
        assertEquals(0x4B, docx.body()[1].toInt())
        assertTrue(docx.body().size > 2000) { "docx ${docx.body().size} байт" }

        val pdf = client.send(
            HttpRequest.newBuilder(
                URI.create("$base/export/documents/conops/print.pdf?project=PJ-2201&issue=$issueId"),
            ).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        assertEquals(200, pdf.statusCode()) { String(pdf.body()).take(200) }
        assertEquals("%PDF", String(pdf.body(), 0, 4, Charsets.US_ASCII))
        assertTrue(pdf.body().size > 2000) { "pdf ${pdf.body().size} байт" }
    }
}
