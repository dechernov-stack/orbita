// Перенос исполняемого эталона spec/traceability_semantics.py — один в один,
// 34 проверки (CR-003/ADR-019). Названия сохранены.
//
// Идентификаторы событий приведены к формату нормативной схемы (VE-NNNN);
// в эталоне они записаны как VE-1 и в утверждениях не участвуют.
package orbita.req

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.store.Link
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

private val mapper = ObjectMapper()
private fun j(json: String): JsonNode = mapper.readTree(json)
private fun req(id: String, allocated: String = "[]", category: String? = null): JsonNode =
    j("""{"id":"$id","allocated_to":$allocated${if (category != null) ""","category":"$category"""" else ""}}""")

class TraceabilitySemanticsTest {

    private val components = mapOf(
        "CM-0001" to ProductNode("CM-0001", "system"),
        "CM-0010" to ProductNode("CM-0010", "segment", parent = "CM-0001"),
        "CM-0011" to ProductNode("CM-0011", "subsystem", parent = "CM-0010"),
        "CM-0020" to ProductNode("CM-0020", "segment", parent = "CM-0001"),
        "IF-0001" to ProductNode("IF-0001", "interface", owners = listOf("CM-0010", "CM-0020")),
        "IF-0002" to ProductNode("IF-0002", "interface", owners = listOf("CM-0010")),
    )

    @Nested
    @DisplayName("Дерево требований ↔ дерево компонентов")
    inner class Trees {

        private val parent = req("RQ-0100", """[{"component":"CM-0001"}]""")
        private val childOk = req("RQ-0101", """[{"component":"CM-0011"}]""")
        private val childBad = req("RQ-0102", """[{"component":"CM-9999"}]""")

        @Test
        fun `потомок на подчинённом элементе — согласовано`() =
            assertTrue(allocationConsistent(parent, childOk, components).first)

        @Test
        fun `потомок вне области родителя выявлен`() {
            val (ok, why) = allocationConsistent(parent, childBad, components)
            assertFalse(ok) { "$why" }
        }

        @Test
        fun `потомок на соседней ветви выявлен`() {
            val mid = req("RQ-0110", """[{"component":"CM-0010"}]""")
            val sibling = req("RQ-0111", """[{"component":"CM-0020"}]""")
            assertFalse(allocationConsistent(mid, sibling, components).first)
        }

        @Test
        fun `нераспределённые требования не блокируют проверку`() =
            assertTrue(allocationConsistent(req("RQ-0x"), childOk, components).first)
    }

    @Nested
    @DisplayName("Интерфейсные требования")
    inner class Interfaces {

        @Test
        fun `интерфейсное требование на интерфейсе принято`() =
            assertTrue(
                interfaceAllocationValid(
                    req("RQ-0200", """[{"interface":"IF-0001"}]""", category = "interface"), components,
                ).first
            )

        @Test
        fun `интерфейсное требование на элементе отклонено`() =
            assertFalse(
                interfaceAllocationValid(
                    req("RQ-0201", """[{"component":"CM-0010"}]""", category = "interface"), components,
                ).first
            )

        @Test
        fun `интерфейс без второй стороны отклонён`() =
            assertFalse(
                interfaceAllocationValid(
                    req("RQ-0202", """[{"interface":"IF-0002"}]""", category = "interface"), components,
                ).first
            )
    }

    @Nested
    @DisplayName("Спецификация элемента")
    inner class Specification {

        private val requirements = listOf(
            req("RQ-0100", """[{"component":"CM-0001"}]"""),
            req("RQ-0101", """[{"component":"CM-0011"}]"""),
            req("RQ-0105", """[{"component":"CM-0011"}]"""),
            req("RQ-0111", """[{"component":"CM-0020"}]"""),
        )

        @Test
        fun `спецификация элемента собирается из распределённых требований`() =
            assertEquals(listOf("RQ-0101", "RQ-0105"), componentSpecification(requirements, "CM-0011"))

        @Test
        fun `элемент без требований даёт пустую спецификацию`() =
            assertEquals(emptyList<String>(), componentSpecification(requirements, "CM-0099"))
    }

    @Nested
    @DisplayName("Распределённые и производные требования")
    inner class Derivation {

        private val links = listOf(
            Link("RQ-0100", "RQ-0101", "derive", null, derivationKind = "allocated"),
            Link("RQ-0100", "RQ-0105", "derive", null, derivationKind = "allocated"),
            Link("RQ-0100", "RQ-0107", "derive", null, derivationKind = "derived"),
        )

        @Test
        fun `в свёртку входят только распределённые потомки`() =
            assertEquals(listOf("RQ-0101", "RQ-0105"), rollupChildIds("RQ-0100", links))

        @Test
        fun `производное требование в бюджет не входит`() =
            assertFalse("RQ-0107" in rollupChildIds("RQ-0100", links))
    }

    // ---- события верификации ----

    private val prelim = """
        {"id":"VE-0001","method":"analysis","phase":"PhaseA","level":"system","kind":"preliminary",
         "approach":"Расчёт массы по MEL с резервами по зрелости элементов",
         "means":"Модель MEL","status":"passed","closes":false}
    """
    private val final = """
        {"id":"VE-0002","method":"test","phase":"PhaseD","level":"system","kind":"qualification",
         "approach":"Взвешивание собранного аппарата после интеграции с фиксацией в протоколе",
         "means":"Весовой стенд, поверенное оборудование","status":"planned","closes":true,
         "design_version":"v1"}
    """

    private fun withEvents(vararg events: String): JsonNode =
        j("""{"id":"RQ-0100","verification_events":[${events.joinToString(",")}]}""")

    private fun finalWith(status: String) = final.replace("\"status\":\"planned\"", "\"status\":\"$status\"")

    @Nested
    @DisplayName("Верификация: несколько событий на требование")
    inner class Events {

        @Test
        fun `предварительный расчёт не закрывает верификацию`() =
            assertEquals(
                VerificationState.PreliminarilyConfirmed,
                verificationState(withEvents(prelim, final)),
            )

        @Test
        fun `успешное закрывающее событие верифицирует требование`() =
            assertEquals(
                VerificationState.Verified,
                verificationState(withEvents(prelim, finalWith("passed"))),
            )

        @Test
        fun `провал закрывающего события — не выполнено`() =
            assertEquals(VerificationState.Failed, verificationState(withEvents(prelim, finalWith("failed"))))

        @Test
        fun `отсутствие событий — верификация не запланирована`() =
            assertEquals(VerificationState.NotPlanned, verificationState(j("""{"id":"x"}""")))

        @Test
        fun `план без закрывающего события выявлен`() =
            assertEquals(VerificationState.PlanIncomplete, verificationState(withEvents(prelim)))
    }

    @Nested
    @DisplayName("Полнота отдельного события")
    inner class EventCompleteness {

        private fun event(json: String, mutate: (ObjectNode) -> Unit = {}): JsonNode =
            (mapper.readTree(json) as ObjectNode).also(mutate)

        @Test
        fun `полное событие без замечаний`() = assertEquals(emptyList<String>(), eventIssues(j(final)))

        @Test
        fun `событие без описания подхода отклонено`() =
            assertTrue(
                eventIssues(event(final) { it.put("approach", "") }).any { "как выполняется" in it }
            )

        @Test
        fun `испытание без средства отклонено`() =
            assertTrue(eventIssues(event(final) { it.put("means", "") }).any { "средство" in it })

        @Test
        fun `предварительное событие не может быть закрывающим`() =
            assertTrue(
                eventIssues(event(prelim) { it.put("closes", true) }).any { "не закрывает" in it }
            )

        @Test
        fun `событие без уровня проверки отклонено`() =
            assertTrue(eventIssues(event(final) { it.putNull("level") }).any { "уровень" in it })
    }

    @Nested
    @DisplayName("Квалификация и приёмка")
    inner class Qualification {

        @Test
        fun `повторная квалификация одной конструкции выявлена`() =
            assertTrue(qualificationScope(listOf(j(final), j(final.replace("VE-0002", "VE-0003")))).isNotEmpty())

        @Test
        fun `квалификация другой версии конструкции допустима`() =
            assertEquals(
                emptyList<String>(),
                qualificationScope(
                    listOf(
                        j(final),
                        j(final.replace("VE-0002", "VE-0003").replace("\"design_version\":\"v1\"", "\"design_version\":\"v2\"")),
                    )
                ),
            )

        @Test
        fun `приёмка без указания экземпляра выявлена`() =
            assertTrue(qualificationScope(listOf(j("""{"kind":"acceptance"}"""))).isNotEmpty())

        @Test
        fun `приёмка с экземпляром принята`() =
            assertEquals(
                emptyList<String>(),
                qualificationScope(listOf(j("""{"kind":"acceptance","unit":"FM-01"}"""))),
            )
    }

    @Nested
    @DisplayName("Свидетельства: предварительный расчёт → физическое испытание")
    inner class EvidenceDocs {

        private val docs = listOf(
            j("""{"id":"EV-0001","kind":"analysis_report","maturity":"preliminary","date":"2026-03-01",
                  "configuration":"C1","superseded_by":"EV-0002"}"""),
            j("""{"id":"EV-0002","kind":"test_report","maturity":"final","date":"2027-06-01",
                  "configuration":"C1"}"""),
        )

        @Test
        fun `цепочка свидетельств упорядочена по времени`() =
            assertEquals(listOf("EV-0001", "EV-0002"), evidenceChain(docs))

        @Test
        fun `заменённое свидетельство помечено`() =
            assertEquals(EvidenceState.Superseded, evidenceState(docs[0], "C1"))

        @Test
        fun `итоговое свидетельство действительно`() =
            assertEquals(EvidenceState.Valid, evidenceState(docs[1], "C1"))

        @Test
        fun `свидетельство для другой конфигурации неприменимо`() =
            assertEquals(EvidenceState.NotApplicable, evidenceState(docs[1], "C2"))
    }

    @Nested
    @DisplayName("Валидация отдельно от верификации")
    inner class ValidationActivities {

        private val valid = """{"target":"ND-0007","conops_ref":"CO-0003","product_kind":"model","phase":"PhaseA"}"""

        private fun withField(field: String, value: String?): JsonNode =
            (mapper.readTree(valid) as ObjectNode).also {
                if (value == null) it.remove(field) else it.put(field, value)
            }

        @Test
        fun `валидация ожидания на модели фазы принята`() =
            assertEquals(emptyList<String>(), validationIssues(j(valid)))

        @Test
        fun `валидация, привязанная к требованию, отклонена`() =
            assertTrue(
                validationIssues(withField("target", "RQ-0100")).any { "а не к ожиданию" in it }
            )

        @Test
        fun `валидация без ссылки на ConOps отклонена`() =
            assertTrue(validationIssues(withField("conops_ref", null)).any { "ConOps" in it })

        @Test
        fun `валидация без указания продукта отклонена`() =
            assertTrue(
                validationIssues(withField("product_kind", null)).any { "на чём выполняется" in it }
            )

        @Test
        fun `валидация допустима на сервисе`() =
            assertEquals(emptyList<String>(), validationIssues(withField("target", "SV-0002")))
    }
}
