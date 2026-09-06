// Фабрика архитектуры: наружу отдаётся порт, реализация невидима.
package orbita.architecture.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.architecture.internal.ArchitectureChecks
import orbita.architecture.internal.EntityArchitecture
import orbita.readiness.api.ExtraChecks
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry

object ArchitectureFactory {
    fun architecture(
        store: EntityStore,
        links: LinkRegistry,
        mapper: ObjectMapper = ObjectMapper(),
    ): Architecture = EntityArchitecture(store, links, mapper)

    /** Условия ворот сцены 7: состав, развёртывание, базовая концепция. */
    fun gateChecks(store: EntityStore, architecture: Architecture): ExtraChecks =
        ArchitectureChecks(store, architecture)
}
