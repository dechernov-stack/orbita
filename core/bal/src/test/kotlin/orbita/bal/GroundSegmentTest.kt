// Наземный сегмент: рекомендательное размещение (шаг 12.1).
// Перенос эталона spec/ground_segment_semantics.py один в один: 22 проверки.
package orbita.bal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.abs

class GroundSegmentTest {

    private val inc = 53.0
    private val alt = 550.0

    private val candidates = listOf(
        StationSite("GS-A", "Мурманск", 68.9, 33.1),
        StationSite("GS-B", "Москва", 55.7, 37.6),
        StationSite("GS-C", "Химки", 55.9, 37.4),
        StationSite("GS-D", "Владивосток", 43.1, 131.9),
        StationSite("GS-E", "Кито", -0.2, -78.5),
    )
    private val moscow = candidates[1]
    private val khimki = candidates[2]
    private val vladivostok = candidates[3]

    @Nested
    @DisplayName("Видимость и широта")
    inner class Visibility {

        @Test
        fun `станция в полосе наклонения видит аппарат`() =
            assertTrue(visibleFraction(53.0, inc, alt) > 0)

        @Test
        fun `станция на полюсе наклонную орбиту не видит`() =
            assertEquals(0.0, visibleFraction(89.0, inc, alt))

        @Test
        fun `широта у наклонения выгоднее экватора`() =
            assertTrue(visibleFraction(53.0, inc, alt) > visibleFraction(0.0, inc, alt))

        @Test
        fun `для полярной орбиты полюс доступен`() =
            assertTrue(visibleFraction(85.0, 97.6, alt) > 0)

        @Test
        fun `выше орбита — шире полоса доступности`() =
            assertTrue(visibleFraction(70.0, inc, 1200.0) >= visibleFraction(70.0, inc, alt))
    }

    @Nested
    @DisplayName("Монотонность и насыщение")
    inner class Monotonicity {

        private val one = coverage(listOf(moscow), inc, alt)
        private val far = coverage(listOf(moscow, vladivostok), inc, alt)
        private val near = coverage(listOf(moscow, khimki), inc, alt)

        @Test
        fun `добавление станции не ухудшает покрытие`() =
            assertTrue(far >= one) { "$one → $far" }

        /** Насыщение: сосед добавляет заметно меньше, чем удалённая станция. */
        @Test
        fun `станция рядом с существующей добавляет мало`() =
            assertTrue(near - one < (far - one) / 2) {
                "рядом +${near - one}, далеко +${far - one}"
            }

        @Test
        fun `совпадающая станция не добавляет ничего`() =
            assertTrue(abs(coverage(listOf(moscow, moscow.copy()), inc, alt) - one) < 1e-9)

        @Test
        fun `покрытие не превышает единицу`() =
            assertTrue(coverage(candidates + candidates + candidates, inc, alt) <= 1.0)

        @Test
        fun `перекрытие соседних зон близко к единице`() =
            assertTrue(stationOverlap(moscow, khimki, alt) > 0.9)

        @Test
        fun `перекрытие удалённых зон нулевое`() =
            assertEquals(0.0, stationOverlap(moscow, vladivostok, alt))
    }

    @Nested
    @DisplayName("Жадный подбор")
    inner class Greedy {

        @Test
        fun `подобрано запрошенное число станций`() =
            assertEquals(2, suggestStations(candidates, inc, alt, k = 2).suggested.size)

        @Test
        fun `предложения помечены происхождением`() =
            assertTrue(suggestStations(candidates, inc, alt, k = 2).suggested.all { it.placement == "suggested" })

        @Test
        fun `первым выбран наибольший прирост`() {
            val (suggested, _) = suggestStations(candidates, inc, alt, k = 2)
            assertTrue(
                stationGain(emptyList(), suggested[0], inc, alt) >=
                    stationGain(emptyList(), suggested[1], inc, alt),
            )
        }

        @Test
        fun `подбор не дублирует близкие точки`() {
            val (suggested, _) = suggestStations(candidates, inc, alt, k = 2)
            assertTrue(stationOverlap(suggested[0], suggested[1], alt) < 0.5)
        }

        /** Ручное размещение не переписывается предложениями. */
        @Test
        fun `ручная станция сохраняется в наборе`() {
            val result = suggestStations(candidates, inc, alt, k = 2, existing = listOf(moscow))
            assertTrue(result.placed.any { it.placement == "manual" })
        }

        @Test
        fun `ручная станция не предлагается повторно`() {
            val result = suggestStations(candidates, inc, alt, k = 2, existing = listOf(moscow))
            assertTrue(result.suggested.none { it.lat == moscow.lat && it.lon == moscow.lon })
        }

        @Test
        fun `подбор поверх ручной даёт покрытие не ниже, чем без неё`() {
            val result = suggestStations(candidates, inc, alt, k = 2, existing = listOf(moscow))
            assertTrue(coverage(result.placed, inc, alt) >= coverage(listOf(moscow), inc, alt))
        }

        @Test
        fun `подбор не выдумывает станций сверх кандидатов`() =
            assertEquals(1, suggestStations(listOf(moscow), inc, alt, k = 5).suggested.size)
    }

    @Nested
    @DisplayName("Задержка до сброса")
    inner class Latency {

        @Test
        fun `больше станций — меньше время до сброса`() {
            val one = meanTimeToDownlinkS(listOf(moscow), inc, alt)
            val two = meanTimeToDownlinkS(listOf(moscow, vladivostok), inc, alt)
            assertTrue(two < one) { "$one → $two" }
        }

        @Test
        fun `без станций сброс невозможен`() =
            assertEquals(Double.POSITIVE_INFINITY, meanTimeToDownlinkS(emptyList(), inc, alt))

        @Test
        fun `станция вне полосы не сокращает задержку`() =
            assertEquals(
                Double.POSITIVE_INFINITY,
                meanTimeToDownlinkS(listOf(StationSite("GS-X", "Полюс", 89.0, 0.0)), inc, alt),
            )
    }
}
