// §7 ЗАДАЧА-CODE-ПОСТРОЕНИЕ3: инварианты углов Walker — диагноз дефекта
// «плоскости не растянуты на полный круг» фактической геометрией.
// Разброс RAAN delta ≈ 360·(P−1)/P; соседние плоскости равноотстоят;
// ССО(550) ≈ 97.6°; ВОСХОДЯЩИЕ УЗЛЫ ТРАСС — P равноотстоящих долгот
// (мера, видимая глазами на карте §6).
package orbita.bal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class WalkerAngleInvariantsTest {

    @Test
    fun `разброс RAAN - полный круг у delta, полукруг у star, соседи равноотстоят`() {
        val delta = walkerDelta(52.0, 8, 4, 1, 600.0)
        val deltaRaans = delta.map { it.raanDeg }.distinct().sorted()
        assertEquals(listOf(0.0, 90.0, 180.0, 270.0), deltaRaans)
        assertEquals(360.0 * 3 / 4, deltaRaans.last() - deltaRaans.first())

        val star = walkerStar(86.0, 8, 4, 0, 780.0)
        val starRaans = star.map { it.raanDeg }.distinct().sorted()
        assertEquals(listOf(0.0, 45.0, 90.0, 135.0), starRaans)
    }

    @Test
    fun `ССО 550 км - 97,6 градуса по справочнику`() {
        assertTrue(abs(ssoInclinationDeg(550.0) - 97.6) < 0.05) {
            "550 км: ${ssoInclinationDeg(550.0)}"
        }
    }

    @Test
    fun `восходящие узлы трасс - P равноотстоящих долгот на экваторе`() {
        // геометрическая мера дефекта §7: если бы 2π/P легло градусами,
        // узлы сбились бы в сектор ~6° — здесь они обязаны разойтись на 90°
        val slots = walkerDelta(52.0, 4, 4, 0, 600.0)
        val vis = VisibilityPrecompute()
        val orbitS = orbitalPeriodS(600.0)
        // 1.2 витка: узел ловится у всех, даже стартовавших на экваторе;
        // долгота приводится К ИНЕРЦИАЛЬНОЙ (+ωE·t) — вращение Земли за
        // разное время до узла не искажает взаимных расстояний RAAN
        val omegaEDegS = 360.0 / 86164.0905
        val tracks = vis.groundTracksSlots(
            slots, "2026-03-20T00:00:00.000Z", orbitS * 1.2, stepS = 10.0,
        )
        val nodes = tracks.values.map { pts ->
            var node: Double? = null
            for (i in 1 until pts.size) {
                val (_, lat0, _) = pts[i - 1]
                val (t1, lat1, lon1) = pts[i]
                if (lat0 < 0 && lat1 >= 0) {
                    node = ((lon1 + omegaEDegS * t1) + 720.0).mod(360.0)
                    break
                }
            }
            node
        }.filterNotNull().sorted()
        assertEquals(4, nodes.size) { "узлы: $nodes" }
        // соседние узлы равноотстоят на ~90° (вращение Земли за виток даёт
        // общий сдвиг, но НЕ меняет взаимных расстояний)
        val gaps = (nodes + (nodes.first() + 360.0)).zipWithNext { a, b -> b - a }
        gaps.forEach { g ->
            assertTrue(abs(g - 90.0) < 3.0) { "узлы не равноотстоят: $nodes (шаги $gaps)" }
        }
    }
}
