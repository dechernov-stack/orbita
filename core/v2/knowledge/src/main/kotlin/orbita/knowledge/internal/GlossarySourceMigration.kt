// Источник кандидата словаря (ответ владельца 26.09): кандидаты, заведённые
// кнопкой «Привязать стороны и узлы проекта» до правки, несут источник «разбор
// документа проекта». Отличить их можно по данным: у кандидата из разбора есть
// цитата или факт-основание, у привязки — нет ни того, ни другого. Миграция
// идемпотентна и идёт при старте ядра вместе с прочими миграциями понятий.
package orbita.knowledge.internal

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.kernel.api.Channel
import orbita.kernel.api.EntityStore
import orbita.kernel.api.Provenance

internal class GlossarySourceMigration(private val store: EntityStore, private val mapper: ObjectMapper = ObjectMapper()) {

    fun run(): String {
        var исправлено = 0
        store.ofKind("glossary_term")
            .filter { it.status == "candidate" && it.doc.path("source").asText("") == ИЗ_РАЗБОРА }
            .forEach { кандидат ->
                val д = кандидат.doc
                val источник = источникКандидата(д.path("class").asText(""), д.path("quote").asText("").ifBlank { null }, д.path("fact").asText("").ifBlank { null })
                if (источник == ИЗ_РАЗБОРА) return@forEach
                val новый = (д.deepCopy() as ObjectNode).put("source", источник)
                store.update(кандидат.id, новый, Provenance(Channel.SERVICE, "служба: источник кандидата словаря 26.09"))
                исправлено += 1
            }
        return "источник кандидатов словаря: исправлено $исправлено (привязка сторон и узлов проекта — не разбор документа)"
    }
}
