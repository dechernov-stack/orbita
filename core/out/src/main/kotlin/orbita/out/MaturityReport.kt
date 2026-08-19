// Отчёт зрелости пакета к контрольной точке (TZ-OUT-003, представление
// TZ-REQ-008): статусы против требуемых, незакрытые TBD/TBR с владельцами,
// разрывы трассировки, непокрытые требования. Формируется на произвольную
// дату по истории версий (срез шага 1); связи в шаге 2 не версионируются,
// поэтому граф связей берётся текущим.
package orbita.out

import orbita.mod.model.Lifecycle
import orbita.req.GateGap
import orbita.req.ObjectSnapshot
import orbita.req.ReqService
import orbita.req.VerificationStatus
import orbita.req.hasOpenTbd
import orbita.req.traceGaps
import orbita.req.verificationStatus
import java.time.OffsetDateTime

data class TbdItem(val id: String, val owner: String?)

data class MaturityReport(
    val gate: String,
    val at: OffsetDateTime?,
    val gapsByType: Map<String, List<GateGap>>,
    val openTbd: List<TbdItem>,
    val traceBreaks: List<String>,
    val unverified: List<String>,
) {
    /** Блокирующие причины для титула отчёта. */
    fun blockingReasons(): List<String> = buildList {
        gapsByType.forEach { (type, gaps) ->
            add("статусы $type: ${gaps.size} объект(ов) ниже требуемого")
        }
        if (openTbd.isNotEmpty()) add("незакрытые TBD/TBR: ${openTbd.size}")
        if (traceBreaks.isNotEmpty()) add("разрывы трассировки: ${traceBreaks.size}")
        if (unverified.isNotEmpty()) add("непокрытые требования: ${unverified.size}")
    }

    fun ready(): Boolean =
        gapsByType.isEmpty() && openTbd.isEmpty() && traceBreaks.isEmpty() && unverified.isEmpty()
}

class MaturityReports(private val req: ReqService) {

    fun build(gate: String, at: OffsetDateTime? = null): MaturityReport {
        val objects = at?.let { req.objects.sliceAt(it) } ?: req.objects.listCurrent()
        val gaps = req.readiness(gate, at).groupBy { it.type }
        val requirements = objects.filter { it.type == "requirement" && it.status != Lifecycle.Cancelled }
        val openTbd = requirements.filter { hasOpenTbd(it.doc) }
            .map { TbdItem(it.id, it.doc.path("owner").asText(null)) }
            .sortedBy { it.id }
        val breaks = traceGaps(objects.map { ObjectSnapshot.of(it) }, req.links.list("trace"))
        val unverified = requirements
            .filter { verificationStatus(it.doc, req.evidenceFor(it.doc)) == VerificationStatus.NotVerified }
            .map { it.id }.sorted()
        return MaturityReport(gate, at, gaps, openTbd, breaks, unverified)
    }
}
