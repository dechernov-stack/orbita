// Словарь — сущность первого порядка (РЕШЕНИЕ-СЛОВАРЬ-И-БАЗА-ЗНАНИЙ, истина
// `glossary_rule`): термин `glossary_term` с каноническим именем и синонимами.
// Здесь — привязка написания к термину: «Ространснадзор» и «Федеральная
// служба по надзору в сфере транспорта» — одна запись, если словарь их знает;
// незнакомое написание становится КАНДИДАТОМ с цитатой, принимает человек.
//
// Словарь класса миссии лежит в библиотеке, проект — дельта поверх: кандидаты
// и принятые в проекте термины живут в области проекта.
package orbita.knowledge.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.Provenance
import orbita.knowledge.api.Glossary
import orbita.knowledge.api.Term

internal class GlossaryIndex(
    private val store: EntityStore,
    private val mapper: ObjectMapper = ObjectMapper(),
) : Glossary {

    override fun terms(area: Area): List<Term> =
        (store.list(Area.Library, "glossary_term") + (if (area is Area.Library) emptyList() else store.list(area, "glossary_term")))
            .filter { it.status != "cancelled" && it.status != "obsolete" }
            .map { term(it) }

    override fun resolve(area: Area, text: String, cls: String?): Term? {
        val ключ = плоско(text)
        if (ключ.isBlank()) return null
        val кандидаты = terms(area).filter { cls == null || it.cls == cls || it.cls == "other" }
        // Принятый термин — раньше кандидата; библиотека — раньше проекта: словарь
        // класса миссии первичен, проект его дополняет, а не спорит.
        val порядок = кандидаты.sortedWith(compareBy({ it.status != "accepted" }, { it.area !is Area.Library }))
        return порядок.firstOrNull { плоско(it.termRu) == ключ }
            ?: порядок.firstOrNull { т -> т.synonyms.any { плоско(it) == ключ } || (т.objectCode != null && плоско(т.objectCode) == ключ) }
    }

    override fun candidate(area: Area, text: String, cls: String, quote: String?, fact: String?, author: String, source: String?): Term {
        require(area is Area.Project) { "кандидат в словарь заводится в проекте, не в библиотеке" }
        resolve(area, text, null)?.let { return it }
        val формулировка = text.trim()
        require(формулировка.isNotBlank()) { "термин без написания в словарь не идёт" }
        val документ: ObjectNode = mapper.createObjectNode()
            .put("term_ru", формулировка)
            .put("class", cls)
            .put("definition", "кандидат из документа: определение уточняет человек при принятии")
        quote?.takeIf { it.isNotBlank() }?.let { документ.put("quote", it) }
        fact?.takeIf { it.isNotBlank() }?.let { документ.put("fact", it) }
        документ.put("source", source?.ifBlank { null } ?: "документ проекта")
        val код = следующий(area)
        return term(store.create(код, "glossary_term", area, null, документ, Provenance(Channel.SERVICE, author), status = "candidate"))
    }

    override fun flat(text: String): String = плоско(text)

    /** Код термина проекта — по счёту записей проекта: GT-P-0001, GT-P-0002 … */
    private fun следующий(area: Area): String {
        val занятые = store.list(area, "glossary_term").map { it.code }.toSet()
        var n = занятые.size + 1
        while ("GT-P-" + n.toString().padStart(4, '0') in занятые) n += 1
        return "GT-P-" + n.toString().padStart(4, '0')
    }

    private fun term(з: Entity): Term = Term(
        id = з.id, code = з.code, termRu = з.doc.path("term_ru").asText(""), cls = з.doc.path("class").asText("other"),
        synonyms = з.doc.path("synonyms").map { it.asText() } + listOfNotNull(з.doc.path("term_en").asText("").ifBlank { null }),
        objectCode = з.doc.path("object_code").asText("").ifBlank { null },
        status = з.status, area = з.area,
    )

    companion object {
        /** Написание плоско: строчные, ё→е, без кавычек и лишних пробелов. */
        fun плоско(текст: String): String = текст.lowercase().replace('ё', 'е')
            .replace(Regex("[«»\"'()]"), " ").replace(Regex("\\s+"), " ").trim()
    }
}
