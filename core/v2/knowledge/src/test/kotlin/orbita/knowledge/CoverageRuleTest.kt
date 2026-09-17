// Чем закрывается нужда — правило владельца, а не догадка кода (истина 17.09).
//
// Здесь проверяется, что карта «роль носителя → вид покрытия» пришла ИЗ
// ОНТОЛОГИИ целиком и что поле у самой нужды сильнее карты («инженер правит»).
package orbita.knowledge

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.knowledge.api.Coverage
import orbita.knowledge.schema.GeneratedOntology
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoverageRuleTest {

    private val mapper = ObjectMapper()

    @Test
    fun `карта покрытия названа истиной для каждой роли стороны`() {
        val роли = orbita.kernel.schema.GeneratedKinds.of("stakeholder").enums["role"].orEmpty()
        assertTrue(роли.isNotEmpty(), "перечень ролей стороны есть в истине схем")
        роли.forEach { роль ->
            assertTrue(роль in Coverage.byRole, "у роли «$роль» назван вид покрытия: ${Coverage.byRole}")
        }
        assertEquals("service", Coverage.byRole["customer"])
        assertEquals("constraint", Coverage.byRole["regulator"])
        assertEquals("programme", Coverage.byRole["supplier"])
    }

    @Test
    fun `поле нужды сильнее карты, неназванная роль покрытия не получает`() {
        val своё = mapper.createObjectNode().put("statement", "нужда").put(Coverage.FIELD, "requirement")
        assertEquals("requirement", Coverage.expected(своё, "regulator"), "названное у нужды сильнее карты")

        val пустая = mapper.createObjectNode().put("statement", "нужда")
        assertEquals("service", Coverage.expected(пустая, "consumer"))
        assertNull(Coverage.expected(пустая, "инопланетянин"), "роль вне истины покрытия не получает")
        assertNull(Coverage.expected(пустая, null))
        assertTrue(Coverage.expectsService(пустая, "operator"))
        assertTrue(!Coverage.expectsService(пустая, "supplier"))
    }

    @Test
    fun `правило сцены 6 и пометы сцен названы истиной словами`() {
        assertTrue("coverage_expected=service" in Coverage.rule, "правило выхода сцены 6 дословно: ${Coverage.rule}")
        assertTrue(Coverage.sceneNotes.keys.containsAll(listOf("scene_6", "scene_8", "scene_12")))
        assertTrue(
            GeneratedOntology.confirmedEdits.any { "17.09" in it && "intent.accepted_by" in it },
            "подтверждённые правки истины перечислены: ${GeneratedOntology.confirmedEdits}",
        )
    }
}
