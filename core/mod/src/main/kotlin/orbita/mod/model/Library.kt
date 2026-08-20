// Полнота редактируемых библиотек (шаг 10.3).
//
// Поведение — эталон spec/ingestion_semantics.py::library_complete.
//
// Правило одно на все библиотеки: пресеты платформ и сценарии потребления
// проверяются им же. Отдельная «облегчённая» проверка для второй библиотеки
// разошлась бы с первой — ровно как разошлись бы облегчённые правила
// для предложений ИИ на шаге 5.
package orbita.mod.model

import com.fasterxml.jackson.databind.JsonNode

/**
 * Записи библиотеки без обязательных полей. Ключ — идентификатор записи,
 * значение — перечень недостающих полей; пустая карта означает полноту.
 *
 * Пустая строка, пустой список и ноль считаются ОТСУТСТВИЕМ: иначе неполная
 * запись подставила бы умолчание и выглядела бы заполненной. Умолчание
 * в справочнике опаснее пропуска — пропуск виден, умолчание нет.
 */
fun libraryComplete(entries: List<JsonNode>, requiredFields: List<String>): Map<String, List<String>> =
    entries.mapNotNull { entry ->
        val missing = requiredFields.filter { blankField(entry.path(it)) }
        if (missing.isEmpty()) null else entry.path("id").asText("?") to missing
    }.toMap()

/** Отказ библиотеки собраться, если в ней есть неполная запись. */
fun requireLibraryComplete(entries: List<JsonNode>, requiredFields: List<String>, what: String) {
    val incomplete = libraryComplete(entries, requiredFields)
    require(incomplete.isEmpty()) {
        "$what: неполные записи — " + incomplete.entries.joinToString("; ") { (id, fields) ->
            "$id не хватает ${fields.joinToString(", ")}"
        }
    }
}

private fun blankField(node: JsonNode): Boolean = when {
    node.isMissingNode || node.isNull -> true
    node.isTextual -> node.asText().isBlank()
    node.isArray || node.isObject -> node.isEmpty
    node.isNumber -> node.asDouble() == 0.0
    node.isBoolean -> !node.asBoolean()
    else -> false
}
