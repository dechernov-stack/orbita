// Матрицы трассировки и верификации (TZ-OUT-004). Формируются из связей и
// документов — ручное заполнение ячеек отсутствует. Пустые ячейки — разрывы,
// перечисляются отдельно; устаревшее свидетельство помечается.
package orbita.out

import orbita.mod.model.Lifecycle
import orbita.req.ReqService
import orbita.req.VerificationStatus
import orbita.req.verificationStatus

data class ServiceRef(val id: String, val consumerClass: String?)

data class TraceMatrixRow(
    val requirementId: String,
    val needs: List<String>,
    val services: List<ServiceRef>,
    val elements: List<String>,
    val method: String?,
)

/** Разрыв: пустая ячейка матрицы. [missing] — source | element | method. */
data class TraceGapEntry(val requirementId: String, val missing: String)

data class TraceMatrix(val rows: List<TraceMatrixRow>, val gaps: List<TraceGapEntry>)

data class VerificationMatrixRow(
    val requirementId: String,
    val method: String?,
    val phase: String?,
    val evidenceRef: String?,
    val status: String,
    val staleEvidence: Boolean,
)

class Matrices(private val req: ReqService) {

    /** Матрица «цель ↔ требование ↔ элемент ↔ метод» из связей хранилища. */
    fun traceMatrix(): TraceMatrix {
        val rows = mutableListOf<TraceMatrixRow>()
        val gaps = mutableListOf<TraceGapEntry>()
        for (r in currentRequirements()) {
            val up = req.links.linksTo(r.id, "trace")
            val needs = req.links.ancestors(r.id).map { it.id }.filter { it.startsWith("ND-") }.sorted()
            val services = up.filter { it.fromId.startsWith("SV-") }
                .map { ServiceRef(it.fromId, it.consumerClass) }
                .sortedBy { it.id }
            val elements = req.links.linksFrom(r.id, "allocation").map { it.toId }.sorted()
            val method = r.doc.path("verification").path("method").asText(null)
            rows += TraceMatrixRow(r.id, needs, services, elements, method)
            if (up.isEmpty()) gaps += TraceGapEntry(r.id, "source")
            if (elements.isEmpty()) gaps += TraceGapEntry(r.id, "element")
            if (method.isNullOrBlank()) gaps += TraceGapEntry(r.id, "method")
        }
        return TraceMatrix(rows.sortedBy { it.requirementId }, gaps.sortedBy { it.requirementId })
    }

    /** Матрица верификации: метод, этап, свидетельство, статус; stale помечается. */
    fun verificationMatrix(): List<VerificationMatrixRow> = currentRequirements().map { r ->
        val ver = r.doc.path("verification")
        val ref = ver.path("evidence_ref").asText("").ifBlank { null }
        val evidence = req.evidenceFor(r.doc)
        val stale = ref?.let { evidence(it)?.stale } ?: false
        VerificationMatrixRow(
            requirementId = r.id,
            method = ver.path("method").asText("").ifBlank { null },
            phase = ver.path("phase").asText("").ifBlank { null },
            evidenceRef = ref,
            status = verificationStatus(r.doc, evidence).label,
            staleEvidence = stale,
        )
    }.sortedBy { it.requirementId }

    /** Непокрытые требования: статус верификации «не проверено» (TZ-REQ-008). */
    fun unverifiedRequirements(): List<String> = currentRequirements()
        .filter { verificationStatus(it.doc, req.evidenceFor(it.doc)) == VerificationStatus.NotVerified }
        .map { it.id }.sorted()

    private fun currentRequirements() =
        req.objects.listCurrent().filter { it.type == "requirement" && it.status != Lifecycle.Cancelled }
}
