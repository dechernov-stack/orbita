// Ф-06: библиотека ЗАПРАШИВАЕТ данные, а не ждёт молча. Инженер «стоял и
// смотрел на неведомые идеи» — система обязана сама называть, какие данные
// ей нужны и в каком формате.
//
// Анкета характеристик носителя (полка LIB, вид property_form) объявляет
// поля роли: имя · единица справочника · обязательность · подсказка. Здесь
// эти анкеты превращаются в ЗАПРОСЫ: что уже заполнено, чего не хватает,
// откуда это можно взять. Незаполненное обязательное — разрыв готовности,
// а не тишина; заполнить его можно тремя путями, и все три названы.
package orbita.com.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.store.ObjectStore

/** Поле анкеты в состоянии «спрошено»: заполнено или нет и чем заполнять. */
data class DataRequestField(
    val key: String,
    val name: String,
    val unit: String?,
    val required: Boolean,
    /** Ф-06 п.5: точка, к которой поле обязано быть задано; null — без срока. */
    val requiredBy: String?,
    /** Спрошено сейчас: точка поля — ближайшая. Иначе приглашение. */
    val dueNow: Boolean,
    val hint: String?,
    val kind: String,
    val options: List<String>,
    val filled: Boolean,
    val value: String?,
    /** Откуда пришло значение: model — из документа модели, harvest — из даташита. */
    val from: String?,
)

/** Запрос данных: анкета роли с полями и состоянием заполнения. */
data class DataRequest(
    val form: String,
    val name: String,
    val role: String,
    val note: String?,
    val target: String?,
    val fields: List<DataRequestField>,
) {
    /**
     * Ф-06 п.5: разрывом считается только то, что требуется к БЛИЖАЙШЕЙ точке.
     * В Pre-A анкеты железа — приглашение: «данные не заданы» не горит там,
     * где срок ещё не пришёл. Заполнить раньше срока законно.
     */
    val missing: List<DataRequestField> get() = fields.filter { it.required && it.dueNow && !it.filled }

    /** Приглашения: спрошено, но срок ещё не наступил. */
    val invited: List<DataRequestField> get() = fields.filter { !it.filled && !it.dueNow }
}

class DataRequests(private val boundary: Boundary) {

    private val mapper = ObjectMapper()

    /**
     * Роль анкеты → чем она закрывается. Платформа и ПН — узлы одного дерева
     * состава с той же ролью (ADR-044): анкета платформы = параметры узла
     * «Платформа». Терминал и станция — пока объекты входов моделирования.
     */
    private val holderTypes = mapOf(
        "terminal" to "terminal_profile",
        "ground_station" to "ground_stations",
    )
    private val holderRoles = setOf("platform", "payload")

    /**
     * Запросы данных проекта: анкеты класса миссии, наложенные на то, что
     * уже есть в модели. Подсказка поля берётся из глоссария (механика Ф-03),
     * если анкета назвала терм.
     */
    fun of(projectId: String): List<DataRequest> {
        // ближайшая непройденная точка проекта — по ней и меряется зрелость
        val nextGate = runCatching { boundary.gatePassing.nextGate(projectId) }.getOrNull()
        val lib = boundary.objects.listCurrent(ObjectStore.LIBRARY_PROJECT)
            .filter { it.status.name != "Cancelled" }
        val classRef = boundary.objects.current(projectId)?.doc?.path("mission_class")?.asText("") ?: ""
        val terms = lib.filter { it.type == "glossary" }
            .flatMap { g -> g.doc.path("entries").map { it.path("term").asText() to it.path("brief").asText("") } }
            .toMap()
        val own = boundary.objects.listCurrent(projectId).filter { it.status.name != "Cancelled" }
        // значения, извлечённые из даташитов (Д2, класс property): ключ поля
        // анкеты — в form_field, координата блока живёт в самом урожае
        val fromDatasheets = harvestedProperties(projectId)

        return lib.filter { it.type == "property_form" }
            .filter { f ->
                val ref = f.doc.path("mission_class_ref").asText("")
                ref.isBlank() || classRef.isBlank() || ref == classRef
            }
            .sortedBy { it.id }
            .map { form ->
                val role = form.doc.path("role").asText("")
                val holder = if (role in holderRoles) {
                    own.firstOrNull { it.type == "component" && orbita.out.CarrierAssembly.role(it.doc) == role }
                } else {
                    holderTypes[role]?.let { t -> own.firstOrNull { it.type == t } }
                }
                val fields = form.doc.path("fields").map { f ->
                    val key = f.path("key").asText()
                    val target = f.path("target").asText("")
                    // узел дерева: поле анкеты закрыто параметром узла с тем же
                    // именем; объект входов — значением по адресу поля
                    val fieldKind = f.path("kind").asText("number")
                    val inModel = holder?.doc?.let { doc ->
                        if (role in holderRoles) {
                            if (fieldKind == "table") {
                                // [Т]-поле: таблица живёт на узле (манёвры — на узле КА,
                                // родителе платформы) — заполнено, если строки есть
                                val parentDoc = doc.path("parent").asText("").ifBlank { null }
                                    ?.let { pid -> own.firstOrNull { it.id == pid }?.doc }
                                listOfNotNull(doc.path(key), parentDoc?.path(key))
                                    .firstOrNull { it.isArray && !it.isEmpty }
                                    ?.let { mapper.valueToTree<JsonNode>(it.size()) }
                            } else {
                                doc.path("parameters").firstOrNull { it.path("name").asText("") == key }
                                    ?.path("quantity")?.path("value")?.takeIf { it.isNumber }
                            }
                        } else if (target.isBlank()) null else doc.at(target).takeIf { !it.isMissingNode && !it.isNull }
                    }
                    val harvested = fromDatasheets[key]
                    val requiredBy = f.path("required_by").asText("").ifBlank { null }
                    DataRequestField(
                        key = key,
                        name = f.path("name").asText(),
                        unit = f.path("unit").asText("").ifBlank { null },
                        required = f.path("required").asBoolean(false),
                        requiredBy = requiredBy,
                        dueNow = requiredBy != null && requiredBy == nextGate,
                        hint = f.path("hint_term").asText("").ifBlank { null }?.let { terms[it] }
                            ?: f.path("hint").asText("").ifBlank { null },
                        kind = f.path("kind").asText("number"),
                        options = f.path("options").map { it.asText() },
                        filled = inModel != null || harvested != null,
                        value = inModel?.asText() ?: harvested?.first,
                        from = when {
                            inModel != null -> "model"
                            harvested != null -> "harvest:${harvested.second}"
                            else -> null
                        },
                    )
                }
                DataRequest(
                    form = form.id,
                    name = form.doc.path("name").asText(form.id),
                    role = role,
                    note = form.doc.path("note").asText("").ifBlank { null },
                    target = holder?.id,
                    fields = fields,
                )
            }
    }

    /**
     * Значения из даташитов: урожай смыслового разбора (Д2) класса property
     * несёт имя поля анкеты (form_field) — так приложенный даташит
     * предзаполняет форму, а координата блока остаётся при значении.
     */
    private fun harvestedProperties(projectId: String): Map<String, Pair<String, String>> {
        val out = mutableMapOf<String, Pair<String, String>>()
        boundary.objects.listCurrent(projectId)
            .filter { it.type == "source_document" && it.status.name != "Cancelled" }
            .forEach { sd ->
                val harvest = DocumentHarvest.of(filesDir(), sd.id) ?: return@forEach
                harvest.path("items")
                    .filter { it.path("class").asText() == "property" }
                    .forEach { item ->
                        val key = item.path("form_field").asText("")
                        if (key.isBlank()) return@forEach
                        val shown = DocumentHarvest.displayOf(item).ifBlank {
                            item.path("statement").asText("").ifBlank { item.path("name").asText("") }
                        }
                        val blocks = DocumentHarvest.blocksOf(item).joinToString(", ")
                        out.putIfAbsent(key, shown to "${sd.id} · $blocks")
                    }
            }
        return out
    }

    private fun filesDir(): String =
        System.getProperty("orbita.test.filesDir")
            ?: System.getenv("ORBITA_FILES_DIR")
            ?: "files"

    /** Незакрытые обязательные поля — строками для разрыва готовности. */
    /**
     * Каркас PBS ред. 2 (ADR-051): у узла своя анкета — поля, которые он обязан
     * нести, и точка их зрелости. Она приходит с каркасом (expects) и живёт на
     * узле: спрашивать характеристики «вообще» бессмысленно, спрашивают у узла.
     */
    fun ofNodes(projectId: String): List<DataRequest> {
        val nextGate = runCatching { boundary.gatePassing.nextGate(projectId) }.getOrNull()
        return boundary.objects.listCurrent(projectId)
            .filter { it.type == "component" && it.status.name != "Cancelled" && !it.doc.path("expects").isEmpty }
            .sortedBy { it.id }
            .map { node ->
                val values = node.doc.path("parameters").associate { p ->
                    p.path("name").asText("") to p.path("quantity").path("value")
                }
                DataRequest(
                    form = node.id,
                    name = "${node.doc.path("name").asText(node.id)} · анкета узла",
                    role = "node",
                    note = node.doc.path("code").asText("").ifBlank { null },
                    target = node.id,
                    fields = node.doc.path("expects").map { f ->
                        val key = f.path("key").asText()
                        val requiredBy = f.path("required_to").asText("").ifBlank { null }
                        val value = values[key]
                        DataRequestField(
                            key = key,
                            name = f.path("name").asText(key),
                            unit = f.path("unit").asText("").ifBlank { null },
                            required = requiredBy != null,
                            requiredBy = requiredBy,
                            dueNow = requiredBy != null && requiredBy == nextGate,
                            hint = f.path("hint").asText("").ifBlank { null },
                            kind = "number",
                            options = emptyList(),
                            filled = value != null && !value.isMissingNode && !value.isNull,
                            value = value?.takeIf { !it.isMissingNode && !it.isNull }?.asText(),
                            from = if (value != null && !value.isMissingNode) "model" else null,
                        )
                    },
                )
            }
    }

    /**
     * ADR-052: анкета СТЫКА — те же поля с точками зрелости, что у узла, но
     * спрашиваются они у ребра: запас линка принадлежит стыку, а не одному из
     * его концов, и делить его между двумя узлами значило бы задать дважды.
     */
    fun ofInterfaces(projectId: String): List<DataRequest> {
        val nextGate = runCatching { boundary.gatePassing.nextGate(projectId) }.getOrNull()
        val nodes = boundary.objects.listCurrent(projectId)
            .filter { it.type == "component" }.associateBy { it.id }
        return boundary.objects.listCurrent(projectId)
            .filter { it.type == "interface" && it.status.name != "Cancelled" && !it.doc.path("expects").isEmpty }
            .sortedBy { it.id }
            .map { iface ->
                val values = iface.doc.path("parameters").associate { p ->
                    p.path("name").asText("") to p.path("quantity").path("value")
                }
                val стороны = iface.doc.path("owners").mapNotNull { nodes[it.asText()]?.doc?.path("name")?.asText() }
                DataRequest(
                    form = iface.id,
                    name = "${iface.doc.path("name").asText(iface.id)} · анкета стыка",
                    role = "interface",
                    note = listOfNotNull(
                        iface.doc.path("code").asText("").ifBlank { null },
                        стороны.takeIf { it.size == 2 }?.joinToString(" ↔ "),
                    ).joinToString(" · ").ifBlank { null },
                    target = iface.id,
                    fields = iface.doc.path("expects").map { f ->
                        val key = f.path("key").asText()
                        val requiredBy = f.path("required_to").asText("").ifBlank { null }
                        val value = values[key]
                        DataRequestField(
                            key = key,
                            name = f.path("name").asText(key),
                            unit = f.path("unit").asText("").ifBlank { null },
                            required = requiredBy != null,
                            requiredBy = requiredBy,
                            dueNow = requiredBy != null && requiredBy == nextGate,
                            hint = f.path("hint").asText("").ifBlank { null },
                            kind = "number",
                            options = emptyList(),
                            filled = value != null && !value.isMissingNode && !value.isNull,
                            value = value?.takeIf { !it.isMissingNode && !it.isNull }?.asText(),
                            from = if (value != null && !value.isMissingNode) "model" else null,
                        )
                    },
                )
            }
    }

    fun missingSummary(projectId: String): List<String> =
        (of(projectId) + ofNodes(projectId) + ofInterfaces(projectId))
            .flatMap { r -> r.missing.map { "${r.name}: ${it.name}" } }

    fun toJson(requests: List<DataRequest>) = mapper.createArrayNode().apply {
        requests.forEach { r ->
            val node = addObject()
                .put("form", r.form).put("name", r.name).put("role", r.role)
                .put("missing", r.missing.size)
                .put("invited", r.invited.size)
            r.note?.let { node.put("note", it) }
            r.target?.let { node.put("holder", it) }
            val arr = node.putArray("fields")
            r.fields.forEach { f ->
                val fn = arr.addObject()
                    .put("key", f.key).put("name", f.name)
                    .put("required", f.required).put("filled", f.filled)
                    .put("due_now", f.dueNow)
                    .put("kind", f.kind)
                f.requiredBy?.let { fn.put("required_by", it) }
                f.unit?.let { fn.put("unit", it) }
                f.hint?.let { fn.put("hint", it) }
                f.value?.let { fn.put("value", it) }
                f.from?.let { fn.put("from", it) }
                if (f.options.isNotEmpty()) {
                    val opts = fn.putArray("options")
                    f.options.forEach { opts.add(it) }
                }
            }
        }
    }
}
