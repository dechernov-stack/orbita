// Свидетельства как самостоятельные объекты EV-NNNN (CR-003/ADR-019).
// Эталон spec/traceability_semantics.py, один в один.
//
// Свидетельство привязано к КОНФИГУРАЦИИ (ловушка 3): после изменения конструкции
// протокол остаётся достоверным документом, но перестаёт быть свидетельством —
// он относится к другому изделию.
package orbita.req

import com.fasterxml.jackson.databind.JsonNode

enum class EvidenceState(val label: String) {
    Superseded("заменено"),
    NotApplicable("неприменимо к текущей конфигурации"),
    Stale("устарело"),
    Valid("действительно");

    override fun toString(): String = label
}

fun evidenceState(evidence: JsonNode, currentConfiguration: String): EvidenceState = when {
    evidence.path("superseded_by").asText("").isNotBlank() -> EvidenceState.Superseded
    evidence.path("configuration").asText("") != currentConfiguration -> EvidenceState.NotApplicable
    evidence.path("stale").asBoolean(false) -> EvidenceState.Stale
    else -> EvidenceState.Valid
}

/** Цепочка свидетельств по времени: предварительный расчёт → физическое испытание. */
fun evidenceChain(documents: List<JsonNode>): List<String> =
    documents.sortedBy { it.path("date").asText("") }.map { it.path("id").asText() }
