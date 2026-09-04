// Ж-01 (прогон 04.09): перегрузка модели — состояние минуты, а не отказ
// работы. Живой вызов упал с «провайдер прервал поток: Overloaded», и человек
// остался ни с чем. Запрос повторяется трижды: он идемпотентен — в модель
// ничего не пишется до акцепта человеком. Отказ по ключу или форме не
// повторяется: ждать там нечего.
package orbita.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProviderRetryTest {

    /** Канал, считающий попытки: первые N падают названной причиной. */
    private class Считающий(
        private val падений: Int,
        private val причина: String,
    ) : ProviderTransport {
        var попыток = 0
        override fun ask(prompt: String, modelHint: String?): ProviderAnswer {
            попыток += 1
            if (попыток <= падений) throw ProviderUnavailableException(причина)
            return ProviderAnswer("ответ", "модель", 10, 20)
        }
    }

    @Test
    fun `перегрузка повторяется и ответ доходит - человек не остаётся ни с чем`() {
        val канал = Считающий(2, "провайдер прервал поток: Overloaded")
        val сПовтором = RetryingTransport(канал, паузыМс = longArrayOf(0, 0, 0))
        val ответ = сПовтором.ask("промпт", null)
        assertEquals("ответ", ответ.text)
        assertEquals(3, канал.попыток) { "две неудачи и третья удачная" }
    }

    @Test
    fun `три перегрузки подряд - отказ называет причину и число попыток`() {
        val канал = Считающий(9, "провайдер прервал поток: Overloaded")
        val сПовтором = RetryingTransport(канал, паузыМс = longArrayOf(0, 0, 0))
        val e = assertThrows(ProviderUnavailableException::class.java) { сПовтором.ask("промпт", null) }
        assertEquals(3, канал.попыток)
        assertTrue(e.message!!.contains("Overloaded") && e.message!!.contains("попыток: 3")) { e.message!! }
    }

    @Test
    fun `отказ по ключу не повторяется - ждать там нечего`() {
        val канал = Считающий(9, "прямой канал не настроен: задайте ORBITA_AI_KEY")
        val сПовтором = RetryingTransport(канал, паузыМс = longArrayOf(0, 0, 0))
        assertThrows(ProviderUnavailableException::class.java) { сПовтором.ask("промпт", null) }
        assertEquals(1, канал.попыток) { "форма и ключ — не перегрузка" }
    }
}
