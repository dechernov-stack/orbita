// Перенос исполняемого эталона spec/ballistics_semantics.py — один в один,
// 39 проверок. Замкнутые формулы, независимые от Orekit; сходимость Orekit
// с ними проверяется отдельно (OrekitConvergenceTest).
package orbita.bal

import orbita.mod.store.ModelViolationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs
import kotlin.math.roundToInt

class BallisticsSemanticsTest {

    @Nested
    @DisplayName("TZ-BAL-001: механика")
    inner class Mechanics {

        @Test
        fun `период на 550 км около 95,6 мин`() =
            assertTrue(abs(orbitalPeriodS(550.0) / 60 - 95.6) < 0.3) { "${orbitalPeriodS(550.0) / 60} мин" }

        @Test
        fun `период растёт с высотой`() =
            assertTrue(orbitalPeriodS(1200.0) > orbitalPeriodS(550.0))

        @Test
        fun `ССО на 700 км около 98,2 градуса`() =
            assertTrue(abs(ssoInclinationDeg(700.0) - 98.2) < 0.2) { "${ssoInclinationDeg(700.0)}°" }

        @Test
        fun `ССО на 550 км около 97,6 градуса`() =
            assertTrue(abs(ssoInclinationDeg(550.0) - 97.6) < 0.2) { "${ssoInclinationDeg(550.0)}°" }

        @Test
        fun `ССО-наклонение растёт с высотой`() =
            assertTrue(ssoInclinationDeg(1000.0) > ssoInclinationDeg(550.0))
    }

    @Nested
    @DisplayName("TZ-BAL-002: геометрия видимости")
    inner class VisibilityGeometry {

        @Test
        fun `сверка геометрии с известной группировкой - 550 км на 25 градусах около 940 км`() =
            assertTrue(abs(footprintRadiusKm(550.0, 25.0) - 940) < 25) { "${footprintRadiusKm(550.0, 25.0)} км" }

        @Test
        fun `центральный угол на 550 км и 10 градусах около 15 градусов`() =
            assertTrue(abs(centralAngleDeg(550.0, 10.0) - 14.96) < 0.3) { "${centralAngleDeg(550.0, 10.0)}°" }

        @Test
        fun `зона сужается с ростом угла места`() =
            assertTrue(centralAngleDeg(550.0, 25.0) < centralAngleDeg(550.0, 10.0))

        @Test
        fun `зона расширяется с высотой`() =
            assertTrue(centralAngleDeg(1200.0, 10.0) > centralAngleDeg(550.0, 10.0))

        @Test
        fun `надирная дальность равна высоте`() =
            assertTrue(abs(slantRangeKm(550.0, 90.0) - 550.0) < 0.5)

        @Test
        fun `дальность у горизонта много больше высоты`() =
            assertTrue(slantRangeKm(550.0, 5.0) > 2 * 550.0)

        @Test
        fun `радиус footprint около 1665 км`() {
            val fp = footprintRadiusKm(550.0, 10.0)
            assertTrue(fp in 1600.0..1750.0) { "$fp км" }
        }

        @Test
        fun `максимальный пролёт 550 км на 10 градусах около 8 минут`() {
            val dur = maxPassDurationS(550.0, 10.0)
            assertTrue(dur in 420.0..540.0) { "${dur / 60} мин" }
        }
    }

    @Nested
    @DisplayName("ADR-013: двухуровневая сетка не теряет пролёты")
    inner class GridApplicability {

        private val coarseKm = 800.0

        @Test
        fun `грубая ячейка меньше диаметра footprint`() =
            assertTrue(coarseKm < 2 * footprintRadiusKm(550.0, 10.0))

        @Test
        fun `условие держится и на минимальной высоте 400 км`() =
            assertTrue(coarseKm < 2 * footprintRadiusKm(400.0, 10.0))

        @Test
        fun `при угле места 45 градусов на 400 км условие нарушается`() =
            assertTrue(coarseKm > 2 * footprintRadiusKm(400.0, 45.0)) {
                "${2 * footprintRadiusKm(400.0, 45.0)} км"
            }

        @Test
        fun `граница применимости лежит между 40 и 45 градусами`() =
            assertTrue(2 * footprintRadiusKm(400.0, 40.0) > coarseKm &&
                coarseKm > 2 * footprintRadiusKm(400.0, 45.0))
    }

    @Nested
    @DisplayName("TZ-BAL-004: энергетика витка")
    inner class Energy {

        private val saM2 = 0.2  // реалистичная площадь СБ малого аппарата класса 12U–16U
        private val eWorst = orbitEnergyWh(550.0, 0.0, saM2, 0.30)
        private val eBest = orbitEnergyWh(550.0, 75.0, saM2, 0.30)

        @Test
        fun `доля тени при beta 0 около 0,38`() =
            assertTrue(abs(eclipseFraction(550.0, 0.0) - 0.38) < 0.03) { "${eclipseFraction(550.0, 0.0)}" }

        @Test
        fun `тень убывает с ростом beta`() =
            assertTrue(eclipseFraction(550.0, 60.0) < eclipseFraction(550.0, 0.0))

        @Test
        fun `терминаторная ССО почти без тени`() =
            assertEquals(0.0, eclipseFraction(550.0, 90.0))

        @Test
        fun `энергия лучшего сезона выше худшего`() =
            assertTrue(eBest > eWorst) { "$eWorst / $eBest Вт·ч" }

        @Test
        fun `энергия худшего витка положительна`() = assertTrue(eWorst > 0)

        @Test
        fun `допустимая скважность больше нуля и не выше единицы`() {
            val d = allowedDutyCycle(eWorst, busW = 15.0, payloadW = 60.0, altKm = 550.0)
            assertTrue(d > 0 && d <= 1.0) { "$d" }
        }

        @Test
        fun `рост потребления шины снижает скважность`() =
            assertTrue(
                allowedDutyCycle(eWorst, 25.0, 60.0, 550.0) < allowedDutyCycle(eWorst, 15.0, 60.0, 550.0)
            )

        @Test
        fun `нехватка энергии даёт нулевую скважность`() =
            assertEquals(0.0, allowedDutyCycle(eWorst, 500.0, 60.0, 550.0))
    }

    @Nested
    @DisplayName("TZ-BAL-003: конфигуратор Walker")
    inner class Walker {

        private val sats = walkerDelta(53.0, 40, 5, 1, 550.0)

        @Test
        fun `40 на 5 даёт 40 аппаратов`() = assertEquals(40, sats.size)

        @Test
        fun `5 плоскостей`() = assertEquals(5, sats.map { it.plane }.toSet().size)

        @Test
        fun `8 аппаратов в плоскости`() = assertEquals(8, sats.count { it.plane == 0 })

        @Test
        fun `ВДУ равномерны по 72 градуса`() =
            assertEquals(listOf(0, 72, 144, 216, 288), sats.map { it.raanDeg.roundToInt() }.toSet().sorted())

        @Test
        fun `фазовый сдвиг F смещает соседнюю плоскость`() =
            assertTrue(abs(sats[8].maDeg - sats[0].maDeg) > 1e-9)

        @Test
        fun `одна пара наклонение-высота равна одной кампании`() =
            assertEquals(1, launchCampaigns(sats))

        @Test
        fun `разнородная группировка требует двух кампаний`() =
            assertEquals(2, launchCampaigns(walkerDelta(53.0, 20, 4, 1, 550.0) + walkerDelta(97.6, 12, 3, 1, 700.0)))

        @Test
        fun `T не делится на P - ошибка`() {
            assertThrows<ModelViolationException> { walkerDelta(53.0, 41, 5, 1, 550.0) }
        }
    }

    @Nested
    @DisplayName("TZ-BAL-009: время существования")
    inner class Decay {

        private val low = decayYears(300.0, 50.0, 0.5)
        private val high = decayYears(700.0, 50.0, 0.5)

        @Test
        fun `на 300 км сходит быстро`() = assertTrue(low < 25) { "$low лет" }

        @Test
        fun `на 700 км держится дольше`() = assertTrue(high > low) { "$high лет" }

        @Test
        fun `большая площадь ускоряет сход`() =
            assertTrue(decayYears(500.0, 50.0, 2.0) < decayYears(500.0, 50.0, 0.5))

        @Test
        fun `норма 25 лет - 700 км без ДУ не проходит`() = assertTrue(high > 25)
    }

    @Nested
    @DisplayName("TZ-BAL-005: качество по зонам обслуживания")
    inner class Score {

        private val cells = listOf(
            WeightedCell(45.0, 0.6), WeightedCell(75.0, 0.05), WeightedCell(15.0, 0.35),
        )
        private val zoneWide = { _: WeightedCell -> 1.0 }                                  // зона A'
        private val zoneNarrow = { c: WeightedCell -> if (abs(c.lat) < 50) 1.0 else 0.0 }  // зона C'

        @Test
        fun `качество по широкой зоне выше`() =
            assertTrue(demandWeightedScore(cells, zoneWide) > demandWeightedScore(cells, zoneNarrow))

        @Test
        fun `узкая зона теряет ровно вес непокрытых ячеек`() =
            assertTrue(abs(demandWeightedScore(cells, zoneNarrow) - 0.95) < 1e-9) {
                "${demandWeightedScore(cells, zoneNarrow)}"
            }
    }
}
