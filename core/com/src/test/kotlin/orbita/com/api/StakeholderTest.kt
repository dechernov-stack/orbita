// Ф-13: стейкхолдер — сущность проекта. Владелец: нужды неявно связаны со
// стейкхолдерами, а объектов их в системе нет; круг шире потребителей —
// регуляторы, операторы, учреждаемые организации.
//
// Профиль (SH, полка А2) остаётся шаблоном класса миссии; стейкхолдер (SK) —
// факт проекта. Урожай документа кладёт его В ПРОЕКТ, а не обобщает молча в
// библиотеку: обобщение — отдельное решение инженера.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
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
class StakeholderTest {

    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val mapper = ObjectMapper()

    @BeforeEach
    fun clean() {
        TestDb.truncateAll()
        boundary.ingest(
            CoreType.Project,
            """{"id":"PJ-1907","name":"Стейкхолдеры","phase":"pre_phase_a",
                "milestones":[{"gate":"MCR"}],"lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1907",
        )
    }

    @Test
    fun `стейкхолдер проекта — объект со своим кодом SK`() {
        val stored = boundary.ingest(
            CoreType.Stakeholder,
            """{"id":"SK-0001","name":"Минтранс России","role":"customer",
                "interest":"единое оперативное управление транспортной инфраструктурой",
                "anchors":["b7"],"lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1907",
        )
        assertEquals("SK-0001", stored.id)
        assertEquals("stakeholder", stored.type)
        assertTrue(boundary.objects.current("SK-0001") != null) { "объект обязан лежать в проекте" }
    }

    @Test
    fun `учреждаемая сторона помечена и не выдаётся за действующую`() {
        val item = mapper.readTree(
            """{"class":"stakeholder","name":"Национальный центр IoT-мониторинга",
                "statement":"учреждается для эксплуатации группировки","block":"b12"}""",
        )
        val filled = mapper.readTree("""{"role":"established"}""")
        val doc = DocumentHarvest.objectOf(item, filled, "SD-0006", "1", "Записка", "2026-08-30")!!
        assertEquals("established", doc.path("role").asText())
        assertTrue(doc.path("establishes").asBoolean()) { "учреждаемая сторона обязана быть помечена" }
        assertTrue(doc.path("anchors").size() > 0) { "происхождение — якорями: ${doc.path("anchors")}" }
        assertTrue(boundary.schemaProblems("core/stakeholder", doc).none { "'id'" !in it.message && "'lifecycle'" !in it.message }) {
            boundary.schemaProblems("core/stakeholder", doc).toString()
        }
    }

    @Test
    fun `урожай кладёт стейкхолдера в проект, а не обобщает в профиль полки`() {
        val target = DocumentHarvest.TARGETS.getValue("stakeholder")
        assertEquals(CoreType.Stakeholder, target.type) {
            "кандидат стейкхолдера обязан ложиться объектом проекта: обобщение в профиль А2 — отдельное решение"
        }
        assertTrue("проект" in target.where) { target.where }
    }

    @Test
    fun `нужда умеет ссылаться на стейкхолдера-носителя`() {
        boundary.ingest(
            CoreType.Stakeholder,
            """{"id":"SK-0002","name":"Перевозчик","role":"consumer",
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1907",
        )
        val need = mapper.readTree(
            """{"id":"ND-1903","statement":"Перевозчику нужна телеметрия груза в пути",
                "stakeholder":{"name":"Перевозчик","role":"customer"},
                "stakeholder_ref":"SK-0002","lifecycle":{"status":"Draft","version":"1"}}""",
        )
        assertTrue(boundary.schemaProblems("core/need", need).isEmpty()) {
            boundary.schemaProblems("core/need", need).toString()
        }
        val wrong = mapper.readTree(
            """{"id":"ND-1904","statement":"Нужда со ссылкой не туда",
                "stakeholder":{"name":"Кто-то","role":"customer"},
                "stakeholder_ref":"SH-0001","lifecycle":{"status":"Draft","version":"1"}}""",
        )
        assertFalse(boundary.schemaProblems("core/need", wrong).isEmpty()) {
            "ссылка на профиль полки вместо стейкхолдера проекта не принимается"
        }
    }
}
