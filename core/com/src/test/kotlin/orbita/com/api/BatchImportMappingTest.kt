// Г-01 (ПМИ-3): пачка из чужого проекта вставляется через сопоставление, а
// не правкой JSON руками. Подтверждённая карта применяется до разбора строк;
// изоляция не ослабляется — записывается ссылка на объект ЭТОГО проекта.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
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
class BatchImportMappingTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)

    @BeforeEach
    fun clean() {
        TestDb.truncateAll()
        boundary.ingest(
            CoreType.Project,
            """{"id":"PJ-2401","name":"Сопоставление","phase":"pre_phase_a",
                "milestones":[{"gate":"MCR","due":"2026-12-01"}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2401",
        )
        boundary.ingest(
            CoreType.Need,
            """{"id":"ND-0001","statement":"Передавать координаты ТС не реже раза в 30 с",
                "stakeholder":{"name":"Минтранс России","role":"customer"},
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2401",
        )
    }

    @Test
    fun `карта сопоставления переписывает чужую ссылку на объект этого проекта`() {
        val payload = mapper.readTree(
            """{"author":"Чернов",
                "link_mapping":{"ND-0777":"ND-0001"},
                "objects":[{"id":"RQ-0001","level":"project","category":"functional",
                            "statement":"Система должна передавать координаты ТС не реже раза в 30 с.",
                            "traces_up":[{"ref":"ND-0777"}],
                            "verification_events":[{"id":"VE-0001","method":"analysis","phase":"PhaseA","level":"system",
                                                    "kind":"qualification","status":"planned","closes":true,"design_version":"v1"}],
                            "owner":"ведущий системный инженер",
                            "lifecycle":{"status":"Draft","version":"1"}}]}""",
        )
        val report = BatchImport(boundary, mapper).import(payload, "Чернов", "PJ-2401")
        assertTrue(report.ok) { report.problems.toString() }
        val требование = boundary.objects.current("RQ-0001")!!
        assertEquals(listOf("ND-0001"), требование.doc.path("traces_up").map { it.path("ref").asText() }) {
            "ссылка стала ссылкой этого проекта: ${требование.doc}"
        }
    }
}
