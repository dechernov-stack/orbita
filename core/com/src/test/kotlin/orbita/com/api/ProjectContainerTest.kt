// Блок A задания «прогон до KDP B»: проект — контейнер (ADR-022).
// Путём данных: пустой портфель → создание проекта → изоляция проектов.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.schema.SchemaRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Сценарий разворачивается по шагам, поэтому порядок методов закреплён:
 * пустой портфель → первый проект → второй проект → изоляция.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ProjectContainerTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val server = HttpApi(boundary).start(0)
    private val base = "http://127.0.0.1:${server.address.port}/api"
    private val client = HttpClient.newHttpClient()

    @BeforeAll
    fun clean() = TestDb.truncateAll()

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

    private fun projectJson(id: String, name: String) =
        """{"id":"$id","name":"$name","phase":"pre_phase_a",
            "milestones":[{"gate":"internal_review"},{"gate":"MCR"},{"gate":"KDP-A"}],
            "lifecycle":{"status":"Draft","version":"1"}}"""

    private fun needJson(id: String) =
        """{"id":"$id","statement":"Наблюдение объектов без наземной инфраструктуры.",
            "stakeholder":{"name":"Оператор","role":"operator"},
            "lifecycle":{"status":"Draft","version":"1"}}"""

    @Test
    @Order(1)
    fun `пустой портфель - запись отклоняется с понятным действием, точки из реестра`() {
        val r = post("/objects/need", needJson("ND-0801"))
        assertEquals(400, r.statusCode()) { r.body() }
        assertTrue("создайте проект" in r.body()) { r.body() }

        val gates = mapper.readTree(get("/views/gates").body())
        assertEquals("registry", gates["source"].asText())
    }

    @Test
    @Order(2)
    fun `первый проект создаётся из интерфейса и становится контекстом`() {
        val created = post("/objects/project", projectJson("PJ-0801", "Проект A"))
        assertEquals(201, created.statusCode()) { created.body() }

        val stored = post("/objects/need", needJson("ND-0801"))
        assertEquals(201, stored.statusCode()) { stored.body() }
        assertEquals("PJ-0801", boundary.objects.current("ND-0801")?.projectId)
        // проект принадлежит сам себе (ADR-022)
        assertEquals("PJ-0801", boundary.objects.current("PJ-0801")?.projectId)
    }

    @Test
    @Order(3)
    fun `при двух проектах обращение без параметра - отказ с перечнем`() {
        assertEquals(201, post("/objects/project", projectJson("PJ-0802", "Проект B")).statusCode())

        val ambiguous = get("/views/needs")
        assertEquals(400, ambiguous.statusCode()) { ambiguous.body() }
        assertTrue("PJ-0801" in ambiguous.body() && "PJ-0802" in ambiguous.body()) { ambiguous.body() }

        val write = post("/objects/need", needJson("ND-0802"))
        assertEquals(400, write.statusCode()) { write.body() }
    }

    @Test
    @Order(4)
    fun `параметр project выбирает контейнер, списки не смешиваются`() {
        val r = post("/objects/need?project=PJ-0802", needJson("ND-0802"))
        assertEquals(201, r.statusCode()) { r.body() }

        val a = mapper.readTree(get("/views/needs?project=PJ-0801").body()).map { it["id"].asText() }
        val b = mapper.readTree(get("/views/needs?project=PJ-0802").body()).map { it["id"].asText() }
        assertEquals(listOf("ND-0801"), a)
        assertEquals(listOf("ND-0802"), b)
    }

    @Test
    @Order(5)
    fun `связь через границу проекта отклоняется поимённо`() {
        // сервис в PJ-0802 объявляет trace к нужде из PJ-0801
        val r = post(
            "/objects/service?project=PJ-0802",
            """{"id":"SV-0801","name":"Сбор телеметрии","traces_up":["ND-0801"],
                "qos_profiles":[{"consumer_class":"A_prime","moe":[
                  {"id":"MOE-0001","name":"delivery_probability_daily",
                   "target":{"value":0.9,"unit":"1",
                    "provenance":{"source":"manual"}}}]}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
        )
        assertEquals(422, r.statusCode()) { r.body() }
        assertTrue("ADR-022" in r.body()) { r.body() }
        assertTrue("ND-0801" in r.body()) { r.body() }
    }

    @Test
    @Order(6)
    fun `несуществующий проект в параметре - отказ`() {
        val r = get("/views/needs?project=PJ-9999")
        assertEquals(400, r.statusCode()) { r.body() }
    }
}
