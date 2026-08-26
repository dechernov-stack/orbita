// Валидация по нормативным схемам (TZ-MOD-001, TZ-MOD-002, TZ-MOD-003):
// схемы загружаются при старте, позитивный набор проходит, негативные наборы
// из spec/fixtures/example-violations.json отклоняются с указанием ADR.
package orbita.mod

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.schema.SchemaRegistry
import orbita.mod.schema.ValidationError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SchemaValidationTest {

    private val mapper = ObjectMapper()
    private val registry = SchemaRegistry(RepoPaths.schemasDir())
    private val violations: Map<String, JsonNode> = mapper
        .readTree(RepoPaths.repoRoot().resolve("spec/fixtures/example-violations.json").toFile())
        .get("violations").associateBy { it["rule"].asText() }

    // --- TZ-MOD-001: схемы — нормативный источник, все загружаются, $ref разрешаются ---

    @Test
    fun `все нормативные схемы загружаются и ссылки разрешаются`() {
        // разрешение $ref происходит в init реестра (fail fast) — сюда доходим, только если оно удалось
        // CR-003 добавил core/evidence и core/validation
        // 20-я — core/risk, добавлена на шаге 7 вместе с реестром рисков
        // 21-я и 22-я — core/constellation и core/ground-stations (CR-005/ADR-021),
        // 23-я — core/interface (Шаг 16), 24–28-я — блок C (Шаг 17): conops,
        // 29–34-я — блок C задания «прогон до KDP B»: mission-goal, alternative,
        // cost-estimate, oda, review-item, wbs-element; 35-я — ai-profile (П5:
        // ограничения службы ИИ объектом, а не текстом в коде);
        // 36-я — source-document (ADR-030: библиотека исходных документов);
        // technology, decision, project, document-issue:
        // входы моделирования стали хранимыми объектами и получили схемы в core/
        assertEquals(41, registry.names.size, "нормативных схем должно быть 36: ${registry.names}")
    }

    @Test
    fun `позитивный набор проходит валидацию`() {
        val fixture = mapper.readTree(RepoPaths.repoRoot().resolve("spec/fixtures/example-valid.json").toFile())
        assertEquals(emptyList<ValidationError>(), registry.validate("core/service", fixture["service"]))
    }

    // --- TZ-MOD-002: ошибка содержит путь до поля и нарушенное правило ---

    @Test
    fun `ошибка валидации содержит путь до поля и правило`() {
        val errors = registry.validate("contracts/spacecraft", spacecraftWith("Р2"))
        val e = errors.single()
        assertEquals("/platform/dry_mass_kg", e.path)
        assertEquals("maximum", e.rule)
    }

    // --- TZ-MOD-003: нарушения Р1–Р9 отклоняются с идентификатором ADR ---

    @Test
    fun `Р1 bent-pipe отклоняется схемой spacecraft со ссылкой на ADR-001`() =
        assertRejected("contracts/spacecraft", spacecraftWith("Р1"), "ADR-001")

    @Test
    fun `Р2 масса вне 12-100 кг отклоняется схемой spacecraft со ссылкой на ADR-002`() =
        assertRejected("contracts/spacecraft", spacecraftWith("Р2"), "ADR-002")

    @Test
    fun `Р3 оптический ISL отклоняется схемой radio-link со ссылкой на ADR-003`() {
        val link = minimalRadioLink().apply { setAll<ObjectNode>(violations.getValue("Р3")["link"] as ObjectNode) }
        assertRejected("contracts/radio-link", link, "ADR-003")
    }

    @Test
    fun `Р3 оптический ISL внутри spacecraft также отклоняется со ссылкой на ADR-003`() {
        val sc = minimalSpacecraft()
        (sc.at("/payload/links/0") as ObjectNode).put("medium", "optical")
        assertRejected("contracts/spacecraft", sc, "ADR-003")
    }

    @Test
    fun `Р5 терминал без эфемерид отклоняется схемой terminal-profile со ссылкой на ADR-005`() {
        val t = minimalTerminalProfile()
        (t.at("/ephemeris") as ObjectNode).setAll<ObjectNode>(violations.getValue("Р5")["ephemeris"] as ObjectNode)
        assertRejected("contracts/terminal-profile", t, "ADR-005")
    }

    @Test
    fun `Р7 роуминг регуляторных зон отклоняется схемой terminal-profile со ссылкой на ADR-007`() {
        val t = minimalTerminalProfile()
        t.set<ObjectNode>("mobility", violations.getValue("Р7")["mobility"])
        assertRejected("contracts/terminal-profile", t, "ADR-007")
    }

    // --- Негативные наборы без ADR: нарушения общесистемных требований ---

    @Test
    fun `величина без происхождения отклоняется схемой quantity`() {
        val errors = registry.validate("common/quantity", violations.getValue("TZ-COM-005")["quantity"])
        assertTrue(errors.any { it.rule == "required" && it.message.contains("provenance") }) { errors.toString() }
    }

    @Test
    fun `сценарий без зерна ГПСЧ отклоняется схемой scenario`() {
        val errors = registry.validate("core/scenario", violations.getValue("TZ-COM-006")["scenario"])
        assertTrue(errors.any { it.rule == "required" && it.message.contains("rng_seed") }) { errors.toString() }
    }

    // --- Вспомогательное: минимальные валидные документы, в которые вживляется нарушение ---

    private fun assertRejected(schema: String, docWithViolation: JsonNode, adr: String) {
        val errors = registry.validate(schema, docWithViolation)
        assertTrue(errors.isNotEmpty()) { "нарушение должно быть отклонено схемой $schema" }
        assertTrue(errors.any { it.adr?.startsWith(adr) == true }) {
            "среди ошибок должна быть ссылка на $adr: $errors"
        }
        // текст ошибки содержит идентификатор ADR (приёмка TZ-MOD-003)
        assertTrue(errors.any { it.toString().contains(adr) })
    }

    /** Вживляет фрагмент нарушения из фикстуры в минимальный валидный документ КА. */
    private fun spacecraftWith(rule: String): ObjectNode {
        val sc = minimalSpacecraft()
        val entry = violations.getValue(rule)
        entry["payload"]?.let { (sc.at("/payload") as ObjectNode).setAll<ObjectNode>(it as ObjectNode) }
        entry["platform"]?.let { (sc.at("/platform") as ObjectNode).setAll<ObjectNode>(it as ObjectNode) }
        return sc
    }

    private fun minimalSpacecraft(): ObjectNode = parse(
        """
        {"id": "SP-0001",
         "platform": {
           "dry_mass_kg": 50,
           "power": {"sa_area_m2": 0.5, "sa_efficiency": 0.3, "battery_wh": 100},
           "attitude": {"pointing_accuracy_deg": 1.0}},
         "payload": {
           "architecture": "regenerative",
           "links": [{"id": "L1", "role": "user_uplink", "band_hz": 868.0e6, "tx_power_w": 2,
                      "antenna": {"type": "patch", "gain_dbi": 6}}],
           "onboard": {"buffer_mb": 64, "priority_policy": ["C_prime", "B_prime", "A_prime"]}}}
        """
    ).also { assertEquals(emptyList<ValidationError>(), registry.validate("contracts/spacecraft", it)) }

    private fun minimalRadioLink(): ObjectNode = parse(
        """
        {"id": "ISL-1", "role": "isl", "band_hz": 2.2e9, "tx_power_w": 1,
         "antenna": {"type": "patch", "gain_dbi": 6}}
        """
    ).also { assertEquals(emptyList<ValidationError>(), registry.validate("contracts/radio-link", it)) }

    private fun minimalTerminalProfile(): ObjectNode = parse(
        """
        {"id": "TP-0001", "consumer_class": "A_prime",
         "radio": {"eirp_dbm": 14, "rx_sensitivity_dbm": -137},
         "generation": {"model": "periodic", "rate_per_day": 24, "payload_bytes": 24},
         "ephemeris": {"knows_ephemeris": true, "max_almanac_age_s": 86400}}
        """
    ).also { assertEquals(emptyList<ValidationError>(), registry.validate("contracts/terminal-profile", it)) }

    private fun parse(json: String): ObjectNode = mapper.readTree(json) as ObjectNode
}
