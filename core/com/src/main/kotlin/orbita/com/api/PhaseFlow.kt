// Круг 4 «Работы»: схема — карта потока фазы.
//
// Диагноз владельца: список = что, лента = когда, рамка = как делать. «Как
// течёт» не показывает никто: артефакты движутся между задачами к точкам
// невидимо. Регламент — это поток (О → Д → КТ), а на экране потока нет.
//
// Схема — ВЫЧИСЛЕННАЯ ПРОЕКЦИЯ, не рисунок: узлы-задачи ярусами зависимостей
// (те же ярусы, что делят ленту), рёбра — артефакты именами, точки — ромбами
// с процентом готовности из той же готовности к точке. Координаты считает
// сервер и не хранит никто: редактора схемы, ручных координат и «сохранить
// раскладку» здесь нет по построению — изменилась зависимость на полке,
// схема перестроилась сама.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.store.StoredObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

object PhaseFlow {

    private val mapper = ObjectMapper()

    // Геометрия — здесь: клиент рисует по готовым координатам, своих не считает
    private const val NODE_W = 210.0
    private const val NODE_H = 86.0
    private const val GATE_W = 104.0
    private const val GATE_H = 104.0
    private const val CLOUD_W = 156.0
    private const val CLOUD_H = 74.0
    private const val COL_GAP = 78.0
    private const val ROW_GAP = 20.0
    private const val PAD = 18.0

    /** Движение «за неделю»: узел подсвечивается фильтром живости. */
    private const val RECENT_DAYS = 7L

    private data class Column(val kind: String, val items: List<String>, val w: Double, val h: Double)

    private data class Edge(
        val from: String,
        val to: String,
        val label: String,
        val full: String,
        val code: String?,
        val ready: Boolean,
        val kind: String,
        /** Круг 6: тип связи с полки — FS · SS · FF · INPUT. */
        val link: String?,
    )

    fun toJson(boundary: Boundary, projectId: String): ObjectNode {
        val out = mapper.createObjectNode()
        val passport = boundary.objects.current(projectId)?.doc ?: mapper.createObjectNode()
        val phase = passport.path("phase").asText("")
        out.put("phase", phase)
        out.put("phase_label", phaseLabel(phase))

        val tasks = PhaseWork.of(boundary, projectId)
        if (tasks.isEmpty()) {
            // пустота объясняет себя: схема рисуется по задачам полки
            out.put("empty_why", "задач фазы на полке нет — поток рисовать не по чему")
            return out
        }
        val byId = tasks.associateBy { it.id }
        val own = boundary.objects.listCurrent(projectId).filter { it.status.name != "Cancelled" }

        // порядок точек — из паспорта: вехи идут в порядке прохождения
        val milestoneOrder = passport.path("milestones").map { it.path("gate").asText() }
        val gates = tasks.mapNotNull { it.gate }.distinct()
            .sortedBy { g -> milestoneOrder.indexOf(g).let { if (it < 0) Int.MAX_VALUE else it } }
        val lastTierOf = gates.associateWith { g -> tasks.filter { it.gate == g }.maxOf { it.tier } }

        // ---- колонки: ярус за ярусом, точка — сразу за своим последним ярусом
        val columns = mutableListOf<Column>()
        tasks.map { it.tier }.distinct().sorted().forEach { tier ->
            columns += Column("task", tasks.filter { it.tier == tier }.sortedBy { it.order }.map { it.id }, NODE_W, NODE_H)
            gates.filter { lastTierOf[it] == tier }.forEach { g ->
                columns += Column("gate", listOf(g), GATE_W, GATE_H)
            }
        }
        // точка без задач этой фазы — в конец, чтобы не пропала из потока
        gates.filterNot { g -> columns.any { it.kind == "gate" && it.items.first() == g } }
            .forEach { columns += Column("gate", listOf(it), GATE_W, GATE_H) }

        // за воротами — следующая фаза свёрнутым облаком: ИС ведёт проект до
        // конца Формулирования, дальше показывает, а не проводит
        val ops = orbita.req.Operations()
        val lastIdx = gates.lastOrNull()?.let { milestoneOrder.indexOf(it) } ?: -1
        val ahead = if (lastIdx >= 0) milestoneOrder.drop(lastIdx + 1) else emptyList()
        val next = ahead.firstNotNullOfOrNull { g ->
            ops.phaseOfGate(g)?.takeIf { it != phase }?.let { it to g }
        }
        if (next != null) columns += Column("cloud", listOf("cloud"), CLOUD_W, CLOUD_H)

        // ---- рёбра: задача → артефакт → задача либо точка
        val edges = mutableListOf<Edge>()
        tasks.forEach { t ->
            t.dependsOn.forEach { dep ->
                val pred = byId[dep.task] ?: return@forEach
                edges += Edge(
                    pred.id, t.id, shortArtifact(pred.artifact), pred.artifact,
                    pred.documentCode, pred.outputDone, "artifact", dep.type,
                )
            }
            t.gate?.let { g ->
                edges += Edge(
                    t.id, g, shortArtifact(t.artifact), t.artifact, t.documentCode,
                    t.outputDone, "gate", null,
                )
            }
        }
        if (next != null && gates.isNotEmpty()) {
            edges += Edge(gates.last(), "cloud", "", "", null, false, "cloud", null)
        }

        val colOf = HashMap<String, Int>()
        columns.forEachIndexed { i, c -> c.items.forEach { colOf[it] = i } }

        // дальние рёбра уходят верхним каналом — иначе кривая режет чужие узлы
        val reserve = edges.maxOfOrNull { 0.75 * lift(span(it, colOf)) } ?: 0.0

        val heights = columns.map { c -> c.items.size * c.h + (c.items.size - 1) * ROW_GAP }
        val tallest = heights.max()
        val top = PAD + reserve + 14.0
        val center = top + tallest / 2

        val box = HashMap<String, DoubleArray>()
        var x = PAD
        columns.forEachIndexed { i, c ->
            var y = center - heights[i] / 2
            c.items.forEach { id ->
                box[id] = doubleArrayOf(x, y, c.w, c.h)
                y += c.h + ROW_GAP
            }
            x += c.w + COL_GAP
        }
        out.put("width", r(x - COL_GAP + PAD))
        out.put("height", r(top + tallest + PAD))

        // ---- узлы
        val nodes = out.putArray("nodes")
        columns.forEachIndexed { i, c ->
            c.items.forEach { id ->
                val g = box.getValue(id)
                when (c.kind) {
                    "task" -> taskNode(nodes, byId.getValue(id), g, own, boundary)
                    "gate" -> gateNode(nodes, id, g, boundary, projectId, passport)
                    else -> cloudNode(nodes, g, next!!.first, next.second)
                }
            }
        }

        // ---- рёбра геометрией: путь, подпись, стрелка — всё готовым
        val arr = out.putArray("edges")
        edges.forEach { e ->
            val from = box[e.from] ?: return@forEach
            val to = box[e.to] ?: return@forEach
            val x1 = from[0] + from[2]
            val y1 = from[1] + from[3] / 2
            val x2 = to[0]
            val y2 = to[1] + to[3] / 2
            val h = lift(span(e, colOf))
            val dx = COL_GAP * 0.6
            val n = arr.addObject()
            n.put("from", e.from)
            n.put("to", e.to)
            n.put("kind", e.kind)
            n.put("ready", e.ready)
            e.link?.let {
                n.put("link", it)
                n.put("link_words", PhaseWork.linkWords(it, "предшественника"))
            }
            if (e.label.isNotBlank()) {
                n.put("label", e.label)
                n.put("full", e.full)
                e.code?.let { n.put("document_code", it) }
                // Подпись — точкой на самой кривой. У рёбер к точке подписи
                // ставятся БЛИЖЕ К ИСТОКУ (t=¼): к воротам сходятся выходы
                // всех параллельных задач, и в середине их надписи легли бы
                // друг на друга. У остальных — середина.
                if (e.kind == "gate") {
                    n.put("label_x", r(0.84375 * x1 + 0.28125 * dx + 0.15625 * x2))
                    n.put("label_y", r(0.84375 * y1 + 0.15625 * y2 - 0.5625 * h - 6))
                } else {
                    n.put("label_x", r((x1 + x2) / 2))
                    n.put("label_y", r((y1 + y2) / 2 - 0.75 * h - 6))
                }
            }
            n.put(
                "path",
                "M ${r(x1)} ${r(y1)} C ${r(x1 + dx)} ${r(y1 - h)}, " +
                    "${r(x2 - dx)} ${r(y2 - h)}, ${r(x2)} ${r(y2)}",
            )
        }
        return out
    }

    /** Ярусов между колонками: соседние — прямая линия, дальние — дугой. */
    private fun span(e: Edge, colOf: Map<String, Int>): Int =
        (colOf[e.to] ?: 0) - (colOf[e.from] ?: 0)

    private fun lift(span: Int): Double =
        if (span <= 1) 0.0 else minOf(40.0 + 12.0 * (span - 1), 88.0)

    private fun taskNode(
        nodes: ArrayNode,
        t: PhaseWork.TaskState,
        g: DoubleArray,
        own: List<StoredObject>,
        boundary: Boundary,
    ) {
        val n = nodes.addObject()
        n.put("kind", "task")
        n.put("id", t.id)
        n.put("order", t.order)
        n.put("name", t.name)
        n.put("status", t.status)
        n.put("why", t.why)
        n.put("gaps", t.gaps.size)
        n.put("steps_done", t.steps.count { it.done })
        n.put("steps_total", t.steps.size)
        n.put("artifact", t.artifact)
        t.gate?.let { n.put("gate", it) }
        t.waitsOn?.let { n.put("waits_on", it) }
        geometry(n, g)

        // След работы: те же виды и коды, что названы условиями задачи.
        // Служебные учётки на схему не выходят (ServiceAuthors — один список
        // служебности на систему): «ci-runner · вчера» о движении не говорит.
        val touched = own.filter { o ->
            o.type in t.touchesTypes ||
                (o.type == "document_issue" && o.doc.path("code").asText("") in t.touchesCodes)
        }.filterNot { orbita.req.ServiceAuthors.isService(it.createdBy) }
        val people = n.putArray("people")
        touched.map { it.createdBy }.distinct().take(3).forEach { author ->
            val name = boundary.humanAuthor(author)
            people.addObject().put("name", name).put("initials", initials(name))
        }
        val last = touched.maxByOrNull { it.validFrom }
        if (last != null) {
            val name = boundary.humanAuthor(last.createdBy)
            n.putObject("activity")
                .put("at", last.validFrom.toString())
                .put("author", name)
                .put("initials", initials(name))
                .put(
                    "what",
                    last.changeRef?.ifBlank { null }
                        ?: if (last.version == "1" && last.supersedes == null) "создан ${last.id}"
                        else "правка ${last.id}",
                )
            n.put(
                "recent",
                ChronoUnit.DAYS.between(last.validFrom.toLocalDate(), LocalDate.now()) < RECENT_DAYS,
            )
        } else {
            n.put("recent", false)
        }
    }

    private fun gateNode(
        nodes: ArrayNode,
        gate: String,
        g: DoubleArray,
        boundary: Boundary,
        projectId: String,
        passport: com.fasterxml.jackson.databind.JsonNode,
    ) {
        val n = nodes.addObject()
        n.put("kind", "gate")
        n.put("id", gate)
        n.put("gate", gate)
        n.put("label", boundary.req.gateLabel(gate))
        geometry(n, g)
        // ромб рисуется по готовым вершинам: клиент не считает и здесь
        n.put(
            "points",
            "${r(g[0] + g[2] / 2)},${r(g[1])} ${r(g[0] + g[2])},${r(g[1] + g[3] / 2)} " +
                "${r(g[0] + g[2] / 2)},${r(g[1] + g[3])} ${r(g[0])},${r(g[1] + g[3] / 2)}",
        )
        passport.path("milestones").firstOrNull { it.path("gate").asText() == gate }
            ?.path("due")?.asText("")?.ifBlank { null }?.let { n.put("due", it) }
        // процент — ИЗ ГОТОВНОСТИ к точке, не второй счёт: доля закрытых
        // среди применимых проверок, и числа названы рядом
        val checks = runCatching { boundary.gatePassing.readiness(gate, projectId) }.getOrDefault(emptyList())
        val applicable = checks.count { it.state != "na" }
        val closed = checks.count { it.state == "closed" }
        if (applicable > 0) {
            n.put("pct", kotlin.math.round(closed * 100.0 / applicable))
            n.put("note", "$closed из $applicable проверок закрыто")
            n.put("blocking_open", checks.count { it.state == "open" && it.blocking })
        } else {
            n.put("note", "проверок к точке не заведено")
        }
    }

    private fun cloudNode(nodes: ArrayNode, g: DoubleArray, phase: String, gate: String) {
        val n = nodes.addObject()
        n.put("kind", "cloud")
        n.put("id", "cloud")
        n.put("name", phaseLabel(phase))
        n.put("note", "за точкой $gate: ИС ведёт проект до конца Формулирования, дальше — показывает")
        geometry(n, g)
    }

    private fun geometry(n: ObjectNode, g: DoubleArray) {
        n.put("x", r(g[0]))
        n.put("y", r(g[1]))
        n.put("w", r(g[2]))
        n.put("h", r(g[3]))
    }

    /**
     * Подпись ребра — артефакт коротко: «Д2 · SEMP» → «Д2 SEMP». Полное имя
     * приходит рядом и стоит в подсказке: сокращение не должно ничего терять.
     */
    private fun shortArtifact(artifact: String): String {
        val parts = artifact.split("·").map { it.trim() }.filter { it.isNotEmpty() }
        val short = if (parts.size >= 2) "${parts[0]} ${parts[1].split(" ", ",").first()}"
        else artifact.split(" ").take(2).joinToString(" ")
        // длинная подпись легла бы поверх соседнего узла: обрезаем, полное имя
        // едет рядом и стоит в подсказке
        return if (short.length <= 18) short else short.take(17).trimEnd() + "…"
    }

    /** Инициалы для аватарки: «Чернов Дмитрий» → «ЧД». */
    private fun initials(name: String): String =
        name.split(" ", ".").filter { it.isNotBlank() }.take(2)
            .joinToString("") { it.first().uppercase() }

    private fun phaseLabel(phase: String): String = when (phase) {
        "pre_phase_a" -> "Pre-Phase A"
        "phase_a" -> "Phase A"
        "phase_b" -> "Phase B"
        else -> phase
    }

    /** Координаты — с одним знаком: схема не хранится, а вес JSON лишний. */
    private fun r(v: Double): Double = kotlin.math.round(v * 10) / 10.0
}
