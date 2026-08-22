// Экспорт в форматы обмена (TZ-OUT-005, STEP-6 §2.4).
// Эталон spec/presentation_semantics.py, один в один.
//
// Выгрузка не теряет данные, В ТОМ ЧИСЛЕ НЕЗНАКОМЫЕ АТРИБУТЫ: инструмент,
// который молча выбрасывает то, чего не знает, портит чужую модель — а замечают
// это только после обратной загрузки.
//
// Направление только наружу (Шаг 16 §2.1): ввод идёт настоящим ReqIF через
// службу обмена (ADR-023), и круговой обмен проверяется на нём.
package orbita.out

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.store.Link

/** Требование в форме обмена: идентификатор и произвольный набор атрибутов. */
data class ExchangeRequirement(val id: String, val attributes: Map<String, JsonNode>)

data class ExchangeDocument(
    val requirements: List<ExchangeRequirement>,
    val links: List<Link>,
    /** Версия формата и дата выгрузки фиксируются в документе (TZ-OUT-005). */
    val formatVersion: String = FORMAT_VERSION,
    val exportedAt: String? = null,
) {
    companion object {
        const val FORMAT_VERSION = "reqif-lite/1.0"
    }
}

fun toExchange(
    requirements: List<ExchangeRequirement>,
    links: List<Link>,
    exportedAt: String? = null,
): ExchangeDocument = ExchangeDocument(
    requirements = requirements.map { ExchangeRequirement(it.id, LinkedHashMap(it.attributes)) },
    links = links.map { it.copy() },
    exportedAt = exportedAt,
)

/** Сериализация ReqIF-подобного документа: атрибуты переносятся как есть. */
fun exchangeToJson(doc: ExchangeDocument, mapper: ObjectMapper = ObjectMapper()): ObjectNode {
    val root = mapper.createObjectNode()
    root.put("format_version", doc.formatVersion)
    doc.exportedAt?.let { root.put("exported_at", it) }
    val reqs = root.putArray("requirements")
    doc.requirements.forEach { r ->
        val n = reqs.addObject()
        n.put("id", r.id)
        val attrs = n.putObject("attributes")
        r.attributes.forEach { (k, v) -> attrs.set<ObjectNode>(k, v) }
    }
    val links = root.putArray("links")
    doc.links.forEach { l ->
        val n = links.addObject()
        n.put("from", l.fromId)
        n.put("to", l.toId)
        n.put("kind", l.kind)
        l.consumerClass?.let { n.put("consumer_class", it) }
        l.allocationKind?.let { n.put("allocation_kind", it) }
        l.rationale?.let { n.put("rationale", it) }
        l.derivationKind?.let { n.put("derivation_kind", it) }
    }
    return root
}

/**
 * CSV-выгрузка требований. Колонки — объединение всех встреченных атрибутов:
 * незнакомый атрибут получает свою колонку, а не выбрасывается.
 */
fun toCsv(requirements: List<ExchangeRequirement>): String {
    val columns = requirements.flatMap { it.attributes.keys }.distinct().sorted()
    val header = (listOf("id") + columns).joinToString(",") { csvCell(it) }
    val rows = requirements.map { r ->
        (listOf(r.id) + columns.map { c -> r.attributes[c]?.asText() ?: "" })
            .joinToString(",") { csvCell(it) }
    }
    return (listOf(header) + rows).joinToString("\n")
}

private fun csvCell(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' }) {
        "\"" + value.replace("\"", "\"\"") + "\""
    } else {
        value
    }
