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
    val missing: List<DataRequestField> get() = fields.filter { it.required && !it.filled }
}

class DataRequests(private val boundary: Boundary) {

    private val mapper = ObjectMapper()

    /** Роль анкеты → вид объекта модели, который её закрывает. */
    private val holderTypes = mapOf(
        "platform" to "spacecraft",
        "payload" to "spacecraft",
        "terminal" to "terminal_profile",
        "ground_station" to "ground_stations",
    )

    /**
     * Запросы данных проекта: анкеты класса миссии, наложенные на то, что
     * уже есть в модели. Подсказка поля берётся из глоссария (механика Ф-03),
     * если анкета назвала терм.
     */
    fun of(projectId: String): List<DataRequest> {
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
                val holder = holderTypes[role]?.let { t -> own.firstOrNull { it.type == t } }
                val fields = form.doc.path("fields").map { f ->
                    val key = f.path("key").asText()
                    val target = f.path("target").asText("")
                    val inModel = holder?.doc?.let { doc ->
                        if (target.isBlank()) null else doc.at(target).takeIf { !it.isMissingNode && !it.isNull }
                    }
                    val harvested = fromDatasheets[key]
                    DataRequestField(
                        key = key,
                        name = f.path("name").asText(),
                        unit = f.path("unit").asText("").ifBlank { null },
                        required = f.path("required").asBoolean(false),
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
    fun missingSummary(projectId: String): List<String> =
        of(projectId).flatMap { r -> r.missing.map { "${r.name}: ${it.name}" } }

    fun toJson(requests: List<DataRequest>) = mapper.createArrayNode().apply {
        requests.forEach { r ->
            val node = addObject()
                .put("form", r.form).put("name", r.name).put("role", r.role)
                .put("missing", r.missing.size)
            r.note?.let { node.put("note", it) }
            r.target?.let { node.put("holder", it) }
            val arr = node.putArray("fields")
            r.fields.forEach { f ->
                val fn = arr.addObject()
                    .put("key", f.key).put("name", f.name)
                    .put("required", f.required).put("filled", f.filled)
                    .put("kind", f.kind)
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
