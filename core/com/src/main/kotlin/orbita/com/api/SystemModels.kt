// Модели системы (ADR-050): модель — не файл, а ответ на инженерный вопрос.
// Вход модели — параметр узла дерева состава (анкета Ф-06) или выход другой
// модели; ответ — выход С ДАТОЙ. «Файл есть» ответом не считается: пустые
// выходы дают разрыв «модель не дала ответа», незаданный параметр входа —
// разрыв, ведущий к анкете узла, а не к экрану модели.
//
// Линки М2а–г и потоки М3а–е живут ЧАСТЯМИ записи и привязаны к интерфейсу
// дерева (ребро между узлами): модель читает параметры обоих концов, а
// интерфейсные требования распределяются на тот же интерфейс.
package orbita.com.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.model.CoreType
import orbita.mod.model.Lifecycle
import orbita.mod.store.StoredObject

class SystemModels(private val boundary: Boundary) {
    private val mapper = ObjectMapper()

    data class ModelGap(val model: String, val code: String, val what: String, val place: String)

    /** Порядок — по номеру кода: «М10» после «М9», иначе список читается как М1, М10, М11. */
    private fun codeOrder(code: String): Pair<Int, String> =
        Regex("\\d+").find(code)?.value?.toIntOrNull()?.let { it to code } ?: (Int.MAX_VALUE to code)

    private fun records(projectId: String?): List<StoredObject> =
        boundary.objects.listCurrent(projectId)
            .filter { it.type == CoreType.SystemModel.dbType && it.status != Lifecycle.Cancelled }
            .sortedWith(compareBy({ codeOrder(it.doc.path("code").asText(it.id)).first }, { it.doc.path("code").asText(it.id) }))

    /** Ответ дан, когда есть выход С ДАТОЙ: без даты это намерение, не результат. */
    private fun answered(node: JsonNode): Boolean =
        node.path("outputs").any { it.path("at").asText("").isNotBlank() }

    private fun proxyAnswer(node: JsonNode): Boolean =
        node.path("outputs").any { it.path("at").asText("").isNotBlank() && it.path("proxy").asBoolean(false) }

    /** Части (линки и потоки) наравне с самой записью: у каждой свой вопрос и своя точка. */
    private fun partsOf(record: StoredObject): List<JsonNode> = record.doc.path("parts").toList()

    /**
     * Разрывы моделей к названной точке: не давшая ответа модель и незаданный
     * вход. Место починки разное — экран моделей против анкеты узла.
     */
    fun gaps(projectId: String, gate: String?): List<ModelGap> {
        val nodes = boundary.objects.listCurrent(projectId)
            .filter { it.type == CoreType.Component.dbType && it.status != Lifecycle.Cancelled }
            .associateBy { it.id }
        val out = mutableListOf<ModelGap>()
        records(projectId).forEach { record ->
            val code = record.doc.path("code").asText(record.id)
            val units = listOf(record.doc) + partsOf(record)
            units.forEach { unit ->
                val unitCode = unit.path("code").asText(code)
                val due = unit.path("due_gate").asText("").ifBlank { record.doc.path("due_gate").asText("") }
                val hasParts = unit === record.doc && partsOf(record).isNotEmpty()
                // у записи с частями ответ даёт часть: спрашивать с «зонтика»
                // отдельный выход значило бы требовать ответ дважды
                if (!hasParts && gate != null && due == gate && !answered(unit)) {
                    out += ModelGap(record.id, unitCode, "модель не дала ответа к $due", "models")
                }
                unit.path("inputs").forEach { input ->
                    val cm = input.path("node").asText("")
                    val param = input.path("param").asText("")
                    if (cm.isBlank() || param.isBlank()) return@forEach
                    val node = nodes[cm] ?: return@forEach
                    val filled = node.doc.path("parameters").any { it.path("name").asText("") == param }
                    if (!filled) {
                        out += ModelGap(
                            record.id, unitCode,
                            "вход модели не задан: параметр «$param» узла ${node.id}", "datarequests",
                        )
                    }
                }
            }
        }
        return out
    }

    /** Экран моделей: запись, её части, состояние ответа и разрывы — считает сервер. */
    fun view(projectId: String, gate: String?): ObjectNode {
        val out = mapper.createObjectNode()
        val nodes = boundary.objects.listCurrent(projectId)
            .filter { it.type == CoreType.Component.dbType }.associateBy { it.id }
        val interfaces = boundary.objects.listCurrent(projectId)
            .filter { it.type == CoreType.Interface.dbType }.associateBy { it.id }
        val gapsByModel = gaps(projectId, gate).groupBy { it.model }
        val rows = out.putArray("models")
        records(projectId).forEach { record ->
            val d = record.doc
            val row = rows.addObject()
                .put("id", record.id).put("code", d.path("code").asText(""))
                .put("name", d.path("name").asText("")).put("question", d.path("question").asText(""))
                .put("status", d.path("status").asText("not_built"))
                .put("tool", d.path("tool").asText("")).put("due_gate", d.path("due_gate").asText(""))
                .put("answered", answered(d) || (partsOf(record).isNotEmpty() && partsOf(record).all { answered(it) }))
                .put("proxy_answer", proxyAnswer(d) || partsOf(record).any { proxyAnswer(it) })
            d.path("interface_ref").asText("").takeIf { it.isNotBlank() }?.let { iface ->
                row.put("interface", iface).put("interface_name", interfaces[iface]?.doc?.path("name")?.asText("") ?: "")
            }
            val inputs = row.putArray("inputs")
            fun putInputs(source: JsonNode, target: com.fasterxml.jackson.databind.node.ArrayNode) {
                source.path("inputs").forEach { input ->
                    val n = target.addObject()
                    input.path("node").asText("").takeIf { it.isNotBlank() }?.let { cm ->
                        n.put("node", cm).put("node_name", nodes[cm]?.doc?.path("name")?.asText("") ?: "узла нет")
                    }
                    input.path("interface").asText("").takeIf { it.isNotBlank() }?.let { n.put("interface", it) }
                    input.path("model").asText("").takeIf { it.isNotBlank() }?.let { n.put("model", it) }
                    input.path("param").asText("").takeIf { it.isNotBlank() }?.let { param ->
                        n.put("param", param)
                        val cm = input.path("node").asText("")
                        n.put("filled", nodes[cm]?.doc?.path("parameters")?.any { it.path("name").asText("") == param } ?: false)
                    }
                    input.path("hint").asText("").takeIf { it.isNotBlank() }?.let { n.put("hint", it) }
                }
            }
            putInputs(d, inputs)
            val outputs = row.putArray("outputs")
            d.path("outputs").forEach { o ->
                outputs.addObject().put("name", o.path("name").asText(""))
                    .put("at", o.path("at").asText("")).put("version", o.path("version").asText(""))
                    .put("proxy", o.path("proxy").asBoolean(false)).put("note", o.path("note").asText(""))
            }
            val feeds = row.putArray("feeds")
            d.path("feeds").forEach { feeds.add(it.asText()) }
            val parts = row.putArray("parts")
            partsOf(record).forEach { part ->
                val p = parts.addObject()
                    .put("code", part.path("code").asText("")).put("name", part.path("name").asText(""))
                    .put("question", part.path("question").asText(""))
                    .put("status", part.path("status").asText("not_built"))
                    .put("due_gate", part.path("due_gate").asText(""))
                    .put("answered", answered(part))
                part.path("interface_ref").asText("").takeIf { it.isNotBlank() }?.let { iface ->
                    p.put("interface", iface).put("interface_name", interfaces[iface]?.doc?.path("name")?.asText("") ?: "")
                }
                part.path("interface_hint").asText("").takeIf { it.isNotBlank() }?.let { p.put("interface_hint", it) }
                putInputs(part, p.putArray("inputs"))
                val po = p.putArray("outputs")
                part.path("outputs").forEach { o ->
                    po.addObject().put("name", o.path("name").asText("")).put("at", o.path("at").asText(""))
                        .put("proxy", o.path("proxy").asBoolean(false))
                }
            }
            val gapsArr = row.putArray("gaps")
            gapsByModel[record.id].orEmpty().forEach {
                gapsArr.addObject().put("what", it.what).put("place", it.place).put("code", it.code)
            }
        }
        out.put("gate", gate)
        out.put("total", rows.size())
        out.put("answered", rows.count { it.path("answered").asBoolean(false) })
        return out
    }
}
