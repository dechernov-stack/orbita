// Загрузка внешних данных населения (шаг 10.2).
//
// Поведение — эталон spec/ingestion_semantics.py, один в один.
//
// На этом пути живут ошибки, невидимые ни в одном отчёте: перепутанные широта
// и долгота, счёт по числу ячеек вместо площади, потеря при смене разрешения,
// пропуски над водой. Каждая из них даёт правдоподобную карту.
//
// ПЕРЕСТАНОВКУ КООРДИНАТ НЕЛЬЗЯ ПОЙМАТЬ ПРОВЕРКОЙ ДИАПАЗОНОВ: города Европы
// укладываются в ±90 по обеим осям. Ловят опорные точки — известные населённые
// места и открытый океан. Если посреди Тихого океана обнаружилось население,
// координаты перепутаны либо сетка смещена.
package orbita.usr

import kotlin.math.abs
import kotlin.math.floor

/** Запись датасета: плотность на ячейке сетки; null — данных нет (вода, пропуск). */
data class GridRecord(val lat: Double, val lon: Double, val densityPerKm2: Double? = null)

/** Ячейка после загрузки: площадь, население, терминалы. */
data class LoadedCell(
    val lat: Double,
    val lon: Double,
    val areaKm2: Double,
    val population: Double,
    val terminals: Double = 0.0,
)

/** Ключ ячейки: координаты, округлённые до микроградуса. */
data class CellKey(val lat: Double, val lon: Double)

fun validateCoords(rec: GridRecord): List<String> = buildList {
    if (rec.lat !in -90.0..90.0) add("широта вне диапазона: ${rec.lat}")
    if (rec.lon !in -180.0..180.0) add("долгота вне диапазона: ${rec.lon}")
}

/** Дешёвый признак: широта вне ±90. Ловит только однозначный случай. */
fun looksSwapped(records: List<GridRecord>): Boolean = records.any { abs(it.lat) > 90 }

data class ReferencePoint(val name: String, val lat: Double, val lon: Double, val populated: Boolean)

/**
 * Опорные точки: суша с заведомым населением и открытый океан. Перестановка
 * координат превращает населённые точки в пустые и наоборот — по диапазонам
 * это неразличимо.
 */
val REFERENCE: List<ReferencePoint> = listOf(
    ReferencePoint("Москва", 55.75, 37.62, populated = true),
    ReferencePoint("Дели", 28.61, 77.21, populated = true),
    ReferencePoint("Тихий океан, центр", -10.0, -140.0, populated = false),
    ReferencePoint("Южный океан", -60.0, 0.0, populated = false),
)

/** Расхождения датасета с ожиданием в опорных точках; пустой список — сошлось. */
fun referenceCheck(
    lookup: (Double, Double) -> Double,
    points: List<ReferencePoint> = REFERENCE,
    populatedThreshold: Double = 1.0,
): List<String> = buildList {
    for (p in points) {
        val density = lookup(p.lat, p.lon)
        if (p.populated && density < populatedThreshold) {
            add("${p.name}: ожидалось население, получено $density")
        }
        if (!p.populated && density >= populatedThreshold) {
            add("${p.name}: ожидалась пустота, получено $density")
        }
    }
}

/** Датасет плотности → ячейки с населением и числом терминалов. */
fun ingest(
    records: List<GridRecord>,
    terminalsPerCapita: Double,
    dlat: Double = 1.0,
    dlon: Double = 1.0,
): Map<CellKey, LoadedCell> {
    val problems = records.flatMap { validateCoords(it) }
    if (problems.isNotEmpty()) throw IllegalArgumentException(problems.take(3).joinToString("; "))
    if (looksSwapped(records)) throw IllegalArgumentException("похоже, широта и долгота перепутаны")

    val cells = LinkedHashMap<CellKey, LoadedCell>()
    for (r in records) {
        val area = cellAreaKm2(r.lat, dlat, dlon)
        // Нет данных (вода, пропуск) — НОЛЬ, а не пропуск и не NaN: ячейка
        // существует, просто в ней никого нет. Пропуск потерял бы её площадь.
        val density = r.densityPerKm2 ?: 0.0
        val key = CellKey(round6(r.lat), round6(r.lon))
        val current = cells[key]
        cells[key] = LoadedCell(
            lat = current?.lat ?: r.lat,
            lon = current?.lon ?: r.lon,
            areaKm2 = current?.areaKm2 ?: area,
            population = (current?.population ?: 0.0) + density * area,
        )
    }
    return cells.mapValues { (_, c) -> c.copy(terminals = c.population * terminalsPerCapita) }
}

/** Огрубление сетки: население, терминалы и площади суммируются. */
fun aggregate(cells: Map<CellKey, LoadedCell>, factor: Int): Map<CellKey, LoadedCell> {
    require(factor >= 1) { "коэффициент огрубления — целое ≥ 1" }
    val out = LinkedHashMap<CellKey, LoadedCell>()
    for (c in cells.values) {
        val key = CellKey(floor(c.lat / factor) * factor, floor(c.lon / factor) * factor)
        val g = out[key]
        out[key] = LoadedCell(
            lat = key.lat,
            lon = key.lon,
            areaKm2 = (g?.areaKm2 ?: 0.0) + c.areaKm2,
            population = (g?.population ?: 0.0) + c.population,
            terminals = (g?.terminals ?: 0.0) + c.terminals,
        )
    }
    return out
}

fun total(cells: Map<CellKey, LoadedCell>, field: (LoadedCell) -> Double = { it.population }): Double =
    cells.values.sumOf(field)

/** Вес ячейки — по её ВКЛАДУ, а не по факту существования (ловушка счёта ячеек). */
fun weightsByArea(cells: Map<CellKey, LoadedCell>): Map<CellKey, Double> {
    val t = total(cells)
    return cells.mapValues { (_, c) -> if (t > 0) c.population / t else 0.0 }
}

private fun round6(x: Double): Double = Math.round(x * 1_000_000.0) / 1_000_000.0
