// Прохождение контрольной точки и возвраты (блок B, ADR-029). Точка
// проходится проверкой, а не кнопкой: пустой перечень незакрытого —
// единственное условие. Эталон — spec/process_backbone.py.
package orbita.com.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.model.CoreType
import orbita.mod.store.ModelViolationException
import orbita.mod.store.StoredObject
import orbita.req.Operations
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Точка не готова: перечень незакрытого и операции, где это чинится. */
class GateNotReadyException(
    val gate: String,
    val issues: List<String>,
    val operations: List<String>,
) : RuntimeException("gate '$gate' is not ready: ${issues.size} issue(s)")

class GatePassing(
    private val boundary: Boundary,
    private val operations: Operations = Operations(),
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    /**
     * Незакрытое до точки (ADR-029 п. 2): статусные и TRL-разрывы — на всех
     * точках; TBD/TBR и разрывы трассировки — с базирования (SRR и далее).
     * Полная верификация точку Формулирования не блокирует — она закрывается
     * в Phase C/D и остаётся сведением отчёта зрелости.
     *
     * Блок C добавил сюда замечания обзора (RFA/RID): незакрытое критическое
     * замечание своей точки блокирует прохождение; comment/question/
     * recommendation — нет. И выпуски комплекта: Д-коды операций точки обязаны
     * иметь выпуск документа своего шаблона (комплект Д1–Д9 / Д1–Д10).
     */
    fun issues(gate: String, projectId: String): List<String> {
        val project = projectOf(projectId)
        val phase = project.doc.path("phase").asText()
        val report = boundary.maturity.build(gate, projectId = projectId)
        val rows = operations.states(phase, boundary.req.snapshotsAt(null, projectId))
        return buildList {
            // выходы операций точки: пустой проект не проходит точку молча —
            // отсутствие материала называется операцией, где он создаётся
            rows.filter { it.operation.gate == gate }.forEach { r ->
                when (r.state) {
                    orbita.req.OperationState.NotStarted -> add(
                        "${r.operation.code}: выход операции не создан " +
                            "(${r.operation.kinds.joinToString()}) — «${r.operation.name}»"
                    )
                    orbita.req.OperationState.InProgress -> add(
                        "${r.operation.code}: выход не достиг статуса " +
                            "${r.operation.requiredStatus} — «${r.operation.name}»"
                    )
                    // NotMeasurable — материал вне системы до блока C (ADR-029 п. 6):
                    // видимый пробел реестра, но не блокировка точки
                    else -> {}
                }
            }
            report.gapsByType.forEach { (type, gaps) ->
                gaps.forEach { g -> add("${g.id}: $type ${g.actual} ниже требуемого ${g.required}") }
            }
            if (gate in BASELINE_GATES) {
                report.openTbd.forEach { add("${it.id}: незакрытый TBD/TBR") }
                report.traceBreaks.forEach { add("$it: требование без входящей нити трассировки") }
            }
            // незакрытые критические замечания обзора точки (блок C)
            boundary.objects.listCurrent(projectId)
                .filter { it.type == "review_item" }
                .map { it.doc }
                .filter {
                    it.path("review_gate").asText() == gate &&
                        it.path("classification").asText() == "critical" &&
                        it.path("status").asText() != "closed"
                }
                .sortedBy { it.path("id").asText() }
                .forEach {
                    add("${it.path("id").asText()}: незакрытое критическое замечание обзора — " +
                        it.path("statement").asText(""))
                }
            // выпуски комплекта точки (блок C): Д-коды операций → шаблоны фазы
            val kit = orbita.out.DocumentKits.kit(phase)
            val issued = boundary.objects.listCurrent(projectId)
                .filter { it.type == "document_issue" }
                .map { it.doc.path("template").asText() }
                .toSet()
            rows.filter { it.operation.gate == gate }
                .flatMap { r -> r.operation.docs }
                .distinct().sorted()
                .forEach { d ->
                    val template = kit[d] ?: return@forEach
                    if (template !in issued) {
                        add("$d: документ не выпущен (шаблон $template — POST /export/documents/$template/issue)")
                    }
                }
        }
    }

    /** Проект обязан существовать и вести перечень вех. */
    private fun projectOf(projectId: String): StoredObject =
        boundary.objects.current(projectId)?.takeIf { it.type == "project" }
            ?: throw NoSuchElementException("проект '$projectId' не найден")

    private fun milestonesOf(project: StoredObject): List<JsonNode> =
        project.doc.path("milestones").toList()

    /**
     * Ближайшая непройденная веха В ГОРИЗОНТЕ ИС; null — горизонт исчерпан.
     * Вехи Phase B–F (PDR, CDR…) — план в едином ряду точек, но ворот к ним
     * нет: ИС ведёт проект до конца Формулирования, дальние точки она
     * показывает, не проводит.
     */
    fun nextGate(projectId: String): String? =
        milestonesOf(projectOf(projectId))
            .firstOrNull {
                !it.path("held").asBoolean(false) &&
                    it.path("gate").asText() in boundary.req.gates.gateNames
            }
            ?.path("gate")?.asText()

    /**
     * Прохождение точки (ADR-029 п. 1, 3, 4): только ближайшая непройденная,
     * только без действующего возврата, только с пустым перечнем незакрытого.
     * Готовность фиксируется решением (Approve) и вехой held.
     */
    fun pass(gate: String, rationale: String, author: String, projectId: String): ObjectNode {
        val project = projectOf(projectId)
        val activeReturn = project.doc.path("return")
        if (activeReturn.isObject) {
            throw ModelViolationException(
                "ADR-029: действует возврат от точки ${activeReturn.path("gate").asText()} " +
                    "(${activeReturn.path("reason").asText()}) — сначала снимите его " +
                    "(POST /gates/return/resolve)"
            )
        }
        if (gate !in boundary.req.gates.gateNames) {
            throw ModelViolationException(
                "ADR-029: точка '$gate' за горизонтом Формулирования — ИС показывает её " +
                    "в плане, но не проводит (ворот к ней нет)"
            )
        }
        val next = nextGate(projectId)
            ?: throw ModelViolationException("ADR-029: все вехи проекта $projectId в горизонте ИС уже пройдены")
        if (gate != next) {
            throw ModelViolationException(
                "ADR-029: точки проходятся по порядку вех — ближайшая непройденная '$next', не '$gate'"
            )
        }
        val blocking = issues(gate, projectId)
        if (blocking.isNotEmpty()) {
            throw GateNotReadyException(
                gate, blocking,
                operations.ofGate(gate)
                    .filter { it.phase == project.doc.path("phase").asText() }
                    .map { "${it.code} — ${it.name}" },
            )
        }
        require(rationale.isNotBlank()) {
            "ADR-029: прохождение фиксируется решением — нужно основание (rationale)"
        }

        // решение — состав рассмотрения KDP (§7.3 БП-PPA, §7.4 БП-PA)
        val decision = mapper.createObjectNode()
        decision.put("question", "Прохождение точки $gate")
        val alts = decision.putArray("alternatives")
        listOf("Approve", "Return", "Terminate/Defer").forEach { alts.addObject().put("name", it) }
        decision.put("status", "decided")
        decision.put("selected", "Approve")
        decision.put("rationale", rationale)
        val stored = boundary.editing.create(CoreType.Decision, decision, author, projectId)

        // веха held — правка объекта проекта тем же рабочим слоем, с автором
        val milestones = project.doc.path("milestones").deepCopy<JsonNode>()
        milestones.forEach { m ->
            if (m.path("gate").asText() == gate) {
                (m as ObjectNode).put("held", true)
                m.put("held_at", OffsetDateTime.now(ZoneOffset.UTC).toString())
            }
        }
        val changes = mapper.createObjectNode()
        changes.set<ObjectNode>("milestones", milestones)
        // KDP A — вход в Phase A (§1.4 БП-PPA): решение «Approve» переводит
        // проект в следующую фазу, операции и комплекты меняются вместе с ней
        if (gate == "KDP-A") changes.put("phase", "phase_a")
        // Паспорт может быть базирован — и это не преграда: прохождение точки
        // само есть процедура с основанием (TZ-COM-003), основание — решение
        boundary.editing.update(
            CoreType.Project, projectId, changes, project.version, author,
            changeRef = "${stored.id}: прохождение точки $gate",
        )

        val out = mapper.createObjectNode()
        out.put("passed", true)
        out.put("gate", gate)
        out.put("decision", stored.id)
        out.put("next_gate", nextGate(projectId))
        return out
    }

    /**
     * Возврат (ADR-029 п. 5): состояние проекта, а не пометка в протоколе.
     * Цели — только из §5.1 своей фазы; фиксируется решением Return.
     */
    fun requestReturn(
        gate: String,
        to: List<String>,
        reason: String,
        author: String,
        projectId: String,
    ): ObjectNode {
        val project = projectOf(projectId)
        require(reason.isNotBlank()) { "ADR-029: возврат без причины не записывается" }
        val phase = project.doc.path("phase").asText()
        val allowed = operations.returnTargets(phase, gate)
        require(allowed.isNotEmpty()) { "ADR-029: точка '$gate' в фазе '$phase' возвратов не имеет" }
        val targets = to.ifEmpty { allowed }
        val illegal = targets.filterNot { it in allowed }
        require(illegal.isEmpty()) {
            "ADR-029 (§5.1): недопустимые цели возврата $illegal — разрешено $allowed"
        }

        val decision = mapper.createObjectNode()
        decision.put("question", "Заключение точки $gate")
        val alts = decision.putArray("alternatives")
        listOf("Approve", "Return", "Terminate/Defer").forEach { alts.addObject().put("name", it) }
        decision.put("status", "decided")
        decision.put("selected", "Return")
        decision.put("rationale", reason)
        val stored = boundary.editing.create(CoreType.Decision, decision, author, projectId)

        val ret = mapper.createObjectNode()
        ret.put("gate", gate)
        ret.putArray("to").also { arr -> targets.forEach(arr::add) }
        ret.put("reason", reason)
        ret.put("decided_by", author)
        ret.put("at", OffsetDateTime.now(ZoneOffset.UTC).toString())
        val changes = mapper.createObjectNode()
        changes.set<ObjectNode>("return", ret)
        boundary.editing.update(
            CoreType.Project, projectId, changes, project.version, author,
            changeRef = "${stored.id}: возврат от точки $gate",
        )

        val out = mapper.createObjectNode()
        out.put("returned", true)
        out.put("gate", gate)
        out.put("decision", stored.id)
        out.putArray("to").also { arr -> targets.forEach(arr::add) }
        return out
    }

    /** Снятие возврата — с автором и основанием; без него точки не проходятся. */
    fun resolveReturn(note: String, author: String, projectId: String): ObjectNode {
        val project = projectOf(projectId)
        val active = project.doc.path("return")
        if (!active.isObject) {
            throw ModelViolationException("ADR-029: действующего возврата нет — снимать нечего")
        }
        require(note.isNotBlank()) { "ADR-029: снятие возврата требует основания (note)" }
        val changes = mapper.createObjectNode()
        changes.putNull("return")
        boundary.editing.update(
            CoreType.Project, projectId, changes, project.version, author,
            changeRef = "снятие возврата: $note",
        )
        val out = mapper.createObjectNode()
        out.put("resolved", true)
        out.put("gate", active.path("gate").asText())
        out.put("note", note)
        return out
    }

    /** Состояние операций фазы проекта (ADR-029 п. 6) с целями возврата. */
    fun operationStates(projectId: String): ObjectNode {
        val project = projectOf(projectId)
        val phase = project.doc.path("phase").asText()
        val returnedTo = project.doc.path("return").path("to").map { it.asText() }.toSet()
        val rows = operations.states(
            phase,
            boundary.req.snapshotsAt(null, projectId),
            returnedTo,
        )
        val out = mapper.createObjectNode()
        out.put("project", projectId)
        out.put("phase", phase)
        out.put("next_gate", nextGate(projectId))
        val arr = out.putArray("operations")
        rows.forEach { r ->
            val n = arr.addObject()
            n.put("code", r.operation.code)
            n.put("name", r.operation.name)
            n.put("executor", r.operation.executor)
            n.put("gate", r.operation.gate)
            n.put("required_status", r.operation.requiredStatus)
            n.put("state", r.state.name)
            n.put("objects", r.objects)
            r.operation.screen?.let { n.put("screen", it) }
            if (r.returnedTo) n.put("returned_to", true)
            r.operation.docs.takeIf { it.isNotEmpty() }?.let { docs ->
                n.putArray("docs").also { a -> docs.forEach(a::add) }
                // операция про документ обязана вести к СВОЕМУ шаблону, а не
                // к первому попавшемуся на экране «Документы» (находка
                // прогона: О11 «План проекта» открывал спецификацию)
                val kit = orbita.out.DocumentKits.kit(phase)
                val templates = docs.mapNotNull { kit[it] }
                if (templates.isNotEmpty()) {
                    n.putArray("templates").also { a -> templates.forEach(a::add) }
                }
            }
        }
        return out
    }

    private companion object {
        /** Точки базирования: TBD/TBR и трассировка блокируют с SRR (§7.1 БП-PA). */
        val BASELINE_GATES = setOf("SRR", "SDR", "KDP-B")
    }
}
