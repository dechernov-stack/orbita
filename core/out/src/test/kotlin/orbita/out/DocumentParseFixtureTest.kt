// Д1, приёмка — на СИНТЕТИКЕ (решение владельца): фикстуру docx порождает
// сам тест по свойствам, которые обязан держать разборщик. Примеры владельца
// в репозитории не лежат — репозиторий публичен; реальные документы стенда
// остаются живыми испытаниями вне CI.
//
// Свойства фикстуры — они же меры приёмки:
//   · разделы трёх уровней, абзацы с переносами;
//   · таблица N×M с ключевой колонкой (мера «N из N строк»);
//   · числа несистемными единицами («30 мин», «7–9 млрд ₽») — канонизация
//     с происхождением;
//   · нормативная ссылка без реквизитов — мера need_ref (её ставит Д2, Д1
//     обязан отдать обозначение кандидатом);
//   · потеря текста 0 — сверка полного текста фикстуры с каноном.
package orbita.out

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class DocumentParseFixtureTest {

    private val mapper = ObjectMapper()

    /** Строк данных в таблице фикстуры — на них мера «N из N». */
    private val tableRows = 12

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

    /** Абзацы фикстуры — тем же списком сверяется потеря текста. */
    private val paragraphs = listOf(
        "Записка вносит предложение о создании низкоорбитальной группировки.",
        "Действующая нормативная база формирует обязательный спрос:\nоснащение транспорта, ЭРА-ГЛОНАСС, идентификация БПЛА.",
        "Требование установлено ГОСТ Р 53802-2010 и постановлением ПП № 1279.",
        "Срок доставки сообщения не превышает 30 мин.",
        "Ориентировочный объём инвестиций первого этапа — 7–9 млрд ₽.",
        "Этап 1 (0–2 года) — развёртывание MVP около 50 аппаратов.",
        "Приоритет покрытия: Арктика, Северный морской путь, Сибирь и ДФО.",
    )

    private val headings = listOf(
        "Записка о национальной IoT-группировке" to 0,
        "1. Обоснование" to 1,
        "1.1. Нормативные основания" to 2,
        "1.1.1. Сроки доставки" to 3,
        "2. Модель реализации" to 1,
        "Таблица оценок" to 1,
    )

    private fun fixture(): ByteArray = XWPFDocument().use { doc ->
        fun heading(text: String, level: Int) {
            val p = doc.createParagraph()
            p.style = if (level == 0) "Title" else "Heading$level"
            p.createRun().setText(text)
        }
        fun para(text: String) {
            val run = doc.createParagraph().createRun()
            // абзац с переносом строки внутри — канон обязан сохранить текст
            text.split("\n").forEachIndexed { i, line ->
                if (i > 0) run.addBreak()
                run.setText(line)
            }
        }
        heading(headings[0].first, 0)
        para(paragraphs[0])
        heading(headings[1].first, 1)
        para(paragraphs[1])
        heading(headings[2].first, 2)
        para(paragraphs[2])
        heading(headings[3].first, 3)
        para(paragraphs[3])
        heading(headings[4].first, 1)
        para(paragraphs[4])
        para(paragraphs[5])
        para(paragraphs[6])
        heading(headings[5].first, 1)
        doc.createTable(tableRows + 1, 4).also { t ->
            listOf("№", "Идея", "Пользователи", "Экономика").forEachIndexed { c, v ->
                t.getRow(0).getCell(c).text = v
            }
            (1..tableRows).forEach { r ->
                t.getRow(r).getCell(0).text = r.toString()
                t.getRow(r).getCell(1).text = "Идея номер $r"
                t.getRow(r).getCell(2).text = (r % 10 + 1).toString()
                t.getRow(r).getCell(3).text = ((r + 3) % 10 + 1).toString()
            }
        }
        ByteArrayOutputStream().use { out -> doc.write(out); out.toByteArray() }
    }

    private fun parsed() = DocumentParse.parse("фикстура.docx", fixture(), lexicon)!!

    @Test
    fun `потеря текста 0 - канон несёт весь текст фикстуры`() {
        val canon = parsed().canonMd
        (paragraphs.flatMap { it.split("\n") } + headings.map { it.first }).forEach { source ->
            assertTrue(source.trim() in canon) { "потерян текст: «$source»" }
        }
        (1..tableRows).forEach { r ->
            assertTrue("| $r | Идея номер $r |" in canon) { "потеряна строка таблицы $r" }
        }
    }

    @Test
    fun `разделы трёх уровней - глубина и состав блоков`() {
        val parsed = parsed()
        val structure = parsed.map.path("structure")
        assertEquals("title", structure[0].path("type").asText())
        val sections = structure.filter { it.path("type").asText() == "section" }
        assertEquals(listOf(1, 2, 3, 1), sections.map { it.path("level").asInt() }) {
            "уровни разделов: ${sections.map { it.path("title").asText() to it.path("level").asInt() }}"
        }
        // глубина в каноне нормируется: титул #, разделы от ##
        assertTrue("# ${headings[0].first} {#b0}" in parsed.canonMd)
        assertTrue("## ${headings[1].first} {#s1}" in parsed.canonMd)
        assertTrue("### ${headings[2].first} {#s2}" in parsed.canonMd)
        assertTrue("#### ${headings[3].first} {#s3}" in parsed.canonMd)
        sections.forEach { s ->
            assertTrue(s.path("blocks").size() > 0) { "раздел ${s.path("title")} без блоков" }
        }
    }

    @Test
    fun `таблица - N из N строк с ключевой колонкой и адресом строки`() {
        val parsed = parsed()
        val table = parsed.map.path("structure").first { it.path("type").asText() == "table" }
        assertEquals(tableRows, table.path("rows").asInt())
        assertEquals("№", table.path("row_key").asText())
        assertEquals(listOf("Идея", "Пользователи", "Экономика"), table.path("cols").map { it.asText() })
        assertEquals("Таблица оценок", table.path("title").asText())
        assertTrue("<!-- t1 -->" in parsed.canonMd)
    }

    @Test
    fun `числа несистемными единицами - каноном справочника с происхождением`() {
        val numbers = parsed().map.path("numbers")
        val money = numbers.first { it.path("unit").asText() == "BRUB" }
        assertEquals(7.0, money.path("value").path("min").asDouble())
        assertEquals(9.0, money.path("value").path("max").asDouble())
        assertEquals("MRUB", money.path("canonical").path("unit").asText())
        assertEquals(7000.0, money.path("canonical").path("value").path("min").asDouble())
        assertEquals(9000.0, money.path("canonical").path("value").path("max").asDouble())
        assertTrue(money.path("converted_from").asText().startsWith("7–9 млрд"))

        val minutes = numbers.first { it.path("unit").asText() == "min" }
        assertEquals(1800.0, minutes.path("canonical").path("value").asDouble())
        assertEquals("s", minutes.path("canonical").path("unit").asText())
        assertEquals("30 мин", minutes.path("converted_from").asText())

        numbers.forEach { n ->
            assertTrue(n.path("block").asText().isNotBlank()) { "величина без координаты блока" }
        }
    }

    @Test
    fun `нормативные обозначения - кандидатами с координатой блока`() {
        val parsed = parsed()
        val mentions = parsed.map.path("normative_candidates").map { it.path("mention").asText() }
        assertTrue(mentions.any { it.startsWith("ГОСТ Р 53802") }) { mentions.toString() }
        assertTrue(mentions.any { it.startsWith("ПП № 1279") }) { mentions.toString() }
        parsed.map.path("normative_candidates").forEach {
            assertTrue(it.path("block").asText().startsWith("b")) { "норматив без координаты блока" }
        }
    }

    @Test
    fun `сводка разбора - счётчики фикстуры`() {
        val summary = parsed().map.path("summary")
        assertEquals(paragraphs.size + 1, summary.path("blocks").asInt()) { "титул и абзацы" }
        assertEquals(4, summary.path("sections").asInt()) { "раздел-заголовок таблицы свёрнут в неё" }
        assertEquals(1, summary.path("tables").asInt())
        assertTrue(summary.path("canon_chars").asInt() >= summary.path("source_chars").asInt())
    }
}
