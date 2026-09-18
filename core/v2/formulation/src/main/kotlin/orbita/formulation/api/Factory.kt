// Фабрика постановки: наружу отдаётся порт, реализация невидима.
package orbita.formulation.api

import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.formulation.internal.LinkCoverage

object FormulationFactory {
    fun formulation(
        store: EntityStore,
        links: LinkRegistry,
        /** Чем закрывается нужда (истина `need.coverage_expected`); по умолчанию — сервис. */
        needCoverage: (orbita.kernel.api.Entity) -> String? = { NeedCoverage.SERVICE },
    ): Formulation = LinkCoverage(store, links, needCoverage)
}
