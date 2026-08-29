// Ф-01 (ПРОГОН-ФИКСЫ-01): порядок дат — по СКВОЗНОЙ ленте жизненного
// цикла, граница фаз держится тем же одним правилом сервера: вехи Pre-A <
// KDP-A ≤ вехи Phase A; отказ — с именами точек.
package orbita.com.api

import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.CoreType
import orbita.mod.schema.SchemaRegistry
import orbita.mod.store.ModelViolationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PhaseBoundaryDatesTest {

    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)

    @BeforeEach
    fun clean() = TestDb.truncateAll()

    private fun project(srr: String?, kdpA: String?) = """
        {"id":"PJ-1701","name":"Граница фаз","phase":"pre_phase_a",
         "milestones":[
           {"gate":"internal_review","due":"2026-08-30"},
           {"gate":"MCR","due":"2026-09-05"},
           {"gate":"KDP-A"${if (kdpA != null) ""","due":"$kdpA"""" else ""}},
           {"gate":"SRR"${if (srr != null) ""","due":"$srr"""" else ""}},
           {"gate":"SDR"},{"gate":"KDP-B"}],
         "lifecycle":{"status":"Draft","version":"1"}}"""

    @Test
    fun `веха Phase A раньше конца Pre-A - отказ с именами точек`() {
        val refusal = assertThrows<ModelViolationException> {
            boundary.ingest(CoreType.Project, project(srr = "2026-09-01", kdpA = "2026-09-18"), "test", "PJ-1701")
        }
        assertTrue("SRR" in refusal.message!! && "KDP-A" in refusal.message!!) { refusal.message }
        assertTrue("раньше" in refusal.message!!)
    }

    @Test
    fun `граница держится и через незаданную веху между фазами`() {
        // KDP-A без даты: SRR сравнивается с последней ЗАДАННОЙ вехой Pre-A (MCR)
        val refusal = assertThrows<ModelViolationException> {
            boundary.ingest(CoreType.Project, project(srr = "2026-09-01", kdpA = null), "test", "PJ-1701")
        }
        assertTrue("SRR" in refusal.message!! && "MCR" in refusal.message!!) { refusal.message }
    }

    @Test
    fun `опора календаря через границу фаз - первая точка Phase A открывается от KDP-A`() {
        boundary.ingest(CoreType.Project, project(srr = null, kdpA = "2026-09-18"), "test", "PJ-1701")
        val ops = orbita.req.Operations()
        // фазы точек знает реестр операций, не клиент
        assertEquals("pre_phase_a", ops.phaseOfGate("KDP-A"))
        assertEquals("phase_a", ops.phaseOfGate("SRR"))

        val lane = boundary.objects.current("PJ-1701")!!.doc.path("milestones")
        val gateNames = lane.map { it.path("gate").asText() }
        val srrAt = gateNames.indexOf("SRR")
        // первая точка новой фазы стоит сразу за последней точкой прежней:
        // от неё и открывается её календарь
        assertEquals("KDP-A", gateNames[srrAt - 1])
        assertEquals(
            "pre_phase_a",
            ops.phaseOfGate(gateNames[srrAt - 1]),
        ) { "опорой первой точки Phase A обязана быть точка Pre-A" }
    }

    @Test
    fun `равенство на границе законно - KDP-A и SRR в один день`() {
        val stored = boundary.ingest(
            CoreType.Project, project(srr = "2026-09-18", kdpA = "2026-09-18"), "test", "PJ-1701",
        )
        assertEquals("PJ-1701", stored.id)
    }
}
