// Верификация требований (TZ-REQ-007). Поведение — эталон
// spec/requirements_semantics.py::verification_status, один в один:
// устаревшее (stale) свидетельство не засчитывается, без исключений.
package orbita.req

import com.fasterxml.jackson.databind.JsonNode

/** Свидетельство верификации: значение показателя и признак устаревания. */
data class Evidence(val value: Double?, val stale: Boolean)

enum class VerificationStatus(val label: String) {
    NotVerified("не проверено"),
    Passed("выполнено"),
    Failed("не выполнено");

    override fun toString(): String = label
}

/**
 * Статус верификации требования по MOP и свидетельству.
 * [evidence] разрешает evidence_ref в свидетельство (null — свидетельства нет).
 */
fun verificationStatus(req: JsonNode, evidence: (String) -> Evidence?): VerificationStatus {
    val ver = req.path("verification")
    if (ver.path("method").asText("").isBlank()) return VerificationStatus.NotVerified
    val ref = ver.path("evidence_ref").asText("")
    if (ref.isBlank()) return VerificationStatus.NotVerified
    val res = evidence(ref) ?: return VerificationStatus.NotVerified
    if (res.stale) return VerificationStatus.NotVerified
    val target = req.path("mop").path("target").path("value")
        .let { if (it.isNumber) it.asDouble() else null }
        ?: return VerificationStatus.NotVerified
    // Свидетельство без значения показателя ничего не подтверждает
    val actual = res.value ?: return VerificationStatus.NotVerified
    val ok = when (req.path("mop").path("comparison").asText("ge")) {
        "le" -> actual <= target
        else -> actual >= target
    }
    return if (ok) VerificationStatus.Passed else VerificationStatus.Failed
}
