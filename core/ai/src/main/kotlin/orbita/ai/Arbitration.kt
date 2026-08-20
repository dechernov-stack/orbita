// Арбитраж расхождений между LLM (TZ-AI-003). Эталон spec/ai_semantics.py.
//
// Совпавшие фрагменты выделяются ЛОКАЛЬНО и в API не передаются: стоимость
// арбитража на порядок ниже стоимости генерации только при условии, что
// в запрос уходит спор, а не весь ответ.
package orbita.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/** Спорный фрагмент: ключ и варианты по источникам — источник каждого сохраняется. */
data class Disputed(val key: String, val variants: Map<String, JsonNode>)

data class Arbitration(val agreed: List<JsonNode>, val disputed: List<Disputed>)

/**
 * Сопоставление ответов нескольких моделей по ключу. Фрагмент считается
 * согласованным, только если ВСЕ источники его прислали и прислали одинаковым:
 * молчание одной из моделей — тоже расхождение.
 */
fun diffAnswers(answers: Map<String, List<JsonNode>>, key: String = "id"): Arbitration {
    val byKey = linkedMapOf<String, MutableMap<String, JsonNode>>()
    answers.forEach { (source, items) ->
        items.forEach { item ->
            byKey.getOrPut(item.path(key).asText()) { linkedMapOf() }[source] = item
        }
    }
    val agreed = mutableListOf<JsonNode>()
    val disputed = mutableListOf<Disputed>()
    byKey.forEach { (k, variants) ->
        val distinct = variants.values.map { canonicalJson(it) }.toSet()
        if (distinct.size == 1 && variants.size == answers.size) {
            // JsonNode сам является Iterable: += добавил бы его ДЕТЕЙ, а не узел
            agreed.add(variants.values.first())
        } else {
            disputed += Disputed(k, variants)
        }
    }
    return Arbitration(agreed, disputed)
}

/**
 * Полезная нагрузка запроса арбитража: ТОЛЬКО спорные фрагменты.
 * Согласованное в запрос не попадает — это и есть экономия.
 */
fun arbitrationPayload(arbitration: Arbitration, mapper: ObjectMapper = ObjectMapper()): ObjectNode {
    val root = mapper.createObjectNode()
    val arr = root.putArray("disputed")
    arbitration.disputed.forEach { d ->
        val n = arr.addObject()
        n.put("key", d.key)
        val variants = n.putObject("variants")
        d.variants.forEach { (source, item) -> variants.set<ObjectNode>(source, item) }
    }
    return root
}

/**
 * Экономия измеряется числом ПЕРЕДАВАЕМЫХ ФРАГМЕНТОВ, а не байтами: на малом
 * примере обёртка запроса перевешивает экономию и делает измерение ложным.
 */
fun fragmentsSent(arbitration: Arbitration): Int = arbitration.disputed.sumOf { it.variants.size }

fun fragmentsTotal(answers: Map<String, List<JsonNode>>): Int = answers.values.sumOf { it.size }
