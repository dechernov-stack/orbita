// Ф-12: проводник постановки. Владелец: «цели и нужды есть, а дальше маршрут
// не строится — с пустыми сервисами надо знать, что идти в Инструменты».
// Куда идти дальше, обязана знать система: цепочка считается сервером, и
// первое несделанное звено несёт приглашение с адресом действия.
package orbita.com.api

import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.CoreType
import orbita.mod.schema.SchemaRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StatementPathTest {

    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)

    private fun project(intent: String) = """
        {"id":"PJ-1906","name":"Путь постановки","phase":"pre_phase_a",
         $intent
         "milestones":[{"gate":"MCR"}],"lifecycle":{"status":"Draft","version":"1"}}"""

    @BeforeEach
    fun clean() = TestDb.truncateAll()

    @Test
    fun `пустой проект зовёт задать замысел, а не молчит`() {
        boundary.ingest(CoreType.Project, project(""), "test", "PJ-1906")
        val view = StatementPath.toJson(boundary, "PJ-1906")
        assertFalse(view.path("complete").asBoolean())
        assertEquals("intent", view.path("next").path("key").asText())
        assertEquals("startpath", view.path("next").path("screen").asText())
        assertTrue("заблокирована" in view.path("next").path("why").asText()) {
            "приглашение обязано объяснять, почему это следующий шаг"
        }
    }

    @Test
    fun `после целей и нужд следующим становится сервис — с видом операции`() {
        boundary.ingest(
            CoreType.Project,
            project(""""mission_intent":{"text":"Группировка IoT для логистики."},"""),
            "test", "PJ-1906",
        )
        boundary.ingest(
            CoreType.MissionGoal,
            """{"id":"MG-1901","kind":"goal","statement":"Отслеживаемость грузов",
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1906",
        )
        boundary.ingest(
            CoreType.Need,
            """{"id":"ND-1901","statement":"Перевозчику нужна телеметрия груза в пути",
                "stakeholder":{"name":"Перевозчик","role":"customer","priority":1},
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1906",
        )
        val view = StatementPath.toJson(boundary, "PJ-1906")
        val next = view.path("next")
        assertEquals("services", next.path("key").asText()) { "сервисы — первое несделанное звено" }
        assertEquals("needs_to_services", next.path("kind").asText()) {
            "проводник ведёт не просто на экран, а к нужной операции"
        }
        // сделанные звенья гаснут счётчиком, а не исчезают
        val links = view.path("links").associateBy { it.path("key").asText() }
        assertTrue(links.getValue("goals").path("done").asBoolean())
        assertEquals(1, links.getValue("needs").path("count").asInt())
        assertFalse(links.getValue("requirements").path("done").asBoolean())
    }

    @Test
    fun `пройденная цепочка не изобретает следующий шаг`() {
        boundary.ingest(
            CoreType.Project,
            project(""""mission_intent":{"text":"Группировка IoT."},"""),
            "test", "PJ-1906",
        )
        boundary.ingest(
            CoreType.MissionGoal,
            """{"id":"MG-1902","kind":"goal","statement":"Цель","lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1906",
        )
        boundary.ingest(
            CoreType.Need,
            """{"id":"ND-1902","statement":"Нужда стейкхолдера в телеметрии",
                "stakeholder":{"name":"Оператор","role":"operator","priority":1},
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1906",
        )
        boundary.ingest(
            CoreType.Service,
            """{"id":"SV-1901","name":"Телеметрия грузов","traces_up":["ND-1902"],
                "qos_profiles":[{"consumer_class":"A_prime","moe":[
                  {"id":"MOE-1901","name":"delivery_probability_daily",
                   "target":{"value":0.9,"unit":"1","provenance":{"source":"manual"}}}]}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1906",
        )
        boundary.ingest(
            CoreType.Requirement,
            """{"id":"RQ-1901","level":"system","statement":"Система должна передавать телеметрию.",
                "category":"functional","owner":"инженер","verification_events":[],
                "traces_up":[{"ref":"SV-1901","consumer_class":"A_prime"}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1906",
        )
        val view = StatementPath.toJson(boundary, "PJ-1906")
        assertTrue(view.path("complete").asBoolean()) { "цепочка пройдена: выдумывать шаг нельзя" }
        assertTrue(view.path("next").isMissingNode) { "следующего шага нет — и это состояние, а не пустота" }
    }
}
