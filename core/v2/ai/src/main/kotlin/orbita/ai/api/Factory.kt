// Фабрика живого контура: наружу отдаются порты, реализация невидима.
package orbita.ai.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.ai.internal.Atomizer
import orbita.ai.internal.HttpTransport
import orbita.ai.internal.JournalService
import orbita.ai.internal.RetryingTransport
import orbita.kernel.api.EntityStore
import orbita.knowledge.api.FactIntake
import orbita.knowledge.api.Intake

/** Разбор материала живым вызовом: канон → факты с якорями. */
fun interface Atomize {
    fun atomize(project: String, material: String, intent: String, author: String): FactIntake
}

object AiFactory {

    /** Транспорт по умолчанию: прямой канал с тремя попытками при перегрузке. */
    fun transport(mapper: ObjectMapper = ObjectMapper()): Transport =
        RetryingTransport(HttpTransport(mapper))

    fun service(
        store: EntityStore,
        transport: Transport = transport(),
        mapper: ObjectMapper = ObjectMapper(),
    ): AiService = JournalService(store, transport, mapper)

    fun atomize(
        store: EntityStore,
        intake: Intake,
        service: AiService,
        mapper: ObjectMapper = ObjectMapper(),
    ): Atomize {
        val работник = Atomizer(store, intake, service, mapper)
        return Atomize { project, material, intent, author ->
            работник.atomize(project, material, intent, author)
        }
    }
}
