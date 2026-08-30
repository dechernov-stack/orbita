// Живой прогон владельца: служба предложила 7 целей, на акцепт вышло 0.
// Причина — происхождение величины: модель ссылается на документ проекта
// («Записка… (SD-0006)»), но версию документа и дату получения знает не она,
// а система. Схема требует оба поля, и весь ответ уходил в брак целиком.
//
// Здесь: недостающие поля берутся из карточки названного документа (факты
// хранилища, не выдумка), а ответ без узнаваемой ссылки честно бракуется.
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
class ImportProvenanceCompletionTest {

    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val mapper = ObjectMapper()

    @BeforeEach
    fun clean() {
        TestDb.truncateAll()
        boundary.ingest(
            CoreType.Project,
            """{"id":"PJ-1905","name":"Прогон","phase":"pre_phase_a",
                "mission_intent":{"text":"Группировка IoT для логистики."},
                "milestones":[{"gate":"MCR"}],"lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1905",
        )
        boundary.ingest(
            CoreType.AiProfile,
            """{"id":"AP-1901","name":"Служба постановки","kinds":["mission_to_goals"],
                "transport":"any","require_source":true,
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1905",
        )
        boundary.ingest(
            CoreType.SourceDocument,
            """{"id":"SD-1901","name":"Записка миссии","kind":"mission_note","org":"Заказчик",
                "rights":"внутренний документ проекта","lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1905",
        )
    }

    /** Ответ службы: ссылка на документ есть, версии и даты нет. */
    private fun answer(dataset: String) = """
        [{"id":"MG-1901","kind":"goal","statement":"Обеспечить отслеживаемость грузов.",
          "moe":[{"id":"MOE-1901","name":"Доля отслеживаемых","target":{"value":1,"unit":"1",
            "provenance":{"source":"imported","import":{"dataset":"$dataset"}}}}],
          "lifecycle":{"status":"Draft","version":"1"},
          "provenance":{"source":"ai_proposed"}}]
    """.trimIndent()

    @Test
    fun `версия и дата документа дописываются из карточки, предложение доходит до акцепта`() {
        val service = boundary.ai
        val run = service.packet(
            answer("Записка миссии (SD-1901)").let {
                """{"kind":"mission_to_goals","items":$it}"""
            },
            "PJ-1905", "test",
        )
        val report = run.report
        assertEquals(1, report.path("proposed").asInt()) { "предложение одно" }
        assertTrue(report.path("shown").size() == 1) {
            "предложение обязано дойти до акцепта, а не сгинуть в браке: " +
                report.path("malformed").toString().take(300)
        }
        val imp = report.path("shown")[0].path("item")
            .path("moe")[0].path("target").path("provenance").path("import")
        assertEquals("1", imp.path("dataset_version").asText()) { "версия — из карточки документа" }
        assertTrue(imp.path("retrieved_at").asText().isNotBlank()) { "дата получения — из хранилища" }
        assertTrue("внутренний документ" in imp.path("terms").asText()) { "условия — из карточки" }
    }

    @Test
    fun `ссылка в никуда не дополняется — такой ответ бракуется честно`() {
        val run = boundary.ai.packet(
            """{"kind":"mission_to_goals","items":${answer("Отраслевой обзор рынка IoT, 2025")}}""",
            "PJ-1905", "test",
        )
        assertEquals(0, run.report.path("shown").size()) {
            "документа с таким именем в проекте нет — выдумывать версию и дату нельзя"
        }
    }
}
