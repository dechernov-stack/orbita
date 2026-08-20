// Импорт внешних данных: третий канал в модель (шаг 14, ADR-024).
// Поведение — эталон spec/import_semantics.py, один в один.
//
// Для импорта действует то же правило, что установлено на шаге 5 для
// предложений ИИ: ТОТ ЖЕ ФИЛЬТР. Никаких послаблений вида «данные от вендора,
// значит корректные»: иначе импорт станет мягким подбрюшьем — то, что нельзя
// ввести руками, зайдёт через загрузку.
//
// ПРАВОВОЙ РЕЖИМ ФИКСИРУЕТСЯ ДО ЗАГРУЗЧИКА. Часть источников допускает
// извлечение отдельных записей и запрещает выгрузку каталога целиком
// (sui generis право, Директива 96/9/EC). Различие не в удобстве, а в праве,
// и отыграть его после написания массового загрузчика дорого. Источник без
// описанного режима ЗАПРЕЩЁН, а не разрешён по умолчанию.
package orbita.mod.model

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path

/** Правовой режим источника: условия и допустимость массовой выгрузки. */
data class ImportSource(val name: String, val terms: String, val bulk: Boolean)

/** Вердикт на попытку импорта: разрешено либо причина отказа. */
data class ImportVerdict(val allowed: Boolean, val reason: String?)

class ImportPolicy(json: String = defaultJson()) {

    val sources: Map<String, ImportSource> = mapper.readTree(json).path("sources")
        .properties().associate { (name, s) ->
            name to ImportSource(name, s.path("terms").asText(), s.path("bulk").asBoolean(false))
        }

    /**
     * Допустимость импорта. mode: `item` — отдельная запись, `bulk` — каталог
     * целиком. Массовой выгрузки защищённого каталога в системе нет как пути:
     * эта функция — последний рубеж, а не единственный.
     */
    fun importAllowed(source: String, mode: String): ImportVerdict {
        val s = sources[source]
            ?: return ImportVerdict(false, "источник $source не описан: правовой режим неизвестен")
        if (mode == "bulk" && !s.bulk) {
            return ImportVerdict(
                false,
                "$source: массовая выгрузка запрещена условиями источника (${s.terms}); " +
                    "допустим импорт отдельных записей",
            )
        }
        return ImportVerdict(true, null)
    }

    /** Происхождение импортированной записи: без него запись непригодна. */
    fun provenanceFor(
        source: String,
        version: String,
        retrievedAt: String,
        itemRef: String? = null,
        mappingVersion: String = "1",
        mapper: ObjectMapper = ImportPolicy.mapper,
    ): ObjectNode {
        val s = sources[source] ?: throw IllegalArgumentException("источник $source не описан")
        val node = mapper.createObjectNode()
        node.put("source", "imported")
        val imp = node.putObject("import")
        imp.put("dataset", source)
        imp.put("dataset_version", version)
        imp.put("retrieved_at", retrievedAt)
        itemRef?.let { imp.put("item_ref", it) }
        imp.put("terms", s.terms)
        imp.put("bulk_allowed", s.bulk)
        imp.put("mapping_version", mappingVersion)
        return node
    }

    companion object {
        private val mapper = ObjectMapper()

        fun defaultJson(): String {
            System.getenv("ORBITA_IMPORT_SOURCES")?.let { return Files.readString(Path.of(it)) }
            return ImportPolicy::class.java.getResourceAsStream("/orbita/mod/import-sources.json")!!
                .use { it.readAllBytes().decodeToString() }
        }
    }
}

/**
 * Неполнота происхождения импортированной записи. Ручной ввод происхождения
 * импорта не требует; `imported` без источника, версии, даты и условий —
 * запись, про которую через полгода нельзя сказать, откуда она и можно ли
 * показывать её заказчику.
 */
fun provenanceIssues(provenance: JsonNode): List<String> {
    if (provenance.path("source").asText("") != "imported") return emptyList()
    val block = provenance.path("import")
    return listOf("dataset", "dataset_version", "retrieved_at", "terms")
        .filter { block.path(it).asText("").isBlank() }
        .map { "не указано: $it" }
}

/** Исход повторного импорта. */
enum class MergeAction { Added, Updated }

/**
 * Повторный импорт ОБНОВЛЯЕТ запись, а не создаёт дубликат; ключ —
 * идентификатор записи в источнике (`provenance.import.item_ref`).
 *
 * РУЧНАЯ ПРАВКА ПОВЕРХ ИМПОРТА НЕ ЗАТИРАЕТСЯ: поля, помеченные в `_edited`,
 * сохраняют своё значение — инженер правил осознанно. То же правило, что для
 * предложенного размещения станций (шаг 12): предложение не переписывает
 * ручное.
 */
fun mergeImported(
    existing: List<ObjectNode>,
    incoming: ObjectNode,
): Pair<List<ObjectNode>, MergeAction> {
    val key = incoming.path("provenance").path("import").path("item_ref").asText("")
    val index = existing.indexOfFirst {
        it.path("provenance").path("import").path("item_ref").asText("") == key && key.isNotBlank()
    }
    // listOf, а не `existing + incoming`: ObjectNode сам Iterable<JsonNode>,
    // и plus-перегрузка для Iterable рассыпала бы запись на значения полей
    if (index < 0) return existing + listOf(incoming) to MergeAction.Added

    val current = existing[index]
    val merged: ObjectNode = current.deepCopy()
    merged.setAll<ObjectNode>(incoming)
    current.path("_edited").properties().forEach { (field, flag) ->
        if (flag.asBoolean(false) && current.has(field)) {
            merged.set<ObjectNode>(field, current.path(field).deepCopy<JsonNode>())
        }
    }
    if (current.has("_edited")) {
        merged.set<ObjectNode>("_edited", current.path("_edited").deepCopy<JsonNode>())
    }
    return existing.toMutableList().also { it[index] = merged }.toList() to MergeAction.Updated
}
