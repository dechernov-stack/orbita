// Правило основания (П5 задания «прогон до KDP B», ловушка 10).
//
// Число, взятое из воздуха, выглядит ровно как посчитанное. Величина без
// ссылки на источник — это «я так решил»: от инженера такое принимается (он
// и отвечает), от службы ИИ — только с явным решением человека.
//
// Правило живёт здесь, рядом с остальными правилами модели, а не в фильтре
// предложений: фильтр обязан вызывать те же правила, что применяются к
// рукописному вводу, иначе своды расходятся при первой же правке.
package orbita.req

import com.fasterxml.jackson.databind.JsonNode

/** Имя правила в отчёте фильтра — по нему считается счётчик журнала службы. */
const val SOURCE_RULE = "основание"

/**
 * Величины документа без ссылки на источник. Считается величиной объект с
 * числовым `value` и текстовой `unit` (та же форма, что у quantity.schema).
 *
 * Основанием признаётся происхождение по schemas/common/provenance:
 * `computed` с указанным модулем-вычислителем либо `imported` с блоком
 * import (набор данных и его версия). `manual` — это «инженер отвечает
 * сам»: от человека принимается, от службы ИИ — только его решением.
 * `ai_proposed` основанием не является по определению.
 */
fun unsourcedQuantities(doc: JsonNode): List<String> {
    val found = mutableListOf<String>()

    fun walk(node: JsonNode, path: String) {
        when {
            node.isObject -> {
                if (node.path("value").isNumber && node.path("unit").isTextual) {
                    val prov = node.path("provenance")
                    val grounded = when (prov.path("source").asText("")) {
                        "computed" -> prov.path("module").asText("").isNotBlank()
                        "imported" -> prov.path("import").path("dataset").asText("").isNotBlank()
                        else -> false
                    }
                    if (!grounded) found += path.ifBlank { "значение" }
                }
                node.properties().forEach { (name, child) ->
                    if (name != "provenance") walk(child, if (path.isBlank()) name else "$path/$name")
                }
            }

            node.isArray -> node.forEachIndexed { i, child -> walk(child, "$path/$i") }
        }
    }

    walk(doc, "")
    return found.distinct()
}

/** Замечания правила основания: снимаются с показа и требуют решения инженера. */
fun sourceIssues(doc: JsonNode): List<String> = unsourcedQuantities(doc).map {
    "$SOURCE_RULE: значение «$it» без ссылки на источник — требуется решение инженера"
}
