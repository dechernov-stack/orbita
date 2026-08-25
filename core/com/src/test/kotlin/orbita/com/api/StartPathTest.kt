// Мастер-путь «Начало проекта», Ш3 (конвейер экранов): профиль службы
// собирается из ограничений паспорта на сервере. Повторная сборка обновляет
// профиль, а не плодит дубли.
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
class StartPathTest {

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
            """{"id":"PJ-1801","name":"Путь","phase":"pre_phase_a",
                "mission_class":"НОО · связь и IoT",
                "constraints":[
                  {"code":"Р1","text":"Полезная нагрузка — только регенеративная; bent-pipe не рассматривается."},
                  {"text":"Платформа не тяжелее 100 кг."}],
                "start_path":{"status":"in_progress","step":3},
                "milestones":[{"gate":"MCR"}],"lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1801",
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
    fun `профиль собирается из ограничений паспорта, повторная сборка обновляет`() {
        val first = post("/views/start-path/profile?project=PJ-1801", """{"author":"test"}""")
        assertEquals(201, first.statusCode()) { first.body() }
        val created = mapper.readTree(first.body())
        assertEquals(2, created["prohibitions"].asInt()) { first.body() }
        val id = created["id"].asText()

        val stored = boundary.objects.current(id)!!
        val texts = stored.doc.path("prohibitions").map { it.asText() }
        // код ограничения уходит в скобки; ограничение без кода — как есть
        assertTrue(texts.any { it.endsWith("(Р1)") }) { texts.toString() }
        assertTrue("Платформа не тяжелее 100 кг." in texts) { texts.toString() }
        assertEquals(
            listOf("mission_to_goals", "mission_to_needs"),
            stored.doc.path("kinds").map { it.asText() },
        )

        val second = post("/views/start-path/profile?project=PJ-1801", """{"author":"test"}""")
        assertEquals(200, second.statusCode()) { second.body() }
        assertEquals(id, mapper.readTree(second.body())["id"].asText())
        val profiles = boundary.objects.listCurrent("PJ-1801").filter { it.type == "ai_profile" }
        assertEquals(1, profiles.size) { profiles.map { it.id }.toString() }
    }
}
