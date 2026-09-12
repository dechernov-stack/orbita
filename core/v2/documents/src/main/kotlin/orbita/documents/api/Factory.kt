// Фабрика документов: наружу отдаётся порт, реализация невидима.
package orbita.documents.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.documents.internal.DocumentChecks
import orbita.documents.internal.EntityDocuments
import orbita.documents.internal.FieldVerification
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

    /** Условия ворот про документы: начат · полон к ступени · базирован (только чтение). */
    fun gateChecks(documents: Documents): orbita.readiness.api.ExtraChecks = DocumentChecks(documents)

    /**
     * Верификация документа против поля знаний.
     *
     * @param documents тот же порт документов, что отдан наружу: проверка
     *   читает документ его же глазами. Второй сборки документа не
     *   существует — иначе отчёт однажды сослался бы на элемент, которого на
     *   экране нет
     * @param links реестр связей: `derived_from_fact` — нить от сущности к её
     *   факту, `contradicts` — спор двух фактов. Без реестра проверка не
     *   отличит сущность без основания от сущности, чьё основание не видно
     */
    fun verification(
        store: EntityStore,
        links: LinkRegistry,
        documents: Documents,
        mapper: ObjectMapper = ObjectMapper(),
    ): Verification = FieldVerification(store, links, documents, mapper)
}
