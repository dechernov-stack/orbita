// Текстовый индекс базы знаний (шип 4 §2): блоки кладутся по ключу и отпечатку,
// ищутся по русской морфологии с фильтрами, исчезнувшие снимаются. Вектор —
// когда есть pgvector; без него индекс честно лексический.
package orbita.kernel

import orbita.kernel.api.IndexBlock
import orbita.kernel.api.KernelFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PgTextIndexTest {
    private val индекс = KernelFactory.textIndex(TestDbV2.conn)

    private fun блок(key: String, kind: String, area: String, title: String, text: String, filters: Map<String, String> = emptyMap()) =
        IndexBlock(key, kind, area, key.substringAfter(':'), title, text, filters, fingerprint = (title + text + filters).hashCode().toString())

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
    }

    @Test
    fun `поиск по словам находит пункт норматива и факт, фильтр и вид сужают, отпечаток бережёт запись`() {
        val блоки = listOf(
            блок("clause:NR-1:1", "clause", "library", "ПП РФ № 2216 · обязанность",
                "Передавать координаты, курс, скорость, дату и время с интервалом не более 30 с через ГАИС «ЭРА-ГЛОНАСС»", mapOf("rank" to "mandatory")),
            блок("fact:F-1", "fact", "project:PJ-1", "записка — интервал", "максимальный интервал передачи 30 с", mapOf("rank" to "expert", "role" to "charter")),
            блок("fact:F-2", "fact", "project:PJ-1", "записка — масса", "масса платформы не более 100 кг", mapOf("rank" to "expert")),
        )
        assertEquals(3, индекс.upsert(блоки))
        assertEquals(0, индекс.upsert(блоки), "тот же отпечаток — ничего не переписано")

        val найдено = индекс.search("интервал передачи", listOf("library", "project:PJ-1"))
        assertEquals(setOf("clause:NR-1:1", "fact:F-1"), найдено.map { it.key }.toSet(), "$найдено")
        assertTrue(найдено.all { it.lexical > 0 && it.snippet.contains("интервал", ignoreCase = true) }, "$найдено")

        val толькоФакты = индекс.search("интервал", listOf("library", "project:PJ-1"), kinds = listOf("fact"))
        assertEquals(listOf("fact:F-1"), толькоФакты.map { it.key })
        val поРангу = индекс.search("интервал", listOf("library", "project:PJ-1"), filters = mapOf("rank" to "mandatory"))
        assertEquals(listOf("clause:NR-1:1"), поРангу.map { it.key })
        assertEquals(mapOf("fact" to 2), индекс.count("project:PJ-1"))

        assertEquals(1, индекс.removeMissing("project:PJ-1", "fact", setOf("fact:F-1")))
        assertEquals(mapOf("fact" to 1), индекс.count("project:PJ-1"))
    }

    @Test
    fun `без расширения pgvector семантики нет, очередь на эмбеддинг пуста`() {
        индекс.upsert(listOf(блок("term:GT-1", "term", "library", "Нужда", "потребность стейкхолдера")))
        if (!индекс.vectorReady) {
            assertTrue(индекс.withoutVector(null).isEmpty())
            assertTrue(индекс.search("нужда", listOf("library")).all { it.semantic == null })
        } else {
            assertEquals(listOf("term:GT-1"), индекс.withoutVector("library").map { it.key })
            индекс.setVector("term:GT-1", FloatArray(индекс.vectorDim) { 0.01f })
            assertTrue(индекс.withoutVector("library").isEmpty())
        }
    }
}
