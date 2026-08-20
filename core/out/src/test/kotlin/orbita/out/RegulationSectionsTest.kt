// Сверка структуры документов с САМИМ РЕГЛАМЕНТОМ (TZ-OUT-001, шаг 11.1).
//
// Таблица разделов в `DocumentTemplate` — вторая копия таблицы из приложения
// регламента. Вторая копия расходится с первой молча: регламент правят,
// генератор об этом не узнаёт, и документ продолжает выглядеть соответствующим.
//
// Поэтому копия сверяется с оригиналом: `docs/regulations/` лежит в репозитории,
// приложения 2–4 разбираются, и номера, заголовки и содержание разделов должны
// совпасть посимвольно. Единственный способ разойтись — поправить регламент
// и не поправить генератор, и тогда падает этот тест.
package orbita.out

import orbita.mod.RepoPaths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RegulationSectionsTest {

    private val text = RepoPaths.repoRoot()
        .resolve("docs/regulations/BP-PhaseA.md").toFile().readText()

    /** Разделы приложения [number] регламента: номер → (заголовок, содержание). */
    private fun appendix(number: Int): List<SectionTemplate> {
        val start = text.indexOf("Приложение $number.")
        require(start >= 0) { "в регламенте нет приложения $number" }
        val next = text.indexOf("Приложение ${number + 1}.", start)
        val block = if (next > 0) text.substring(start, next) else text.substring(start)
        return block.lineSequence()
            .filter { it.startsWith("|") }
            .mapNotNull { line ->
                val cells = line.trim('|', ' ').split("|").map { it.trim() }
                if (cells.size < 3) return@mapNotNull null
                val n = cells[0].toIntOrNull() ?: return@mapNotNull null
                SectionTemplate(n, cells[1], cells[2])
            }
            .toList()
    }

    private fun assertMatches(template: DocumentTemplate, appendixNumber: Int) {
        val fromRegulation = appendix(appendixNumber)
        assertTrue(fromRegulation.isNotEmpty(), "приложение $appendixNumber разобрать не удалось")
        assertEquals(
            fromRegulation.map { it.number to it.title },
            template.sections.map { it.number to it.title },
            "${template.code}: состав разделов разошёлся с приложением $appendixNumber",
        )
        // Содержание раздела — тоже часть регламента: по нему читатель понимает,
        // чего в пустом разделе не хватает, и своими словами его писать нельзя.
        fromRegulation.zip(template.sections).forEach { (regulation, ours) ->
            assertEquals(
                regulation.expects, ours.expects,
                "${template.code}, раздел ${regulation.number}: содержание разошлось с регламентом",
            )
        }
    }

    @Test
    fun `спецификация требований соответствует приложению 2`() =
        assertMatches(DocumentTemplate.RequirementSpecification, 2)

    @Test
    fun `ConOps соответствует приложению 3`() =
        assertMatches(DocumentTemplate.ConOps, 3)

    @Test
    fun `описание архитектуры соответствует приложению 4`() =
        assertMatches(DocumentTemplate.ArchitectureDescription, 4)

    /** Ссылка на источник структуры ведёт на существующее приложение. */
    @Test
    fun `каждый шаблон называет своё приложение регламента`() {
        DocumentTemplate.entries.forEach { t ->
            assertTrue(t.source.startsWith("БП-PA, Приложение ")) { "${t.code}: ${t.source}" }
            val number = t.source.substringAfterLast(" ").toInt()
            assertTrue(text.contains("Приложение $number.")) { "${t.code}: в регламенте нет ${t.source}" }
        }
    }
}
