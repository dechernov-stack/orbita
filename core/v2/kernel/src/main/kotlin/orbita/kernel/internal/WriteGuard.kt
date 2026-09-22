// Сторож записи — правило истины 21-09 (write_rules.schema_on_write):
// «любая запись сущности валидируется истиной: поле вне схемы вида — отказ,
// а не молчаливое сохранение».
package orbita.kernel.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.kernel.schema.Enums
import orbita.kernel.schema.GeneratedKinds

/**
 * Что проверяется на КАЖДОЙ записи (create и update): вид — в истине (вида
 * вне истины не существует), имена полей верхнего уровня — по полям вида и
 * ядру, значения перечислений — по перечням истины (русское имя узнаётся).
 * Обязательность НЕ проверяется: истина 18.09 сделала её ступенчатой
 * (`required_at`) — приём спрашивает своё, остальное ждёт своей сцены.
 *
 * Допуск — поимённый и с причиной, из ресурса `write-allowance.json`: обмер
 * стенда 22.09 нашёл двадцать видов с полями вне истины (полки-шаблоны
 * содержимым поставки, контур знаний, полдюжины полей продукта) и три вида
 * полок, истине неизвестных. Отказать им разом значило бы остановить контур
 * знаний и полки; молчать — нарушить правило. Ратчет
 * `tools/validate_write_allowance.py` следит, чтобы допуск только таял.
 */
object WriteGuard {

    /** Поля ядра — колонки записи и две пометы; они не поля вида, но законны в документе. */
    private val ЯДРО = setOf(
        "id", "code", "kind", "area", "born_in", "status", "version",
        "provenance", "created_at", "updated_at", "notes", "tags",
    )

    private val допуск: JsonNode by lazy {
        val поток = WriteGuard::class.java.getResourceAsStream("/orbita/kernel/write-allowance.json")
            ?: error("допуск сторожа записи не найден в ресурсах ядра")
        поток.use { ObjectMapper().readTree(it) }
    }

    private val видыВнеИстины: Set<String> by lazy {
        допуск.path("kinds_outside_truth").fieldNames().asSequence().toSet()
    }

    private val поляВнеИстины: Map<String, Set<String>> by lazy {
        допуск.path("fields_outside_truth").properties().associate { (вид, поля) ->
            вид to поля.map { it.asText() }.toSet()
        }
    }

    /** Значения перечней, которые код пишет, а истина не назвала: «вид.поле» → значения. */
    private val значенияВнеИстины: Map<String, Set<String>> by lazy {
        допуск.path("enum_values_outside_truth").properties().associate { (путь, значения) ->
            путь to значения.map { it.asText() }.toSet()
        }
    }

    /** Беды записи словами; пусто — записывать можно. */
    fun problems(kind: String, doc: JsonNode): List<String> {
        if (kind in видыВнеИстины) return emptyList()
        val спец = GeneratedKinds.byCode[kind]
            ?: return listOf("вида «$kind» в истине схем нет — такой записи не существует")
        val известные = спец.fields.toSet() + ЯДРО + поляВнеИстины[kind].orEmpty()
        val чужие = doc.fieldNames().asSequence().filter { it !in известные }.map {
            "поле «$it» у вида «$kind» истиной не названо"
        }.toList()
        return чужие + перечни(kind, doc)
    }

    /**
     * Значение перечня — из перечня истины (код или русское имя); иначе отказ
     * словами, как у правки на месте. Значение из допуска пропускается.
     */
    private fun перечни(kind: String, doc: JsonNode): List<String> {
        val спец = GeneratedKinds.byCode[kind] ?: return emptyList()
        return спец.enums.mapNotNull { (поле, перечень) ->
            val узел = doc.path(поле)
            if (!узел.isTextual) return@mapNotNull null
            val значение = узел.asText().trim()
            val законно = значение.isEmpty() || значение in перечень ||
                Enums.код(kind, поле, значение) != null ||
                значение in значенияВнеИстины["$kind.$поле"].orEmpty()
            if (законно) return@mapNotNull null
            val метки = GeneratedKinds.enumLabels["$kind.$поле"]
            val слова = перечень.joinToString(" · ") { метки?.get(it) ?: it }
            "«${спец.labels[поле] ?: поле}»: значения «$значение» в перечне истины нет — $слова"
        }
    }
}
