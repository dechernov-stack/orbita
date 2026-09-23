// База знаний (шип 4 §2): гибридный поиск с фильтрами и перестроение индекса
// проекта. Находка называет, откуда она: блок канона, термин, пункт норматива,
// факт — и её видно словами, не только оценкой.
package orbita.api.internal

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.knowledge.api.KnowledgeIndex

class SearchRoutes(private val index: KnowledgeIndex, private val mapper: ObjectMapper) {
    private val видыСловами = mapOf("canon_block" to "блок документа", "term" to "термин", "clause" to "пункт норматива", "fact" to "факт")

    fun handle(method: String, path: String, query: Map<String, String>): V2Router.Ответ? = when {
        method == "GET" && path == "/v2/search" -> найти(требуется(query), query)
        method == "POST" && path == "/v2/index/rebuild" -> перестроить(требуется(query))
        else -> null
    }

    private fun найти(проект: String, query: Map<String, String>): V2Router.Ответ {
        val слова = query["q"].orEmpty().trim()
        val фильтры = listOf("role", "rank", "scene", "class", "mark", "material").mapNotNull { к -> query[к]?.trim()?.takeIf { it.isNotBlank() }?.let { к to it } }.toMap()
        val виды = query["kind"]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        val предел = query["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 20
        val находки = if (слова.isBlank()) emptyList() else index.search(проект, слова, фильтры, виды, предел)
        val ответ = mapper.createObjectNode()
        ответ.putArray("items").also { м ->
            находки.forEach { н ->
                val у = м.addObject().put("kind", н.kind).put("kind_word", видыСловами[н.kind] ?: н.kind)
                    .put("ref", н.ref).put("title", н.title).put("snippet", н.snippet)
                    .put("score", н.score).put("lexical", н.lexical).put("area", н.area)
                н.semantic?.let { у.put("semantic", it) }
                у.putObject("filters").also { ф -> н.filters.forEach { (к, з) -> ф.put(к, з) } }
            }
        }
        ответ.put("vector", index.vectorReady)
        ответ.put("note", when {
            слова.isBlank() -> "назовите, что искать: слова — по тексту, смысл — вектором, если провайдер задан"
            находки.isEmpty() -> "ничего не нашлось: индекс проекта перестраивается кнопкой либо после разбора документа"
            index.vectorReady -> "гибридно: слова и смысл; фильтры — точное совпадение"
            else -> "лексически: слова по русской морфологии; вектора нет (провайдер эмбеддингов не задан или расширения pgvector нет)"
        })
        return V2Router.Ответ(200, ответ)
    }

    private fun перестроить(проект: String): V2Router.Ответ {
        val итог = index.rebuild(проект)
        return V2Router.Ответ(200, mapper.createObjectNode()
            .put("blocks", итог.blocks).put("written", итог.written).put("removed", итог.removed)
            .put("embedded", итог.embedded).put("vector", итог.vector).put("note", итог.note))
    }

    private fun требуется(query: Map<String, String>): String =
        query["project"]?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("нужен параметр project")
}
