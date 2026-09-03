// Матрицы трассировки и верификации (TZ-OUT-004). Формируются из связей и
// документов — ручное заполнение ячеек отсутствует. Пустые ячейки — разрывы,
// перечисляются отдельно; устаревшее свидетельство помечается.
package orbita.out

import orbita.mod.model.Lifecycle
import orbita.req.ReqService
import orbita.req.UnitLabels
import orbita.req.VerificationState
import orbita.req.eventIssues
import orbita.req.evidenceState
import orbita.req.successCriterion
import orbita.req.validationIssues
import orbita.req.verificationPlanIssues
import orbita.req.verificationState

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

/** ADR-047: матрица «функции × узлы». */
data class FunctionColumn(val id: String, val name: String, val type: String)
data class FunctionCell(val id: String, val kind: String, val rationale: String?)
data class FunctionRow(val functionId: String, val name: String, val level: String?, val sources: List<String>, val nodes: List<FunctionCell>)
data class FunctionMatrix(val columns: List<FunctionColumn>, val rows: List<FunctionRow>, val unallocated: List<String>, val nodesWithoutFunctions: List<String>)

/**
 * Строка матрицы верификации — ОДНО СОБЫТИЕ требования (CR-003/ADR-019):
 * «сначала расчёт по модели, потом испытание» больше не сворачивается в одну
 * ячейку. Подход и критерий — CR-002.
 */
data class VerificationEventRow(
    val requirementId: String,
    val eventId: String,
    val method: String?,
    val kind: String?,
    val phase: String?,
    val level: String?,
    val closes: Boolean,
    val status: String?,
    val approach: String?,
    val means: String?,
    val successCriterion: String?,
    val evidenceRef: String?,
    val evidenceState: String?,
    val issues: List<String> = emptyList(),
)

/** Итог по требованию: состояние верификации и замечания к плану. */
data class VerificationSummaryRow(
    val requirementId: String,
    val state: String,
    val events: List<VerificationEventRow>,
    val planIssues: List<String>,
)

/** Строка матрицы ВАЛИДАЦИИ — отдельной от верификации (CR-003, ловушка 1). */
data class ValidationMatrixRow(
    val validationId: String,
    val target: String,
    val conopsRef: String?,
    val productKind: String?,
    val method: String?,
    val phase: String?,
    val status: String?,
    val evidenceRef: String?,
    val issues: List<String> = emptyList(),
)

class Matrices(
    private val req: ReqService,
    /** Подписи единиц для критерия успеха: коды СИ остаются в модели (CR-001 п.6). */
    private val unitLabels: UnitLabels = UnitLabels(),
) {

    /** Матрица «цель ↔ требование ↔ элемент ↔ метод» из связей хранилища. */
    fun traceMatrix(projectId: String? = null): TraceMatrix {
        val rows = mutableListOf<TraceMatrixRow>()
        val gaps = mutableListOf<TraceGapEntry>()
        for (r in currentRequirements(projectId)) {
            val up = req.links.linksTo(r.id, "trace")
            val needs = req.links.ancestors(r.id).map { it.id }.filter { it.startsWith("ND-") }.sorted()
            val services = up.filter { it.fromId.startsWith("SV-") }
                .map { ServiceRef(it.fromId, it.consumerClass) }
                .sortedBy { it.id }
            val elements = req.links.linksFrom(r.id, "allocation").map { it.toId }.sorted()
            // CR-003: метод берётся из событий верификации — закрывающего, иначе первого
            val events = r.doc.path("verification_events")
            val method = (events.firstOrNull { it.path("closes").asBoolean(false) } ?: events.firstOrNull())
                ?.path("method")?.asText("")?.ifBlank { null }
            rows += TraceMatrixRow(r.id, needs, services, elements, method)
            if (up.isEmpty()) gaps += TraceGapEntry(r.id, "source")
            if (elements.isEmpty()) gaps += TraceGapEntry(r.id, "element")
            if (method.isNullOrBlank()) gaps += TraceGapEntry(r.id, "method")
        }
        return TraceMatrix(rows.sortedBy { it.requirementId }, gaps.sortedBy { it.requirementId })
    }

    /**
     * Матрица верификации: по строке на КАЖДОЕ событие требования (CR-003).
     * Свидетельство разрешается в объект EV-NNNN и проверяется на применимость
     * к текущей конфигурации.
     */
    fun verificationMatrix(
        currentConfiguration: String? = null,
        projectId: String? = null,
    ): List<VerificationSummaryRow> =
        currentRequirements(projectId).map { r ->
            val events = r.doc.path("verification_events").map { e ->
                val ref = e.path("evidence_ref").asText("").ifBlank { null }
                val evidence = ref?.let { req.objects.current(it) }
                VerificationEventRow(
                    requirementId = r.id,
                    eventId = e.path("id").asText(""),
                    method = e.path("method").asText("").ifBlank { null },
                    kind = e.path("kind").asText("").ifBlank { null },
                    phase = e.path("phase").asText("").ifBlank { null },
                    level = e.path("level").asText("").ifBlank { null },
                    closes = e.path("closes").asBoolean(false),
                    status = e.path("status").asText("").ifBlank { null },
                    approach = e.path("approach").asText("").ifBlank { null },
                    means = e.path("means").asText("").ifBlank { null },
                    successCriterion = successCriterion(r.doc, unitLabels.asFunction()),
                    evidenceRef = ref,
                    evidenceState = evidence?.let { ev ->
                        currentConfiguration?.let { evidenceState(ev.doc, it).label }
                    },
                    issues = eventIssues(e),
                )
            }
            VerificationSummaryRow(
                requirementId = r.id,
                state = verificationState(r.doc).label,
                events = events,
                planIssues = verificationPlanIssues(r.doc),
            )
        }.sortedBy { it.requirementId }

    /**
     * Матрица ВАЛИДАЦИИ — отдельная от матрицы верификации (CR-003 п. 5):
     * «то ли мы построили» против «построили ли мы правильно».
     */
    fun validationMatrix(projectId: String? = null): List<ValidationMatrixRow> = req.objects.listCurrent(projectId)
        .filter { it.type == "validation" && it.status != Lifecycle.Cancelled }
        .map { v ->
            ValidationMatrixRow(
                validationId = v.id,
                target = v.doc.path("target").asText(""),
                conopsRef = v.doc.path("conops_ref").asText("").ifBlank { null },
                productKind = v.doc.path("product_kind").asText("").ifBlank { null },
                method = v.doc.path("method").asText("").ifBlank { null },
                phase = v.doc.path("phase").asText("").ifBlank { null },
                status = v.doc.path("status").asText("").ifBlank { null },
                evidenceRef = v.doc.path("evidence_ref").asText("").ifBlank { null },
                issues = validationIssues(v.doc),
            )
        }.sortedBy { it.validationId }

    /** Непокрытые требования: статус верификации «не проверено» (TZ-REQ-008). */
    fun unverifiedRequirements(projectId: String? = null): List<String> = currentRequirements(projectId)
        .filter { verificationState(it.doc) != VerificationState.Verified }
        .map { it.id }.sorted()

    /**
     * ADR-047: матрица «функции × узлы» — четвёртая матрица. Столбцы — все
     * определения состава и интерфейсы проекта, строки — функции с узлами
     * распределения; разрывы — функции без носителя и узлы без функций.
     */
    fun functionMatrix(projectId: String? = null): FunctionMatrix {
        val current = req.objects.listCurrent(projectId).filter { it.status != orbita.mod.model.Lifecycle.Cancelled }
        val columns = current.filter { it.type == "component" || it.type == "interface" }
            .sortedBy { it.id }.map { FunctionColumn(it.id, it.doc.path("name").asText(""), it.type) }
        val rows = current.filter { it.type == "function" }.sortedBy { it.id }.map { f ->
            val nodes = f.doc.path("allocated_to").map { a ->
                FunctionCell(
                    a.path("component").asText("").ifBlank { a.path("interface").asText("") },
                    a.path("kind").asText("full"), a.path("rationale").asText("").ifBlank { null },
                )
            }.sortedBy { it.id }
            FunctionRow(f.id, f.doc.path("name").asText(""), f.doc.path("level").asText("").ifBlank { null },
                f.doc.path("traces_up").map { it.path("ref").asText() }, nodes)
        }
        val covered = rows.flatMap { r -> r.nodes.map { it.id } }.toSet()
        return FunctionMatrix(
            columns, rows,
            unallocated = rows.filter { it.nodes.isEmpty() }.map { it.functionId },
            nodesWithoutFunctions = columns.map { it.id }.filter { it !in covered },
        )
    }

    private fun currentRequirements(projectId: String? = null) =
        req.objects.listCurrent(projectId).filter { it.type == "requirement" && it.status != Lifecycle.Cancelled }
}
