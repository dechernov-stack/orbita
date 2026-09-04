// Фаза проекта — не поле формы (находка 0.3 прогона 04.09): проект прогона
// оказался в Phase A, хотя создавался как Pre-A, и лента показывала чужой ряд
// точек. Фазу двигает только прохождение KDP-A уполномоченным: у решения есть
// основание и след в истории. Любой другой путь — правка паспорта, импорт,
// сид — отказывается с названной причиной.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.CoreType
import orbita.mod.schema.SchemaRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectPhaseTest {
    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val project = "PJ-2905"

    @BeforeEach
    fun setup() {
        TestDb.truncateAll()
        boundary.ingest(
            CoreType.Project,
            """{"id":"$project","name":"Фаза проекта","phase":"pre_phase_a",
                "milestones":[{"gate":"internal_review"},{"gate":"MCR"},{"gate":"KDP-A"},
                              {"gate":"SRR"},{"gate":"SDR"},{"gate":"KDP-B"}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", project,
        )
    }

    private fun фаза(): String =
        boundary.objects.current(project)!!.doc.path("phase").asText("")

    @Test
    fun `правка паспорта фазу не двигает - отказ называет обе фазы и путь`() {
        val cur = boundary.objects.current(project)!!
        val changes = mapper.createObjectNode().put("phase", "phase_a")
        val e = assertThrows(IllegalArgumentException::class.java) {
            boundary.editing.update(CoreType.Project, project, changes, cur.version, "Чернов Д.")
        }
        assertTrue(e.message!!.contains("только прохождением точки KDP-A")) { e.message }
        assertTrue(e.message!!.contains("pre_phase_a") && e.message!!.contains("phase_a")) { e.message }
        assertEquals("pre_phase_a", фаза()) { "фаза осталась прежней" }
    }

    @Test
    fun `правка паспорта без фазы проходит - запрет точечный, а не на весь паспорт`() {
        val cur = boundary.objects.current(project)!!
        val changes = mapper.createObjectNode().put("name", "Фаза проекта · переименован")
        boundary.editing.update(CoreType.Project, project, changes, cur.version, "Чернов Д.")
        assertEquals("pre_phase_a", фаза())
        assertEquals("Фаза проекта · переименован", boundary.objects.current(project)!!.doc.path("name").asText())
    }

    @Test
    fun `та же фаза в правке — не изменение, отказа нет`() {
        val cur = boundary.objects.current(project)!!
        val changes = mapper.createObjectNode().put("phase", "pre_phase_a").put("name", "Тот же этап")
        boundary.editing.update(CoreType.Project, project, changes, cur.version, "Чернов Д.")
        assertEquals("pre_phase_a", фаза())
    }

    @Test
    fun `прохождение KDP-A переводит в Phase A - решение несёт основание`() {
        // путь прохождения точки идёт своим каналом с основанием «прохождение
        // точки», и только он вправе записать фазу
        val cur = boundary.objects.current(project)!!
        val changes = mapper.createObjectNode().put("phase", "phase_a")
        boundary.editing.update(
            CoreType.Project, project, changes, cur.version, "Чернов Д.",
            changeRef = "DN-0001: прохождение точки KDP-A",
        )
        assertEquals("phase_a", фаза())
    }
}
