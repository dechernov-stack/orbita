// Обход кода фильтра (STEP-5, определение готовности: «фильтр вызывает те же
// функции правил, что и рукописный ввод — проверено обходом кода»).
//
// Проверка тождества вердиктов живёт в AiSemanticsTest; здесь проверяется, что
// тождество не случайное: в фильтре нет собственных правил, которые могли бы
// разойтись с правилами рукописного ввода при следующей правке.
package orbita.ai

import orbita.mod.RepoPaths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ScreeningSourceTest {

    private val source: String =
        RepoPaths.repoRoot().resolve("core/ai/src/main/kotlin/orbita/ai/Screening.kt").toFile().readText()

    @Test
    @DisplayName("фильтр вызывает только правила core/req и ничего своего")
    fun `правила фильтра приходят из core-req`() {
        val imported = Regex("^import orbita\\.req\\.(\\w+)$", RegexOption.MULTILINE)
            .findAll(source).map { it.groupValues[1] }.toSet()
        // ровно тот набор источников правил, что назван в таблице STEP-5 §1.3
        assertEquals(
            setOf(
                "ProductNode",              // тип контекста, не правило
                "QualityControl",           // requirements_semantics + constraint_semantics
                "allocationConsistent",     // traceability_semantics
                "interfaceAllocationValid", // traceability_semantics
                "rollupCheck",              // constraint_semantics
                "verificationIssues",       // verification_semantics
                "verificationPlanIssues",   // verification_semantics
            ),
            imported,
        )
    }

    @Test
    @DisplayName("в фильтре нет собственной логики правил")
    fun `фильтр не заводит своих правил`() {
        // Своя логика правила выглядела бы как регулярное выражение, список слов
        // или порог: любое из этого — вторая реализация правила (ловушка 1).
        listOf("Regex(", "listOf(\"должн", "setOf(\"должн", "0.7", "modalWords", "vagueWords").forEach {
            assertTrue(it !in source, "в фильтре появилась собственная логика правила: «$it»")
        }
    }

    @Test
    @DisplayName("каждое замечание фильтра порождено вызовом правила")
    fun `фильтр только помечает источник замечания`() {
        // Все строки замечаний в фильтре — либо префикс источника к тексту
        // правила, либо форматирование результата rollupCheck. Собственных
        // формулировок замечаний быть не должно.
        val issueLines = source.lines().filter { "issues +=" in it }
        assertTrue(issueLines.isNotEmpty())
        issueLines.forEach { line ->
            val callsRule = listOf(
                "quality.check",            // requirements_semantics
                "verificationIssues",       // verification_semantics
                "verificationPlanIssues",   // verification_semantics
                "reason",                   // allocationConsistent / interfaceAllocationValid
                "roll.error",               // rollupCheck: текст замечания от правила
                "roll.aggregate",           // rollupCheck: числа от правила
            ).any { it in line }
            assertTrue(callsRule, "замечание сформулировано в фильтре, а не правилом: $line")
        }
    }
}
