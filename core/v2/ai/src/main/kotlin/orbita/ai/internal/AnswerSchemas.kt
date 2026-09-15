// Схемы ответа модели — формат держит ПРОВАЙДЕР, а не уговор в тексте.
//
// До 15.09 формат жил словами промпта: раздел «## Формат ответа — ТОЛЬКО
// JSON» и пример. Дважды за один день правка соседних инструкций уводила
// модель с формата, и разбор возвращал пустой список фактов при исправном
// вызове. Причину видно только в журнале, а на экране это выглядит как
// «модель ничего не нашла» — худший вид отказа, тихий.
//
// Схема отдаётся провайдеру входом инструмента (`tools` + `tool_choice`),
// и ответ приходит объектом. Перечни в схемах — из ИСТИНЫ: виды фактов из
// сгенерированного KindSpec, понятия из сгенерированной онтологии. Второй
// копии перечня здесь нет и быть не должно.
package orbita.ai.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.kernel.schema.GeneratedKinds
import orbita.knowledge.schema.GeneratedOntology

internal object AnswerSchemas {

    /** Виды факта — закрытый перечень истины схем (вид «fact», поле kind). */
    private val ВИДЫ_ФАКТА: List<String> =
        GeneratedKinds.byCode["fact"]?.enums?.get("kind").orEmpty()

    /** Понятия постановки — перечень онтологии формирования. */
    private val ПОНЯТИЯ: List<String> = GeneratedOntology.concepts.map { it.code }

    /** Вердикты предложения — те же четыре, что знает порт. */
    private val ВЕРДИКТЫ: List<String> = listOf("new", "augment", "contradict", "confirm")

    /** Перечни полей — из истины схем; своих в коде нет. */
    private fun перечисление(вид: String, поле: String): List<String> =
        GeneratedKinds.byCode[вид]?.enums?.get(поле).orEmpty()

    private val РОЛИ_СТОРОНЫ: List<String> = перечисление("stakeholder", "role")
    private val ТИПЫ_РАМКИ: List<String> = перечисление("constraint", "type")
    private val РОДЫ_ВЕХИ: List<String> = перечисление("milestone", "kind")
    private val ВЕРДИКТЫ_ПРИМЕНИМОСТИ: List<String> = перечисление("opportunity", "verdict")

    /**
     * Ответ разбора: факты, темы, профиль, связи и план действий.
     *
     * Факт идёт СТРУКТУРОЙ (субъект · предикат · объект · значение с
     * единицей), а не прозой: формирование опирается на поля факта, и
     * пока они приходили текстом, вся атомизация терялась на следующем
     * шаге (разбор владельца 15.09).
     */
    fun разбор(mapper: ObjectMapper): ObjectNode {
        val факт = объект(
            mapper,
            // `value` обязателен истиной схем (вид «fact»): факт без значения
            // приёмом отвергается. У величины это число, у прочих видов —
            // объект утверждения словами: «телеметрия груза вне покрытия».
            обязательные = listOf("kind", "subject", "predicate", "value", "anchor", "source_mark"),
        )
        поле(факт, "kind", перечень(mapper, ВИДЫ_ФАКТА, "вид факта; обязателен"))
        поле(факт, "subject", строка(mapper, "о чём факт: сторона, система, акт, объект"))
        поле(факт, "predicate", строка(mapper, "что утверждается о субъекте, словами источника"))
        поле(факт, "value", строка(mapper, "у вида quantity — число без единицы; у прочих видов — объект утверждения словами"))
        поле(факт, "unit", строка(mapper, "единица величины; у вида quantity обязательна"))
        поле(факт, "anchor", строка(mapper, "якорь блока канона, например s3#2; придуманный якорь недопустим"))
        поле(факт, "source_mark", перечень(mapper, listOf("И", "В", "П"), "метка достоверности"))
        поле(факт, "entity_class", строка(mapper, "класс сущности ТЗ, если разбирается ТЗ"))
        поле(факт, "topic", строка(mapper, "тема, к которой факт относится"))
        поле(факт, "conflict", строка(mapper, "код рамки проекта, которой величина противоречит"))

        val тема = объект(mapper, обязательные = listOf("label"))
        поле(тема, "label", строка(mapper, "имя темы"))

        val связь = объект(mapper, обязательные = listOf("from", "to", "type", "rationale"))
        поле(связь, "from", целое(mapper, "номер факта в массиве facts"))
        поле(связь, "to", целое(mapper, "номер факта в массиве facts"))
        поле(связь, "type", перечень(mapper, listOf("supports", "contradicts", "refines", "same_as"), "вид связи"))
        поле(связь, "rationale", строка(mapper, "почему связь именно такая"))

        val действие = объект(mapper, обязательные = listOf("kind", "target_kind", "title", "payload", "facts"))
        поле(действие, "kind", строка(mapper, "create_entity либо flag_conflict"))
        поле(действие, "target_kind", строка(mapper, "вид заводимой записи"))
        поле(действие, "scene", строка(mapper, "сцена, в которой запись рождается"))
        поле(действие, "title", строка(mapper, "что произойдёт, словами для человека"))
        поле(действие, "preview", строка(mapper, "что появится на экране после нажатия"))
        поле(действие, "payload", свободный(mapper, "содержимое будущей записи полями"))
        поле(действие, "facts", массив(mapper, целое(mapper, "номер факта в массиве facts")))

        val профиль = объект(mapper, обязательные = emptyList())
        listOf("statement", "params", "norms", "assessments").forEach {
            поле(профиль, it, число(mapper, "доля блока, 0…1"))
        }

        val корень = объект(mapper, обязательные = listOf("facts"))
        поле(корень, "facts", массив(mapper, факт))
        поле(корень, "topics", массив(mapper, тема))
        поле(корень, "links", массив(mapper, связь))
        поле(корень, "actions", массив(mapper, действие))
        поле(корень, "profile", профиль)
        return корень
    }

    /**
     * Ответ формирования: предложения понятий.
     *
     * Ссылка на соседнее понятие — либо код принятого (`code`), либо
     * номер предложения из ЭТОГО же ответа (`ref`): сторона, которой ещё
     * нет, не может быть названа именем, и нужды висли без владельца
     * (разбор владельца 15.09, дефект 5).
     */
    fun формирование(mapper: ObjectMapper): ObjectNode {
        val предложение = объект(mapper, обязательные = listOf("id", "concept", "payload", "basis", "verdict"))
        поле(предложение, "id", строка(mapper, "номер предложения в этом ответе, например p1"))
        поле(предложение, "concept", перечень(mapper, ПОНЯТИЯ, "понятие онтологии формирования"))
        поле(предложение, "payload", свободный(mapper, "поля понятия; ссылка на соседа — {\"code\"} либо {\"ref\"}"))
        поле(
            предложение, "basis",
            массив(
                mapper,
                строка(
                    mapper,
                    "код ФАКТА из среза (F-NNNN). Код принятой сущности (стороны, рамки, вехи) " +
                        "основанием не бывает — он идёт полем target",
                ),
            ),
        )
        поле(предложение, "verdict", перечень(mapper, ВЕРДИКТЫ, "вердикт о принятом"))
        поле(
            предложение, "target",
            строка(mapper, "код ПРИНЯТОЙ сущности, о которой вердикт; пусто только у new"),
        )
        поле(предложение, "diff_field", строка(mapper, "поле, о котором вердикт; обязательно, кроме new"))
        поле(предложение, "confidence", число(mapper, "уверенность 0…1"))
        поле(предложение, "why", строка(mapper, "одной строкой: почему сложено именно так"))

        val корень = объект(mapper, обязательные = listOf("proposals"))
        поле(корень, "proposals", массив(mapper, предложение))
        return корень
    }

    // --- кирпичи схемы ------------------------------------------------------

    /**
     * Ответ чтения документа: постановка-кандидат сразу, одним вызовом.
     *
     * РЕШЕНИЕ-ЧИТАТЬ-СМЫСЛ (владелец, 15.09): конвейер «факты по видам и
     * предикатам → понятия по правилам» фильтровал лексически и отбивал
     * очевидное. Новый порядок — документ читается смыслом, а модель отдаёт
     * понятия сразу, каждое с ЦИТАТОЙ и якорем. Цитата — не украшение: по ней
     * стоят ворота («цитата дословно есть в каноне»), и она же становится
     * следом-фактом, на котором держится происхождение.
     *
     * Перечни ролей, вердиктов и предложений — из истины схем: второй копии
     * здесь нет.
     */
    fun чтение(mapper: ObjectMapper): ObjectNode {
        val корень = объект(mapper, обязательные = listOf("stakeholders"))
        поле(корень, "stakeholders", массив(mapper, сторона(mapper)))
        поле(корень, "needs", массив(mapper, нужда(mapper)))
        поле(корень, "goals", массив(mapper, цель(mapper)))
        поле(корень, "external_targets", массив(mapper, чужаяЦель(mapper)))
        поле(корень, "constraints", массив(mapper, рамка(mapper)))
        поле(корень, "milestones", массив(mapper, веха(mapper)))
        поле(корень, "assumptions", массив(mapper, допущение(mapper)))
        поле(корень, "applicabilities", массив(mapper, применимость(mapper)))
        поле(корень, "services", массив(mapper, сервис(mapper)))
        return корень
    }

    /** Общие поля всякого пункта чтения: свой номер, цитата, якорь, уверенность. */
    private fun пункт(mapper: ObjectMapper, обязательные: List<String>): ObjectNode {
        val узел = объект(mapper, обязательные = listOf("id", "quote", "anchor") + обязательные)
        поле(узел, "id", строка(mapper, "номер пункта в этом ответе, например s1 — на него ссылаются соседи"))
        поле(
            узел, "quote",
            строка(
                mapper,
                "ДОСЛОВНЫЙ фрагмент текста выше, 10–200 знаков. Пересказ недопустим: " +
                    "система сверяет цитату с каноном и пункт без совпадения отбрасывает",
            ),
        )
        поле(узел, "anchor", строка(mapper, "якорь блока, где найдена цитата, например s3#2"))
        поле(узел, "confidence", число(mapper, "уверенность 0…1"))
        return узел
    }

    private fun ссылка(mapper: ObjectMapper, пояснение: String): ObjectNode {
        val узел = объект(mapper, обязательные = emptyList())
        поле(узел, "code", строка(mapper, "код уже принятого понятия"))
        поле(узел, "ref", строка(mapper, "id пункта ЭТОГО же ответа"))
        узел.put("description", пояснение)
        return узел
    }

    private fun сторона(mapper: ObjectMapper): ObjectNode {
        val узел = пункт(mapper, обязательные = listOf("name", "role"))
        поле(узел, "name", строка(mapper, "имя организации, ведомства или группы"))
        поле(узел, "role", перечень(mapper, РОЛИ_СТОРОНЫ, "роль к проекту"))
        поле(узел, "interest", строка(mapper, "интерес одной фразой, если назван"))
        поле(узел, "scale", строка(mapper, "масштаб с единицей, если назван"))
        return узел
    }

    private fun нужда(mapper: ObjectMapper): ObjectNode {
        val узел = пункт(mapper, обязательные = listOf("statement", "stakeholder"))
        поле(узел, "statement", строка(mapper, "чего стороне не хватает или что она требует"))
        поле(узел, "stakeholder", ссылка(mapper, "чья нужда — code принятого либо ref пункта этого ответа"))
        поле(узел, "qos_class", строка(mapper, "класс обслуживания, если назван в тексте"))
        return узел
    }

    private fun цель(mapper: ObjectMapper): ObjectNode {
        val узел = пункт(mapper, обязательные = listOf("statement", "value", "year"))
        поле(узел, "statement", строка(mapper, "измеримый результат"))
        поле(узел, "value", строка(mapper, "значение с единицей, как в тексте"))
        поле(узел, "year", строка(mapper, "год или горизонт"))
        поле(узел, "needs", массив(mapper, ссылка(mapper, "нужда, которую цель закрывает")))
        return узел
    }

    private fun чужаяЦель(mapper: ObjectMapper): ObjectNode {
        val узел = пункт(mapper, обязательные = listOf("statement", "owner"))
        поле(узел, "statement", строка(mapper, "цель чужой программы или заказчика"))
        поле(узел, "owner", строка(mapper, "ЧЬЯ это цель — без этого она станет нашей по ошибке"))
        поле(узел, "value", строка(mapper, "значение с единицей, если названо"))
        поле(узел, "year", строка(mapper, "год, если назван"))
        return узел
    }

    private fun рамка(mapper: ObjectMapper): ObjectNode {
        val узел = пункт(mapper, обязательные = listOf("statement", "type"))
        поле(узел, "statement", строка(mapper, "что нельзя, что только так, не более/не менее чего"))
        поле(узел, "type", перечень(mapper, ТИПЫ_РАМКИ, "тип рамки"))
        поле(узел, "bound", строка(mapper, "числовая граница с единицей, если есть"))
        поле(узел, "normative_basis", строка(mapper, "обозначение норматива-основания, если назван"))
        return узел
    }

    private fun веха(mapper: ObjectMapper): ObjectNode {
        val узел = пункт(mapper, обязательные = listOf("name", "kind"))
        поле(узел, "name", строка(mapper, "имя этапа или события"))
        поле(узел, "kind", перечень(mapper, РОДЫ_ВЕХИ, "род вехи"))
        поле(узел, "date", строка(mapper, "дата или диапазон, как в тексте"))
        поле(узел, "fleet", строка(mapper, "числа этапа с единицей: сколько КА, терминалов"))
        return узел
    }

    private fun допущение(mapper: ObjectMapper): ObjectNode {
        val узел = пункт(mapper, обязательные = listOf("statement"))
        поле(
            узел, "statement",
            строка(mapper, "что принято на веру. Владельца и срок НЕ выписывай — их ставит человек"),
        )
        return узел
    }

    private fun применимость(mapper: ObjectMapper): ObjectNode {
        val узел = пункт(mapper, обязательные = listOf("external_item", "owner", "verdict"))
        поле(узел, "external_item", строка(mapper, "чужая нужда или программа"))
        поле(узел, "owner", строка(mapper, "чья она"))
        поле(узел, "verdict", перечень(mapper, ВЕРДИКТЫ_ПРИМЕНИМОСТИ, "чем наша система полезна"))
        поле(узел, "missing", строка(mapper, "чего не хватает"))
        поле(узел, "scale", строка(mapper, "объём с единицей, если назван"))
        return узел
    }

    private fun сервис(mapper: ObjectMapper): ObjectNode {
        val узел = пункт(mapper, обязательные = listOf("name"))
        поле(узел, "name", строка(mapper, "что система даёт"))
        поле(узел, "needs", массив(mapper, ссылка(mapper, "нужда, которую сервис закрывает")))
        поле(узел, "qos_class", строка(mapper, "класс обслуживания, если назван"))
        поле(узел, "target_measure", строка(mapper, "целевой показатель с единицей"))
        return узел
    }

    private fun объект(mapper: ObjectMapper, обязательные: List<String>): ObjectNode {
        val узел = mapper.createObjectNode()
        узел.put("type", "object")
        узел.putObject("properties")
        if (обязательные.isNotEmpty()) {
            val список = узел.putArray("required")
            обязательные.forEach { список.add(it) }
        }
        return узел
    }

    private fun поле(куда: ObjectNode, имя: String, что: JsonNode) {
        (куда.path("properties") as ObjectNode).set<JsonNode>(имя, что)
    }

    private fun строка(mapper: ObjectMapper, пояснение: String): ObjectNode =
        mapper.createObjectNode().put("type", "string").put("description", пояснение)

    private fun целое(mapper: ObjectMapper, пояснение: String): ObjectNode =
        mapper.createObjectNode().put("type", "integer").put("description", пояснение)

    private fun число(mapper: ObjectMapper, пояснение: String): ObjectNode =
        mapper.createObjectNode().put("type", "number").put("description", пояснение)

    private fun свободный(mapper: ObjectMapper, пояснение: String): ObjectNode =
        mapper.createObjectNode().put("type", "object").put("description", пояснение)

    private fun массив(mapper: ObjectMapper, чего: JsonNode): ObjectNode {
        val узел = mapper.createObjectNode()
        узел.put("type", "array")
        узел.set<JsonNode>("items", чего)
        return узел
    }

    private fun перечень(mapper: ObjectMapper, значения: List<String>, пояснение: String): ObjectNode {
        val узел = mapper.createObjectNode()
        узел.put("type", "string")
        val список: ArrayNode = узел.putArray("enum")
        значения.forEach { список.add(it) }
        узел.put("description", пояснение)
        return узел
    }
}
