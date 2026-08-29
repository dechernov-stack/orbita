// Д3: индекс и поиск по материалам проекта. Канон Д1 — единственный носитель
// текста, поэтому и поиск идёт по нему: строка находится с координатой блока,
// а не «где-то в файле». Индекс строится из канонов и живёт в памяти по
// отпечатку разбора: файл не менялся — индекс не пересчитывается.
//
// Ищется двумя способами сразу: подстрокой (полнотекст) и по термам
// глоссария, которые Д1 уже разметил в карте, — так «зона обслуживания»
// находит блоки, где термин употреблён, даже если запрос написан иначе.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode

/** Находка: документ, координата блока и фрагмент вокруг совпадения. */
data class SearchHit(
    val document: String,
    val documentName: String,
    val anchor: String,
    val section: String?,
    val fragment: String,
    val by: String,
)

class DocumentSearch(private val boundary: Boundary) {

    private val mapper = ObjectMapper()

    /** Блок канона: якорь, заголовок раздела и текст. */
    private data class Block(val anchor: String, val section: String?, val text: String)

    /** Индекс по отпечатку разбора: пересчитывается только при новом разборе. */
    private val index = mutableMapOf<String, List<Block>>()

    fun search(projectId: String, query: String, limit: Int = 40): List<SearchHit> {
        val needle = query.trim()
        if (needle.length < 2) return emptyList()
        val docs = boundary.objects.listCurrent(projectId)
            .filter { it.type == "source_document" && it.status.name != "Cancelled" }
        val hits = mutableListOf<SearchHit>()
        docs.sortedBy { it.id }.forEach { sd ->
            val map = DocumentParseStore.mapOf(filesDir(), sd.id) ?: return@forEach
            val fingerprint = map.path("fingerprint").asText(sd.id)
            val blocks = index.getOrPut(fingerprint) {
                blocksOf(DocumentParseStore.canonOf(filesDir(), sd.id) ?: "")
            }
            val name = sd.doc.path("name").asText(sd.id)

            blocks.forEach { b ->
                val at = b.text.indexOf(needle, ignoreCase = true)
                if (at >= 0) {
                    hits += SearchHit(sd.id, name, b.anchor, b.section, fragmentOf(b.text, at, needle.length), "текст")
                }
            }
            // термы: карта Д1 уже знает, где термин употреблён — запрос,
            // совпавший с термом, находит блоки без повторного разбора
            map.path("terms").forEach { t ->
                val term = t.path("term").asText()
                if (!term.equals(needle, ignoreCase = true) && !term.contains(needle, ignoreCase = true)) return@forEach
                t.path("blocks").forEach { blockNode ->
                    val anchor = blockNode.asText()
                    if (hits.any { it.document == sd.id && it.anchor == anchor }) return@forEach
                    val block = blocks.firstOrNull { it.anchor == anchor }
                    hits += SearchHit(
                        sd.id, name, anchor, block?.section,
                        block?.text?.take(160) ?: "", "терм «$term»",
                    )
                }
            }
        }
        return hits.take(limit)
    }

    /** Разбор канона на блоки по якорям — тем же координатам, что в карте. */
    private fun blocksOf(canon: String): List<Block> {
        val out = mutableListOf<Block>()
        var anchor: String? = null
        var section: String? = null
        val text = StringBuilder()
        fun flush() {
            val a = anchor ?: return
            val body = text.toString().trim()
            if (body.isNotEmpty()) out += Block(a, section, body)
            text.setLength(0)
        }
        canon.lineSequence().forEach { line ->
            val comment = Regex("""^<!--\s*([bt]\d+[^\s]*)\s*-->$""").find(line.trim())
            val heading = Regex("""^(#{1,6})\s+(.*?)\s*\{#([bs]\d+)}$""").find(line.trim())
            when {
                comment != null -> {
                    flush()
                    anchor = comment.groupValues[1]
                }
                heading != null -> {
                    flush()
                    anchor = heading.groupValues[3]
                    if (heading.groupValues[3].startsWith("s")) section = heading.groupValues[2]
                    text.append(heading.groupValues[2])
                    flush()
                    anchor = null
                }
                else -> if (anchor != null) text.appendLine(line)
            }
        }
        flush()
        return out
    }

    private fun fragmentOf(text: String, at: Int, length: Int): String {
        val from = (at - 60).coerceAtLeast(0)
        val to = (at + length + 60).coerceAtMost(text.length)
        return (if (from > 0) "…" else "") + text.substring(from, to).replace("\n", " ") +
            (if (to < text.length) "…" else "")
    }

    private fun filesDir(): String =
        System.getProperty("orbita.test.filesDir")
            ?: System.getenv("ORBITA_FILES_DIR")
            ?: "files"

    fun toJson(hits: List<SearchHit>): ArrayNode = mapper.createArrayNode().apply {
        hits.forEach { h ->
            val node = addObject()
                .put("document", h.document).put("document_name", h.documentName)
                .put("anchor", h.anchor).put("fragment", h.fragment).put("by", h.by)
            h.section?.let { node.put("section", it) }
        }
    }
}
