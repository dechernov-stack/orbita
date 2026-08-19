// Гарантии уровня типов (TZ-MOD-004, STEP-1 §1.2): величина без единицы или
// происхождения не существует. Отсутствие конструктора без provenance — свойство
// сигнатуры Quantity; здесь проверяется то, что выразимо исполняемо.
package orbita.mod

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.model.Provenance
import orbita.mod.model.Quantity
import orbita.mod.schema.SchemaRegistry
import orbita.mod.schema.ValidationError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class QuantityTest {

    private val mapper = ObjectMapper()
    private val registry = SchemaRegistry(RepoPaths.schemasDir())

    @Test
    fun `пустая единица отклоняется при создании, а не постфактум`() {
        assertThrows<IllegalArgumentException> { Quantity(50.0, "", Provenance.Manual()) }
        assertThrows<IllegalArgumentException> { Quantity(50.0, "   ", Provenance.Manual()) }
    }

    @Test
    fun `нечисловое значение отклоняется`() {
        assertThrows<IllegalArgumentException> { Quantity(Double.NaN, "kg", Provenance.Manual()) }
    }

    @Test
    fun `углы хранятся в радианах и отображаются в градусах`() {
        val angle = Quantity.angleFromDegrees(90.0, Provenance.Manual(author = "test"))
        assertEquals("rad", angle.unit)
        assertEquals(Math.PI / 2, angle.value, 1e-12)
        assertEquals(90.0, angle.displayDegrees(), 1e-12)
        assertThrows<IllegalArgumentException> { Quantity(1.0, "deg", Provenance.Manual()).displayDegrees() }
    }

    @Test
    fun `сериализация Quantity соответствует нормативной схеме`() {
        val q = Quantity(50.0, "kg", Provenance.Manual(author = "engineer"), marginPct = 15.0)
        assertEquals(emptyList<ValidationError>(), registry.validate("common/quantity", q.toJson(mapper)))
    }

    @Test
    fun `сериализация происхождения ИИ соответствует схеме и несёт признак акцепта`() {
        val ai = Provenance.AiProposed(promptPackageId = "PP-1", accepted = false, llm = "claude")
        val json = ai.toJson(mapper)
        assertEquals(false, json["ai"]["accepted"].asBoolean())
        assertEquals(emptyList<ValidationError>(), registry.validate("common/provenance", json))
        // расчётное происхождение обязано нести модуль-вычислитель (TZ-COM-005)
        val computed = Provenance.Computed(module = "ballistics", moduleVersion = "0.1", inputVersion = "42")
        assertEquals(emptyList<ValidationError>(), registry.validate("common/provenance", computed.toJson(mapper)))
    }
}
