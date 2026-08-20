// Применение предложения как diff (TZ-AI-006). Эталон spec/ai_semantics.py.
//
// Ручного повторного ввода принятых значений не требуется ни на одном шаге:
// инженер выбирает поля, значения переносятся сами.
package orbita.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

enum class DiffOp(val code: String) {
    Add("add"),         // поля не было — предложено новое
    Change("change"),   // значение отличается
    Keep("keep"),       // в предложении поля нет — текущее сохраняется
    Same("same"),       // значения совпадают
}

data class DiffEntry(val op: DiffOp, val from: JsonNode? = null, val to: JsonNode? = null) {
    /** Значение, которое останется в объекте, если поле НЕ выбрано. */
    val current: JsonNode? get() = from
}

/** Построчный diff к текущему состоянию: add, change, keep, same. */
fun makeDiff(current: JsonNode, proposed: JsonNode): Map<String, DiffEntry> {
    val keys = (current.fieldNames().asSequence() + proposed.fieldNames().asSequence()).toSortedSet()
    return keys.associateWith { k ->
        val cur = current.get(k)
        val prop = proposed.get(k)
        when {
            cur == null -> DiffEntry(DiffOp.Add, to = prop)
            prop == null -> DiffEntry(DiffOp.Keep, from = cur)
            cur != prop -> DiffEntry(DiffOp.Change, from = cur, to = prop)
            else -> DiffEntry(DiffOp.Same, from = cur, to = prop)
        }
    }
}

/**
 * Применяются ТОЛЬКО выбранные поля; остальное не трогается.
 * Пустой выбор не меняет ничего.
 */
fun applyDiff(
    current: JsonNode,
    diff: Map<String, DiffEntry>,
    selected: Set<String>,
    mapper: ObjectMapper = ObjectMapper(),
): ObjectNode {
    val out = current.deepCopy<ObjectNode>()
    diff.forEach { (k, entry) ->
        if (k in selected && entry.op in setOf(DiffOp.Add, DiffOp.Change)) {
            out.set<ObjectNode>(k, entry.to)
        }
    }
    return out
}

/** Поля, по которым инженеру есть что решать: добавления и изменения. */
fun actionableFields(diff: Map<String, DiffEntry>): Set<String> =
    diff.filterValues { it.op == DiffOp.Add || it.op == DiffOp.Change }.keys

fun diffToJson(diff: Map<String, DiffEntry>, mapper: ObjectMapper = ObjectMapper()): ObjectNode {
    val root = mapper.createObjectNode()
    diff.forEach { (k, e) ->
        val n = root.putObject(k)
        n.put("op", e.op.code)
        e.from?.let { n.set<ObjectNode>(if (e.op == DiffOp.Change) "from" else "value", it) }
        if (e.op == DiffOp.Add || e.op == DiffOp.Change) e.to?.let { n.set<ObjectNode>("to", it) }
    }
    return root
}
