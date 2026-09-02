// Носители в одном дереве (ADR-044): узел КА — определение компонента с
// profile.role = spacecraft; модель аппарата собирается из его поддерева
// (CarrierAssembly) и проходит схему контракта перед расчётом. Вхождения КА
// в построения — component_usage с constellation_ref: quantity вхождения и
// есть число аппаратов подгруппы, свёртка массы группировки — сумма по ним.
package orbita.com.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.model.CoreType
import orbita.mod.model.Lifecycle
import orbita.mod.store.ModelViolationException
import orbita.mod.store.StoredObject
import orbita.out.AssembledCarrier
import orbita.out.CarrierAssembly
import orbita.out.SpacecraftConditions

class Carriers(private val boundary: Boundary) {
    private val mapper = ObjectMapper()

    private fun definitions(projectId: String?): List<StoredObject> =
        boundary.objects.listCurrent(projectId)
            .filter { it.type == CoreType.Component.dbType && it.status != Lifecycle.Cancelled }

    /** Узлы КА проекта — те, у кого роль spacecraft. */
    fun nodes(projectId: String?): List<StoredObject> =
        definitions(projectId).filter { CarrierAssembly.role(it.doc) == CarrierAssembly.ROLE_SPACECRAFT }.sortedBy { it.id }

    /** Сборка контракта по узлу КА; проблемы сборки — списком, документ — всегда. */
    fun assemble(nodeId: String): AssembledCarrier {
        val node = boundary.objects.current(nodeId)
            ?.takeIf { it.type == CoreType.Component.dbType }
            ?: throw NoSuchElementException("узел состава $nodeId в модели отсутствует")
        require(CarrierAssembly.role(node.doc) == CarrierAssembly.ROLE_SPACECRAFT) {
            "узел $nodeId — не КА (profile.role ≠ spacecraft): модель аппарата собирается только из узла КА"
        }
        val all = definitions(node.projectId).map { it.doc }
        return CarrierAssembly.assemble(node.doc, all, mapper)
    }

    /**
     * Собранный и прошедший схему контракт: считать по непрошедшему нельзя,
     * поэтому проблемы сборки и ошибки схемы — отказ с перечнем, не тишина.
     */
    fun contract(nodeId: String): ObjectNode {
        val a = assemble(nodeId)
        val schema = boundary.validateContract("contracts/spacecraft", mapper.writeValueAsString(a.doc))
        val problems = a.problems + schema.map { it.toString() }
        if (problems.isNotEmpty()) {
            throw ModelViolationException("модель аппарата $nodeId не собирается из дерева: " + problems.joinToString("; "))
        }
        return a.doc
    }

    /** Первый узел КА проекта, собранный в контракт, — для мест, где нужен «аппарат проекта». */
    fun firstContract(projectId: String?): ObjectNode? =
        nodes(projectId).firstOrNull()?.let { runCatching { contract(it.id) }.getOrNull() }

    /** Контракт по ссылке сценария: вхождение → определение → сборка. */
    fun contractByUsage(usageId: String): ObjectNode {
        val usage = boundary.objects.current(usageId)
            ?.takeIf { it.type == CoreType.ComponentUsage.dbType }
            ?: throw NoSuchElementException("вхождение $usageId в модели отсутствует")
        return contract(usage.doc.path("definition_ref").asText(""))
    }

    /**
     * Дерево состава по вхождениям: строки с уровнем, кратностью по цепочке
     * родителей и массой узла из собранной модели (для КА) или параметра mass.
     * Построения — отдельной группой: подгруппа → вхождение ×N, масса
     * группировки — сумма quantity × масса КА.
     */
    fun tree(projectId: String): ObjectNode {
        val cur = boundary.objects.listCurrent(projectId).filter { it.status != Lifecycle.Cancelled }
        val defs = cur.filter { it.type == CoreType.Component.dbType }.associateBy { it.id }
        val usages = cur.filter { it.type == CoreType.ComponentUsage.dbType }
        val constellations = cur.filter { it.type == CoreType.Constellation.dbType }.associateBy { it.id }
        val byParent = usages.groupBy { it.doc.path("parent_usage").asText("") }
        val massCache = mutableMapOf<String, Double?>()
        fun nodeMass(defId: String): Double? = massCache.getOrPut(defId) {
            val def = defs[defId] ?: return@getOrPut null
            if (CarrierAssembly.role(def.doc) == CarrierAssembly.ROLE_SPACECRAFT) {
                runCatching { boundary.spacecraft.build(contract(defId), SpacecraftConditions()).mass.dryMassKg }.getOrNull()
            } else {
                CarrierAssembly.quantityOf(def.doc, "mass")?.takeIf { it.second == "kg" }?.first
            }
        }
        val defChildren = defs.values.groupBy { it.doc.path("parent").asText("") }
        val out = mapper.createObjectNode()
        val rows = out.putArray("rows")
        fun defRow(d: StoredObject, level: Int, m: Long): ObjectNode =
            rows.addObject()
                .put("definition", d.id)
                .put("name", d.doc.path("name").asText(""))
                .put("kind", d.doc.path("kind").asText(""))
                .put("role", CarrierAssembly.role(d.doc))
                .put("level", level)
                .put("multiplier", m)
                .put("parameters", d.doc.path("parameters").size())
                .also { row -> nodeMass(d.id)?.let { kg -> row.put("mass_kg", kg).put("mass_total_kg", kg * m) } }
        // Поддерево ОПРЕДЕЛЕНИЯ без вхождений показывается по родителю: узел,
        // заведённый как определение (платформа, ПН, подсистемы КА), не пропадает
        // из состава оттого, что вхождение ему ещё не заведено — строка помечена
        // «по определению», кратность наследуется от вхождения выше.
        fun walkDefinitions(parentDef: String, level: Int, m: Long, covered: Set<String>) {
            defChildren[parentDef].orEmpty().filter { it.id !in covered }.sortedBy { it.id }.forEach { d ->
                defRow(d, level, m).put("usage", "").put("by_definition", true).put("quantity", 1)
                walkDefinitions(d.id, level + 1, m, emptySet())
            }
        }
        fun walk(u: StoredObject, level: Int, multiplier: Long) {
            val def = defs[u.doc.path("definition_ref").asText("")]
            val qty = u.doc.path("quantity").asLong(1)
            val m = multiplier * qty
            val row = if (def != null) defRow(def, level, m) else rows.addObject()
                .put("definition", "").put("name", "определение отсутствует").put("kind", "").put("role", "")
                .put("level", level).put("multiplier", m)
            row.put("usage", u.id).put("quantity", qty).put("by_definition", false)
            u.doc.path("constellation_ref").asText("").takeIf { it.isNotBlank() }?.let { cn ->
                row.put("constellation", cn)
                row.put("constellation_name", constellations[cn]?.doc?.path("name")?.asText("") ?: "")
                row.put("subgroup", u.doc.path("subgroup").asText(""))
            }
            val kids = byParent[u.id].orEmpty().sortedBy { it.id }
            kids.forEach { walk(it, level + 1, m) }
            def?.let { walkDefinitions(it.id, level + 1, m, kids.map { k -> k.doc.path("definition_ref").asText("") }.toSet()) }
        }
        // корни — вхождения без родителя, не привязанные к построению;
        // подгруппы построений идут своей группой ниже
        usages.filter { it.doc.path("parent_usage").asText("").isBlank() && it.doc.path("constellation_ref").asText("").isBlank() }
            .sortedBy { it.id }.forEach { walk(it, 0, 1) }

        val cons = out.putArray("constellations")
        usages.filter { it.doc.path("constellation_ref").asText("").isNotBlank() }
            .groupBy { it.doc.path("constellation_ref").asText("") }
            .toSortedMap()
            .forEach { (cn, list) ->
                val c = cons.addObject().put("id", cn)
                    .put("name", constellations[cn]?.doc?.path("name")?.asText("") ?: "построение отсутствует")
                val subs = c.putArray("subgroups")
                var total = 0L
                var mass = 0.0
                var massKnown = true
                list.sortedBy { it.id }.forEach { u ->
                    val defId = u.doc.path("definition_ref").asText("")
                    val qty = u.doc.path("quantity").asLong(1)
                    total += qty
                    val kg = nodeMass(defId)
                    if (kg == null) massKnown = false else mass += kg * qty
                    subs.addObject()
                        .put("usage", u.id)
                        .put("subgroup", u.doc.path("subgroup").asText(""))
                        .put("definition", defId)
                        .put("name", defs[defId]?.doc?.path("name")?.asText("") ?: "")
                        .put("quantity", qty)
                        .also { s -> kg?.let { s.put("mass_kg", it).put("mass_total_kg", it * qty) } }
                }
                c.put("satellites", total)
                if (massKnown && list.isNotEmpty()) c.put("mass_total_kg", mass)
                else c.put("mass_note", "масса не сворачивается: у узла КА нет собранной модели")
            }

        val carriers = out.putArray("carriers")
        nodes(projectId).forEach { n ->
            val a = assemble(n.id)
            val schema = boundary.validateContract("contracts/spacecraft", mapper.writeValueAsString(a.doc))
            val c = carriers.addObject().put("id", n.id).put("name", n.doc.path("name").asText(""))
            val p = c.putArray("problems")
            (a.problems + schema.map { it.toString() }).forEach { p.add(it) }
            val nodesArr = c.putArray("nodes")
            a.nodes.forEach { nodesArr.add(it) }
            nodeMass(n.id)?.let { c.put("dry_mass_kg", it) }
        }
        // определения вне вхождений — чтобы узел не пропал из виду молча
        val orphans = out.putArray("definitions_without_usage")
        val used = usages.map { it.doc.path("definition_ref").asText("") }.toSet()
        defs.values.filter { it.id !in used && it.doc.path("parent").asText("").isBlank() }
            .sortedBy { it.id }.forEach { orphans.addObject().put("id", it.id).put("name", it.doc.path("name").asText("")) }
        return out
    }

    /** Условия расчёта по умолчанию — общие с экраном аппарата. */
    fun view(nodeId: String, conditions: SpacecraftConditions) =
        boundary.spacecraft.build(contract(nodeId), conditions)

    fun toJson(a: AssembledCarrier): JsonNode = mapper.createObjectNode().apply {
        set<JsonNode>("spacecraft", a.doc)
        putArray("problems").also { arr -> a.problems.forEach(arr::add) }
        putArray("nodes").also { arr -> a.nodes.forEach(arr::add) }
    }
}
