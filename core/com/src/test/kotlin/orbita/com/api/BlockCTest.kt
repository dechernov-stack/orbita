// Блок C (Шаг 17) — один в один с spec/block_c_semantics.py, путём данных:
// хранимый объект → маршрут → ответ.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.schema.SchemaRegistry
import orbita.mod.store.ModelViolationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BlockCTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val server = HttpApi(boundary).start(0)
    private val base = "http://127.0.0.1:${server.address.port}/api"
    private val client = HttpClient.newHttpClient()

    @BeforeAll
    fun seed() {
        TestDb.truncateAll()
        // ADR-022: контейнер прежде содержимого
        boundary.ingest(
            orbita.mod.model.CoreType.Project,
            """{"id":"PJ-0001","name":"Тестовый проект","phase":"phase_a",
                "milestones":[{"gate":"SRR"},{"gate":"SDR"}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
        )
        boundary.req.ingestNeed(
            """{"id":"ND-0701","statement":"Сбор показаний датчиков без наземной связи.",
                "stakeholder":{"name":"Оператор","role":"operator"},
                "lifecycle":{"status":"Draft","version":"1"}}""",
        )
    }

    @AfterAll
    fun stop() = server.stop(0)

    private fun post(path: String, body: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create("$base$path"))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun get(path: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create("$base$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun conopsJson(id: String) = """
        {"id":"$id","name":"Номинальный сбор телеметрии","kind":"nominal",
         "phase":"operations","flow":["терминал накапливает пакет","КА принимает в зоне"],
         "success_criterion":"пакет доставлен за сутки","traces_up":["ND-0701"],
         "lifecycle":{"status":"Draft","version":"1"}}"""

    @Test
    fun `сценарий conops создаётся и разворачивает нужду связью`() {
        val r = post("/objects/conops", conopsJson("CO-0701"))
        assertEquals(201, r.statusCode()) { r.body() }
        assertEquals(listOf("ND-0701"), boundary.links.linksTo("CO-0701", "trace").map { it.fromId })
    }

    @Test
    fun `валидация против несуществующего сценария отклоняется`() {
        val r = post(
            "/objects/validation",
            """{"id":"VA-0701","target":"ND-0701","conops_ref":"CO-9999",
                "product_kind":"model","method":"demonstration","status":"planned",
                "provenance":{"source":"manual"}}""",
        )
        assertEquals(422, r.statusCode()) { r.body() }
        assertTrue(r.body().contains("CO-9999")) { r.body() }
    }

    @Test
    fun `документ conops наполняет раздел операционных сценариев из хранимых`() {
        post("/objects/conops", conopsJson("CO-0702"))
        val r = get("/export/documents/conops")
        assertEquals(200, r.statusCode())
        val sections = mapper.readTree(r.body())["body"]["sections"]
        val section4 = sections.first { it["number"].asInt() == 4 }
        assertTrue(section4["items"].any { it["id"].asText() == "CO-0702" }) { section4.toString() }
    }

    @Test
    fun `технология ниже требуемого TRL к своей точке блокирует зрелость`() {
        post(
            "/objects/technology",
            """{"id":"TL-0701","name":"Бортовой демодулятор","trl_current":3,
                "trl_required":5,"gate":"SRR","lifecycle":{"status":"Draft","version":"1"}}""",
        )
        val r = mapper.readTree(get("/reports/maturity?gate=SRR").body())
        assertTrue(r["gaps_by_type"].has("technology")) { r.toString() }
        assertTrue(r["blocking"].any { it.asText().startsWith("TRL технологий") })
        // чужая точка — чужой срок: TRL-разрыва к SDR нет; статусный разрыв
        // (Draft ниже требуемого реестром точек) — отдельная, законная причина
        val sdr = mapper.readTree(get("/reports/maturity?gate=SDR").body())
        assertTrue(
            !sdr["gaps_by_type"].has("technology") ||
                sdr["gaps_by_type"]["technology"].none {
                    it["id"].asText() == "TL-0701" && it["actual"].asText().startsWith("TRL")
                },
        )
    }

    @Test
    fun `решение decided без обоснования отклоняется, полное принимается`() {
        val bad = post(
            "/objects/decision",
            """{"id":"DN-0701","question":"Построение группировки",
                "alternatives":[{"name":"Walker 8/2"},{"name":"ССО 6/3"}],
                "status":"decided","selected":"Walker 8/2",
                "lifecycle":{"status":"Draft","version":"1"}}""",
        )
        assertEquals(422, bad.statusCode()) { bad.body() }
        val good = post(
            "/objects/decision",
            """{"id":"DN-0702","question":"Построение группировки",
                "alternatives":[{"name":"Walker 8/2"},{"name":"ССО 6/3"}],
                "status":"decided","selected":"Walker 8/2","rationale":"покрытие выше при той же стоимости",
                "lifecycle":{"status":"Draft","version":"1"}}""",
        )
        assertEquals(201, good.statusCode()) { good.body() }
    }

    @Test
    fun `выпуск документа фиксирует слепок, правка модели делает его устаревшим`() {
        val issued = post(
            "/export/documents/req_spec/issue",
            """{"issued_at":"2026-08-22T00:00:00Z","author":"Чернов Д."}""",
        )
        assertEquals(201, issued.statusCode()) { issued.body() }

        var list = mapper.readTree(get("/export/documents/req_spec/issues").body())
        assertTrue(list["issues"].size() >= 1)
        assertTrue(list["issues"].all { !it["stale"].asBoolean() }) { list.toString() }

        // правка модели меняет слепок текущей генерации: требование входит
        // в раздел 3 спецификации требований
        boundary.req.ingestRequirement(
            """{"id":"RQ-0799","level":"system","category":"performance",
                "statement":"Новое требование сдвигает документ.",
                "traces_up":[{"ref":"ND-0701"}],"owner":"вед. системный инженер",
                "verification_events":[],"lifecycle":{"status":"Draft","version":"1"}}""",
        )
        list = mapper.readTree(get("/export/documents/req_spec/issues").body())
        assertTrue(list["issues"].all { it["stale"].asBoolean() }) { list.toString() }
    }

    @Test
    fun `выпуск сразу approved не бывает`() {
        val r = post(
            "/objects/document_issue",
            """{"id":"DI-0777","template":"req_spec","digest":"0123456789abcdef",
                "issued_at":"2026-08-22T00:00:00Z","status":"approved",
                "lifecycle":{"status":"Draft","version":"1"}}""",
        )
        assertEquals(422, r.statusCode()) { r.body() }
    }

    @Test
    fun `точки читаются из проекта контекста`() {
        // ADR-022: контекст запроса — единственный проект портфеля (PJ-0001
        // из фикстуры); случай пустого портфеля (source=registry) — в
        // ProjectContainerTest, где портфель действительно пуст
        val gates = mapper.readTree(get("/views/gates").body())
        assertEquals("project", gates["source"].asText())
        assertEquals("PJ-0001", gates["project_ref"].asText())
        assertEquals(listOf("SRR", "SDR"), gates["gates"].map { it["gate"].asText() })
    }
}
