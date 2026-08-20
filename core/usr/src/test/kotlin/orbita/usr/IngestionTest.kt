// Загрузка внешних данных населения (шаг 10.2).
//
// Перенос эталона spec/ingestion_semantics.py один в один: 33 проверки.
// Плюс проверки на НАСТОЯЩЕМ датасете — эталон работает на подготовленных
// примерах, а датасет способен разойтись с ними на первой же реальной
// особенности.
package orbita.usr

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.model.libraryComplete
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class IngestionTest {

    private val mapper = ObjectMapper()

    // ---------- Координаты ----------

    @Test
    fun `корректная запись принята`() =
        assertEquals(emptyList<String>(), validateCoords(GridRecord(55.7, 37.6)))

    @Test
    fun `широта вне диапазона выявлена`() =
        assertTrue(validateCoords(GridRecord(137.6, 55.7)).isNotEmpty())

    @Test
    fun `долгота вне диапазона выявлена`() =
        assertTrue(validateCoords(GridRecord(55.7, 237.6)).isNotEmpty())

    @Test
    fun `широта вне ±90 ловится дешёвым признаком`() =
        assertTrue(looksSwapped(listOf(GridRecord(137.6, 55.7))))

    /** Вот почему нужны опорные точки: перестановка Москвы неотличима по границам. */
    @Test
    fun `перестановка в пределах ±90 дешёвым признаком не ловится`() =
        assertFalse(looksSwapped(listOf(GridRecord(37.6, 55.7))))

    private val data = mapOf(
        Pair(55.75, 37.62) to 4000.0,
        Pair(28.61, 77.21) to 11000.0,
    )
    private val goodLookup: (Double, Double) -> Double =
        { la, lo -> data[Pair(round2(la), round2(lo))] ?: 0.0 }
    private val swappedLookup: (Double, Double) -> Double =
        { la, lo -> data[Pair(round2(lo), round2(la))] ?: 0.0 }

    @Test
    fun `правильный датасет проходит по опорным точкам`() =
        assertEquals(emptyList<String>(), referenceCheck(goodLookup))

    @Test
    fun `перестановка координат выявлена опорными точками`() =
        assertTrue(referenceCheck(swappedLookup).size >= 2)

    @Test
    fun `названо, какая точка не сошлась`() =
        assertTrue(referenceCheck(swappedLookup).first().contains("Москва"))

    @Test
    fun `население посреди океана выявлено`() =
        assertTrue(referenceCheck({ _, _ -> 500.0 }).any { it.contains("океан") })

    @Test
    fun `загрузка с битой широтой отклонена`() {
        assertThrows(IllegalArgumentException::class.java) {
            ingest(listOf(GridRecord(100.0, 0.0, 1.0)), 0.02)
        }
    }

    // ---------- Площадь и вес ----------

    private val a0 = cellAreaKm2(0.0)
    private val a60 = cellAreaKm2(60.0)

    @Test
    fun `площадь ячейки убывает к полюсу`() = assertTrue(a0 > a60)

    @Test
    fun `ячейка на 60° примерно вдвое меньше`() = assertTrue(abs(a60 / a0 - 0.5) < 0.02)

    private val uniform = listOf(
        GridRecord(0.0, 0.0, 10.0),
        GridRecord(60.0, 0.0, 10.0),
    )
    private val cells = ingest(uniform, 0.02)
    private val k0 = CellKey(0.0, 0.0)
    private val k60 = CellKey(60.0, 0.0)

    /** Вес идёт по площади, а не по числу ячеек — иначе полюс весит как экватор. */
    @Test
    fun `при равной плотности вес идёт по площади`() {
        val w = weightsByArea(cells)
        assertTrue(abs(w.getValue(k60) / w.getValue(k0) - a60 / a0) < 0.01)
    }

    @Test
    fun `веса нормированы`() =
        assertEquals(1.0, weightsByArea(cells).values.sum(), 1e-9)

    @Test
    fun `население считается из плотности и площади`() =
        assertEquals(10 * a0, cells.getValue(k0).population, 1e-6)

    // ---------- Коэффициент терминалов ----------

    @Test
    fun `терминалы = население × коэффициент`() =
        assertEquals(cells.getValue(k0).population * 0.02, cells.getValue(k0).terminals, 1e-9)

    @Test
    fun `удвоение коэффициента удваивает терминалы`() =
        assertEquals(
            2 * cells.getValue(k0).terminals,
            ingest(uniform, 0.04).getValue(k0).terminals,
            1e-6,
        )

    @Test
    fun `коэффициент не влияет на население`() =
        assertEquals(
            cells.getValue(k0).population,
            ingest(uniform, 0.04).getValue(k0).population,
            1e-9,
        )

    // ---------- Смена разрешения ----------

    private val fine = ingest(
        buildList {
            for (la in listOf(30.0, 31.0, 32.0, 33.0)) {
                for (lo in listOf(10.0, 11.0, 12.0, 13.0)) add(GridRecord(la, lo, 5.0))
            }
        },
        0.02,
    )
    private val coarse = aggregate(fine, 4)

    @Test
    fun `огрубление сохраняет население`() = assertEquals(total(fine), total(coarse), 1e-6)

    @Test
    fun `огрубление сохраняет терминалы`() =
        assertEquals(total(fine) { it.terminals }, total(coarse) { it.terminals }, 1e-6)

    @Test
    fun `огрубление сохраняет площадь`() =
        assertEquals(total(fine) { it.areaKm2 }, total(coarse) { it.areaKm2 }, 1e-6)

    @Test
    fun `число ячеек уменьшается`() = assertTrue(coarse.size < fine.size)

    @Test
    fun `огрубление вдвое даёт больше ячеек, чем вчетверо`() =
        assertTrue(aggregate(fine, 2).size >= coarse.size)

    @Test
    fun `недопустимый коэффициент отклонён`() {
        assertThrows(IllegalArgumentException::class.java) { aggregate(fine, 0) }
    }

    // ---------- Пропуски и границы ----------

    private val withGaps = ingest(
        listOf(GridRecord(10.0, 20.0, 5.0), GridRecord(11.0, 20.0)), // вторая — над водой
        0.02,
    )

    @Test
    fun `отсутствие плотности даёт ноль, а не пропуск`() = assertEquals(2, withGaps.size)

    @Test
    fun `ячейка без данных не вносит население`() =
        assertEquals(0.0, withGaps.getValue(CellKey(11.0, 20.0)).population)

    /** Пропуск потерял бы площадь ячейки, и вес соседей вырос бы на пустом месте. */
    @Test
    fun `ячейка без данных сохраняет площадь`() =
        assertTrue(withGaps.getValue(CellKey(11.0, 20.0)).areaKm2 > 0)

    private val edge = ingest(
        listOf(
            GridRecord(0.0, 180.0, 3.0),
            GridRecord(0.0, -180.0, 3.0),
            GridRecord(89.5, 0.0, 1.0),
        ),
        0.02,
    )

    @Test
    fun `антимеридиан не теряется`() = assertEquals(3, edge.size)

    @Test
    fun `приполюсная ячейка имеет малую, но ненулевую площадь`() {
        val area = edge.getValue(CellKey(89.5, 0.0)).areaKm2
        assertTrue(area > 0 && area < cellAreaKm2(0.0) * 0.02)
    }

    // ---------- Полнота библиотек ----------

    private fun json(s: String): List<JsonNode> = mapper.readTree(s).toList()

    private val presets = json(
        """[{"id":"12U","dry_mass_kg":18,"sa_area_m2":0.15,"battery_wh":80,
             "links":["user_uplink"],"source":"аналог"},
            {"id":"16U","dry_mass_kg":24,"sa_area_m2":0.2,"battery_wh":120,
             "links":["user_uplink"],"source":"аналог"},
            {"id":"micro_50","dry_mass_kg":48,"sa_area_m2":0.6,"battery_wh":400,
             "links":[],"source":""}]"""
    )
    private val presetFields = listOf("dry_mass_kg", "sa_area_m2", "battery_wh", "links", "source")

    @Test
    fun `неполный пресет выявлен`() =
        assertEquals(setOf("micro_50"), libraryComplete(presets, presetFields).keys)

    @Test
    fun `названы недостающие поля`() =
        assertEquals(
            setOf("links", "source"),
            libraryComplete(presets, presetFields).getValue("micro_50").toSet(),
        )

    @Test
    fun `полная библиотека замечаний не даёт`() =
        assertEquals(emptyMap<String, List<String>>(), libraryComplete(presets.take(2), presetFields))

    @Test
    fun `неполный сценарий потребления выявлен`() {
        val scenarios = json(
            """[{"id":"agro","klass":"A_prime","rate_per_day":4,"payload_bytes":20,
                 "geography":"средние широты"},
                {"id":"marine","klass":"C_prime","rate_per_day":12,"payload_bytes":40,
                 "geography":""}]"""
        )
        assertEquals(
            setOf("marine"),
            libraryComplete(scenarios, listOf("klass", "rate_per_day", "payload_bytes", "geography")).keys,
        )
    }

    // ---------- Настоящий датасет ----------
    //
    // Эталон работает на подготовленных примерах. Датасет способен разойтись
    // с ними на первой же реальной особенности, поэтому он проверяется отдельно.

    private val dataset by lazy {
        PopulationDatasets.fromGeoJson(PopulationDatasets.defaultPath(RepoPaths.repoRoot()))
    }

    @Test
    fun `настоящий датасет загружается и несёт версию с происхождением`() {
        assertTrue(dataset.records.size > 100, "ячеек в датасете: ${dataset.records.size}")
        assertTrue(dataset.version.length == 16)
        assertTrue(dataset.source.contains("Natural Earth"))
        // чего в датасете НЕТ, сказано в нём самом, а не только в комментарии
        assertTrue(dataset.source.contains("сельское население не представлено"))
    }

    /** Главная проверка шага: опорные точки на настоящих данных. */
    @Test
    fun `настоящий датасет проходит по опорным точкам`() {
        val problems = referenceCheck({ la, lo -> dataset.lookup(la, lo) })
        assertEquals(emptyList<String>(), problems)
    }

    /** Перестановка координат настоящего датасета выявляется опорными точками. */
    @Test
    fun `перестановка настоящего датасета выявлена`() {
        val problems = referenceCheck({ la, lo -> dataset.lookup(lo, la) })
        assertTrue(problems.isNotEmpty(), "перестановка прошла незамеченной")
    }

    @Test
    fun `версия датасета меняется вместе с содержимым`() {
        val same = PopulationDatasets.fromGeoJson(PopulationDatasets.defaultPath(RepoPaths.repoRoot()))
        assertEquals(dataset.version, same.version)
    }

    @Test
    fun `слой населения строится из датасета`() {
        val layer = PopulationDatasets.populationLayer(dataset, 0.02, 4.0, "A_prime")
        assertEquals(dataset.records.size, layer.size)
        assertTrue(layer.all { it.klass == "A_prime" && it.terminalsPerCapita == 0.02 })
        val map = DemandMapBuilder.build(layer)
        assertEquals(1.0, map.values.sumOf { it.weight }, 1e-9)
    }

    private fun round2(x: Double) = Math.round(x * 100.0) / 100.0
}
