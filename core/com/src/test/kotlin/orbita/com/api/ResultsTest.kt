// Шип 2.3 «трёх пакетов»: Библиотека → «Результаты». Карточка выпуска несёт
// авторов текста из истории правок разделов и выпустившего; служба автором
// не бывает — её версии в авторы не попадают (сторож ServiceAuthors).
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
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
class ResultsTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)

    @BeforeEach
    fun clean() {
        TestDb.truncateAll()
        boundary.ingest(
            CoreType.Project,
            """{"id":"PJ-2301","name":"Результаты","phase":"phase_a",
                "mission_intent":{"for_whom":"Минтранс России","what":"резервный канал","where":"вне покрытия","horizon":"2030"},
                "milestones":[{"gate":"SRR","due":"2026-11-01"}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2301",
        )
        mapper.readTree(RepoPaths.repoRoot().resolve("docs/tz/manual-run/packets/13-шаблон-semp.json").toFile())
            .path("objects").forEach {
                boundary.ingest(CoreType.DocumentTemplate, it.toString(), "test", ObjectStore.LIBRARY_PROJECT)
            }
    }

    private fun текст(id: String, section: Int, text: String, author: String) {
        val n = mapper.createObjectNode()
        n.put("id", id); n.put("template_code", "semp"); n.put("section", section); n.put("text", text)
        n.put("inserts_fingerprint", "")
        n.set<ObjectNode>("lifecycle", mapper.createObjectNode().put("status", "Draft").put("version", "1"))
        boundary.ingest(CoreType.SectionText, n.toString(), author, "PJ-2301")
    }

    @Test
    fun `карточка выпуска несёт авторов текста без служебных учёток и выпустившего`() {
        текст("ST-0001", 3, "Резюме связным текстом.", "Чернов")
        текст("ST-0002", 10, "Среда работ — своими расчётами.", "ci-runner")
        val template = orbita.out.TemplateData.of(
            boundary.objects.listCurrent(ObjectStore.LIBRARY_PROJECT)
                .first { it.type == "document_template" && it.doc.path("code").asText() == "semp" }.doc,
        )
        val generated = orbita.out.DocumentGenerator(mapper).render(
            DocumentModel.model(boundary, "PJ-2301"), template, DocumentModel.sectionTexts(boundary, "semp", "PJ-2301"),
        )
        val issue = mapper.createObjectNode()
        issue.put("template", "semp"); issue.put("digest", generated.digest)
        // выпуск — ПОСЛЕ принятия текстов: дата берётся от часов, а не зашивается —
        // зашитая дата делала тест зависимым от календаря (упал 03.09)
        issue.put("issued_at", java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(1).toString()); issue.put("status", "issued"); issue.put("gaps", generated.gaps.size)
        issue.set<ObjectNode>("snapshot", generated.body)
        boundary.editing.create(CoreType.DocumentIssue, issue, "Иванов", "PJ-2301")

        val v = Results.toJson(boundary, "PJ-2301")
        assertEquals(1, v.path("cards").size())
        val card = v.path("cards").first()
        assertEquals("Иванов", card.path("issued_by").asText())
        assertFalse(card.path("stale").asBoolean()) { "выпуск только что сделан — модель не уходила" }
        val авторы = card.path("authors").map { it.path("name").asText() }
        assertEquals(listOf("Чернов"), авторы) { "служба автором не бывает: $авторы" }
        assertEquals(listOf(3), card.path("authors").first().path("sections").map { it.asInt() })
        assertTrue(card.path("sections_with_text").asInt() >= 1)
    }

    @Test
    fun `без выпусков раздел объясняет пустоту`() {
        val v = Results.toJson(boundary, "PJ-2301")
        assertEquals(0, v.path("cards").size())
        assertTrue(v.path("empty_why").asText().contains("выпусков"))
    }
}
