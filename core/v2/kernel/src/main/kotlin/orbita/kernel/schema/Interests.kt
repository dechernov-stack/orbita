// Интересы стороны — список её слов с цитатой (истина 24.09, ОНТОЛОГИЯ-АУДИТ
// часть 4): `stakeholder.interest: [{statement*, quote?, fact?}]`. Интерес — не
// роль и не нужда: нуждой он становится только решением человека через
// сверку (`interest_to_need_rule`), автоматом — никогда.
//
// Входы приносят интерес по-разному: строкой (экран, прежние записи, ответ
// модели «одной фразой»), перечнем строк (эталон постановки) или уже списком
// объектов. Правило приведения одно на все входы — здесь, а не в каждом
// маршруте по-своему.
package orbita.kernel.schema

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

object Interests {
    const val ПОЛЕ = "interest"

    /** Один интерес: формулировка, цитата источника и факт-основание (коды). */
    data class Интерес(val statement: String, val quote: String?, val fact: String?)

    /**
     * Привести поле `interest` документа к списку `{statement, quote?, fact?}`.
     * Строка делится по переводам строк и «;» — так инженер правит интересы в
     * одной строке экрана. Цитата и основание интереса, чья формулировка не
     * изменилась, берутся из `прежние` (документ записи до правки); у новых —
     * из `цитата` и `факт` (кандидат-факт, из которого сторона заведена).
     * Возвращает true, если поле изменилось.
     */
    fun нормализовать(документ: ObjectNode, прежние: JsonNode? = null, цитата: String? = null, факт: String? = null): Boolean {
        val узел = документ.get(ПОЛЕ) ?: return false
        val было = узел.deepCopy<JsonNode>()
        val список = документ.arrayNode()
        прочитать(узел).distinctBy { it.statement.lowercase() }.forEach { интерес ->
            val прежний = прежние?.get(ПОЛЕ)?.let { найти(it, интерес.statement) }
            val п = список.addObject().put("statement", интерес.statement)
            (интерес.quote ?: прежний?.quote ?: цитата)?.let { п.put("quote", it) }
            (интерес.fact ?: прежний?.fact ?: факт)?.let { п.put("fact", it) }
        }
        if (список.isEmpty) документ.remove(ПОЛЕ) else документ.set<JsonNode>(ПОЛЕ, список)
        return документ.get(ПОЛЕ) != было
    }

    /** Интересы записи как есть — любым входным видом поля. */
    fun прочитать(узел: JsonNode?): List<Интерес> = when {
        узел == null || узел.isNull || узел.isMissingNode -> emptyList()
        узел.isTextual -> узел.asText().split('\n', ';').map { it.trim() }.filter { it.isNotBlank() }
            .map { Интерес(it, null, null) }
        узел.isArray -> узел.flatMap { прочитать(it) }
        узел.isObject -> {
            val формулировка = узел.path("statement").asText("").ifBlank { узел.path("text").asText("") }.trim()
            if (формулировка.isBlank()) emptyList()
            else listOf(Интерес(формулировка, узел.path("quote").asText("").ifBlank { null }, узел.path("fact").asText("").ifBlank { null }))
        }
        else -> emptyList()
    }

    /** Формулировки интересов словами — для экрана, печати и промпта. */
    fun словами(узел: JsonNode?): List<String> = прочитать(узел).map { it.statement }

    private fun найти(прежние: JsonNode, формулировка: String): Интерес? =
        прочитать(прежние).firstOrNull { it.statement.equals(формулировка, ignoreCase = true) }
}
