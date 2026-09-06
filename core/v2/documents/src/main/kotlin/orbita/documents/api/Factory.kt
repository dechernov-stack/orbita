// Фабрика документов: наружу отдаётся порт, реализация невидима.
package orbita.documents.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.documents.internal.EntityDocuments
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry

object DocumentsFactory {
    /**
     * @param template шаблон документа С ПОЛКИ по коду: содержания в коде нет,
     *   и второй копии структуры документа не существует
     */
    fun documents(
        store: EntityStore,
        links: LinkRegistry,
        template: (String) -> JsonNode?,
        mapper: ObjectMapper = ObjectMapper(),
    ): Documents = EntityDocuments(store, links, template, mapper)
}
