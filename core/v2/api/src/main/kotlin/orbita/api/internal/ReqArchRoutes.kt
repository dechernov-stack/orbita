// Композитор волны 3: требования и архитектура за одним входом.
//
// Роутер знает про волну одним делегатом, а внутри волны маршруты живут
// двумя тонкими файлами по своим предметам.
package orbita.api.internal

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.architecture.api.Architecture
import orbita.kernel.api.EntityStore
import orbita.requirements.api.Baselines
import orbita.requirements.api.Requirements

class ReqArchRoutes(
    store: EntityStore,
    requirements: Requirements,
    baselines: Baselines,
    architecture: Architecture,
    mapper: ObjectMapper = ObjectMapper(),
) {

    private val требования = ReqRoutes(store, requirements, baselines, mapper)
    private val архитектура = ArchRoutes(store, architecture, mapper)

    fun handle(method: String, path: String, query: Map<String, String>, body: String?): V2Router.Ответ? =
        требования.handle(method, path, query, body)
            ?: архитектура.handle(method, path, query, body)
}
