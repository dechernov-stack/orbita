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

    override fun derive(
        project: String,
        parent: String,
        statement: String,
        rationale: String,
        author: String,
        code: String?,
        carrier: String?,
        subtype: String,
        category: String?,
        verificationMethod: String?,
    ): RequirementView {
        val область = Area.Project(project)
        val родитель = store.byCode(область, parent) ?: error("проектного требования «$parent» нет в проекте")
        require(родитель.kind == "requirement") { "«$parent» — это ${родитель.kind}, а не требование" }
        require(statement.isNotBlank()) { "формулировка системного требования пуста" }
        require(rationale.isNotBlank()) { "деривация без основания не принимается: скажите, почему из «$parent» следует это требование" }
        require(subtype in ВИДЫ_УТОЧНЕНИЯ) { "вид уточнения из: ${ВИДЫ_УТОЧНЕНИЯ.joinToString(" · ")}" }
        val носитель = carrier?.takeIf { it.isNotBlank() }?.let { к ->
            store.byCode(область, к) ?: error("носителя «$к» нет в проекте")
        }
        val документ = mapper.createObjectNode()
            .put("level", "system")
            .put("title", statement.take(60))
            .put("statement", statement)
            .put("rationale", rationale)
            .put("category", category ?: родитель.doc.path("category").asText("functional"))
        (verificationMethod ?: родитель.doc.path("verification_method").asText("").ifBlank { null })?.let { документ.put("verification_method", it) }
        носитель?.let { документ.put("carrier", it.id) }
        документ.putArray("source").addObject().put("kind", "requirement").put("ref", родитель.id).put("anchor", родитель.code)
        val кодЗаписи = code?.takeIf { it.isNotBlank() } ?: следующий(область, "RQ-S")
        val создано = store.create(
            кодЗаписи, "requirement", область, "A4", документ,
            Provenance(Channel.MANUAL, author, source = родитель.code),
        )
        // Родитель — связью с видом уточнения и основанием: по ней считается
        // «каждое системное выведено», по ней же ReqIF/StrictDoc несут Derives.
        links.link("derives_from", создано.id, родитель.id, Provenance(Channel.MANUAL, author), rationale = rationale, subtype = subtype)
        return вид(создано, Snapshots.последний(store, project))
    }

    private fun следующий(область: Area, префикс: String): String {
        val занято = store.list(область, "requirement").mapNotNull { т ->
            Regex("^${Regex.escape(префикс)}-(\\d+)$").find(т.code)?.groupValues?.get(1)?.toIntOrNull()
        }
        return "%s-%04d".format(префикс, (занято.maxOrNull() ?: 0) + 1)
    }
}

/** Общее для требований и базирования: где лежит последний снимок. */
private val ВИДЫ_УТОЧНЕНИЯ = setOf("decomposition", "derivation", "refinement")


internal object Snapshots {

    /**
     * Порядок снимков: сначала отметка базы, при равенстве — своя отметка
     * из документа. База ставит `now()` на транзакцию, и два снимка,
     * сделанные подряд, могут получить одно время — тогда «последний» стал бы
     * делом случая.
     */
    private val порядок = compareBy<Entity>({ it.createdAt }, { it.doc.path("at").asText("") })

    fun последний(store: EntityStore, project: String): Entity? =
        store.list(Area.Project(project), "baseline").maxWithOrNull(порядок)

    fun поПорядку(store: EntityStore, project: String): List<Entity> =
        store.list(Area.Project(project), "baseline").sortedWith(порядок)

    fun версии(снимок: Entity?): Map<String, Int> =
        снимок?.doc?.path("items")?.associate { it.path("ref").asText() to it.path("version").asInt() }
            ?: emptyMap()
}
