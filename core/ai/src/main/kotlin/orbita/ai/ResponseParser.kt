// Разбор ответа LLM (TZ-AI-002). Эталон spec/ai_semantics.py, один в один.
//
// Разбор ЛОКАЛЬНЫЙ: класс не имеет ни клиента API, ни какой-либо иной внешней
// зависимости — только строка ответа и схема пакета. Корректность гарантируется
// схемой, а не доверием к модели.
package orbita.ai

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.schema.SchemaRegistry
import orbita.mod.schema.ValidationError

/** Отклонённый объект: сам объект (null — не разобрался вовсе) и причины по полям. */
data class Rejected(val item: JsonNode?, val errors: List<String>)

data class ParseResult(val accepted: List<JsonNode>, val rejected: List<Rejected>)

/** Обрамление ```json снимается: модели выдают ответ в разметке чаще, чем без неё. */
private val FENCE = Regex("^```(?:json)?|```$", setOf(RegexOption.MULTILINE))

class ResponseParser(private val mapper: ObjectMapper = ObjectMapper()) {

    /**
     * Разбор ответа по схеме пакета. Частично корректный ответ принимается
     * в части валидных объектов; причина отклонения называется по полю.
     * Неразбираемый ответ не роняет обработку.
     */
    fun parse(raw: String, pkg: PromptPackage): ParseResult {
        val data = try {
            mapper.readTree(FENCE.replace(raw.trim(), "").trim())
        } catch (e: JsonProcessingException) {
            return ParseResult(
                emptyList(),
                listOf(Rejected(null, listOf("ответ не разбирается как JSON: ${e.originalMessage}"))),
            )
        }
        val items = when {
            data.isArray -> data.toList()
            else -> data.path("items").toList()
        }
        val required = pkg.responseSchema?.path("required")?.map { it.asText() }.orEmpty()

        val accepted = mutableListOf<JsonNode>()
        val rejected = mutableListOf<Rejected>()
        items.forEach { item ->
            val errors = required.filterNot { item.has(it) }.map { "нет обязательного поля $it" }
            // add, а не +=: JsonNode — Iterable, и += добавил бы его детей
            if (errors.isEmpty()) accepted.add(item) else rejected += Rejected(item, errors)
        }
        return ParseResult(accepted, rejected)
    }

    /**
     * Полная проверка по нормативной схеме целевого объекта: список required —
     * необходимое условие, но не достаточное. Ошибки перечисляются с путём
     * до поля (TZ-AI-002, ACCEPTANCE 2).
     */
    fun parseAgainstSchema(raw: String, pkg: PromptPackage, registry: SchemaRegistry, schemaName: String): ParseResult {
        val first = parse(raw, pkg)
        val accepted = mutableListOf<JsonNode>()
        val rejected = first.rejected.toMutableList()
        first.accepted.forEach { item ->
            val errors: List<ValidationError> = registry.validate(schemaName, item)
            if (errors.isEmpty()) accepted.add(item)
            else rejected += Rejected(item, errors.map { "${it.path}: ${it.message}" })
        }
        return ParseResult(accepted, rejected)
    }
}
