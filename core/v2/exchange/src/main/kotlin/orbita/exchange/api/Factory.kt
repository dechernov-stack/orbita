// Фабрика обмена: наружу отдаётся порт, реализация невидима.
package orbita.exchange.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.exchange.internal.EntityExchange
import orbita.exchange.internal.HttpStrictDoc
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.knowledge.api.Intake

object ExchangeFactory {
    /**
     * @param strictDoc служба StrictDoc; null — канал не настроен, экспорт
     *   отказывает словами (ORBITA_STRICTDOC_URL)
     * @param baselineRoot каталог снимков .sdoc; по умолчанию — том стенда
     */
    fun exchange(
        store: EntityStore,
        links: LinkRegistry,
        intake: Intake,
        mapper: ObjectMapper = ObjectMapper(),
        strictDoc: StrictDocService? = fromEnv(mapper),
        baselineRoot: java.io.File =
            java.io.File(System.getenv("ORBITA_FILES_DIR") ?: "/files").resolve("sdoc-baselines"),
    ): Exchange = EntityExchange(store, links, intake, mapper, strictDoc, baselineRoot)

    /** Служба по переменной окружения; пусто — канала нет. */
    fun fromEnv(mapper: ObjectMapper = ObjectMapper()): StrictDocService? =
        System.getenv("ORBITA_STRICTDOC_URL")?.takeIf { it.isNotBlank() }?.let { HttpStrictDoc(it, mapper) }
}
