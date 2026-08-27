// Т-1 (О-12): реестр требований одним запросом — строка несёт носителя с
// именем, родителя RQ→RQ, вычисляемый вид и ФЛАГИ ПОМЕТ с сервера (клиент
// семантику не вычисляет); сохранённые виды — серверные объекты: личный
// не виден второй учётке, проектный виден.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
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
class ReqRegistryTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val server = HttpApi(boundary).start(0)
    private val base = "http://127.0.0.1:${server.address.port}/api"
    private val client = HttpClient.newHttpClient()
    private val tokens = mutableMapOf<String, String>()
    private lateinit var project: String

    @BeforeAll
    fun setup() {
        TestDb.truncateAll()
        val r = send(
            "POST", "/edit/project",
            """{"author":"т","doc":{"name":"Реестр Т-1","phase":"pre_phase_a",
                "milestones":[{"gate":"internal_review"},{"gate":"MCR"}]}}""",
        )
        assertEquals(201, r.statusCode()) { r.body() }
        project = mapper.readTree(r.body())["id"].asText()
    }

    private fun send(method: String, path: String, body: String? = null, asUser: String? = null): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI.create("$base$path"))
            .header("Content-Type", "application/json; charset=utf-8")
        asUser?.let { b.header("Cookie", "orbita_session=${tokens[it]}") }
        val req = when (method) {
            "GET" -> b.GET()
            "PATCH" -> b.method("PATCH", HttpRequest.BodyPublishers.ofString(body ?: "{}", Charsets.UTF_8))
            else -> b.POST(HttpRequest.BodyPublishers.ofString(body ?: "{}", Charsets.UTF_8))
        }.build()
        return client.send(req, HttpResponse.BodyHandlers.ofString())
    }

    private fun requirementJson(statement: String, traceRef: String, extra: String = ""): String =
        """{"author":"т","doc":{"level":"system","statement":"$statement",
            "category":"performance","owner":"инженер И.",
            "mop":{"name":"Показатель","operator":"le","rollup":"sum",
                   "value":{"value":100.0,"unit":"kg"}},
            "traces_up":[{"ref":"$traceRef"}],
            "verification_events":[
              {"id":"VE-0901","method":"analysis","kind":"preliminary","phase":"PhaseA",
               "level":"system","closes":false,"status":"planned",
               "approach":"Анализ сводного перечня масс с резервами зрелости.",
               "means":"Сводный перечень оборудования"},
              {"id":"VE-0902","method":"test","kind":"qualification","phase":"PhaseD",
               "level":"system","closes":true,"status":"planned","design_version":"v1",
               "approach":"Взвешивание собранного изделия с протоколом.",
               "means":"Весовой стенд"}]$extra}}"""

    private fun rowOf(id: String): com.fasterxml.jackson.databind.JsonNode {
        val r = send("GET", "/views/requirement-tree?project=$project")
        assertEquals(200, r.statusCode()) { r.body() }
        return mapper.readTree(r.body()).path("rows").first { it.path("id").asText() == id }
    }

    @Test
    @Order(1)
    fun `строка реестра несёт вид, носителя с именем, родителя и флаги помет`() {
        val nd = send(
            "POST", "/edit/need?project=$project",
            """{"author":"т","doc":{"statement":"Оператору нужен суточный сбор телеметрии региона.",
                "stakeholder":{"name":"Оператор","role":"operator"}}}""",
        ).let { assertEquals(201, it.statusCode()) { it.body() }; mapper.readTree(it.body())["id"].asText() }
        val cm = send(
            "POST", "/edit/component?project=$project",
            """{"author":"т","doc":{"name":"Полезная нагрузка теста","kind":"subsystem"}}""",
        ).let { assertEquals(201, it.statusCode()) { it.body() }; mapper.readTree(it.body())["id"].asText() }

        val a = send(
            "POST", "/edit/requirement?project=$project",
            requirementJson("Сухая масса изделия не должна превышать предельного значения.", nd),
        ).let { assertEquals(201, it.statusCode()) { it.body() }; mapper.readTree(it.body())["id"].asText() }
        val b = send(
            "POST", "/edit/requirement?project=$project",
            requirementJson(
                "Носитель должен выдерживать распределённую долю массы.", nd,
                extra = ""","derives_from":["$a"],
                    "allocated_to":[{"component":"$cm","kind":"full"}]""",
            ),
        ).let { assertEquals(201, it.statusCode()) { it.body() }; mapper.readTree(it.body())["id"].asText() }

        val rowA = rowOf(a)
        val rowB = rowOf(b)
        assertEquals("numeric", rowA.path("kind").asText()) { "mop есть — вид числовой" }
        assertTrue(rowA.path("hasChildren").asBoolean()) { rowA.toString() }
        assertTrue(rowA.path("parentId").isNull) { "корень без родителя: $rowA" }
        assertEquals(a, rowB.path("parentId").asText()) { rowB.toString() }
        assertEquals("Полезная нагрузка теста", rowB.path("carrierName").asText()) { rowB.toString() }
        assertEquals("инженер И.", rowB.path("owner").asText())
        assertEquals("manual", rowB.path("origin").asText())
        assertFalse(rowA.path("recalcAfterBaseline").asBoolean())
        assertFalse(rowA.path("changedAfterApproval").asBoolean())
        assertEquals(nd, rowA.path("sources").first().asText())
    }

    @Test
    @Order(2)
    fun `пометы считает сервер по истории - показатель после базирования и правка после утверждения`() {
        val id = boundary.objects.listCurrent(project)
            .first { it.type == "requirement" && it.doc.path("statement").asText().startsWith("Сухая масса") }.id
        send("POST", "/objects/$id/promote", """{"status":"Baseline","author":"т"}""")
            .also { assertEquals(200, it.statusCode()) { it.body() } }
        assertFalse(rowOf(id).path("changedAfterApproval").asBoolean()) { "базирование само по себе — не правка" }

        // правка с основанием: меняется показатель
        val doc = boundary.objects.current(id)!!.doc.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        (doc.path("mop").path("value") as com.fasterxml.jackson.databind.node.ObjectNode).put("value", 90.0)
        send(
            "POST", "/objects/$id/change?project=$project",
            mapper.writeValueAsString(
                mapper.createObjectNode().apply {
                    set<com.fasterxml.jackson.databind.JsonNode>("doc", doc)
                    put("change_ref", "пересчёт бюджета массы")
                    put("author", "т")
                },
            ),
        ).also { assertEquals(200, it.statusCode()) { it.body() } }

        val row = rowOf(id)
        assertTrue(row.path("recalcAfterBaseline").asBoolean()) { row.toString() }
        assertTrue(row.path("changedAfterApproval").asBoolean()) { row.toString() }
    }

    @Test
    @Order(3)
    fun `сохранённый вид создаётся и читается - до учёток без фильтра`() {
        val save = send(
            "POST", "/views/req-views?project=$project",
            """{"author":"т","doc":{"name":"Рабочий","section":"requirements","scope":"personal",
                "columns":[{"key":"id","on":true},{"key":"statement","on":true}],
                "sort":{"key":"mop","dir":"desc"},"grouping":"carrier","form":"tree"}}""",
        )
        assertEquals(201, save.statusCode()) { save.body() }
        val list = send("GET", "/views/req-views?project=$project")
        assertEquals(200, list.statusCode())
        val views = mapper.readTree(list.body()).path("views")
        assertEquals(1, views.size()) { list.body() }
        assertEquals("Рабочий", views[0].path("name").asText())
    }

    @Test
    @Order(4)
    fun `личный вид не виден второй учётке, проектный виден, вид переживает перезаход`() {
        send("POST", "/auth/register", """{"login":"vera","password":"строгий-пароль","display_name":"Вера И."}""")
            .also { assertEquals(201, it.statusCode()) { it.body() } }
        login("vera", "строгий-пароль")
        send("POST", "/auth/register", """{"login":"mark","password":"другой-пароль","display_name":"Марк С."}""", asUser = "vera")
            .also { assertEquals(201, it.statusCode()) { it.body() } }
        send("POST", "/auth/roles", """{"project":"$project","login":"mark","role":"lead_se"}""", asUser = "vera")
            .also { assertEquals(200, it.statusCode()) { it.body() } }
        login("mark", "другой-пароль")

        send(
            "POST", "/views/req-views?project=$project",
            """{"author":"Вера И.","doc":{"name":"Мой личный","section":"requirements","scope":"personal",
                "columns":[{"key":"id","on":true}],"form":"flat"}}""",
            asUser = "vera",
        ).also { assertEquals(201, it.statusCode()) { it.body() } }
        send(
            "POST", "/views/req-views?project=$project",
            """{"author":"Вера И.","doc":{"name":"К обзору","section":"requirements","scope":"project",
                "columns":[{"key":"id","on":true}],"form":"tree"}}""",
            asUser = "vera",
        ).also { assertEquals(201, it.statusCode()) { it.body() } }

        val mine = mapper.readTree(send("GET", "/views/req-views?project=$project", asUser = "vera").body()).path("views")
        val names = mine.map { it.path("name").asText() }
        assertTrue("Мой личный" in names && "К обзору" in names) { names.toString() }

        val other = mapper.readTree(send("GET", "/views/req-views?project=$project", asUser = "mark").body()).path("views")
        val otherNames = other.map { it.path("name").asText() }
        assertTrue("К обзору" in otherNames) { "проектный виден всем: $otherNames" }
        assertFalse("Мой личный" in otherNames) { "личный чужой скрыт: $otherNames" }
        // владельца проставил сервер, а не клиент
        assertTrue(mine.first { it.path("name").asText() == "Мой личный" }.path("owner_login").asText() == "vera")

        // перезаход: новая сессия видит то же
        login("vera", "строгий-пароль")
        val again = mapper.readTree(send("GET", "/views/req-views?project=$project", asUser = "vera").body()).path("views")
        assertTrue(again.any { it.path("name").asText() == "Мой личный" }) { "вид пережил перезаход" }
    }

    private fun login(user: String, password: String) {
        val r = send("POST", "/auth/login", """{"login":"$user","password":"$password"}""")
        assertEquals(200, r.statusCode()) { r.body() }
        val cookie = r.headers().firstValue("Set-Cookie").orElseThrow()
        tokens[user] = cookie.substringAfter("orbita_session=").substringBefore(';')
    }
}
