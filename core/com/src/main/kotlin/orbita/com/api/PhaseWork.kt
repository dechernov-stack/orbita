// «Работа фазы» — главный экран проекта после мастера. Владелец: «мастер
// доводит до постановки и обрывается; дальше месяцы работы команды по
// регламенту, и именно у неё нет лица в продукте».
//
// Здесь считается всё, что показывает экран, и НИЧЕГО не хранится:
//   · статус задачи — из состояния проекта (ожидает → доступна → в работе →
//     выполнена). Ручных статусов, процентов и «сделано» не существует;
//   · шаги — из полки, сделанность каждого — проверяемым условием;
//   · разрывы задачи — те же разрывы готовности к точке, взятые РАЗРЕЗОМ по
//     операциям задачи. Второй готовности не заводится;
//   · окна ленты — только производные от дат вех и зависимостей: старт —
//     готовность входа либо дедлайн предшественника, конец — дата точки, к
//     которой зреет выход. Ручных длительностей и сроков задач нет.
package orbita.com.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.store.ObjectStore
import orbita.mod.store.StoredObject
import java.time.LocalDate

object PhaseWork {

    private val mapper = ObjectMapper()

    /** До точки меньше этого — окно считается сжатым (разрыв «цепочка не успевает»). */
    private const val TIGHT_DAYS = 7L

    data class StepState(val title: String, val hint: String?, val screen: String?, val kind: String?, val done: Boolean, val why: String)

    data class TaskState(
        val id: String,
        val order: Int,
        val name: String,
        val why: String,
        val status: String,
        val waitsOn: String?,
        val inputReady: Boolean,
        val inputWhy: String,
        val steps: List<StepState>,
        val gaps: List<String>,
        val artifact: String,
        val gate: String?,
        val outputDone: Boolean,
        val start: LocalDate?,
        val end: LocalDate?,
        val tight: Boolean,
    )

    /**
     * Условие полки → да/нет по состоянию проекта. Набор закрытый: система
     * обязана уметь ответить сама, иначе на экране появился бы ручной
     * переключатель «сделано», а его быть не должно.
     */
    private fun holds(
        condition: JsonNode,
        own: List<StoredObject>,
        passport: JsonNode,
        gateChecks: Map<String, String>,
        issued: Set<String>,
    ): Boolean = when (condition.path("check").asText()) {
        "always" -> true
        "objects" -> {
            val type = condition.path("type").asText("")
            val min = condition.path("min").asInt(1)
            own.count { it.type == type } >= min
        }
        "passport_field" -> {
            val field = condition.path("field").asText("")
            val node = passport.path(field)
            when {
                node.isMissingNode || node.isNull -> false
                node.isObject -> !node.isEmpty
                node.isArray -> node.size() > 0
                else -> node.asText("").isNotBlank()
            }
        }
        "taken_from_library" -> {
            val type = condition.path("type").asText("")
            passport.path("start_path").path("created_counts").path(type).asInt(0) > 0
        }
        "gate_check" -> gateChecks[condition.path("gate_check_id").asText("")] == "closed"
        "document_issued" -> condition.path("code").asText("") in issued
        else -> false
    }

    private fun labelOf(condition: JsonNode): String =
        condition.path("label").asText("").ifBlank {
            when (condition.path("check").asText()) {
                "objects" -> "нужны объекты вида «${condition.path("type").asText()}»"
                "passport_field" -> "нужно поле паспорта «${condition.path("field").asText()}»"
                "taken_from_library" -> "нужен набор библиотеки «${condition.path("type").asText()}»"
                "gate_check" -> "нужна закрытая проверка «${condition.path("gate_check_id").asText()}»"
                "document_issued" -> "нужен выпущенный документ «${condition.path("code").asText()}»"
                else -> "условие не названо"
            }
        }

    fun of(boundary: Boundary, projectId: String): List<TaskState> {
        val passport = boundary.objects.current(projectId)?.doc ?: mapper.createObjectNode()
        val phase = passport.path("phase").asText("pre_phase_a")
        val own = boundary.objects.listCurrent(projectId).filter { it.status.name != "Cancelled" }
        val tasks = boundary.objects.listCurrent(ObjectStore.LIBRARY_PROJECT)
            .filter { it.type == "phase_task" && it.status.name != "Cancelled" }
            .filter { it.doc.path("phase").asText() == phase }
            .sortedBy { it.doc.path("order").asInt() }
        if (tasks.isEmpty()) return emptyList()

        // разрывы готовности к точке — ОДИН источник; здесь берётся разрез.
        // Второй готовности не заводится: те же проверки, другой разрез.
        val passing = GatePassing(boundary)
        val gate = passing.nextGate(projectId) ?: ""
        val checks = if (gate.isBlank()) emptyList() else passing.readiness(gate, projectId)
        val gateChecks = checks.associate { it.id to it.state }
        val gateDate = milestoneDate(passport, gate)
        val issued = own.filter { it.type == "document_issue" }
            .map { it.doc.path("code").asText("") }.filter { it.isNotBlank() }.toSet()

        val byId = tasks.associateBy { it.id }
        val states = LinkedHashMap<String, TaskState>()
        tasks.forEach { task ->
            val doc = task.doc
            val inputs = doc.path("input").toList()
            val unmet = inputs.filterNot { holds(it, own, passport, gateChecks, issued) }
            val steps = doc.path("steps").map { st ->
                StepState(
                    title = st.path("title").asText(),
                    hint = st.path("hint").asText("").ifBlank { null },
                    screen = st.path("screen").asText("").ifBlank { null },
                    kind = st.path("kind").asText("").ifBlank { null },
                    done = holds(st.path("done_when"), own, passport, gateChecks, issued),
                    why = labelOf(st.path("done_when")),
                )
            }
            // предшественник, чей выход ещё не готов, — вот кого ждём
            val waiting = doc.path("depends_on").mapNotNull { byId[it.asText()] }
                .firstOrNull { pred ->
                    val out = pred.doc.path("output").path("done_when")
                    !out.isMissingNode && !holds(out, own, passport, gateChecks, issued)
                }
            val outputDone = doc.path("output").path("done_when").let {
                !it.isMissingNode && holds(it, own, passport, gateChecks, issued)
            }
            // Разрез: разрывом ЗАДАЧИ считается открытая проверка, которая
            // либо названа условием её шага, либо чинится на экране её шага.
            // Так задача не заводит собственных чек-листов — она лишь смотрит
            // на общую готовность своим углом.
            val screens = (steps.mapNotNull { it.screen } +
                listOfNotNull(doc.path("output").path("document_code").asText("").ifBlank { null }
                    ?.let { "documents" })).toSet()
            val namedChecks = (doc.path("steps").mapNotNull {
                it.path("done_when").path("gate_check_id").asText("").ifBlank { null }
            } + listOfNotNull(
                doc.path("output").path("done_when").path("gate_check_id").asText("").ifBlank { null },
            )).toSet()
            val gaps = checks
                .filter { it.state == "open" }
                .filter { it.id in namedChecks || (it.place != null && it.place in screens) }
                .map { "${it.title}: ${it.note}" }
            val status = when {
                outputDone -> "done"
                waiting != null -> "waiting"
                unmet.isNotEmpty() -> "waiting"
                steps.any { it.done } -> "in_progress"
                else -> "available"
            }
            // окно: старт — сегодня для доступной, дедлайн предшественника для
            // ожидающей; конец — дата точки выхода. Длительности не вводятся.
            val end = milestoneDate(passport, doc.path("output").path("gate").asText("")) ?: gateDate
            val start = when {
                status == "waiting" && waiting != null ->
                    milestoneDate(passport, waiting.doc.path("output").path("gate").asText("")) ?: end
                else -> null
            }
            states[task.id] = TaskState(
                id = task.id,
                order = doc.path("order").asInt(),
                name = doc.path("name").asText(),
                why = doc.path("why").asText(),
                status = status,
                waitsOn = waiting?.let { "${it.doc.path("order").asInt()} · ${it.doc.path("name").asText()}" }
                    ?: unmet.firstOrNull()?.let { labelOf(it) },
                inputReady = unmet.isEmpty() && waiting == null,
                inputWhy = if (unmet.isEmpty() && waiting == null) "вход готов"
                else (unmet.map { labelOf(it) } + listOfNotNull(waiting?.doc?.path("name")?.asText()))
                    .joinToString("; "),
                steps = steps,
                gaps = gaps,
                artifact = doc.path("output").path("artifact").asText(""),
                gate = doc.path("output").path("gate").asText("").ifBlank { null },
                outputDone = outputDone,
                start = start,
                end = end,
                tight = end != null && !outputDone && tightness(end),
            )
        }
        return states.values.toList()
    }

    /** Окно сжато: до точки осталось меньше порога, а выход не готов. */
    private fun tightness(end: LocalDate): Boolean =
        java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), end) in 0 until TIGHT_DAYS

    private fun milestoneDate(passport: JsonNode, gate: String): LocalDate? {
        if (gate.isBlank()) return null
        val text = passport.path("milestones")
            .firstOrNull { it.path("gate").asText() == gate }
            ?.path("due")?.asText("") ?: return null
        return runCatching { LocalDate.parse(text) }.getOrNull()
    }

    fun toJson(boundary: Boundary, projectId: String): ObjectNode {
        val tasks = of(boundary, projectId)
        // Геометрия ленты — тоже расчёт, и место ему на сервере: клиент
        // рисует полосу по готовым долям, а не делит даты сам.
        val dates = tasks.flatMap { listOfNotNull(it.start, it.end) }
        val from = dates.minOrNull()
        val to = dates.maxOrNull()
        val span = if (from != null && to != null)
            java.time.temporal.ChronoUnit.DAYS.between(from, to).coerceAtLeast(1) else 1L
        val out = mapper.createObjectNode()
        from?.let { out.put("lane_from", it.toString()) }
        to?.let { out.put("lane_to", it.toString()) }
        out.put("tasks", tasks.size)
        out.put("in_progress", tasks.count { it.status == "in_progress" })
        out.put("available", tasks.count { it.status == "available" })
        out.put("waiting", tasks.count { it.status == "waiting" })
        out.put("done", tasks.count { it.status == "done" })
        // «следующий шаг» шапки — верхушка работы: первая незавершённая
        val next = tasks.firstOrNull { it.status == "in_progress" }
            ?: tasks.firstOrNull { it.status == "available" }
            ?: tasks.firstOrNull { it.status != "done" }
        next?.let { t ->
            val step = t.steps.firstOrNull { !it.done }
            val n = out.putObject("next")
            n.put("task", t.id)
            n.put("name", t.name)
            step?.let {
                n.put("step", it.title)
                it.screen?.let { s -> n.put("screen", s) }
                it.kind?.let { k -> n.put("kind", k) }
            }
        }
        val arr = out.putArray("items")
        tasks.forEach { t ->
            val n = arr.addObject()
            n.put("id", t.id)
            n.put("order", t.order)
            n.put("name", t.name)
            n.put("why", t.why)
            n.put("status", t.status)
            t.waitsOn?.let { n.put("waits_on", it) }
            n.put("input_ready", t.inputReady)
            n.put("input_why", t.inputWhy)
            n.put("artifact", t.artifact)
            t.gate?.let { n.put("gate", it) }
            n.put("output_done", t.outputDone)
            t.start?.let { n.put("start", it.toString()) }
            t.end?.let { n.put("end", it.toString()) }
            n.put("tight", t.tight)
            if (from != null && t.end != null) {
                val begin = t.start ?: from
                val offset = java.time.temporal.ChronoUnit.DAYS.between(from, begin)
                val length = java.time.temporal.ChronoUnit.DAYS.between(begin, t.end)
                n.put("lane_offset_pct", (offset * 100.0 / span).coerceIn(0.0, 100.0))
                n.put("lane_width_pct", (length * 100.0 / span).coerceIn(2.0, 100.0))
            }
            val steps = n.putArray("steps")
            t.steps.forEach { s ->
                val sn = steps.addObject()
                sn.put("title", s.title)
                s.hint?.let { sn.put("hint", it) }
                s.screen?.let { sn.put("screen", it) }
                s.kind?.let { sn.put("kind", it) }
                sn.put("done", s.done)
                sn.put("why", s.why)
            }
            val gaps = n.putArray("gaps")
            t.gaps.forEach { gaps.add(it) }
        }
        return out
    }
}
