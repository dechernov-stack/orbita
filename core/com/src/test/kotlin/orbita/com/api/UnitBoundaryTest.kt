// Справочник единиц (решение ранга ADR) — меры: пакет с «30 min» ложится
// как 1800 s с происхождением; неизвестная единица («lb») — отказ с именем
// и предложением открыть справочник; курсовая без курса — отказ.
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
import java.nio.file.Path

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UnitBoundaryTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val importer = BatchImport(boundary)
    private val PROJECT = "PJ-1601"

    @BeforeEach
    fun clean() {
        TestDb.truncateAll()
        boundary.ingest(
            CoreType.Project,
            """{"id":"$PROJECT","name":"Единицы","phase":"pre_phase_a",
                "milestones":[{"gate":"MCR"}],"lifecycle":{"status":"Draft","version":"1"}}""",
            "test", PROJECT,
        )
        // справочник — сидом из репозитория, полка LIB (один на систему)
        val seed = mapper.readTree(
            Path.of(RepoPaths.repoRoot().toString(), "docs/tz/manual-run/packets/07-справочник-единиц.json").toFile(),
        )
        boundary.ingest(
            CoreType.UnitRegistry, mapper.writeValueAsString(seed.path("objects")[0]),
            "test", orbita.mod.store.ObjectStore.LIBRARY_PROJECT,
        )
    }

    private fun need(unitValue: String) = mapper.readTree(
        """{"author":"инженер","objects":[
             {"id":"ND-1601","statement":"Оператору нужен суточный сбор телеметрии.",
              "stakeholder":{"name":"Оператор","role":"operator"},
              "lifecycle":{"status":"Draft","version":"1"}},
             {"id":"RQ-1601","level":"project","category":"performance",
              "statement":"Система должна доставлять пакет за отведённый срок.",
              "traces_up":[{"ref":"ND-1601"}],"owner":"вед. системный инженер",
              "mop":{"name":"Срок доставки","operator":"le","value":$unitValue},
              "verification_events":[{"id":"VE-1601","method":"analysis","kind":"preliminary",
                "phase":"PhaseA","level":"system","status":"planned","closes":false,
                "approach":"расчётная проверка по имитационной модели потоков",
                "means":"модель Монте-Карло ядра"},
               {"id":"VE-1602","method":"test","kind":"qualification","phase":"PhaseC",
                "level":"system","status":"planned","closes":true,
                "approach":"квалификационные испытания на стенде радиолинии",
                "means":"испытательный стенд радиолинии","design_version":"К-1"}],
              "lifecycle":{"status":"Draft","version":"1"}}]}""",
    )

    @Test
    fun `30 min ложится как 1800 s с происхождением перевода`() {
        val report = importer.import(
            need("""{"value":30,"unit":"min","provenance":{"source":"manual"}}"""),
            "инженер", PROJECT,
        )
        assertEquals(2, report.written) { report.problems.toString() }
        val stored = boundary.objects.current("RQ-1601")!!
        val v = stored.doc.path("mop").path("value")
        assertEquals(1800.0, v.path("value").asDouble())
        assertEquals("s", v.path("unit").asText())
        assertEquals("30 min", v.path("provenance").path("converted_from").asText())
    }

    @Test
    fun `фунты - отказ с именем единицы и адресом справочника`() {
        val report = importer.import(
            need("""{"value":5,"unit":"lb","provenance":{"source":"manual"}}"""),
            "инженер", PROJECT,
        )
        assertEquals(0, report.written)
        val p = report.problems.single()
        assertEquals("unit_unknown", p.rule)
        assertTrue("'lb'" in p.message && "Справочник единиц" in p.message) { p.message }
        // тихой строки мимо словаря нет — вся пачка не записана
        assertEquals(null, boundary.objects.current("RQ-1601"))
    }

    @Test
    fun `курсовая единица без курса - отказ, не тихая конверсия`() {
        val report = importer.import(
            need("""{"value":2,"unit":"MUSD","provenance":{"source":"manual"}}"""),
            "инженер", PROJECT,
        )
        assertEquals(0, report.written)
        assertEquals("unit_rate", report.problems.single().rule)
        assertTrue("курс" in report.problems.single().message)
    }

    @Test
    fun `канон и лог-единицы проходят как есть`() {
        val report = importer.import(
            need("""{"value":9.5,"unit":"dBm","provenance":{"source":"manual"}}"""),
            "инженер", PROJECT,
        )
        assertEquals(2, report.written) { report.problems.toString() }
        val v = boundary.objects.current("RQ-1601")!!.doc.path("mop").path("value")
        assertEquals("dBm", v.path("unit").asText())
        assertTrue(!v.path("provenance").has("converted_from"))
    }
}
