// Полосы бюджета (STEP-6 §1.3). Эталон spec/presentation_semantics.py.
//
// Превышение ПОМЕЧАЕТСЯ, а не обрезается: полоса, показанная как «100%»,
// скрывает ровно ту информацию, ради которой она рисуется (ловушка 4).
package orbita.out

/** Сегмент полосы: вклад потомка либо резерв. */
data class BudgetSegment(val label: String, val value: Double, val reserve: Boolean = false)

data class BudgetBar(
    val segments: List<BudgetSegment>,
    val used: Double,
    val limit: Double,
    /** Остаток бюджета; при превышении отрицателен. */
    val remaining: Double,
    val overrun: Boolean,
    /** Величина превышения; null — превышения нет. */
    val overrunValue: Double? = null,
) {
    /**
     * Доля заполнения полосы в процентах, срезанная сотней. Считается ЗДЕСЬ,
     * чтобы клиенту не пришлось делить used на limit: любое арифметическое
     * действие над величинами модели на стороне интерфейса — заготовка второй
     * реализации правила (STEP-6, ловушка 5).
     */
    val fillPercent: Double
        get() = if (limit > 0) minOf(100.0, used / limit * 100.0) else 0.0
}

/**
 * Сегменты полосы: вклады потомков плюс резерв. Сумма сегментов равна пределу,
 * пока предел не превышен; при превышении резерв не добавляется, остаток
 * отрицателен, а величина превышения выводится отдельной величиной.
 */
fun budgetSegments(limit: Double, children: List<BudgetSegment>): BudgetBar {
    val used = children.sumOf { it.value }
    val remaining = limit - used
    if (used <= limit) {
        return BudgetBar(
            segments = children + BudgetSegment("Резерв", remaining, reserve = true),
            used = used, limit = limit, remaining = remaining, overrun = false,
        )
    }
    return BudgetBar(
        segments = children,
        used = used, limit = limit, remaining = remaining,
        overrun = true, overrunValue = used - limit,
    )
}
