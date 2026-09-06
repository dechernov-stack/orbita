// Требования как сущности ядра: чтение, линт, экземпляр типового.
package orbita.requirements.internal

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.kernel.api.Provenance
import orbita.requirements.api.Applicability
import orbita.requirements.api.Ears
import orbita.requirements.api.Level
import orbita.requirements.api.LintNote
import orbita.requirements.api.RequirementView
import orbita.requirements.api.Requirements

class EntityRequirements(
    private val store: EntityStore,
    private val links: LinkRegistry,
    private val mapper: ObjectMapper = ObjectMapper(),
) : Requirements {

    override fun lint(statement: String, ears: Ears): List<LintNote> = EarsLint.check(statement, ears)

    override fun list(project: String): List<RequirementView> {
        val снимок = Snapshots.последний(store, project)
        return store.list(Area.Project(project), "requirement").map { вид(it, снимок) }
    }

    override fun byCode(project: String, code: String): RequirementView? =
        store.byCode(Area.Project(project), code)
            ?.takeIf { it.kind == "requirement" }
            ?.let { вид(it, Snapshots.последний(store, project)) }

    /** Собирает карточку требования; носитель разворачивается в код и вид. */
    private fun вид(требование: Entity, снимок: Entity?): RequirementView {
        val шаблон = Ears.of(требование.doc.path("ears_pattern").asText(null))
        val формулировка = требование.doc.path("statement").asText("")
        val носитель = требование.doc.path("carrier").asText("").ifBlank { null }?.let { store.byId(it) }
        val былаВерсия = снимок?.doc?.path("items")?.firstOrNull { it.path("ref").asText() == требование.id }
            ?.path("version")?.asInt()
        return RequirementView(
            id = требование.id,
            code = требование.code,
            level = Level.of(требование.doc.path("level").asText(null)),
            title = требование.doc.path("title").asText(требование.code),
            statement = формулировка,
            category = требование.doc.path("category").asText(""),
            ears = шаблон,
            carrier = носитель?.code,
            carrierKind = носитель?.kind,
            measure = требование.doc.path("measure").takeIf { !it.isMissingNode && !it.isNull }?.toString(),
            verificationMethod = требование.doc.path("verification_method").asText("").ifBlank { null },
            sources = требование.doc.path("source").map { it.path("ref").asText() },
            templateRef = требование.doc.path("template_ref").asText("").ifBlank { null },
            applicability = требование.doc.path("applicability").asText("").ifBlank { null }
                ?.let { runCatching { Applicability.valueOf(it.uppercase()) }.getOrNull() },
            status = требование.status,
            version = требование.version,
            afterBaselineChanged = былаВерсия != null && требование.version > былаВерсия,
            notes = EarsLint.check(формулировка, шаблон),
        )
    }

    override fun instantiate(
        project: String,
        typicalCode: String,
        code: String,
        carrier: String,
        applicability: Applicability,
        author: String,
        statement: String?,
        deviationRationale: String?,
    ): RequirementView {
        val типовое = store.byCode(Area.Library, typicalCode)
            ?: error("типового требования «$typicalCode» нет на полке")
        require(типовое.kind == "typical_requirement") {
            "«$typicalCode» — это ${типовое.kind}, а не типовое требование"
        }

        // Отклонение без обоснования — отказ (инвариант 4): иначе через год
        // никто не вспомнит, почему норму не применили.
        require(applicability == Applicability.APPLIED || !deviationRationale.isNullOrBlank()) {
            "применимость «${applicability.name.lowercase()}» требует обоснования: " +
                "отклонение от типового без причины неотличимо от забывчивости"
        }

        val носитель = store.byCode(Area.Project(project), carrier)
            ?: error("носителя «$carrier» нет в проекте: без носителя — не требование")

        val документ = типовое.doc.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        документ.remove(listOf("applicability_class", "placeholders"))
        документ.put("title", типовое.doc.path("title").asText(типовое.code))
        документ.put("statement", statement ?: типовое.doc.path("statement").asText(""))
        документ.put("carrier", носитель.id)
        документ.put("template_ref", типовое.id)
        документ.put("applicability", applicability.name.lowercase())
        deviationRationale?.let { документ.put("deviation_rationale", it) }
        if (документ.path("source").isMissingNode) {
            документ.putArray("source").addObject()
                .put("kind", "requirement").put("ref", типовое.id).put("anchor", типовое.code)
        }

        val создано = store.create(
            code, "requirement", Area.Project(project), "8", документ,
            Provenance(Channel.SHELF, author, source = типовое.code),
        )
        // Экземпляр помнит шаблон связью, а не только полем: по ней полка
        // покажет, кого задела новая редакция типового.
        links.link(
            "instantiates", создано.id, типовое.id,
            Provenance(Channel.SHELF, author),
            rationale = "экземпляр типового «${типовое.code}»: ${applicability.name.lowercase()}",
        )
        return вид(создано, Snapshots.последний(store, project))
    }
}

/** Общее для требований и базирования: где лежит последний снимок. */
internal object Snapshots {
    fun последний(store: EntityStore, project: String): Entity? =
        store.list(Area.Project(project), "baseline").maxByOrNull { it.createdAt }

    fun версии(снимок: Entity?): Map<String, Int> =
        снимок?.doc?.path("items")?.associate { it.path("ref").asText() to it.path("version").asInt() }
            ?: emptyMap()
}
