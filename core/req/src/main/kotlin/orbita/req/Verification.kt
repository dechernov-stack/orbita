// Верификация требования (TZ-REQ-007, CR-002/ADR-018).
//
// Статус вычисляется ПО ОПЕРАТОРУ условия (CR-001/ADR-017): скрытого допущения
// «не менее» больше нет. Полнота верификации — отдельная проверка: метод сам по
// себе не делает требование проверяемым (ловушка 3), а пересказ формулировки
// не является описанием порядка действий (ловушка 4).
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
 * Статус верификации требования по условию и свидетельству.
 * [evidence] разрешает evidence_ref в свидетельство (null — свидетельства нет).
 * Устаревшее свидетельство не засчитывается, без исключений.
 */
fun verificationStatus(req: JsonNode, evidence: (String) -> Evidence?): VerificationStatus {
    val ver = req.path("verification")
    if (ver.path("method").asText("").isBlank()) return VerificationStatus.NotVerified
    val ref = ver.path("evidence_ref").asText("")
    if (ref.isBlank()) return VerificationStatus.NotVerified
    val res = evidence(ref) ?: return VerificationStatus.NotVerified
    if (res.stale) return VerificationStatus.NotVerified
    val mop = req.path("mop")
    if (mop.path("operator").asText("").isBlank() || mop.path("value").isMissingNode) {
        return VerificationStatus.NotVerified
    }
    val actual = res.value ?: return VerificationStatus.NotVerified
    return if (satisfies(mop, actual)) VerificationStatus.Passed else VerificationStatus.Failed
}

// ---------- полнота описания верификации (CR-002) ----------

/** Служебные слова, не несущие содержания при сравнении подхода с формулировкой. */
private val STOP_WORDS = setOf(
    "должна", "должен", "должно", "система", "не", "более", "менее", "и", "в", "с", "на", "для",
    "что", "как", "по", "из", "к", "а", "или", "при", "до", "от", "это",
)

private val WORD_RE = Regex("[а-яёa-z0-9]+")

private fun significantWords(text: String?): Set<String> =
    WORD_RE.findAll((text ?: "").lowercase())
        .map { it.value }
        .filter { it !in STOP_WORDS && it.length > 2 }
        .toSet()

/** Подход, лишь пересказывающий требование, содержания не несёт (порог 70% общих слов). */
fun isRestatement(approach: String?, statement: String?): Boolean {
    val a = significantWords(approach)
    if (a.isEmpty()) return true
    val s = significantWords(statement)
    return a.intersect(s).size.toDouble() / a.size >= 0.7
}

/** Методы, для которых средство проверки обязательно. */
private val MEANS_REQUIRED = setOf("test", "analysis")

/** Замечания к полноте верификации; пустой список — требование проверяемо. */
fun verificationIssues(req: JsonNode): List<String> {
    val v = req.path("verification")
    val method = v.path("method").asText("")
    if (method.isBlank()) return listOf("метод верификации не назначен")

    val issues = mutableListOf<String>()
    val approach = v.path("approach").asText("").trim()
    when {
        approach.isEmpty() -> issues += "не описано, как именно выполняется проверка"
        approach.length < 20 -> issues += "описание проверки слишком краткое, чтобы быть выполнимым"
        isRestatement(approach, req.path("statement").asText("")) ->
            issues += "описание проверки пересказывает требование, а не задаёт порядок действий"
    }
    if (method in MEANS_REQUIRED && v.path("means").asText("").isBlank()) {
        issues += "для метода «$method» не указано средство проверки (стенд, методика, модель)"
    }
    if (req.path("mop").path("operator").asText("").isBlank() &&
        v.path("success_criterion").asText("").isBlank()
    ) {
        issues += "критерий успеха не выводится из условия и не задан явно"
    }
    return issues
}

fun isVerifiable(req: JsonNode): Boolean = verificationIssues(req).isEmpty()

/** Критерий успеха: заданный явно либо выведенный из условия требования. */
fun successCriterion(req: JsonNode, unitLabel: (String) -> String = { it }): String? {
    val explicit = req.path("verification").path("success_criterion").asText("")
    if (explicit.isNotBlank()) return explicit
    val mop = req.path("mop")
    if (mop.path("operator").asText("").isBlank()) return null
    val name = mop.path("name").asText("показатель")
    return "$name: ${renderConstraint(mop, unitLabel)}"
}
