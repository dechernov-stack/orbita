// Перенос исполняемого эталона spec/constraint_semantics.py — один в один,
// 38 проверок (CR-001/ADR-017). Названия сохранены.
package orbita.req

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.abs

private val mapper = ObjectMapper()

/** Величина как в эталоне: значение, единица, происхождение. */
private fun q(v: Number, u: String): String =
    """{"value":$v,"unit":"$u","provenance":{"source":"manual"}}"""

private fun mop(json: String): JsonNode = mapper.readTree(json)

class ConstraintSemanticsTest {

    private val le500 = mop("""{"name":"Сухая масса","operator":"le","value":${q(500, "g")}}""")
    private val ge500 = mop("""{"name":"Сухая масса","operator":"ge","value":${q(500, "g")}}""")
    private val eq500 = mop(
        """{"name":"Сухая масса","operator":"tolerance","value":${q(500, "g")},"tolerance":${q(5, "g")}}"""
    )
    private val rng = mop(
        """{"name":"Температура","operator":"range","value":${q(-20, "degC")},"upper":${q(50, "degC")}}"""
    )
    private val labels = UnitLabels()

    @Nested
    @DisplayName("Оператор различает требования (дефект CR-001)")
    inner class Operators {

        @Test
        fun `«не более 500 г» - 480 проходит`() = assertTrue(satisfies(le500, 480.0))

        @Test
        fun `«не более 500 г» - 520 не проходит`() = assertFalse(satisfies(le500, 520.0))

        @Test
        fun `«не менее 500 г» - 480 НЕ проходит`() = assertFalse(satisfies(ge500, 480.0))

        @Test
        fun `«не менее 500 г» - 520 проходит`() = assertTrue(satisfies(ge500, 520.0))

        @Test
        fun `одно значение, разные операторы — разный вердикт`() =
            assertTrue(satisfies(le500, 480.0) != satisfies(ge500, 480.0))

        @Test
        fun `«ровно 500 ± 5» - 503 проходит`() = assertTrue(satisfies(eq500, 503.0))

        @Test
        fun `«ровно 500 ± 5» - 507 не проходит`() = assertFalse(satisfies(eq500, 507.0))

        @Test
        fun `диапазон - 25 внутри`() = assertTrue(satisfies(rng, 25.0))

        @Test
        fun `диапазон - 60 снаружи`() = assertFalse(satisfies(rng, 60.0))

        @Test
        fun `строгий оператор отличается от нестрогого`() =
            assertTrue(
                satisfies(le500, 500.0) &&
                    !satisfies(mop("""{"operator":"lt","value":${q(500, "g")}}"""), 500.0)
            )
    }

    @Nested
    @DisplayName("Читаемая запись условия")
    inner class Rendering {

        @Test
        fun `le — «не более»`() = assertEquals("не более 500 g", renderConstraint(le500))

        @Test
        fun `ge — «не менее»`() = assertEquals("не менее 500 g", renderConstraint(ge500))

        @Test
        fun `tolerance — «±»`() = assertEquals("500 ± 5 g", renderConstraint(eq500))

        @Test
        fun `range — «от … до …»`() = assertEquals("от -20 до 50 degC", renderConstraint(rng))

        @Test
        fun `подпись единицы подставляется, код в модели не меняется`() {
            assertEquals("не более 500 г", renderConstraint(le500, labels.asFunction()))
            assertEquals("g", le500.path("value").path("unit").asText())
        }

        @Test
        fun `неизвестная единица выводится кодом, а не теряется`() =
            assertEquals(
                "не более 5 sr",
                renderConstraint(mop("""{"operator":"le","value":${q(5, "sr")}}"""), labels.asFunction()),
            )
    }

    @Nested
    @DisplayName("Структурная целостность условия")
    inner class Structure {

        @Test
        fun `корректное условие без замечаний`() = assertEquals(emptyList<String>(), validateMop(le500))

        @Test
        fun `отсутствие оператора выявлено`() =
            assertTrue("оператор обязателен" in validateMop(mop("""{"value":${q(1, "kg")}}""")))

        @Test
        fun `отсутствие единицы выявлено`() =
            assertTrue(
                validateMop(
                    mop("""{"operator":"le","value":{"value":1,"unit":"","provenance":{"source":"manual"}}}""")
                ).any { "единица" in it }
            )

        @Test
        fun `range без upper отклонён`() =
            assertTrue(validateMop(mop("""{"operator":"range","value":${q(1, "kg")}}""")).any { "upper" in it })

        @Test
        fun `tolerance без допуска отклонён`() =
            assertTrue(validateMop(mop("""{"operator":"tolerance","value":${q(1, "kg")}}""")).any { "допуск" in it })

        @Test
        fun `перевёрнутый диапазон отклонён`() =
            assertTrue(
                validateMop(
                    mop("""{"operator":"range","value":${q(50, "degC")},"upper":${q(-20, "degC")}}""")
                ).any { "не выше" in it }
            )

        @Test
        fun `разные единицы границ отклонены`() =
            assertTrue(
                validateMop(
                    mop("""{"operator":"range","value":${q(0, "degC")},"upper":${q(300, "K")}}""")
                ).any { "единицы границ" in it }
            )
    }

    @Nested
    @DisplayName("Согласованность формулировки и оператора")
    inner class StatementAgreement {

        @Test
        fun `«не более» плюс le — согласовано`() =
            assertEquals(null, statementMatchesOperator("Масса КА не должна превышать 500 г.", le500))

        @Test
        fun `«не более» плюс ge — расхождение выявлено`() =
            assertTrue(statementMatchesOperator("Масса КА не должна превышать 500 г.", ge500) != null)

        @Test
        fun `«не менее» плюс ge — согласовано`() =
            assertEquals(null, statementMatchesOperator("Запас линии должен быть не менее 3 дБ.", ge500))
    }

    @Nested
    @DisplayName("Свёртка бюджетов по декомпозиции (derive)")
    inner class Rollup {

        private val parent = mop(
            """{"name":"Сухая масса КА","operator":"le","value":${q(100, "kg")},"rollup":"sum"}"""
        )
        private val kidsOk = listOf(
            mop("""{"name":"Платформа","operator":"le","value":${q(60, "kg")}}"""),
            mop("""{"name":"ПН","operator":"le","value":${q(30, "kg")}}"""),
        )
        private val kidsBad = listOf(
            mop("""{"name":"Платформа","operator":"le","value":${q(60, "kg")}}"""),
            mop("""{"name":"ПН","operator":"le","value":${q(50, "kg")}}"""),
        )
        private val r1 = rollupCheck(parent, kidsOk)
        private val r2 = rollupCheck(parent, kidsBad)

        @Test
        fun `состоятельная декомпозиция принята`() = assertTrue(r1.consistent == true) { "$r1" }

        @Test
        fun `остаток бюджета вычислен`() = assertTrue(abs(r1.remaining!! - 10) < 1e-9) { "${r1.remaining}" }

        @Test
        fun `превышение родительского бюджета выявлено`() = assertFalse(r2.consistent!!) { "$r2" }

        @Test
        fun `величина превышения видна`() = assertTrue(r2.remaining!! < 0) { "${r2.remaining}" }

        @Test
        fun `разные единицы дочерних отклонены, а не приведены молча`() =
            assertTrue(
                rollupCheck(parent, listOf(mop("""{"operator":"le","value":${q(60000, "g")}}"""))).error != null
            )

        @Test
        fun `отсутствие потомков — не молчаливое согласие`() =
            assertTrue(rollupCheck(parent, emptyList()).error != null)

        @Test
        fun `rollup none не проверяется`() =
            assertFalse(
                rollupCheck(
                    mop("""{"operator":"le","value":${q(3, "dB")},"rollup":"none"}"""), kidsOk,
                ).applicable
            )

        @Test
        fun `бюджет времени реакции складывается по участкам`() {
            val lat = mop("""{"name":"Задержка","operator":"le","value":${q(120, "s")},"rollup":"sum"}""")
            val segs = listOf(40, 30, 35).map { mop("""{"operator":"le","value":${q(it, "s")}}""") }
            val r = rollupCheck(lat, segs)
            assertTrue(r.consistent == true)
            assertEquals(105.0, r.aggregate)
        }

        @Test
        fun `предельные величины сворачиваются по максимуму`() {
            val tmax = mop(
                """{"name":"Предельная температура","operator":"le","value":${q(60, "degC")},"rollup":"max"}"""
            )
            val kids = listOf(55, 58).map { mop("""{"operator":"le","value":${q(it, "degC")}}""") }
            assertEquals(58.0, rollupCheck(tmax, kids).aggregate)
        }
    }

    @Nested
    @DisplayName("Верификация по оператору, а не по умолчанию")
    inner class OperatorVerification {

        /** Требование с условием и свидетельством — как в эталоне. */
        private fun reqWith(mopJson: JsonNode, value: Double?, stale: Boolean): JsonNode {
            val node = mapper.createObjectNode()
            node.set<JsonNode>("mop", mopJson)
            node.putObject("verification").put("method", "analysis").put("evidence_ref", "RES-1")
            return node
        }

        private fun status(mopJson: JsonNode, value: Double?, stale: Boolean = false) =
            verificationStatus(reqWith(mopJson, value, stale)) { Evidence(value, stale) }

        @Test
        fun `масса 480 г при «не более 500» — выполнено`() =
            assertEquals(VerificationStatus.Passed, status(le500, 480.0))

        @Test
        fun `масса 480 г при «не менее 500» — НЕ выполнено`() =
            assertEquals(VerificationStatus.Failed, status(ge500, 480.0))

        @Test
        fun `устаревшее свидетельство не засчитано`() =
            assertEquals(VerificationStatus.NotVerified, status(le500, 480.0, stale = true))
    }
}
