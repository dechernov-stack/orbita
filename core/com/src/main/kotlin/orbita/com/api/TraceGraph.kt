// Граф трассировки и impact (ADR-046, шип 4 ночи 02→03.09): нужда →
// требование → узел состава → интерфейс → событие верификации → документ.
// Граф — ПРОЕКЦИЯ модели: узлы и рёбра выводятся из объектов и таблицы
// связей при каждом запросе, координат у него нет — раскладку считает
// клиентская библиотека (dagre), и хранить её негде по построению.
//
// Impact (по образцу reqpilot impact.py): фокус → соседи ГРУППАМИ по природе
// (родители · дети · зависимые · противоречия · события верификации ·
// носители · интерфейсы · документы со вставкой · битые ссылки), глубина
// 1–4, кратчайший путь до второго объекта; циклы не зацикливают обход.
//
// Функции (ADR-047) — узлы графа между нуждами и носителями; пока их в
// проекте нет, граф говорит об этом словами, а не рисует пустую колонку.
package orbita.com.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.model.Lifecycle
import orbita.mod.store.ObjectStore
import orbita.mod.store.StoredObject
import orbita.out.DocumentGenerator
import orbita.out.TemplateData

class TraceGraph(private val boundary: Boundary) {
    private val mapper = ObjectMapper()

    data class Node(val id: String, val kind: String, val title: String, val status: String?)
    data class Edge(val from: String, val to: String, val kind: String)

    private class Built(val nodes: LinkedHashMap<String, Node>, val edges: LinkedHashSet<Edge>)

    private val OBJECT_KINDS = mapOf(
        "need" to "need", "service" to "service", "mission_goal" to "goal", "conops" to "conops",
        "requirement" to "requirement", "component" to "node", "interface" to "interface", "evidence" to "evidence",
        "function" to "function", "model_element" to "external",
    )
    private val LINK_KINDS = setOf("trace", "derive", "allocation", "conflict")

    /** Полный граф проекта: объекты, связи, стороны интерфейсов, события верификации, документы. */
    private fun build(projectId: String): Built {
        val current = boundary.objects.listCurrent(projectId).filter { it.status != Lifecycle.Cancelled }
        val nodes = LinkedHashMap<String, Node>()
        val edges = LinkedHashSet<Edge>()
        fun title(o: StoredObject) = o.doc.path("title").asText("").ifBlank { null }
            ?: o.doc.path("name").asText("").ifBlank { null }
            ?: o.doc.path("statement").asText("").ifBlank { null } ?: o.id
        current.forEach { o ->
            OBJECT_KINDS[o.type]?.let { kind -> nodes[o.id] = Node(o.id, kind, title(o), o.status.name) }
        }
        fun missing(id: String) {
            if (id.isNotBlank() && id !in nodes) nodes[id] = Node(id, "missing", "объект $id в модели отсутствует", null)
        }
        // связи из таблицы — те, что несут трассировку, декомпозицию, распределение, противоречие
        boundary.links.list(null, projectId).filter { it.kind in LINK_KINDS }.forEach { l ->
            missing(l.fromId); missing(l.toId)
            edges += Edge(l.fromId, l.toId, l.kind)
        }
        // интерфейс — ребро между двумя сторонами
        current.filter { it.type == "interface" }.forEach { i ->
            i.doc.path("owners").forEach { side ->
                val cm = side.asText("")
                if (cm.isNotBlank()) { missing(cm); edges += Edge(i.id, cm, "side") }
            }
        }
        // события верификации — узлы при требовании; свидетельство — ребро к EV
        current.filter { it.type == "requirement" }.forEach { r ->
            r.doc.path("verification_events").forEach { e ->
                val eid = "${r.id}#${e.path("id").asText("VE")}"
                val label = listOfNotNull(
                    e.path("method").asText("").ifBlank { null }, e.path("phase").asText("").ifBlank { null },
                ).joinToString(" · ").ifBlank { e.path("id").asText("") }
                nodes[eid] = Node(eid, "event", label, e.path("status").asText("").ifBlank { null })
                edges += Edge(eid, r.id, "verifies")
                e.path("evidence_ref").asText("").takeIf { it.isNotBlank() }?.let { ev -> missing(ev); edges += Edge(eid, ev, "evidence") }
            }
            // битые ссылки самого документа: источник, норматив, связи
            listOf(r.doc.path("source").path("doc"), r.doc.path("normative_basis").path("ref"))
                .map { it.asText("") }.filter { it.isNotBlank() }
                .forEach { ref -> if (boundary.objects.current(ref) == null) { missing(ref); edges += Edge(r.id, ref, "source") } }
            r.doc.path("relations").forEach { rel ->
                val ref = rel.path("ref").asText("")
                if (rel.path("kind").asText("") == "depends_on" && ref.isNotBlank()) { missing(ref); edges += Edge(r.id, ref, "depends_on") }
            }
        }
        // документы: шаблон → объекты, вставленные в его разделы (рендер — проекция)
        val model = DocumentModel.model(boundary, projectId)
        val generator = DocumentGenerator(mapper)
        templates().forEach { t ->
            val did = "DOC:${t.code}"
            val rendered = runCatching { generator.render(model, t) }.getOrNull() ?: return@forEach
            val inserted = LinkedHashSet<String>()
            rendered.body.path("sections").forEach { s ->
                s.path("items").forEach { it.path("id").asText("").takeIf { id -> id in nodes }?.let(inserted::add) }
            }
            if (inserted.isNotEmpty()) {
                nodes[did] = Node(did, "document", t.title.ifBlank { t.code }, null)
                inserted.forEach { edges += Edge(it, did, "inserted_in") }
            }
        }
        return Built(nodes, edges)
    }

    private fun templates(): List<TemplateData> =
        boundary.objects.listCurrent(ObjectStore.LIBRARY_PROJECT)
            .filter { it.type == "document_template" && it.status != Lifecycle.Cancelled }
            .groupBy { it.doc.path("code").asText() }.values
            .mapNotNull { same -> same.maxWithOrNull(compareBy({ it.doc.path("edition").asText("").toIntOrNull() ?: 0 }, { it.version.toIntOrNull() ?: 0 })) }
            .map { TemplateData.of(it.doc) }

    private fun adjacency(edges: Collection<Edge>): Map<String, List<Edge>> =
        buildMap<String, MutableList<Edge>> {
            edges.forEach { e ->
                getOrPut(e.from) { mutableListOf() }.add(e)
                getOrPut(e.to) { mutableListOf() }.add(e)
            }
        }

    /** Кратчайший путь по неориентированному графу — обход в ширину, циклы не страшны. */
    private fun shortestPath(adj: Map<String, List<Edge>>, from: String, to: String): List<String> {
        if (from == to) return listOf(from)
        val prev = HashMap<String, String>()
        val queue = ArrayDeque(listOf(from))
        val seen = hashSetOf(from)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (e in adj[cur].orEmpty()) {
                val next = if (e.from == cur) e.to else e.from
                if (!seen.add(next)) continue
                prev[next] = cur
                if (next == to) {
                    val path = mutableListOf(to)
                    var x = to
                    while (x != from) { x = prev.getValue(x); path += x }
                    return path.reversed()
                }
                queue.addLast(next)
            }
        }
        return emptyList()
    }

    fun graph(projectId: String, focus: String?, depth: Int, to: String?): ObjectNode {
        val built = build(projectId)
        val adj = adjacency(built.edges)
        val out = mapper.createObjectNode()
        val keep: Set<String> = if (focus == null || focus !in built.nodes) built.nodes.keys else {
            val d = depth.coerceIn(1, 4)
            val dist = hashMapOf(focus to 0)
            val queue = ArrayDeque(listOf(focus))
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                val cd = dist.getValue(cur)
                if (cd >= d) continue
                // документ — узел-концентратор: в него входят сотни вставок, и
                // окрестность сквозь него — это весь реестр; он показывается как
                // сосед, но обход дальше через него не идёт (фокус на документе — идёт)
                if (cur != focus && built.nodes[cur]?.kind == "document") continue
                adj[cur].orEmpty().forEach { e ->
                    val next = if (e.from == cur) e.to else e.from
                    if (next !in dist) { dist[next] = cd + 1; queue.addLast(next) }
                }
            }
            dist.keys
        }
        val nodesArr = out.putArray("nodes")
        built.nodes.values.filter { it.id in keep }.forEach { n ->
            nodesArr.addObject().put("id", n.id).put("kind", n.kind).put("title", n.title).put("status", n.status)
        }
        val edgesArr = out.putArray("edges")
        built.edges.filter { it.from in keep && it.to in keep }.forEach { e ->
            edgesArr.addObject().put("from", e.from).put("to", e.to).put("kind", e.kind)
        }
        out.put("focus", focus)
        out.put("depth", depth.coerceIn(1, 4))
        if (focus != null && focus !in built.nodes) out.put("focus_note", "объект $focus в модели отсутствует — показан весь граф")
        // impact: группы соседей по природе — только для фокуса
        focus?.takeIf { it in built.nodes }?.let { f ->
            val g = out.putObject("groups")
            fun group(name: String, ids: Collection<String>) { val a = g.putArray(name); ids.distinct().sorted().forEach { a.add(it) } }
            val around = adj[f].orEmpty()
            group("parents", around.filter { it.kind == "derive" && it.to == f }.map { it.from })
            group("children", around.filter { it.kind == "derive" && it.from == f }.map { it.to })
            group("needs", around.filter { it.kind == "trace" && it.to == f }.map { it.from })
            group("dependents", around.filter { it.kind == "depends_on" }.map { if (it.from == f) it.to else it.from })
            group("conflicts", around.filter { it.kind == "conflict" }.map { if (it.from == f) it.to else it.from })
            group("events", around.filter { it.kind == "verifies" && it.to == f }.map { it.from })
            group("carriers", around.filter { it.kind == "allocation" && it.from == f }.map { it.to }.filter { built.nodes[it]?.kind == "node" })
            group("functions", around.filter { it.kind == "allocation" || it.kind == "trace" }.map { if (it.from == f) it.to else it.from }.filter { built.nodes[it]?.kind == "function" })
            group("external", around.filter { it.kind == "allocation" && it.from == f }.map { it.to }.filter { built.nodes[it]?.kind == "external" })
            group("interfaces", around.filter { (it.kind == "allocation" && it.from == f && built.nodes[it.to]?.kind == "interface") || it.kind == "side" }.map { if (it.from == f) it.to else it.from })
            group("documents", around.filter { it.kind == "inserted_in" && it.from == f }.map { it.to })
            group("broken", around.map { if (it.from == f) it.to else it.from }.filter { built.nodes[it]?.kind == "missing" })
        }
        to?.takeIf { it.isNotBlank() && focus != null }?.let { target ->
            val path = shortestPath(adj, focus!!, target)
            val p = out.putArray("path"); path.forEach { p.add(it) }
            if (path.isEmpty()) out.put("path_note", "пути от $focus до $target по связям нет")
        }
        if (built.nodes.values.none { it.kind == "function" }) {
            out.put("functions_note", "функций в проекте нет: заведите их на «Функциях» (из нужд и ConOps) и распределите на узлы")
        }
        out.put("counts_missing", built.nodes.values.count { it.kind == "missing" })
        return out
    }
}
