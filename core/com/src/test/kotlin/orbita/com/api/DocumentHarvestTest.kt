// Д2, приёмка: урожай смыслового разбора — ЭТАЛОННЫЙ пакет владельца
// (пачка-1/ПАКЕТ-РАЗБОР-ЗАПИСКИ.json, шаг Б2 ПМИ) проходит нормативную
// схему, даёт эталонные счётчики по классам и раскладывается по адресам:
// стейкхолдеры на полку А2, ограничения в паспорт Р-кодом, суммы — каноном
// денег; нормативы без реквизитов НЕ создаются — это разрыв, не выдумка.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.CoreType
import orbita.mod.schema.SchemaRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentHarvestTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val batch = RepoPaths.repoRoot().resolve("docs/tz/manual-run-2/пачка-1")
    private val harvest by lazy {
        mapper.readTree(Files.readString(batch.resolve("ПАКЕТ-РАЗБОР-ЗАПИСКИ.json"))) as ObjectNode
    }

    @BeforeEach
    fun clean() {
        TestDb.truncateAll()
        boundary.ingest(
            CoreType.Project,
            """{"id":"PJ-1801","name":"Разбор записки","phase":"pre_phase_a",
                "constraints":[{"code":"Р1","text":"Полезная нагрузка — только регенеративная."}],
                "milestones":[{"gate":"MCR"}],"lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1801",
        )
    }

    private fun item(cls: String, at: Int = 0) =
        harvest.path("items").filter { it.path("class").asText() == cls }[at]

    @Test
    fun `эталонный пакет владельца проходит нормативную схему`() {
        val problems = boundary.schemaProblems("core/document-harvest", harvest)
        assertTrue(problems.isEmpty()) { "пакет ПМИ Б2 не по схеме: ${problems.take(3)}" }
    }

    @Test
    fun `счётчики по классам совпадают с эталонными`() {
        val summary = DocumentHarvest.summaryOf(harvest)
        val reference = harvest.path("summary")
        reference.properties().forEach { (cls, expected) ->
            assertEquals(expected.asInt(), summary.path(cls).asInt()) { "класс $cls" }
        }
        // меры решения Д2: ≥6 стейкхолдеров, 6 нормативов «уточнить», сумма
        assertTrue(summary.path("stakeholder").asInt() >= 6)
        assertEquals(6, summary.path("need_ref_flags").asInt())
        assertEquals(1, summary.path("budget").asInt())
    }

    @Test
    fun `стейкхолдер ложится на полку А2 с ролью инженера и координатой блока`() {
        val filled = mapper.createObjectNode().put("role", "regulator")
        val doc = DocumentHarvest.objectOf(
            item("stakeholder"), filled, "SD-0003", "3", "Записка Минтранса", "2026-08-29",
        )!!
        val stored = boundary.editing.create(
            CoreType.StakeholderProfile, doc, "инженер", orbita.mod.store.ObjectStore.LIBRARY_PROJECT,
        )
        val saved = boundary.objects.current(stored.id)!!
        assertEquals("Минтранс России", saved.doc.path("name").asText())
        assertEquals("regulator", saved.doc.path("role").asText())
        assertTrue("b0" in saved.doc.path("provenance").path("import").path("item_ref").asText())
        assertTrue("SD-0003" in saved.doc.path("provenance").path("import").path("dataset").asText())
    }

    @Test
    fun `сумма документа ложится оценкой каноном млн рублей`() {
        val doc = DocumentHarvest.objectOf(
            item("budget"), mapper.createObjectNode(), "SD-0003", "3", "Записка Минтранса", "2026-08-29",
        )!!
        val stored = boundary.editing.create(CoreType.CostEstimate, doc, "инженер", "PJ-1801")
        val saved = boundary.objects.current(stored.id)!!.doc
        assertEquals(7000.0, saved.path("total_low").path("value").asDouble())
        assertEquals(9000.0, saved.path("total_high").path("value").asDouble())
        assertEquals("MRUB", saved.path("total_low").path("unit").asText())
        assertTrue("b7" in saved.path("basis").asText()) { saved.path("basis").asText() }
    }

    @Test
    fun `ограничение документа получает следующий свободный Р-код и след источника`() {
        val passport = boundary.objects.current("PJ-1801")!!
        val c = DocumentHarvest.constraintOf(item("constraint"), passport.doc.path("constraints"), "SD-0003")
        assertEquals("Р2", c.path("code").asText())
        assertTrue(c.path("text").asText().isNotBlank())
        assertTrue("SD-0003" in c.path("source").asText())
    }

    @Test
    fun `норматив без реквизитов не создаётся - это разрыв, а не выдумка`() {
        val nr = item("normative_ref")
        assertTrue(nr.path("need_ref").asBoolean()) { "эталон помечает норматив как «уточнить обозначение»" }
        val gaps = DocumentHarvest.gapsOf("normative_ref", nr, mapper.createObjectNode())
        assertTrue(gaps.any { it.field == "number" } && gaps.any { it.field == "edition_date" }) {
            "у норматива без реквизитов обязаны спрашиваться обозначение и дата: $gaps"
        }
        // с реквизитами инженера — ложится нормативом
        val filled = mapper.createObjectNode()
            .put("number", "ПП № 1279").put("edition_date", "2020-09-01").put("kind", "decree")
        val doc = DocumentHarvest.objectOf(nr, filled, "SD-0003", "3", "Записка Минтранса", "2026-08-29")!!
        val stored = boundary.editing.create(
            CoreType.NormativeDocument, doc, "инженер", orbita.mod.store.ObjectStore.LIBRARY_PROJECT,
        )
        assertEquals("ПП № 1279", boundary.objects.current(stored.id)!!.doc.path("number").asText())
    }

    @Test
    fun `цель и нужда требуют решения инженера, а не догадки службы`() {
        assertTrue(DocumentHarvest.gapsOf("goal", item("goal"), mapper.createObjectNode())
            .any { it.field == "kind" })
        val needGaps = DocumentHarvest.gapsOf("need", item("need"), mapper.createObjectNode())
        assertTrue(needGaps.any { it.field == "stakeholder_name" } && needGaps.any { it.field == "stakeholder_role" })

        val filled = mapper.createObjectNode()
            .put("stakeholder_name", "Минтранс России").put("stakeholder_role", "customer")
        val doc = DocumentHarvest.objectOf(item("need"), filled, "SD-0003", "3", "Записка", "2026-08-29")!!
        val stored = boundary.editing.create(CoreType.Need, doc, "инженер", "PJ-1801")
        assertEquals(
            "Минтранс России",
            boundary.objects.current(stored.id)!!.doc.path("stakeholder").path("name").asText(),
        )
    }

    @Test
    fun `география и вехи адресуются, но объектами не становятся`() {
        listOf("geography", "milestone").forEach { cls ->
            assertEquals(null, DocumentHarvest.TARGETS[cls]?.type) { "$cls не объект" }
            assertTrue(DocumentHarvest.TARGETS[cls]?.where?.isNotBlank() == true) { "$cls без адреса" }
            assertEquals(
                null,
                DocumentHarvest.objectOf(item(cls), mapper.createObjectNode(), "SD-0003", "3", "З", "2026-08-29"),
            )
        }
    }

    @Test
    fun `выжимка для службы — канон с якорями, а не файл`() {
        val card = mapper.createObjectNode().put("name", "Записка Минтранса").put("kind", "mission_note")
        val statement = DocumentHarvest.statementOf(
            card, "SD-0003", "# Записка {#b0}\n\n<!-- b1 -->\nТекст записки.\n", null,
        )
        assertTrue("SD-0003" in statement && "Записка Минтранса" in statement)
        assertTrue("{#b0}" in statement && "<!-- b1 -->" in statement)
    }
}
