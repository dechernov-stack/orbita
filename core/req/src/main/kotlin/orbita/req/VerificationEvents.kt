// События верификации (CR-003/ADR-019). Эталон spec/traceability_semantics.py,
// один в один.
//
// Верификация описывается НЕСКОЛЬКИМИ событиями: «сначала расчёт по модели,
// потом испытание» — обычный случай ранних фаз. Предварительное событие даёт
// промежуточную уверенность, но не закрывает требование (ловушка 2): закрывает
// только квалификационное, приёмочное или сертификационное событие,
// завершённое успешно.
package orbita.req

import com.fasterxml.jackson.databind.JsonNode

/** Виды событий, которые могут закрывать верификацию. */
val CLOSING_KINDS = setOf("qualification", "acceptance", "certification")

enum class VerificationState(val label: String) {
    NotPlanned("не запланирована"),
    PlanIncomplete("план неполон: нет закрывающего события"),
    Planned("запланирована"),
    PreliminarilyConfirmed("предварительно подтверждено"),
    Verified("верифицировано"),
    Failed("не выполнено");

    override fun toString(): String = label
}

/** Состояние верификации требования по совокупности его событий. */
fun verificationState(req: JsonNode): VerificationState {
    val events = req.path("verification_events")
    if (!events.isArray || events.isEmpty) return VerificationState.NotPlanned
    val closing = events.filter { it.path("closes").asBoolean(false) }
    if (closing.isEmpty()) return VerificationState.PlanIncomplete
    if (closing.any { it.path("status").asText() == "failed" }) return VerificationState.Failed
    if (closing.all { it.path("status").asText() == "passed" }) return VerificationState.Verified
    // закрывающее ещё не выполнено: предварительный успех даёт промежуточное состояние
    if (events.any { it.path("status").asText() == "passed" && !it.path("closes").asBoolean(false) }) {
        return VerificationState.PreliminarilyConfirmed
    }
    return VerificationState.Planned
}

/** Полнота описания отдельного события верификации. */
fun eventIssues(event: JsonNode): List<String> {
    val issues = mutableListOf<String>()
    if (event.path("approach").asText("").isBlank()) {
        issues += "не описано, как выполняется проверка"
    }
    if (event.path("method").asText() in setOf("test", "analysis") &&
        event.path("means").asText("").isBlank()
    ) {
        issues += "не указано средство проверки"
    }
    val kind = event.path("kind").asText("")
    if (event.path("closes").asBoolean(false) && kind !in CLOSING_KINDS) {
        issues += "закрывающим может быть только квалификационное, приёмочное или сертификационное событие"
    }
    if (kind == "preliminary" && event.path("closes").asBoolean(false)) {
        issues += "предварительное событие не закрывает верификацию"
    }
    if (event.path("level").asText("").isBlank()) {
        issues += "не указан уровень проверки в структуре изделия"
    }
    return issues
}

/**
 * Область квалификации и приёмки: квалификация проводится один раз на конструкцию,
 * приёмка — на каждый экземпляр (CR-003 п. 3).
 */
fun qualificationScope(events: List<JsonNode>): List<String> {
    val issues = mutableListOf<String>()
    val qualification = events.filter { it.path("kind").asText() == "qualification" }
    val acceptance = events.filter { it.path("kind").asText() == "acceptance" }
    if (qualification.size > 1 &&
        qualification.map { it.path("design_version").asText("") }.toSet().size == 1
    ) {
        issues += "повторная квалификация одной и той же конструкции"
    }
    if (acceptance.isNotEmpty() && !acceptance.all { it.path("unit").asText("").isNotBlank() }) {
        issues += "приёмочное событие без указания экземпляра"
    }
    return issues
}

/** Все замечания к плану верификации требования: по каждому событию плюс область. */
fun verificationPlanIssues(req: JsonNode): List<String> {
    val events = req.path("verification_events").toList()
    return events.flatMap { eventIssues(it) } + qualificationScope(events)
}
