// Д1: приёмка разбора — эталоны пачки-1 (ЗАПИСКА-КАНОН.md / АНАЛИЗ-КАНОН.md).
// Меры решения: потеря текста 0 (канон несёт ВЕСЬ текст документа), блоки с
// координатами, таблицы — MD-таблицами с адресом строки по ключевой колонке,
// числа с единицами — каноном справочника с происхождением.
package orbita.out

import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class DocumentParseTest {

    /** Словари системы — как их подаёт полка LIB (сид 07/08). */
    private val lexicon = ParseLexicon(
        unitSpellings = mapOf(
            "млрд руб" to "BRUB", "млрд руб." to "BRUB", "млн руб" to "MRUB",
            "года" to "a", "лет" to "a", "%" to "%", "мин" to "min", "с" to "s",
        ),
        terms = listOf("Карта спроса", "Зона обслуживания"),
        toCanon = { v, u ->
            when (u) {
                "BRUB" -> v * 1000 to "MRUB"
                "a" -> v * 31_557_600 to "s"
                "min" -> v * 60 to "s"
                "%" -> v / 100 to "1"
                else -> null
            }
        },
    )

    /** Документ строится POI — тем же форматом, каким приходит от владельца. */
    private fun docx(build: XWPFDocument.() -> Unit): ByteArray =
        XWPFDocument().use { doc ->
            doc.build()
            ByteArrayOutputStream().use { out -> doc.write(out); out.toByteArray() }
        }

    private fun XWPFDocument.heading(text: String, level: Int) {
        val p = createParagraph()
        p.style = if (level == 0) "Title" else "Heading$level"
        p.createRun().setText(text)
    }

    private fun XWPFDocument.para(text: String) {
        createParagraph().createRun().setText(text)
    }

    private fun ByteArray.parsed() = DocumentParse.parse("d.docx", this, lexicon)!!

    @Test
    fun `канон несёт весь текст документа - потеря 0`() {
        val texts = listOf(
            "Действующая нормативная база формирует обязательный спрос на массовую передачу телеметрии.",
            "Ориентировочный объем инвестиций первого этапа — 7–9 млрд руб.",
            "Этап 1 (0–2 года) — развертывание MVP.",
        )
        val parsed = docx {
            heading("О формировании национальной спутниковой IoT-платформы", 0)
            heading("1. Обоснование", 1)
            texts.forEach { para(it) }
        }.parsed()

        texts.forEach { assertTrue(it in parsed.canonMd) { "потерян текст: $it" } }
        assertTrue("О формировании национальной спутниковой IoT-платформы" in parsed.canonMd)
        val summary = parsed.map.path("summary")
        assertEquals(summary.path("source_chars").asInt(), summary.path("source_chars").asInt())
        assertTrue(summary.path("canon_chars").asInt() >= summary.path("source_chars").asInt()) {
            "канон короче исходного текста — потеря"
        }
    }

    @Test
    fun `блоки и разделы получают координаты - якорями канона`() {
        val parsed = docx {
            heading("Записка", 0)
            heading("1. Обоснование", 1)
            para("Первый абзац обоснования.")
            para("Второй абзац обоснования.")
            heading("2. Модель реализации", 1)
            para("Этапы программы.")
        }.parsed()

        assertTrue("{#b0}" in parsed.canonMd && "{#s1}" in parsed.canonMd && "{#s2}" in parsed.canonMd)
        assertTrue("<!-- b1 -->" in parsed.canonMd && "<!-- b2 -->" in parsed.canonMd)

        val structure = parsed.map.path("structure")
        assertEquals("title", structure[0].path("type").asText())
        val s1 = structure.first { it.path("anchor").asText() == "s1" }
        assertEquals("1. Обоснование", s1.path("title").asText())
        assertEquals(listOf("b1", "b2"), s1.path("blocks").map { it.asText() })
        val s2 = structure.first { it.path("anchor").asText() == "s2" }
        assertEquals(listOf("b3"), s2.path("blocks").map { it.asText() })
    }

    @Test
    fun `таблица - MD-таблицей и картой со строками и ключевой колонкой`() {
        val parsed = docx {
            heading("Оценка идей использования спутникового IoT", 0)
            heading("Таблица оценок", 1)
            createTable(4, 3).also { t ->
                listOf(
                    listOf("№", "Идея", "Пользователи"),
                    listOf("1", "Трубопроводы, ЛЭП", "7"),
                    listOf("2", "Промышленный Север", "6"),
                    listOf("3", "Агро: поля, техника", "8"),
                ).forEachIndexed { r, row ->
                    row.forEachIndexed { c, v -> t.getRow(r).getCell(c).text = v }
                }
            }
        }.parsed()

        assertTrue("| № | Идея | Пользователи |" in parsed.canonMd) { parsed.canonMd }
        assertTrue("| 1 | Трубопроводы, ЛЭП | 7 |" in parsed.canonMd)
        val table = parsed.map.path("structure").first { it.path("type").asText() == "table" }
        assertEquals("t1", table.path("anchor").asText())
        assertEquals(3, table.path("rows").asInt())
        assertEquals("№", table.path("row_key").asText())
        assertEquals(listOf("Идея", "Пользователи"), table.path("cols").map { it.asText() })
    }

    @Test
    fun `числа с единицами - каноном справочника с происхождением`() {
        val parsed = docx {
            heading("Записка", 0)
            para("Ориентировочный объем инвестиций первого этапа — 7–9 млрд руб.")
            para("Срок доставки сообщения не превышает 30 мин.")
        }.parsed()

        val numbers = parsed.map.path("numbers")
        val money = numbers.first { it.path("unit").asText() == "BRUB" }
        assertEquals(7.0, money.path("value").path("min").asDouble())
        assertEquals(9.0, money.path("value").path("max").asDouble())
        assertEquals("MRUB", money.path("canonical").path("unit").asText())
        assertEquals(7000.0, money.path("canonical").path("value").path("min").asDouble())
        assertTrue(money.path("converted_from").asText().startsWith("7–9 млрд руб"))

        val minutes = numbers.first { it.path("unit").asText() == "min" }
        assertEquals(1800.0, minutes.path("canonical").path("value").asDouble())
        assertEquals("s", minutes.path("canonical").path("unit").asText())
        assertEquals("30 мин", minutes.path("converted_from").asText())
    }

    @Test
    fun `нормативы и термы - кандидатами с координатой блока`() {
        val parsed = docx {
            heading("Записка", 0)
            para("Требование установлено ГОСТ Р 53802-2010 и постановлением ПП № 1279.")
            para("Карта спроса строится по классам потребителей.")
        }.parsed()

        val mentions = parsed.map.path("normative_candidates").map { it.path("mention").asText() }
        assertTrue(mentions.any { it.startsWith("ГОСТ Р 53802") }) { mentions.toString() }
        assertTrue(mentions.any { it.startsWith("ПП № 1279") }) { mentions.toString() }
        parsed.map.path("normative_candidates").forEach {
            assertTrue(it.path("block").asText().startsWith("b")) { "норматив без координаты блока" }
        }
        val term = parsed.map.path("terms").first { it.path("term").asText() == "Карта спроса" }
        assertEquals(listOf("b2"), term.path("blocks").map { it.asText() })
    }

    @Test
    fun `отпечаток разбора - по хешу файла и версии разборщика`() {
        val bytes = docx { heading("Записка", 0); para("Текст.") }
        val other = docx { heading("Записка", 0); para("Иной текст.") }
        assertEquals(DocumentParse.fingerprint(bytes, lexicon), DocumentParse.fingerprint(bytes, lexicon))
        assertTrue(DocumentParse.fingerprint(bytes, lexicon) != DocumentParse.fingerprint(other, lexicon))
        assertTrue("-v${DocumentParse.VERSION}-" in DocumentParse.fingerprint(bytes, lexicon))
        // правка словарей — новый разбор: добавленная единица обязана увидеться
        val richer = ParseLexicon(lexicon.unitSpellings + ("тыс. т" to "t"), lexicon.terms, lexicon.toCanon)
        assertTrue(DocumentParse.fingerprint(bytes, lexicon) != DocumentParse.fingerprint(bytes, richer))
    }

    @Test
    fun `формат вне списка - разбора нет, приём документа это не ломает`() {
        assertNotNull(DocumentParse.parse("d.md", "# Записка\n\nТекст абзаца.".toByteArray(), lexicon))
        assertEquals(null, DocumentParse.parse("d.xlsx", byteArrayOf(1, 2, 3), lexicon))
    }
}
