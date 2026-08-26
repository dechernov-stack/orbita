// В3: многопользовательность. Мера волны: двое ведут один проект;
// специалист работает только в своих узлах; точку проходит только DA;
// конфликт одновременной правки разрешается без потери. Права — на сервере
// (ловушка 5); до первой учётки действует прежний режим.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.CoreType
import orbita.mod.schema.SchemaRegistry
import org.junit.jupiter.api.Assertions.assertEquals
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
class MultiUserTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val server = HttpApi(boundary).start(0)
    private val base = "http://127.0.0.1:${server.address.port}/api"
    private val client = HttpClient.newHttpClient()
    private val tokens = mutableMapOf<String, String>()

    @BeforeAll
    fun setup() {
        TestDb.truncateAll()
    }

    private fun send(method: String, path: String, body: String? = null, asUser: String? = null): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI.create("$base$path"))
            .header("Content-Type", "application/json; charset=utf-8")
        asUser?.let { b.header("Cookie", "orbita_session=${tokens[it]}") }
        val req = when (method) {
            "GET" -> b.GET()
            "PUT" -> b.PUT(HttpRequest.BodyPublishers.ofString(body ?: "", Charsets.UTF_8))
            "PATCH" -> b.method("PATCH", HttpRequest.BodyPublishers.ofString(body ?: "{}", Charsets.UTF_8))
            else -> b.POST(HttpRequest.BodyPublishers.ofString(body ?: "{}", Charsets.UTF_8))
        }.build()
        return client.send(req, HttpResponse.BodyHandlers.ofString())
    }

    private fun login(user: String, password: String) {
        val r = send("POST", "/auth/login", """{"login":"$user","password":"$password"}""")
        assertEquals(200, r.statusCode()) { r.body() }
        val cookie = r.headers().firstValue("Set-Cookie").orElseThrow()
        tokens[user] = cookie.substringAfter("orbita_session=").substringBefore(';')
    }

    @Test
    @Order(1)
    fun `до первой учётки прежний режим, первая регистрируется свободно`() {
        // запись работает без сессии — однопользовательский режим
        val open = send(
            "POST", "/edit/project",
            """{"author":"старый режим","doc":{"name":"Наш проект","phase":"pre_phase_a",
                "milestones":[{"gate":"internal_review"},{"gate":"MCR"}]}}""",
        )
        assertEquals(201, open.statusCode()) { open.body() }

        val reg = send(
            "POST", "/auth/register",
            """{"login":"lead","password":"строгий-пароль","display_name":"Ведущая О."}""",
        )
        assertEquals(201, reg.statusCode()) { reg.body() }
        // режим включился: без сессии — 401
        assertEquals(401, send("GET", "/objects?type=project").statusCode())
        login("lead", "строгий-пароль")
        // руководитель заводит остальных (двое с разных машин — разные сессии)
        listOf("se" to "Ведущий СИ", "spec" to "Специалист Б.", "da" to "DA-обзор").forEach { (l, n) ->
            // lead ещё без роли: создаст проект — станет lead в нём
            val rr = send(
                "POST", "/auth/register",
                """{"login":"$l","password":"строгий-пароль","display_name":"$n"}""",
                asUser = "lead",
            )
            // первый register после включения требует lead-роли — заведём проект ниже
            if (rr.statusCode() != 201) {
                assertEquals(400, rr.statusCode()) { rr.body() }
            }
        }
        // lead создаёт проект под сессией — становится его руководителем
        val pj = send(
            "POST", "/edit/project",
            """{"doc":{"name":"Многопользовательский","phase":"pre_phase_a",
                "milestones":[{"gate":"internal_review"},{"gate":"MCR"}]}}""",
            asUser = "lead",
        )
        assertEquals(201, pj.statusCode()) { pj.body() }
        projectId = mapper.readTree(pj.body())["id"].asText()
        // теперь регистрация остальных руководителем проходит
        listOf("se" to "Ведущий СИ", "spec" to "Специалист Б.", "da" to "DA-обзор").forEach { (l, n) ->
            send(
                "POST", "/auth/register",
                """{"login":"$l","password":"строгий-пароль","display_name":"$n"}""",
                asUser = "lead",
            )
            login(l, "строгий-пароль")
        }
        // роли на проект
        listOf("se" to "lead_se", "spec" to "specialist", "da" to "da_review").forEach { (l, r) ->
            val rr = send(
                "POST", "/auth/roles",
                """{"project":"$projectId","login":"$l","role":"$r"}""",
                asUser = "lead",
            )
            assertEquals(200, rr.statusCode()) { rr.body() }
        }
    }

    private var projectId = ""
    private var nodeA = ""
    private var nodeB = ""

    @Test
    @Order(2)
    fun `специалист работает только в своих узлах, автор из учётки`() {
        // ведущий СИ заводит два узла: свой специалисту и чужой
        val a = send(
            "POST", "/edit/component?project=$projectId",
            """{"doc":{"name":"БЦВМ","kind":"subsystem","owner":"spec"}}""",
            asUser = "se",
        )
        assertEquals(201, a.statusCode()) { a.body() }
        nodeA = mapper.readTree(a.body())["id"].asText()
        val bResp = send(
            "POST", "/edit/component?project=$projectId",
            """{"doc":{"name":"СЭП","kind":"subsystem","owner":"se"}}""",
            asUser = "se",
        )
        nodeB = mapper.readTree(bResp.body())["id"].asText()

        // свой узел — правится; автор в провенансе — из учётки, не из тела
        val own = send(
            "PATCH", "/edit/$nodeA?project=$projectId",
            """{"author":"подделка","base_version":"1",
                "changes":{"name":"БЦВМ (уточнено)"}}""",
            asUser = "spec",
        )
        assertEquals(200, own.statusCode()) { own.body() }
        val stored = boundary.objects.current(nodeA)!!
        assertEquals("Специалист Б.", stored.createdBy)

        // чужой узел — отказ сервера, не спрятанная кнопка
        val foreign = send(
            "PATCH", "/edit/$nodeB?project=$projectId",
            """{"author":"x","base_version":"1","changes":{"name":"взлом"}}""",
            asUser = "spec",
        )
        assertEquals(403, foreign.statusCode()) { foreign.body() }
        assertTrue("принадлежит se" in foreign.body()) { foreign.body() }
    }

    @Test
    @Order(3)
    fun `точку фиксирует только DA`() {
        val bySpec = send(
            "POST", "/gates/internal_review/pass?project=$projectId",
            """{"decision":"проходим"}""", asUser = "spec",
        )
        assertEquals(403, bySpec.statusCode()) { bySpec.body() }
        assertTrue("точку фиксирует DA" in bySpec.body())
        // DA — доходит до содержательной проверки готовности (не 403)
        val byDa = send(
            "POST", "/gates/internal_review/pass?project=$projectId",
            """{"decision":"проходим"}""", asUser = "da",
        )
        assertTrue(byDa.statusCode() != 403) { byDa.body() }
    }

    @Test
    @Order(4)
    fun `конфликт одновременной правки разрешается без потери`() {
        val v = boundary.objects.current(nodeA)!!.version
        // se и spec правят один узел от одной базовой версии
        val first = send(
            "PATCH", "/edit/$nodeA?project=$projectId",
            """{"base_version":"$v","changes":{"name":"БЦВМ-М"}}""",
            asUser = "se",
        )
        assertTrue(first.statusCode() != 403) { first.body() }
        val second = send(
            "PATCH", "/edit/$nodeA?project=$projectId",
            """{"base_version":"$v","changes":{"name":"БЦВМ-К"}}""",
            asUser = "spec",
        )
        // 409 несёт кто, что и какие поля — материал экрана конфликта
        assertEquals(409, second.statusCode()) { second.body() }
        val conflict = mapper.readTree(second.body())
        assertEquals("Ведущий СИ", conflict["changed_by"].asText())
        assertEquals("БЦВМ-М", conflict["their_values"]["name"].asText())
        // «наложить своё»: повтор с их версией — правка не потеряна
        val retry = send(
            "PATCH", "/edit/$nodeA?project=$projectId",
            """{"base_version":"${conflict["current_version"].asText()}",
                "changes":{"name":"БЦВМ-К"}}""",
            asUser = "spec",
        )
        assertEquals(200, retry.statusCode()) { retry.body() }
        assertEquals("БЦВМ-К", boundary.objects.current(nodeA)!!.doc.path("name").asText())
    }
}
