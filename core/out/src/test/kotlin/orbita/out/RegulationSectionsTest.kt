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
    private val textPpa = RepoPaths.repoRoot()
        .resolve("docs/regulations/BP-PreA.md").toFile().readText()

    /** Разделы приложения [number] регламента: номер → (заголовок, содержание). */
    private fun appendix(number: Int, source: String = text): List<SectionTemplate> {
        val start = source.indexOf("Приложение $number.")
        require(start >= 0) { "в регламенте нет приложения $number" }
        val next = source.indexOf("Приложение ${number + 1}.", start)
        val block = if (next > 0) source.substring(start, next) else source.substring(start)
        return block.lineSequence()
            .filter { it.startsWith("|") }
            .mapNotNull { line ->
                val cells = line.trim('|', ' ').split("|").map { it.trim() }
                if (cells.size < 3) return@mapNotNull null
                val n = cells[0].trim('*').toIntOrNull() ?: return@mapNotNull null
                SectionTemplate(n, cells[1].trim('*'), cells[2])
            }
            .toList()
    }

    private fun assertMatches(
        template: DocumentTemplate,
        appendixNumber: Int,
        source: String = text,
    ) {
        val fromRegulation = appendix(appendixNumber, source)
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

    // ---------- блок C: комплекты Д1–Д9 / Д1–Д10 ----------

    @Test
    fun `FAD соответствует приложению 1 БП-PPA`() =
        assertMatches(DocumentTemplate.Fad, 1, textPpa)

    @Test
    fun `MCReport соответствует приложению 3 БП-PPA`() =
        assertMatches(DocumentTemplate.MissionConcept, 3, textPpa)

    @Test
    fun `FA соответствует приложению 2 БП-PPA`() =
        assertMatches(DocumentTemplate.FormulationAgreement, 2, textPpa)

    @Test
    fun `SEMP соответствует приложению 1 БП-PA`() =
        assertMatches(DocumentTemplate.Semp, 1)

    @Test
    fun `план технологий соответствует приложению 5 БП-PA`() =
        assertMatches(DocumentTemplate.TechnologyPlan, 5)

    @Test
    fun `план рисков соответствует приложению 6 БП-PA`() =
        assertMatches(DocumentTemplate.RiskPlan, 6)

    @Test
    fun `план проекта соответствует приложению 7 БП-PA`() =
        assertMatches(DocumentTemplate.ProjectPlan, 7)

    /**
     * Ссылка на источник структуры ведёт на существующее место: приложение
     * своего регламента, параграф статусной таблицы либо внешний стандарт.
     * Атрибутные приложения (Прил. 4, 6, 7 БП-PPA) — таблицы атрибутов записи,
     * не разделов: их состав сверяют DocumentsOnModelTest и генератор.
     */
    @Test
    fun `каждый шаблон называет своё приложение регламента`() {
        DocumentTemplate.entries.forEach { t ->
            when {
                t.source.startsWith("БП-PA, Приложение ") -> {
                    val number = t.source.substringAfterLast(" ").toInt()
                    assertTrue(text.contains("Приложение $number.")) { "${t.code}: в БП-PA нет ${t.source}" }
                }
                t.source.startsWith("БП-PPA, Приложение ") -> {
                    val number = t.source.substringAfterLast(" ").toInt()
                    assertTrue(textPpa.contains("Приложение $number.")) { "${t.code}: в БП-PPA нет ${t.source}" }
                }
                t.source.startsWith("БП-PPA §") -> assertTrue(textPpa.contains("# **6."))
                t.source.startsWith("БП-PA §") -> assertTrue(text.contains("# **6."))
                t.source.startsWith("NASA-STD-") ->
                    assertTrue(textPpa.contains(t.source.substringBefore(" "))) { "${t.code}: стандарт не упомянут" }
                else -> throw AssertionError("${t.code}: непонятный источник «${t.source}»")
            }
        }
    }
}
