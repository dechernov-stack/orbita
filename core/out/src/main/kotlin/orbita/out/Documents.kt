// Генерация документов из модели (TZ-OUT-001, STEP-6 §2.1).
// Эталон spec/presentation_semantics.py, один в один.
//
// Генерация — ЧИСТАЯ ФУНКЦИЯ МОДЕЛИ: повторный вызов даёт идентичный результат,
// модель не изменяется. Следствие, о котором предупреждает регламент: ручное
// дополнение текста после генерации не сохраняется — его негде хранить, документ
// целиком выводится из модели. Правка вносится в модель, а не в документ.
package orbita.out

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.security.MessageDigest

/** Шаблоны первой очереди: приложения 2–4 регламента БП-PA. */
enum class DocumentTemplate(val code: String, val title: String) {
    RequirementSpecification("req_spec", "Спецификация требований"),
    ConOps("conops", "Концепция применения (заготовка)"),
    ArchitectureDescription("architecture", "Описание архитектуры"),
    ;

    companion object {
        fun of(code: String): DocumentTemplate = entries.firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("неизвестный шаблон документа: $code")
    }
}

/** Документ: тело и слепок содержимого для сверки воспроизводимости. */
data class GeneratedDocument(val template: DocumentTemplate, val body: ObjectNode, val digest: String)

class DocumentGenerator(private val mapper: ObjectMapper = ObjectMapper()) {

    /**
     * Сборка документа из выгрузки модели. Функция не принимает изменяемого
     * состояния и ничего не пишет: модель после вызова та же, что и до.
     */
    fun render(model: JsonNode, template: DocumentTemplate): GeneratedDocument {
        val body = mapper.createObjectNode()
        body.put("template", template.code)
        body.put("title", template.title)
        val items = body.putArray("items")
        when (template) {
            DocumentTemplate.RequirementSpecification -> model.path("requirements").forEach { r ->
                val n = items.addObject()
                n.put("id", r.path("id").asText())
                n.put("statement", r.path("statement").asText(""))
                n.put("category", r.path("category").asText(""))
                n.put("lifecycle", r.path("lifecycle").path("status").asText(""))
                r.path("mop").takeIf { !it.isMissingNode && !it.isEmpty }
                    ?.let { n.set<ObjectNode>("mop", it) }
            }
            DocumentTemplate.ConOps -> model.path("needs").forEach { nd ->
                val n = items.addObject()
                n.put("id", nd.path("id").asText())
                n.put("statement", nd.path("statement").asText(""))
                n.put("stakeholder", nd.path("stakeholder").asText(""))
            }
            DocumentTemplate.ArchitectureDescription -> model.path("components").forEach { c ->
                val n = items.addObject()
                n.put("id", c.path("id").asText())
                n.put("name", c.path("name").asText(""))
                n.put("parent", c.path("parent").asText(""))
            }
        }
        return GeneratedDocument(template, body, digestOf(body))
    }

    private fun digestOf(body: JsonNode): String =
        MessageDigest.getInstance("SHA-256").digest(canonical(body).toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }

    /** Каноническая форма: порядок полей не влияет на слепок. */
    private fun canonical(node: JsonNode): String = when {
        node.isObject -> node.properties().sortedBy { it.key }
            .joinToString(",", "{", "}") { (k, v) -> "\"$k\":${canonical(v)}" }
        node.isArray -> node.joinToString(",", "[", "]") { canonical(it) }
        node.isTextual -> "\"${node.asText()}\""
        node.isNull -> "null"
        else -> node.asText()
    }
}
