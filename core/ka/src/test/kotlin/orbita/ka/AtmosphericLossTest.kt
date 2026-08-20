// Атмосферные потери от угла места (ADR-025, шаг 12.4).
// Перенос новых проверок эталона spec/spacecraft_semantics.py один в один.
package orbita.ka

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class AtmosphericLossTest {

    @Test
    fun `у горизонта потери больше, чем в зените`() =
        assertTrue(atmosphericLossDb(5.0) > atmosphericLossDb(90.0))

    @Test
    fun `в зените потери равны зенитному поглощению`() =
        assertTrue(abs(atmosphericLossDb(90.0) - ATM_ZENITH_UHF_DB) < 1e-12)

    /** УВЧ, не Ka: на 868 МГц даже у границы зоны потери меньше полудецибела. */
    @Test
    fun `на границе зоны потери меньше полудецибела`() =
        assertTrue(atmosphericLossDb(5.0) < 0.5) { atmosphericLossDb(5.0).toString() }

    /** Ниже 5° плоское приближение завышает путь — не экстраполируется. */
    @Test
    fun `ниже пяти градусов потери не экстраполируются`() =
        assertEquals(atmosphericLossDb(5.0), atmosphericLossDb(1.0))
}
