// База знаний (шип 4 §2): что индексируется и как ищется. Блоки — канон
// материалов проекта (абзацы с якорями), термины словаря (библиотека и проект),
// пункты нормативов полки, факты проекта с цитатами. Перестроение
// идемпотентно: отпечаток блока не изменился — ничего не пишется и не
// эмбеддится; исчезнувшие блоки снимаются. Структура системы и связи здесь
// не индексируются — они ищутся точно по реестру.
package orbita.knowledge.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.kernel.api.Area
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.IndexBlock
import orbita.kernel.api.IndexHit
import orbita.kernel.api.TextIndex
import orbita.knowledge.api.Embeddings
import orbita.knowledge.api.IndexReport
import orbita.knowledge.api.KnowledgeIndex
import java.security.MessageDigest

internal class KnowledgeIndexer(
    private val store: EntityStore,
    private val index: TextIndex,
    private val embeddings: Embeddings?,
    private val mapper: ObjectMapper = ObjectMapper(),
) : KnowledgeIndex {

    override val vectorReady: Boolean get() = index.vectorReady && embeddings != null

    override fun rebuild(project: String): IndexReport {
        val область = Area.Project(project)
        val блоки = каноны(область) + термины(Area.Library) + термины(область) + пункты() + факты(область)
        val записано = index.upsert(блоки)
        var снято = 0
        блоки.groupBy { it.area to it.kind }.forEach { (ключ, свои) ->
            снято += index.removeMissing(ключ.first, ключ.second, свои.map { it.key }.toSet())
        }
        // Пустые группы тоже чистятся: у проекта без фактов их блоков быть не должно.
        listOf("canon_block", "fact", "term").forEach { вид ->
            if (блоки.none { it.area == область.asText() && it.kind == вид }) снято += index.removeMissing(область.asText(), вид, emptySet())
        }
        val заэмбеддено = заэмбеддить(область)
        return IndexReport(
            blocks = блоки.size, written = записано, removed = снято, embedded = заэмбеддено, vector = vectorReady,
            note = "индекс ${project}: блоков ${блоки.size}, записано $записано, снято $снято" +
                (if (index.vectorReady) (if (embeddings == null) "; эмбеддингов нет — провайдер не задан (ORBITA_EMBED_URL), поиск лексический" else "; заэмбеддено $заэмбеддено")
                else "; расширения pgvector в базе нет — поиск лексический"),
        )
    }

    override fun search(project: String, query: String, filters: Map<String, String>, kinds: List<String>, limit: Int): List<IndexHit> {
        val вектор = if (vectorReady && query.isNotBlank()) runCatching { embeddings!!.embed(listOf(query)).firstOrNull() }.getOrNull() else null
        return index.search(query, listOf(Area.Library.asText(), Area.Project(project).asText()), filters, kinds, вектор, limit)
    }

    private fun заэмбеддить(область: Area): Int {
        val провайдер = embeddings ?: return 0
        if (!index.vectorReady) return 0
        var всего = 0
        listOf(Area.Library.asText(), область.asText()).forEach { где ->
            while (true) {
                val пачка = index.withoutVector(где, 64)
                if (пачка.isEmpty()) break
                val векторы = провайдер.embed(пачка.map { (it.title + "\n" + it.text).take(8000) })
                пачка.zip(векторы).forEach { (блок, вектор) -> index.setVector(блок.key, вектор) }
                всего += пачка.size
                if (векторы.size < пачка.size) break
            }
        }
        return всего
    }

    // --- блоки ---------------------------------------------------------------

    private fun каноны(область: Area): List<IndexBlock> =
        store.list(область, "material").filter { it.status != "cancelled" }.flatMap { м ->
            val текст = м.doc.path("text").asText("")
            if (текст.isBlank()) emptyList() else Canon.of(текст).blocks.filter { it.text.isNotBlank() }.map { б ->
                блок(
                    "canon:${м.code}:${б.anchor}", "canon_block", область, м.code,
                    title = "${м.doc.path("name").asText(м.code)} · ${б.anchor}", text = б.text,
                    filters = mapOf("role" to м.doc.path("role").asText(""), "rank" to м.doc.path("rank").asText(""), "material" to м.code),
                )
            }
        }

    private fun термины(область: Area): List<IndexBlock> =
        store.list(область, "glossary_term").filter { it.status != "cancelled" && it.status != "obsolete" }.map { т ->
            val синонимы = т.doc.path("synonyms").map { it.asText() } + listOfNotNull(т.doc.path("term_en").asText("").ifBlank { null })
            блок(
                "term:${т.code}", "term", область, т.code,
                title = т.doc.path("term_ru").asText(т.code),
                text = (синонимы.joinToString(" · ") + "\n" + т.doc.path("definition").asText("") + "\n" + т.doc.path("difference").asText("")).trim(),
                filters = mapOf("class" to т.doc.path("class").asText(""), "status" to т.status),
            )
        }

    private fun пункты(): List<IndexBlock> =
        store.list(Area.Library, "normative_document").filter { it.status != "cancelled" }.flatMap { н ->
            н.doc.path("clauses").mapIndexed { i, п ->
                блок(
                    "clause:${н.code}:${i + 1}", "clause", Area.Library, н.code,
                    title = "${н.doc.path("designation").asText(н.code)} · ${п.path("clause").asText("п. ${i + 1}")}",
                    text = (п.path("text").asText("") + (п.path("who").asText("").takeIf { it.isNotBlank() }?.let { "\nкто: $it" } ?: "")).trim(),
                    filters = mapOf("rank" to "mandatory", "role" to "regulatory", "normative" to н.code),
                )
            }
        }

    private fun факты(область: Area): List<IndexBlock> {
        val материалы = store.list(область, "material").associateBy { it.code }
        return store.list(область, "fact").filter { it.status != "cancelled" && it.doc.path("disposition").asText("") != "rejected" }.map { ф ->
            val материал = материалы[ф.doc.path("material").asText("")]
            val цитата = ф.doc.path("quote").asText("").ifBlank { null }
            блок(
                "fact:${ф.code}", "fact", область, ф.code,
                title = "${ф.doc.path("subject").asText("")} — ${ф.doc.path("predicate").asText("")}",
                text = цитата ?: "${ф.doc.path("value").asText("")} ${ф.doc.path("unit").asText("")}".trim(),
                filters = mapOf(
                    "rank" to ф.doc.path("rank").asText(""), "mark" to ф.doc.path("mark").asText(""),
                    "scene" to (ф.bornIn ?: ""), "role" to (материал?.doc?.path("role")?.asText("") ?: ""),
                    "material" to ф.doc.path("material").asText(""),
                ),
            )
        }
    }

    private fun блок(key: String, kind: String, область: Area, ref: String, title: String, text: String, filters: Map<String, String>): IndexBlock {
        val чистые = filters.filterValues { it.isNotBlank() }
        return IndexBlock(key, kind, область.asText(), ref, title, text, чистые, отпечаток(title, text, чистые))
    }

    private fun отпечаток(vararg части: Any): String {
        val md = MessageDigest.getInstance("SHA-256")
        части.forEach { md.update(it.toString().toByteArray()); md.update(0) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
