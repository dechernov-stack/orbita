// Канал библиотеки (ЗАДАЧА-CODE-БИБЛИОТЕКА §2–§3, СТРУКТУРА-БИБЛИОТЕКИ §4):
// «Сохранить как шаблон» — замыкание фрагмента с поимённой резкой внешних
// связей ДО записи, и применение фрагмента — импорт пачки с ремапом
// идентификаторов, связью «применяет» и происхождением. Обезличивание
// молчком запрещено: сохранение сверяет подтверждённые резы с фактическими.
package orbita.com.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.model.CoreType
import orbita.mod.store.ObjectStore
import orbita.mod.store.StoredObject

class LibraryChannel(
    private val boundary: Boundary,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    /** Один рез: связь или ссылка, ведущая наружу фрагмента, — поимённо. */
    data class Cut(val from: String, val to: String, val what: String)

    data class Closure(
        val objects: List<StoredObject>,
        val cuts: List<Cut>,
        /** Кандидаты обезличивания: величины с проектными значениями. */
        val valueCandidates: List<Triple<String, String, String>>,
    )

    /**
     * Замыкание фрагмента: выбранное с внутренними связями. Для узла дерева —
     * поддерево по parent, интерфейсы с обеими сторонами внутри и требования,
     * распределённые на вошедшее. Ссылки на область LIB (основания А1,
     * прототипы) не режутся: они и так библиотечные.
     */
    fun closure(projectId: String, kind: String?, ids: List<String>, root: String?): Closure {
        val all = boundary.objects.listCurrent(projectId)
        val byId = all.associateBy { it.id }
        val seed = mutableListOf<StoredObject>()
        if (root != null) {
            val rootObj = byId[root] ?: throw NoSuchElementException("object '$root' not found in $projectId")
            seed += rootObj
            // поддерево по parent — component и wbs_element
            val queue = ArrayDeque(listOf(root))
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                all.filter { it.doc.path("parent").asText("") == cur }.forEach {
                    if (seed.none { s -> s.id == it.id }) { seed += it; queue += it.id }
                }
            }
            val nodeIds = seed.map { it.id }.toSet()
            // интерфейсы: обе стороны внутри поддерева
            all.filter { it.type == "interface" }.forEach { itf ->
                val owners = itf.doc.path("owners").map { it.asText() }
                if (owners.isNotEmpty() && owners.all { it in nodeIds }) seed += itf
            }
            // требования, распределённые на вошедшие узлы и интерфейсы
            val memberIds = seed.map { it.id }.toSet()
            all.filter { it.type == "requirement" }.forEach { rq ->
                val targets = rq.doc.path("allocated_to").mapNotNull { a ->
                    a.path("component").asText("").ifBlank { a.path("interface").asText("") }.ifBlank { null }
                }
                if (targets.isNotEmpty() && targets.any { it in memberIds }) seed += rq
            }
        } else {
            require(!ids.isEmpty()) { "field 'ids' or 'root' is required" }
            ids.forEach { id ->
                seed += byId[id] ?: throw NoSuchElementException("object '$id' not found in $projectId")
            }
            if (kind != null) require(seed.all { it.type == kind }) { "selection must be of kind '$kind'" }
        }

        val member = seed.map { it.id }.toSet()
        val cuts = mutableListOf<Cut>()

        fun isLibrary(id: String): Boolean =
            boundary.objects.current(id)?.projectId == ObjectStore.LIBRARY_PROJECT

        // внешние ссылки в документах — поимённо
        seed.forEach { o ->
            val d = o.doc
            d.path("parent").asText("").takeIf { it.isNotBlank() && it !in member }
                ?.let { cuts += Cut(o.id, it, "parent") }
            d.path("traces_up").forEach { t ->
                val ref = t.path("ref").asText(t.asText(""))
                if (ref.isNotBlank() && ref !in member && !isLibrary(ref)) {
                    cuts += Cut(o.id, ref, "traces_up")
                }
            }
            d.path("allocated_to").forEach { a ->
                val ref = a.path("component").asText("").ifBlank { a.path("interface").asText("") }
                if (ref.isNotBlank() && ref !in member) cuts += Cut(o.id, ref, "allocated_to")
            }
            d.path("derives_from").forEach { p ->
                val ref = p.asText("")
                if (ref.isNotBlank() && ref !in member) cuts += Cut(o.id, ref, "derives_from")
            }
            d.path("interfaces").forEach { i ->
                val peer = i.path("peer").asText("")
                if (peer.isNotBlank() && peer !in member) cuts += Cut(o.id, peer, "interfaces.peer")
            }
            d.path("applies").path("ref").asText("").takeIf { it.isNotBlank() && !isLibrary(it) }
                ?.let { cuts += Cut(o.id, it, "applies") }
        }

        // кандидаты обезличивания: величины (value+unit) в параметрах
        val values = mutableListOf<Triple<String, String, String>>()
        seed.forEach { o ->
            collectQuantities(o.doc, "", o.id, values)
        }
        return Closure(seed.sortedBy { it.id }, cuts.distinct(), values)
    }

    private fun collectQuantities(
        node: JsonNode, path: String, objId: String,
        out: MutableList<Triple<String, String, String>>,
    ) {
        when {
            node.isObject -> {
                if (node.path("value").isNumber && node.path("unit").isTextual) {
                    out += Triple(objId, path, "${node.path("value").asDouble()} ${node.path("unit").asText()}")
                }
                node.properties().forEach { (k, v) ->
                    if (k != "provenance") collectQuantities(v, if (path.isEmpty()) k else "$path/$k", objId, out)
                }
            }
            node.isArray -> node.forEachIndexed { i, v -> collectQuantities(v, "$path/$i", objId, out) }
        }
    }

    /**
     * Запись фрагмента. Подтверждённые резы сверяются с фактическими: рез,
     * который инженер не видел, — отказ, а не молчаливая потеря (§2).
     * Карта замен: {objectId: [paths]} — величина по пути обезличивается.
     */
    fun save(
        projectId: String,
        kind: String?,
        ids: List<String>,
        root: String?,
        name: String,
        shelf: String,
        missionClassRef: String?,
        acknowledgedCuts: Set<String>,
        replacements: Map<String, List<String>>,
        author: String,
    ): StoredObject {
        val c = closure(projectId, kind, ids, root)
        val actual = c.cuts.map { "${it.from} → ${it.to} (${it.what})" }.toSet()
        val unseen = actual - acknowledgedCuts
        require(unseen.isEmpty()) {
            "резка связей без предпросмотра запрещена — не подтверждено: ${unseen.sorted().joinToString("; ")}"
        }

        val member = c.objects.map { it.id }.toSet()
        val payloadObjects = mapper.createArrayNode()
        var anonymized = false
        c.objects.forEach { o ->
            val d = o.doc.deepCopy<ObjectNode>()
            d.remove("lifecycle")
            d.remove("provenance")
            trimExternal(d, member)
            replacements[o.id].orEmpty().forEach { path ->
                if (removeAt(d, path)) anonymized = true
            }
            payloadObjects.add(d)
        }

        val doc = mapper.createObjectNode()
        doc.put("name", name)
        doc.put("shelf", shelf)
        missionClassRef?.takeIf { it.isNotBlank() }?.let { doc.put("mission_class_ref", it) }
        val counters = doc.putObject("counters")
        c.objects.groupBy { it.type }.forEach { (t, list) -> counters.put(t, list.size) }
        val origin = doc.putObject("origin")
        origin.put("project", projectId)
        origin.put("author", author)
        origin.put("date", java.time.LocalDate.now().toString())
        val versions = origin.putObject("object_versions")
        c.objects.forEach { versions.put(it.id, it.version) }
        doc.put("anonymized", anonymized)
        doc.putObject("payload").set<ArrayNode>("objects", payloadObjects)
        return boundary.editing.create(
            CoreType.LibraryFragment, doc, author, ObjectStore.LIBRARY_PROJECT,
        )
    }

    /** Ссылки наружу фрагмента вычищаются из документа (резы уже подтверждены). */
    private fun trimExternal(d: ObjectNode, member: Set<String>) {
        fun isLib(id: String) = boundary.objects.current(id)?.projectId == ObjectStore.LIBRARY_PROJECT
        d.path("parent").asText("").takeIf { it.isNotBlank() && it !in member }?.let { d.remove("parent") }
        (d.path("traces_up") as? ArrayNode)?.let { arr ->
            val keep = arr.filter { t ->
                val ref = t.path("ref").asText(t.asText(""))
                ref in member || isLib(ref)
            }
            arr.removeAll(); keep.forEach(arr::add)
            if (arr.isEmpty) d.remove("traces_up")
        }
        (d.path("allocated_to") as? ArrayNode)?.let { arr ->
            val keep = arr.filter { a ->
                val ref = a.path("component").asText("").ifBlank { a.path("interface").asText("") }
                ref in member
            }
            arr.removeAll(); keep.forEach(arr::add)
            if (arr.isEmpty) d.remove("allocated_to")
        }
        (d.path("derives_from") as? ArrayNode)?.let { arr ->
            val keep = arr.filter { it.asText("") in member }
            arr.removeAll(); keep.forEach(arr::add)
            if (arr.isEmpty) d.remove("derives_from")
        }
        (d.path("interfaces") as? ArrayNode)?.let { arr ->
            val keep = arr.filter { it.path("peer").asText("") in member }
            arr.removeAll(); keep.forEach(arr::add)
            if (arr.isEmpty) d.remove("interfaces")
        }
        d.path("applies").path("ref").asText("").takeIf { it.isNotBlank() && !isLib(it) }
            ?.let { d.remove("applies") }
    }

    private fun removeAt(d: ObjectNode, path: String): Boolean {
        val parts = path.split('/')
        var cur: JsonNode = d
        parts.dropLast(1).forEach { p ->
            cur = if (cur.isArray) cur.path(p.toIntOrNull() ?: return false) else cur.path(p)
        }
        val last = parts.last()
        return when {
            cur is ObjectNode && cur.has(last) -> { (cur as ObjectNode).remove(last); true }
            cur.isArray -> {
                val i = last.toIntOrNull() ?: return false
                if (i < cur.size()) { (cur as ArrayNode).remove(i); true } else false
            }
            else -> false
        }
    }

    /**
     * Применение фрагмента в проект: экземпляры копией с новыми
     * идентификаторами (id глобально уникальны — TZ-MOD-007), внутренние
     * ссылки пачки ремапятся, каждый созданный несёт «применяет» на прототип
     * и происхождение imported с родословной фрагмента.
     */
    /** Итог применения: created — пары «старый id → новый»; existing — живые
     * экземпляры прежнего взятия (идемпотентность: второй набор не создаётся). */
    data class ApplyOutcome(val created: List<Pair<String, String>>, val existing: List<String>)

    /** Отмена заблокирована: созданное взятием уже тронуто руками. */
    class RevertBlockedException(val touched: List<String>) :
        IllegalStateException("созданное взятием уже тронуто руками: ${touched.joinToString()}")

    /** Живые экземпляры проекта, применённые из фрагмента, — журнал применения
     * и есть связи «применяет»: отдельного состояния у взятия нет. */
    fun appliedInstances(fragmentId: String, projectId: String): List<orbita.mod.store.StoredObject> =
        boundary.links.linksTo(fragmentId, "applies")
            .mapNotNull { boundary.objects.current(it.fromId) }
            .filter { it.projectId == projectId && it.status != orbita.mod.model.Lifecycle.Cancelled }
            .sortedBy { it.id }

    /**
     * Отмена взятия — до конца пути: гасит созданное ИМЕННО этим взятием.
     * Тронутое руками (история длиннее создания) — отказ с перечнем, не
     * молчаливое удаление.
     */
    fun revert(fragmentId: String, projectId: String, author: String): List<String> {
        val instances = appliedInstances(fragmentId, projectId)
        val touched = instances.filter { boundary.objects.history(it.id).size > 1 }.map { it.id }
        if (touched.isNotEmpty()) throw RevertBlockedException(touched)
        instances.forEach { boundary.editing.cancel(it.id, author) }
        return instances.map { it.id }
    }

    fun apply(fragmentId: String, projectId: String, author: String): ApplyOutcome {
        val frag = boundary.objects.current(fragmentId)
            ?: throw NoSuchElementException("fragment '$fragmentId' not found")
        require(frag.type == "library_fragment") { "'$fragmentId' is not a library fragment" }
        // идемпотентность по связи «применяет»: повторное нажатие не плодит набор
        appliedInstances(fragmentId, projectId).takeIf { it.isNotEmpty() }?.let { alive ->
            return ApplyOutcome(created = emptyList(), existing = alive.map { it.id })
        }
        val objects = frag.doc.path("payload").path("objects")
        require(objects.isArray && objects.size() > 0) { "fragment '$fragmentId' payload is empty" }

        val raw = objects.map { it.deepCopy<ObjectNode>() }
        // родители раньше детей: parent должен существовать к моменту записи
        val ids = raw.map { it.path("id").asText("") }.toSet()
        val list = mutableListOf<ObjectNode>()
        val placed = mutableSetOf<String>()
        while (list.size < raw.size) {
            val next = raw.filter { o ->
                o.path("id").asText("") !in placed &&
                    o.path("parent").asText("").let { it.isBlank() || it !in ids || it in placed }
            }
            require(next.isNotEmpty()) { "fragment payload has a parent cycle" }
            next.forEach { list += it; placed += it.path("id").asText("") }
        }
        // новые идентификаторы: nextId даёт базу по типу, дальше — счёт
        // (два объекта одного вида до записи получили бы один и тот же id)
        val remap = mutableMapOf<String, String>()
        val created = mutableListOf<Pair<String, String>>()
        val counters = mutableMapOf<CoreType, Int>()
        list.forEach { o ->
            val oldId = o.path("id").asText("")
            val type = CoreType.entries.firstOrNull { oldId.startsWith(it.idPrefix + "-") }
                ?: throw IllegalArgumentException("unknown id prefix in fragment payload: '$oldId'")
            val n = counters.getOrPut(type) {
                boundary.editing.nextId(type).substringAfterLast('-').toInt()
            }
            counters[type] = n + 1
            remap[oldId] = "%s-%04d".format(type.idPrefix, n)
            created += oldId to remap[oldId]!!
        }
        list.forEach { o ->
            val oldId = o.path("id").asText("")
            var text = mapper.writeValueAsString(o)
            remap.forEach { (old, new) -> text = text.replace(Regex("\\b" + Regex.escape(old) + "\\b"), new) }
            val doc = mapper.readTree(text) as ObjectNode
            val type = CoreType.entries.first { remap[oldId]!!.startsWith(it.idPrefix + "-") }
            // связь «применяет» — видам, чья схема её несёт
            if (boundary.schemaAllows(type, "applies") && !doc.has("applies")) {
                doc.putObject("applies").put("ref", fragmentId).put("status", "applied")
            }
            // статусная модель: экземпляр начинает черновиком (пачка хранится
            // без lifecycle — обезличена от статусов донора)
            if (boundary.schemaAllows(type, "lifecycle")) {
                doc.putObject("lifecycle").put("status", "Draft").put("version", "1")
            }
            doc.putObject("provenance")
                .put("source", "imported")
                .put("author", author)
                .putObject("import")
                .put("dataset", "библиотека: $fragmentId «${frag.doc.path("name").asText("")}»")
                .put("dataset_version", frag.version)
                .put("retrieved_at", java.time.LocalDate.now().toString())
                .put("terms", "из проекта ${frag.doc.path("origin").path("project").asText("")}")
            val stored = boundary.ingest(type, mapper.writeValueAsString(doc), author, projectId)
            boundary.req.syncLinks(stored.type, stored.id, stored.doc, stored.projectId)
        }
        return ApplyOutcome(created = created, existing = emptyList())
    }
}
