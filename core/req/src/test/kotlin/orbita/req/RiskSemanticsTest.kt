// Перенос spec/risk_semantics.py в тесты, один в один: 34 проверки.
package orbita.req

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RiskSemanticsTest {

    private val mapper = ObjectMapper()
    private fun json(s: String): JsonNode = mapper.readTree(s)

    private val statement =
        "При задержке поставки приёмника — срыв срока интеграции — " +
            "сдвиг готовности к SRR на два месяца"

    private val risk: ObjectNode = mapper.readTree(
        """{"id":"RSK-0001","statement":"$statement","category":"schedule",
           "probability":4,"impact":4,"owner":"руководитель проекта",
           "strategy":"mitigate","actions":["резервный поставщик"],
           "due":"2026-12-01","affects":["CM-0011"],"status":"open"}""",
    ) as ObjectNode

    private fun withField(vararg pairs: Pair<String, Any?>): JsonNode {
        val n: ObjectNode = risk.deepCopy()
        pairs.forEach { (k, v) ->
            when (v) {
                null -> n.remove(k)
                is Int -> n.put(k, v)
                is String -> n.put(k, v)
                is List<*> -> n.putArray(k).also { arr -> v.forEach { arr.add(it.toString()) } }
                else -> error("unsupported")
            }
        }
        return n
    }

    @Nested
    @DisplayName("Матрица критичности")
    inner class Matrix {

        @Test
        fun `низкая вероятность и низкие последствия — низкая критичность`() {
            assertEquals(Criticality.Low, criticality(1, 1))
        }

        @Test
        fun `высокая вероятность и тяжёлые последствия — высокая`() {
            assertEquals(Criticality.High, criticality(5, 5))
        }

        @Test
        fun `монотонность по вероятности`() {
            (1..4).forEach { p ->
                assertTrue(criticality(p, 3).order <= criticality(p + 1, 3).order, "p=$p")
            }
        }

        @Test
        fun `монотонность по последствиям`() {
            (1..4).forEach { i ->
                assertTrue(criticality(3, i).order <= criticality(3, i + 1).order, "i=$i")
            }
        }

        @Test
        fun `последствия весомее вероятности редкое тяжёлое не считается малым`() {
            assertTrue(
                criticality(2, 5).order >= criticality(5, 2).order,
                "${criticality(2, 5)} vs ${criticality(5, 2)}",
            )
        }

        @Test
        fun `матрица несимметрична тяжёлые последствия весят больше`() {
            assertEquals(Criticality.High, criticality(1, 5))
            assertEquals(Criticality.Medium, criticality(5, 1))
        }

        @Test
        fun `критичность не сводится к произведению оценок`() {
            assertTrue(criticality(1, 5) != criticality(5, 1))
        }

        @Test
        fun `значение вне шкалы отклонено`() {
            assertThrows<IllegalArgumentException> { criticality(0, 3) }
        }
    }

    @Nested
    @DisplayName("Формулировка риска")
    inner class Statement {

        @Test
        fun `полная формулировка принята`() {
            assertEquals(emptyList<String>(), riskStatementIssues(statement))
        }

        @Test
        fun `формулировка без последствия отклонена`() {
            assertTrue(riskStatementIssues("При задержке поставки — срыв срока интеграции").isNotEmpty())
        }

        @Test
        fun `одно предложение без структуры отклонено`() {
            assertTrue(riskStatementIssues("Риск срыва сроков").isNotEmpty())
        }
    }

    @Nested
    @DisplayName("Полнота записи")
    inner class Completeness {

        private val low = withField(
            "probability" to 1, "impact" to 1, "strategy" to null,
            "actions" to emptyList<String>(), "due" to null,
        )

        @Test
        fun `полная запись без замечаний`() {
            assertEquals(emptyList<String>(), riskIssues(risk))
        }

        @Test
        fun `риск без владельца отклонён`() {
            assertTrue(riskIssues(withField("owner" to "")).any { "владельца" in it })
        }

        @Test
        fun `недопустимая категория отклонена`() {
            assertTrue(riskIssues(withField("category" to "прочее")).any { "категория" in it })
        }

        @Test
        fun `оценка вне шкалы отклонена`() {
            assertTrue(riskIssues(withField("probability" to 7)).any { "шкал" in it })
        }

        @Test
        fun `высокая критичность без стратегии отклонена`() {
            assertTrue(riskIssues(withField("strategy" to null)).any { "стратегия" in it })
        }

        @Test
        fun `стратегия без мероприятий отклонена`() {
            assertTrue(
                riskIssues(withField("actions" to emptyList<String>())).any { "мероприятий" in it },
            )
        }

        @Test
        fun `принятие риска мероприятий не требует`() {
            val accepted = withField("strategy" to "accept", "actions" to emptyList<String>())
            assertEquals(emptyList<String>(), riskIssues(accepted))
        }

        @Test
        fun `высокая критичность без срока отклонена`() {
            assertTrue(riskIssues(withField("due" to null)).any { "срок" in it })
        }

        @Test
        fun `низкая критичность не требует стратегии и срока`() {
            assertEquals(emptyList<String>(), riskIssues(low))
        }
    }

    @Nested
    @DisplayName("Эскалация")
    inner class Escalation {

        @Test
        fun `риск высокой критичности выводится на уровень программы`() {
            assertTrue(needsEscalation(risk))
        }

        @Test
        fun `риск низкой критичности не эскалируется`() {
            assertFalse(needsEscalation(withField("probability" to 1, "impact" to 1)))
        }

        @Test
        fun `порог применяется к критичности, а не к оценкам напрямую`() {
            assertTrue(needsEscalation(json("""{"probability":2,"impact":5}""")))
            assertFalse(needsEscalation(json("""{"probability":5,"impact":1}""")))
        }
    }

    @Nested
    @DisplayName("Остаточный риск")
    inner class Residual {

        private fun withResidual(p: Int, i: Int): JsonNode {
            val n: ObjectNode = risk.deepCopy()
            n.putObject("residual").put("probability", p).put("impact", i)
            return n
        }

        @Test
        fun `снижение после мероприятий принято`() {
            assertTrue(residualOk(withResidual(2, 3)))
        }

        @Test
        fun `остаточный риск выше исходного отклонён`() {
            assertFalse(residualOk(withResidual(5, 5)))
        }

        @Test
        fun `ухудшение внутри одного класса критичности выявляется`() {
            // 2×5 и 5×5 — оба «высокие», но по вероятности стало хуже
            assertFalse(
                residualOk(json("""{"probability":2,"impact":5,"residual":{"probability":5,"impact":5}}""")),
            )
        }

        @Test
        fun `равный остаточный риск допустим`() {
            assertTrue(residualOk(withResidual(4, 4)))
        }

        @Test
        fun `отсутствие оценки остатка не является ошибкой`() {
            assertTrue(residualOk(risk))
        }
    }

    @Nested
    @DisplayName("Реестр и связи")
    inner class Register {

        private fun copy(id: String, vararg pairs: Pair<String, Any?>): JsonNode {
            val n = withField(*pairs) as ObjectNode
            n.put("id", id)
            return n
        }

        private val register = listOf(
            risk,
            copy("RSK-0002", "probability" to 1, "impact" to 2),
            copy("RSK-0003", "status" to "closed"),
            copy("RSK-0004", "probability" to 3, "impact" to 3),
        )
        private val summary = registerSummary(register)

        @Test
        fun `закрытый риск сохраняется в реестре`() {
            assertEquals(listOf("RSK-0003"), summary.closedRetained)
        }

        @Test
        fun `закрытый риск не входит в активные`() {
            assertEquals(3, summary.active)
        }

        @Test
        fun `распределение по критичности посчитано`() {
            assertEquals(mapOf("low" to 1, "medium" to 1, "high" to 1), summary.distribution)
        }

        @Test
        fun `к эскалации отобраны только высокие`() {
            assertEquals(listOf("RSK-0001"), summary.escalate)
        }

        @Test
        fun `риск связан с затронутым объектом`() {
            assertTrue(riskTraced(risk))
        }

        @Test
        fun `несвязанный риск выявляется`() {
            assertFalse(riskTraced(withField("affects" to emptyList<String>())))
        }
    }
}
