// Единицы — одна истина в хранилище v2 (§0.1 шипа 6). Долг был про ДАННЫЕ:
// Normalize читает записи вида `unit`, которых на стенде не было, — «180 мин» и
// «3 ч» были несравнимы. Здесь грузим НАСТОЯЩУЮ порождённую полку
// (ПОЛКА-ЕДИНИЦЫ.json) в хранилище и проверяем, что Normalize.of их читает и
// канонизирует: ≤180 мин против ≤2 ч — спор, против ≤3 ч — подтверждение.
package orbita.knowledge

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.Provenance
import orbita.knowledge.internal.Difference
import orbita.knowledge.internal.Normalize
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class UnitsShelfNormalizeTest {
    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val провенанс = Provenance(Channel.MANUAL, "полка единиц")

    @BeforeTest
    fun засеять() {
        TestDbV2.очистить()
        // Настоящая полка, порождённая gen_units_shelf.py из справочника (пакет 07).
        val полка = mapper.readTree(TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые/ПОЛКА-ЕДИНИЦЫ.json").toFile())
        полка.path("items").forEach { запись ->
            store.create(
                запись.path("code").asText(), "unit", Area.Library, null,
                (запись.deepCopy() as ObjectNode).apply { remove("code") }, провенанс,
            )
        }
    }

    private fun величина(оператор: String, значение: Double, единица: String) =
        mapper.createObjectNode().put("key", "p95_latency").put("op", оператор).put("value", значение).put("unit", единица)

    @Test
    fun `полка засеяла время — минуты и часы читаются из хранилища`() {
        val минута = store.list(Area.Library, "unit").firstOrNull { it.doc.path("symbol").asText() == "min" }
        assertEquals("время", минута?.doc?.path("dimension")?.asText(), "минута с полки, размерность время")
        assertEquals(60.0, минута?.doc?.path("factor")?.asDouble(), "минута = 60 с")
    }

    @Test
    fun `сто восемьдесят минут против двух часов — спор (единицы из хранилища)`() {
        val n = Normalize.of(store, Area.Library)
        val отличие = n.сравнить("measure", величина("<=", 180.0, "min"), величина("<=", 2.0, "h"))
        assertEquals(Difference.DIFFERS, отличие.kind, "180 мин = 10800 с, 2 ч = 7200 с — расходятся")
    }

    @Test
    fun `сто восемьдесят минут против трёх часов — подтверждение`() {
        val n = Normalize.of(store, Area.Library)
        val отличие = n.сравнить("measure", величина("<=", 180.0, "min"), величина("<=", 3.0, "h"))
        assertEquals(Difference.EQUAL, отличие.kind, "180 мин = 3 ч = 10800 с — одна величина")
    }
}
