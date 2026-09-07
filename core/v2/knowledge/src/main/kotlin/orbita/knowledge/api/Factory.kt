// Фабрика знаний: наружу отдаётся порт, реализация невидима.
package orbita.knowledge.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.knowledge.internal.EntityIntake
import orbita.library.api.Shelves

object KnowledgeFactory {
    /**
     * @param links реестр связей: без него сущность не свяжется со своим
     *   фактом, и доля знаний в проекте не посчитается
     * @param shelves полка: по ней принятый норматив узнаёт свою карточку,
     *   вместо того чтобы заводить второй такой же акт под своим кодом
     */
    fun intake(
        store: EntityStore,
        links: LinkRegistry? = null,
        mapper: ObjectMapper = ObjectMapper(),
        shelves: Shelves? = null,
    ): Intake = EntityIntake(store, links, mapper, shelves)
}
