// Реестр рисков (шаг 7). Эталон spec/risk_semantics.py, один в один.
// Основание: NPR 8000.4; Прил. 6 регламента БП-PA, Прил. 7 БП-PPA.
// Реестр — входной материал MCR и KDP; без него пакет передачи неполон.
package orbita.req

import com.fasterxml.jackson.databind.JsonNode

val RISK_SCALE = 1..5
val RISK_CATEGORIES = setOf("technical", "cost", "schedule", "safety")
val RISK_STRATEGIES = setOf("mitigate", "accept", "transfer", "avoid")

enum class Criticality(val code: String, val order: Int) {
    Low("low", 0),
    Medium("medium", 1),
    High("high", 2);

    companion object {
        fun of(code: String): Criticality = entries.first { it.code == code }
    }
}

/**
 * Матрица критичности 5×5: строка — последствия, столбец — вероятность.
 *
 * Это МАТРИЦА, а не произведение оценок. Ранжирование по p×i уравняло бы
 * «часто и мелко» с «редко и тяжело», и редкое тяжёлое событие потерялось бы
 * среди частых мелких. Матрица несимметрична намеренно: последствия весят
 * больше вероятности — crit(p=1, i=5) высокая, crit(p=5, i=1) средняя.
 */
private val MATRIX: Map<Int, List<Criticality>> = mapOf(
    5 to listOf(Criticality.High, Criticality.High, Criticality.High, Criticality.High, Criticality.High),
    4 to listOf(Criticality.Medium, Criticality.High, Criticality.High, Criticality.High, Criticality.High),
    3 to listOf(Criticality.Low, Criticality.Medium, Criticality.Medium, Criticality.High, Criticality.High),
    2 to listOf(Criticality.Low, Criticality.Low, Criticality.Medium, Criticality.Medium, Criticality.High),
    1 to listOf(Criticality.Low, Criticality.Low, Criticality.Low, Criticality.Low, Criticality.Medium),
)

val ESCALATION_THRESHOLD = Criticality.High

fun criticality(probability: Int, impact: Int): Criticality {
    require(probability in RISK_SCALE && impact in RISK_SCALE) {
        "вероятность и последствия задаются по шкале 1–5"
    }
    return MATRIX.getValue(impact)[probability - 1]
}

fun needsEscalation(risk: JsonNode): Boolean =
    criticality(risk.path("probability").asInt(), risk.path("impact").asInt()).order >=
        ESCALATION_THRESHOLD.order

/** Формулировка «условие — событие — последствие»: три части. */
fun riskStatementIssues(text: String): List<String> {
    val parts = text.split('—').map { it.trim() }.filter { it.isNotEmpty() }
    return if (parts.size < 3) {
        listOf("формулировка должна содержать условие, событие и последствие")
    } else {
        emptyList()
    }
}

/** Полнота записи риска; пустой список — запись управляема. */
fun riskIssues(risk: JsonNode): List<String> {
    val issues = riskStatementIssues(risk.path("statement").asText("")).toMutableList()
    if (risk.path("category").asText("") !in RISK_CATEGORIES) {
        issues += "категория риска не задана или недопустима"
    }
    listOf("probability", "impact").forEach { field ->
        val v = risk.path(field)
        if (!v.isInt || v.asInt() !in RISK_SCALE) issues += "$field: значение вне шкалы 1–5"
    }
    if (risk.path("owner").asText("").isBlank()) issues += "у риска нет владельца"

    val p = risk.path("probability")
    val i = risk.path("impact")
    if (p.isInt && p.asInt() in RISK_SCALE && i.isInt && i.asInt() in RISK_SCALE && needsEscalation(risk)) {
        val strategy = risk.path("strategy").asText("")
        if (strategy !in RISK_STRATEGIES) {
            issues += "для риска высокой критичности не задана стратегия реагирования"
        } else if (strategy != "accept" && risk.path("actions").let { it.isMissingNode || it.isEmpty }) {
            issues += "стратегия требует перечня мероприятий"
        }
        if (risk.path("due").asText("").isBlank()) {
            issues += "для риска высокой критичности не задан срок"
        }
    }
    return issues
}

/**
 * Остаточный риск после мероприятий не выше исходного НИ ПО ОДНОЙ из шкал.
 * Сравнения по классу критичности недостаточно: классы грубые, и ухудшение
 * внутри класса «высокий» осталось бы незамеченным.
 */
fun residualOk(risk: JsonNode): Boolean {
    val residual = risk.path("residual")
    if (residual.isMissingNode || residual.isNull || residual.isEmpty) return true
    val rp = residual.path("probability").asInt()
    val ri = residual.path("impact").asInt()
    val p = risk.path("probability").asInt()
    val i = risk.path("impact").asInt()
    if (rp > p || ri > i) return false
    return criticality(rp, ri).order <= criticality(p, i).order
}

data class RegisterSummary(
    val total: Int,
    val active: Int,
    val distribution: Map<String, Int>,
    val escalate: List<String>,
    /** Закрытые риски СОХРАНЯЮТСЯ в реестре, из активных исключаются. */
    val closedRetained: List<String>,
)

fun registerSummary(risks: List<JsonNode>): RegisterSummary {
    val active = risks.filter { it.path("status").asText("") != "closed" }
    val distribution = Criticality.entries.associate { it.code to 0 }.toMutableMap()
    active.forEach { r ->
        val c = criticality(r.path("probability").asInt(), r.path("impact").asInt())
        distribution[c.code] = distribution.getValue(c.code) + 1
    }
    return RegisterSummary(
        total = risks.size,
        active = active.size,
        distribution = distribution,
        escalate = active.filter { needsEscalation(it) }.map { it.path("id").asText() }.sorted(),
        closedRetained = risks.filter { it.path("status").asText("") == "closed" }
            .map { it.path("id").asText() }.sorted(),
    )
}

/** Риск связан с тем, что им затронуто: требование, элемент, интерфейс или сервис. */
fun riskTraced(risk: JsonNode): Boolean =
    risk.path("affects").let { it.isArray && !it.isEmpty }
