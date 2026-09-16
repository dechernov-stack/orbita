// Влияние стороны — поле, которое считает СИСТЕМА, а не модель.
//
// Истина 15.09 (ОНТОЛОГИЯ-ФОРМИРОВАНИЯ, stakeholder.computed_fields):
// «вычисляется системой из role по карте … модель поле не заполняет, инженер
// правит на месте». Сама карта лежит там же разделом `influence_map` и сюда
// приходит генерацией: своего правила в коде нет и быть не должно.
//
// Рядом в истине — `power`: «оценка человека 1–5 на сцене 3; по умолчанию
// пусто». Его не считает никто: матрица «влияние × сила» заполняется рукой, и
// это её назначение, а не пробел.
//
// Дом — модуль знаний, а не ядро (где живёт QosClass): правило приходит из
// ОНТОЛОГИИ, а её сгенерированный Kotlin ядру не виден.
package orbita.knowledge.api

import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.knowledge.schema.GeneratedOntology

object Influence {

    /** Понятие, у которого поле вычисляется, — сторона. */
    const val CONCEPT: String = "stakeholder"

    /** Имя поля по истине схем — `stakeholder.influence`. */
    const val FIELD: String = "influence"

    /** Поле-источник: роль стороны. */
    const val FROM: String = "role"

    /** Правило дословно: им человеку объясняется, откуда взялось значение. */
    val rule: String = GeneratedOntology.byCode[CONCEPT]?.computedFields?.get(FIELD).orEmpty()

    /** Роль → влияние. Карта целиком из истины; роль вне её влияния не получает. */
    val byRole: Map<String, String> = GeneratedOntology.influenceMap

    /** Поля понятия, которые считает система: модель их не заполняет. */
    fun computed(concept: String): Set<String> =
        GeneratedOntology.byCode[concept]?.computedFields?.keys.orEmpty()

    /** Влияние по роли; null — истина о такой роли не говорит. */
    fun of(role: String): String? = byRole[role.trim()]

    /**
     * Проставить влияние, если его нет. Возвращает `true`, когда значение
     * появилось: вызывающему есть что сказать человеку.
     */
    fun fillIfMissing(doc: ObjectNode): Boolean {
        if (doc.path(FIELD).asText("").isNotBlank()) return false
        val влияние = of(doc.path(FROM).asText("")) ?: return false
        doc.put(FIELD, влияние)
        return true
    }

    /**
     * Снять вычисляемые поля с содержимого, названного моделью: «модель это
     * поле не заполняет». Возвращает снятое — человеку говорят, что именно
     * снято, а не молчат. `power` снимается тоже: его ставит человек на сцене 3.
     */
    fun снять(concept: String, doc: ObjectNode): List<String> {
        val поля = computed(concept).filter { doc.has(it) }
        doc.remove(поля)
        return поля
    }

    /** Словами — для пометы в происхождении: откуда взялось значение поля. */
    fun словами(влияние: String): String = "«$FIELD = $влияние» вычислено системой по роли ($rule)"
}
