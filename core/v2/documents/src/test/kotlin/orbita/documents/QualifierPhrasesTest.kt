// Обороты для сторожа и промпта (день живой модели 25.09, MCReport §3):
// квалификатор с продолжением — «не их замещение», не голое «не».
package orbita.documents

import orbita.documents.internal.NumberGuard
import kotlin.test.Test
import kotlin.test.assertEquals

class QualifierPhrasesTest {
    private val словарь = listOf("не менее", "не более", "не", "без", "только")

    @Test
    fun `оборот — квалификатор с двумя словами продолжения, длинный квалификатор раньше короткого`() {
        assertEquals(
            listOf("без смены прикладной", "не их замещение", "не менее 20 минут", "только с подтвержденным"),
            NumberGuard.phrases(
                "Непрерывность регуляторной телематики без смены прикладной системы · интеграция с AIS/LRIT, а не их замещение; " +
                    "связь не менее 20 минут в сутки, только с подтверждённым спросом.",
                словарь,
            ),
        )
    }

    @Test
    fun `оборот обрывается на знаке препинания и разделителе ячеек, «не» внутри слова не считается`() {
        assertEquals(listOf("не"), NumberGuard.phrases("нештатная ситуация, а не.", словарь), "конец фразы — оборот из одного слова")
        assertEquals(listOf("без внешней"), NumberGuard.phrases("оператор без внешней | зависимости", словарь))
        assertEquals(emptyList(), NumberGuard.phrases("нештатное уменьшение", словарь))
    }
}
