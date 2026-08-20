// Нормировка показателей для лепестковой диаграммы (TZ-BAL-007, STEP-6 §1.1).
// Эталон spec/presentation_semantics.py, один в один.
//
// Нормировка ведётся ПО СРАВНИВАЕМОМУ НАБОРУ и С УЧЁТОМ НАПРАВЛЕНИЯ показателя.
// Без направления стоимость и срок развёртывания нормируются «как есть», и
// диаграмма рисует дорогой и медленный вариант лучшим — убедительно и неверно
// (ловушка 1). Показатель без заданного направления отклоняется.
package orbita.bal

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs

enum class AxisDirection { HigherIsBetter, LowerIsBetter }

/** Направления показателей; конфигурируемы через ORBITA_KPI_AXES. */
class KpiAxes(private val directions: Map<String, AxisDirection>) {

    fun direction(axis: String): AxisDirection =
        directions[axis] ?: throw UnknownAxisException(axis, directions.keys)

    val axes: Set<String> get() = directions.keys

    companion object {
        private val mapper = ObjectMapper()

        val default: KpiAxes by lazy { load() }

        fun load(): KpiAxes {
            System.getenv("ORBITA_KPI_AXES")?.let { return fromJson(Files.readString(Path.of(it))) }
            val res = KpiAxes::class.java.getResourceAsStream("/orbita/bal/kpi-axes.json")
                ?: error("kpi-axes.json resource is missing")
            return res.use { fromJson(it.readAllBytes().decodeToString()) }
        }

        fun fromJson(json: String): KpiAxes {
            val n = mapper.readTree(json)
            val map = buildMap {
                n.path("higher_is_better").forEach { put(it.asText(), AxisDirection.HigherIsBetter) }
                n.path("lower_is_better").forEach { put(it.asText(), AxisDirection.LowerIsBetter) }
            }
            return KpiAxes(map)
        }
    }
}

class UnknownAxisException(axis: String, known: Set<String>) :
    IllegalArgumentException("направление показателя $axis не задано (известны: ${known.sorted()})")

/**
 * Нормировка значений одной оси по сравниваемому набору в [0,1], где 1 — лучше.
 * Вырожденный случай (все значения равны) даёт единицы, а не деление на ноль.
 */
fun normalizeAxis(values: List<Double>, axis: String, axes: KpiAxes = KpiAxes.default): List<Double> {
    val direction = axes.direction(axis)
    require(values.isNotEmpty()) { "empty axis values" }
    val lo = values.min()
    val hi = values.max()
    if (abs(hi - lo) <= 1e-12) return List(values.size) { 1.0 }
    val span = hi - lo
    return when (direction) {
        AxisDirection.HigherIsBetter -> values.map { (it - lo) / span }
        AxisDirection.LowerIsBetter -> values.map { (hi - it) / span }
    }
}

/** Вариант сравнения: имя и значения показателей в исходных единицах. */
data class RadarOption(val name: String, val values: Map<String, Double>)

data class RadarSeriesEntry(val name: String, val values: List<Double>)

/**
 * Ряды лепестковой диаграммы. Значения ОТНОСИТЕЛЬНЫ сравниваемому набору,
 * поэтому диаграмма несёт его состав: две диаграммы сопоставимы, только если
 * построены по одному набору и одним осям (ловушка 2).
 */
data class RadarChart(
    val axes: List<String>,
    val series: List<RadarSeriesEntry>,
    val normalizedOver: List<String>,
) {
    fun comparableWith(other: RadarChart): Boolean =
        normalizedOver == other.normalizedOver && axes == other.axes
}

fun radarSeries(
    options: List<RadarOption>,
    axes: List<String>,
    directions: KpiAxes = KpiAxes.default,
): RadarChart {
    val columns = axes.associateWith { axis ->
        normalizeAxis(options.map { it.values.getValue(axis) }, axis, directions)
    }
    return RadarChart(
        axes = axes,
        series = options.mapIndexed { i, o ->
            RadarSeriesEntry(o.name, axes.map { columns.getValue(it)[i] })
        },
        normalizedOver = options.map { it.name },
    )
}

/**
 * Фронт Парето для отображения: [x] — меньше лучше, [y] — больше лучше.
 * Направления берутся из того же перечня, что и нормировка, — второго
 * источника истины о направлении показателя в системе нет.
 */
fun paretoFrontByAxes(
    options: List<RadarOption>,
    x: String = "cost",
    y: String = "quality",
    directions: KpiAxes = KpiAxes.default,
): List<String> {
    require(directions.direction(x) == AxisDirection.LowerIsBetter) { "$x: ожидается «меньше лучше»" }
    require(directions.direction(y) == AxisDirection.HigherIsBetter) { "$y: ожидается «больше лучше»" }
    return options.filter { a ->
        options.none { b ->
            b !== a &&
                b.values.getValue(x) <= a.values.getValue(x) &&
                b.values.getValue(y) >= a.values.getValue(y) &&
                (b.values.getValue(x) < a.values.getValue(x) || b.values.getValue(y) > a.values.getValue(y))
        }
    }.map { it.name }.sorted()
}
