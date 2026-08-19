// Перенос исполняемого эталона spec/demand_semantics.py — один в один,
// 16 проверок. Ключевая ловушка — проекция: равноугольная сетка завышает вес
// высоких широт; вес ячейки обязан быть пропорционален площади (STEP-2 №1).
package orbita.usr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.floor

class DemandSemanticsTest {

    @Nested
    @DisplayName("TZ-USR-004: равноплощадность (ловушка проекции)")
    inner class EqualArea {

        private val a0 = cellAreaKm2(0.0)
        private val a60 = cellAreaKm2(60.0)
        private val a80 = cellAreaKm2(80.0)

        @Test
        fun `площадь ячейки убывает к полюсу`() =
            assertTrue(a0 > a60 && a60 > a80) { "$a0 $a60 $a80" }

        @Test
        fun `ячейка на 60 градусах примерно вдвое меньше экваториальной`() =
            assertTrue(abs(a60 / a0 - 0.5) < 0.02) { "${a60 / a0}" }

        // при ОДИНАКОВОЙ плотности населения на км² вес ячейки обязан быть пропорционален площади
        private val uniform = listOf(
            PopulationCell("c0", 0.0, 10.0, 0.1, 4.0, "A_prime"),
            PopulationCell("c60", 60.0, 10.0, 0.1, 4.0, "A_prime"),
        )
        private val m = DemandMapBuilder.build(uniform)

        @Test
        fun `равная плотность — вес по площади, а не по числу ячеек`() =
            assertTrue(abs(m.getValue("c60").weight / m.getValue("c0").weight - a60 / a0) < 0.01) {
                "${m.getValue("c60").weight / m.getValue("c0").weight} vs ${a60 / a0}"
            }

        @Test
        fun `веса нормированы`() =
            assertTrue(abs(m.values.sumOf { it.weight } - 1.0) < 1e-9)
    }

    // реалистичный слой: несколько населённых ячеек средних широт + северный единичный объект
    private val pop = listOf(30 to 40.0, 40 to 55.0, 45 to 50.0, 50 to 35.0, 55 to 20.0)
        .map { (lat, d) -> PopulationCell("agro$lat", lat.toDouble(), d, 0.02, 4.0, "A_prime") }
    private val pts = listOf(SeedObject("smp", 75.0, 400.0, 12.0, "C_prime"))
    private val m2 = DemandMapBuilder.build(pop, pointObjects = pts)

    @Nested
    @DisplayName("TZ-USR-004: слои спроса")
    inner class Layers {

        @Test
        fun `слой населения создаёт ячейки`() =
            assertTrue(listOf(30, 40, 45, 50, 55).all { "agro$it" in m2 })

        @Test
        fun `единичные объекты добавляют ячейку вне населения`() =
            assertTrue("smp" in m2)

        @Test
        fun `вес северной ячейки мал, но не нулевой`() {
            val w = m2.getValue("smp").weight
            assertTrue(w > 0 && w < 0.05) { "$w" }
        }

        @Test
        fun `карта строится при наличии только слоя населения`() {
            val m3 = DemandMapBuilder.build(pop)
            assertEquals(5, m3.size)
            assertTrue(abs(m3.values.sumOf { it.weight } - 1.0) < 1e-9)
        }
    }

    @Nested
    @DisplayName("TZ-USR-005: профили активности")
    inner class Activity {

        private val cell = m2.getValue("agro45")
        private val diurnal = List(6) { 0.3 } + List(12) { 1.5 } + List(6) { 0.6 }
        private val seasonal = listOf(1.4, 1.3, 1.0, 0.8, 0.7, 0.6, 0.6, 0.7, 0.9, 1.1, 1.3, 1.5)

        @Test
        fun `худший час в дневном окне`() {
            val (h, _) = worstCaseHourMonth(cell, diurnal, seasonal)
            assertTrue(h in 6 until 18) { "$h" }
        }

        @Test
        fun `худший месяц зимний`() {
            val (_, mo) = worstCaseHourMonth(cell, diurnal, seasonal)
            assertTrue(mo == 0 || mo == 11) { "$mo" }
        }

        @Test
        fun `отсутствие профиля равносильно равномерности`() =
            assertTrue(abs(intensityAt(cell, 3, 5) - cell.totalMsgsPerDay() / 24) < 1e-9)
    }

    @Nested
    @DisplayName("TZ-USR-007: спрос-взвешенное качество (эффект ССО)")
    inner class WeightedQuality {

        // Население: средние широты; крайний север почти пуст.
        private val cells = DemandMapBuilder.build(
            listOf(15 to 20.0, 30 to 40.0, 45 to 50.0, 60 to 8.0, 75 to 0.2)
                .map { (lat, d) -> PopulationCell("c$lat", lat.toDouble(), d, 0.02, 4.0, "A_prime") }
        )
        private val polar = { c: DemandCell -> if (abs(c.lat) >= 60) 1.0 else 0.35 }   // ССО: отлично у полюсов
        private val midlat = { c: DemandCell -> if (abs(c.lat) < 60) 0.9 else 0.4 }    // наклонное построение

        @Test
        fun `ССО-построение не выигрывает при населённой карте спроса`() {
            val qPolar = demandWeightedQuality(cells, polar)
            val qMid = demandWeightedQuality(cells, midlat)
            assertTrue(qMid > qPolar) { "ССО=$qPolar, наклонное=$qMid" }
        }

        @Test
        fun `ячейка с нулевым спросом не влияет на интеграл`() {
            val zero = cells.mapValues { (id, c) -> if (id == "c75") c.copy(weight = 0.0) else c }
            val expected = zero.values.filter { it.weight > 0 }.sumOf { it.weight * polar(it) }
            assertTrue(abs(demandWeightedQuality(zero, polar) - expected) < 1e-12)
        }

        @Test
        fun `широтный профиль покрывает все пояса`() =
            assertEquals(
                cells.values.map { (floor(it.lat / 15) * 15).toInt() }.toSet().size,
                latitudeProfile(cells, midlat).size,
            )

        @Test
        fun `сумма весов поясов равна единице`() =
            assertTrue(abs(latitudeProfile(cells, midlat).sumOf { it.weight } - 1.0) < 1e-9)

        @Test
        fun `вес пояса 75 градусов мал`() =
            assertTrue(latitudeProfile(cells, polar).first { it.band == 75 }.weight < 0.01)
    }
}
