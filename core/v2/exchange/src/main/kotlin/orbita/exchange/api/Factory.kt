// Фабрика обмена: наружу отдаётся порт, реализация невидима.
package orbita.exchange.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.exchange.internal.EntityExchange
import orbita.exchange.internal.HttpReqifParser
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
        /** Служба обмена для чужих файлов; null — импорт отказывает словами (ORBITA_EXCHANGE_URL). */
        reqif: ReqifParser? = parserFromEnv(mapper),
    ): Exchange = EntityExchange(store, links, intake, mapper, strictDoc, baselineRoot, reqif)

    fun parserFromEnv(mapper: ObjectMapper = ObjectMapper()): ReqifParser? =
        System.getenv("ORBITA_EXCHANGE_URL")?.takeIf { it.isNotBlank() }?.let { HttpReqifParser(it, mapper) }

    /** Служба по переменной окружения; пусто — канала нет. */
    fun fromEnv(mapper: ObjectMapper = ObjectMapper()): StrictDocService? =
        System.getenv("ORBITA_STRICTDOC_URL")?.takeIf { it.isNotBlank() }?.let { HttpStrictDoc(it, mapper) }
}
