// Д2, приёмка — на СИНТЕТИКЕ (решение владельца): пакет урожая строит сам
// тест, по девяти классам плюс один класс вне схемы (мера правила 9).
// Примеры владельца в репозитории не лежат — репозиторий публичен.
//
// Меры: пакет проходит нормативную схему; счётчики по классам считаются;
// урожай раскладывается по адресам — стейкхолдеры на полку А2, ограничения
// в паспорт Р-кодом, суммы каноном денег, география маской-заготовкой, этап
// вехой без даты; нормативы без реквизитов НЕ создаются — это разрыв.
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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentHarvestTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    /** Урожай-фикстура: девять классов схемы плюс класс вне перечня. */
    private val harvest: ObjectNode by lazy {
        mapper.readTree(
            """
            {"kind":"document_semantic_parse","source_document":"SD-0003",
             "parser":"фикстура приёмки",
             "items":[
               {"class":"stakeholder","name":"Минтранс России",
                "role":"адресат, инициатор программы","block":["b0","b8"]},
               {"class":"stakeholder","name":"Единый центр мониторинга",
                "establishes":true,"block":["b12"]},
               {"class":"normative_ref","statement":"Оснащение транспорта, перевозящего опасные грузы",
                "need_ref":true,"block":["b2"]},
               {"class":"service","name":"Цифровой контроль перевозок опасных грузов","block":["b2"]},
               {"class":"goal","statement":"100% отслеживаемость критических грузов",
                "measure":{"value":1.0,"unit":"1"},"horizon":2033,"block":["b13"]},
               {"class":"need","statement":"Массовая передача обязательной телеметрии","block":["b1","b2"]},
               {"class":"milestone","name":"Этап 1: MVP","span":{"min":0,"max":2,"unit":"год"},
                "fleet":{"value":50,"unit":"шт","approx":true},"block":["b4"]},
               {"class":"budget","statement":"Инвестиции этапа 1",
                "range":{"min":7,"max":9,"unit":"млрд ₽"},
                "canonical":{"min":7000,"max":9000,"unit":"млн ₽"},"block":["b7"]},
               {"class":"geography","name":"Арктика","priority":true,"block":["b3","b11"]},
               {"class":"constraint","statement":"Приоритетное покрытие логистических коридоров","block":["b9"]},
               {"class":"evaluation_criterion","name":"Востребованность через спутник","scale":"1–10",
                "schema_note":"класс вне перечня — расширение схемы документом (правило 9)","anchor":"s1"}
             ]}
            """.trimIndent(),
        ) as ObjectNode
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
    fun `пакет урожая проходит нормативную схему, класс вне перечня — тоже`() {
        val problems = boundary.schemaProblems("core/document-harvest", harvest)
        assertTrue(problems.isEmpty()) { "пакет не по схеме: ${problems.take(3)}" }
        // правило 9: сущность вне известных классов несёт schema_note и живёт
        val extra = harvest.path("items").first { it.path("class").asText() == "evaluation_criterion" }
        assertTrue(extra.path("schema_note").asText().isNotBlank())
    }

    @Test
    fun `счётчики по классам считаются, включая пометку «уточнить обозначение»`() {
        val summary = DocumentHarvest.summaryOf(harvest)
        assertEquals(2, summary.path("stakeholder").asInt())
        assertEquals(1, summary.path("normative_ref").asInt())
        assertEquals(1, summary.path("need_ref_flags").asInt())
        assertEquals(1, summary.path("budget").asInt())
        assertEquals(1, summary.path("evaluation_criterion").asInt())
    }

    @Test
    fun `стейкхолдер ложится В ПРОЕКТ с ролью инженера и координатой блока`() {
        // Ф-13: кандидат стейкхолдера — факт проекта, а не шаблон полки.
        // Прежде он молча обобщался в профиль А2: библиотека наполнялась
        // сама собой, а в проекте стейкхолдеров не заводилось вовсе.
        val filled = mapper.createObjectNode().put("role", "regulator")
        val doc = DocumentHarvest.objectOf(
            item("stakeholder"), filled, "SD-0003", "3", "Записка Минтранса", "2026-08-29",
        )!!
        val stored = boundary.editing.create(CoreType.Stakeholder, doc, "инженер", "PJ-1801")
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
    fun `география - область-заготовка с приоритетом и БЕЗ геометрии`() {
        val doc = DocumentHarvest.objectOf(
            item("geography"), mapper.createObjectNode(), "SD-0003", "3", "Записка", "2026-08-29",
        )!!
        val stored = boundary.editing.create(CoreType.GeoMask, doc, "инженер", "PJ-1801")
        val saved = boundary.objects.current(stored.id)!!.doc
        assertEquals("Арктика", saved.path("name").asText())
        assertTrue(saved.path("priority").asBoolean()) { "приоритет документа потерян" }
        assertTrue(saved.path("geometry").isMissingNode) {
            "граница выдумана: контур «Арктики» из слова был бы витриной"
        }
        assertTrue("b3" in saved.path("provenance").path("import").path("item_ref").asText())
    }

    @Test
    fun `область без границы - разрыв готовности карты спроса, а не тишина`() {
        val doc = DocumentHarvest.objectOf(
            item("geography"), mapper.createObjectNode(), "SD-0003", "3", "Записка", "2026-08-29",
        )!!
        boundary.editing.create(CoreType.GeoMask, doc, "инженер", "PJ-1801")
        val checks = boundary.gatePassing.readiness("MCR", "PJ-1801")
        val gap = checks.first { it.id == "geo_masks" }
        assertEquals("open", gap.state)
        assertTrue("Арктика" in gap.note) { gap.note }
        assertEquals("seeddemand", gap.place)
    }

    @Test
    fun `веха этапа - без даты, с примечанием-происхождением`() {
        val m = DocumentHarvest.milestoneOf(item("milestone"), "SD-0003")
        assertEquals("Этап 1: MVP", m.path("gate").asText())
        assertTrue(m.path("due").isMissingNode) { "дата вычислена — этого делать нельзя" }
        assertTrue(m.path("duration_days").isMissingNode) { "длительность документа стала планом" }
        val note = m.path("note").asText()
        assertTrue("0–2 год" in note && "SD-0003" in note && "b4" in note) { note }

        // веха ложится в ленту паспорта и не ломает правило порядка дат
        val passport = boundary.objects.current("PJ-1801")!!
        val lane = (passport.doc.path("milestones").deepCopy() as com.fasterxml.jackson.databind.node.ArrayNode)
        lane.add(m)
        val changes = mapper.createObjectNode()
        changes.set<com.fasterxml.jackson.databind.node.ArrayNode>("milestones", lane)
        boundary.editing.update(CoreType.Project, "PJ-1801", changes, passport.version, "инженер")
        val saved = boundary.objects.current("PJ-1801")!!.doc.path("milestones")
        assertTrue(saved.any { it.path("gate").asText() == "Этап 1: MVP" && !it.has("due") })
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
