// Фабрика моделей: наружу отдаются порты, реализация невидима.
package orbita.models.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.models.internal.EntityImpact
import orbita.models.internal.EntityModels
import orbita.models.internal.EntityVariants

object ModelsFactory {
    fun models(store: EntityStore, mapper: ObjectMapper = ObjectMapper()): Models =
        EntityModels(store, mapper)

    fun variants(store: EntityStore): Variants = EntityVariants(store)

    /** «Что заденет правка» — граф по связям и ссылкам полей. */
    fun impact(store: EntityStore, links: LinkRegistry): Impact = EntityImpact(store, links)
}
