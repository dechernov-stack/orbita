// Фабрика знаний: наружу отдаётся порт, реализация невидима.
package orbita.knowledge.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.kernel.api.EntityStore
import orbita.knowledge.internal.EntityIntake

object KnowledgeFactory {
    fun intake(store: EntityStore, mapper: ObjectMapper = ObjectMapper()): Intake =
        EntityIntake(store, mapper)
}
