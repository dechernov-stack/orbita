// Д1: разбор живёт РЯДОМ С ФАЙЛОМ, не в модели. Причина — решение ADR:
// текст документа хранится единственный раз (MD-канон), карта несёт только
// координаты; модель объектов не раздувается копиями чужих текстов, а
// разбор переживает переиндексацию сменой имени, не версией объекта.
//
// Имя разбора — отпечаток: хеш файла + версия разборщика. Новый файл или
// новый разборщик — новое имя; старое остаётся, пока его не подберут.
package orbita.com.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.store.ObjectStore
import orbita.out.DocumentParse
import orbita.out.ParseLexicon
import java.nio.file.Files
import java.nio.file.Path

object DocumentParseStore {

    private val mapper = ObjectMapper()

    /** Словари системы для разбора — данными полок LIB, не кодом. */
    fun lexiconOf(boundary: Boundary): ParseLexicon {
        val lib = boundary.objects.listCurrent(ObjectStore.LIBRARY_PROJECT)
        val registryDoc = lib.firstOrNull { it.type == "unit_registry" && it.status.name != "Cancelled" }?.doc
        val spellings = mutableMapOf<String, String>()
        registryDoc?.path("dimensions")?.forEach { d ->
            d.path("spellings").forEach { s -> spellings[s.asText().lowercase()] = d.path("canon").asText() }
            d.path("inputs").forEach { i ->
                i.path("spellings").forEach { s -> spellings[s.asText().lowercase()] = i.path("unit").asText() }
            }
        }
        val terms = lib.filter { it.type == "glossary" && it.status.name != "Cancelled" }
            .flatMap { g -> g.doc.path("entries").filterNot { it.has("sd_kind") } }
            // подсказки типов документов термами текста не считаются: их
            // место — карточка загрузки, а не разметка чужого документа
            .map { it.path("term").asText() }
            .filter { it.isNotBlank() }
        val index = registryDoc?.let { UnitRegistryIndex(it) }
        return ParseLexicon(
            unitSpellings = spellings,
            terms = terms,
            toCanon = { value, unit -> index?.toCanon(value, unit) },
        )
    }

    private fun dirOf(filesDir: String, sdId: String): Path = Path.of(filesDir, sdId, "parse")

    /**
     * Разбор документа: канон и карта кладутся рядом с файлом. Повторный
     * вызов на том же файле и той же версии разборщика ничего не считает —
     * возвращает готовый отпечаток (кэш по хешу, решение ADR).
     */
    fun parseAndStore(
        filesDir: String,
        sdId: String,
        fileName: String,
        bytes: ByteArray,
        lexicon: ParseLexicon,
    ): String? {
        val fingerprint = DocumentParse.fingerprint(bytes, lexicon)
        val dir = dirOf(filesDir, sdId)
        val mapFile = dir.resolve("$fingerprint.json")
        if (Files.exists(mapFile)) return fingerprint
        val parsed = DocumentParse.parse(fileName, bytes, lexicon) ?: return null
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("$fingerprint.md"), parsed.canonMd)
        // Д3: длинные листы книги — приложениями-CSV рядом с каноном; канон
        // остаётся читаемым, а данные не теряются
        parsed.appendices.forEach { (name, csv) ->
            Files.writeString(dir.resolve("$fingerprint.$name"), csv)
        }
        val map = parsed.map
        map.put("source_document", sdId)
        map.put("source_file", fileName)
        map.put("fingerprint", fingerprint)
        map.put("canonical_text", "$fingerprint.md — единственный носитель текста; ссылки ниже — якоря в нём")
        Files.writeString(mapFile, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(map))
        return fingerprint
    }

    /** Карта разбора: самая свежая по времени записи; null — разбора нет. */
    fun mapOf(filesDir: String, sdId: String): JsonNode? = latest(filesDir, sdId, ".json")
        ?.let { mapper.readTree(Files.readString(it)) }

    /** MD-канон разбора — текстом; null — разбора нет. */
    fun canonOf(filesDir: String, sdId: String): String? = latest(filesDir, sdId, ".md")
        ?.let { Files.readString(it) }

    private fun latest(filesDir: String, sdId: String, suffix: String): Path? {
        val dir = dirOf(filesDir, sdId)
        if (!Files.isDirectory(dir)) return null
        Files.list(dir).use { stream ->
            return stream
                // рядом лежит урожай Д2 (<отпечаток>.harvest.json) — он тоже
                // .json, но это ДРУГОЙ слой: карту им подменять нельзя
                .filter { val n = it.fileName.toString(); n.endsWith(suffix) && !n.endsWith(".harvest.json") }
                .max(compareBy { Files.getLastModifiedTime(it) })
                .orElse(null)
        }
    }
}
