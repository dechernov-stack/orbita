// Перенос исполняемого эталона spec/verification_semantics.py — один в один,
// 16 проверок (CR-002/ADR-018). Названия сохранены.
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

class VerificationSemanticsTest {

    private val mapper = ObjectMapper()

    private val massJson = """
        {"id":"RQ-0100",
         "statement":"Сухая масса космического аппарата не должна превышать 100 кг.",
         "mop":{"name":"Сухая масса","operator":"le","value":{"value":100,"unit":"kg"}}}
    """

    private fun mass(verification: String? = null): JsonNode {
        val node = mapper.readTree(massJson) as ObjectNode
        verification?.let { node.set<JsonNode>("verification", mapper.readTree(it)) }
        return node
    }

    private val realVerification = """
        {"method":"analysis","phase":"PhaseA",
         "means":"Сводный перечень оборудования (MEL) с резервами по зрелости",
         "approach":"Суммирование масс подсистем по MEL с применением резервов по зрелости элементов и системного резерва; результат сверяется с независимой оценкой по аналогам платформ того же класса.",
         "conditions":"Заправленная конфигурация, худший случай резервов"}
    """

    @Nested
    @DisplayName("Метод без подхода не делает требование проверяемым")
    inner class MethodAlone {

        @Test
        fun `метод без описания подхода отклонён`() =
            assertTrue(
                verificationIssues(mass("""{"method":"analysis","phase":"PhaseA"}"""))
                    .any { "как именно" in it }
            )

        @Test
        fun `отсутствие метода выявлено отдельно`() =
            assertEquals(listOf("метод верификации не назначен"), verificationIssues(mass("{}")))
    }

    @Nested
    @DisplayName("Подход должен нести содержание")
    inner class ApproachContent {

        @Test
        fun `слишком краткое описание отклонено`() =
            assertTrue(
                verificationIssues(mass("""{"method":"analysis","approach":"Расчёт массы","means":"MEL"}"""))
                    .any { "слишком краткое" in it }
            )

        @Test
        fun `пересказ требования вместо порядка действий отклонён`() =
            assertTrue(
                verificationIssues(
                    mass(
                        """{"method":"analysis","means":"MEL",
                            "approach":"Проверить, что сухая масса космического аппарата не превышает 100 кг."}"""
                    )
                ).any { "пересказывает" in it }
            )

        @Test
        fun `содержательный подход принят`() =
            assertEquals(emptyList<String>(), verificationIssues(mass(realVerification)))

        @Test
        fun `требование признано проверяемым`() = assertTrue(isVerifiable(mass(realVerification)))
    }

    @Nested
    @DisplayName("Средства проверки")
    inner class Means {

        @Test
        fun `анализ без средства отклонён`() {
            val v = mapper.readTree(realVerification) as ObjectNode
            v.put("means", "")
            assertTrue(verificationIssues(mass(v.toString())).any { "средство" in it })
        }

        @Test
        fun `испытание без средства отклонено`() =
            assertTrue(
                verificationIssues(
                    mass(
                        """{"method":"test","means":"",
                            "approach":"Взвешивание собранного аппарата после интеграции с фиксацией показаний поверенного оборудования в протоколе."}"""
                    )
                ).any { "средство" in it }
            )

        @Test
        fun `инспекция без средства допустима`() =
            assertEquals(
                emptyList<String>(),
                verificationIssues(
                    mass(
                        """{"method":"inspection",
                            "approach":"Проверка наличия и подписей в ведомости массовых характеристик, сверка версии документа с конфигурационным журналом."}"""
                    )
                ),
            )
    }

    @Nested
    @DisplayName("Критерий успеха")
    inner class SuccessCriterion {

        private val noMopJson = """
            {"id":"RQ-0200","statement":"Система должна вести журнал команд.",
             "verification":{"method":"demonstration",
               "approach":"Демонстрация записи команд в журнал на сценарии штатного сеанса с последующим просмотром содержимого."}}
        """

        @Test
        fun `критерий выводится из условия требования`() =
            assertEquals("Сухая масса: не более 100 kg", successCriterion(mass()))

        @Test
        fun `требование без условия и без явного критерия отклонено`() =
            assertTrue(verificationIssues(mapper.readTree(noMopJson)).any { "критерий успеха" in it })

        @Test
        fun `явный критерий закрывает замечание`() {
            val node = mapper.readTree(noMopJson) as ObjectNode
            (node.path("verification") as ObjectNode)
                .put("success_criterion", "Журнал содержит все переданные команды с отметками времени")
            assertEquals(emptyList<String>(), verificationIssues(node))
        }

        @Test
        fun `явный критерий возвращается как есть`() {
            val node = mapper.readTree(noMopJson) as ObjectNode
            (node.path("verification") as ObjectNode)
                .put("success_criterion", "Журнал содержит все переданные команды с отметками времени")
            assertEquals("Журнал содержит все переданные команды с отметками времени", successCriterion(node))
        }
    }

    @Nested
    @DisplayName("Влияние на базирование")
    inner class BaselineImpact {

        private val baselining = Baselining()

        @Test
        fun `проверяемое требование базируется`() = assertTrue(isVerifiable(mass(realVerification)))

        @Test
        fun `требование с иконкой вместо подхода не базируется`() =
            assertFalse(isVerifiable(mass("""{"method":"analysis","phase":"PhaseA"}""")))

        @Test
        fun `несколько замечаний перечисляются вместе`() =
            assertTrue(verificationIssues(mass("""{"method":"test"}""")).size >= 2) {
                verificationIssues(mass("""{"method":"test"}""")).toString()
            }
    }
}
