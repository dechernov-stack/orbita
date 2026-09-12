// Приведение к канону: сравнивать можно только приведённое.
//
// Отказные здесь важнее положительных: несравнимое обязано называться
// несравнимым с причиной, а не превращаться в противоречие — спор на пустом
// месте стоит человеку времени и доверия к сверке.
package orbita.knowledge

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.IntNode
import com.fasterxml.jackson.databind.node.TextNode
import orbita.knowledge.internal.Difference
import orbita.knowledge.internal.ЕдиницаКанона
import orbita.knowledge.internal.Normalize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NormalizeTest {

    private val mapper = ObjectMapper()

    /**
     * Справочник единиц, как его отдаёт вид `unit`: ключ — написание,
     * значение — правило приведения. На стенде эти записи приходят
     * справочником, здесь — списком, чтобы тест не поднимал базу.
     */
    private val нормализация = Normalize(
        mapOf(
            "с" to ЕдиницаКанона("с", "время", true, 1.0, "linear"),
            "мин" to ЕдиницаКанона("мин", "время", false, 60.0, "linear"),
            "ч" to ЕдиницаКанона("ч", "время", false, 3600.0, "linear"),
            "кг" to ЕдиницаКанона("кг", "масса", true, 1.0, "linear"),
        ),
        mapOf("mcr" to 2030),
    )

    private fun величина(показатель: String, оператор: String, значение: Double, единица: String) =
        mapper.createObjectNode()
            .put("key", показатель).put("op", оператор).put("value", значение).put("unit", единица)

    @Test
    fun `сто восемьдесят минут и три часа — одна и та же величина`() {
        val отличие = нормализация.сравнить(
            "measure",
            величина("p95_latency", "<=", 180.0, "мин"),
            величина("p95_latency", "<=", 3.0, "ч"),
        )
        assertEquals(Difference.EQUAL, отличие.kind, "приведение к канону снимает разницу написаний")
    }

    @Test
    fun `разные значения одного показателя расходятся, и оба названы`() {
        val отличие = нормализация.сравнить(
            "measure",
            величина("p95_latency", "<=", 180.0, "мин"),
            величина("p95_latency", "<=", 4.0, "ч"),
        )
        assertEquals(Difference.DIFFERS, отличие.kind)
        assertTrue(отличие.mine.contains("180") && отличие.theirs.contains("4"), "оба значения остаются видны")
    }

    @Test
    fun `год ворот плана сводится к году цели`() {
        val отличие = нормализация.сравнитьГоризонт("year", IntNode.valueOf(2030), TextNode.valueOf("к MCR"))
        assertEquals(Difference.EQUAL, отличие.kind, "горизонт назван воротами плана, а не числом из текста")
    }

    @Test
    fun `разные годы расходятся, и победитель не выбирается`() {
        val отличие = нормализация.сравнитьГоризонт("year", IntNode.valueOf(2030), IntNode.valueOf(2032))
        assertEquals(Difference.DIFFERS, отличие.kind)
        assertEquals("2030", отличие.mine)
        assertEquals("2032", отличие.theirs)
    }

    // --- отказные -------------------------------------------------------

    @Test
    fun `P95 против среднего — несравнимое с причиной, а не противоречие`() {
        val отличие = нормализация.сравнить(
            "measure",
            величина("p95_latency", "<=", 180.0, "мин"),
            величина("avg_latency", "<=", 180.0, "мин"),
        )
        assertEquals(Difference.INCOMPARABLE, отличие.kind, "два разных показателя друг с другом не спорят")
        assertTrue(отличие.reason.contains("разные показатели"), "причина названа словами")
    }

    @Test
    fun `число из свободного текста не разбирается`() {
        val отличие = нормализация.сравнить(
            "statement",
            TextNode.valueOf("задержка не менее 180 минут"),
            TextNode.valueOf("задержка не менее 3 часов"),
        )
        assertEquals(Difference.INCOMPARABLE, отличие.kind)
        assertTrue(отличие.reason.contains("thresholds_rule"), "порог живёт полем, а не строкой")
    }

    @Test
    fun `единица вне справочника к канону не приводится`() {
        val отличие = нормализация.сравнить(
            "measure",
            величина("autonomy", ">=", 3.0, "года"),
            величина("autonomy", ">=", 36.0, "мес"),
        )
        assertEquals(Difference.INCOMPARABLE, отличие.kind)
        assertTrue(отличие.reason.contains("справочник"), "запись добавляется в справочник единиц, а не в код")
    }

    @Test
    fun `величины разных размерностей несравнимы`() {
        val отличие = нормализация.сравнить(
            "measure",
            величина("autonomy", ">=", 180.0, "мин"),
            величина("autonomy", ">=", 180.0, "кг"),
        )
        assertEquals(Difference.INCOMPARABLE, отличие.kind)
        assertTrue(отличие.reason.contains("размерност"), "причина называет обе размерности")
    }

    @Test
    fun `срок от старта словами годом не становится`() {
        val отличие = нормализация.сравнитьГоризонт(
            "year",
            IntNode.valueOf(2030),
            TextNode.valueOf("этап 1, 1,5–3 года от старта 2027"),
        )
        assertEquals(Difference.INCOMPARABLE, отличие.kind, "числа из текста горизонтом не становятся")
        assertTrue(отличие.reason.contains("plan.gate_dates"), "сказано, что делать — назвать ворота плана")
    }
}
