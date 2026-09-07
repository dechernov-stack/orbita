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
    /**
     * @param baselineRoot каталог репозиториев базирования; по умолчанию —
     *   том стенда. Отдельно от репозитория изделия: документы проектов —
     *   содержание владельца, и в публичный репозиторий они не попадают.
     */
    fun documents(
        store: EntityStore,
        links: LinkRegistry,
        template: (String) -> JsonNode?,
        mapper: ObjectMapper = ObjectMapper(),
        baselineRoot: java.io.File =
            java.io.File(System.getenv("ORBITA_BASELINES_DIR") ?: "/files/basirovaniya"),
        /** Живая модель: промпт → (текст, имя модели). Пусто — связного текста нет. */
        writer: ((project: String, prompt: String) -> Pair<String, String>)? = null,
    ): Documents = EntityDocuments(store, links, template, mapper, baselineRoot, writer)
}
