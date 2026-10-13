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

    @Test
    fun `написание единицы «минут» узнаётся из полки (spellings, правка 28_09)`() {
        val n = Normalize.of(store, Area.Library)
        // «минут» — написание минуты (spellings полки), а не символ; узнаётся и сравнивается.
        val отличие = n.сравнить("measure", величина("<=", 180.0, "минут"), величина("<=", 3.0, "ч"))
        assertEquals(Difference.EQUAL, отличие.kind, "«минут» и «ч» приведены к канону через написания полки")
    }

    // --- план (а) владельца 13.10: потолок и пол меры — полями max/min -----

    private fun граница(мера: ObjectNode) =
        orbita.knowledge.internal.NormalizeMeasures(store, mapper).bound(мера)

    @Test
    fun `max без op читается как потолок — план а владельца 13_10`() {
        // Живой пример дня: модель дала {"unit":"s","max":"300"} — строже
        // родителя «≤ 900 s»; до правки bound не читал max и ворота отбивали.
        val г = граница(mapper.createObjectNode().put("unit", "s").put("max", "300"))
        assertEquals("<=", г?.op, "max — это потолок")
        assertEquals(300.0, г?.value, "число из max, в каноне размерности")
        assertEquals("время", г?.dimension)
    }

    @Test
    fun `min без op читается как пол`() {
        val г = граница(mapper.createObjectNode().put("unit", "min").put("min", "15"))
        assertEquals(">=", г?.op, "min — это пол")
        assertEquals(900.0, г?.value, "15 мин = 900 с в каноне")
        assertEquals("время", г?.dimension)
    }

    @Test
    fun `min и max вместе — потолок сильнее (max), ворота широкие на ранних фазах`() {
        val г = граница(mapper.createObjectNode().put("unit", "s").put("min", "60").put("max", "300"))
        assertEquals("<=", г?.op, "диапазон читается по потолку")
        assertEquals(300.0, г?.value)
    }

    @Test
    fun `value и max вместе без op — потолок, живой пример дня 900 s`() {
        val г = граница(mapper.createObjectNode().put("unit", "s").put("value", "900").put("max", "900"))
        assertEquals("<=", г?.op, "max даёт направление, значение совпадает")
        assertEquals(900.0, г?.value)
    }

    @Test
    fun `знак в value сильнее max`() {
        val г = граница(mapper.createObjectNode().put("unit", "шт").put("value", "≤ 4").put("max", "10"))
        assertEquals("<=", г?.op, "явный знак в значении — его мнение главнее")
        assertEquals(4.0, г?.value)
    }

    @Test
    fun `голое value без знака остаётся точкой`() {
        val г = граница(mapper.createObjectNode().put("unit", "s").put("value", "900"))
        assertEquals(null, г?.op, "без op, min и max направления нет — точка, как раньше")
        assertEquals(900.0, г?.value)
    }
}
