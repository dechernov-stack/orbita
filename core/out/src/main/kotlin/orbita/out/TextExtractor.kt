// Круг 2 стартового потока: файл без описания — склад, не библиотека.
// Извлечение текста из принятого файла (docx — POI, PDF — PDFBox, txt/md —
// как есть) наполняет поле text карточки: от него работают аннотация и
// типовые разборы службы. Нечитаемый формат — карточка без текста, честно.
package orbita.out

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.poi.xwpf.extractor.XWPFWordExtractor
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.ByteArrayInputStream

object TextExtractor {
    /** Текст файла; null — формат не читается (это не ошибка приёма). */
    fun extract(fileName: String, bytes: ByteArray): String? = runCatching {
        when {
            fileName.endsWith(".txt", true) || fileName.endsWith(".md", true) ->
                bytes.decodeToString()
            fileName.endsWith(".docx", true) ->
                XWPFDocument(ByteArrayInputStream(bytes)).use { XWPFWordExtractor(it).text }
            fileName.endsWith(".pdf", true) ->
                Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) }
            else -> null
        }?.trim()?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
