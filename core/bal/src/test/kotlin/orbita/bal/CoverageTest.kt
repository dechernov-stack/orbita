// Метрики покрытия ячейки — один в один с spec/ballistics_semantics.py
// (TZ-BAL-006, шаг 16 §2.2). Расхождение с эталоном — дефект реализации.
package orbita.bal

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoverageTest {

    private val mapper = ObjectMapper()

    @Test
    fun `перекрывающиеся окна двух КА не считаются дважды`() {
        val m = coverageMetrics(listOf(0.0 to 100.0, 50.0 to 200.0, 400.0 to 500.0), 1000.0)
        assertEquals(0.3, m.availability, 1e-12)
    }

    @Test
    fun `разрыв — между концом и началом, повторный обзор — между началами`() {
        val m = coverageMetrics(listOf(0.0 to 100.0, 50.0 to 200.0, 400.0 to 500.0), 1000.0)
        assertEquals(200.0, m.maxGapS)
        assertEquals(400.0, m.revisitS)
    }

    @Test
    fun `краевые интервалы в статистику разрывов не входят`() {
        assertNull(coverageMetrics(listOf(400.0 to 500.0), 1000.0).maxGapS)
    }

    private fun visibility(): com.fasterxml.jackson.databind.node.ObjectNode {
        val root = mapper.createObjectNode()
        val passes = root.putArray("passes")
        passes.addObject().put("target_ref", "c1").put("start_s", 0.0).put("end_s", 100.0)
            .put("in_service_zone", true)
        passes.addObject().put("target_ref", "c1").put("start_s", 300.0).put("end_s", 400.0)
            .put("in_service_zone", false)
        return root
    }

    @Test
    fun `непокрытая цель остаётся в выдаче с нулевой доступностью`() {
        val bt = coverageByTarget(visibility(), 1000.0, targets = listOf("c1", "c2"))
        assertEquals(0.0, bt.getValue("c2").availability)
        assertEquals(0, bt.getValue("c2").accessWindows)
    }

    @Test
    fun `фильтр зоны обслуживания отбрасывает пролёт вне её`() {
        val all = coverageByTarget(visibility(), 1000.0)
        val sz = coverageByTarget(visibility(), 1000.0, serviceZoneOnly = true)
        assertEquals(2, all.getValue("c1").accessWindows)
        assertEquals(1, sz.getValue("c1").accessWindows)
    }

    @Test
    fun `почасовая серия — доля покрытия часа, неполный час отбрасывается`() {
        assertEquals(
            listOf(1.0, 0.5, 0.0),
            hourlySeries(listOf(0.0 to 3600.0, 5400.0 to 7200.0), 3 * 3600.0),
        )
        assertEquals(1, hourlySeries(listOf(0.0 to 3600.0), 5400.0).size)
    }

    @Test
    fun `класс ячейки считается по правилу сервера`() {
        assertEquals(CoverageClass.Gap, coverageClass(0.4, 0.0))
        assertEquals(CoverageClass.Degraded, coverageClass(0.6, 0.2))
        assertEquals(CoverageClass.Ok, coverageClass(0.6, 0.5))
        // пустая ячейка — gap, а не деление на ноль
        assertEquals(CoverageClass.Gap, coverageClass(0.0, 0.0))
    }

    @Test
    fun `метрики требуют положительной длительности`() {
        assertTrue(runCatching { coverageMetrics(emptyList(), 0.0) }.isFailure)
    }
}
