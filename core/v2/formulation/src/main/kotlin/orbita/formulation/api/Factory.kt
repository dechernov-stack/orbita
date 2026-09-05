// Фабрика постановки: наружу отдаётся порт, реализация невидима.
package orbita.formulation.api

import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.formulation.internal.LinkCoverage

object FormulationFactory {
    fun formulation(store: EntityStore, links: LinkRegistry): Formulation = LinkCoverage(store, links)
}
