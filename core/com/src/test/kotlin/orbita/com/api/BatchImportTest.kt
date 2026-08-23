// Загрузка пачкой (блок A, ADR-024): проверка до записи, всё или ничего,
// порядок разрешает сервер, отчёт с путём до поля. Путём данных: HTTP → база.
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
class BatchImportTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val server = HttpApi(boundary).start(0)
    private val base = "http://127.0.0.1:${server.address.port}/api"
    private val client = HttpClient.newHttpClient()

    @BeforeEach
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

    // Пачка «в неудобном порядке»: сервис раньше нужды, требование раньше
    // сервиса — порядок вставки разрешает сервер, а не автор файла
    private fun batch(needStatement: String = "Наблюдение объектов без наземной инфраструктуры.") = """
        {"author":"Чернов Д.","objects":[
          {"id":"RQ-0901","level":"project","category":"performance",
           "statement":"Система должна доставлять пакет за сутки.",
           "traces_up":[{"ref":"SV-0901","consumer_class":"A_prime"}],
           "owner":"вед. системный инженер","verification_events":[],
           "lifecycle":{"status":"Draft","version":"1"}},
          {"id":"SV-0901","name":"Сбор телеметрии","traces_up":["ND-0901"],
           "qos_profiles":[{"consumer_class":"A_prime","moe":[
             {"id":"MOE-0001","name":"delivery_probability_daily",
              "target":{"value":0.9,"unit":"1","provenance":{"source":"manual"}}}]}],
           "lifecycle":{"status":"Draft","version":"1"}},
          {"id":"PJ-0901","name":"Импортированный проект","phase":"pre_phase_a",
           "milestones":[{"gate":"MCR"}],"lifecycle":{"status":"Draft","version":"1"}},
          {"id":"ND-0901","statement":"$needStatement",
           "stakeholder":{"name":"Оператор","role":"operator"},
           "lifecycle":{"status":"Draft","version":"1"}}
        ]}"""

    @Test
    fun `пачка с проектом грузится в пустой портфель, порядок разрешает сервер`() {
        val r = post("/import/objects", batch())
        assertEquals(201, r.statusCode()) { r.body() }
        assertEquals(4, mapper.readTree(r.body())["written"].asInt())
        // все в контейнере пачки, связи выведены из документов
        assertEquals("PJ-0901", boundary.objects.current("RQ-0901")?.projectId)
        assertEquals(listOf("ND-0901"), boundary.links.linksTo("SV-0901", "trace").map { it.fromId })
    }

    @Test
    fun `ошибка схемы - отчёт с путём до поля, ничего не записано`() {
        val broken = batch().replace(""""statement":"Система должна доставлять пакет за сутки.",""", "")
        val r = post("/import/objects", broken)
        assertEquals(422, r.statusCode()) { r.body() }
        val problems = mapper.readTree(r.body())["problems"]
        assertTrue(problems.any { it["id"].asText() == "RQ-0901" && "statement" in it["message"].asText() }) {
            r.body()
        }
        assertEquals(null, boundary.objects.current("ND-0901"))
    }

    @Test
    fun `неразрешимая ссылка - отказ всей пачки, откат`() {
        // требование распределено на элемент, которого нет ни в пачке, ни в базе
        val r = post(
            "/import/objects",
            batch().replace(
                """"owner":"вед. системный инженер",""",
                """"allocated_to":[{"component":"CM-9999","kind":"full",
                    "rationale":"нет такого элемента"}],
                   "owner":"вед. системный инженер",""",
            ),
        )
        assertEquals(422, r.statusCode()) { r.body() }
        assertTrue("CM-9999" in r.body()) { r.body() }
        // всё или ничего: даже независимые строки пачки не записаны
        assertEquals(null, boundary.objects.current("ND-0901"))
        assertEquals(null, boundary.objects.current("PJ-0901"))
    }

    @Test
    fun `выгрузка тем же форматом грузится обратно`() {
        assertEquals(201, post("/import/objects", batch()).statusCode())
        val exported = get("/export/objects?project=PJ-0901")
        assertEquals(200, exported.statusCode())

        TestDb.truncateAll()
        val again = post("/import/objects", """{"author":"Чернов Д.",${exported.body().removePrefix("{")}""")
        assertEquals(201, again.statusCode()) { again.body() }
        assertEquals("PJ-0901", boundary.objects.current("SV-0901")?.projectId)
    }
}
