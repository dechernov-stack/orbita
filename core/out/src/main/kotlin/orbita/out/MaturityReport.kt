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
import orbita.req.VerificationState
import orbita.req.hasOpenTbd
import orbita.req.traceGaps
import orbita.req.verificationState
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
            if (type == "technology") {
                add("TRL технологий: ${gaps.size} ниже требуемого к точке")
                return@forEach
            }
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

    /** Имена точек из реестра ворот — для /views/gates без проекта. */
    fun gateNames(): Set<String> = req.gates.gateNames

    fun build(gate: String, at: OffsetDateTime? = null): MaturityReport {
        val objects = at?.let { req.objects.sliceAt(it) } ?: req.objects.listCurrent()
        val gaps = req.readiness(gate, at).groupBy { it.type }
        val requirements = objects.filter { it.type == "requirement" && it.status != Lifecycle.Cancelled }
        val openTbd = requirements.filter { hasOpenTbd(it.doc) }
            .map { TbdItem(it.id, it.doc.path("owner").asText(null)) }
            .sortedBy { it.id }
        val breaks = traceGaps(objects.map { ObjectSnapshot.of(it) }, req.links.list("trace"))
        // CR-003: требование покрыто, только когда закрывающие события успешны
        val unverified = requirements
            .filter { verificationState(it.doc) != VerificationState.Verified }
            .map { it.id }.sorted()
        // Шаг 17 C2: технология ниже требуемого TRL к своей точке — блокирующая
        // причина наравне со статусами. Требуемое сравнивается только для
        // технологий, заявивших ЭТУ точку: чужая точка — чужой срок.
        val trlGaps = objects
            .filter { it.type == "technology" && it.status != Lifecycle.Cancelled }
            .filter { it.doc.path("gate").asText() == gate }
            .filter { it.doc.path("trl_current").asInt() < it.doc.path("trl_required").asInt() }
            .map {
                GateGap(
                    id = it.id,
                    type = "technology",
                    actual = "TRL ${it.doc.path("trl_current").asInt()}",
                    required = "TRL ${it.doc.path("trl_required").asInt()}",
                    owner = it.doc.path("owner").asText(null),
                )
            }
            .sortedBy { it.id }
        val allGaps = if (trlGaps.isEmpty()) gaps else gaps + ("technology" to trlGaps)
        return MaturityReport(gate, at, allGaps, openTbd, breaks, unverified)
    }
}
