// Запрос раздела: как элемент документа превращается в таблицу.
//
// Запрос — единственный способ попасть содержанию в документ автоматически.
// Он не «выбирает по смыслу»: он называет вид сущности, отбор и колонки, и
// ровно это отдаёт. Пусто — значит выходов сцены ещё нет, и раздел это
// говорит вслух, а не молчит пустотой.
package orbita.documents.internal

import com.fasterxml.jackson.databind.JsonNode
import orbita.kernel.api.Area
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry

internal class Queries(
    private val store: EntityStore,
    private val links: LinkRegistry,
) {

    /**
     * Строки запроса: список значений по колонкам, уже человеческим текстом.
     *
     * @param подписи словарь служебных значений из ШАБЛОНА: «functional» в
     *   печати не бывает, а держать перевод в коде значило бы править
     *   продукт ради слова
     */
    fun rows(project: String, элемент: JsonNode, подписи: Map<String, String> = emptyMap()): List<List<String>> {
        val вид = элемент.path("select").asText("")
        if (вид.isBlank()) return emptyList()
        // Полка — законная область запроса (SEMP §2 нормативы, §6 каталог
        // процессов): документ проекта читает общие знания, не копируя их.
        val область = if (элемент.path("area").asText("") == "library") Area.Library else Area.Project(project)
        val все = store.list(область, вид).filter { it.status != "cancelled" }
        val колонки = элемент.path("columns").map { it.path("field").asText("") }
        val разворот = элемент.path("expand").asText("")
        val отбор = элемент.path("where")

        if (разворот.isNotBlank()) {
            // Разворот: одна сущность даёт по строке на каждый элемент своего
            // списка (отклонённые варианты, отложенное содержание).
            return все.flatMap { сущность ->
                сущность.doc.path(разворот).map { пункт ->
                    колонки.map { поле -> поПодписи(человечно(путём(пункт, поле)), подписи) }
                }
            }
        }
        return все.filter { подходит(it, отбор) }
            .map { сущность -> колонки.map { поле -> поПодписи(значение(сущность, поле), подписи) } }
    }

    /** Отбор: точное совпадение по полю либо предел уровня состава. */
    private fun подходит(сущность: Entity, отбор: JsonNode): Boolean {
        if (!отбор.isObject || отбор.isEmpty) return true
        return отбор.properties().all { (поле, ожидание) ->
            when (поле) {
                // Состав до уровня N: документ показывает верхушку дерева,
                // а не все сто тридцать пять узлов.
                "level_max" -> сущность.doc.path("level").asInt(Int.MAX_VALUE) <= ожидание.asInt()
                "unresolved" -> сущность.doc.path("resolved_to").asText("").isBlank() == ожидание.asBoolean(true)
                // Пакеты одной группы WBS: «04.» — созревание технологий (FA §2).
                "code_prefix" -> сущность.code.startsWith(ожидание.asText())
                // Ключевые риски: критичность = вероятность × влияние (шкала 1–5).
                "criticality_min" ->
                    сущность.doc.path("probability").asInt(0) * сущность.doc.path("impact").asInt(0) >= ожидание.asInt()
                // Природа носителя / цели: ICD берёт требования на стыки и
                // параметры стыков, не зная их кодов заранее.
                "carrier_kind" -> store.byId(сущность.doc.path("carrier").asText(""))?.kind == ожидание.asText()
                "target_kind" -> store.byId(сущность.doc.path("target").asText(""))?.kind == ожидание.asText()
                else -> путём(сущность.doc, поле).asText("") == ожидание.asText()
            }
        }
    }

    /**
     * Значение колонки. Поле читается из документа сущности точечным путём;
     * `code` и `status` — из самой сущности; `link:<тип>` — связанные
     * сущности человеческими названиями, а не идентификаторами.
     */
    private fun значение(сущность: Entity, поле: String): String = when {
        поле == "code" -> сущность.code
        поле == "status" -> сущность.status
        поле.startsWith("link:") -> связанные(сущность.id, поле.removePrefix("link:"))
        else -> поИмени(человечно(путём(сущность.doc, поле)))
    }

    /**
     * Ссылка на сущность в печать не идёт идентификатором: «носитель
     * component-b562b483» человеку не говорит ничего, а печать обязана быть
     * человеческим текстом. Идентификатор заменяется именем цели.
     */
    private fun поИмени(значение: String): String =
        if (!ИДЕНТИФИКАТОР.matches(значение)) значение
        else store.byId(значение)?.let { имя(it) } ?: значение

    private fun связанные(id: String, тип: String): String {
        val вперёд = links.from(id, тип).mapNotNull { store.byId(it.to) }
        val назад = links.to(id, тип).mapNotNull { store.byId(it.from) }
        return (вперёд + назад).joinToString(" · ") { имя(it) }
    }

    /** Имя сущности для человека: заголовок, формулировка или код. */
    private fun имя(сущность: Entity): String = listOf("name", "title", "statement", "label")
        .firstNotNullOfOrNull { сущность.doc.path(it).asText("").ifBlank { null } }
        ?: сущность.code

    /** Служебное значение — словом шаблона; неизвестное остаётся как есть. */
    private fun поПодписи(значение: String, подписи: Map<String, String>): String =
        подписи[значение] ?: значение

    private val ИДЕНТИФИКАТОР = Regex("""[a-z_]+-[0-9a-f]{6,}""")

    private fun путём(узел: JsonNode, путь: String): JsonNode =
        путь.split(".").fold(узел) { н, часть -> н.path(часть) }

    /**
     * Величина в печатный вид. Правило печати одно: служебных ключей и
     * латинских имён видов в тексте не бывает — их место в модели.
     */
    private fun человечно(узел: JsonNode): String = when {
        узел.isMissingNode || узел.isNull -> ""
        узел.isBoolean -> if (узел.asBoolean()) "да" else "нет"
        узел.isArray -> узел.joinToString(" · ") { человечно(it) }
        узел.isObject -> {
            val величина = узел.path("value")
            val участник = узел.path("actor").asText("")
            val что = узел.path("what").asText("")
            when {
                !величина.isMissingNode ->
                    listOf(человечно(величина), узел.path("unit").asText(""))
                        .filter { it.isNotBlank() }.joinToString(" ")
                // Шаг сценария читается как «участник: что». Общее склеивание
                // полей дало бы «принять пакет · терминал · TERM» — набор
                // слов вместо шага, да ещё с кодом узла в печатном тексте.
                участник.isNotBlank() && что.isNotBlank() -> "$участник: $что"
                else -> узел.properties().joinToString(" · ") { (_, v) -> человечно(v) }
            }
        }
        else -> узел.asText("")
    }
}
