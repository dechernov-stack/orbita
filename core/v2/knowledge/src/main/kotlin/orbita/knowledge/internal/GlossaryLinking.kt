// Привязка при чтении (истина `glossary_rule`, шип 4 §2): всё извлечённое
// линкуется к словарю — сторона по имени, узел по коду или имени. Не
// привязалось — написание становится КАНДИДАТОМ в словарь с цитатой, и его
// принимает человек. Связь `named_by` от сущности к термину: по ней словарь
// знает, кто как назван, а дедупликация сторон идёт через словарь, не через
// порог семантической близости.
package orbita.knowledge.internal

import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.kernel.api.Provenance
import orbita.knowledge.api.Glossary
import orbita.knowledge.api.Term

/**
 * Источник кандидата словами (дефект из отчёта шипа 5, ответ владельца 26.09):
 * написание, пришедшее разбором документа, несёт цитату или факт; кнопка
 * «Привязать стороны и узлы проекта» — ни того, ни другого, и её кандидат —
 * «привязка стороны проекта» (узла — «привязка узла проекта»), а не «разбор
 * документа»: полоса кандидатов называется по источнику.
 */
internal fun источникКандидата(класс: String, цитата: String?, факт: String?): String = when {
    !цитата.isNullOrBlank() || !факт.isNullOrBlank() -> ИЗ_РАЗБОРА
    класс == "node" -> "привязка узла проекта"
    else -> "привязка стороны проекта"
}

internal const val ИЗ_РАЗБОРА = "разбор документа проекта"

internal class GlossaryLinking(
    private val store: EntityStore,
    private val links: LinkRegistry?,
    private val glossary: Glossary,
) {
    /**
     * Привязать сущность к термину; вернуть заметку человеку, если написание
     * стало кандидатом либо канон словаря отличается от написания в записи.
     */
    fun привязать(область: Area, сущность: Entity, вид: String, author: String, цитата: String?, факт: String?): String? {
        val реестр = links ?: return null
        if (область !is Area.Project) return null
        val класс = if (вид == "component") "node" else "stakeholder"
        val написания = when (вид) {
            "component" -> listOfNotNull(сущность.doc.path("code").asText("").ifBlank { null }, сущность.code, сущность.doc.path("name").asText("").ifBlank { null })
            else -> listOfNotNull(сущность.doc.path("name").asText("").ifBlank { null })
        }
        val имя = написания.firstOrNull() ?: return null
        val провенанс = Provenance(Channel.SERVICE, author)
        val найденный = написания.firstNotNullOfOrNull { glossary.resolve(область, it, класс) }
        val термин: Term = найденный ?: glossary.candidate(
            область, имя, класс, цитата, факт, author,
            source = источникКандидата(класс, цитата, факт),
        )
        if (реестр.to(термин.id, "named_by").none { it.from == сущность.id }) {
            реестр.link("named_by", сущность.id, термин.id, провенанс, rationale = "названо термином словаря: «${термин.termRu}»")
        }
        return when {
            найденный == null -> "${сущность.code}: «$имя» — кандидат в словарь (${термин.code}), примите его на экране «Словарь»"
            glossary.flat(термин.termRu) != glossary.flat(имя) ->
                "${сущность.code}: «$имя» — по словарю ${термин.code} «${термин.termRu}»"
            else -> null
        }
    }
}
