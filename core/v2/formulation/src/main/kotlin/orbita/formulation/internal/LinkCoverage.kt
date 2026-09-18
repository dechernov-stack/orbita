// Расчёт покрытия по связям ядра. Ни одного собственного хранилища: связи
// уже есть в реестре, и второй копии правды заводить незачем.
package orbita.formulation.internal

import orbita.kernel.api.Area
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.formulation.api.CoverageMatrix
import orbita.formulation.api.Formulation
import orbita.formulation.api.NeedCoverage

class LinkCoverage(
    private val store: EntityStore,
    private val links: LinkRegistry,
    /**
     * Чем закрывается нужда (`need.coverage_expected`): сервис ждёт не всякая.
     * Правило живёт в истине онтологии — она слоем выше, — поэтому приходит
     * функцией; по умолчанию сервис, как было до 17.09.
     */
    private val видПокрытия: (Entity) -> String? = { NeedCoverage.SERVICE },
) : Formulation {

    override fun coverage(project: String): CoverageMatrix {
        val область = Area.Project(project)
        val стороны = живые(область, "stakeholder").associateBy { it.id }
        val цели = живые(область, "goal").associateBy { it.id }
        val сервисы = живые(область, "service").associateBy { it.id }
        val живыеНужды = живые(область, "need")

        val нужды = живыеНужды.map { нужда ->
            // Носитель — ЖИВАЯ сторона: после «Отменить пакет» связь `owns`
            // может вести на снятую, и нужда выглядела ничьей (216, 17.09).
            val носитель = links.to(нужда.id, "owns").mapNotNull { стороны[it.from] }.firstOrNull()
            val покрытия = links.to(нужда.id, "covers").map { it.from }
            NeedCoverage(
                id = нужда.id,
                code = нужда.code,
                statement = нужда.doc.path("statement").asText(нужда.code),
                ownerCode = носитель?.code,
                ownerName = носитель?.doc?.path("name")?.asText(носитель.code),
                goals = покрытия.mapNotNull { цели[it] }.map { it.doc.path("statement").asText(it.code) },
                services = покрытия.mapNotNull { сервисы[it] }.map { it.doc.path("name").asText(it.code) },
                expected = видПокрытия(нужда),
            )
        }

        val сНуждами = живыеНужды.flatMap { links.to(it.id, "owns").map { связь -> связь.from } }.toSet()
        val безНужд = стороны.values
            .filter { it.id !in сНуждами }
            .map { it.doc.path("name").asText(it.code) }

        return CoverageMatrix(нужды, безНужд)
    }

    /**
     * Снятое с учёта в матрице не живёт: «Отменить пакет» ставит cancelled, а
     * матрица считала такие записи — «покрыто 29 из 143» при тридцати трёх
     * живых нуждах (проход владельца, 17.09).
     */
    private fun живые(область: Area, вид: String): List<Entity> =
        store.list(область, вид).filter { it.status != "cancelled" }
}
