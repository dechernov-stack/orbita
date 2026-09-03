// Подписи перечислений покрывают то, что модель может выдать на экран
// (шаг 15 §2: «коды перечислений не выходят в интерфейс»).
//
// Значения берутся ИЗ СХЕМ, а не переписываются в тест: список в тесте
// разошёлся бы со схемой молча — ровно так код `regulator` и оказался
// на экране нужд.
package orbita.req

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.io.path.readText

class EnumLabelsTest {

    private val labels = EnumLabels()
    private val mapper = ObjectMapper()

    /**
     * Группа подписей → где в схемах живёт её перечисление. Пара «группа —
     * путь» и есть то, что связывает подпись с моделью; без неё подпись
     * пережила бы удаление кода из схемы и наоборот.
     */
    private val bindings = listOf(
        Binding("lifecycle", "common/status", "/properties/status"),
        Binding("provenance_source", "common/provenance", "/properties/source"),
        Binding("stakeholder_role", "core/need", "/properties/stakeholder/properties/role"),
        Binding("consumer_class", "core/service", "/properties/qos_profiles/items/properties/consumer_class"),
        Binding("requirement_category", "core/requirement", "/properties/category"),
        Binding("requirement_level", "core/requirement", "/properties/level"),
        Binding("requirement_priority", "core/requirement", "/properties/priority"),
        Binding("function_level", "core/function", "/properties/level"),
        Binding("requirement_relation_kind", "core/requirement", "/properties/relations/items/properties/kind"),
        Binding("verification_method", "core/requirement", "/properties/verification_events/items/properties/method"),
        Binding("verification_kind", "core/requirement", "/properties/verification_events/items/properties/kind"),
        Binding("verification_status", "core/requirement", "/properties/verification_events/items/properties/status"),
        Binding("phase", "core/requirement", "/properties/verification_events/items/properties/phase"),
        Binding("risk_strategy", "core/risk", "/properties/strategy"),
        Binding("risk_status", "core/risk", "/properties/status"),
        Binding("segment", "core/component", "/properties/segment"),
        Binding("component_kind", "core/component", "/properties/kind"),
        Binding("interface_type", "core/component", "/properties/interfaces/items/properties/type"),
        Binding("review", "core/component", "/properties/parameters/items/properties/tpm/properties/trend/items/properties/review"),
        Binding("verification_level", "core/requirement", "/properties/verification_events/items/properties/level"),
        Binding("allocation_kind", "core/requirement", "/properties/allocated_to/items/properties/kind"),
        Binding("mop_operator", "core/requirement", "/properties/mop/properties/operator"),
        Binding("mop_rollup", "core/requirement", "/properties/mop/properties/rollup"),
        Binding("moe_name", "core/service", "/properties/qos_profiles/items/properties/moe/items/properties/name"),
        Binding("subsystem", "contracts/spacecraft", "/properties/platform/properties/mel/items/properties/subsystem"),
        Binding("spacecraft_mode", "contracts/spacecraft", "/properties/modes/items/properties/name"),
        Binding("limiting_factor", "contracts/service-zone", "/properties/boundary/properties/limiting_factor"),
    )

    private data class Binding(val group: String, val schema: String, val pointer: String)

    private fun schemaNode(name: String): JsonNode =
        mapper.readTree(RepoPaths.schemasDir().resolve("$name.schema.json").readText())

    @Test
    @DisplayName("шаг 15: каждое значение перечисления из схемы имеет подпись")
    fun `подписи покрывают схемы`() {
        val missing = mutableListOf<String>()
        bindings.forEach { b ->
            val node = schemaNode(b.schema).at(b.pointer)
            assertTrue(!node.isMissingNode) { "путь ${b.pointer} в схеме ${b.schema} не найден" }
            val values = node.path("enum").map { it.asText() }
            assertTrue(values.isNotEmpty()) { "в ${b.schema}${b.pointer} нет перечисления" }
            values.forEach { code ->
                if (labels.label(b.group, code) == code) missing += "${b.group}/$code"
            }
        }
        assertEquals(emptyList<String>(), missing) { "коды без подписи: $missing" }
    }

    @Test
    @DisplayName("шаг 15: неизвестный код выводится как есть, а не теряется")
    fun `неизвестный код не теряется`() {
        assertEquals("невиданный_код", labels.label("lifecycle", "невиданный_код"))
        assertEquals("Draft", labels.label("группы-нет", "Draft"))
        assertEquals("черновик", labels.label("lifecycle", "Draft"))
    }
}
