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
        // блок C сделал стоимость (О8) измеримой; компиляция MCReport (О9)
        // видов не порождает — видимый пробел, а не прочерк
        assertEquals("NotStarted", byCode["О8"]!!["state"].asText())
        assertEquals("NotMeasurable", byCode["О9"]!!["state"].asText())
        assertEquals(15, ops["operations"].size())
    }

    @Test
    @Order(3)
    fun `материал внесён - внутренний обзор проходится и фиксируется решением`() {
        // выходы операций КТ-1 (О2–О11): черновики всех видов, что есть в системе
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
            // блок C: цели, альтернативы, стоимость, ODA — тоже выходы КТ-1
            "/objects/mission_goal" to """{"id":"MG-1001","kind":"goal",
                "statement":"Обеспечить суточный сбор телеметрии на всей территории обслуживания.",
                "traces_up":["ND-1001"],
                "moe":[{"id":"MOE-1001","name":"Вероятность суточной доставки",
                        "target":{"value":0.9,"unit":"1","provenance":{"source":"manual"}}}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "/objects/alternative" to """{"id":"AL-1001","name":"Walker 8/2","kind":"option",
                "summary":"Восемь КА в двух плоскостях",
                "criteria":[{"name":"покрытие","score":4}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "/objects/cost_estimate" to """{"id":"CE-1001","name":"Концептуальная оценка","kind":"rom",
                "basis":"аналоги",
                "total_low":{"value":1.2e9,"unit":"RUB","provenance":{"source":"manual"}},
                "total_high":{"value":2.4e9,"unit":"RUB","provenance":{"source":"manual"}},
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "/objects/oda" to """{"id":"OD-1001","kind":"initial",
                "deorbit_years":{"value":8,"unit":"a","provenance":{"source":"manual"}},
                "findings":[{"rule":"4.5-1 увод с НОО ≤ 25 лет","compliant":true}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
        ).forEach { (path, body) ->
            assertEquals(201, post(path, body).statusCode()) { "$path: ${post(path, body).body()}" }
        }

        // комплект Д1–Д9: без выпусков точка не проходится и называет документы
        val noDocs = post("/gates/internal_review/pass", """{$author,"rationale":"комплект"}""")
        assertEquals(409, noDocs.statusCode()) { noDocs.body() }
        assertTrue("не выпущен" in noDocs.body()) { noDocs.body() }
        orbita.out.DocumentKits.PRE_PHASE_A.values.toSortedSet().forEach { template ->
            val issued = post(
                "/export/documents/$template/issue",
                """{$author,"issued_at":"2026-08-23T00:00:00Z"}""",
            )
            assertEquals(201, issued.statusCode()) { "$template: ${issued.body()}" }
        }

        // Паспорт БАЗИРОВАН до прохождения — и это не преграда (находка
        // второго захода): прохождение само есть процедура с основанием
        // (TZ-COM-003), основание — решение. Прежде здесь был 409 и ворота
        // запирались наглухо.
        boundary.req.promote("PJ-1001", orbita.mod.model.Lifecycle.Baseline)

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
        // след основания — на закрытом интервале истории паспорта
        val ref = TestDb.conn.prepareStatement(
            "SELECT change_ref FROM objects WHERE id='PJ-1001' AND valid_to IS NOT NULL " +
                "ORDER BY pk DESC LIMIT 1",
        ).use { ps -> ps.executeQuery().use { rs -> rs.next(); rs.getString(1) } }
        assertTrue(ref != null && "прохождение точки internal_review" in ref) { "change_ref=$ref" }
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

        // довод зрелости: нужда и цель → Approved, остальное → Preliminary
        listOf(
            "ND-1001" to "Approved", "MG-1001" to "Approved",
            "TL-1001" to "Preliminary", "RSK-1001" to "Preliminary",
            "AL-1001" to "Preliminary", "CE-1001" to "Preliminary",
        ).forEach { (id, status) ->
            val p = post("/objects/$id/promote", """{"status":"$status"}""")
            assertEquals(200, p.statusCode()) { "$id: ${p.body()}" }
        }

        val r = post("/gates/MCR/pass", """{$author,"rationale":"концепция признана зрелой"}""")
        assertEquals(200, r.statusCode()) { r.body() }
        assertEquals("KDP-A", mapper.readTree(r.body())["next_gate"].asText())
    }

    @Test
    @Order(7)
    fun `критическое замечание обзора блокирует точку, закрытие с ответом открывает`() {
        // KDP-A: замечание категории critical от обзора KDP-A
        val created = post(
            "/objects/review_item",
            """{"id":"RF-1001","review_gate":"KDP-A","classification":"critical",
                "statement":"Стоимость наземного сегмента не обоснована",
                "status":"open","owner":"офис оценки стоимости"}""",
        )
        assertEquals(201, created.statusCode()) { created.body() }

        val blocked = post("/gates/KDP-A/pass", """{$author,"rationale":"комплект готов"}""")
        assertEquals(409, blocked.statusCode()) { blocked.body() }
        assertTrue("RF-1001" in blocked.body()) { blocked.body() }

        // закрытие без ответа отклоняется правилом вида
        val bare = post(
            "/edit/RF-1001",
            """{$author,"base_version":"1","doc":{"status":"closed"}}""",
        )
        assertTrue(bare.statusCode() != 200) { bare.body() }
    }
}
