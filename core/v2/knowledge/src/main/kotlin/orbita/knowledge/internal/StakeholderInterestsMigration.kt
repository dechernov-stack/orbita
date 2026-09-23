// Миграция понятий (ЗАДАНИЕ-ШИП-4 §1, ОНТОЛОГИЯ-АУДИТ часть 4): интерес — отдельное
// понятие у стороны, список её слов с цитатой (`interest: [{statement, quote?,
// fact?}]`). До 24.09 интерес лежал строкой; эталон постановки давал перечень
// строк. Строка сходит первым интересом, перечень — интересами по порядку;
// цитаты у прежних записей нет — их принесут документы (§3). Идемпотентно:
// запись, уже держащая список объектов, не трогается.
package orbita.knowledge.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.kernel.api.Channel
import orbita.kernel.api.EntityStore
import orbita.kernel.api.Provenance
import orbita.kernel.schema.Interests

internal class StakeholderInterestsMigration(
    private val store: EntityStore,
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    data class Report(val converted: Int, val notes: List<String>) {
        override fun toString(): String =
            "интерес — список у стороны: приведено записей $converted" +
                (if (notes.isEmpty()) "" else "; " + notes.joinToString("; "))
    }

    private val провенанс = Provenance(Channel.MANUAL, "миграция понятий 24.09 (интересы стороны)")

    fun run(): Report {
        var приведено = 0
        val заметки = mutableListOf<String>()
        store.ofKind("stakeholder").filter { it.status != "cancelled" }.forEach { сторона ->
            val узел = сторона.doc.get(Interests.ПОЛЕ) ?: return@forEach
            if (уже(узел)) return@forEach
            val документ = сторона.doc.deepCopy<JsonNode>() as ObjectNode
            if (Interests.нормализовать(документ)) {
                store.update(сторона.id, документ, провенанс)
                приведено += 1
                заметки += сторона.code
            }
        }
        return Report(приведено, заметки)
    }

    /** Список объектов с формулировками — уже по истине; ничего не менять. */
    private fun уже(узел: JsonNode): Boolean =
        узел.isArray && узел.all { it.isObject && it.path("statement").asText("").isNotBlank() }
}
