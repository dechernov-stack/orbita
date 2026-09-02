// Шип 1 «трёх пакетов» и шип 2.2 пачки SEMP: связный текст [С]-раздела.
// Вход — данные вставок раздела человеческими строками и только они;
// принятый текст помнит эти строки, и когда данные уходят, раздел говорит
// «текст устарел» и НАЗЫВАЕТ, что разошлось. Молчаливой перезаписи нет.
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
class SectionProseTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)

    @BeforeEach
    fun clean() {
        TestDb.truncateAll()
        boundary.ingest(
            CoreType.Project,
            """{"id":"PJ-2201","name":"Проза","phase":"phase_a",
                "mission_intent":{"for_whom":"Минтранс России","what":"резервный канал координат",
                                  "where":"вне наземного покрытия","horizon":"2030"},
                "milestones":[{"gate":"SRR","due":"2026-11-01"}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2201",
        )
        // шаблон SEMP ред. 2 — из пакета полки, как на стенде
        val пакет = mapper.readTree(
            RepoPaths.repoRoot().resolve("docs/tz/manual-run/packets/13-шаблон-semp.json").toFile(),
        )
        пакет.path("objects").forEach {
            boundary.ingest(CoreType.DocumentTemplate, it.toString(), "test", ObjectStore.LIBRARY_PROJECT)
        }
    }

    private fun template() = boundary.objects.listCurrent(ObjectStore.LIBRARY_PROJECT)
        .first { it.type == "document_template" && it.doc.path("code").asText() == "semp" }
        .let { orbita.out.TemplateData.of(it.doc) }

    @Test
    fun `вход связного текста — подсказка раздела и данные вставок человеческими строками`() {
        val вход = DocumentModel.proseInput(boundary, "PJ-2201", template(), 3)
        assertEquals("prose", вход.path("mode").asText())
        val statement = вход.path("statement").asText()
        assertTrue("Регламент ожидает видеть" in statement)
        assertTrue("для кого: Минтранс России" in statement) { statement }
        // латинских ключей во входе нет — модель не должна их пересказать
        val строки = вход.path("lines").map { it.asText() }
        assertTrue(orbita.out.PrintHumanizer.serviceKeys(строки).isEmpty()) { строки.toString() }
    }

    @Test
    fun `принятый текст помнит строки данных, устаревание называет разошедшееся`() {
        val шаблон = template()
        val вход = DocumentModel.proseInput(boundary, "PJ-2201", шаблон, 3)
        val текст = mapper.createObjectNode()
        текст.put("id", "ST-0001")
        текст.put("template_code", "semp")
        текст.put("section", 3)
        текст.put("text", "Резервный канал координат для Минтранса России вне наземного покрытия.")
        текст.put("inserts_fingerprint", вход.path("inserts_fingerprint").asText())
        val lines = текст.putArray("inserts_lines")
        вход.path("lines").forEach { lines.add(it.asText()) }
        текст.set<com.fasterxml.jackson.databind.node.ObjectNode>(
            "lifecycle", mapper.createObjectNode().put("status", "Draft").put("version", "1"),
        )
        boundary.ingest(CoreType.SectionText, текст.toString(), "Чернов", "PJ-2201")

        fun раздел3() = orbita.out.DocumentGenerator(mapper)
            .render(DocumentModel.model(boundary, "PJ-2201"), шаблон, DocumentModel.sectionTexts(boundary, "semp", "PJ-2201"))
            .body.path("sections").first { it.path("number").asInt() == 3 }
        assertFalse(раздел3().path("text_stale").asBoolean(false)) { "данные не менялись — текст свеж" }

        // замысел ушёл из-под текста
        val проект = boundary.objects.current("PJ-2201")!!
        val changes = mapper.createObjectNode()
        changes.set<com.fasterxml.jackson.databind.node.ObjectNode>(
            "mission_intent",
            mapper.createObjectNode().put("for_whom", "Росатом").put("what", "резервный канал координат")
                .put("where", "вне наземного покрытия").put("horizon", "2030"),
        )
        boundary.editing.update(CoreType.Project, "PJ-2201", changes, проект.version, "test", changeRef = "замысел уточнён")
        val s3 = раздел3()
        assertTrue(s3.path("text_stale").asBoolean(false)) { "текст обязан устареть" }
        val diff = s3.path("text_diff").map { it.asText() }
        assertTrue(diff.any { it.startsWith("было:") && "Минтранс России" in it }) { diff.toString() }
        assertTrue(diff.any { it.startsWith("стало:") && "Росатом" in it }) { diff.toString() }
        // текст на месте — молча его никто не переписал
        assertEquals("Резервный канал координат для Минтранса России вне наземного покрытия.", s3.path("text").asText())
    }
}
