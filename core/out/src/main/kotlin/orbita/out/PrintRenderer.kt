// Печатная форма выпуска (В1.4/О-8): docx и PDF с сервера — пакет точки
// уходит людям без Орбиты. Оформление черновое данными (титул, разделы,
// колонтитулы); подтянется вёрсткой по эталону печатной формы Design.
package orbita.out

import com.fasterxml.jackson.databind.JsonNode
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.poi.xwpf.usermodel.ParagraphAlignment
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.ByteArrayOutputStream
import java.io.File

/** Реквизиты печати: обозначение, версия, статус, дата, автор, проект. */
data class PrintMeta(
    val project: String,
    val designation: String,
    val version: String,
    val status: String,
    val issuedAt: String,
    val author: String,
)

class PrintRenderer {

    /** Выпуск документом Word: титул, разделы с текстом и записями, колонтитулы. */
    fun docx(body: JsonNode, meta: PrintMeta): ByteArray {
        val doc = XWPFDocument()

        // колонтитулы данными: проект · документ | обозначение · версия · стр.
        val header = doc.createHeader(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT)
        header.createParagraph().apply {
            alignment = ParagraphAlignment.RIGHT
            createRun().apply { fontSize = 8; setText("${meta.project} · ${body.path("title").asText("")}") }
        }
        val footer = doc.createFooter(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT)
        footer.createParagraph().apply {
            alignment = ParagraphAlignment.RIGHT
            createRun().apply { fontSize = 8; setText("${meta.designation} · в. ${meta.version} · лист ") }
            // номер страницы полем
            ctp.addNewFldSimple().instr = "PAGE"
        }

        // титул
        doc.createParagraph().apply {
            alignment = ParagraphAlignment.CENTER
            spacingBefore = 2400
            createRun().apply { isBold = true; fontSize = 20; setText(body.path("title").asText("")) }
        }
        doc.createParagraph().apply {
            alignment = ParagraphAlignment.CENTER
            createRun().apply { fontSize = 12; setText(meta.project) }
        }
        doc.createParagraph().apply {
            alignment = ParagraphAlignment.CENTER
            createRun().apply {
                fontSize = 11
                setText("${meta.designation} · версия ${meta.version} · ${meta.status} · ${meta.issuedAt}")
            }
        }
        doc.createParagraph().apply {
            alignment = ParagraphAlignment.CENTER
            createRun().apply { fontSize = 10; setText("Структура: ${body.path("source").asText("")}") }
        }
        doc.createParagraph().apply {
            alignment = ParagraphAlignment.CENTER
            createRun().apply { fontSize = 10; setText("Выпустил: ${meta.author}") }
            isPageBreak = true
        }

        // разделы: заголовок, авторский текст, записи вставок
        body.path("sections").forEach { s ->
            doc.createParagraph().apply {
                createRun().apply {
                    isBold = true; fontSize = 14
                    setText("${s.path("number").asInt()}. ${s.path("title").asText("")}")
                }
            }
            s.path("text").asText("").takeIf { it.isNotBlank() }?.let { text ->
                text.split("\n").forEach { line ->
                    doc.createParagraph().createRun().apply { fontSize = 11; setText(line) }
                }
            }
            val items = s.path("items")
            if (items.size() > 0) {
                val table = doc.createTable(items.size(), 2)
                table.setWidth("100%")
                table.rows.forEachIndexed { i, row ->
                    val item = items[i]
                    // вставка печатается по-человечески: веха словами, узел
                    // именем, поля по-русски (PrintHumanizer); id — только у
                    // записей с обозначением (требование, нужда, риск)
                    val id = designation(item)
                    row.getCell(0).apply { text = id; widthType = org.apache.poi.xwpf.usermodel.TableWidthType.DXA; setWidth("1600") }
                    row.getCell(1).text = PrintHumanizer.line(item)
                }
            }
            if (items.size() == 0 && s.path("text").asText("").isBlank()) {
                doc.createParagraph().createRun().apply {
                    fontSize = 10; isItalic = true
                    setText("— раздел пуст: ${s.path("expects").asText("")}")
                }
            }
        }

        val out = ByteArrayOutputStream()
        doc.use { it.write(out) }
        return out.toByteArray()
    }

    /** Выпуск PDF: кириллический шрифт обязан быть встраиваемым (DejaVu). */
    fun pdf(body: JsonNode, meta: PrintMeta): ByteArray {
        val fontFile = FONT_PATHS.map(::File).firstOrNull { it.exists() }
            ?: error(
                "printable font not found: положите DejaVuSans.ttf по одному из путей " +
                    FONT_PATHS.joinToString() + " либо задайте ORBITA_PRINT_FONT",
            )
        val boldFile = File(fontFile.parentFile, "DejaVuSans-Bold.ttf").takeIf { it.exists() } ?: fontFile

        PDDocument().use { pdf ->
            val font = PDType0Font.load(pdf, fontFile)
            val bold = PDType0Font.load(pdf, boldFile)
            val writer = PageWriter(pdf, font, bold, meta, body.path("title").asText(""))

            writer.centered(bold, 20f, body.path("title").asText(""), yStart = 620f)
            writer.centered(font, 12f, meta.project)
            writer.centered(font, 11f, "${meta.designation} · версия ${meta.version} · ${meta.status} · ${meta.issuedAt}")
            writer.centered(font, 10f, "Структура: ${body.path("source").asText("")}")
            writer.centered(font, 10f, "Выпустил: ${meta.author}")
            writer.newPage()

            body.path("sections").forEach { s ->
                writer.heading("${s.path("number").asInt()}. ${s.path("title").asText("")}")
                s.path("text").asText("").takeIf { it.isNotBlank() }?.let { writer.paragraph(it) }
                s.path("items").forEach { item ->
                    writer.paragraph(PrintHumanizer.line(item), size = 9f, indent = 12f)
                }
                if (s.path("items").size() == 0 && s.path("text").asText("").isBlank()) {
                    writer.paragraph("— раздел пуст: ${s.path("expects").asText("")}", size = 9f, indent = 12f)
                }
            }
            writer.close()
            val out = ByteArrayOutputStream()
            pdf.save(out)
            return out.toByteArray()
        }
    }

    /**
     * Обозначение в первой колонке — только у записей, где id и есть
     * обозначение документа (требование, нужда, риск, решение). Узлы,
     * стейкхолдеры и станции печатаются именем: их id — подсказка экрана.
     */
    private fun designation(item: JsonNode): String {
        val id = item.path("id").asText("")
        val record = item.has("statement") || item.has("question") || item.has("target") ||
            item.has("trl_current") || item.has("deorbit_years") || item.has("basis")
        return if (record) id else ""
    }

    /**
     * Весь печатный текст документа строками — им пользуется сторож печати:
     * латинский служебный ключ в любой строке — отказ выпуска.
     */
    fun lines(body: JsonNode): List<String> = buildList {
        add(body.path("title").asText(""))
        body.path("sections").forEach { s ->
            add("${s.path("number").asInt()}. ${s.path("title").asText("")}")
            s.path("text").asText("").takeIf { it.isNotBlank() }?.let { addAll(it.split("\n")) }
            s.path("items").forEach { add(PrintHumanizer.line(it)) }
        }
    }

    /** Постраничный писатель: перенос строк по ширине, колонтитул на каждой странице. */
    private class PageWriter(
        private val pdf: PDDocument,
        private val font: PDFont,
        private val bold: PDFont,
        private val meta: PrintMeta,
        private val docTitle: String,
    ) {
        private val pageSize = PDRectangle.A4
        private val margin = 56f
        private var page = PDPage(pageSize)
        private var cs: PDPageContentStream
        private var y = 0f
        private var pageNo = 0

        init {
            pdf.addPage(page)
            cs = open()
        }

        private fun open(): PDPageContentStream {
            pageNo += 1
            val stream = PDPageContentStream(pdf, page)
            // колонтитулы данными
            stream.beginText()
            stream.setFont(font, 7f)
            stream.newLineAtOffset(margin, pageSize.height - 30f)
            stream.showText("${meta.project} · $docTitle")
            stream.endText()
            stream.beginText()
            stream.setFont(font, 7f)
            stream.newLineAtOffset(margin, 24f)
            stream.showText("${meta.designation} · в. ${meta.version} · лист $pageNo")
            stream.endText()
            y = pageSize.height - margin
            return stream
        }

        fun newPage() {
            cs.close()
            page = PDPage(pageSize)
            pdf.addPage(page)
            cs = open()
        }

        private fun ensure(height: Float) {
            if (y - height < margin) newPage()
        }

        private fun wrap(text: String, f: PDFont, size: Float, width: Float): List<String> {
            val out = mutableListOf<String>()
            text.split("\n").forEach { raw ->
                var line = StringBuilder()
                raw.split(" ").forEach { word ->
                    val probe = if (line.isEmpty()) word else "$line $word"
                    val w = runCatching { f.getStringWidth(probe) / 1000 * size }.getOrDefault(0f)
                    if (w > width && line.isNotEmpty()) {
                        out += line.toString(); line = StringBuilder(word)
                    } else line = StringBuilder(probe)
                }
                out += line.toString()
            }
            return out
        }

        private fun show(f: PDFont, size: Float, text: String, x: Float) {
            ensure(size + 4f)
            // NBSP и глиф вне шрифта не валят печать целиком: непечатаемое
            // заменяется, страница выходит
            val printable = text.replace('\u00A0', ' ').map { ch ->
                if (runCatching { f.encode(ch.toString()) }.isSuccess) ch else '·'
            }.joinToString("")
            cs.beginText()
            cs.setFont(f, size)
            cs.newLineAtOffset(x, y)
            cs.showText(printable)
            cs.endText()
            y -= size + 4f
        }

        fun centered(f: PDFont, size: Float, text: String, yStart: Float? = null) {
            yStart?.let { y = it }
            val w = runCatching { f.getStringWidth(text) / 1000 * size }.getOrDefault(0f)
            show(f, size, text, ((pageSize.width - w) / 2).coerceAtLeast(margin))
        }

        fun heading(text: String) {
            y -= 8f
            wrap(text, bold, 13f, pageSize.width - 2 * margin).forEach { show(bold, 13f, it, margin) }
        }

        fun paragraph(text: String, size: Float = 10.5f, indent: Float = 0f) {
            wrap(text, font, size, pageSize.width - 2 * margin - indent)
                .forEach { show(font, size, it, margin + indent) }
        }

        fun close() = cs.close()
    }

    companion object {
        /** Пути кириллического шрифта; первый — Ubuntu-образ api. */
        private val FONT_PATHS: List<String> = buildList {
            System.getenv("ORBITA_PRINT_FONT")?.let { add(it) }
            add("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf")
            add("/usr/share/fonts/dejavu/DejaVuSans.ttf")
        }
    }
}
