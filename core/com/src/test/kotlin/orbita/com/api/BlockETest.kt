// Блок E: объём приходит пачками — акцепт предложений ИИ пачкой (порядок
// разрешает сервер, всё или ничего, происхождение ИИ с акцептором на каждом
// объекте) и массовое действие реестра — перевод статуса пачкой с отчётом
// о непереведённых поимённо.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.schema.SchemaRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BlockETest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val server = HttpApi(boundary).start(0)
    private val base = "http://127.0.0.1:${server.address.port}/api"
    private val client = HttpClient.newHttpClient()

    @BeforeEach
    fun clean() {
        TestDb.truncateAll()
        boundary.ingest(
            orbita.mod.model.CoreType.Project,
            """{"id":"PJ-1101","name":"Объём","phase":"pre_phase_a",
                "milestones":[{"gate":"internal_review"}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
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
    fun `пачка предложений принимается одним действием, порядок разрешает сервер`() {
        // требование раньше нужды, на которую ссылается, — порядок за сервером
        val r = post(
            "/ai/accept-batch",
            """{"by":"Чернов Д.","package_id":"PP-e2e","llm":"claude",
                "items":[
                  {"id":"RQ-1101","level":"project","category":"performance",
                   "statement":"Система должна доставлять пакет за сутки.",
                   "traces_up":[{"ref":"ND-1101"}],"owner":"вед. системный инженер",
                   "verification_events":[],"lifecycle":{"status":"Draft","version":"1"}},
                  {"id":"ND-1101","statement":"Оператору нужен суточный сбор телеметрии.",
                   "stakeholder":{"name":"Оператор","role":"operator"},
                   "lifecycle":{"status":"Draft","version":"1"}}
                ]}""",
        )
        assertEquals(201, r.statusCode()) { r.body() }
        assertEquals(2, mapper.readTree(r.body())["written"].asInt())
        val stored = boundary.objects.current("RQ-1101")!!
        // происхождение ИИ с акцептором — на каждом принятом объекте (TZ-AI-004)
        assertEquals("ai_proposed", stored.doc.path("provenance").path("source").asText())
        assertEquals(true, stored.doc.path("provenance").path("ai").path("accepted").asBoolean())
        assertEquals("Чернов Д.", stored.doc.path("provenance").path("ai").path("accepted_by").asText())
        assertEquals("PJ-1101", stored.projectId)
    }

    @Test
    fun `битая строка пачки откатывает всё и называется поимённо`() {
        val r = post(
            "/ai/accept-batch",
            """{"by":"Чернов Д.","package_id":"PP-e2e","llm":"claude",
                "items":[
                  {"id":"ND-1102","statement":"Полная нужда.",
                   "stakeholder":{"name":"Оператор","role":"operator"},
                   "lifecycle":{"status":"Draft","version":"1"}},
                  {"id":"ND-1103","stakeholder":{"name":"Оператор","role":"operator"}}
                ]}""",
        )
        assertEquals(422, r.statusCode()) { r.body() }
        assertTrue("ND-1103" in r.body()) { r.body() }
        assertEquals(null, boundary.objects.current("ND-1102"))
    }

    @Test
    fun `перевод статуса пачкой - непереведённые поимённо, переведённые остаются`() {
        post(
            "/objects/need",
            """{"id":"ND-1104","statement":"Готовая нужда.",
                "stakeholder":{"name":"Оператор","role":"operator"},
                "lifecycle":{"status":"Draft","version":"1"}}""",
        )
        // требование без метода верификации в Baseline не переводится
        post(
            "/objects/requirement",
            """{"id":"RQ-1102","level":"project","category":"performance",
                "statement":"Система должна работать TBD часов.",
                "traces_up":[{"ref":"ND-1104"}],"owner":"вед. системный инженер",
                "verification_events":[],"lifecycle":{"status":"Draft","version":"1"}}""",
        )
        val r = post(
            "/objects/promote-batch",
            """{"author":"Чернов Д.","status":"Baseline","ids":["ND-1104","RQ-1102"]}""",
        )
        assertEquals(200, r.statusCode()) { r.body() }
        val n = mapper.readTree(r.body())
        assertEquals(listOf("ND-1104"), n["promoted"].map { it.asText() })
        assertEquals("RQ-1102", n["failed"][0]["id"].asText())
        assertTrue(n["failed"][0]["reason"].asText().isNotBlank())
        assertEquals("Baseline", boundary.objects.current("ND-1104")!!.status.name)
    }
}
