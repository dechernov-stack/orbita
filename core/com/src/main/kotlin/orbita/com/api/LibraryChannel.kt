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
    /**
     * Каркас PBS ред. 2 (ADR-051): что именно берём. Уровни 0–3 обязательны,
     * 4–5 — по классу миссии; необязательные узлы (ISL, PNT, экспериментальная
     * ОГ) — только по подтверждению; узел, чей код уже есть в проекте, не
     * дублируется — вместо него подставляется существующий.
     */
    data class TakeOptions(
        val depth: Int? = null,
        /** код узла каркаса → идентификатор уже заведённого узла проекта. */
        val mapping: Map<String, String> = emptyMap(),
        /**
         * Решение Б3-01 ред. 2: данные полки полны, ВЫБОР — у инженера при взятии.
         * select — идентификаторы пачки выбранных элементов; null — рекомендованный
         * набор класса (default_take != false); selectAll — вся полка.
         */
        val select: Set<String>? = null,
        val selectAll: Boolean = false,
        /** Довзятие из ДРУГИХ полок тем же подтверждением: полка → коды элементов. */
        val extras: Map<String, Set<String>> = emptyMap(),
        /** Снятие взятого ранее (идентификаторы пачки): отмена с историей. */
        val unselect: Set<String> = emptySet(),
    )

    /**
     * Что каркас найдёт в проекте: по коду узла, иначе по имени. Диалог взятия
     * берёт список отсюда — заводить дубль там, где узел уже есть, нельзя.
     */
    fun matches(fragmentId: String, projectId: String): List<Triple<String, String, String>> {
        val frag = boundary.objects.current(fragmentId)
            ?: throw NoSuchElementException("fragment '$fragmentId' not found")
        val existing = boundary.objects.listCurrent(projectId)
            .filter { it.type == "component" && it.status != orbita.mod.model.Lifecycle.Cancelled }
        val byCode = existing.filter { it.doc.path("code").asText("").isNotBlank() }
            .associateBy { it.doc.path("code").asText() }
        val byName = existing.associateBy { it.doc.path("name").asText("").lowercase().trim() }
        return frag.doc.path("payload").path("objects").mapNotNull { o ->
            val code = o.path("code").asText("")
            val name = o.path("name").asText("")
            val hit = byCode[code] ?: byName[name.lowercase().trim()]
            hit?.let { Triple(code.ifBlank { o.path("id").asText() }, name, it.id) }
        }
    }


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
    data class ApplyOutcome(
        val created: List<Pair<String, String>>,
        val existing: List<String>,
        /** Снятое этим взятием — отменённые объекты проекта. */
        val removed: List<String> = emptyList(),
    )

    private fun liveFragments(): List<orbita.mod.store.StoredObject> =
        boundary.objects.listCurrent(orbita.mod.store.ObjectStore.LIBRARY_PROJECT)
            .filter { it.type == "library_fragment" && it.status != orbita.mod.model.Lifecycle.Cancelled }

    /** Полка библиотеки, чья пачка заводит объект с этим кодом, — для подсказки и отказа. */
    private fun shelfOfCode(code: String): orbita.mod.store.StoredObject? =
        liveFragments().firstOrNull { f -> f.doc.path("payload").path("objects").any { it.path("code").asText("") == code } }

    private val codeRefs = Regex("@([A-Za-zА-Яа-я0-9_.\\-]+)")

    private fun typeOfPayloadId(id: String): CoreType? =
        CoreType.entries.firstOrNull { id.startsWith(it.idPrefix + "-") }

    /** Взятое из этой полки, по идентификатору пачки: код, без кода — вид и имя. */
    private fun takenByPayloadId(fragmentId: String, projectId: String, all: List<ObjectNode>, mapping: Map<String, String>): Map<String, String> {
        val alive = appliedInstances(fragmentId, projectId)
        val aliveByCode = alive.filter { it.doc.path("code").asText("").isNotBlank() }.associateBy { it.doc.path("code").asText() }
        // вид без поля applies (стейкхолдер-актор) связи «применяет» не несёт —
        // взятое узнаётся по виду и имени среди живых объектов проекта
        val aliveByName = (alive + boundary.objects.listCurrent(projectId)
            .filter { it.status != orbita.mod.model.Lifecycle.Cancelled }
            .filter { o -> CoreType.entries.firstOrNull { it.dbType == o.type }?.let { !boundary.schemaAllows(it, "applies") } ?: false })
            .filter { it.doc.path("code").asText("").isBlank() }
            .associateBy { it.type + "|" + it.doc.path("name").asText("").lowercase().trim() }
        val out = mutableMapOf<String, String>()
        all.forEach { node ->
            val code = node.path("code").asText("")
            val hit = mapping[code]
                ?: aliveByCode[code]?.id
                ?: if (code.isBlank()) {
                    aliveByName[typeOfPayloadId(node.path("id").asText(""))?.dbType + "|" + node.path("name").asText("").lowercase().trim()]?.id
                } else null
            if (hit != null) out[node.path("id").asText("")] = hit
        }
        return out
    }

    /**
     * Окно взятия (решение Б3-01 ред. 2): элементы полки с рекомендованным
     * набором, что уже взято, ЗАЧЕМ элемент и от чего он зависит — по ссылкам,
     * а не по флагам. Зависимость, не взятая в проект, но лежащая на другой
     * полке, — предложение довзять тем же подтверждением, не ошибка.
     */
    fun takeWindow(fragmentId: String, projectId: String): ObjectNode {
        val frag = boundary.objects.current(fragmentId)
            ?: throw NoSuchElementException("fragment '$fragmentId' not found")
        require(frag.type == "library_fragment") { "'$fragmentId' is not a library fragment" }
        val all = frag.doc.path("payload").path("objects").map { it.deepCopy<ObjectNode>() }
        val byPayloadId = all.associateBy { it.path("id").asText("") }
        val codeOfPayloadId = all.associate { it.path("id").asText("") to it.path("code").asText("") }
        val taken = takenByPayloadId(fragmentId, projectId, all, emptyMap())
        val inProject = boundary.objects.listCurrent(projectId)
            .filter { it.status != orbita.mod.model.Lifecycle.Cancelled }
            .mapNotNull { o -> o.doc.path("code").asText("").takeIf { it.isNotBlank() }?.let { it to o.id } }.toMap()
        val shelves = liveFragments()
        // кто на что ссылается — по всем полкам: «от него зависит» видно и для чужих
        data class Ref(val fragment: String, val fragmentName: String, val code: String, val name: String)
        val dependents = mutableMapOf<String, MutableList<Ref>>()
        shelves.forEach { f ->
            f.doc.path("payload").path("objects").forEach { o ->
                val text = mapper.writeValueAsString(o)
                codeRefs.findAll(text).map { it.groupValues[1] }.toSet().forEach { code ->
                    dependents.getOrPut(code) { mutableListOf() } +=
                        Ref(f.id, f.doc.path("name").asText(f.id), o.path("code").asText(""), o.path("name").asText(""))
                }
                if (f.id == fragmentId) {
                    // ссылки внутри пачки — по идентификатору (шаг цепочки → функция)
                    byPayloadId.keys.filter { pid -> pid != o.path("id").asText("") && Regex("\\b" + Regex.escape(pid) + "\\b").containsMatchIn(text) }
                        .forEach { pid ->
                            val code = codeOfPayloadId[pid].orEmpty()
                            if (code.isNotBlank()) dependents.getOrPut(code) { mutableListOf() } +=
                                Ref(f.id, f.doc.path("name").asText(f.id), o.path("code").asText(""), o.path("name").asText(""))
                        }
                }
            }
        }
        val out = mapper.createObjectNode()
        out.put("id", fragmentId).put("name", frag.doc.path("name").asText("")).put("shelf", frag.doc.path("shelf").asText(""))
        val rows = out.putArray("elements")
        val byType = mutableMapOf<String, Int>()
        var recommended = 0
        var takenCount = 0
        all.forEach { o ->
            val pid = o.path("id").asText("")
            val type = typeOfPayloadId(pid)?.dbType ?: "?"
            byType[type] = (byType[type] ?: 0) + 1
            val isRecommended = !(o.has("default_take") && !o.path("default_take").asBoolean(true))
            if (isRecommended) recommended++
            val takenId = taken[pid]
            if (takenId != null) takenCount++
            val row = rows.addObject()
                .put("id", pid).put("code", o.path("code").asText("")).put("name", o.path("name").asText(""))
                .put("type", type).put("kind", o.path("kind").asText(o.path("type").asText("")))
                .put("level", o.path("level").asInt(-1)).put("parent", o.path("parent").asText(""))
                .put("default_take", isRecommended)
                .put("taken", takenId ?: "")
                .put("why", listOf("statement", "question", "scope", "description", "standard")
                    .map { o.path(it).asText("") }.firstOrNull { it.isNotBlank() } ?: "")
            val needs = row.putArray("needs")
            val text = mapper.writeValueAsString(o)
            codeRefs.findAll(text).map { it.groupValues[1] }.toSet().sorted().forEach { code ->
                val n = needs.addObject().put("code", code).put("in_project", inProject.containsKey(code)).put("internal", false)
                if (!inProject.containsKey(code)) {
                    val shelf = shelfOfCode(code)
                    if (shelf != null) {
                        n.put("shelf", shelf.id).put("shelf_name", shelf.doc.path("name").asText(""))
                        shelf.doc.path("payload").path("objects").firstOrNull { it.path("code").asText("") == code }
                            ?.let { n.put("name", it.path("name").asText("")) }
                    }
                }
            }
            byPayloadId.keys.filter { other -> other != pid && Regex("\\b" + Regex.escape(other) + "\\b").containsMatchIn(text) }
                .forEach { other ->
                    val code = codeOfPayloadId[other].orEmpty()
                    needs.addObject().put("code", code.ifBlank { other }).put("payload_id", other)
                        .put("in_project", taken.containsKey(other)).put("internal", true)
                        .put("name", byPayloadId[other]?.path("name")?.asText("") ?: "")
                }
            val neededBy = row.putArray("needed_by")
            o.path("code").asText("").takeIf { it.isNotBlank() }?.let { code ->
                dependents[code].orEmpty().distinct().forEach { r ->
                    neededBy.addObject().put("fragment", r.fragment).put("fragment_name", r.fragmentName)
                        .put("code", r.code).put("name", r.name).put("same_shelf", r.fragment == fragmentId)
                }
            }
        }
        val summary = out.putObject("summary")
        summary.put("total", all.size).put("recommended", recommended).put("taken", takenCount)
        val bt = summary.putObject("by_type")
        byType.forEach { (t, n) -> bt.put(t, n) }
        return out
    }

    /** Отмена заблокирована: созданное взятием уже тронуто руками. */
    class RevertBlockedException(val touched: List<String>) :
        IllegalStateException("созданное взятием уже тронуто руками: ${touched.joinToString()}")

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
        // симметрия автораспределения (ОТВЕТЫ-Т1-ДОП §2): уходит корень —
        // авто-связи гаснут той же сводной записью; ручные на корень — отказ
        val rootDefs = instances
            .filter { it.type == "component_usage" && it.doc.path("parent_usage").asText("").isBlank() }
            .mapNotNull { it.doc.path("definition_ref").asText("").ifBlank { null } }
        rootDefs.forEach { def ->
            val (_, manual) = boundary.req.releaseAutoRoot(projectId, def, author)
            if (manual.isNotEmpty()) throw RevertBlockedException(manual)
        }
        instances.forEach { boundary.editing.cancel(it.id, author) }
        return instances.map { it.id }
    }

    fun apply(fragmentId: String, projectId: String, author: String): ApplyOutcome =
        apply(fragmentId, projectId, author, TakeOptions())

    fun apply(fragmentId: String, projectId: String, author: String, options: TakeOptions): ApplyOutcome {
        val frag = boundary.objects.current(fragmentId)
            ?: throw NoSuchElementException("fragment '$fragmentId' not found")
        require(frag.type == "library_fragment") { "'$fragmentId' is not a library fragment" }
        val objects = frag.doc.path("payload").path("objects")
        require(objects.isArray && objects.size() > 0) { "fragment '$fragmentId' payload is empty" }
        val all = objects.map { it.deepCopy<ObjectNode>() }
        val byPayloadId = all.associateBy { it.path("id").asText("") }

        // довзятие из других полок — тем же подтверждением, ДО этой полки:
        // узел каркаса, которого ждёт стык, появляется в проекте первым
        val extraCreated = mutableListOf<Pair<String, String>>()
        options.extras.forEach { (otherId, codes) ->
            if (otherId == fragmentId || codes.isEmpty()) return@forEach
            val other = boundary.objects.current(otherId) ?: throw NoSuchElementException("fragment '$otherId' not found")
            val ids = other.doc.path("payload").path("objects")
                .filter { it.path("code").asText("") in codes }.map { it.path("id").asText("") }.toSet()
            require(ids.isNotEmpty()) { "на полке «${other.doc.path("name").asText(otherId)}» нет элементов с кодами ${codes.joinToString()}" }
            extraCreated += apply(otherId, projectId, author, TakeOptions(select = ids, mapping = options.mapping)).created
        }

        // снятие взятого ранее — отмена с историей; тронутое руками — отказ
        val takenBefore = takenByPayloadId(fragmentId, projectId, all, options.mapping)
        val removed = mutableListOf<String>()
        val touched = options.unselect.mapNotNull { pid -> takenBefore[pid] }
            .filter { boundary.objects.history(it).size > 1 }
        if (touched.isNotEmpty()) throw RevertBlockedException(touched)
        options.unselect.forEach { pid ->
            takenBefore[pid]?.let { boundary.editing.cancel(it, author); removed += it }
        }
        val existingByPayloadId = takenByPayloadId(fragmentId, projectId, all, options.mapping)

        // выбор: названные элементы, вся полка либо рекомендованный набор класса
        val selected: Set<String> = when {
            options.selectAll -> all.map { it.path("id").asText("") }.toSet()
            options.select != null -> options.select
            else -> all.filter { !(it.has("default_take") && !it.path("default_take").asBoolean(true)) }
                .map { it.path("id").asText("") }.toSet()
        }
        fun depthOf(node: ObjectNode): Int {
            var d = 0; var cur = node
            while (true) { val parent = byPayloadId[cur.path("parent").asText("")] ?: break; d += 1; cur = parent; if (d > 12) break }
            return d
        }
        val dropped = mutableSetOf<String>()
        all.forEach { node ->
            val id = node.path("id").asText("")
            if (id in existingByPayloadId || id !in selected) { dropped += id; return@forEach }
            val level = node.path("level").takeIf { it.isInt }?.asInt() ?: depthOf(node)
            if (options.depth != null && level > options.depth) dropped += id
        }
        // потомок неберущегося родителя не берётся: дерево не рвётся на пустом родителе
        var changed = true
        while (changed) {
            changed = false
            all.forEach { node ->
                val parent = node.path("parent").asText(""); val id = node.path("id").asText("")
                if (parent.isNotBlank() && parent in dropped && parent !in existingByPayloadId && id !in dropped) { dropped += id; changed = true }
            }
        }
        val raw = all.filter { it.path("id").asText("") !in dropped }
        if (raw.isEmpty()) {
            return ApplyOutcome(created = extraCreated, existing = existingByPayloadId.values.distinct(), removed = removed)
        }
        // родители раньше детей
        val ids = raw.map { it.path("id").asText("") }.toSet()
        val list = mutableListOf<ObjectNode>()
        val placed = mutableSetOf<String>()
        while (list.size < raw.size) {
            val next = raw.filter { o -> o.path("id").asText("") !in placed && o.path("parent").asText("").let { it.isBlank() || it !in ids || it in placed } }
            require(next.isNotEmpty()) { "fragment payload has a parent cycle" }
            next.forEach { list += it; placed += it.path("id").asText("") }
        }

        // отказ — ДО первой записи и только для настоящей ошибки: ссылка на
        // элемент, которого нет ни в проекте, ни среди выбранного, ни на полках
        val byCode = boundary.objects.listCurrent(projectId)
            .filter { it.status != orbita.mod.model.Lifecycle.Cancelled }
            .mapNotNull { obj -> obj.doc.path("code").asText("").takeIf { it.isNotBlank() }?.let { it to obj.id } }
            .toMap()
        val takingIds = list.map { it.path("id").asText("") }.toSet()
        val problems = mutableListOf<String>()
        val name = frag.doc.path("name").asText(fragmentId)
        list.forEach { o ->
            val text = mapper.writeValueAsString(o)
            val who = o.path("code").asText("").ifBlank { o.path("name").asText(o.path("id").asText("")) }
            codeRefs.findAll(text).map { it.groupValues[1] }.toSet().filter { !byCode.containsKey(it) }.forEach { code ->
                val shelf = shelfOfCode(code)
                problems += if (shelf != null) {
                    "«$who» требует «$code»: возьмите его из полки «${shelf.doc.path("name").asText(shelf.id)}» тем же подтверждением или снимите «$who»"
                } else {
                    "«$who» ссылается на код «$code», которого нет ни в проекте, ни на полках"
                }
            }
            byPayloadId.keys.filter { other -> other != o.path("id").asText("") && other !in takingIds && other !in existingByPayloadId &&
                Regex("\\b" + Regex.escape(other) + "\\b").containsMatchIn(text) }.forEach { other ->
                val dep = byPayloadId[other]!!.let { it.path("code").asText("").ifBlank { it.path("name").asText(other) } }
                problems += "«$who» требует «$dep» из этой же полки: выберите его тоже или снимите «$who»"
            }
        }
        if (problems.isNotEmpty()) {
            throw IllegalArgumentException("полка «$name» с таким выбором не берётся: " + problems.distinct().joinToString("; "))
        }

        // идентификаторы: nextId даёт базу по виду, дальше — счёт
        val remap = mutableMapOf<String, String>()
        val created = mutableListOf<Pair<String, String>>()
        val counters = mutableMapOf<CoreType, Int>()
        list.forEach { o ->
            val oldId = o.path("id").asText("")
            val type = typeOfPayloadId(oldId) ?: throw IllegalArgumentException("unknown id prefix in fragment payload: '$oldId'")
            val n = counters.getOrPut(type) { boundary.editing.nextId(type).substringAfterLast('-').toInt() }
            counters[type] = n + 1
            remap[oldId] = "%s-%04d".format(type.idPrefix, n)
            created += oldId to remap[oldId]!!
        }
        existingByPayloadId.forEach { (payloadId, existingId) -> remap[payloadId] = existingId }

        list.forEach { o ->
            val oldId = o.path("id").asText("")
            // default_take — данные полки для окна взятия, не поле объекта
            o.remove("default_take")
            var text = mapper.writeValueAsString(o)
            remap.forEach { (old, new) -> text = text.replace(Regex("\\b" + Regex.escape(old) + "\\b"), new) }
            text = codeRefs.replace(text) { m -> byCode[m.groupValues[1]] ?: m.value }
            val doc = mapper.readTree(text) as ObjectNode
            val type = CoreType.entries.first { remap[oldId]!!.startsWith(it.idPrefix + "-") }
            if (boundary.schemaAllows(type, "applies") && !doc.has("applies")) {
                doc.putObject("applies").put("ref", fragmentId).put("status", "applied")
            }
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
        return ApplyOutcome(created = extraCreated + created, existing = existingByPayloadId.values.distinct(), removed = removed)
    }
}
