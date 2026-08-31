// «Работа фазы»: задачи регламента ведут инженера после мастера. Ловушки
// владельца, которые держит этот тест: ручных статусов нет (всё считается);
// второй готовности нет (разрывы — разрезом общей); ручных длительностей
// нет (окна — только от дат вех).
package orbita.com.api

import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.CoreType
import orbita.mod.schema.SchemaRegistry
import orbita.mod.store.ObjectStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PhaseWorkTest {

    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)

    private fun задача(
        id: String,
        order: Int,
        name: String,
        depends: String = "",
        input: String = "",
        выходВид: String = "mission_goal",
    ) = """
        {"id":"$id","phase":"pre_phase_a","order":$order,"name":"$name",
         "why":"Зачем эта задача — текстом с полки, а не из кода экрана.",
         ${if (depends.isBlank()) "" else "\"depends_on\":[\"$depends\"],"}
         ${if (input.isBlank()) "" else "\"input\":[$input],"}
         "steps":[{"title":"Собрать цели","hint":"служба соберёт по замыслу",
                   "screen":"aiservice","kind":"mission_to_goals",
                   "done_when":{"check":"objects","type":"mission_goal","label":"цели приняты"}}],
         "output":{"artifact":"Постановка","gate":"MCR","maturity":"draft",
                   "done_when":{"check":"objects","type":"$выходВид","min":1}},
         "lifecycle":{"status":"Draft","version":"1"}}"""

    @BeforeEach
    fun clean() {
        TestDb.truncateAll()
        boundary.ingest(
            CoreType.Project,
            """{"id":"PJ-1908","name":"Работа","phase":"pre_phase_a",
                "mission_intent":{"text":"Группировка IoT для логистики."},
                "milestones":[{"gate":"MCR","due":"2026-12-01"}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1908",
        )
        boundary.ingest(CoreType.PhaseTask, задача("PW-9001", 1, "Постановка"), "test", ObjectStore.LIBRARY_PROJECT)
        boundary.ingest(
            CoreType.PhaseTask,
            // у второй задачи СВОЙ выход: иначе она «выполнялась» бы вместе с первой
            задача("PW-9002", 2, "Концепция", depends = "PW-9001", выходВид = "decision"),
            "test", ObjectStore.LIBRARY_PROJECT,
        )
    }

    @Test
    fun `задача без входа доступна, зависимая ждёт предшественника ИМЕНЕМ`() {
        val view = PhaseWork.toJson(boundary, "PJ-1908")
        assertEquals(2, view.path("tasks").asInt())
        val items = view.path("items").associateBy { it.path("id").asText() }
        assertEquals("available", items.getValue("PW-9001").path("status").asText())
        val вторая = items.getValue("PW-9002")
        assertEquals("waiting", вторая.path("status").asText())
        assertTrue("Постановка" in вторая.path("waits_on").asText()) {
            "ожидание обязано называть, КОГО ждём: ${вторая.path("waits_on").asText()}"
        }
    }

    @Test
    fun `шаг гаснет сам, когда его условие выполнено — ручной отметки нет`() {
        boundary.ingest(
            CoreType.MissionGoal,
            """{"id":"MG-9101","kind":"goal","statement":"Отслеживаемость грузов",
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1908",
        )
        val items = PhaseWork.toJson(boundary, "PJ-1908").path("items").associateBy { it.path("id").asText() }
        val первая = items.getValue("PW-9001")
        assertEquals("done", первая.path("status").asText()) { "выход задачи готов — статус вычислен" }
        assertTrue(первая.path("steps")[0].path("done").asBoolean()) { "шаг погас сам" }
        // и зависимая ожила без единого клика: ждать ей больше некого
        val вторая = items.getValue("PW-9002")
        assertTrue(вторая.path("status").asText() != "waiting") {
            "предшественник выполнен — задача обязана ожить сама: ${вторая.path("status").asText()}"
        }
        assertFalse(вторая.has("waits_on")) { "ожидания больше нет" }
    }

    @Test
    fun `окно задачи берётся от даты вехи, а не вводится руками`() {
        val первая = PhaseWork.toJson(boundary, "PJ-1908").path("items")[0]
        assertEquals("2026-12-01", первая.path("end").asText()) {
            "конец окна — дата точки выхода из паспорта"
        }
        assertFalse(первая.has("duration")) { "длительностей у задачи не существует" }
        assertFalse(первая.has("progress")) { "процентов выполнения не существует" }
    }

    @Test
    fun `следующий шаг шапки — верхушка работы, а не выдумка`() {
        val view = PhaseWork.toJson(boundary, "PJ-1908")
        val next = view.path("next")
        assertEquals("PW-9001", next.path("task").asText())
        assertEquals("Собрать цели", next.path("step").asText())
        assertEquals("aiservice", next.path("screen").asText())
        assertEquals("mission_to_goals", next.path("kind").asText()) {
            "переход обязан вести к преднастроенной операции, а не просто на экран"
        }
    }
}
