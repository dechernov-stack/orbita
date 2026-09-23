// Интересы стороны — список её слов с цитатой (истина 24.09). Правило
// приведения одно на все входы: строка экрана, перечень эталона, список
// объектов; цитаты нетронутых интересов переживают правку.
package orbita.kernel.schema

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InterestsTest {
    private val mapper = ObjectMapper()

    @Test
    fun `строка через точку с запятой и переводы строк — интересы по порядку, без пустых и повторов`() {
        val документ = mapper.createObjectNode().put("interest", "телематика; контроль исполнения\n\n Телематика ;")
        assertTrue(Interests.нормализовать(документ))
        assertEquals(listOf("телематика", "контроль исполнения"), Interests.словами(документ.path("interest")))
        assertTrue(документ.path("interest").all { it.isObject && !it.has("quote") }, "цитаты у строки нет")
    }

    @Test
    fun `перечень строк эталона и список объектов читаются одинаково — второй прогон ничего не меняет`() {
        val документ = mapper.createObjectNode()
        документ.putArray("interest").add("прозрачность полётов").add("идентификация вне покрытия")
        assertTrue(Interests.нормализовать(документ, цитата = "…прозрачность полётов…", факт = "FT-0001"))
        assertEquals("FT-0001", документ.path("interest")[0].path("fact").asText())
        assertEquals("…прозрачность полётов…", документ.path("interest")[1].path("quote").asText())
        assertFalse(Interests.нормализовать(документ), "уже список объектов — не тронут")
    }

    @Test
    fun `правка строкой сохраняет цитату нетронутого интереса и снимает убранный`() {
        val прежние = mapper.createObjectNode()
        прежние.putArray("interest")
            .add(mapper.createObjectNode().put("statement", "телематика").put("quote", "…контроль телематики…").put("fact", "FT-0007"))
            .add(mapper.createObjectNode().put("statement", "частоты"))
        val документ = mapper.createObjectNode().put("interest", "Телематика; ледовая обстановка")
        Interests.нормализовать(документ, прежние = прежние)
        val интересы = Interests.прочитать(документ.path("interest"))
        assertEquals(listOf("Телематика", "ледовая обстановка"), интересы.map { it.statement })
        assertEquals("…контроль телематики…", интересы[0].quote, "цитата нетронутого интереса цела")
        assertEquals("FT-0007", интересы[0].fact)
        assertNull(интересы[1].quote, "у нового интереса цитаты нет")
    }

    @Test
    fun `пустое поле снимается, отсутствующее не появляется`() {
        val пустое = mapper.createObjectNode().put("interest", " ; ")
        assertTrue(Interests.нормализовать(пустое))
        assertFalse(пустое.has("interest"))
        val без = mapper.createObjectNode().put("name", "Минтранс")
        assertFalse(Interests.нормализовать(без))
        assertFalse(без.has("interest"))
    }
}
