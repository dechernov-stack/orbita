// Фабрика знаний: наружу отдаётся порт, реализация невидима.
package orbita.knowledge.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.knowledge.internal.EntityIntake

object KnowledgeFactory {
    /**
     * @param links реестр связей: без него сущность не свяжется со своим
     *   фактом, и доля знаний в проекте не посчитается
     */
    fun intake(
        store: EntityStore,
        links: LinkRegistry? = null,
        mapper: ObjectMapper = ObjectMapper(),
    ): Intake = EntityIntake(store, links, mapper)
}
