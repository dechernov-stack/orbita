// Печать Typst (шип 4 §4): исходник из тех же строк, что видит человек, —
// титул с авторами и линией, таблицы из строк stub-рендера, проза принятого
// текста; разметка Typst в тексте экранируется. Компиляция — настоящим
// двоичным файлом, когда он есть (build/tools/typst или ORBITA_TYPST); без
// него компиляция пропускается ВСЛУХ, а не молча зеленеет.
package orbita.documents

import orbita.documents.api.PrintSection
import orbita.documents.api.PrintView
import orbita.documents.internal.TypstPrint
import org.junit.jupiter.api.Assumptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TypstPrintTest {

    private val вид = PrintView(
        title = "Отчёт #1 о концепции миссии",
        subtitle = "NASA 7120 · полнота 3 из 11 разделов к ступени MCR",
        sections = listOf(
            PrintSection(
                "§1", "Цели, задачи [и] стейкхолдеры",
                listOf(
                    "Стороны миссии",
                    "  Имя | Роль | Нужда",
                    "  Минтранс России | заказчик | телематика 100 % грузов",
                    "  Росморпорт | оператор | —",
                    "  помета: у стороны нет нужды",
                    "Замысел обращён к перевозчикам: *телеметрия* груза на СМП к 2033 году.",
                ),
            ),
            PrintSection("§2", "Анализ альтернатив", listOf("Альтернативы", "  — сведений нет: ждёт сцен 7")),
        ),
        authors = listOf("Иванов И.", "связный текст: claude-sonnet-5"),
        baseline = "базовая линия «внутренний обзор» · тег базирование/mcreport/внутренний-обзор",
    )

    @Test
    fun `исходник - титул с авторами и линией, таблица из строк, разметка экранирована`() {
        val src = TypstPrint.source(вид, "ПМИ-7 · Национальная система IoT")
        assertTrue("Отчёт \\#1 о концепции миссии" in src, "решётка заголовка экранирована")
        assertTrue("Авторы: Иванов И., связный текст: claude-sonnet-5" in src, "авторы из элементов на титуле")
        assertTrue("базовая линия «внутренний обзор»" in src, "линия на титуле")
        assertTrue("= §1 Цели, задачи \\[и\\] стейкхолдеры" in src, "заголовок раздела экранирован")
        assertTrue("#table(columns: 3," in src, "строки stub-рендера стали таблицей")
        assertTrue("table.header([Имя], [Роль], [Нужда])" in src, "первая строка — шапка")
        assertTrue("[Минтранс России], [заказчик], [телематика 100 % грузов]" in src, src)
        assertTrue("\\*телеметрия\\*" in src, "звёздочки текста не стали курсивом")
        assertTrue("[помета: у стороны нет нужды]" in src, "помета мелким текстом")
        assertTrue("сведений нет: ждёт сцен 7" in src)
    }

    @Test
    fun `компиляция даёт настоящий PDF - когда двоичный файл Typst есть`() {
        Assumptions.assumeTrue(TypstPrint.available, "typst не найден: положите build/tools/typst или задайте ORBITA_TYPST — компиляция пропущена")
        val байты = TypstPrint.bytes(вид, "ПМИ-7")
        assertEquals("%PDF", байты.copyOfRange(0, 4).decodeToString())
        assertTrue(байты.size > 5000, "файл не пустой: ${байты.size}")
    }
}
