// Перенос исполняемого эталона spec/requirements_semantics.py — один в один,
// 23 проверки. Названия сохранены. Расхождение реализации с эталоном —
// дефект реализации, а не эталона (START-HERE).
package orbita.req

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.store.Link
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

private val mapper = ObjectMapper()
private fun j(json: String): JsonNode = mapper.readTree(json)

class RequirementsSemanticsTest {

    private val quality = QualityControl()
    private val baselining = Baselining(quality)

    private val good = j(
        """{"id":"RQ-0001","statement":"Система должна обеспечивать вероятность доставки не менее 0,9 за сутки.",
            "category":"performance","mop":{"name":"Вероятность доставки","operator":"ge","value":{"value":0.9,"unit":"1"}}}"""
    )

    // ---- свидетельства эталона (TZ-REQ-007) ----
    private val results = mapOf(
        "RES-1" to Evidence(0.94, stale = false),
        "RES-2" to Evidence(0.94, stale = true),
        "RES-3" to Evidence(0.80, stale = false),
    )
    private val evidence: (String) -> Evidence? = { results[it] }

    @Nested
    @DisplayName("TZ-REQ-004: качество формулировок")
    inner class Quality {

        @Test
        fun `корректное требование без замечаний`() =
            assertEquals(emptyList<String>(), quality.check(good))

        @Test
        fun `нет модального «должна»`() =
            assertTrue("нет модального «должна»" in
                quality.check(j("""{"id":"x","statement":"Обеспечивается доставка данных.","category":"functional"}""")))

        @Test
        fun `неизмеримое определение выявлено`() =
            assertTrue(quality.check(
                j("""{"id":"x","statement":"Система должна обеспечивать достаточную пропускную способность.","category":"functional"}""")
            ).any { "неизмеримое" in it })

        @Test
        fun `performance без MOP отклонено`() =
            assertTrue(quality.check(
                j("""{"id":"x","statement":"Система должна обеспечивать высокую доступность сервиса.","category":"performance"}""")
            ).any { "MOP" in it })

        @Test
        fun `конъюнкция выявлена`() =
            assertTrue(quality.check(
                j("""{"id":"x","statement":"Система должна принимать данные и должна их передавать.","category":"functional"}""")
            ).any { "онъюнкц" in it })

        @Test
        fun `пустая формулировка отклонена`() =
            assertTrue(quality.check(j("""{"id":"x","statement":"   ","category":"functional"}"""))
                .any { "пустая" in it })
    }

    @Nested
    @DisplayName("TZ-REQ-003 / TZ-REQ-005: целостность связей")
    inner class Links {

        private val objs = listOf(
            ObjectSnapshot("ND-0001", "need", "Draft"),
            ObjectSnapshot("SV-0001", "service", "Draft"),
            ObjectSnapshot("RQ-0001", "requirement", "Draft", level = "system"),
            ObjectSnapshot("RQ-0002", "requirement", "Draft", level = "system"),
            ObjectSnapshot("CM-0001", "component", "Draft"),
            ObjectSnapshot("CM-0002", "component", "Draft"),
        )
        private val links = listOf(
            Link("ND-0001", "SV-0001", "trace", null),
            Link("SV-0001", "RQ-0001", "trace", "A_prime"),
            Link("RQ-0001", "CM-0001", "allocation", null),
        )

        @Test
        fun `требование без источника выявлено`() =
            assertEquals(listOf("RQ-0002"), traceGaps(objs, links))

        @Test
        fun `ссылка на сервис с классом принята`() =
            assertTrue(serviceLinkValid(Link("SV-0001", "RQ-0001", "trace", "A_prime"), objs))

        @Test
        fun `ссылка на сервис без класса отклонена`() =
            assertFalse(serviceLinkValid(Link("SV-0001", "RQ-0002", "trace", null), objs))

        @Test
        fun `нераспределённое требование выявлено`() =
            assertEquals(listOf("RQ-0002"), allocationCoverage(objs, links).first)

        @Test
        fun `элемент без требований выявлен`() =
            assertEquals(listOf("CM-0002"), allocationCoverage(objs, links).second)
    }

    @Nested
    @DisplayName("TZ-REQ-002: покрытие классов потребителей")
    inner class Coverage {

        private val svc = j(
            """{"id":"SV-0001","qos_profiles":[{"consumer_class":"A_prime"},{"consumer_class":"B_prime"}]}"""
        )

        @Test
        fun `непокрытый класс выявлен`() =
            assertEquals(listOf("C_prime"),
                uncoveredConsumerClasses(svc, setOf("A_prime", "B_prime", "C_prime")))

        @Test
        fun `полное покрытие не даёт замечаний`() =
            assertEquals(emptyList<String>(), uncoveredConsumerClasses(svc, setOf("A_prime", "B_prime")))
    }

    @Nested
    @DisplayName("TZ-REQ-007: верификация и свидетельства")
    inner class Verification {

        private fun withVerification(evidenceRef: String?): JsonNode = j(
            """{"mop":{"name":"Доставка","operator":"ge","value":{"value":0.9,"unit":"1"}},
                "verification":{"method":"analysis","means":"Прогон сценария Монте-Карло",
                  "approach":"Прогон эталонного сценария с фиксированным зерном ГПСЧ и сверка доли доставленных сообщений с целевым значением показателя."${if (evidenceRef != null) ""","evidence_ref":"$evidenceRef"""" else ""}}}"""
        )

        @Test
        fun `свидетельство подтверждает выполнение`() =
            assertEquals(VerificationStatus.Passed, verificationStatus(withVerification("RES-1"), evidence))

        @Test
        fun `устаревшее свидетельство не засчитано`() =
            assertEquals(VerificationStatus.NotVerified, verificationStatus(withVerification("RES-2"), evidence))

        @Test
        fun `недостижение цели выявлено`() =
            assertEquals(VerificationStatus.Failed, verificationStatus(withVerification("RES-3"), evidence))

        @Test
        fun `без метода — не проверено`() =
            assertEquals(VerificationStatus.NotVerified,
                verificationStatus(j("""{"mop":{"name":"Доставка","operator":"ge","value":{"value":0.9,"unit":"1"}},"verification":{}}"""), evidence))
    }

    @Nested
    @DisplayName("TZ-REQ-006: условия базирования")
    inner class Baseline {

        private val ready = j(
            """{"id":"RQ-0001","statement":"Система должна обеспечивать вероятность доставки не менее 0,9 за сутки.",
                "category":"performance","mop":{"name":"Вероятность доставки","operator":"ge","value":{"value":0.9,"unit":"1"}},
                "verification":{"method":"analysis","evidence_ref":"RES-1","means":"Прогон сценария Монте-Карло",
                  "approach":"Прогон эталонного сценария с фиксированным зерном ГПСЧ и сверка доли доставленных сообщений с целевым значением показателя."}}"""
        )

        @Test
        fun `пригодное требование базируется`() {
            val (ok, why) = baselining.canBaseline(ready)
            assertTrue(ok) { why.toString() }
        }

        @Test
        fun `незакрытый TBD блокирует базирование`() {
            val withTbd = (ready.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()).apply {
                putObject("mop").put("name", "Вероятность доставки").put("operator", "ge").put("tbd", true)
                    .putObject("value").put("value", 0.9).put("unit", "1")
            }
            val (ok, why) = baselining.canBaseline(withTbd)
            assertFalse(ok)
            assertTrue(why.any { "TBD" in it }) { why.toString() }
        }

        @Test
        fun `отсутствие метода верификации блокирует`() {
            val (ok, why) = baselining.canBaseline(good)
            assertFalse(ok)
            assertTrue(why.any { "верификации" in it }) { why.toString() }
        }
    }

    @Nested
    @DisplayName("TZ-REQ-008: готовность к контрольной точке")
    inner class Readiness {

        private val gates = Gates()
        private val pkg = listOf(
            ObjectSnapshot("RQ-0001", "requirement", "Baseline"),
            ObjectSnapshot("RQ-0002", "requirement", "Preliminary"),
            ObjectSnapshot("SV-0001", "service", "Approved"),
            ObjectSnapshot("CM-0001", "component", "Draft"),
            ObjectSnapshot("RQ-0009", "requirement", "Cancelled"),
        )

        @Test
        fun `к SRR выявлены только незрелые`() =
            assertEquals(listOf("CM-0001", "RQ-0002"), gates.readiness(pkg, "SRR").map { it.id })

        @Test
        fun `Cancelled не попадает в отчёт`() =
            assertTrue(gates.readiness(pkg, "SRR").none { it.id == "RQ-0009" })

        @Test
        fun `к SDR требования строже`() {
            val srr = gates.readiness(pkg, "SRR")
            val sdr = gates.readiness(pkg, "SDR")
            assertTrue(sdr.size > srr.size) { "SRR=${srr.size}, SDR=${sdr.size}" }
        }
    }
}
