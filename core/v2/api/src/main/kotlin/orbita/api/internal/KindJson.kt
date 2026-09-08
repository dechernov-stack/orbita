// Один сериализатор на вид (ПРИЁМКА-KNOWLEDGE-REMARKS, правило 2).
//
// Список фактов и ответ на заведение факта писались разным кодом — `manual`
// был в одном и не был в другом, и экран показывал «пусто» при живом ручном
// факте. Правило: у вида порта ОДИН построитель JSON, и он здесь; маршруты
// его зовут, а не переписывают. Сторож — tools/validate_one_serializer.py.
// Следующий шаг правила — DTO видов реестра из схемы YAML генерацией.
package orbita.api.internal

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.documents.api.RenderedSection
import orbita.knowledge.api.Fact
import orbita.programmatics.api.EstimateView

internal object KindJson {

    fun факт(mapper: ObjectMapper, ф: Fact): ObjectNode = mapper.createObjectNode()
        .put("id", ф.id)
        .put("manual", ф.manual)
        .put("kind", ф.kind)
        .put("subject", ф.subject)
        .put("predicate", ф.predicate)
        .put("value", ф.value)
        .put("unit", ф.unit)
        .put("anchor", ф.anchor)
        .put("mark", ф.mark.name)
        .put("confidence", ф.confidence)
        .put("material", ф.material)
        .put("topic", ф.topic)
        .put("disposition", ф.disposition.name.lowercase())

    fun оценка(mapper: ObjectMapper, о: EstimateView): ObjectNode = mapper.createObjectNode()
        .put("min", о.min).put("max", о.max).put("unit", о.unit)
        .put("method", о.method.name.lowercase()).put("assumptions", о.assumptions)
        .put("date", о.date)

    /** Текст раздела: принятый и отклонённый — одним видом, причины и заметки списками. */
    fun текст(mapper: ObjectMapper, т: RenderedSection): ObjectNode = mapper.createObjectNode()
        .put("section", т.section)
        .put("title", т.title)
        .put("text", т.text)
        .put("model", т.model)
        .put("accepted", т.accepted)
        .also { у ->
            у.putArray("refusals").also { а -> т.refusals.forEach { а.add(it) } }
            у.putArray("notes").also { а -> т.notes.forEach { а.add(it) } }
        }
}
