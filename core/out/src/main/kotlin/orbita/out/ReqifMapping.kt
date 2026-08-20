// Отображение модели в ReqIF (TZ-OUT-005, шаг 11.2, ADR-023).
// Эталон spec/reqif_semantics.py, один в один.
//
// ReqIF — стандарт OMG. Трудность не в XML — его пишет библиотека `reqif`
// в отдельной службе (ADR-023), — а в ОТОБРАЖЕНИИ: наши структурные поля должны
// попасть в ReqIF так, чтобы принимающий инструмент мог ими пользоваться.
//
// СОСТАВНОЕ ПОЛЕ НЕ СВОРАЧИВАЕТСЯ В СТРОКУ. Условие требования — оператор,
// значение, единица — раскладывается на три атрибута с правильными типами:
// перечисление, число, строка. Сериализованный JSON в одном строковом атрибуте
// формально валиден, файл откроется, но отфильтровать по оператору и
// отсортировать по значению принимающий инструмент не сможет: обмен состоится,
// смысл потеряется.
//
// ИДЕНТИФИКАТОР УСТОЙЧИВ. Одна сущность между выгрузками получает то же
// значение. Иначе принимающий инструмент видит новый объект вместо изменённого
// и теряет историю — обмен превращается в перезалив.
package orbita.out

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.security.MessageDigest

/** Типы данных ReqIF, используемые отображением. */
val REQIF_DATATYPES: Map<String, String> = linkedMapOf(
    "string" to "DATATYPE-DEFINITION-STRING",
    "integer" to "DATATYPE-DEFINITION-INTEGER",
    "real" to "DATATYPE-DEFINITION-REAL",
    "date" to "DATATYPE-DEFINITION-DATE",
    "boolean" to "DATATYPE-DEFINITION-BOOLEAN",
    "enum" to "DATATYPE-DEFINITION-ENUMERATION",
    "xhtml" to "DATATYPE-DEFINITION-XHTML",
)

/** Поле отображения: путь в модели, имя атрибута ReqIF, тип данных. */
data class ReqifField(val source: String, val name: String, val kind: String)

/**
 * Отображение полей требования. Составное условие разложено на три атрибута:
 * принимающий инструмент должен уметь фильтровать по оператору и сортировать
 * по значению.
 */
val REQUIREMENT_MAP: List<ReqifField> = listOf(
    ReqifField("id", "ReqIF.ForeignID", "string"),
    ReqifField("statement", "ReqIF.Text", "xhtml"),
    ReqifField("level", "Level", "enum"),
    ReqifField("category", "Category", "enum"),
    ReqifField("rationale", "Rationale", "xhtml"),
    ReqifField("mop.name", "MeasureName", "string"),
    ReqifField("mop.operator", "MeasureOperator", "enum"),
    ReqifField("mop.value.value", "MeasureValue", "real"),
    ReqifField("mop.value.unit", "MeasureUnit", "string"),
    ReqifField("lifecycle.status", "Status", "enum"),
    ReqifField("owner", "Owner", "string"),
)

val REQIF_ENUM_VALUES: Map<String, List<String>> = linkedMapOf(
    "Level" to listOf("project", "system", "element"),
    "Category" to listOf(
        "functional", "performance", "interface", "operational",
        "reliability", "safety", "environmental", "constraint",
    ),
    "MeasureOperator" to listOf("eq", "le", "ge", "lt", "gt", "range", "tolerance"),
    "Status" to listOf("Draft", "Preliminary", "Approved", "Baseline", "Cancelled"),
)

/** У каждого вида объекта свой SPEC-OBJECT-TYPE. */
val SPEC_OBJECT_TYPES: Map<String, String> = linkedMapOf(
    "requirement" to "ST-REQUIREMENT",
    "need" to "ST-NEED",
    "service" to "ST-SERVICE",
    "component" to "ST-COMPONENT",
    "risk" to "ST-RISK",
)

/** Виды связей различаются типами: трассировка и распределение — не одно и то же. */
val SPEC_RELATION_TYPES: Map<String, String> = linkedMapOf(
    "trace" to "RT-TRACE",
    "derive" to "RT-DERIVE",
    "allocation" to "RT-ALLOCATION",
    "verification" to "RT-VERIFICATION",
)

/** Объект обмена: идентификатор, тип и значения атрибутов. */
data class SpecObject(val identifier: String, val type: String, val values: Map<String, JsonNode>)

data class SpecRelation(
    val identifier: String,
    val type: String,
    val source: String,
    val target: String,
)

class UnmappedLinkKindException(message: String) : IllegalArgumentException(message)

/**
 * Устойчивый IDENTIFIER: одна и та же сущность между выгрузками получает то же
 * значение. Считается от ВИДА и КЛЮЧА, а не от содержимого — иначе правка
 * статуса выглядела бы для принимающего инструмента новым объектом.
 */
fun reqifIdentifier(kind: String, key: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest("$kind:$key".toByteArray())
    return "$kind-" + digest.take(8).joinToString("") { "%02x".format(it) }
}

/** Описания типов данных для атрибутов отображения. */
fun datatypeDefinitions(mapping: List<ReqifField> = REQUIREMENT_MAP): Map<String, ReqifDatatype> =
    mapping.associate { f ->
        f.name to ReqifDatatype(
            type = REQIF_DATATYPES[f.kind] ?: "?",
            values = if (f.kind == "enum") REQIF_ENUM_VALUES[f.name] else null,
        )
    }

data class ReqifDatatype(val type: String, val values: List<String>?)

/**
 * Замечания к отображению. Перечислимое поле, отданное строкой, — не мелочь:
 * принимающий инструмент теряет возможность фильтровать по нему.
 */
fun mappingIssues(mapping: List<ReqifField> = REQUIREMENT_MAP): List<String> = buildList {
    for (f in mapping) {
        if (f.kind !in REQIF_DATATYPES) add("${f.name}: неизвестный тип данных ${f.kind}")
        if (f.kind == "enum" && f.name !in REQIF_ENUM_VALUES) {
            add("${f.name}: перечисление без набора значений")
        }
        if (f.kind == "string" &&
            ENUM_LIKE_SUFFIXES.any { f.source.endsWith(it) }
        ) {
            add("${f.name}: перечислимое поле отображено строкой, фильтрация потеряется")
        }
    }
}

private val ENUM_LIKE_SUFFIXES = listOf("operator", "status", "level", "category")

/** Требование модели → объект обмена. Незнакомые поля не теряются. */
fun toSpecObject(
    requirement: JsonNode,
    mapping: List<ReqifField> = REQUIREMENT_MAP,
    kind: String = "requirement",
): SpecObject {
    val values = LinkedHashMap<String, JsonNode>()
    for (f in mapping) {
        dig(requirement, f.source)?.let { values[f.name] = it }
    }
    // Незнакомые поля уходят в атрибуты с префиксом X-: инструмент, молча
    // выбрасывающий то, чего не знает, портит чужую модель — и замечают это
    // только после обратной загрузки.
    val known = mapping.map { it.source.substringBefore('.') }.toSet()
    requirement.properties().forEach { (key, value) ->
        if (key !in known && !value.isObject && !value.isArray) values["X-$key"] = value
    }
    return SpecObject(
        identifier = reqifIdentifier("SO", requirement.path("id").asText()),
        type = SPEC_OBJECT_TYPES[kind] ?: throw IllegalArgumentException("вид объекта $kind не отображён"),
        values = values,
    )
}

/** Объект обмена → требование модели. Обратное отображение того же состава. */
fun fromSpecObject(
    so: SpecObject,
    mapping: List<ReqifField> = REQUIREMENT_MAP,
    mapper: ObjectMapper = ObjectMapper(),
): ObjectNode {
    val out = mapper.createObjectNode()
    for (f in mapping) {
        val value = so.values[f.name] ?: continue
        val parts = f.source.split(".")
        var cur = out
        for (part in parts.dropLast(1)) {
            cur = (cur.path(part) as? ObjectNode) ?: cur.putObject(part)
        }
        cur.set<ObjectNode>(parts.last(), value)
    }
    so.values.forEach { (key, value) ->
        if (key.startsWith("X-")) out.set<ObjectNode>(key.removePrefix("X-"), value)
    }
    return out
}

/** Связи модели → SPEC-RELATION. Неотображённый вид связи — отказ, а не пропуск. */
fun toSpecRelations(links: List<ExchangeLink>): List<SpecRelation> = links.map { l ->
    val type = SPEC_RELATION_TYPES[l.kind]
        ?: throw UnmappedLinkKindException("вид связи ${l.kind} не отображён в SPEC-RELATION-TYPE")
    SpecRelation(
        identifier = reqifIdentifier("SR", "${l.from}>${l.to}:${l.kind}"),
        type = type,
        source = reqifIdentifier("SO", l.from),
        target = reqifIdentifier("SO", l.to),
    )
}

/** Связь в форме обмена: концы и вид. */
data class ExchangeLink(val from: String, val to: String, val kind: String)

/**
 * Признак ошибки отображения: составное значение сложено в одну строку.
 * Ловится по началу строки — сериализованные объект и список ни с чем
 * не спутать, а стоимость пропуска высока: файл валиден, обмен бесполезен.
 */
fun flattenedAsString(so: SpecObject): List<String> =
    so.values.filter { (_, v) ->
        v.isTextual && v.asText().trimStart().let { it.startsWith("{") || it.startsWith("[") }
    }.keys.toList()

private fun dig(node: JsonNode, path: String): JsonNode? {
    var cur: JsonNode = node
    for (part in path.split(".")) {
        if (!cur.isObject || !cur.has(part)) return null
        cur = cur.path(part)
    }
    return if (cur.isNull) null else cur
}
