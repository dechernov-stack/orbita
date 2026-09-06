// Фабрика требований: наружу отдаются порты, реализация невидима.
package orbita.requirements.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.readiness.api.ExtraChecks
import orbita.requirements.internal.EntityBaselines
import orbita.requirements.internal.EntityRequirements
import orbita.requirements.internal.RequirementChecks

object RequirementsFactory {
    fun requirements(
        store: EntityStore,
        links: LinkRegistry,
        mapper: ObjectMapper = ObjectMapper(),
    ): Requirements = EntityRequirements(store, links, mapper)

    fun baselines(
        store: EntityStore,
        links: LinkRegistry,
        mapper: ObjectMapper = ObjectMapper(),
    ): Baselines = EntityBaselines(store, links, mapper)

    /** Условия ворот сцены 8: правило носителя знает тот, кто ведёт требования. */
    fun gateChecks(store: EntityStore, baselines: Baselines): ExtraChecks =
        RequirementChecks(store, baselines)
}
