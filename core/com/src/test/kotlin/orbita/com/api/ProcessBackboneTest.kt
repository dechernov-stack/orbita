// Спина процесса (блок B, ADR-029) путём данных: проект движется по точкам
// проверкой, возвраты §5.1 ограничивают движение, состояние операций считает
// сервер. Сценарий закреплён порядком методов: пустой проект → материал →
// первая точка → возврат → вторая точка.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.schema.SchemaRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterAll
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
class ProcessBackboneTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val server = HttpApi(boundary).start(0)
    private val base = "http://127.0.0.1:${server.address.port}/api"
    private val client = HttpClient.newHttpClient()
    private val author = """"author":"Чернов Д.""""

    @BeforeAll
    fun clean() {
        TestDb.truncateAll()
        boundary.ingest(
            orbita.mod.model.CoreType.Project,
            """{"id":"PJ-1001","name":"Спина процесса","phase":"pre_phase_a",
                "milestones":[{"gate":"internal_review"},{"gate":"MCR"},{"gate":"KDP-A"}],
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

    private fun get(path: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create("$base$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    @Order(1)
    fun `пустой проект - точка не проходится, перечень называет операции`() {
        val r = post("/gates/internal_review/pass", """{$author,"rationale":"комплект готов"}""")
        assertEquals(409, r.statusCode()) { r.body() }
        val n = mapper.readTree(r.body())
        assertEquals(false, n["ready"].asBoolean())
        // отсутствие материала названо операцией, где он создаётся
        assertTrue(n["issues"].any { "О2" in it.asText() }) { r.body() }
        assertTrue(n["operations"].any { "О4" in it.asText() }) { r.body() }
    }

    @Test
    @Order(2)
    fun `состояние операций считает сервер - не начато или нечем измерить`() {
        val ops = mapper.readTree(get("/views/operations").body())
        assertEquals("pre_phase_a", ops["phase"].asText())
        assertEquals("internal_review", ops["next_gate"].asText())
        val byCode = ops["operations"].associateBy { it["code"].asText() }
        assertEquals("NotStarted", byCode["О2"]!!["state"].asText())
        // стоимость (О8) до блока C системой не измеряется — видимый пробел
        assertEquals("NotMeasurable", byCode["О8"]!!["state"].asText())
        assertEquals(15, ops["operations"].size())
    }

    @Test
    @Order(3)
    fun `материал внесён - внутренний обзор проходится и фиксируется решением`() {
        // выходы операций КТ-1 (О2–О7): черновики всех видов, что есть в системе
        listOf(
            "/objects/need" to """{"id":"ND-1001","statement":"Сбор телеметрии удалённых датчиков.",
                "stakeholder":{"name":"Оператор","role":"operator"},
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "/objects/component" to """{"id":"CM-1001","name":"КА","kind":"system",
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "/objects/requirement" to """{"id":"RQ-1001","level":"project","category":"performance",
                "statement":"Система должна доставлять пакет за сутки.",
                "traces_up":[{"ref":"ND-1001"}],"owner":"вед. системный инженер",
                "verification_events":[],"lifecycle":{"status":"Draft","version":"1"}}""",
            "/objects/conops" to """{"id":"CO-1001","name":"Номинальный сбор","kind":"nominal",
                "phase":"operations","flow":["терминал передаёт","КА принимает"],
                "success_criterion":"пакет доставлен","traces_up":["ND-1001"],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "/objects/technology" to """{"id":"TL-1001","name":"Демодулятор","trl_current":5,
                "trl_required":5,"gate":"SRR","lifecycle":{"status":"Draft","version":"1"}}""",
            "/objects/risk" to """{"id":"RSK-1001",
                "statement":"При задержке поставки — срыв интеграции — сдвиг обзора",
                "category":"schedule","probability":2,"impact":2,
                "owner":"руководитель","status":"open"}""",
        ).forEach { (path, body) ->
            assertEquals(201, post(path, body).statusCode()) { "$path: ${post(path, body).body()}" }
        }

        val r = post("/gates/internal_review/pass", """{$author,"rationale":"комплект КТ-1 собран"}""")
        assertEquals(200, r.statusCode()) { r.body() }
        val n = mapper.readTree(r.body())
        assertTrue(n["passed"].asBoolean())
        assertTrue(n["decision"].asText().startsWith("DN-"))
        assertEquals("MCR", n["next_gate"].asText())
        // решение зафиксировано объектом, веха held
        val decision = boundary.objects.current(n["decision"].asText())!!
        assertEquals("Approve", decision.doc.path("selected").asText())
        val project = boundary.objects.current("PJ-1001")!!
        assertTrue(project.doc.path("milestones")[0].path("held").asBoolean())
    }

    @Test
    @Order(4)
    fun `перескочить точку нельзя - только ближайшая непройденная`() {
        val r = post("/gates/KDP-A/pass", """{$author,"rationale":"хотим сразу"}""")
        assertEquals(422, r.statusCode()) { r.body() }
        assertTrue("MCR" in r.body()) { r.body() }
    }

    @Test
    @Order(5)
    fun `возврат ограничивает движение, цели - только из параграфа 5-1`() {
        // недопустимая цель отклоняется
        val bad = post("/gates/MCR/return", """{$author,"reason":"концепция сырая","to":["О9"]}""")
        assertEquals(400, bad.statusCode()) { bad.body() }

        val r = post("/gates/MCR/return", """{$author,"reason":"концепция требует пересмотра","to":["О3"]}""")
        assertEquals(200, r.statusCode()) { r.body() }

        // операции возврата помечены
        val ops = mapper.readTree(get("/views/operations").body())
        val o3 = ops["operations"].first { it["code"].asText() == "О3" }
        assertTrue(o3["returned_to"].asBoolean()) { ops.toString() }

        // прохождение при действующем возврате отклоняется
        val blocked = post("/gates/MCR/pass", """{$author,"rationale":"готово"}""")
        assertEquals(422, blocked.statusCode()) { blocked.body() }
        assertTrue("возврат" in blocked.body()) { blocked.body() }

        // снятие с основанием открывает движение
        val resolved = post("/gates/return/resolve", """{$author,"note":"концепция пересмотрена в О3"}""")
        assertEquals(200, resolved.statusCode()) { resolved.body() }
    }

    @Test
    @Order(6)
    fun `MCR требует зрелости - перечень поимённо, после промоутов проходится`() {
        val notReady = post("/gates/MCR/pass", """{$author,"rationale":"зрелость набрана"}""")
        assertEquals(409, notReady.statusCode()) { notReady.body() }
        // нужда Draft ниже требуемого Approved — названа поимённо
        assertTrue(notReady.body().contains("ND-1001")) { notReady.body() }

        // довод зрелости: нужда → Approved, технология и риск → Preliminary
        listOf("ND-1001" to "Approved", "TL-1001" to "Preliminary", "RSK-1001" to "Preliminary")
            .forEach { (id, status) ->
                val p = post("/objects/$id/promote", """{"status":"$status"}""")
                assertEquals(200, p.statusCode()) { "$id: ${p.body()}" }
            }

        val r = post("/gates/MCR/pass", """{$author,"rationale":"концепция признана зрелой"}""")
        assertEquals(200, r.statusCode()) { r.body() }
        assertEquals("KDP-A", mapper.readTree(r.body())["next_gate"].asText())
    }
}
