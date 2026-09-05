// Расчёт покрытия по связям ядра. Ни одного собственного хранилища: связи
// уже есть в реестре, и второй копии правды заводить незачем.
package orbita.formulation.internal

import orbita.kernel.api.Area
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.formulation.api.CoverageMatrix
import orbita.formulation.api.Formulation
import orbita.formulation.api.NeedCoverage

class LinkCoverage(
    private val store: EntityStore,
    private val links: LinkRegistry,
) : Formulation {

    override fun coverage(project: String): CoverageMatrix {
        val область = Area.Project(project)
        val стороны = store.list(область, "stakeholder").associateBy { it.id }
        val цели = store.list(область, "goal").associateBy { it.id }
        val сервисы = store.list(область, "service").associateBy { it.id }

        val нужды = store.list(область, "need").map { нужда ->
            val носитель = links.to(нужда.id, "owns").firstOrNull()?.from?.let { стороны[it] }
            val покрытия = links.to(нужда.id, "covers").map { it.from }
            NeedCoverage(
                id = нужда.id,
                code = нужда.code,
                statement = нужда.doc.path("statement").asText(нужда.code),
                ownerCode = носитель?.code,
                ownerName = носитель?.doc?.path("name")?.asText(носитель.code),
                goals = покрытия.mapNotNull { цели[it] }.map { it.doc.path("statement").asText(it.code) },
                services = покрытия.mapNotNull { сервисы[it] }.map { it.doc.path("name").asText(it.code) },
            )
        }

        val сНуждами = links.let { реестр ->
            store.list(область, "need").mapNotNull { реестр.to(it.id, "owns").firstOrNull()?.from }.toSet()
        }
        val безНужд = стороны.values
            .filter { it.id !in сНуждами }
            .map { it.doc.path("name").asText(it.code) }

        return CoverageMatrix(нужды, безНужд)
    }
}
