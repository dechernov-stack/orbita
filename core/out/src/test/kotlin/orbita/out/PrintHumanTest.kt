// Шип 0 «трёх пакетов»: печать по-человечески. Сверка SEMP с каноном нашла в
// PDF «kind: mission_intent; held: false» — служебные ключи латиницей в
// продуктовом документе. Здесь держится обратное: записи вставок печатаются
// предложениями по-русски, веха — «SRR — 02.09.2026, не проведена», узел —
// именем без id, а латинский ключ в печатном тексте ловится сторожем, который
// отказывает выпуску.
package orbita.out

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrintHumanTest {

    private val mapper = ObjectMapper()

    /** Модель с вехами, замыслом, нуждами, узлами, стейкхолдерами и бюджетами. */
    private fun model() = mapper.readTree(
        """
        {"project": {"id": "PJ-0001", "name": "Группировка IoT", "phase": "phase_a",
                     "purpose": "Резервный канал телеметрии", "scope": "Pre-Phase A и Phase A",
                     "mission_intent": {"for_whom": "Минтранс России", "what": "резервный канал координат",
                                        "where": "вне наземного покрытия", "horizon": "2030"},
                     "milestones": [{"gate": "SRR", "due": "2026-09-02", "held": false},
                                    {"gate": "SDR", "due": "2026-09-11", "held": false, "phase": "Phase A"}],
                     "gate_tailoring": [{"gate": "SRR", "check": "trace", "rationale": "трассировка на нужды ведётся полкой",
                                         "author": "Чернов", "at": "2026-09-01"}],
                     "lifecycle": {"status": "Draft", "version": "1"}},
         "stakeholders": [{"id": "SH-0001", "name": "Минтранс России", "role": "customer",
                           "interest": "резервный канал", "supplies": []}],
         "needs": [{"id": "ND-0001", "statement": "Передавать координаты ТС не реже раза в 30 с",
                    "stakeholder": {"name": "Минтранс России", "role": "customer"}}],
         "components": {"CM-0001": {"id": "CM-0001", "name": "Космический сегмент", "kind": "segment", "parent": ""},
                        "CM-0002": {"id": "CM-0002", "name": "Система в целом", "kind": "system", "parent": ""}},
         "requirements": [{"id": "RQ-0001", "statement": "Система обязана доставлять сообщение за сутки",
                           "level": "project", "category": "performance", "rationale": "нужда ND-0001",
                           "traces_up": [{"ref": "ND-0001"}], "allocated_to": [],
                           "mop": {"name": "вероятность доставки", "operator": "ge",
                                   "value": {"value": 0.95, "unit": "1"}},
                           "verification_events": [{"id": "VE-1", "method": "analysis", "phase": "PhaseA",
                                                    "level": "system", "closes": true, "status": "planned"}],
                           "lifecycle": {"status": "Draft", "version": "1"}}],
         "budgets": [{"kind": "mass", "unit": "kg", "nominal": 48.5, "system_margin_pct": 20,
                      "dry": 58.2, "wet": 60.0, "reserve": 9.7, "within_platform_range": true}],
         "conops_scenarios": [], "validations": [], "spacecraft": {"modes": []},
         "constellation": {}, "ground_stations": {"stations": []},
         "mission_goals": [], "cost_estimates": [], "wbs_elements": [], "risks": [], "technologies": []}
        """.trimIndent(),
    )

    private fun lines(code: String): List<String> {
        val body = DocumentGenerator(mapper).render(model(), SeedTemplates.of(code)).body
        return PrintRenderer().lines(body)
    }

    @Test
    fun `в печати SEMP, ConOps и FA нет ни одного служебного ключа латиницей`() {
        listOf("semp", "conops", "fad", "project_plan", "architecture", "req_spec").forEach { code ->
            val ключи = PrintHumanizer.serviceKeys(lines(code))
            assertTrue(ключи.isEmpty()) { "$code: служебные ключи в печати — $ключи\n${lines(code).joinToString("\n")}" }
        }
    }

    @Test
    fun `веха печатается словами, а не парой gate-held`() {
        val строки = lines("semp")
        assertTrue(строки.any { it == "SRR — 02.09.2026, не проведена" }) { строки.joinToString("\n") }
        assertTrue(строки.any { it.startsWith("SDR — 11.09.2026, не проведена (Phase A") }) { строки.joinToString("\n") }
        assertFalse(строки.any { "held:" in it || "gate:" in it })
    }

    @Test
    fun `узел печатается именем без id, нужда — со стейкхолдером и ролью по-русски`() {
        val conops = lines("conops")
        assertTrue(conops.any { it.startsWith("Космический сегмент") }) { conops.joinToString("\n") }
        assertFalse(conops.any { it.startsWith("CM-0001") }) { "id узла — подсказка экрана, не печать" }
        assertTrue(conops.any { it.startsWith("ND-0001. Минтранс России (заказчик): Передавать координаты") }) {
            conops.joinToString("\n")
        }
        val semp = lines("semp")
        assertTrue(semp.any { "замысел миссии" in it && "для кого: Минтранс России" in it }) { semp.joinToString("\n") }
    }

    @Test
    fun `величины печатаются числом с единицей показа, перечисления — подписью`() {
        PrintHumanizer.unitLabel = { code -> if (code == "kg") "кг" else code }
        try {
            val arch = lines("architecture")
            assertTrue(arch.any { "масса" in it && "сухая масса: 58,2" in it || "сухая масса: 58" in it }) { arch.joinToString("\n") }
            val spec = lines("req_spec")
            assertTrue(spec.any { "уровень: проектный" in it || "уровень: проект" in it }) { spec.joinToString("\n") }
            assertFalse(spec.any { "level: project" in it })
        } finally {
            PrintHumanizer.unitLabel = { it }
        }
    }

    @Test
    fun `сторож ловит подложенный ключ и не трогает время и адреса`() {
        assertEquals(listOf("kind"), PrintHumanizer.serviceKeys(listOf("вставка: kind: mission_intent; для кого: …")))
        assertTrue(PrintHumanizer.serviceKeys(listOf("срок 12:30, см. https://kis.local/x", "Масса: 12 кг")).isEmpty())
    }

    @Test
    fun `docx собирается по тем же строкам`() {
        val body = DocumentGenerator(mapper).render(model(), SeedTemplates.of("semp")).body
        val bytes = PrintRenderer().docx(
            body, PrintMeta("Группировка IoT", "semp · проба", "1", "issued", "2026-09-02", "Чернов"),
        )
        assertTrue(bytes.size > 2000) { "docx пустой" }
    }
}
