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

    /** Внешняя модель (ADR-048): адаптер Capella по ORBITA_CAPELLA_URL либо учебная fixture с баннером. */
    fun externalModel(store: EntityStore, mapper: ObjectMapper = ObjectMapper(), adapterUrl: String? = System.getenv("ORBITA_CAPELLA_URL")?.takeIf { it.isNotBlank() }): ExternalModel =
        orbita.architecture.internal.CapellaExternalModel(store, mapper, adapterUrl)

    /** Условия ворот сцены 7: состав, развёртывание, базовая концепция. */
    fun gateChecks(store: EntityStore, architecture: Architecture): ExtraChecks =
        ArchitectureChecks(store, architecture)
}
