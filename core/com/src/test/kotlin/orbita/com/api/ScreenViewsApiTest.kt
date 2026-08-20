// Экраны клиента получают ГОТОВЫЕ строки (STEP-6 §3.2, ловушка 5).
//
// Проверяется не только форма ответа, но и главное свойство: всё, что клиенту
// пришлось бы вычислять — глубина в дереве, свёртка бюджета, подпись единицы,
// критерий успеха, состояние верификации — приходит с сервера посчитанным.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.schema.SchemaRegistry
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScreenViewsApiTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val server = HttpApi(boundary).start(0)
    private val base = "http://127.0.0.1:${server.address.port}/api"
    private val client = HttpClient.newHttpClient()

    private fun get(path: String): HttpResponse<String> = client.send(
        HttpRequest.newBuilder(URI.create("$base$path")).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    @AfterAll
    fun stop() = server.stop(0)

    @BeforeAll
    fun seed() {
        TestDb.truncateAll()
        boundary.req.ingestNeed(
            """{"id":"ND-0001","statement":"Сбор данных с датчиков в удалённых районах.",
                "stakeholder":{"name":"Оператор","role":"operator","priority":2},
                "lifecycle":{"status":"Draft","version":"1"}}""",
        )
        boundary.req.ingestService(
            """{"id":"SV-0001","name":"Сбор телеметрии","traces_up":["ND-0001"],
                "qos_profiles":[{"consumer_class":"A_prime","moe":[{"id":"MOE-0001",
                  "name":"delivery_probability_daily",
                  "target":{"value":0.9,"unit":"1","provenance":{"source":"manual"}}}]}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
        )
        listOf(
            """{"id":"CM-0001","name":"Космический аппарат","kind":"system",
                "lifecycle":{"status":"Draft","version":"1"}}""",
            """{"id":"CM-0002","name":"Платформа","kind":"subsystem","parent":"CM-0001",
                "lifecycle":{"status":"Draft","version":"1"}}""",
        ).forEach { boundary.req.ingestComponent(it) }

        boundary.req.ingestRequirement(requirement("RQ-0100", "CM-0001", 100.0, "Масса КА", "VE-0100"))
        boundary.req.ingestRequirement(requirement("RQ-0101", "CM-0002", 60.0, "Масса платформы", "VE-0101", listOf("RQ-0100")))
        boundary.req.ingestRequirement(requirement("RQ-0102", "CM-0002", 30.0, "Масса ПН", "VE-0102", listOf("RQ-0100")))
        boundary.req.deriveAs("RQ-0100", "RQ-0101", "allocated")
        boundary.req.deriveAs("RQ-0100", "RQ-0102", "allocated")
    }

    private fun requirement(
        id: String,
        component: String,
        limit: Double,
        name: String,
        eventId: String,
        derivesFrom: List<String> = emptyList(),
    ) = """
        {"id":"$id","level":"system","category":"performance",
         "statement":"Сухая масса должна быть не более $limit кг.",
         "traces_up":[{"ref":"SV-0001","consumer_class":"A_prime"}],
         "derives_from":${derivesFrom.joinToString(",", "[", "]") { "\"" + it + "\"" }},
         "allocated_to":[{"component":"$component","kind":"full"}],
         "mop":{"name":"$name","operator":"le","rollup":"sum",
           "value":{"value":$limit,"unit":"kg","provenance":{"source":"manual"}}},
         "verification_events":[{"id":"$eventId","method":"test","phase":"PhaseD","level":"system",
           "kind":"qualification","approach":"Взвешивание собранного изделия на поверенных весах.",
           "means":"Весы поверенные","status":"planned","closes":true,"design_version":"v1"}],
         "lifecycle":{"status":"Draft","version":"1"},"owner":"вед. системный инженер"}
    """

    @Test
    @DisplayName("экран 3: дерево приходит с глубиной, условием и свёрткой")
    fun `дерево требований приходит готовым`() {
        val res = get("/views/requirement-tree")
        assertEquals(200, res.statusCode()) { res.body() }
        val body = mapper.readTree(res.body())

        assertEquals(listOf("RQ-0100"), body["roots"].map { it.asText() })
        val rows = body["rows"].associateBy { it["id"].asText() }

        // глубину клиенту считать не надо
        assertEquals(0, rows.getValue("RQ-0100")["depth"].asInt())
        assertEquals(1, rows.getValue("RQ-0101")["depth"].asInt())
        assertTrue(rows.getValue("RQ-0100")["hasChildren"].asBoolean())

        // условие — структурное значение с готовой строкой и подписью единицы
        val condition = rows.getValue("RQ-0101")["condition"]
        assertEquals("le", condition["operator"].asText())
        assertEquals("kg", condition["unit"].asText(), "в модели должен остаться код СИ")
        assertEquals("кг", condition["unitLabel"].asText(), "подпись подставлена на сервере")
        assertTrue(condition["rendered"].asText().isNotBlank())

        // свёртка — готовая полоса с сегментами и остатком
        val budget = rows.getValue("RQ-0100")["budget"]
        assertNotNull(budget)
        assertEquals(90.0, budget["used"].asDouble(), 1e-9)
        assertEquals(100.0, budget["limit"].asDouble(), 1e-9)
        assertEquals(10.0, budget["remaining"].asDouble(), 1e-9)
        assertTrue(budget["segments"].last()["reserve"].asBoolean(), "резерв добавлен сервером")
        assertTrue(!rows.getValue("RQ-0100")["budgetOverrun"].asBoolean())

        // у листа свёртки нет — и это отличается от «свёртка равна нулю»
        assertTrue(rows.getValue("RQ-0101")["budget"].isNull)
    }

    @Test
    @DisplayName("экран 3б: карточка несёт события верификации и критерий успеха")
    fun `карточка требования приходит готовой`() {
        val res = get("/views/requirements/RQ-0100")
        assertEquals(200, res.statusCode()) { res.body() }
        val card = mapper.readTree(res.body())

        assertEquals("RQ-0100", card["row"]["id"].asText())
        // критерий успеха выведен из условия сервером, а не собран в клиенте
        assertTrue(card["successCriterion"].asText().isNotBlank())
        assertTrue("кг" in card["successCriterion"].asText(), card["successCriterion"].asText())

        assertEquals(1, card["events"].size())
        val event = card["events"][0]
        assertEquals("test", event["method"].asText())
        assertTrue(event["closes"].asBoolean())
        assertTrue(event["issues"].isArray, "замечания к событию считает сервер")

        assertEquals(listOf("SV-0001"), card["sources"].map { it.asText() })
        assertEquals(listOf("CM-0001"), card["allocatedTo"].map { it.asText() })
    }

    @Test
    @DisplayName("экран 11: спецификация элемента несёт источник и прогресс V&V")
    fun `спецификация элемента приходит готовой`() {
        val res = get("/views/components/CM-0002")
        assertEquals(200, res.statusCode()) { res.body() }
        val spec = mapper.readTree(res.body())

        assertEquals("CM-0002", spec["componentId"].asText())
        val rows = spec["rows"].associateBy { it["id"].asText() }
        assertEquals(setOf("RQ-0101", "RQ-0102"), rows.keys)

        val row = rows.getValue("RQ-0101")
        assertEquals("RQ-0100", row["source"].asText(), "источник — родительское требование")
        assertEquals("allocated", row["derivationKind"].asText())
        assertEquals(0, row["eventsDone"].asInt())
        assertEquals(1, row["eventsTotal"].asInt())
        assertTrue(row["verificationState"].asText().isNotBlank())
        assertTrue(row["condition"]["rendered"].asText().isNotBlank())
    }

    @Test
    @DisplayName("подписи единиц отдаются таблицей: коды СИ остаются в модели")
    fun `таблица подписей единиц доступна клиенту`() {
        val res = get("/unit-labels")
        assertEquals(200, res.statusCode())
        val labels = mapper.readTree(res.body())
        assertEquals("кг", labels["kg"].asText())
        assertTrue(labels.size() > 1)
    }
}
