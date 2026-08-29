// Д1, приёмка эталонами пачки-1: разбор системы прогоняется по ТЕМ ЖЕ двум
// документам владельца (docs/tz/manual-run-2/пачка-1/исходники/) и обязан
// дать канон с потерей текста 0 и карту, эквивалентную ручному эталону по
// СОСТАВУ (формулировки могут отличаться, состав — нет).
package orbita.out

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files

class DocumentParseReferenceTest {

    private val mapper = ObjectMapper()
    private val batch = RepoPaths.repoRoot().resolve("docs/tz/manual-run-2/пачка-1")

    /** Словари — из тех же сидов полки, что уходят на стенд (не из кода теста). */
    private val lexicon: ParseLexicon by lazy {
        val packets = RepoPaths.repoRoot().resolve("docs/tz/manual-run/packets")
        val units = mapper.readTree(Files.readString(packets.resolve("07-справочник-единиц.json")))
            .path("objects")[0]
        val spellings = mutableMapOf<String, String>()
        units.path("dimensions").forEach { d ->
            d.path("spellings").forEach { s -> spellings[s.asText().lowercase()] = d.path("canon").asText() }
            d.path("inputs").forEach { i ->
                i.path("spellings").forEach { s -> spellings[s.asText().lowercase()] = i.path("unit").asText() }
            }
        }
        val terms = mapper.readTree(Files.readString(packets.resolve("08-глоссарий.json")))
            .path("objects")[0].path("entries").filterNot { it.has("sd_kind") }
            .map { it.path("term").asText() }
        ParseLexicon(spellings, terms) { value, unit ->
            val dim = units.path("dimensions").firstOrNull { d ->
                d.path("canon").asText() == unit || d.path("inputs").any { it.path("unit").asText() == unit }
            } ?: return@ParseLexicon null
            if (dim.path("canon").asText() == unit) null
            else dim.path("inputs").first { it.path("unit").asText() == unit }
                .path("factor").takeIf { it.isNumber }
                ?.let { value * it.asDouble() to dim.path("canon").asText() }
        }
    }

    private fun parse(name: String): ParsedDocument {
        val bytes = Files.readAllBytes(batch.resolve("исходники/$name"))
        return DocumentParse.parse(name, bytes, lexicon)!!
    }

    /** Весь текст исходника: абзацы и ячейки таблиц — до единой строки. */
    private fun sourceTexts(name: String): List<String> =
        XWPFDocument(ByteArrayInputStream(Files.readAllBytes(batch.resolve("исходники/$name")))).use { doc ->
            doc.paragraphs.map { it.text.trim() } +
                doc.tables.flatMap { t -> t.rows.flatMap { r -> r.tableCells.map { it.text.trim() } } }
        }.filter { it.isNotEmpty() }

    @Test
    fun `записка - канон без потери текста, блоки с координатами`() {
        val parsed = parse("записка-минтранс.docx")
        val canon = parsed.canonMd

        val lost = sourceTexts("записка-минтранс.docx").filterNot { source ->
            canon.contains(source.replace("\n", " "))
        }
        assertTrue(lost.isEmpty()) { "потеря текста: ${lost.size} фрагментов, первый — «${lost.firstOrNull()}»" }

        val summary = parsed.map.path("summary")
        assertTrue(summary.path("blocks").asInt() >= 40) { "блоков ${summary.path("blocks")}" }
        assertTrue(summary.path("sections").asInt() >= 15) { "разделов ${summary.path("sections")}" }
        assertEquals(4, summary.path("tables").asInt())
        assertEquals("title", parsed.map.path("structure")[0].path("type").asText())
        parsed.map.path("structure").forEach {
            assertTrue(it.path("anchor").asText().isNotBlank()) { "блок без координаты" }
        }
    }

    @Test
    fun `записка - 7-9 млрд рублей лежат каноном денег с происхождением`() {
        val parsed = parse("записка-минтранс.docx")
        val money = parsed.map.path("numbers")
            .first { it.path("unit").asText() == "BRUB" && it.path("value").has("min") }
        assertEquals(7.0, money.path("value").path("min").asDouble())
        assertEquals(9.0, money.path("value").path("max").asDouble())
        assertEquals("MRUB", money.path("canonical").path("unit").asText())
        assertEquals(7000.0, money.path("canonical").path("value").path("min").asDouble())
        assertEquals(9000.0, money.path("canonical").path("value").path("max").asDouble())
        assertTrue(money.path("converted_from").asText().startsWith("7–9 млрд"))
        assertTrue(money.path("block").asText().isNotBlank()) { "число без координаты блока" }
    }

    @Test
    fun `анализ - карта эквивалентна ручному эталону по составу`() {
        val parsed = parse("анализ-идей.docx")
        val reference = mapper.readTree(Files.readString(batch.resolve("ПАКЕТ-РАЗБОР-АНАЛИЗА.json")))
            .path("structure")
        val actual = parsed.map.path("structure")

        assertEquals(
            reference.map { it.path("type").asText() },
            actual.map { it.path("type").asText() },
        ) { "состав элементов карты разошёлся с эталоном" }

        val refTable = reference.first { it.path("type").asText() == "table" }
        val table = actual.first { it.path("type").asText() == "table" }
        assertEquals(refTable.path("anchor").asText(), table.path("anchor").asText())
        assertEquals(refTable.path("rows").asInt(), table.path("rows").asInt())   // 36 идей — все
        assertEquals(refTable.path("row_key").asText(), table.path("row_key").asText())
        assertEquals(
            refTable.path("cols").map { it.asText() },
            table.path("cols").map { it.asText() },
        )
        assertEquals("Таблица оценок", table.path("title").asText())
    }

    @Test
    fun `анализ - канон совпадает с ручным эталоном по строкам таблицы`() {
        val canon = parse("анализ-идей.docx").canonMd
        val referenceCanon = Files.readString(batch.resolve("АНАЛИЗ-КАНОН.md"))
        val rows = referenceCanon.lines().filter { it.startsWith("| ") && it.count { c -> c == '|' } == 6 }
            .filterNot { it.contains("---") }
        assertEquals(37, rows.size) { "в эталоне ожидались шапка и 36 строк" }
        rows.forEach { assertTrue(it in canon) { "строка эталона отсутствует в каноне: $it" } }
        assertTrue("{#b0}" in canon && "{#s1}" in canon && "<!-- t1 -->" in canon)
    }
}
