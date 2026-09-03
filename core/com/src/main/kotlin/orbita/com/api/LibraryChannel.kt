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
        val withOptional: Boolean = false,
        /** код узла каркаса → идентификатор уже заведённого узла проекта. */
        val mapping: Map<String, String> = emptyMap(),
        /**
         * Замечание Б3-01: подтверждение опциональных узлов — взятие ТОЛЬКО
         * названных кодов (и их потомков) из уже взятого каркаса; родители
         * разрешаются в узлы проекта по коду.
         */
        val onlyCodes: Set<String>? = null,
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
    /** Пропущенная при взятии запись: чего ждёт и почему (замечание Б3-01). */
    data class Skipped(val from: String, val code: String, val name: String, val on: List<String>, val reason: String)

    data class ApplyOutcome(
        val created: List<Pair<String, String>>,
        val existing: List<String>,
        val skipped: List<Skipped> = emptyList(),
    )

    /**
     * Коды опциональных узлов и стыков ВСЕХ полок библиотеки: по ним канал
     * отличает «узел не подтверждён» от «полки нет». Это данные полок, не догадка.
     */
    private fun optionalCodesOnShelves(): Set<String> =
        boundary.objects.listCurrent(orbita.mod.store.ObjectStore.LIBRARY_PROJECT)
            .filter { it.type == "library_fragment" && it.status != orbita.mod.model.Lifecycle.Cancelled }
            .flatMap { f -> f.doc.path("payload").path("objects").toList() }
            .filter { it.path("optional").asBoolean(false) }
            .mapNotNull { it.path("code").asText("").ifBlank { null } }
            .toSet()

    /** Полка библиотеки, чья пачка заводит объект с этим кодом, — для текста отказа. */
    private fun shelfOfCode(code: String): orbita.mod.store.StoredObject? =
        boundary.objects.listCurrent(orbita.mod.store.ObjectStore.LIBRARY_PROJECT)
            .filter { it.type == "library_fragment" && it.status != orbita.mod.model.Lifecycle.Cancelled }
            .firstOrNull { f -> f.doc.path("payload").path("objects").any { it.path("code").asText("") == code } }

    /**
     * Замечание Б3-01 п. 1: подтвердить опциональные узлы каркаса — завести
     * названные коды (с потомками) из уже взятого каркаса. Идёт тем же взятием
     * с отбором по кодам: родители разрешаются в узлы проекта по коду, дубли не
     * плодятся.
     */
    fun confirmOptional(fragmentId: String, projectId: String, codes: Set<String>, author: String): ApplyOutcome {
        require(codes.isNotEmpty()) { "не названо ни одного кода узла для подтверждения" }
        return apply(fragmentId, projectId, author, TakeOptions(withOptional = true, onlyCodes = codes))
    }

    /**
     * Замечание Б3-01 п. 3: ДОБОР — после подтверждения узла повторное взятие
     * всех полок, уже применённых к проекту, создаёт только теперь разрешимые
     * записи; взятое остаётся на месте, повтор без новых — ноль.
     */
    data class TopUp(
        val fragment: String, val name: String, val created: Int, val skipped: Int,
        /** Что именно добрано — экран называет записи, а не число. */
        val createdIds: List<String> = emptyList(),
    )

    fun topUp(projectId: String, author: String): List<TopUp> {
        val applied = boundary.objects.listCurrent(projectId)
            .filter { it.status != orbita.mod.model.Lifecycle.Cancelled }
            .mapNotNull { it.doc.path("applies").path("ref").asText("").ifBlank { null } }
            .distinct().sorted()
        return applied.mapNotNull { ref ->
            val frag = boundary.objects.current(ref) ?: return@mapNotNull null
            val outcome = apply(ref, projectId, author, TakeOptions())
            TopUp(ref, frag.doc.path("name").asText(ref), outcome.created.size, outcome.skipped.size,
                outcome.created.map { it.second })
        }
    }

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
        // идемпотентность — ПО ЗАПИСЯМ, а не по полке целиком (замечание Б3-01):
        // взятое раньше узнаётся по коду (без кода — по имени) и не создаётся
        // заново, а ссылки на него разрешаются; новые записи — добор
        val alive = appliedInstances(fragmentId, projectId)
        val aliveByCode = alive.filter { it.doc.path("code").asText("").isNotBlank() }
            .associateBy { it.doc.path("code").asText() }
        // вид без поля applies (стейкхолдер-актор) связи «применяет» не несёт —
        // взятое раньше узнаётся по виду и имени среди живых объектов проекта,
        // иначе добор плодил бы акторов при каждом повторе
        val aliveByName = (alive + boundary.objects.listCurrent(projectId)
            .filter { it.status != orbita.mod.model.Lifecycle.Cancelled }
            .filter { o -> CoreType.entries.firstOrNull { it.dbType == o.type }?.let { !boundary.schemaAllows(it, "applies") } ?: false })
            .filter { it.doc.path("code").asText("").isBlank() }
            .associateBy { it.type + "|" + it.doc.path("name").asText("").lowercase().trim() }
        val objects = frag.doc.path("payload").path("objects")
        require(objects.isArray && objects.size() > 0) { "fragment '$fragmentId' payload is empty" }

        // Отбор каркаса (ADR-051): глубина уровней, необязательные узлы и те,
        // что в проекте уже есть. Потомок неберущегося узла не берётся тоже —
        // иначе дерево порвётся на пустом родителе.
        val all = objects.map { it.deepCopy<ObjectNode>() }
        val byPayloadId = all.associateBy { it.path("id").asText("") }
        // Глубина считается ПО ДЕРЕВУ, а не по имени уровня: в каркасе имя
        // уровня и его глубина расходятся (подсистема платформы лежит на
        // четвёртом уровне), и брать «до L4» надо по родителям.
        fun depthOf(node: ObjectNode): Int {
            var d = 0
            var cur = node
            while (true) {
                val parent = byPayloadId[cur.path("parent").asText("")] ?: break
                d += 1
                cur = parent
                if (d > 12) break
            }
            return d
        }
        val dropped = mutableSetOf<String>()
        val skipped = mutableListOf<Skipped>()
        // уже взятое из этой полки: ссылки на него разрешаются, само не создаётся
        val existingByPayloadId = mutableMapOf<String, String>()
        all.forEach { node ->
            val code = node.path("code").asText("")
            val hit = options.mapping[code]
                ?: aliveByCode[code]?.id
                ?: if (code.isBlank()) {
                    val type = CoreType.entries.firstOrNull { node.path("id").asText("").startsWith(it.idPrefix + "-") }
                    aliveByName[type?.dbType + "|" + node.path("name").asText("").lowercase().trim()]?.id
                } else null
            if (hit != null) existingByPayloadId[node.path("id").asText("")] = hit
        }
        // подтверждение опциональных: берутся только названные коды и их потомки
        val chosen: Set<String>? = options.onlyCodes?.let { codes ->
            val ids = mutableSetOf<String>()
            all.filter { it.path("code").asText("") in codes }.forEach { ids += it.path("id").asText("") }
            var grew = true
            while (grew) {
                grew = false
                all.forEach { n ->
                    val id = n.path("id").asText("")
                    if (id !in ids && n.path("parent").asText("") in ids) { ids += id; grew = true }
                }
            }
            ids
        }
        all.forEach { node ->
            val id = node.path("id").asText("")
            if (id in existingByPayloadId) { dropped += id; return@forEach }
            if (chosen != null && id !in chosen) { dropped += id; return@forEach }
            // уровень каркаса — данные узла; без него меряем глубиной по дереву
            val level = node.path("level").takeIf { it.isInt }?.asInt() ?: depthOf(node)
            val tooDeep = options.depth != null && level > options.depth
            // собственный флаг optional гасит ТОЛЬКО узел каркаса (у него нет
            // optional_on): стык, функция, пакет работ с optional_on берутся,
            // если их узлы подтверждены, и пропускаются резолвом, если нет
            val optionalOut = !options.withOptional && chosen == null &&
                node.path("optional").asBoolean(false) && node.path("optional_on").isEmpty
            if (optionalOut) {
                // необязательный узел каркаса: по подтверждению — не отказ и не
                // молчание, а помета, из которой видно, что подтверждать
                skipped += Skipped(id, node.path("code").asText(""), node.path("name").asText(""),
                    listOf(node.path("code").asText("")), "необязательный узел: создаётся по подтверждению")
            }
            if (tooDeep || optionalOut) dropped += id
        }
        var changed = true
        while (changed) {
            changed = false
            all.forEach { node ->
                val parent = node.path("parent").asText("")
                val id = node.path("id").asText("")
                val parentPresent = parent in existingByPayloadId
                if (parent.isNotBlank() && parent in dropped && !parentPresent && id !in dropped) {
                    dropped += id
                    changed = true
                }
            }
        }
        val raw = all.filter { it.path("id").asText("") !in dropped }
        if (raw.isEmpty()) {
            // всё уже взято либо всё ждёт подтверждения — это итог, а не отказ
            return ApplyOutcome(created = emptyList(), existing = existingByPayloadId.values.distinct(), skipped = skipped)
        }
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
        // уже заведённое (по сопоставлению или прежним взятием): ссылки ведут
        // на него, само заново не создаётся (дубли запрещены)
        existingByPayloadId.forEach { (payloadId, existingId) -> remap[payloadId] = existingId }
        // ADR-052: ссылка ПОЛКИ НА ПОЛКУ пишется кодом — «@PL-S», «@IF-S-USER».
        // Идентификатора у неё быть не может: узел получает его в том проекте,
        // куда каркас взят, и архитектурная полка обязана попасть в него же.
        val byCode = boundary.objects.listCurrent(projectId)
            .filter { it.status != orbita.mod.model.Lifecycle.Cancelled }
            .mapNotNull { obj -> obj.doc.path("code").asText("").takeIf { it.isNotBlank() }?.let { it to obj.id } }
            .toMap()
        val codeRefs = Regex("@([A-Za-zА-Яа-я0-9_.\\-]+)")
        val unresolved = linkedSetOf<String>()
        // Замечание Б3-01: код отсутствует И запись ждёт опционального узла
        // (свой optional_on либо узел помечен опциональным на полке) — запись
        // ПРОПУСКАЕТСЯ с пометой, полка берётся. Отсутствует и не опционален —
        // отказ до первой записи, с причиной: «полка не взята» или «узлы не
        // подтверждены» — это разные починки. Пропущенная запись делает
        // опциональными и тех, кто ссылается на её код (каскад по данным).
        val optionalOnShelves = optionalCodesOnShelves()
        val skippedCodes = mutableSetOf<String>()
        // пачка ссылается на своих и по идентификатору (шаг цепочки → функция):
        // пропущенная запись тянет за собой тех, кто на неё ссылается, — иначе
        // цепочка ушла бы на запись с несуществующей функцией и уронила взятие
        // посередине, чего проверка «до первой записи» и не допускает
        val skippedPayloadIds = mutableSetOf<String>()
        val toWrite = mutableListOf<ObjectNode>()
        var pending = list.toList()
        var again = true
        while (again) {
            again = false
            val rest = mutableListOf<ObjectNode>()
            pending.forEach { o ->
                val text = mapper.writeValueAsString(o)
                val missing = codeRefs.findAll(text).map { it.groupValues[1] }.filter { !byCode.containsKey(it) }.toSet()
                // объявленная зависимость: узел из optional_on отсутствует — запись
                // ждёт подтверждения, даже если кодом на него не ссылается
                val waitsFor = o.path("optional_on").map { it.asText() }.toSet()
                val waitsMissing = waitsFor.filter { !byCode.containsKey(it) && it !in skippedCodes }
                val dependsOnSkipped = skippedPayloadIds.filter { sid -> Regex("\\b" + Regex.escape(sid) + "\\b").containsMatchIn(text) }
                val optionalMissing = missing.filter { it in waitsFor || it in optionalOnShelves || it in skippedCodes }
                when {
                    missing.isEmpty() && waitsMissing.isEmpty() && dependsOnSkipped.isEmpty() -> toWrite += o
                    optionalMissing.size == missing.size -> {
                        val причины = (optionalMissing + waitsMissing).toSortedSet().toList()
                        val ждёт = if (причины.isNotEmpty()) причины else
                            dependsOnSkipped.mapNotNull { sid -> byPayloadId[sid]?.path("code")?.asText("")?.ifBlank { null } }
                        skipped += Skipped(
                            o.path("id").asText(""), o.path("code").asText(""), o.path("name").asText(""),
                            ждёт, "пропущено: не подтверждён " + ждёт.joinToString(", "),
                        )
                        skippedPayloadIds += o.path("id").asText("")
                        o.path("code").asText("").takeIf { it.isNotBlank() }?.let { skippedCodes.add(it) }
                        again = true
                    }
                    else -> rest += o
                }
            }
            pending = rest
        }
        pending.forEach { o ->
            codeRefs.findAll(mapper.writeValueAsString(o)).forEach { m ->
                if (!byCode.containsKey(m.groupValues[1]) && m.groupValues[1] !in skippedCodes) unresolved += m.groupValues[1]
            }
        }
        if (unresolved.isNotEmpty()) {
            val shelves = unresolved.mapNotNull { shelfOfCode(it) }.distinctBy { it.id }
            val notTaken = shelves.filter { appliedInstances(it.id, projectId).isEmpty() }
            val cause = if (notTaken.isNotEmpty()) {
                "полка не взята: " + notTaken.joinToString(", ") { "«${it.doc.path("name").asText(it.id)}»" } +
                    " — возьмите её раньше"
            } else {
                "узлов с этими кодами в проекте нет и на полках они не помечены необязательными — проверьте состав"
            }
            throw IllegalArgumentException(
                "полка «${frag.doc.path("name").asText(fragmentId)}» ссылается на коды, которых в проекте нет: " +
                    unresolved.joinToString(", ") + ". Причина: $cause " +
                    "(каркас PBS — узлы, полка интерфейсов — стыки)",
            )
        }
        val writable: List<ObjectNode> = toWrite

        writable.forEach { o ->
            val oldId = o.path("id").asText("")
            var text = mapper.writeValueAsString(o)
            remap.forEach { (old, new) -> text = text.replace(Regex("\\b" + Regex.escape(old) + "\\b"), new) }
            text = codeRefs.replace(text) { m ->
                val code = m.groupValues[1]
                byCode[code] ?: run { unresolved += code; m.value }
            }
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
        val writtenFrom = writable.map { it.path("id").asText("") }.toSet()
        return ApplyOutcome(
            created = created.filter { it.first in writtenFrom },
            existing = existingByPayloadId.values.distinct(),
            skipped = skipped,
        )
    }
}
