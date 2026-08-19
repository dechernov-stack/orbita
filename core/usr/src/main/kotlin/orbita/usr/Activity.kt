// Профили активности (TZ-USR-005). Поведение — эталон spec/demand_semantics.py:
// суточный (24) и сезонный (12) множители; отсутствие профиля = равномерность;
// худшее сочетание «час × месяц» совмещается с худшей энергетикой витка (шаг 4).
package orbita.usr

/** Интенсивность ячейки в час [hour] месяца [month]; профили null = равномерность. */
fun intensityAt(
    cell: DemandCell,
    hour: Int,
    month: Int,
    diurnal: List<Double>? = null,
    seasonal: List<Double>? = null,
): Double {
    val base = cell.totalMsgsPerDay() / 24.0
    val d = diurnal?.get(hour) ?: 1.0
    val s = seasonal?.get(month) ?: 1.0
    return base * d * s
}

/** Худшее (пиковое) сочетание часа и месяца; при равенстве — первое по порядку обхода. */
fun worstCaseHourMonth(cell: DemandCell, diurnal: List<Double>, seasonal: List<Double>): Pair<Int, Int> =
    (0 until 24).flatMap { h -> (0 until 12).map { m -> h to m } }
        .maxByOrNull { (h, m) -> intensityAt(cell, h, m, diurnal, seasonal) }!!
