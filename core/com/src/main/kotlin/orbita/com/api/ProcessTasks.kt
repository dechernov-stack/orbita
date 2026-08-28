// Процесс к точке (МВП-П1, РЕШЕНИЕ-ПРОЦЕСС-К-ТОЧКЕ §1): задание =
// адресованный разрыв готовности. Статус задания ВЫЧИСЛЯЕТСЯ: разрыв
// закрыт — задание закрыто; ручных «сделано» нет. Зависимость — только
// данными входов операции (ловушка 2): задание на разрыв op:X при
// неготовом входе — «ожидает: ‹предшественник›», срок не тикает; вход
// закрылся — задание стало активным само.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.model.CoreType
import orbita.req.OperationState
import orbita.req.Operations
import java.time.LocalDate

class ProcessTasks(
    private val boundary: Boundary,
    private val operations: Operations = Operations(),
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    /** Назначать может руководитель и ведущий СИ (реестр прав В3). */
    class AssignForbiddenException(role: String?) : RuntimeException(
        "назначение заданий — право руководителя и ведущего СИ (ваша роль — ${role ?: "нет"})",
    )

    data class GapRef(val id: String, val title: String, val place: String?)

    /**
     * Пачка заданий по разрывам. Идемпотентно по (gap_ref, assignee):
     * живое задание того же разрыва тому же исполнителю не дублируется.
     */
    fun assign(
        gate: String,
        gaps: List<GapRef>,
        assignee: String,
        due: String?,
        note: String?,
        author: String,
        projectId: String,
        authorLogin: String?,
    ): Pair<List<String>, List<String>> {
        require(gaps.isNotEmpty()) { "нет разрывов к назначению" }
        require(assignee.isNotBlank()) { "исполнитель обязателен" }
        if (authorLogin != null) {
            val role = boundary.auth.roleIn(projectId, authorLogin)
            if (role != "lead" && role != "lead_se") throw AssignForbiddenException(role)
        }
        val existing = boundary.objects.listCurrent(projectId)
            .filter { it.type == "task" && it.status.name != "Cancelled" }
            .map { it.doc.path("gap_ref").asText() to it.doc.path("assignee").asText() }
            .toSet()
        val created = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        gaps.forEach { g ->
            if (g.id to assignee in existing) {
                skipped += g.id
                return@forEach
            }
            val doc = mapper.createObjectNode()
            doc.put("gap_ref", g.id)
            doc.put("gate", gate)
            doc.put("title", g.title.ifBlank { g.id })
            doc.put("assignee", assignee)
            due?.takeIf { it.isNotBlank() }?.let { doc.put("due", it) }
            note?.takeIf { it.isNotBlank() }?.let { doc.put("note", it) }
            created += boundary.editing.create(CoreType.Task, doc, author, projectId).id
        }
        return created to skipped
    }

    /**
     * Личный разрез готовности: задания участника (или все — руководителю)
     * с вычисленным состоянием. Просроченное первым; «ожидает» — отдельно.
     */
    fun myTasks(projectId: String, assignee: String?): ObjectNode {
        val project = boundary.objects.current(projectId)
            ?: throw NoSuchElementException("проект '$projectId' не найден")
        val phase = project.doc.path("phase").asText()
        val opRows = operations.states(phase, boundary.req.snapshotsAt(null, projectId))
        val opByCode = opRows.associateBy { it.operation.code }
        val checksByGate = mutableMapOf<String, Map<String, GatePassing.Check>>()
        fun checkOf(gate: String, ref: String): GatePassing.Check? =
            checksByGate.getOrPut(gate) {
                runCatching { boundary.gatePassing.readiness(gate, projectId) }
                    .getOrDefault(emptyList()).associateBy { it.id }
            }[ref]

        val today = LocalDate.now().toString()
        data class Row(
            val id: String, val gapRef: String, val gate: String, val title: String,
            val assignee: String, val due: String?, val note: String?,
            val state: String, val waitsOn: String?, val place: String?, val overdue: Boolean,
        )
        val rows = boundary.objects.listCurrent(projectId)
            .filter { it.type == "task" && it.status.name != "Cancelled" }
            .filter { assignee == null || it.doc.path("assignee").asText() == assignee }
            .map { t ->
                val gapRef = t.doc.path("gap_ref").asText()
                val gate = t.doc.path("gate").asText()
                val check = checkOf(gate, gapRef)
                // проверка исчезла из перечня (точка пройдена) — разрыв закрыт
                val closed = check == null || check.state == "closed" || check.state == "na"
                // зависимость — только данными входов операции разрыва op:X
                val waitsOn = if (!closed && gapRef.startsWith("op:")) {
                    opByCode[gapRef.removePrefix("op:")]?.operation?.inputs
                        ?.mapNotNull { opByCode[it] }
                        ?.firstOrNull { it.state == OperationState.NotStarted || it.state == OperationState.InProgress }
                        ?.let { "${it.operation.code} · ${it.operation.name}" }
                } else null
                val state = when {
                    closed -> "done"
                    waitsOn != null -> "waiting"
                    else -> "active"
                }
                val due = t.doc.path("due").asText("").ifBlank { null }
                Row(
                    id = t.id, gapRef = gapRef, gate = gate,
                    title = t.doc.path("title").asText(gapRef),
                    assignee = t.doc.path("assignee").asText(),
                    due = due, note = t.doc.path("note").asText("").ifBlank { null },
                    state = state, waitsOn = waitsOn,
                    place = check?.place,
                    // срок не тикает у ожидающих — просрочка только у активных
                    overdue = state == "active" && due != null && due < today,
                )
            }
            .sortedWith(
                compareBy(
                    { if (it.overdue) 0 else if (it.state == "active") 1 else if (it.state == "waiting") 2 else 3 },
                    { it.due ?: "9999" },
                    { it.id },
                ),
            )

        val out = mapper.createObjectNode()
        val arr = out.putArray("tasks")
        rows.forEach { r ->
            val n = arr.addObject()
            n.put("id", r.id)
            n.put("gap_ref", r.gapRef)
            n.put("gate", r.gate)
            n.put("title", r.title)
            n.put("assignee", r.assignee)
            r.due?.let { n.put("due", it) }
            r.note?.let { n.put("note", it) }
            n.put("state", r.state)
            r.waitsOn?.let { n.put("waits_on", it) }
            r.place?.let { n.put("place", it) }
            if (r.overdue) n.put("overdue", true)
        }
        val counts = out.putObject("counts")
        counts.put("active", rows.count { it.state == "active" })
        counts.put("overdue", rows.count { it.overdue })
        counts.put("waiting", rows.count { it.state == "waiting" })
        return out
    }
}
