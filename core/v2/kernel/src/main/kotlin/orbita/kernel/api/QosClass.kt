// Класс обслуживания нужды — одно правило на все контуры.
//
// Решение владельца 12.09 («ЗНАНИЯ-V2-ПРИНЯТЫ» §1): класс у нужды
// ОБЯЗАТЕЛЕН, но значение TBR допустимо — `tbr{owner, gate = сцена 6}`.
// Класс появляется из фактов о задержке и гарантии; фактов нет — нужда
// всё равно заводится, но несёт TBR с ответственным, и выход сцены
// сервисов её не пропускает.
//
// Правило живёт в ядре, потому что спрашивают о нём с четырёх сторон:
// сверка знаний (заводя нужду), приём плана загрузки, маршрут сцены 3
// (ручной ввод) и оценщик готовности (выход сцены 6). Второй копии
// правила быть не должно — она разойдётся с истиной молча.
package orbita.kernel.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.kernel.schema.GeneratedKinds

object QosClass {

    /** Имя поля по истине схем — `need.qos_class`, `service.qos_class`. */
    const val FIELD: String = "qos_class"

    /** Слово, которым человек пишет «значения ещё нет». */
    const val TBR: String = "TBR"

    /**
     * Ворота, где TBR обязан закрыться, — сцена, в которой заводятся сервисы.
     * Берётся у самого вида «сервис»: числа сцены в коде нет.
     */
    val GATE: String = GeneratedKinds.byCode["service"]?.bornIn.orEmpty()

    /** Назначен ли класс: ссылка на класс справочника — да, TBR и пусто — нет. */
    fun assigned(value: JsonNode): Boolean = when {
        value.isObject -> false
        else -> value.asText("").trim().let { it.isNotEmpty() && !it.equals(TBR, ignoreCase = true) }
    }

    /**
     * Значение TBR: кто отвечает за появление класса и где оно обязано
     * появиться. Без ответственного TBR был бы вечным.
     */
    fun tbr(mapper: ObjectMapper, owner: String): ObjectNode =
        mapper.createObjectNode().put("owner", owner).put("gate", GATE)

    /**
     * Класс словами — для выгрузки и печати. Пусто, только если поля нет
     * вовсе: TBR называется TBR, а не прячется пустотой.
     */
    fun words(value: JsonNode): String? = when {
        value.isObject -> "класс $TBR" + value.path("owner").asText("").ifBlank { null }?.let { " — за $it" }.orEmpty()
        else -> value.asText("").trim().ifBlank { null }?.let { "класс $it" }
    }

    /**
     * Проставить TBR там, где класс не назначен. Возвращает `true`, если
     * значение появилось, — вызывающему есть что сказать человеку.
     */
    fun fillIfMissing(mapper: ObjectMapper, doc: ObjectNode, owner: String): Boolean {
        if (assigned(doc.path(FIELD))) return false
        doc.set<JsonNode>(FIELD, tbr(mapper, owner))
        return true
    }
}
