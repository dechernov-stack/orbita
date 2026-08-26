// Комплекты документов (блок C): Д-коды регламентов покрыты шаблонами без
// пропусков, каждый шаблон комплекта существует, и каждый Д-код операций
// процесса разрешается в шаблон — иначе прохождение точки потребовало бы
// выпуск документа, который нечем собрать.
package orbita.out

import orbita.req.Operations
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DocumentKitsTest {

    @Test
    fun `комплекты полны - Д1-Д9 и Д1-Д10 без пропусков`() {
        assertEquals((1..9).map { "Д$it" }, DocumentKits.PRE_PHASE_A.keys.toList())
        assertEquals((1..10).map { "Д$it" }, DocumentKits.PHASE_A.keys.toList())
    }

    @Test
    fun `каждый шаблон комплекта существует в генераторе`() {
        (DocumentKits.PRE_PHASE_A.values + DocumentKits.PHASE_A.values).toSet().forEach { code ->
            SeedTemplates.of(code) // неизвестный код бросил бы исключение
        }
    }

    @Test
    fun `каждый Д-код операций процесса разрешается в шаблон своей фазы`() {
        val ops = Operations()
        for (phase in listOf("pre_phase_a", "phase_a")) {
            val kit = DocumentKits.kit(phase)
            ops.ofPhase(phase).flatMap { it.docs }.distinct().forEach { d ->
                assertTrue(d in kit) { "$phase: Д-код $d операций не имеет шаблона в комплекте" }
            }
        }
    }

    @Test
    fun `тринадцать шаблонов блока C - все со своим источником структуры`() {
        // 3 было до блока C (req_spec, conops, architecture), 13 добавил блок C
        assertEquals(16, SeedTemplates.all.size)
        SeedTemplates.all.forEach { t ->
            assertTrue(t.sections.isNotEmpty()) { t.code }
            assertTrue(t.source.isNotBlank()) { t.code }
        }
    }
}
