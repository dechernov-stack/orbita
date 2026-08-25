// Мастер-путь Ш2 «Взять из библиотеки» (конвейер экранов): исходные
// документы других проектов видны как библиотека, взятие — копия в текущий
// проект с провенансом imported; исходный документ не тронут.
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
class LibraryTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val server = HttpApi(boundary).start(0)
    private val base = "http://127.0.0.1:${server.address.port}/api"
    private val client = HttpClient.newHttpClient()

    @BeforeAll
    fun setup() {
        TestDb.truncateAll()
        listOf("PJ-1901" to "Первый", "PJ-1902" to "Второй").forEach { (id, nm) ->
            boundary.ingest(
                orbita.mod.model.CoreType.Project,
                """{"id":"$id","name":"$nm","phase":"pre_phase_a",
                    "milestones":[{"gate":"MCR"}],"lifecycle":{"status":"Draft","version":"1"}}""",
                "test", id,
            )
        }
        boundary.ingest(
            orbita.mod.model.CoreType.SourceDocument,
            """{"id":"SD-0001","name":"ПП РФ № 2216","kind":"standard","org":"Правительство РФ",
                "rights":"открытый нормативный акт","summary":"мониторинг опасных грузов",
                "text":"геопозиция ТС не реже раза в 30 с",
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1901",
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
    fun `документ чужого проекта виден библиотекой и берётся копией с провенансом`() {
        // из второго проекта библиотека показывает документ первого
        val lib = get("/views/library/source-documents?project=PJ-1902")
        assertEquals(200, lib.statusCode()) { lib.body() }
        val rows = mapper.readTree(lib.body())
        assertEquals(1, rows.size()) { lib.body() }
        assertEquals("SD-0001", rows[0]["id"].asText())
        assertEquals("PJ-1901", rows[0]["project"].asText())
        assertTrue(rows[0]["has_text"].asBoolean())

        // свой документ библиотекой не считается
        val own = get("/views/library/source-documents?project=PJ-1901")
        assertEquals(0, mapper.readTree(own.body()).size()) { own.body() }

        val take = post(
            "/views/library/take?project=PJ-1902",
            """{"author":"test","ids":["SD-0001"]}""",
        )
        assertEquals(201, take.statusCode()) { take.body() }
        val newId = mapper.readTree(take.body())["taken"][0]["id"].asText()
        assertTrue(newId != "SD-0001") { take.body() }

        val copy = boundary.objects.current(newId)!!
        assertEquals("PJ-1902", copy.projectId)
        assertEquals("imported", copy.doc.path("provenance").path("source").asText())
        val imp = copy.doc.path("provenance").path("import")
        assertTrue(imp.path("dataset").asText().contains("SD-0001")) { copy.doc.toString() }
        assertEquals("открытый нормативный акт", imp.path("terms").asText())
        // исходный документ не тронут
        assertEquals("1", boundary.objects.current("SD-0001")!!.version)
    }
}
