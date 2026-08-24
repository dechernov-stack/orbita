// Согласованность дерева требований и дерева компонентов (CR-003/ADR-019).
// Эталон spec/traceability_semantics.py, один в один.
//
// Потомок требования распределяется на тот же элемент либо на его потомка:
// иначе декомпозиция уходит за пределы области родителя. Интерфейс — не элемент
// (ловушка 5): требование к стыку имеет две стороны ответственности.
// Свёртка бюджета применяется только к РАСПРЕДЕЛЁННЫМ потомкам: производное
// требование рождается из проектного решения и долей бюджета не является (ловушка 4).
package orbita.req

import com.fasterxml.jackson.databind.JsonNode
import orbita.mod.store.Link

/** Узел дерева изделия: элемент с родителем либо интерфейс с двумя сторонами. */
data class ProductNode(
    val id: String,
    val kind: String,
    val parent: String? = null,
    val owners: List<String> = emptyList(),
)

/** Все потомки элемента в дереве изделия. */
fun descendantsOf(nodes: Map<String, ProductNode>, root: String): Set<String> {
    val out = mutableSetOf<String>()
    val stack = ArrayDeque(listOf(root))
    while (stack.isNotEmpty()) {
        val current = stack.removeLast()
        nodes.values.filter { it.parent == current && it.id !in out }.forEach {
            out += it.id
            stack.addLast(it.id)
        }
    }
    return out
}

private fun allocatedComponents(req: JsonNode): List<String> =
    req.path("allocated_to").mapNotNull { it.path("component").asText("").ifBlank { null } }

/** (согласовано, причина расхождения). */
fun allocationConsistent(
    parentReq: JsonNode,
    childReq: JsonNode,
    nodes: Map<String, ProductNode>,
): Pair<Boolean, String?> {
    val parents = allocatedComponents(parentReq)
    val children = allocatedComponents(childReq)
    if (parents.isEmpty() || children.isEmpty()) return true to null
    val allowed = parents.flatMap { listOf(it) + descendantsOf(nodes, it) }.toSet()
    val stray = children.filterNot { it in allowed }
    return if (stray.isEmpty()) true to null
    else false to "потомок распределён вне области родителя: $stray"
}

/** Интерфейсное требование распределяется на интерфейс с двумя сторонами. */
fun interfaceAllocationValid(req: JsonNode, nodes: Map<String, ProductNode>): Pair<Boolean, String?> {
    if (req.path("category").asText("") != "interface") return true to null
    val targets = req.path("allocated_to")
    val interfaces = targets.mapNotNull { it.path("interface").asText("").ifBlank { null } }
    if (interfaces.isEmpty()) {
        // Два разных положения — два разных сообщения (находка второго
        // захода: «распределено на элемент» про требование БЕЗ распределения
        // посылало инженера чинить не то)
        return if (targets.isEmpty || targets.size() == 0) {
            false to "интерфейсное требование не распределено: укажите интерфейс в allocated_to"
        } else {
            false to "интерфейсное требование распределено на элемент, а не на интерфейс"
        }
    }
    interfaces.forEach { id ->
        val owners = nodes[id]?.owners ?: emptyList()
        if (owners.size != 2) return false to "интерфейс $id не имеет двух сторон"
    }
    return true to null
}

/** Спецификация элемента — множество требований, распределённых на него (представление). */
fun componentSpecification(requirements: List<JsonNode>, componentId: String): List<String> =
    requirements
        .filter { r -> r.path("allocated_to").any { it.path("component").asText("") == componentId } }
        .map { it.path("id").asText() }
        .sorted()

/** Потомки, участвующие в свёртке бюджета: только связи derive с видом allocated. */
fun rollupChildIds(parentId: String, links: List<Link>): List<String> =
    links.filter { it.fromId == parentId && it.kind == "derive" && it.derivationKind == "allocated" }
        .map { it.toId }
