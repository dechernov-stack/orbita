// Находка базы знаний — ОДИН сериализатор на вид (правило владельца 08.09):
// поиск и инструмент модели отдают её одними полями.
package orbita.api.internal

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.kernel.api.IndexHit

object IndexHitJson {
    private val видыСловами = mapOf("canon_block" to "блок документа", "term" to "термин", "clause" to "пункт норматива", "fact" to "факт")

    fun of(mapper: ObjectMapper, н: IndexHit): ObjectNode {
        val у = mapper.createObjectNode().put("kind", н.kind).put("kind_word", видыСловами[н.kind] ?: н.kind)
            .put("ref", н.ref).put("title", н.title).put("snippet", н.snippet)
            .put("score", н.score).put("lexical", н.lexical).put("area", н.area)
        н.semantic?.let { у.put("semantic", it) }
        у.putObject("filters").also { ф -> н.filters.forEach { (к, з) -> ф.put(к, з) } }
        return у
    }
}
