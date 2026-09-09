// Кандидаты из ЧУЖОГО файла обмена (ADR-064, остаток сноса): формат разбирает
// служба обмена библиотекой и отдаёт объекты с атрибутами по именам, как они
// названы в файле, плюс стандартные поля формата (id · text · name), если
// файл их несёт. Ядро формата не знает: здесь нет ни одного имени атрибута
// стандарта — только объект, его атрибуты и честная догадка, что из них
// код и формулировка. Догадка названа в `used`: человек видит, откуда взято,
// и всё остальное лежит в foreign_attributes без потерь.
package orbita.exchange.api

import com.fasterxml.jackson.databind.JsonNode

/**
 * Кандидат в требование из чужого файла.
 *
 * @property identifier идентификатор объекта в файле
 * @property code код требования: стандартное поле id, иначе атрибут-идентификатор
 *   (uid · id · code · key), иначе identifier
 * @property statement формулировка: стандартное поле text, иначе самое длинное
 *   текстовое значение
 * @property type имя типа объекта в файле — его словами
 * @property attributes все атрибуты как есть
 * @property used что откуда взято: поле кандидата → имя атрибута
 * @property relations связи объекта: код другого кандидата → роль
 */
data class ForeignCandidate(
    val identifier: String,
    val code: String,
    val statement: String,
    val title: String?,
    val type: String,
    val attributes: Map<String, String>,
    val used: Map<String, String>,
    val relations: List<Pair<String, String>>,
) {
    /** Атрибуты сверх того, что легло в код, формулировку и заголовок. */
    val foreign: Map<String, String> get() = attributes.filterKeys { it !in used.values }
}

object ForeignCandidates {
    private val КОД = setOf("uid", "id", "code", "key", "identifier")
    private val ЗАГОЛОВОК = setOf("title", "name", "heading", "summary")

    /** @param parsed ответ службы обмена: `{title, objects[{identifier,type,type_name,values,std}], relations[{source,target,type}]}` */
    fun of(parsed: JsonNode): List<ForeignCandidate> {
        val объекты = parsed.path("objects").toList()
        val кодПоId = объекты.associate { it.path("identifier").asText() to код(it).first }
        val ролиТипов = parsed.path("relation_types").properties().associate { (k, v) -> k to v.asText(k) }
        return объекты.map { о ->
            val (код, откудаКод) = код(о)
            val значения = о.path("values").properties().associate { (k, v) -> k to текст(v) }
            val std = о.path("std")
            val (формулировка, откудаФормулировка) = std.path("text").takeIf { it.isTextual && it.asText().isNotBlank() }
                ?.let { чисто(it.asText()) to "text" }
                ?: (значения.filterKeys { it !in откудаКод.split("|") }.maxByOrNull { it.value.length }
                    ?.let { чисто(it.value) to it.key } ?: ("" to ""))
            val (заголовок, откудаЗаголовок) = std.path("name").takeIf { it.isTextual && it.asText().isNotBlank() }
                ?.let { it.asText() to "name" }
                ?: (значения.entries.firstOrNull { it.key.lowercase() in ЗАГОЛОВОК && it.key != откудаФормулировка }
                    ?.let { it.value to it.key } ?: (null to ""))
            val used = buildMap {
                if (откудаКод.isNotBlank()) put("code", откудаКод)
                if (откудаФормулировка.isNotBlank()) put("statement", откудаФормулировка)
                if (откудаЗаголовок.isNotBlank()) put("title", откудаЗаголовок)
            }
            val id = о.path("identifier").asText()
            val связи = parsed.path("relations").filter { it.path("source").asText() == id }
                .mapNotNull { r -> кодПоId[r.path("target").asText()]?.let { it to ролиТипов[r.path("type").asText()].orEmpty().ifBlank { r.path("type").asText() } } }
            ForeignCandidate(
                identifier = id, code = код, statement = формулировка, title = заголовок,
                type = о.path("type_name").asText(о.path("type").asText()),
                attributes = значения, used = used, relations = связи,
            )
        }
    }

    /** Код и откуда он: стандартное поле id → атрибут-идентификатор → identifier. */
    private fun код(о: JsonNode): Pair<String, String> {
        val std = о.path("std").path("id")
        if (std.isTextual && std.asText().isNotBlank()) return std.asText() to "id"
        val поИмени = о.path("values").properties().firstOrNull { (k, v) -> k.lowercase() in КОД && текст(v).isNotBlank() }
        if (поИмени != null) return текст(поИмени.value) to поИмени.key
        return о.path("identifier").asText() to ""
    }

    private fun текст(v: JsonNode): String = when {
        v.isNull || v.isMissingNode -> ""
        v.isTextual -> v.asText()
        v.isArray -> v.joinToString(", ") { текст(it) }
        else -> v.asText()
    }

    /** Абзацы XHTML — форматирование, не содержание. */
    private fun чисто(t: String): String = t.replace(Regex("</?p>"), "").replace(Regex("\\s+"), " ").trim()
}
