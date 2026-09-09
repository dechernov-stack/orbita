// Импорт ReqIF: объект обмена → черновик требования (ADR-023, сторона
// разбора; ADR-064). XML читает библиотека `reqif` в службе обмена
// (ops/exchange), здесь — только имена атрибутов в поля черновика.
//
// Собственного экспорта ReqIF больше нет (снесён 09.09.2026 по пяти «да»
// сверки каналов, ADR-064): выгрузку делает StrictDoc из .sdoc по грамматике
// Орбиты. Поэтому импорт понимает ОБА словаря имён — прежний свой (Level,
// MeasureOperator…) и грамматики Орбиты (LEVEL, MOP_OP…): файлы прошлых
// выгрузок и нынешние читаются одинаково. Незнакомые атрибуты не теряются —
// они ложатся в foreign_attributes, и человек видит, что приехало сверх схемы.
package orbita.out

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/** Объект обмена: идентификатор, тип и значения атрибутов — как их отдала библиотека. */
data class SpecObject(val identifier: String, val type: String, val values: Map<String, JsonNode>)

/** Имя атрибута ReqIF → путь поля черновика. Оба словаря — в один. */
val REQIF_IMPORT_NAMES: Map<String, String> = linkedMapOf(
    "ReqIF.ForeignID" to "id", "ReqIF.Text" to "statement", "ReqIF.Name" to "title", "Title" to "title", "TITLE" to "title",
    "Level" to "level", "LEVEL" to "level",
    "Category" to "category", "CATEGORY" to "category",
    "Priority" to "priority", "PRIORITY" to "priority",
    "Rationale" to "rationale", "RATIONALE" to "rationale",
    "AcceptanceCriteria" to "acceptance_criteria", "ACCEPTANCE_CRITERIA" to "acceptance_criteria",
    "MeasureName" to "mop.name", "MOP_NAME" to "mop.name",
    "MeasureOperator" to "mop.operator", "MOP_OP" to "mop.operator",
    "MeasureValue" to "mop.value.value", "MOP_VALUE" to "mop.value.value",
    "MeasureUnit" to "mop.value.unit", "MOP_UNIT" to "mop.value.unit",
    "Status" to "lifecycle.status", "STATUS" to "lifecycle.status",
    "Version" to "lifecycle.version", "VERSION" to "lifecycle.version",
    "VerificationMethod" to "verification_method", "VERIFICATION_METHOD" to "verification_method",
    "Owner" to "owner", "OWNER" to "owner",
    "SourceDoc" to "source.doc", "SOURCE_DOC" to "source.doc",
    "SourceAnchor" to "source.anchor", "SOURCE_ANCHOR" to "source.anchor",
    "NORMATIVE_BASIS" to "normative_basis.ref", "NORMATIVE_CLAUSE" to "normative_basis.clause",
    "Tags" to "tags", "TAGS" to "tags",
)

/** Атрибуты самого формата и StrictDoc — не поля содержания, в чужие не идут. */
private val СЛУЖЕБНЫЕ = setOf("MID", "ReqIF.ChapterName", "ReqIF.Description")

/**
 * Объект обмена → черновик требования. Прежний префикс `X-` (свои расширения
 * старого канала) читается как и раньше — полем без префикса.
 */
fun fromSpecObject(so: SpecObject, mapper: ObjectMapper = ObjectMapper()): ObjectNode {
    val out = mapper.createObjectNode()
    val чужие = mapper.createObjectNode()
    so.values.forEach { (имя, значение) ->
        val путь = REQIF_IMPORT_NAMES[имя]
        when {
            путь != null -> положить(out, путь, значение)
            имя.startsWith("X-") -> out.set<JsonNode>(имя.removePrefix("X-"), значение)
            имя in СЛУЖЕБНЫЕ -> Unit
            else -> чужие.set<JsonNode>(имя, значение)
        }
    }
    if (!чужие.isEmpty) out.set<JsonNode>("foreign_attributes", чужие)
    return out
}

private fun положить(корень: ObjectNode, путь: String, значение: JsonNode) {
    val части = путь.split(".")
    var текущий = корень
    for (часть in части.dropLast(1)) {
        текущий = (текущий.path(часть) as? ObjectNode) ?: текущий.putObject(часть)
    }
    текущий.set<JsonNode>(части.last(), значение)
}
