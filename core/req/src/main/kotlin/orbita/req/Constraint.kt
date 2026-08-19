// Условие требования: оператор, структурная целостность, читаемая запись
// (CR-001, ADR-017). Эталон spec/constraint_semantics.py, один в один.
//
// Оператор — часть требования, а не оформление (ловушка 1): одно измерение даёт
// противоположный вердикт при «не более» и «не менее». Единицы хранятся кодами
// СИ; подписи подставляются функцией локализации — в модели их нет (CLAUDE.md §3).
package orbita.req

import com.fasterxml.jackson.databind.JsonNode

enum class ConstraintOperator(val code: String) {
    Eq("eq"), Le("le"), Ge("ge"), Lt("lt"), Gt("gt"), Range("range"), Tolerance("tolerance");

    companion object {
        fun of(code: String): ConstraintOperator = entries.firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("неизвестный оператор: $code")
    }
}

/** Правило свёртки по дочерним требованиям (связь derive). */
enum class RollupRule(val code: String) {
    Sum("sum"), Max("max"), Min("min"), None("none");

    companion object {
        fun of(code: String?): RollupRule = entries.firstOrNull { it.code == code } ?: None
    }
}

private fun JsonNode.qValue(): Double = path("value").asDouble()
private fun JsonNode.qUnit(): String = path("unit").asText("")

/** Выполняется ли условие требования фактическим значением. */
fun satisfies(mop: JsonNode, actual: Double): Boolean {
    val v = mop.path("value").qValue()
    return when (ConstraintOperator.of(mop.path("operator").asText())) {
        ConstraintOperator.Le -> actual <= v
        ConstraintOperator.Ge -> actual >= v
        ConstraintOperator.Lt -> actual < v
        ConstraintOperator.Gt -> actual > v
        ConstraintOperator.Eq -> actual == v
        ConstraintOperator.Range -> actual >= v && actual <= mop.path("upper").qValue()
        ConstraintOperator.Tolerance -> Math.abs(actual - v) <= mop.path("tolerance").qValue()
    }
}

/**
 * Читаемая запись условия. [unitLabel] — подстановка подписи единицы;
 * по умолчанию выводится код СИ, как он хранится в модели.
 */
fun renderConstraint(mop: JsonNode, unitLabel: (String) -> String = { it }): String {
    val u = unitLabel(mop.path("value").qUnit())
    val v = formatNumber(mop.path("value").qValue())
    return when (ConstraintOperator.of(mop.path("operator").asText())) {
        ConstraintOperator.Le -> "не более $v $u"
        ConstraintOperator.Ge -> "не менее $v $u"
        ConstraintOperator.Lt -> "менее $v $u"
        ConstraintOperator.Gt -> "более $v $u"
        ConstraintOperator.Eq -> "ровно $v $u"
        ConstraintOperator.Range -> "от $v до ${formatNumber(mop.path("upper").qValue())} $u"
        ConstraintOperator.Tolerance -> "$v ± ${formatNumber(mop.path("tolerance").qValue())} $u"
    }
}

/** Целые значения печатаются без дробной части: «500 г», не «500.0 г». */
private fun formatNumber(d: Double): String =
    if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()

/** Структурная целостность условия (CR-001). */
fun validateMop(mop: JsonNode): List<String> {
    val errors = mutableListOf<String>()
    val op = mop.path("operator").asText("")
    if (op.isBlank()) errors += "оператор обязателен"
    if (mop.path("value").isMissingNode || mop.path("value").isNull) {
        errors += "значение обязательно"
    } else if (mop.path("value").qUnit().isBlank()) {
        errors += "единица обязательна"
    }
    if (op == "range" && (mop.path("upper").isMissingNode || mop.path("upper").isNull)) {
        errors += "range требует upper"
    }
    if (op == "tolerance" && (mop.path("tolerance").isMissingNode || mop.path("tolerance").isNull)) {
        errors += "tolerance требует допуска"
    }
    if (op == "range" && !mop.path("upper").isMissingNode && !mop.path("upper").isNull) {
        if (mop.path("upper").qValue() <= mop.path("value").qValue()) {
            errors += "верхняя граница не выше нижней"
        }
        if (mop.path("upper").qUnit() != mop.path("value").qUnit()) {
            errors += "единицы границ диапазона различны"
        }
    }
    return errors
}

/** Соответствие оборотов формулировки оператору (CR-001, правило качества). */
private val PHRASE_OPERATOR: List<Pair<String, String>> = listOf(
    "не более" to "le", "не превыша" to "le", "не должна превыша" to "le",
    "не менее" to "ge", "не ниже" to "ge", "ровно" to "eq",
    "в пределах" to "range", "от " to "range",
)

/** null — противоречия нет; иначе описание расхождения формулировки и оператора. */
fun statementMatchesOperator(statement: String, mop: JsonNode): String? {
    val low = statement.lowercase()
    for ((phrase, op) in PHRASE_OPERATOR) {
        if (phrase in low) {
            val actual = mop.path("operator").asText("")
            if (op != actual && !(op == "range" && phrase == "от ")) {
                return "формулировка говорит «$phrase» ($op), а оператор — $actual"
            }
            return null
        }
    }
    return null
}

/** Результат проверки состоятельности декомпозиции (свёртка по derive). */
data class RollupResult(
    val applicable: Boolean,
    val error: String? = null,
    val rule: RollupRule? = null,
    val aggregate: Double? = null,
    val limit: Double? = null,
    val consistent: Boolean? = null,
    /** Остаток бюджета; определён для операторов le/lt. Отрицательный — превышение. */
    val remaining: Double? = null,
    val unit: String? = null,
)

/**
 * Свёртка дочерних требований против родительского бюджета (CR-001).
 * Единицы дочерних обязаны совпадать с родительским: молчаливое приведение
 * запрещено — 60 кг и 30 000 г в одной свёртке суть ошибка ввода (ловушка 2).
 */
fun rollupCheck(parentMop: JsonNode, childMops: List<JsonNode>): RollupResult {
    val rule = RollupRule.of(parentMop.path("rollup").asText(null))
    if (rule == RollupRule.None) return RollupResult(applicable = false)

    val units = childMops.map { it.path("value").qUnit() }.toMutableSet()
    units += parentMop.path("value").qUnit()
    if (units.size > 1) {
        return RollupResult(true, error = "единицы не совпадают: ${units.sorted()}")
    }
    if (childMops.isEmpty()) {
        return RollupResult(true, error = "нет дочерних требований")
    }
    val values = childMops.map { it.path("value").qValue() }
    val aggregate = when (rule) {
        RollupRule.Sum -> values.sum()
        RollupRule.Max -> values.max()
        RollupRule.Min -> values.min()
        RollupRule.None -> return RollupResult(false)
    }
    val limit = parentMop.path("value").qValue()
    val op = parentMop.path("operator").asText("")
    return RollupResult(
        applicable = true,
        rule = rule,
        aggregate = aggregate,
        limit = limit,
        consistent = satisfies(parentMop, aggregate),
        remaining = if (op == "le" || op == "lt") limit - aggregate else null,
        unit = parentMop.path("value").qUnit(),
    )
}
