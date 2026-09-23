// Миграция понятий (ЗАДАНИЕ-ШИП-4 §1, ОНТОЛОГИЯ-АУДИТ часть 3): четыре оси
// достоверности — четыре поля с этими именами у своих уровней, синонимы сняты:
//   mark (происхождение И/В/П) — у факта и норматива, прежде source_mark;
//   mark_no (номер источника) — у факта, прежде source_mark_no;
//   rank (доверие) — у документа и, наследованием, у факта, прежде authority;
//   evidence (свидетельство) — у факта, имя не менялось;
//   confidence (уверенность) — у ПРЕДЛОЖЕНИЯ; у факта её нет — поле снимается.
// Идемпотентно: запись без прежних имён не трогается; история — версией.
package orbita.knowledge.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.kernel.api.Channel
import orbita.kernel.api.EntityStore
import orbita.kernel.api.Provenance

internal class CredibilityAxesMigration(
    private val store: EntityStore,
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    data class Report(val facts: Int, val materials: Int, val normatives: Int) {
        override fun toString(): String =
            "оси достоверности: приведено фактов $facts, материалов $materials, нормативов $normatives"
    }

    private val провенанс = Provenance(Channel.MANUAL, "миграция понятий 24.09 (оси достоверности)")

    /** Прежнее имя → новое; пустое новое имя — поле снимается. */
    private val переименования = mapOf(
        "fact" to listOf("source_mark" to "mark", "source_mark_no" to "mark_no", "authority" to "rank", "confidence" to ""),
        "material" to listOf("authority" to "rank"),
        "normative_document" to listOf("source_mark" to "mark"),
    )

    fun run(): Report {
        val итоги = переименования.mapValues { (вид, правила) ->
            store.ofKind(вид).filter { it.status != "cancelled" }.count { запись ->
                val документ = запись.doc.deepCopy<JsonNode>() as ObjectNode
                if (!привести(документ, правила)) return@count false
                store.update(запись.id, документ, провенанс)
                true
            }
        }
        return Report(итоги.getValue("fact"), итоги.getValue("material"), итоги.getValue("normative_document"))
    }

    private fun привести(документ: ObjectNode, правила: List<Pair<String, String>>): Boolean {
        var изменено = false
        правила.forEach { (старое, новое) ->
            val значение = документ.get(старое) ?: return@forEach
            документ.remove(старое)
            // Новое имя уже стоит — оно и остаётся: запись после миграции правилась.
            if (новое.isNotBlank() && !документ.has(новое)) документ.set<JsonNode>(новое, значение)
            изменено = true
        }
        return изменено
    }
}
