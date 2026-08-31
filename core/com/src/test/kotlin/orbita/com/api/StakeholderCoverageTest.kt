// Ф-13 (шип 3): матрица «стейкхолдер × нужды». Тройное состояние — заявлена
// (нужда есть, требования нет), покрыта (требование ссылается), закрыта
// (у требования есть закрывающее событие верификации). Края видимы:
// стейкхолдер без нужд и нужда без носителя не теряются молча.
package orbita.com.api

import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.CoreType
import orbita.mod.schema.SchemaRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StakeholderCoverageTest {

    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)

    @BeforeEach
    fun clean() {
        TestDb.truncateAll()
        boundary.ingest(
            CoreType.Project,
            """{"id":"PJ-1909","name":"Покрытие","phase":"pre_phase_a",
                "milestones":[{"gate":"MCR"}],"lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1909",
        )
        boundary.ingest(
            CoreType.Stakeholder,
            """{"id":"SK-1901","name":"Перевозчики опасных грузов","role":"consumer",
                "interest":"видеть груз в пути","lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1909",
        )
        boundary.ingest(
            CoreType.Stakeholder,
            """{"id":"SK-1902","name":"Поставщик платформы","role":"supplier",
                "supplies":["CM-1901"],"lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1909",
        )
    }

    private fun нужда(id: String, ref: String?) = boundary.ingest(
        CoreType.Need,
        """{"id":"$id","statement":"Нужда стейкхолдера номер $id в телеметрии",
            "stakeholder":{"name":"Перевозчик","role":"customer"},
            ${if (ref == null) "" else "\"stakeholder_ref\":\"$ref\","}
            "lifecycle":{"status":"Draft","version":"1"}}""",
        "test", "PJ-1909",
    )

    @Test
    fun `нужда без требования — заявлена, с требованием — покрыта`() {
        нужда("ND-1911", "SK-1901")
        нужда("ND-1912", "SK-1901")
        boundary.ingest(
            CoreType.Requirement,
            """{"id":"RQ-1911","level":"system","statement":"Система должна передавать телеметрию груза.",
                "category":"functional","owner":"инженер","verification_events":[],
                "traces_up":[{"ref":"ND-1911"}],"lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1909",
        )
        val view = StakeholderCoverage.toJson(boundary, "PJ-1909")
        assertEquals(1, view.path("covered").asInt())
        assertEquals(1, view.path("declared").asInt())
        val строка = view.path("rows").first { it.path("id").asText() == "SK-1901" }
        assertEquals(2, строка.path("needs").asInt())
        assertEquals(1, строка.path("covered").asInt()) { "покрыта одна из двух" }
        val покрытая = строка.path("items").first { it.path("id").asText() == "ND-1911" }
        assertEquals("covered", покрытая.path("state").asText())
        assertEquals("RQ-1911", покрытая.path("covered_by")[0].asText())
    }

    @Test
    fun `закрытая верификацией нужда отличается от просто покрытой`() {
        нужда("ND-1913", "SK-1901")
        boundary.ingest(
            CoreType.Requirement,
            """{"id":"RQ-1912","level":"system","statement":"Система должна закрывать нужду проверкой.",
                "category":"functional","owner":"инженер",
                "verification_events":[{"id":"VE-1901","method":"analysis","kind":"qualification",
                                        "level":"system","status":"passed","closes":true}],
                "traces_up":[{"ref":"ND-1913"}],"lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1909",
        )
        val view = StakeholderCoverage.toJson(boundary, "PJ-1909")
        assertEquals(1, view.path("verified").asInt()) { "третье состояние обязано отличаться от «покрыта»" }
    }

    @Test
    fun `край матрицы виден — нужда без носителя не теряется`() {
        нужда("ND-1914", null)
        val view = StakeholderCoverage.toJson(boundary, "PJ-1909")
        assertEquals(1, view.path("without_stakeholder").size())
        assertTrue("без носителя" in view.path("summary").asText()) { view.path("summary").asText() }
        val поставщик = view.path("rows").first { it.path("id").asText() == "SK-1902" }
        assertTrue(поставщик.path("empty_why").asText().isNotBlank()) {
            "стейкхолдер без нужд обязан объяснить пустоту, а не молчать"
        }
        assertEquals("CM-1901", поставщик.path("supplies")[0].path("id").asText()) {
            "поставщик связан с узлом состава"
        }
    }
}
