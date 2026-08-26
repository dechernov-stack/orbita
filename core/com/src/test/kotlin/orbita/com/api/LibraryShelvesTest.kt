// Библиотека §1 (ЗАДАЧА-CODE-БИБЛИОТЕКА): три формы хранения в области LIB;
// применение с обязательным обоснованием отклонения; связь «применяет»
// выводится из документа.
package orbita.com.api

import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.CoreType
import orbita.mod.schema.SchemaRegistry
import orbita.mod.store.ObjectStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LibraryShelvesTest {

    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val lib = ObjectStore.LIBRARY_PROJECT

    @BeforeAll
    fun setup() {
        TestDb.truncateAll()
        boundary.ingest(
            CoreType.Project,
            """{"id":"PJ-2001","name":"Приёмник","phase":"pre_phase_a",
                "milestones":[{"gate":"MCR"}],"lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2001",
        )
    }

    @Test
    fun `типизированные полки создаются в области библиотеки`() {
        val norm = boundary.ingest(
            CoreType.NormativeDocument,
            """{"id":"NR-0001","name":"О мониторинге перевозок опасных грузов","kind":"decree",
                "number":"ПП РФ № 2216","org":"Правительство РФ","edition_date":"2020-12-18",
                "in_force":"in_force",
                "clauses":[{"clause":"п. 6","text":"геопозиция ТС с опасным грузом не реже раза в 30 с"}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", lib,
        )
        assertEquals(lib, norm.projectId)

        val cls = boundary.ingest(
            CoreType.MissionClass,
            """{"id":"MC-0001","name":"НОО · связь и IoT",
                "typical_constraints":[{"code":"Р1","text":"Полезная нагрузка — только регенеративная."}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", lib,
        )
        assertEquals("mission_class", cls.type)

        boundary.ingest(
            CoreType.StakeholderProfile,
            """{"id":"SH-0001","name":"Минтранс России","role":"regulator",
                "regulatory_powers":"ОГ по ПП № 2216","mission_class_ref":"MC-0001",
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", lib,
        )
        boundary.ingest(
            CoreType.TypicalRisk,
            """{"id":"TR-0001","statement":"Если поставка ПН задержится — сдвиг лётной кампании — срыв срока развёртывания","category":"supply",
                "typical_mitigations":["второй поставщик"],"mission_class_ref":"MC-0001",
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", lib,
        )
        val frag = boundary.ingest(
            CoreType.LibraryFragment,
            """{"id":"LF-0001","name":"Типовые требования НОО","shelf":"B1",
                "mission_class_ref":"MC-0001","counters":{"requirement":1},
                "origin":{"project":"PJ-0000","author":"test","date":"2026-08-26"},
                "anonymized":true,
                "payload":{"objects":[{"id":"RQ-0001","statement":"Увод с орбиты за 25 лет"}]},
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", lib,
        )
        assertEquals("B1", frag.doc.path("shelf").asText())
    }

    @Test
    fun `отклонение без обоснования отклоняется схемой, применение даёт связь`() {
        // фрагмент-прототип
        boundary.objects.current("LF-0002") ?: boundary.ingest(
            CoreType.LibraryFragment,
            """{"id":"LF-0002","name":"Каркас","shelf":"B5",
                "origin":{"project":"PJ-0000","author":"test","date":"2026-08-26"},
                "payload":{"objects":[]},"lifecycle":{"status":"Draft","version":"1"}}""",
            "test", lib,
        )
        // отклонение без rationale — отказ
        assertThrows<Exception> {
            boundary.ingest(
                CoreType.Component,
                """{"id":"CM-0001","name":"БЦВМ","kind":"subsystem","applies":{"ref":"LF-0002","status":"deviation"},
                    "lifecycle":{"status":"Draft","version":"1"}}""",
                "test", "PJ-2001",
            )
        }
        // применение — связь «применяет» выводится из документа
        val cm = boundary.editing.create(
            CoreType.Component,
            com.fasterxml.jackson.databind.ObjectMapper().readTree(
                """{"name":"БЦВМ","kind":"subsystem","applies":{"ref":"LF-0002","status":"applied"}}"""
            ),
            "test", "PJ-2001",
        )
        boundary.req.syncLinks(cm.type, cm.id, cm.doc, cm.projectId)
        val applied = boundary.links.linksFrom(cm.id, "applies")
        assertEquals(1, applied.size)
        assertEquals("LF-0002", applied[0].toId)
        assertTrue(boundary.objects.current(applied[0].toId)!!.projectId == lib)
    }
}
