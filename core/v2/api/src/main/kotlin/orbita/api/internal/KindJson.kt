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
import orbita.knowledge.api.FactLink
import orbita.knowledge.api.FactSource
import orbita.knowledge.api.Topic
import orbita.programmatics.api.EstimateView

internal object KindJson {

    fun факт(mapper: ObjectMapper, ф: Fact): ObjectNode = mapper.createObjectNode()
        .put("id", ф.id)
        .put("manual", ф.manual)
        .put("kind", ф.kind)
        .put("subject", ф.subject)
        .put("predicate", ф.predicate)
        .put("entity_class", ф.entityClass)
        .put("value", ф.value)
        .put("unit", ф.unit)
        .put("anchor", ф.anchor)
        .put("mark", ф.mark.name)
        .put("confidence", ф.confidence)
        .put("material", ф.material)
        .put("topic", ф.topic)
        .put("disposition", ф.disposition.name.lowercase())
        .put("param_key", ф.paramKey)
        .put("source_updated", ф.sourceUpdated)
        // Ранг доверия: наследуется от материала, у руки эксперта — expert.
        // Пусто — факт заведён до перестройки, и ранг здесь не выдумывается:
        // выдуманное доверие хуже отсутствующего.
        .put("authority", ф.authority)
        // Свидетельство поднимает сверка: без него мера «после подтверждения источников — corroborated» снаружи не читается.
        .put("evidence", ф.evidence)
        .also { у ->
            у.putArray("conflicts").also { а -> ф.conflicts.forEach { а.add(it) } }
            // Источник союзом (истина схем `fact.source`): документ с якорем
            // ЛИБО эксперт с учёткой, ролью и датой. Плоские `material` и
            // `anchor` остаются рядом до DTO из YAML — на них смотрит экспорт.
            ф.source?.let { и -> у.set<ObjectNode>("source", источник(mapper, и)) }
            // Связи фактов обоими концами: конфликт между документами живёт
            // связью, а `conflicts` остаётся вычисляемым представлением.
            у.putArray("links").also { а -> ф.links.forEach { с -> а.add(связь(mapper, с)) } }
            ф.assumption?.let { д ->
                у.putObject("assumption").put("owner", д.owner).put("confirm_by", д.confirmBy)
                    .put("validation", д.validation).put("impact_if_wrong", д.impactIfWrong)
            }
        }

    /** Источник факта — ровно одна ветка союза: документ с якорем либо эксперт. */
    fun источник(mapper: ObjectMapper, и: FactSource): ObjectNode = when (и) {
        is FactSource.FromMaterial -> mapper.createObjectNode().put("material", и.material).put("anchor", и.anchor)
        is FactSource.FromExpert ->
            mapper.createObjectNode().put("account", и.account).put("role", и.role).put("at", и.at)
    }

    /** Связь факта с фактом: оба конца и причина — связь без причины неотличима от случайной. */
    private fun связь(mapper: ObjectMapper, с: FactLink): ObjectNode = mapper.createObjectNode()
        .put("type", с.type).put("from", с.from).put("to", с.to).put("rationale", с.rationale)

    /**
     * Тема наружу.
     *
     * Поля `merged_into` здесь нет намеренно: порт отдаёт только ГОЛОВЫ
     * цепочек слияния (`EntityIntake.topics`), а у головы это поле пусто по
     * определению — писать его значило бы отдавать экрану всегда `null`.
     * Слияние видно там, где оно происходит: ответ POST /v2/topics/{код}/merge
     * возвращает голову и называет, что во что слито.
     */
    fun тема(mapper: ObjectMapper, т: Topic): ObjectNode = mapper.createObjectNode()
        .put("id", т.id).put("label", т.label)
        .put("scene", т.scene).put("resolved_to", т.resolvedTo)
        .put("facts", т.facts)

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
