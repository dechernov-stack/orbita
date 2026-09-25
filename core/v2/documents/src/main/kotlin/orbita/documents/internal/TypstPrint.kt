// Печать Typst (шип 4 §4): строгий нативный режим DocPilot — настоящий PDF из
// тех же строк, что видит человек, титул с авторами из элементов и линией.
//
// Typst — внешний двоичный файл (в образе изделия он лежит рядом с java,
// см. ops/api.Dockerfile); нет файла — печать Typst не состоялась, и об этом
// говорится прямо. Запасного «похожего на PDF» вывода здесь нет: где Typst
// не подключён, печать идёт PDFBox, и движок называется в ответе.
package orbita.documents.internal

import orbita.documents.api.PrintView
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

internal object TypstPrint {

    /** Где искать Typst: переменная окружения, обычные пути, локальная сборка для тестов. */
    fun binary(): File? = listOfNotNull(
        System.getenv("ORBITA_TYPST"),
        "/usr/local/bin/typst", "/usr/bin/typst", "/bin/typst",
        System.getenv("ORBITA_REPO_ROOT")?.let { "$it/build/tools/typst" },
        "build/tools/typst",
        System.getenv("HOME")?.let { "$it/.local/bin/typst" },
        "/opt/homebrew/bin/typst",
    ).map(::File).firstOrNull { it.isFile && it.canExecute() }

    val available: Boolean get() = binary() != null

    /** Исходник Typst: титул, разделы, таблицы из строк stub-рендера, проза принятого текста. */
    fun source(вид: PrintView, проект: String): String {
        val sb = StringBuilder()
        sb.append(
            """
            #set page(paper: "a4", margin: (x: 2.2cm, y: 2.2cm), numbering: "1",
              footer: context [#text(size: 8pt, fill: luma(90))[${э(проект)} · ${э(вид.title)}] #h(1fr) #text(size: 8pt)[#counter(page).display()]])
            #set text(font: ("DejaVu Sans", "Libertinus Serif", "New Computer Modern"), size: 10pt, lang: "ru", hyphenate: true)
            #set par(justify: true, leading: 0.65em)
            #set heading(numbering: none)
            #show heading.where(level: 1): it => block(above: 1.4em, below: 0.7em, text(size: 13pt, weight: "bold", it.body))
            #set table(stroke: 0.4pt + luma(140), inset: 5pt)

            #align(center)[
              #v(5.5cm)
              #text(size: 22pt, weight: "bold")[${э(вид.title)}]
              #v(1em)
              #text(size: 12pt)[${э(проект)}]

              #text(size: 10pt, fill: luma(60))[${э(вид.subtitle)}]
            """.trimIndent(),
        )
        sb.append('\n')
        if (вид.authors.isNotEmpty()) {
            sb.append("\n  #v(1.5em)\n  #text(size: 10pt)[Авторы: ${э(вид.authors.joinToString(", "))}]\n")
        }
        if (вид.baseline.isNotBlank()) sb.append("\n  #text(size: 10pt)[${э(вид.baseline)}]\n")
        sb.append("]\n#pagebreak()\n\n")
        вид.sections.forEach { раздел ->
            sb.append("= ${э(раздел.no)} ${э(раздел.title)}\n\n")
            var i = 0
            val строки = раздел.lines
            while (i < строки.size) {
                val строка = строки[i]
                when {
                    таблица(строка) -> {
                        val ряды = mutableListOf<List<String>>()
                        while (i < строки.size && таблица(строки[i])) {
                            ряды += строки[i].trim().split(" | ")
                            i += 1
                        }
                        val колонок = ряды.maxOf { it.size }
                        sb.append("#table(columns: $колонок,\n")
                        ряды.forEachIndexed { n, ряд ->
                            val ячейки = (0 until колонок).joinToString(", ") { к -> "[${э(ряд.getOrNull(к).orEmpty())}]" }
                            if (n == 0) sb.append("  table.header($ячейки),\n") else sb.append("  $ячейки,\n")
                        }
                        sb.append(")\n\n")
                        continue
                    }
                    строка.startsWith("  ") -> sb.append("#text(size: 9pt, fill: luma(70))[${э(строка.trim())}]\n\n")
                    else -> sb.append("${э(строка)}\n\n")
                }
                i += 1
            }
        }
        return sb.toString()
    }

    /** Собрать PDF: исходник во времянке, `typst compile`, шрифты системы рядом со встроенными. */
    fun bytes(вид: PrintView, проект: String): ByteArray {
        val typst = binary() ?: error(
            "печать Typst не состоялась: двоичного файла typst нет — положите его в /usr/local/bin " +
                "либо задайте ORBITA_TYPST; в образе изделия он есть (ops/api.Dockerfile)",
        )
        val папка = Files.createTempDirectory("orbita-typst").toFile()
        try {
            val вход = File(папка, "main.typ").apply { writeText(source(вид, проект)) }
            val выход = File(папка, "main.pdf")
            val команда = mutableListOf(typst.absolutePath, "compile", "--font-path", "/usr/share/fonts", вход.absolutePath, выход.absolutePath)
            val процесс = ProcessBuilder(команда).directory(папка).redirectErrorStream(true).start()
            val журнал = процесс.inputStream.bufferedReader().readText()
            if (!процесс.waitFor(90, TimeUnit.SECONDS)) {
                процесс.destroyForcibly()
                error("печать Typst не состоялась: компиляция дольше 90 с")
            }
            if (процесс.exitValue() != 0 || !выход.isFile) {
                error("печать Typst не состоялась (код ${процесс.exitValue()}): ${журнал.trim().take(600)}")
            }
            return выход.readBytes()
        } finally {
            папка.deleteRecursively()
        }
    }

    private fun таблица(строка: String): Boolean = строка.startsWith("  ") && " | " in строка

    /** Экранирование разметки Typst: текст остаётся текстом, что бы в нём ни стояло. */
    fun э(текст: String): String = buildString {
        текст.forEach { с ->
            when (с) {
                '\\', '#', '*', '_', '$', '@', '<', '>', '[', ']', '`', '~', '/' -> append('\\').append(с)
                '\n' -> append(" \\\n")
                else -> append(с)
            }
        }
    }
}
