// Порты ядра: чем модуль пользуются снаружи (ТЗ-BACKEND §3).
//
// Другие модули видят только эти интерфейсы. Реализация — в internal, и
// подменить её (например, на память в тестах) можно, не трогая вызывающих.
package orbita.kernel.api

import com.fasterxml.jackson.databind.JsonNode

/** Хранилище сущностей: одна текущая версия на id, история рядом. */
interface EntityStore {
    fun create(
        code: String,
        kind: String,
        area: Area,
        bornIn: String?,
        doc: JsonNode,
        provenance: Provenance,
        status: String = "draft",
    ): Entity

    /** Правка заводит НОВУЮ версию; прежняя остаётся историей. */
    fun update(id: String, doc: JsonNode, provenance: Provenance, status: String? = null): Entity

    fun byId(id: String): Entity?
    fun byCode(area: Area, code: String): Entity?
    fun list(area: Area, kind: String? = null): List<Entity>
    fun history(id: String): List<Entity>
}

/**
 * Типизированная связь из реестра связей (МОДЕЛЬ-ДАННЫХ §4).
 *
 * @property rationale обоснование; у части связей реестр требует его обязательно —
 *   связь без причины неотличима от случайной
 */
data class Link(
    val id: String,
    val type: String,
    val from: String,
    val to: String,
    val rationale: String?,
    val provenance: Provenance,
)

/** Реестр связей: типы известны заранее, произвольных связей не бывает. */
interface LinkRegistry {
    fun link(type: String, from: String, to: String, provenance: Provenance, rationale: String? = null): Link
    fun unlink(id: String, provenance: Provenance)
    fun from(id: String, type: String? = null): List<Link>
    fun to(id: String, type: String? = null): List<Link>
}

/** Проверка документа по сгенерированной схеме вида. */
interface SchemaRegistry {
    fun kinds(): List<String>
    fun problems(kind: String, doc: JsonNode): List<String>
}

/** Событие домена: «сущность появилась/изменилась» — повод переоценить ворота. */
data class DomainEvent(val kind: String, val entityId: String, val area: Area, val what: String)

interface DomainEvents {
    fun publish(event: DomainEvent)
    fun subscribe(listener: (DomainEvent) -> Unit)
}
