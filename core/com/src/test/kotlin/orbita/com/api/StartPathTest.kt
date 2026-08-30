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
            // Ф-11: профиль умеет и то, что мастер предлагает сам, — иначе
            // «собрать замысел из документов» упиралось бы в настройку
            listOf("mission_to_goals", "mission_to_needs", "mission_intent_from_docs", "normative_to_candidates"),
            stored.doc.path("kinds").map { it.asText() },
        )

        val second = post("/views/start-path/profile?project=PJ-1801", """{"author":"test"}""")
        assertEquals(200, second.statusCode()) { second.body() }
        assertEquals(id, mapper.readTree(second.body())["id"].asText())
        val profiles = boundary.objects.listCurrent("PJ-1801").filter { it.type == "ai_profile" }
        assertEquals(1, profiles.size) { profiles.map { it.id }.toString() }
    }

    /**
     * Ф-11: шагов мастера четыре — параметры · библиотека и материалы ·
     * замысел · запуск ИИ. Замысел стоит ПОСЛЕ материалов, поэтому паспорт
     * обязан принимать шаг 4; пятого шага не существует.
     */
    @Test
    fun `путь знает четыре шага, пятого нет`() {
        val four = mapper.readTree(
            """{"id":"PJ-1802","name":"Порядок","phase":"pre_phase_a",
                "start_path":{"status":"in_progress","step":4},
                "milestones":[{"gate":"MCR"}],"lifecycle":{"status":"Draft","version":"1"}}""",
        )
        assertTrue(boundary.schemaProblems("core/project", four).isEmpty()) {
            "замысел стоит четвёртым шагом — паспорт обязан его принимать: " +
                boundary.schemaProblems("core/project", four).toString()
        }
        val five = mapper.readTree(
            """{"id":"PJ-1803","name":"Лишний","phase":"pre_phase_a",
                "start_path":{"status":"in_progress","step":5},
                "milestones":[{"gate":"MCR"}],"lifecycle":{"status":"Draft","version":"1"}}""",
        )
        assertTrue(boundary.schemaProblems("core/project", five).isNotEmpty()) {
            "шаг вне 1..4 обязан отсекаться схемой, а не жить в паспорте"
        }
    }

    /**
     * Ф-11 (продолжение по живому наблюдению владельца): «собрать замысел из
     * документов» отвечало 400 «нет профиля службы с видом …» — профиль
     * собирается на последнем шаге мастера, а замысел спрашивается раньше.
     * Операция, предложенная системой, не имеет права упереться в её же
     * настройку: профиль обеспечивается сам.
     */
    @Test
    fun `промпт замысла обеспечивает профиль сам, а не отказывает`() {
        val response = client.send(
            HttpRequest.newBuilder(URI.create("$base/views/mission-intent/prompt?project=PJ-1801"))
                .GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        // документов у фикстуры нет, поэтому промпт может быть пуст по данным,
        // но отказа «нет профиля службы с видом» быть не должно
        assertTrue("нет профиля службы с видом" !in response.body()) {
            "профиль обязан обеспечиваться системой: ${response.body().take(200)}"
        }
        val profiles = boundary.objects.listCurrent("PJ-1801").filter { it.type == "ai_profile" }
        assertTrue(profiles.isNotEmpty()) { "профиль обязан появиться сам" }
        assertTrue(
            profiles.any { p -> p.doc.path("kinds").any { it.asText() == MissionIntentDraft.KIND } },
        ) { "вид «${MissionIntentDraft.KIND}» обязан быть в профиле: ${profiles.map { it.doc.path("kinds") }}" }
    }
}

