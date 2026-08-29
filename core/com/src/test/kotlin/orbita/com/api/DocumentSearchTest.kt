// Д3: индекс и поиск по материалам + промпт ИЗ БЛОКОВ. Меры решения:
// найденное приходит с координатой блока; терм глоссария находит блоки, где
// он употреблён; включение документа в промпт — выбор блоков, и в промпт
// уходит именно выбранное, а не файл целиком.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.CoreType
import orbita.mod.schema.SchemaRegistry
import orbita.mod.store.ObjectStore
import orbita.out.ParseLexicon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentSearchTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)

    @TempDir
    lateinit var files: Path

    /** Исходник — обычный markdown: якоря расставит разбор Д1, не фикстура. */
    private val note = """
        # Записка миссии

        Группировка передаёт короткие сообщения от датчиков в Арктике.

        ## 1. Обоснование

        Зона обслуживания шире зоны видимости лишь там, где замыкается бюджет линии.

        Ориентировочный объём инвестиций первого этапа — 7–9 млрд ₽.
    """.trimIndent()

    private val lexicon = ParseLexicon(
        unitSpellings = mapOf("млрд ₽" to "BRUB"),
        terms = listOf("Зона обслуживания", "Зона видимости"),
        toCanon = { v, u -> if (u == "BRUB") v * 1000 to "MRUB" else null },
    )

    @BeforeEach
    fun clean() {
        TestDb.truncateAll()
        System.setProperty("orbita.test.filesDir", files.toString())
        boundary.ingest(
            CoreType.Project,
            """{"id":"PJ-2101","name":"Поиск по материалам","phase":"pre_phase_a",
                "mission_intent":{"text":"Группировка IoT для логистики."},
                "milestones":[{"gate":"MCR"}],"lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2101",
        )
        boundary.ingest(
            CoreType.SourceDocument,
            """{"id":"SD-2101","name":"Записка миссии","kind":"mission_note","org":"Минтранс",
                "rights":"внутренний документ проекта","lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2101",
        )
        DocumentParseStore.parseAndStore(
            files.toString(), "SD-2101", "записка.md", note.toByteArray(), lexicon,
        )
    }

    @Test
    fun `поиск по тексту находит блок с координатой и фрагментом`() {
        val hits = DocumentSearch(boundary).search("PJ-2101", "инвестиций")
        assertTrue(hits.isNotEmpty()) { "по тексту ничего не найдено" }
        val hit = hits.first()
        assertEquals("SD-2101", hit.document)
        assertEquals("b3", hit.anchor)
        assertTrue("7–9 млрд ₽" in hit.fragment) { hit.fragment }
        assertEquals("текст", hit.by)
        assertEquals("1. Обоснование", hit.section)
    }

    @Test
    fun `поиск по терму глоссария находит блоки, где он употреблён`() {
        val hits = DocumentSearch(boundary).search("PJ-2101", "Зона обслуживания")
        assertTrue(hits.any { it.anchor == "b2" }) { hits.map { it.anchor }.toString() }
        assertTrue(hits.any { it.by.startsWith("терм") || it.by == "текст" })
    }

    @Test
    fun `короткий запрос не ищется - шум вместо находок не нужен`() {
        assertTrue(DocumentSearch(boundary).search("PJ-2101", "а").isEmpty())
    }

    @Test
    fun `промпт берёт ВЫБРАННЫЕ блоки документа, а не файл целиком`() {
        val passport = boundary.objects.current("PJ-2101")!!
        val changes = mapper.readTree(
            """{"start_path":{"status":"in_progress","step":3,
                 "source_refs":["SD-2101"],
                 "source_blocks":{"SD-2101":["b3"]}}}""",
        )
        boundary.editing.update(CoreType.Project, "PJ-2101", changes, passport.version, "инженер")

        val materials = StatementSources(boundary).of("mission_to_goals", "PJ-2101")
            .first { it.key == "materials" }
        assertEquals(1, materials.count) { "в промпт ушёл ровно один выбранный блок" }
        assertTrue(materials.lines.single().contains("[b3]")) { materials.lines.toString() }
        assertTrue(materials.lines.single().contains("7–9 млрд ₽"))
        assertTrue(materials.lines.none { it.contains("Зона обслуживания") }) {
            "невыбранные блоки в промпт не идут — в этом и смысл выбора"
        }
    }

    @Test
    fun `документ без выбора блоков отдаёт оглавление, а не молчание`() {
        val passport = boundary.objects.current("PJ-2101")!!
        val changes = mapper.readTree(
            """{"start_path":{"status":"in_progress","step":3,"source_refs":["SD-2101"]}}""",
        )
        boundary.editing.update(CoreType.Project, "PJ-2101", changes, passport.version, "инженер")

        val materials = StatementSources(boundary).of("mission_to_goals", "PJ-2101")
            .first { it.key == "materials" }
        assertTrue(materials.lines.single().contains("блоки не выбраны")) { materials.lines.toString() }
        assertTrue(materials.lines.single().contains("s1")) { "оглавление называет разделы" }
    }
}
