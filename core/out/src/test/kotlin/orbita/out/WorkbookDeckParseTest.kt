// Д3: книга и презентация — теми же двумя слоями, что docx и pdf. Фикстуры
// синтетические (решение владельца): их строит сам тест.
//
// Меры: лист книги становится разделом, его данные — MD-таблицей с ключевой
// колонкой; длинный лист не топит канон — он уходит приложением-CSV с пометой
// деградации; слайд презентации становится разделом, тезисы — блоками.
package orbita.out

import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class WorkbookDeckParseTest {

    private val lexicon = ParseLexicon(
        unitSpellings = mapOf("кг" to "kg", "вт" to "W"),
        toCanon = { v, u -> if (u == "kg") v to "kg" else null },
    )

    private fun workbook(rows: Int): ByteArray = XSSFWorkbook().use { book ->
        val sheet = book.createSheet("Ведомость масс")
        val header = sheet.createRow(0)
        listOf("Позиция", "Масса", "Примечание").forEachIndexed { c, v ->
            header.createCell(c).setCellValue(v)
        }
        (1..rows).forEach { r ->
            val row = sheet.createRow(r)
            row.createCell(0).setCellValue("Узел $r")
            row.createCell(1).setCellValue("$r,5 кг")
            row.createCell(2).setCellValue("строка $r")
        }
        val second = book.createSheet("Условия")
        second.createRow(0).createCell(0).setCellValue("Средняя мощность 12 Вт")
        ByteArrayOutputStream().use { out -> book.write(out); out.toByteArray() }
    }

    private fun deck(): ByteArray = XMLSlideShow().use { show ->
        listOf(
            listOf("Концепция группировки", "Низкая орбита", "Регенеративная нагрузка"),
            listOf("Этапы", "MVP за два года", "Национальная система за пять"),
        ).forEach { texts ->
            val slide = show.createSlide()
            texts.forEach { t ->
                val box = slide.createTextBox()
                box.setText(t)
            }
        }
        ByteArrayOutputStream().use { out -> show.write(out); out.toByteArray() }
    }

    @Test
    fun `лист книги — раздел, данные — таблица с ключевой колонкой`() {
        val parsed = DocumentParse.parse("ведомость.xlsx", workbook(6), lexicon)!!
        val canon = parsed.canonMd
        assertTrue("# Ведомость масс {#b0}" in canon) { canon.take(200) }
        assertTrue("| Позиция | Масса | Примечание |" in canon)
        assertTrue("| Узел 3 | 3,5 кг | строка 3 |" in canon)

        val table = parsed.map.path("structure").first { it.path("type").asText() == "table" }
        assertEquals(6, table.path("rows").asInt())
        assertEquals("Позиция", table.path("row_key").asText())
        assertEquals(listOf("Масса", "Примечание"), table.path("cols").map { it.asText() })

        // второй лист — свой раздел, его текст в каноне
        assertTrue("Условия" in canon && "Средняя мощность 12 Вт" in canon)
        assertTrue(parsed.appendices.isEmpty()) { "короткий лист приложения не требует" }
    }

    @Test
    fun `длинный лист — приложением-CSV с пометой деградации`() {
        val rows = DocumentParse.CSV_ROW_LIMIT + 20
        val parsed = DocumentParse.parse("большая.xlsx", workbook(rows), lexicon)!!
        val table = parsed.map.path("structure").first { it.path("type").asText() == "table" }
        assertEquals(DocumentParse.CSV_ROW_LIMIT, table.path("rows").asInt()) { "в каноне — начало листа" }
        assertEquals(rows, table.path("rows_total").asInt())
        assertTrue(table.path("degraded").asBoolean())
        assertEquals("t1.csv", table.path("csv").asText())
        assertTrue("деградация: показаны первые" in parsed.canonMd)

        val csv = parsed.appendices["t1.csv"]!!
        assertEquals(rows + 1, csv.trim().lines().size) { "в приложении — шапка и все строки" }
        assertTrue(csv.lines().first().startsWith("Позиция,Масса"))
        assertTrue(csv.contains("Узел $rows"))
    }

    @Test
    fun `слайд — раздел, тезисы — блоки с координатами`() {
        val parsed = DocumentParse.parse("концепция.pptx", deck(), lexicon)!!
        val canon = parsed.canonMd
        assertTrue("# Концепция группировки {#b0}" in canon) { canon.take(200) }
        assertTrue("## Этапы {#s1}" in canon) { canon }
        assertTrue("- Низкая орбита" in canon && "- MVP за два года" in canon)

        val structure = parsed.map.path("structure")
        assertEquals("title", structure[0].path("type").asText())
        val slide = structure.first { it.path("type").asText() == "section" }
        assertEquals("Этапы", slide.path("title").asText())
        assertTrue(slide.path("blocks").size() >= 2)
    }

    @Test
    fun `величины книги канонизируются, как и в документе`() {
        val parsed = DocumentParse.parse("ведомость.xlsx", workbook(3), lexicon)!!
        val numbers = parsed.map.path("numbers")
        assertTrue(numbers.size() > 0) { "числа листа обязаны попасть в карту" }
        assertTrue(numbers.any { it.path("unit").asText() == "kg" }) { numbers.toString().take(200) }
        numbers.forEach { assertTrue(it.path("block").asText().isNotBlank()) }
    }
}
