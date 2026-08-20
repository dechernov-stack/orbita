// Дерево требований для отображения (STEP-6 §1.4).
// Эталон spec/presentation_semantics.py, один в один.
//
// Дерево строится ИЗ СВЯЗЕЙ и отдельно не хранится: второе представление той же
// подчинённости неизбежно разошлось бы с первым. Связь trace деревом не является —
// у требования может быть несколько источников, и это не подчинённость.
package orbita.out

import orbita.mod.store.Link

data class RequirementTree(
    val roots: List<String>,
    val children: Map<String, List<String>>,
) {
    /** Глубина узла от корня; корень — ноль. */
    fun depthOf(node: String): Int {
        val parent = children.entries.firstOrNull { node in it.value }?.key ?: return 0
        return depthOf(parent) + 1
    }
}

fun buildTree(nodes: List<String>, links: List<Link>, kind: String = "derive"): RequirementTree {
    val edges = links.filter { it.kind == kind }
    val children = edges.groupBy({ it.fromId }, { it.toId }).mapValues { it.value.sorted() }
    val hasParent = edges.map { it.toId }.toSet()
    return RequirementTree(
        roots = nodes.filterNot { it in hasParent }.sorted(),
        children = children,
    )
}

/** Цикл в дереве требований: подчинённость, замкнутая сама на себя. */
fun treeCycle(links: List<Link>, kind: String = "derive"): Boolean {
    val adjacency = links.filter { it.kind == kind }.groupBy({ it.fromId }, { it.toId })
    val visited = mutableSetOf<String>()
    val onStack = mutableSetOf<String>()

    fun walk(node: String): Boolean {
        if (node in onStack) return true
        if (node in visited) return false
        visited += node
        onStack += node
        adjacency[node].orEmpty().forEach { if (walk(it)) return true }
        onStack -= node
        return false
    }
    return adjacency.keys.toList().any { walk(it) }
}
