// Печать документа в PDF (ЗАДАНИЕ-ДОКУМЕНТЫ-СЕЙЧАС §2: печать HTML2PDF).
//
// Печать НАСТОЯЩАЯ: файл собирается PDFBox из тех же строк, что видит
// человек на экране. Запасного «похожего на PDF» вывода нет — нет шрифта,
// значит печать не состоялась, и об этом говорится прямо (правило
// DocPilot: строгий нативный режим, сбой не маскируется).
//
// Титул несёт проект, обозначение и полноту: печатный лист обязан сам
// отвечать, чей он и на какой момент.
package orbita.documents.internal

import orbita.documents.api.PrintView
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import java.io.ByteArrayOutputStream
import java.io.File

internal object PdfPrint {

    private val ШРИФТЫ = listOf(
        System.getenv("ORBITA_PRINT_FONT"),
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/Library/Fonts/DejaVuSans.ttf",
        "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
    ).filterNotNull()

    private const val ПОЛЕ = 56f
    private const val ВЕРХ = 780f
    private const val НИЗ = 60f
    private const val СТРОКА = 14f

    fun bytes(вид: PrintView, проект: String): ByteArray {
        val файлШрифта = ШРИФТЫ.map(::File).firstOrNull { it.isFile }
            ?: error(
                "печатного шрифта нет: положите DejaVuSans.ttf по одному из путей " +
                    ШРИФТЫ.joinToString() + " либо задайте ORBITA_PRINT_FONT",
            )
        val жирный = File(файлШрифта.parentFile, "DejaVuSans-Bold.ttf").takeIf { it.isFile } ?: файлШрифта

        PDDocument().use { pdf ->
            val обычный = PDType0Font.load(pdf, файлШрифта)
            val полужирный = PDType0Font.load(pdf, жирный)
            val лист = Лист(pdf, обычный, проект, вид.title)

            лист.поЦентру(полужирный, 18f, вид.title, 560f)
            лист.поЦентру(обычный, 11f, проект)
            лист.поЦентру(обычный, 10f, вид.subtitle)
            // Титул отвечает на два вопроса читателя: кто это писал и
            // зафиксировано ли состояние. Без них печатный лист — просто
            // бумага, за которую никто не отвечает.
            if (вид.authors.isNotEmpty()) {
                лист.поЦентру(обычный, 10f, "Авторы: " + вид.authors.joinToString(", "))
            }
            if (вид.baseline.isNotBlank()) лист.поЦентру(обычный, 10f, вид.baseline)
            лист.новый()

            вид.sections.forEach { раздел ->
                лист.строка(полужирный, 12f, "${раздел.no} ${раздел.title}")
                раздел.lines.forEach { стр -> лист.перенос(обычный, 10f, стр) }
                лист.пропуск()
            }
            лист.закрыть()
            val поток = ByteArrayOutputStream()
            pdf.save(поток)
            return поток.toByteArray()
        }
    }

    /** Лист с колонтитулом и переносом: ширина считается по шрифту. */
    private class Лист(
        private val pdf: PDDocument,
        private val обычный: PDType0Font,
        private val проект: String,
        private val заголовок: String,
    ) {
        private var страница = PDPage(PDRectangle.A4)
        private var поток: PDPageContentStream
        private var y = ВЕРХ
        private var номер = 1

        init {
            pdf.addPage(страница)
            поток = PDPageContentStream(pdf, страница)
            колонтитул()
        }

        fun поЦентру(шрифт: PDType0Font, размер: Float, текст: String, сверху: Float? = null) {
            сверху?.let { y = it }
            val ширина = шрифт.getStringWidth(текст) / 1000 * размер
            пиши(шрифт, размер, (PDRectangle.A4.width - ширина) / 2, текст)
            y -= размер + 6
        }

        fun строка(шрифт: PDType0Font, размер: Float, текст: String) {
            если()
            пиши(шрифт, размер, ПОЛЕ, текст)
            y -= размер + 4
        }

        /** Длинная строка переносится по ширине листа, а не уезжает за край. */
        fun перенос(шрифт: PDType0Font, размер: Float, текст: String) {
            val предел = PDRectangle.A4.width - 2 * ПОЛЕ
            var текущая = StringBuilder()
            текст.split(" ").forEach { слово ->
                val проба = if (текущая.isEmpty()) слово else "$текущая $слово"
                if (шрифт.getStringWidth(проба) / 1000 * размер > предел && текущая.isNotEmpty()) {
                    строка(шрифт, размер, текущая.toString())
                    текущая = StringBuilder(слово)
                } else {
                    текущая = StringBuilder(проба)
                }
            }
            if (текущая.isNotEmpty()) строка(шрифт, размер, текущая.toString())
        }

        fun пропуск() { y -= СТРОКА }

        fun новый() {
            поток.close()
            страница = PDPage(PDRectangle.A4)
            pdf.addPage(страница)
            поток = PDPageContentStream(pdf, страница)
            номер += 1
            y = ВЕРХ
            колонтитул()
        }

        fun закрыть() = поток.close()

        private fun если() { if (y < НИЗ) новый() }

        private fun колонтитул() {
            пиши(обычный, 8f, ПОЛЕ, "$проект · $заголовок", 806f)
            val подпись = "лист $номер"
            val ширина = обычный.getStringWidth(подпись) / 1000 * 8f
            пиши(обычный, 8f, PDRectangle.A4.width - ПОЛЕ - ширина, подпись, 32f)
        }

        private fun пиши(шрифт: PDType0Font, размер: Float, x: Float, текст: String, наY: Float? = null) {
            поток.beginText()
            поток.setFont(шрифт, размер)
            поток.newLineAtOffset(x, наY ?: y)
            поток.showText(текст.replace(" ", " "))
            поток.endText()
        }
    }
}
