// Д1 (РЕШЕНИЕ-РАЗБОР-ДОКУМЕНТОВ.md): детерминированный разбор документа при
// загрузке — без LLM. Два слоя: MD-канон (100% текста, якоря блоков) и
// JSON-карта (структура и находки, текста НЕ хранит — только якоря). Всё,
// что потребляет документ дальше — промпты, извлечения, поиск — читает эти
// слои, а не файл: службе сырой docx больше не отдаётся никогда.
//
// Разбор — чистая функция от файла и словарей системы (справочник единиц,
// глоссарий): числа канонизируются по справочнику, термы помечаются, что
// делает разбор воспроизводимым и версионируемым по хешу.
package orbita.out

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFTextShape
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFTable
import java.io.ByteArrayInputStream
import java.security.MessageDigest

/**
 * Словари системы для разбора: числа канонизируются справочником единиц,
 * термы — глоссарием. Оба приходят данными полки LIB (правка — правкой
 * полки, не кода); пустые словари не ломают разбор, а сужают находки.
 */
class ParseLexicon(
    /** Написание единицы в тексте → имя единицы справочника («млрд руб.» → BRUB). */
    val unitSpellings: Map<String, String> = emptyMap(),
    /** Термы глоссария: терм → его запись (для связи «упоминает»). */
    val terms: List<String> = emptyList(),
    /** Конверсия в канон; null — уже канон либо конверсии нет по определению. */
    val toCanon: (Double, String) -> Pair<Double, String>? = { _, _ -> null },
) {
    /**
     * Отпечаток словарей: разбор — функция файла И словарей, поэтому правка
     * справочника единиц или глоссария обязана давать НОВЫЙ разбор, а не
     * молча возвращать прежний (иначе добавленная единица не увидится).
     */
    fun fingerprint(): String {
        val material = unitSpellings.toSortedMap().entries.joinToString(";") { "${it.key}=${it.value}" } +
            "|" + terms.sorted().joinToString(";")
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray()).joinToString("") { "%02x".format(it) }.take(6)
    }
}

/**
 * Разбор: канон-текст (MD), карта (JSON, без текста документа) и приложения —
 * длинные листы книги, вынесенные из канона в CSV (Д3).
 */
class ParsedDocument(
    val canonMd: String,
    val map: ObjectNode,
    val appendices: Map<String, String> = emptyMap(),
)

object DocumentParse {

    /** Версия разборщика: входит в имя разбора — смена версии переиндексирует. */
    const val VERSION = 3

    /** Длиннее — лист уходит приложением-CSV, в канон идёт начало с пометой. */
    const val CSV_ROW_LIMIT = 50

    private val mapper = ObjectMapper()

    /** Обозначения нормативных актов — кандидаты на связь с normative_document. */
    private val NORMATIVE = Regex(
        """(ГОСТ\s+Р?\s*[\d.\-–—]+(?:-\d{2,4})?|ПП\s*№\s*\d+|""" +
            """Постановлени[ея]\s+Правительства[^.,;]{0,60}|""" +
            """Федеральн\w+\s+закон\w*[^.,;]{0,60}|ФЗ\s*[-–—]?\s*№?\s*\d+|""" +
            """ГОСТ|ОСТ\s+\d[\d.\-]*|СП\s+\d[\d.\-]*|ITU-[RT]\s+[A-Z]?\.?\d+)""",
    )

    /**
     * Ф-08.3: метка источника утверждения в начале блока — [И] из источника,
     * [В] вывод автора, [П] предложение. Метка попадает координатой в карту:
     * предложение автора («1 млн терминалов») не путается с внешним фактом.
     */
    private val SOURCE_MARK = Regex("""^\s*\[([ИВП])]\s*""")

    /** Число и диапазон: «7–9 …», «30 …», «75–80…»; единица — словарём. */
    private val NUMBER = Regex(
        """(?<![\w,.])(\d{1,3}(?:\s\d{3})*(?:[.,]\d+)?)""" +
            """(?:\s*[-–—]\s*(\d{1,3}(?:\s\d{3})*(?:[.,]\d+)?))?\s*""",
    )

    /**
     * Единица после числа — САМЫМ ДЛИННЫМ написанием справочника («млрд руб.»
     * раньше «млрд»), и только на границе слова: иначе «5 систем» прочлось бы
     * секундами. Написания — данные полки, порядок разбора — код.
     */
    private fun unitAt(text: String, from: Int, spellings: Map<String, String>): Pair<String, String>? {
        val tail = text.substring(from.coerceAtMost(text.length)).take(24).lowercase()
        var best: Pair<String, String>? = null
        spellings.forEach { (spelling, unit) ->
            if (tail.startsWith(spelling) && spelling.length > (best?.first?.length ?: 0)) {
                val next = tail.getOrNull(spelling.length)
                if (next == null || !next.isLetterOrDigit()) best = spelling to unit
            }
        }
        return best
    }

    /** Разбор файла; null — формат не читается (это не ошибка приёма). */
    fun parse(fileName: String, bytes: ByteArray, lexicon: ParseLexicon = ParseLexicon()): ParsedDocument? =
        runCatching {
            when {
                fileName.endsWith(".docx", true) -> parseDocx(bytes, lexicon)
                fileName.endsWith(".xlsx", true) -> parseXlsx(bytes, lexicon)
                fileName.endsWith(".pptx", true) -> parsePptx(bytes, lexicon)
                fileName.endsWith(".pdf", true) -> parsePdf(bytes, lexicon)
                fileName.endsWith(".txt", true) || fileName.endsWith(".md", true) ->
                    parsePlain(bytes.decodeToString(), lexicon)
                else -> null
            }
        }.getOrNull()

    /** Отпечаток разбора: хеш файла + версия разборщика + отпечаток словарей. */
    fun fingerprint(bytes: ByteArray, lexicon: ParseLexicon = ParseLexicon()): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }.take(16) + "-v$VERSION-${lexicon.fingerprint()}"

    // ——— docx: структура по стилям (имя стиля и outlineLvl — не styleId) ———

    private fun parseDocx(bytes: ByteArray, lexicon: ParseLexicon): ParsedDocument =
        XWPFDocument(ByteArrayInputStream(bytes)).use { doc ->
            val b = Builder(lexicon)
            doc.bodyElements.forEach { el ->
                when (el) {
                    is XWPFParagraph -> {
                        val text = el.text.trim()
                        if (text.isNotEmpty()) {
                            val level = headingLevel(doc, el)
                            when {
                                // заголовок первой строкой — титул документа,
                                // каким бы стилем он ни был набран (Title или
                                // «Заголовок 1»): эталон анализа ждёт {#b0}
                                level == 0 || (level > 0 && b.isEmpty) -> b.title(text)
                                level > 0 -> b.section(text, level)
                                else -> b.para(text, listItem = el.numID != null)
                            }
                        }
                    }
                    is XWPFTable -> b.table(el)
                    else -> Unit
                }
            }
            b.build()
        }

    /**
     * Уровень заголовка: 0 — титул, 1..9 — раздел, -1 — обычный абзац.
     * Опора — имя стиля из styles.xml («Title», «heading 2») и outlineLvl:
     * идентификаторы стилей у разных редакторов произвольны (LibreOffice
     * пишет Style_3 там, где Word пишет Heading1), имена — стабильны.
     */
    private fun headingLevel(doc: XWPFDocument, p: XWPFParagraph): Int {
        val styleId = p.styleID ?: return -1
        val style = doc.styles?.getStyle(styleId)
        val name = style?.name?.lowercase()
        if (name != null) {
            if (name == "title") return 0
            val outline = style.ctStyle?.pPr?.outlineLvl?.`val`?.toInt()
            if (outline != null && outline in 0..8) return outline + 1
            Regex("""heading\s*(\d)""").find(name)?.let { return it.groupValues[1].toInt() }
            if (name != styleId.lowercase()) return -1
        }
        // стиль не описан в styles.xml — читаем сам идентификатор: Word
        // пишет Heading2/Title там, где LibreOffice завёл бы Style_N
        val id = styleId.lowercase()
        if (id == "title" || id == "заголовок") return 0
        Regex("""(?:heading|заголовок)\s*(\d)""").find(id)?.let { return it.groupValues[1].toInt() }
        return -1
    }

    // ——— xlsx: лист — раздел, таблица листа — MD-таблица ———

    /**
     * Д3: книга разбирается листами. Лист становится разделом, его данные —
     * MD-таблицей с шапкой из первой непустой строки; адрес строки — ключевой
     * колонкой, как у docx-таблиц. Длинный лист в канон целиком не идёт:
     * он помечается деградацией и выкладывается приложением-CSV (решение
     * владельца) — иначе канон превращается в свалку чисел.
     */
    private fun parseXlsx(bytes: ByteArray, lexicon: ParseLexicon): ParsedDocument =
        XSSFWorkbook(ByteArrayInputStream(bytes)).use { book ->
            val b = Builder(lexicon)
            val formatter = DataFormatter()
            var titled = false
            for (i in 0 until book.numberOfSheets) {
                val sheet = book.getSheetAt(i)
                val rows = mutableListOf<List<String>>()
                for (r in 0..sheet.lastRowNum) {
                    val row = sheet.getRow(r) ?: continue
                    val cells = (0 until row.lastCellNum.toInt().coerceAtLeast(0)).map { c ->
                        formatter.formatCellValue(row.getCell(c)).trim()
                    }
                    if (cells.any { it.isNotEmpty() }) rows += cells
                }
                if (!titled) {
                    b.title(book.getSheetName(i).ifBlank { "Книга" })
                    titled = true
                } else {
                    b.section(book.getSheetName(i).ifBlank { "Лист ${i + 1}" }, 1)
                }
                if (rows.isEmpty()) {
                    b.para("Лист пуст.")
                    continue
                }
                if (rows.size == 1) {
                    b.para(rows.first().filter { it.isNotEmpty() }.joinToString("; "))
                } else {
                    b.rowsTable(rows, book.getSheetName(i), CSV_ROW_LIMIT)
                }
            }
            b.build()
        }

    // ——— pptx: слайд — раздел, тезисы — абзацы ———

    /** Д3: слайд становится разделом, его тезисы — блоками; номер слайда — в якоре. */
    private fun parsePptx(bytes: ByteArray, lexicon: ParseLexicon): ParsedDocument =
        XMLSlideShow(ByteArrayInputStream(bytes)).use { deck ->
            val b = Builder(lexicon)
            deck.slides.forEachIndexed { index, slide ->
                val texts = slide.shapes.filterIsInstance<XSLFTextShape>()
                    .flatMap { shape -> shape.textParagraphs.map { it.text.trim() } }
                    .filter { it.isNotEmpty() }
                val heading = texts.firstOrNull() ?: "Слайд ${index + 1}"
                if (index == 0) b.title(heading) else b.section(heading, 1)
                texts.drop(1).forEach { b.para(it, listItem = true) }
                if (texts.size <= 1) b.para("Слайд без текста.")
            }
            b.build()
        }

    // ——— pdf: страница — единица координат (текст постранично) ———

    private fun parsePdf(bytes: ByteArray, lexicon: ParseLexicon): ParsedDocument =
        Loader.loadPDF(bytes).use { pdf ->
            val b = Builder(lexicon)
            val stripper = PDFTextStripper()
            for (page in 1..pdf.numberOfPages) {
                stripper.startPage = page
                stripper.endPage = page
                b.section("Страница $page", 1)
                stripper.getText(pdf).split(Regex("\n{2,}")).forEach { chunk ->
                    val text = chunk.replace(Regex("\\s*\n\\s*"), " ").trim()
                    if (text.isNotEmpty()) b.para(text)
                }
            }
            b.build()
        }

    // ——— txt/md: абзацы как есть; заголовки Markdown — разделами ———

    private fun parsePlain(text: String, lexicon: ParseLexicon): ParsedDocument {
        val b = Builder(lexicon)
        text.split(Regex("\n{2,}")).forEach { chunk ->
            val t = chunk.trim()
            if (t.isEmpty()) return@forEach
            val head = Regex("""^(#{1,6})\s+(.*)$""").find(t.lineSequence().first())
            when {
                head == null -> b.para(t)
                head.groupValues[1].length == 1 && b.isEmpty -> b.title(head.groupValues[2])
                else -> b.section(head.groupValues[2], head.groupValues[1].length)
            }
        }
        return b.build()
    }

    // ——— сборка обоих слоёв разом: текст в MD, координаты в карту ———

    /** Заполнитель глубины заголовка — заменяется решётками в build(). */
    private const val HEADING_MARK = "\u0000\u0000\u0000\u0000\u0000\u0000"

    private class Builder(val lexicon: ParseLexicon) {
        private val md = StringBuilder()
        private val structure: ArrayNode = mapper.createArrayNode()
        private val numbers: ArrayNode = mapper.createArrayNode()
        private val terms: ArrayNode = mapper.createArrayNode()
        private val normatives: ArrayNode = mapper.createArrayNode()
        /** Ф-08.3: метки источников блоков — [И]/[В]/[П] с координатой. */
        private val marks: ArrayNode = mapper.createArrayNode()
        private var blockNo = 0
        private var sectionNo = 0
        private var tableNo = 0
        private var currentSection: ObjectNode? = null
        /** Позиции меток заголовков и их уровни — глубина нормируется в build(). */
        private val headings = mutableListOf<Pair<Int, Int>>()
        /** Свёрнутые заголовки таблиц: якорь раздела → якорь таблицы. */
        private val foldedAnchors = mutableListOf<Pair<String, String>>()
        /** Длинные листы книги: якорь таблицы → все строки для CSV-приложения. */
        val csvAppendices = linkedMapOf<String, List<List<String>>>()
        private var lastWasSection = false
        private var sourceChars = 0

        val isEmpty: Boolean get() = blockNo == 0 && sectionNo == 0

        fun title(text: String) {
            val anchor = nextBlock()
            md.append("# ").append(text).append(" {#").append(anchor).append("}\n\n")
            structure.addObject().put("anchor", anchor).put("type", "title").put("title", text)
            lastWasSection = false
            harvest(text, anchor)
        }

        fun section(text: String, level: Int) {
            val anchor = "s${++sectionNo}"
            headings += md.length to level
            md.append(HEADING_MARK).append(' ')
                .append(text).append(" {#").append(anchor).append("}\n\n")
            currentSection = structure.addObject()
                .put("anchor", anchor).put("type", "section")
                .put("title", text).put("level", level)
            currentSection!!.putArray("blocks")
            lastWasSection = true
            harvest(text, anchor)
        }

        fun para(text: String, listItem: Boolean = false) {
            val anchor = nextBlock()
            val mark = SOURCE_MARK.find(text)?.groupValues?.get(1)
            md.append("<!-- ").append(anchor).append(" -->\n")
                .append(if (listItem) "- " else "").append(text).append("\n\n")
            lastWasSection = false
            val section = currentSection
            if (section != null) {
                (section.path("blocks") as ArrayNode).add(anchor)
            } else {
                structure.addObject().put("anchor", anchor).put("type", "para")
            }
            // метка живёт отдельным слоем карты: текст блока её сохраняет,
            // а координата «чем это является» становится машиночитаемой
            if (mark != null) marks.addObject().put("block", anchor).put("mark", mark)
            harvest(text, anchor)
        }

        fun table(t: XWPFTable) {
            val rows = t.rows.map { r -> r.tableCells.map { it.text.trim().replace("\n", " ") } }
            rowsTable(rows, null, Int.MAX_VALUE)
        }

        /**
         * Таблица строками — общая механика для docx и листов книги: якорь
         * комментарием, шапка первой строкой, адрес строки ключевой колонкой.
         * Длиннее предела — в канон идёт начало с пометой деградации, полный
         * лист выкладывается приложением-CSV (решение владельца по xlsx).
         */
        fun rowsTable(all: List<List<String>>, sheetName: String?, limit: Int) {
            // сравнение без limit + 1: у docx предел Int.MAX_VALUE, и
            // прибавление единицы переполняло счёт (разбор падал целиком)
            val rows = if (all.size - 1 > limit) all.take(limit + 1) else all
            val degraded = all.size > rows.size
            if (rows.isEmpty()) return
            // Раздел без абзацев прямо перед таблицей — не раздел, а ЗАГОЛОВОК
            // ТАБЛИЦЫ (так и в ручном эталоне: «## Таблица оценок {#t1}»).
            // Он сворачивается: запись раздела уходит из карты, его якорь в
            // каноне становится якорем таблицы. Текст заголовка остаётся —
            // канон ничего не теряет.
            val anchor = "t${++tableNo}"
            val own = currentSection?.takeIf { it.path("blocks").isEmpty && lastWasSection }
            val ownTitle = own?.path("title")?.asText()
            val outerSection = if (own != null) "" else currentSection?.path("anchor")?.asText() ?: ""
            if (own != null) {
                foldedAnchors += own.path("anchor").asText() to anchor
                structure.remove(structure.indexOf(own))
                currentSection = null
                sectionNo--
            }
            val header = rows.first()
            val body = rows.drop(1)
            // якорь — комментарием: канон не выдумывает заголовков, которых
            // в документе нет; человеческое имя таблицы живёт в карте
            md.append("<!-- ").append(anchor).append(" -->\n")
            md.append(header.joinToString(" | ", "| ", " |")).append('\n')
            md.append(header.joinToString(" | ", "| ", " |") { "---" }).append('\n')
            body.forEach { r ->
                md.append(r.joinToString(" | ", "| ", " |") { it.ifBlank { " " } }).append('\n')
            }
            if (degraded) {
                md.append("<!-- деградация: показаны первые ").append(body.size)
                    .append(" строк из ").append(all.size - 1)
                    .append("; полный лист — приложением-CSV ").append(anchor).append(".csv -->\n")
            }
            md.append('\n')
            val node = structure.addObject()
                .put("anchor", anchor).put("type", "table")
                .put("title", ownTitle ?: sheetName ?: "")
                .put("section", outerSection)
                .put("rows", body.size).put("row_key", header.firstOrNull() ?: "")
            if (degraded) {
                node.put("rows_total", all.size - 1)
                node.put("degraded", true)
                node.put("csv", "$anchor.csv")
                csvAppendices[anchor] = all
            }
            val cols = node.putArray("cols")
            header.drop(1).forEach { cols.add(it) }
            // адрес строки — ключевой колонкой (правило 10 промпта Д2: t1#15)
            rows.forEach { r -> r.forEach { sourceChars += it.length } }
            body.forEachIndexed { i, r ->
                val rowAnchor = "$anchor#${r.firstOrNull()?.ifBlank { (i + 1).toString() } ?: (i + 1)}"
                r.forEach { harvest(it, rowAnchor, countSource = false) }
            }
        }

        /** Находки блока: числа с единицами, термы глоссария, нормативы. */
        private fun harvest(text: String, anchor: String, countSource: Boolean = true) {
            if (countSource) sourceChars += text.length
            NUMBER.findAll(text).forEach { m ->
                val (spelling, unit) = unitAt(text, m.range.last + 1, lexicon.unitSpellings)
                    ?: return@forEach
                val from = m.groupValues[1].replace(" ", "").replace(',', '.').toDoubleOrNull()
                    ?: return@forEach
                val to = m.groupValues[2].takeIf { it.isNotBlank() }
                    ?.replace(" ", "")?.replace(',', '.')?.toDoubleOrNull()
                val node = numbers.addObject().put("block", anchor).put("unit", unit)
                if (to != null) {
                    node.putObject("value").put("min", from).put("max", to)
                    canonical(from, unit)?.let { (v, u) ->
                        val c = node.putObject("canonical")
                        c.put("unit", u)
                        c.putObject("value").put("min", v).put("max", canonical(to, unit)!!.first)
                        node.put("converted_from", "${trim(from)}–${trim(to)} $spelling")
                    }
                } else {
                    node.put("value", from)
                    canonical(from, unit)?.let { (v, u) ->
                        node.putObject("canonical").put("value", v).put("unit", u)
                        node.put("converted_from", "${trim(from)} $spelling")
                    }
                }
            }
            lexicon.terms.forEach { term ->
                if (text.contains(term, ignoreCase = true)) {
                    terms.addObject().put("term", term).put("block", anchor)
                }
            }
            NORMATIVE.findAll(text).forEach { m ->
                normatives.addObject().put("mention", m.value.trim()).put("block", anchor)
            }
        }

        private fun canonical(value: Double, unit: String): Pair<Double, String>? =
            runCatching { lexicon.toCanon(value, unit) }.getOrNull()

        private fun trim(v: Double): String =
            if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

        private fun nextBlock(): String = "b${blockNo++}"

        /** Приложение-CSV: лист целиком, экранирование — по RFC 4180. */
        private fun csv(rows: List<List<String>>): String = buildString {
            rows.forEach { row ->
                append(
                    row.joinToString(",") { cell ->
                        if (cell.any { it == ',' || it == '"' || it == '\n' }) {
                            "\"" + cell.replace("\"", "\"\"") + "\""
                        } else cell
                    },
                )
                append('\n')
            }
        }

        fun build(): ParsedDocument {
            val minLevel = headings.minOfOrNull { it.second } ?: 1
            val text = StringBuilder(md)
            foldedAnchors.forEach { (from, to) ->
                val at = text.indexOf("{#$from}")
                if (at >= 0) text.replace(at, at + from.length + 3, "{#$to}")
            }
            val folded = foldedAnchors.toMap()
            listOf(numbers, terms, normatives).forEach { arr ->
                arr.forEach { n ->
                    val block = n.path("block").asText()
                    folded[block]?.let { (n as ObjectNode).put("block", it) }
                }
            }
            headings.asReversed().forEach { (at, level) ->
                text.replace(at, at + HEADING_MARK.length, "#".repeat((2 + level - minLevel).coerceIn(2, 6)))
            }
            val canon = text.toString().trimEnd() + "\n"
            val map = mapper.createObjectNode()
            map.put("parser_version", VERSION)
            map.set<ArrayNode>("structure", structure)
            map.set<ArrayNode>("numbers", numbers)
            map.set<ArrayNode>("terms", dedupTerms())
            map.set<ArrayNode>("normative_candidates", normatives)
            map.set<ArrayNode>("source_marks", marks)
            map.putObject("summary")
                .put("blocks", blockNo)
                .put("sections", sectionNo)
                .put("tables", tableNo)
                .put("numbers", numbers.size())
                .put("terms", map.path("terms").size())
                .put("normative_candidates", normatives.size())
                .put("source_marks", marks.size())
                .put("source_chars", sourceChars)
                .put("canon_chars", canon.length)
            return ParsedDocument(
                canon, map,
                appendices = csvAppendices.entries.associate { (anchor, rows) -> "$anchor.csv" to csv(rows) },
            )
        }

        /** Терм упоминается многократно — одна запись со списком блоков. */
        private fun dedupTerms(): ArrayNode {
            val byTerm = linkedMapOf<String, MutableList<String>>()
            terms.forEach { t ->
                byTerm.getOrPut(t.path("term").asText()) { mutableListOf() }
                    .add(t.path("block").asText())
            }
            val out = mapper.createArrayNode()
            byTerm.forEach { (term, blocks) ->
                val node = out.addObject().put("term", term)
                val arr = node.putArray("blocks")
                blocks.distinct().forEach { arr.add(it) }
            }
            return out
        }
    }
}
