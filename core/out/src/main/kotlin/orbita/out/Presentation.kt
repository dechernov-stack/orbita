// Представление матрицы верификации и аналитические отчёты
// (TZ-OUT-002, TZ-OUT-004; STEP-6 §2.2, §2.3).
// Эталон spec/presentation_semantics.py, один в один.
package orbita.out

import com.fasterxml.jackson.databind.JsonNode

/** Плоская строка матрицы верификации: одна строка на СОБЫТИЕ. */
data class VerificationRow(
    val requirementId: String,
    val eventId: String,
    val method: String?,
    val level: String?,
    val closes: Boolean,
    val approach: String,
    val status: String?,
    val evidenceRef: String?,
    val evidenceStale: Boolean,
)

/** Разрыв матрицы: требование без событий либо событие без описания подхода. */
data class VerificationGap(val requirementId: String, val eventId: String? = null, val reason: String)

data class VerificationMatrixView(val rows: List<VerificationRow>, val gaps: List<VerificationGap>)

/**
 * Плоское представление матрицы для отображения и выгрузки. Требование без
 * событий и событие без подхода попадают в разрывы ОТДЕЛЬНЫМ списком: пустая
 * ячейка в таблице читается как «данных нет», а не как «проверять нечего».
 */
fun verificationMatrixView(requirements: List<JsonNode>): VerificationMatrixView {
    val rows = mutableListOf<VerificationRow>()
    val gaps = mutableListOf<VerificationGap>()
    requirements.forEach { r ->
        val id = r.path("id").asText()
        val events = r.path("verification_events")
        if (!events.isArray || events.isEmpty) {
            gaps += VerificationGap(id, reason = "нет событий верификации")
            return@forEach
        }
        events.forEach { e ->
            val approach = e.path("approach").asText("")
            rows += VerificationRow(
                requirementId = id,
                eventId = e.path("id").asText(),
                method = e.path("method").asText("").ifBlank { null },
                level = e.path("level").asText("").ifBlank { null },
                closes = e.path("closes").asBoolean(false),
                approach = approach,
                status = e.path("status").asText("").ifBlank { null },
                evidenceRef = e.path("evidence_ref").asText("").ifBlank { null },
                evidenceStale = e.path("evidence_stale").asBoolean(false),
            )
            if (approach.isBlank()) {
                gaps += VerificationGap(id, e.path("id").asText(), "не описан подход")
            }
        }
    }
    return VerificationMatrixView(rows, gaps)
}

/**
 * Аналитический отчёт. Пустой отчёт ОТЛИЧАЕТСЯ от неисполненного:
 * `executed = false` означает «не считали», пустой список при `executed = true` —
 * «считали, ничего не нашли». Без этого различия отсутствие находок неотличимо
 * от отсутствия проверки (TZ-OUT-002).
 */
data class AnalyticReport<T>(val name: String, val executed: Boolean, val entries: List<T>) {
    val empty: Boolean get() = executed && entries.isEmpty()

    companion object {
        fun <T> notExecuted(name: String): AnalyticReport<T> = AnalyticReport(name, false, emptyList())
        fun <T> of(name: String, entries: List<T>): AnalyticReport<T> = AnalyticReport(name, true, entries)
    }
}

/** Узкое место прогона: участок и его загрузка (TZ-FLW-007). */
data class BottleneckEntry(val scenarioRef: String, val location: String, val utilization: Double)

/**
 * Узкие места из сохранённых результатов моделирования. Отчёт читает то,
 * что посчитало ядро потоков, и ничего не пересчитывает: единственный
 * источник загрузки участков — прогон.
 */
fun bottlenecks(flowResults: List<JsonNode>): AnalyticReport<BottleneckEntry> =
    AnalyticReport.of(
        "bottlenecks",
        flowResults.flatMap { result ->
            val scenario = result.path("scenario_ref").asText("")
            result.path("bottlenecks").map {
                BottleneckEntry(scenario, it.path("location").asText(), it.path("utilization").asDouble())
            }
        }.sortedByDescending { it.utilization },
    )

/** TPM за пределами резервов: фактическое значение вне допуска параметра. */
data class TpmBreach(val parameterId: String, val value: Double, val limit: Double, val overrun: Double)

fun tpmOutsideReserves(parameters: List<JsonNode>): AnalyticReport<TpmBreach> =
    AnalyticReport.of(
        "tpm_outside_reserves",
        parameters.mapNotNull { p ->
            val value = p.path("value").path("value").takeIf { it.isNumber }?.asDouble() ?: return@mapNotNull null
            val limit = p.path("limit").takeIf { it.isNumber }?.asDouble() ?: return@mapNotNull null
            if (value > limit) {
                TpmBreach(p.path("id").asText(), value, limit, value - limit)
            } else {
                null
            }
        }.sortedBy { it.parameterId },
    )
