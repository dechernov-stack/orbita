// Перенос spec/flows_semantics.py в тесты, один в один: 38 проверок.
// Эталон — поведенческий стандарт (CLAUDE.md §4): расхождение здесь означает
// дефект реализации, а не повод поправить ожидание.
package orbita.flw

import orbita.mod.store.ModelViolationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs

class FlowsSemanticsTest {

    @Nested
    @DisplayName("TZ-FLW-003: потоки")
    inner class Streams {

        @Test
        fun `суперпозиция складывает интенсивности`() {
            assertEquals(4.0, superpose(listOf(1.5, 2.0, 0.5)))
        }

        @Test
        fun `распределение нормировано`() {
            val sum = (0 until 60).sumOf { poissonPmf(it, 3.0) }
            assertTrue(abs(sum - 1.0) < 1e-9, "$sum")
        }

        @Test
        fun `мода распределения около интенсивности`() {
            val mode = (0 until 20).maxByOrNull { poissonPmf(it, 5.0) }
            assertTrue(mode in listOf(4, 5), "$mode")
        }

        @Test
        fun `всплеск повышает интенсивность`() {
            assertEquals(500.0, mmppRate(10.0, 50.0, inBurst = true))
        }

        @Test
        fun `вне всплеска интенсивность фоновая`() {
            assertEquals(10.0, mmppRate(10.0, 50.0, inBurst = false))
        }
    }

    @Nested
    @DisplayName("TZ-FLW-004: коллизии и лавина повторов")
    inner class Collisions {

        private val peak = alohaThroughput(0.5)
        private val hi = offeredWithRetries(100.0, capacity = 400.0)
        private val lo = offeredWithRetries(100.0, capacity = 200.0)
        private val cr = offeredWithRetries(100.0, capacity = 100.0)

        @Test
        fun `максимум чистой ALOHA около 0,184 при нагрузке 0,5`() {
            assertTrue(abs(peak - 0.1839) < 0.001, "$peak")
        }

        @Test
        fun `перегрузка снижает пропускную способность`() {
            assertTrue(alohaThroughput(2.0) < peak)
        }

        @Test
        fun `максимум достигается именно при G=0,5`() {
            listOf(0.1, 0.3, 0.7, 1.0, 1.5, 3.0).forEach { g ->
                assertTrue(alohaThroughput(g) <= peak + 1e-12, "G=$g")
            }
        }

        @Test
        fun `захват сильного сигнала повышает пропускную способность`() {
            assertTrue(alohaThroughput(0.5, capture = true) > peak)
        }

        @Test
        fun `предложенная нагрузка больше обслуженной`() {
            assertTrue(hi.offered > hi.delivered, "${hi.offered}/${hi.delivered}")
        }

        @Test
        fun `падение ёмкости вдвое увеличивает долю повторов нелинейно`() {
            assertTrue(
                lo.retransmissionRatio > hi.retransmissionRatio * 1.2,
                "${lo.retransmissionRatio} vs ${hi.retransmissionRatio}",
            )
        }

        // Ловушка 1: до порога повторы КОМПЕНСИРУЮТ деградацию канала —
        // качество кажется прежним, растут только повторы.
        @Test
        fun `до порога повторы скрывают деградацию доставка не падает`() {
            assertTrue(abs(lo.delivered - hi.delivered) < 1e-6, "${hi.delivered} vs ${lo.delivered}")
        }

        @Test
        fun `за порогом доставка обваливается`() {
            assertTrue(cr.delivered < 0.5 * hi.delivered, "${cr.delivered} из ${hi.delivered}")
        }

        @Test
        fun `обвал нелинеен вдвое меньше ёмкости — впятеро больше повторов`() {
            assertTrue(
                cr.retransmissionRatio > 5 * lo.retransmissionRatio,
                "${cr.retransmissionRatio} vs ${lo.retransmissionRatio}",
            )
        }
    }

    @Nested
    @DisplayName("TZ-FLW-005: эфемеридный backoff")
    inner class Backoff {

        @Test
        fun `ожидание следующего аппарата повышает доставку`() {
            val p1 = deliveryWithBackoff(0.5, attemptsPerPass = 4, passes = 1)
            val p3 = deliveryWithBackoff(0.5, attemptsPerPass = 4, passes = 3)
            assertTrue(p3 > p1, "$p1 → $p3")
        }

        @Test
        fun `хвост задержки удлиняется на интервал пролётов`() {
            assertEquals(1200.0, latencyTailS(600.0, 3))
        }

        @Test
        fun `один пролёт — хвоста нет`() {
            assertEquals(0.0, latencyTailS(600.0, 1))
        }

        @Test
        fun `больше КА в плоскости — короче хвост при той же надёжности`() {
            assertTrue(latencyTailS(300.0, 3) < latencyTailS(600.0, 3))
        }
    }

    @Nested
    @DisplayName("TZ-FLW-006: бюджет времени реакции C'")
    inner class ReactionBudget {

        private val parts = mapOf(
            "detection" to 2.0, "uplink_wait" to 45.0, "uplink_transit" to 1.0,
            "external_decision" to 20.0, "downlink_wait" to 30.0,
            "downlink_transit" to 1.0, "execution" to 3.0,
        )

        @Test
        fun `бюджет складывается по всем участкам`() {
            assertEquals(102.0, reactionTimeS(parts))
        }

        @Test
        fun `неполный бюджет отклонён`() {
            assertThrows<ModelViolationException> {
                reactionTimeS(parts.filterKeys { it != "external_decision" })
            }
        }

        @Test
        fun `store-and-forward выводит контур за 120 с`() {
            val sf = parts + ("uplink_wait" to 1800.0)   // ждём пролёта
            assertTrue(reactionTimeS(sf) > 120, "${reactionTimeS(sf)}")
        }

        @Test
        fun `ISL укладывает контур в 120 с`() {
            assertTrue(reactionTimeS(parts) <= 120)
        }

        @Test
        fun `вероятность уложиться считается по выборке`() {
            val samples = listOf(90.0, 100.0, 110.0, 130.0, 200.0)
            assertTrue(abs(pWithin(samples, 120.0) - 0.6) < 1e-9)
        }

        @Test
        fun `время решения внешней системы входит в бюджет и не моделируется`() {
            assertTrue("external_decision" in BUDGET_PARTS)
        }
    }

    @Nested
    @DisplayName("TZ-FLW-002: воспроизводимость при параллельности")
    inner class Reproducibility {

        private val a = (0 until 3).flatMap { r -> (0 until 4).map { e -> CounterRng.uniform(42, r, e) } }
        private val b = (0 until 4).flatMap { e -> (0 until 3).map { r -> CounterRng.uniform(42, r, e) } }

        @Test
        fun `поток адресуется ключом порядок обхода не влияет`() {
            assertEquals(a.sorted(), b.sorted())
        }

        @Test
        fun `тот же ключ даёт то же число`() {
            assertEquals(CounterRng.uniform(42, 1, 2), CounterRng.uniform(42, 1, 2))
        }

        @Test
        fun `другое зерно даёт другой поток`() {
            assertTrue(CounterRng.uniform(43, 1, 2) != CounterRng.uniform(42, 1, 2))
        }

        @Test
        fun `значения в допустимом диапазоне`() {
            assertTrue(a.all { it >= 0.0 && it < 1.0 })
        }

        @Test
        fun `разные сущности одной реализации независимы`() {
            assertEquals(50, (0 until 50).map { CounterRng.uniform(42, 0, it) }.toSet().size)
        }
    }

    @Nested
    @DisplayName("TZ-FLW-001: выборка по представителям")
    inner class Representatives {

        private val values = (0 until 1000).map { 0.5 + 0.1 * CounterRng.uniform(7, it, 0) }
        private val se100 = mcStdErr(values.take(100))
        private val se1000 = mcStdErr(values)

        @Test
        fun `оценка взвешена по численности а не по числу представителей`() {
            val est = weightedEstimate(
                listOf(Representative(1000.0, 0.9), Representative(50.0, 0.4), Representative(10.0, 0.1)),
            )
            assertTrue(abs(est - 0.8698) < 0.001, "$est")
        }

        @Test
        fun `равные веса дают среднее`() {
            val est = weightedEstimate(listOf(Representative(1.0, 0.2), Representative(1.0, 0.8)))
            assertTrue(abs(est - 0.5) < 1e-9)
        }

        @Test
        fun `погрешность убывает как 1 на корень из N`() {
            assertTrue(se1000 < se100, "$se100 → $se1000")
        }

        @Test
        fun `отношение погрешностей близко к корню из 10`() {
            assertTrue(se100 / se1000 in 2.0..5.0, "${se100 / se1000}")
        }
    }

    @Nested
    @DisplayName("TZ-FLW-007 / 008: узкие места и деградация")
    inner class BottlenecksAndDegradation {

        private val degraded = degradedShare(200_000.0, 60.0, 86_400.0)

        @Test
        fun `узкое место определено`() {
            val loads = mapOf(
                "user_uplink" to 0.42, "onboard_buffer" to 0.91,
                "isl" to 0.30, "feeder_downlink" to 0.55,
            )
            val (name, value) = bottleneck(loads)
            assertEquals("onboard_buffer", name)
            assertEquals(0.91, value)
        }

        @Test
        fun `нет деградации при частых пролётах`() {
            assertEquals(0.0, degradedShare(900.0, 60.0, 86_400.0))
        }

        @Test
        fun `редкие пролёты дают деградацию`() {
            assertTrue(degraded > 0.0 && degraded < 1.0, "$degraded")
        }

        @Test
        fun `реже пролёты — больше деградация`() {
            assertTrue(degradedShare(400_000.0, 60.0, 86_400.0) > degraded)
        }

        @Test
        fun `редкий маяк деградирует даже при частых пролётах`() {
            assertTrue(degradedShare(900.0, 200_000.0, 86_400.0) > 0)
        }
    }
}
