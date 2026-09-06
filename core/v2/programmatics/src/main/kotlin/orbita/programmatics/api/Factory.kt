// Фабрика программатики: наружу отдаётся порт, реализация невидима.
package orbita.programmatics.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.kernel.api.EntityStore
import orbita.programmatics.internal.EntityProgrammatics
import orbita.programmatics.internal.ProgrammaticsChecks
import orbita.readiness.api.ExtraChecks

object ProgrammaticsFactory {
    fun programmatics(store: EntityStore, mapper: ObjectMapper = ObjectMapper()): Programmatics =
        EntityProgrammatics(store, mapper)

    /** Условия ворот сцен 10–12: технологии, риски, стоимость. */
    fun gateChecks(store: EntityStore, programmatics: Programmatics): ExtraChecks =
        ProgrammaticsChecks(store, programmatics)
}
