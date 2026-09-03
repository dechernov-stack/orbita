// Экран «Архитектура» (ADR-052): четыре слоя Arcadia одним взглядом.
//
// OA — зачем: способности и акторы-стейкхолдеры. SA — что делает система:
// функции с обменами по стыкам. LA — кто делает логически: компоненты,
// развёрнутые на узлы. PA — на чём стоит: дерево состава и его рёбра; своего
// хранения у PA нет, это те же узлы и стыки (ADR-044 — состав один).
//
// Разрывы считает сервер и говорит, ГДЕ чинить: функция без узла чинится в
// матрице, цепочка без требования — в реестре требований, способность без
// привязки — здесь же. Клиент рисует то, что пришло.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.model.Lifecycle
import orbita.mod.store.StoredObject

class ArchitectureView(private val boundary: Boundary) {
    private val mapper = ObjectMapper()

    private fun живые(projectId: String, тип: String): List<StoredObject> =
        boundary.objects.listCurrent(projectId)
            .filter { it.type == тип && it.status != Lifecycle.Cancelled }
            .sortedBy { it.doc.path("code").asText(it.id) }

    fun view(projectId: String): ObjectNode {
        val out = mapper.createObjectNode()
        val узлы = живые(projectId, "component").associateBy { it.id }
        val стыки = живые(projectId, "interface").associateBy { it.id }
        val функции = живые(projectId, "function")
        val функцииПоId = функции.associateBy { it.id }
        val стейкхолдеры = живые(projectId, "stakeholder")
        val покрытие = boundary.matrices.coverageMatrix(projectId)
        val цепочкиСТребованиями = покрытие.rows.flatMap { it.realizedBy }.toSet()

        // ── OA: способности и акторы ──────────────────────────────────────
        val oa = out.putObject("oa")
        val способности = oa.putArray("capabilities")
        живые(projectId, "capability").forEach { c ->
            val n = способности.addObject()
                .put("id", c.id).put("code", c.doc.path("code").asText(""))
                .put("name", c.doc.path("name").asText(""))
                .put("hint", c.doc.path("traced_to_hint").asText(""))
            val к = n.putArray("traced_to")
            c.doc.path("traced_to").forEach { t ->
                val ref = t.path("ref").asText()
                к.addObject().put("ref", ref)
                    .put("name", boundary.objects.current(ref)?.doc?.path("name")?.asText("") ?: "")
            }
            n.put("linked", c.doc.path("traced_to").size() > 0)
        }
        val акторы = oa.putArray("actors")
        стейкхолдеры.forEach { sk ->
            акторы.addObject().put("id", sk.id).put("name", sk.doc.path("name").asText(""))
                .put("role", sk.doc.path("role").asText(""))
                .put("note", sk.doc.path("note").asText(""))
        }

        // ── SA: функции с обменами ───────────────────────────────────────
        val sa = out.putObject("sa")
        val строки = sa.putArray("functions")
        функции.forEach { f ->
            val n = строки.addObject()
                .put("id", f.id).put("code", f.doc.path("code").asText(""))
                .put("name", f.doc.path("name").asText(""))
            val узлыФункции = n.putArray("allocated_to")
            f.doc.path("allocated_to").forEach { a ->
                val ref = a.path("component").asText("").ifBlank { a.path("interface").asText("") }
                if (ref.isNotBlank()) {
                    узлыФункции.addObject().put("ref", ref)
                        .put("name", узлы[ref]?.doc?.path("name")?.asText("")
                            ?: стыки[ref]?.doc?.path("name")?.asText("") ?: "")
                }
            }
            n.put("allocated", f.doc.path("allocated_to").size() > 0)
            val обмены = n.putArray("exchanges")
            f.doc.path("exchanges").forEach { ex ->
                val адресат = ex.path("to").asText("")
                val стык = ex.path("interface").asText("")
                обмены.addObject()
                    .put("code", ex.path("code").asText(""))
                    .put("name", ex.path("name").asText(""))
                    .put("to", адресат)
                    .put("to_name", функцииПоId[адресат]?.doc?.path("name")?.asText("")
                        ?: стейкхолдеры.firstOrNull { it.id == адресат }?.doc?.path("name")?.asText("") ?: "")
                    .put("to_activity", ex.path("to_activity").asText(""))
                    .put("interface", стык)
                    .put("interface_name", стыки[стык]?.doc?.path("name")?.asText("") ?: "")
            }
            val откуда = n.putArray("traces_up")
            f.doc.path("traces_up").forEach { t -> откуда.add(t.path("ref").asText()) }
        }
        val цепочки = sa.putArray("chains")
        живые(projectId, "function_chain").forEach { c ->
            val n = цепочки.addObject()
                .put("id", c.id).put("code", c.doc.path("code").asText(""))
                .put("name", c.doc.path("name").asText(""))
                .put("capability", c.doc.path("capability").asText(""))
                .put("has_requirement", c.id in цепочкиСТребованиями)
            val шаги = n.putArray("steps")
            c.doc.path("steps").forEach { шаг ->
                val fn = шаг.path("function").asText()
                шаги.addObject().put("ref", fn)
                    .put("code", функцииПоId[fn]?.doc?.path("code")?.asText("") ?: "")
                    .put("name", функцииПоId[fn]?.doc?.path("name")?.asText("") ?: "")
            }
            val назад = n.putArray("ack")
            c.doc.path("ack").forEach { шаг ->
                val fn = шаг.path("function").asText()
                назад.addObject().put("ref", fn)
                    .put("name", функцииПоId[fn]?.doc?.path("name")?.asText("") ?: "")
            }
            val виды = n.putArray("requirement_kinds")
            c.doc.path("requirement_kinds").forEach { виды.add(it.asText()) }
            val требования = n.putArray("requirements")
            покрытие.rows.filter { c.id in it.realizedBy }.forEach { требования.add(it.requirementId) }
        }

        // ── LA: логические компоненты ────────────────────────────────────
        val la = out.putArray("logical_components")
        живые(projectId, "logical_component").forEach { lc ->
            val n = la.addObject()
                .put("id", lc.id).put("code", lc.doc.path("code").asText(""))
                .put("name", lc.doc.path("name").asText(""))
            val ф = n.putArray("functions")
            lc.doc.path("functions").forEach { fn ->
                ф.addObject().put("ref", fn.asText())
                    .put("name", функцииПоId[fn.asText()]?.doc?.path("name")?.asText("") ?: "")
            }
            val у = n.putArray("deployed_to")
            lc.doc.path("deployed_to").forEach { cm ->
                у.addObject().put("ref", cm.asText())
                    .put("name", узлы[cm.asText()]?.doc?.path("name")?.asText("") ?: "")
            }
        }

        // ── PA: стыки дерева состава (узлы живут на своём экране) ─────────
        val pa = out.putArray("interfaces")
        стыки.values.sortedBy { it.doc.path("code").asText(it.id) }.forEach { iface ->
            val стороны = iface.doc.path("owners").map { узлы[it.asText()]?.doc?.path("name")?.asText() ?: it.asText() }
            val анкета = iface.doc.path("expects")
            val заполнено = анкета.count { f ->
                iface.doc.path("parameters").any {
                    it.path("name").asText("") == f.path("key").asText() &&
                        !it.path("quantity").path("value").isMissingNode
                }
            }
            val n = pa.addObject()
                .put("id", iface.id).put("code", iface.doc.path("code").asText(""))
                .put("name", iface.doc.path("name").asText(""))
                .put("type", iface.doc.path("type").asText(iface.doc.path("kind").asText("")))
                .put("sides", стороны.joinToString(" ↔ "))
                .put("fields", анкета.size()).put("filled", заполнено)
                .put("icd_section", iface.doc.path("icd_section").asText(""))
            val обменыСтыка = n.putArray("exchanges")
            функции.forEach { f ->
                f.doc.path("exchanges").filter { it.path("interface").asText() == iface.id }.forEach { ex ->
                    обменыСтыка.addObject().put("code", ex.path("code").asText(""))
                        .put("name", ex.path("name").asText(""))
                        .put("from", f.doc.path("code").asText(f.id))
                }
            }
            val требования = n.putArray("requirements")
            покрытие.rows.filter { iface.id in it.carriers }.forEach { требования.add(it.requirementId) }
        }

        // ── разрывы: что и где чинить ────────────────────────────────────
        val разрывы = out.putArray("gaps")
        val матрица = boundary.matrices.functionMatrix(projectId)
        матрица.unallocated.forEach {
            разрывы.addObject().put("what", "функция $it не распределена на узел").put("place", "matrices")
        }
        живые(projectId, "function_chain").filter { it.id !in цепочкиСТребованиями }.forEach {
            разрывы.addObject()
                .put("what", "на цепочке ${it.doc.path("code").asText(it.id)} нет сценарного требования")
                .put("place", "req")
        }
        живые(projectId, "capability").filter { it.doc.path("traced_to").isEmpty }.forEach {
            разрывы.addObject()
                .put("what", "способность ${it.doc.path("code").asText(it.id)} не привязана к целям и нуждам" +
                    it.doc.path("traced_to_hint").asText("").let { h -> if (h.isBlank()) "" else " (подсказка полки: $h)" })
                .put("place", "architecture")
        }
        живые(projectId, "interface").filter { iface ->
            iface.doc.path("expects").size() > 0 && iface.doc.path("parameters").isEmpty
        }.forEach {
            разрывы.addObject()
                .put("what", "анкета стыка ${it.doc.path("code").asText(it.id)} не заполнена")
                .put("place", "datarequests")
        }

        out.put("counts_functions", функции.size)
        out.put("counts_chains", живые(projectId, "function_chain").size)
        out.put("counts_capabilities", живые(projectId, "capability").size)
        out.put("counts_logical", живые(projectId, "logical_component").size)
        out.put("counts_interfaces", стыки.size)
        out.put("counts_nodes", узлы.size)
        return out
    }
}
