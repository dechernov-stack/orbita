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
    @Order(11)
    fun `носитель стратифицирован - сирота только системный, проектному нужна нужда, нужда без требования не покрыта`() {
        // системное без носителя — настоящий разрыв
        val rows0 = mapper.readTree(send("GET", "/views/requirement-tree?project=$project", asUser = "vera").body()).path("rows")
        val sysRow = rows0.firstOrNull { it.path("level").asText() == "system" && it.path("allocatedTo").isEmpty }
            ?: error("нет системного без носителя: " + rows0.map { it.path("id").asText() + "/" + it.path("level").asText() + "/" + it.path("allocatedTo").size() })
        assertTrue(sysRow.path("noCarrierGap").asBoolean()) { sysRow.toString() }

        // проектное без носителя — НЕ разрыв носителя; без trace — разрыв «без нужды»
        val nd = boundary.objects.listCurrent(project).first { it.type == "need" }.id
        val withNeed = send(
            "POST", "/edit/requirement?project=$project", asUser = "vera", body =
            requirementJson("Проект должен покрывать регион обслуживания целиком.", nd,
                extra = "" ).replaceFirst("\"level\":\"system\"", "\"level\":\"project\""),
        ).let { assertEquals(201, it.statusCode()) { it.body() }; mapper.readTree(it.body())["id"].asText() }
        val tree = mapper.readTree(send("GET", "/views/requirement-tree?project=$project", asUser = "vera").body())
        val prj = tree.path("rows").firstOrNull { it.path("id").asText() == withNeed }
            ?: error("создание проектного не отразилось: " + tree.path("rows").size())
        assertFalse(prj.path("noCarrierGap").asBoolean()) { "проектное не сирота: $prj" }
        assertFalse(prj.path("noNeedGap").asBoolean()) { "нужда указана: $prj" }

        // нужда с требованием (через что угодно) покрыта; свежая нужда — нет
        val uncovered = tree.path("needsUncovered").map { it.asText() }
        assertFalse(nd in uncovered) { "покрытая нужда не в списке: $uncovered" }
        val lonely = send(
            "POST", "/edit/need?project=$project", asUser = "vera", body =
            """{"author":"т","doc":{"statement":"Оператору нужна валидационная дыра для показа.",
                "stakeholder":{"name":"Оператор","role":"operator"}}}""",
        ).let { assertEquals(201, it.statusCode()) { it.body() }; mapper.readTree(it.body())["id"].asText() }
        val after = mapper.readTree(send("GET", "/views/requirement-tree?project=$project", asUser = "vera").body())
        assertTrue(lonely in after.path("needsUncovered").map { it.asText() }) { after.path("needsUncovered").toString() }

        // корень системы: единственное корневое вхождение — его определение
        assertTrue(after.path("systemRoot").isNull) { "вхождений нет — корня нет" }
        // корень — СВОЙ компонент: на «Полезной нагрузке» висит ручное
        // распределение, и симметрия отмены честно блокировала бы снятие
        val cm = send(
            "POST", "/edit/component?project=$project", asUser = "vera",
            body = """{"author":"т","doc":{"name":"Корень системы теста","kind":"system"}}""",
        ).let { assertEquals(201, it.statusCode()) { it.body() }; mapper.readTree(it.body())["id"].asText() }
        // мера ADR-031: базируем проектное ДО появления корня — после
        // автораспределения оно обязано остаться Baseline
        val prjId = boundary.objects.listCurrent(project)
            .first { it.type == "requirement" && it.doc.path("level").asText() == "project" }.id
        send("POST", "/objects/$prjId/promote", """{"status":"Baseline","author":"т"}""", asUser = "vera")
            .also { assertEquals(200, it.statusCode()) { it.body() } }
        send("POST", "/edit/component_usage?project=$project", asUser = "vera", body = """{"author":"т","doc":{"definition_ref":"$cm","quantity":1}}""")
            .also { assertEquals(201, it.statusCode()) { it.body() } }
        assertEquals(orbita.mod.model.Lifecycle.Baseline, boundary.objects.current(prjId)!!.status) {
            "автораспределение не понизило статус (ADR-031)"
        }
        val rooted = mapper.readTree(send("GET", "/views/requirement-tree?project=$project", asUser = "vera").body())
        assertEquals(cm, rooted.path("systemRoot").path("id").asText()) { rooted.path("systemRoot").toString() }

        // появление корня раздало носителя проектным — хранимо, auto_root,
        // сводной записью, автором действия (ОТВЕТЫ-Т1-ДОП §2)
        val prj2 = boundary.objects.listCurrent(project)
            .first { it.type == "requirement" && it.doc.path("level").asText() == "project" }
        val alloc = prj2.doc.path("allocated_to").first()
        assertEquals(cm, alloc.path("component").asText()) { prj2.doc.toString() }
        assertEquals("auto_root", alloc.path("provenance").path("source").asText())
        assertTrue(boundary.objects.history(prj2.id).last().changeRef!!.startsWith("Распределено на корень"))

        // симметрия: ручное распределение на корень блокирует снятие
        val (released, manual) = boundary.req.releaseAutoRoot(project, cm, "т")
        assertEquals(listOf(prj2.id), released) { "автосвязь снята: $released" }
        assertTrue(manual.isEmpty())
        assertTrue(
            boundary.objects.current(prj2.id)!!.doc.path("allocated_to").let { it.isEmpty || it.isMissingNode },
        )
    }

    @Test
    @Order(2)
    fun `пометы содержательные - инженерская правка горит, служебная двигает якорь, пересчёта нет`() {
        val id = boundary.objects.listCurrent(project)
            .first { it.type == "requirement" && it.doc.path("statement").asText().startsWith("Сухая масса") }.id
        send("POST", "/objects/$id/promote", """{"status":"Baseline","author":"т"}""")
            .also { assertEquals(200, it.statusCode()) { it.body() } }
        assertFalse(rowOf(id).path("changedAfterApproval").asBoolean()) { "базирование само по себе — не правка" }

        val change = { value: Double, author: String, ref: String ->
            val doc = boundary.objects.current(id)!!.doc.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
            (doc.path("mop").path("value") as com.fasterxml.jackson.databind.node.ObjectNode).put("value", value)
            send(
                "POST", "/objects/$id/change?project=$project",
                mapper.writeValueAsString(
                    mapper.createObjectNode().apply {
                        set<com.fasterxml.jackson.databind.JsonNode>("doc", doc)
                        put("change_ref", ref)
                        put("author", author)
                    },
                ),
            ).also { assertEquals(200, it.statusCode()) { it.body() } }
        }

        // инженерская правка показателя — содержательное изменение: горит
        change(90.0, "т", "пересчёт бюджета массы")
        var row = rowOf(id)
        assertTrue(row.path("changedAfterApproval").asBoolean()) { row.toString() }
        // механизма пересчёта нет — «пересчитан» честно молчит (РЕШЕНИЯ-Т1 §1.2)
        assertFalse(row.path("recalcAfterBaseline").asBoolean()) { row.toString() }

        // служебная правка (канонизация) двигает якорь — помета гаснет
        change(90.0, "system", "канонизация единиц")
        row = rowOf(id)
        assertFalse(row.path("changedAfterApproval").asBoolean()) { "техническая волна не горит: $row" }

        // следующая инженерская — снова горит против нового якоря
        change(85.0, "т", "ужесточение лимита")
        assertTrue(rowOf(id).path("changedAfterApproval").asBoolean())

        // ADR-031: правка НАСЛЕДУЕТ статус — объект остался базированным
        assertEquals(orbita.mod.model.Lifecycle.Baseline, boundary.objects.current(id)!!.status)
        // правка Baseline без основания — отказ
        val bare = boundary.objects.current(id)!!.doc
        val noRef = send(
            "POST", "/objects/$id/change?project=$project",
            mapper.writeValueAsString(mapper.createObjectNode().apply {
                set<com.fasterxml.jackson.databind.JsonNode>("doc", bare)
                put("author", "т")
            }),
        )
        assertEquals(409, noRef.statusCode()) { noRef.body() }
        // повторное базирование — подтверждение статуса — гасит помету (§2.4)
        send("POST", "/objects/$id/promote", """{"status":"Baseline","author":"т"}""")
            .also { assertEquals(200, it.statusCode()) { it.body() } }
        assertFalse(rowOf(id).path("changedAfterApproval").asBoolean()) { "повторное базирование гасит" }
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

    @Test
    @Order(13)
    fun `портфель одним запросом - строка несёт всё для решения куда идти`() {
        val r = send("GET", "/views/portfolio", asUser = "vera")
        assertEquals(200, r.statusCode()) { r.body() }
        val rows = mapper.readTree(r.body()).path("projects")
        assertTrue(rows.size() >= 1) { r.body() }
        val row = rows.first { it.path("id").asText() == project }
        assertEquals("Реестр Т-1", row.path("name").asText())
        assertEquals("Внутренний обзор", row.path("gate").path("label").asText()) { row.toString() }
        assertTrue(row.path("gate").path("open_count").isInt)
        assertTrue(row.path("last_activity").path("what").asText().isNotBlank()) { row.toString() }
        assertTrue(row.path("owner").asText().isNotBlank())
    }

    private fun login(user: String, password: String) {
        val r = send("POST", "/auth/login", """{"login":"$user","password":"$password"}""")
        assertEquals(200, r.statusCode()) { r.body() }
        val cookie = r.headers().firstValue("Set-Cookie").orElseThrow()
        tokens[user] = cookie.substringAfter("orbita_session=").substringBefore(';')
    }
}
