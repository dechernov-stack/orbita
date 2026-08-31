// Чек-лист обзора (NASA SEH App. C): инспекция ЛЮДЕЙ — оговорённое
// исключение из закона «всё вычисляется». Отметка существует, но она несёт
// автора и время, а сам чек-лист живёт данными полки, не кодом.
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
class ReviewChecklistTest {

    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)

    @BeforeEach
    fun clean() {
        TestDb.truncateAll()
        boundary.ingest(
            CoreType.ReviewChecklist,
            """{"id":"RC-9001","name":"Чек внутреннего обзора","gate":"internal_review",
                "source":"NASA SEH App. C",
                "items":[
                  {"key":"necessity","title":"Что худшее случится, если требование убрать?",
                   "screen":"req","evidence":"реестр требований"},
                  {"key":"unambiguity","title":"Чтение вслух двумя инженерами даёт одно понимание",
                   "hint":"инспекция людей: машине не поручается","screen":"req"}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", ObjectStore.LIBRARY_PROJECT,
        )
        boundary.ingest(
            CoreType.Project,
            """{"id":"PJ-1940","name":"Инспекция","phase":"phase_a","milestones":[{"gate":"SRR"}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1940",
        )
    }

    @Test
    fun `чек-лист приходит с полки, а не из кода`() {
        val view = ReviewChecklist.view(boundary, "PJ-1940", "internal_review")
        assertEquals(1, view.path("checklists").size())
        assertEquals(2, view.path("total").asInt())
        assertEquals(0, view.path("checked").asInt())
        assertTrue("не начата" in view.path("summary").asText()) { view.path("summary").asText() }
        val пункт = view.path("checklists")[0].path("items")[0]
        assertEquals("req", пункт.path("screen").asText()) { "у пункта есть адрес: где смотреть" }
        assertFalse(пункт.path("checked").asBoolean())
    }

    @Test
    fun `отметка несёт автора и время — иначе это не решение, а надежда`() {
        val паспорт = boundary.objects.current("PJ-1940")!!
        val changes = com.fasterxml.jackson.databind.ObjectMapper().readTree(
            """{"review_checks":[{"checklist":"RC-9001","item":"necessity",
                "author":"вед. системный инженер","at":"2026-08-31",
                "note":"прочитаны все требования уровня проекта"}]}""",
        ) as com.fasterxml.jackson.databind.node.ObjectNode
        boundary.editing.update(CoreType.Project, "PJ-1940", changes, паспорт.version, "test")

        val view = ReviewChecklist.view(boundary, "PJ-1940", null)
        val пункты = view.path("checklists")[0].path("items")
        val отмеченный = пункты.first { it.path("key").asText() == "necessity" }
        assertTrue(отмеченный.path("checked").asBoolean())
        assertEquals("вед. системный инженер", отмеченный.path("author").asText())
        assertTrue("прочитаны" in отмеченный.path("note").asText()) { "замечание словами сохраняется" }
        assertEquals(1, view.path("checked").asInt())
        assertTrue("1 из 2" in view.path("summary").asText()) { view.path("summary").asText() }
    }

    @Test
    fun `чек-лист другой точки не подмешивается`() {
        val view = ReviewChecklist.view(boundary, "PJ-1940", "SRR")
        assertEquals(0, view.path("checklists").size())
        assertTrue("на полке нет" in view.path("summary").asText())
    }
}
