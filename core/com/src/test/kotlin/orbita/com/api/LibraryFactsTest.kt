// Ф-09: библиотека в контуре ИИ — факты и кандидаты, а не имена. Меры
// владельца: промпт несёт пункты норматива ТЕКСТОМ С ЧИСЛАМИ; предложение
// требования приходит с основанием-якорем в канон НПА; для морского сервиса
// приходит ограничение-кандидат из SOLAS; норматив, знающий только своё имя,
// назван прямо, а не пропущен молча.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
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
class LibraryFactsTest {

    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val sources = StatementSources(boundary)
    private val mapper = ObjectMapper()

    @BeforeEach
    fun clean() {
        TestDb.truncateAll()
        boundary.ingest(
            CoreType.MissionClass,
            """{"id":"MC-9001","name":"НОО · связь и IoT","lifecycle":{"status":"Draft","version":"1"}}""",
            "test", ObjectStore.LIBRARY_PROJECT,
        )
        // норматив С ПУНКТАМИ — знание, которое обязано дойти до промпта
        boundary.ingest(
            CoreType.NormativeDocument,
            """{"id":"NR-9001","name":"Об оснащении транспорта аппаратурой спутниковой навигации",
                "kind":"decree","number":"ПП №2216","org":"Правительство РФ",
                "in_force":"in_force","edition_date":"2021-12-01",
                "clauses":[{"clause":"п. 3","text":"Передача геопозиции — не реже одного раза в 30 секунд"},
                           {"clause":"п. 7","text":"Хранение трека в памяти терминала — не менее 30 суток"}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", ObjectStore.LIBRARY_PROJECT,
        )
        // норматив БЕЗ пунктов — знает только своё имя
        boundary.ingest(
            CoreType.NormativeDocument,
            """{"id":"NR-9002","name":"Международная конвенция по охране человеческой жизни на море",
                "kind":"convention","number":"SOLAS","org":"ИМО","in_force":"in_force",
                "edition_date":"1974-11-01",
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", ObjectStore.LIBRARY_PROJECT,
        )
        boundary.ingest(
            CoreType.Project,
            """{"id":"PJ-1902","name":"Морская логистика","phase":"pre_phase_a",
                "mission_class":"MC-9001",
                "mission_intent":{"text":"Группировка IoT для морской и наземной логистики."},
                "constraints":[{"code":"Р1","text":"Полезная нагрузка — только регенеративная."}],
                "milestones":[{"gate":"MCR"}],"lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1902",
        )
    }

    @Test
    fun `промпт несёт пункты норматива текстом с числами, а не имя позиции`() {
        val list = sources.of("services_to_requirements", "PJ-1902")
        val facts = list.first { it.key == "library_facts" }
        assertTrue(facts.count >= 2) { "оба пункта обязаны дойти: ${facts.lines}" }
        val text = facts.lines.joinToString("\n")
        assertTrue("30 секунд" in text) { "число нормы пропало: $text" }
        assertTrue("п. 3" in text && "ПП №2216" in text) { "реквизит пункта пропал: $text" }
        // старый источник остаётся перечнем — он про состав полки, не про знание
        val names = list.first { it.key == "class_library" }
        assertTrue(names.lines.any { "норматив (Б1)" in it }) { "перечень позиций никуда не делся" }
    }

    @Test
    fun `норматив, знающий только имя, назван прямо`() {
        val facts = sources.of("services_to_requirements", "PJ-1902").first { it.key == "library_facts" }
        assertTrue(facts.note != null && "1" in facts.note!!) { "молчащий норматив обязан быть посчитан: ${facts.note}" }
        assertTrue("пунктов" in facts.note!!) { facts.note!! }
    }

    @Test
    fun `готовность кандидатов считает говорящие нормативы`() {
        val readiness = NormativeCandidates.readiness(boundary, "files", "PJ-1902")
        assertEquals(2, readiness.path("normatives").asInt())
        assertEquals(1, readiness.path("speaking").asInt())
        assertTrue(readiness.path("can_compose").asBoolean())
        val solas = readiness.path("sources").first { it.path("id").asText() == "NR-9002" }
        assertFalse(solas.path("speaks").asBoolean()) { "SOLAS без пунктов и без документа не говорит" }
    }

    @Test
    fun `карточка полки говорит, что система знает из документа`() {
        boundary.ingest(
            CoreType.SourceDocument,
            """{"id":"SD-9001","name":"Регламент морского мониторинга","kind":"normative",
                "org":"ИМО","rights":"внешний источник","prompt":{"included":true,"blocks":["b1","b2"]},
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", ObjectStore.LIBRARY_PROJECT,
        )
        val readiness = NormativeCandidates.readiness(boundary, "files", "PJ-1902")
        val row = readiness.path("documents").first { it.path("id").asText() == "SD-9001" }
        assertTrue(row.path("in_prompt").asBoolean()) { "пометка «в промпт» живёт на карточке полки" }
        assertEquals(2, row.path("blocks").asInt())
        assertFalse(row.path("parsed").asBoolean()) { "разбора у фикстуры нет — и это видно, а не молчится" }
    }

    @Test
    fun `вход операции даёт пункты нормативов и то, что уже есть в проекте`() {
        boundary.ingest(
            CoreType.Requirement,
            """{"id":"RQ-9001","level":"system","statement":"Система должна передавать телеметрию.",
                "category":"functional","owner":"инженер","verification_events":[],
                "traces_up":[{"ref":"NR-9001"}],"lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1902",
        )
        val statement = NormativeCandidates.statementOf(boundary, "files", "PJ-1902")
        assertTrue("ПП №2216" in statement && "30 секунд" in statement) { statement.take(400) }
        assertTrue("RQ-9001" in statement) { "принятое обязано попасть во вход — чтобы не дублировали" }
        assertTrue("Р1" in statement) { "действующие ограничения паспорта — во вход" }
    }

    @Test
    fun `кандидат требования ложится объектом с основанием, кандидат ограничения — Р-кодом`() {
        val packet = mapper.readTree(
            """{"kind":"normative_to_candidates","rules_version":1,"items":[
                 {"class":"requirement",
                  "statement":"Система должна обеспечивать передачу геопозиции не реже 1 раза в 30 с.",
                  "measure":{"value":30,"unit":"s"},
                  "basis":{"normative_ref":"NR-9001","clause":"п. 3","anchors":["b12"],
                           "quote":"периодичность передачи геопозиции"}},
                 {"class":"constraint",
                  "statement":"Морской сервис обязан поддерживать опознавание судов по SOLAS.",
                  "category":"регуляторное",
                  "basis":{"normative_ref":"NR-9002","clause":"гл. V"}}]}""",
        )
        assertTrue(NormativeCandidates.problems(boundary, packet).isEmpty()) {
            NormativeCandidates.problems(boundary, packet).toString()
        }

        val requirement = NormativeCandidates.requirementOf(packet.path("items")[0])
        assertEquals("NR-9001", requirement.path("traces_up")[0].path("ref").asText())
        val rationale = requirement.path("rationale").asText()
        assertTrue("п. 3" in rationale && "b12" in rationale) { "основание обязано нести пункт и якорь: $rationale" }
        // id и lifecycle проставит запись объекта; остальное обязано быть на месте
        val gaps = boundary.schemaProblems("core/requirement", requirement)
            .map { "${it.path}: ${it.message}" }
            .filterNot { "'id'" in it || "'lifecycle'" in it }
        assertTrue(gaps.isEmpty()) { "кандидат обязан ложиться в схему требования: $gaps" }
        assertEquals("functional", requirement.path("category").asText())
        assertTrue(requirement.path("verification_events").isArray) { "метод верификации не выдумывается — пустой список" }

        val existing = mapper.createArrayNode()
        existing.addObject().put("code", "Р1").put("text", "Регенеративная нагрузка")
        val constraint = NormativeCandidates.constraintOf(packet.path("items")[1], existing)
        assertEquals("Р2", constraint.path("code").asText()) { "код — следующий свободный в серии Р" }
        assertTrue("NR-9002" in constraint.path("source").asText()) { "источник называет норматив" }
        assertEquals("регуляторное", constraint.path("category").asText())
    }

    @Test
    fun `кандидат без основания воротами не проходит`() {
        val packet = mapper.readTree(
            """{"kind":"normative_to_candidates","items":[
                 {"class":"requirement","statement":"Система должна быть надёжной."}]}""",
        )
        assertTrue(NormativeCandidates.problems(boundary, packet).isNotEmpty()) {
            "без basis кандидат не может быть принят — иначе основание выдумывается"
        }
    }
}
