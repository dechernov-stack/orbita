// Готовые строки экранов клиента (STEP-6 §3).
//
// В КЛИЕНТЕ НЕТ РАСЧЁТОВ (ловушка 5): свёртка, глубина в дереве, критерий
// успеха, состояние верификации и подпись единицы вычисляются здесь, на
// сервере, и уходят наружу готовыми. Пересчёт любого из этого на стороне
// интерфейса создал бы вторую реализацию правила — и она разошлась бы
// с серверной ровно так же, как разошлись бы «облегчённые» проверки
// предложений ИИ на шаге 5.
package orbita.out

import orbita.mod.model.Lifecycle
import orbita.req.ReqService
import orbita.req.UnitLabels
import orbita.req.renderConstraint
import orbita.req.successCriterion
import orbita.req.verificationPlanIssues
import orbita.req.verificationState

/** Условие требования как СТРУКТУРНОЕ значение: оператор, число, код и подпись единицы. */
data class ConditionView(
    val name: String?,
    val operator: String?,
    val value: Double?,
    val valueMax: Double?,
    val tolerance: Double?,
    /** Код СИ — то, что хранится в модели. */
    val unit: String?,
    /** Подпись для отображения; подстановка выполнена здесь, в модели код не менялся. */
    val unitLabel: String?,
    /** Готовая строка условия, напр. «≤ 60 кг». */
    val rendered: String?,
)

/** Строка дерева требований экрана 3. */
data class RequirementRow(
    val id: String,
    val depth: Int,
    val hasChildren: Boolean,
    val statement: String,
    val category: String?,
    val status: String,
    val condition: ConditionView?,
    /** Свёртка бюджета; заполнена только у требований с распределёнными потомками. */
    val budget: BudgetBar?,
    val budgetOverrun: Boolean,
    val verificationState: String,
    val method: String?,
    val approach: String?,
    val planIssues: List<String>,
)

data class RequirementTreeView(
    val roots: List<String>,
    val children: Map<String, List<String>>,
    val rows: List<RequirementRow>,
)

/** Событие верификации карточки требования (экран 3б). */
data class EventView(
    val id: String,
    val method: String?,
    val kind: String?,
    val phase: String?,
    val level: String?,
    val closes: Boolean,
    val status: String?,
    val approach: String?,
    val means: String?,
    val evidenceRef: String?,
    val evidenceStale: Boolean,
    val issues: List<String>,
)

data class RequirementCard(
    val row: RequirementRow,
    val successCriterion: String?,
    val sources: List<String>,
    val allocatedTo: List<String>,
    val events: List<EventView>,
)

/** Строка спецификации элемента (экран 11). */
data class SpecificationRow(
    val id: String,
    val statement: String,
    val condition: ConditionView?,
    /** Родительское требование и вид декомпозиции: allocated либо derived. */
    val source: String?,
    val derivationKind: String?,
    val verificationState: String,
    val eventsDone: Int,
    val eventsTotal: Int,
    val status: String,
)

data class ComponentSpecification(
    val componentId: String,
    val rows: List<SpecificationRow>,
    /** Бюджеты элемента: по одному на свёртку, сегментированные вкладом потомков. */
    val budgets: Map<String, BudgetBar>,
)

class ScreenViews(
    private val req: ReqService,
    private val unitLabels: UnitLabels = UnitLabels(),
) {

    fun requirementTree(): RequirementTreeView {
        val requirements = currentRequirements()
        val ids = requirements.map { it.id }
        val links = ids.flatMap { req.links.linksFrom(it, "derive") }
        val tree = buildTree(ids, links)
        val rows = requirements.map { r -> row(r.id, tree) }.sortedBy { it.id }
        return RequirementTreeView(tree.roots, tree.children, rows)
    }

    fun card(requirementId: String): RequirementCard {
        val stored = req.objects.current(requirementId)
            ?: throw NoSuchElementException("нет требования $requirementId")
        val tree = requirementTree()
        val doc = stored.doc
        val events = doc.path("verification_events").map { e ->
            val ref = e.path("evidence_ref").asText("").ifBlank { null }
            EventView(
                id = e.path("id").asText(""),
                method = e.path("method").asText("").ifBlank { null },
                kind = e.path("kind").asText("").ifBlank { null },
                phase = e.path("phase").asText("").ifBlank { null },
                level = e.path("level").asText("").ifBlank { null },
                closes = e.path("closes").asBoolean(false),
                status = e.path("status").asText("").ifBlank { null },
                approach = e.path("approach").asText("").ifBlank { null },
                means = e.path("means").asText("").ifBlank { null },
                evidenceRef = ref,
                evidenceStale = ref?.let { req.objects.current(it)?.doc?.path("stale")?.asBoolean(false) } ?: false,
                issues = orbita.req.eventIssues(e),
            )
        }
        return RequirementCard(
            row = tree.rows.first { it.id == requirementId },
            successCriterion = successCriterion(doc, unitLabels.asFunction()),
            sources = doc.path("traces_up").map { it.path("ref").asText() },
            allocatedTo = doc.path("allocated_to").mapNotNull {
                it.path("component").asText("").ifBlank { null }
                    ?: it.path("interface").asText("").ifBlank { null }
            },
            events = events,
        )
    }

    fun componentSpecification(componentId: String): ComponentSpecification {
        val ids = req.specificationOf(componentId)
        val rows = ids.mapNotNull { id ->
            val stored = req.objects.current(id) ?: return@mapNotNull null
            val doc = stored.doc
            val parentLink = req.links.linksTo(id, "derive").firstOrNull()
            val events = doc.path("verification_events")
            SpecificationRow(
                id = id,
                statement = doc.path("statement").asText(""),
                condition = condition(doc),
                source = parentLink?.fromId,
                derivationKind = parentLink?.derivationKind,
                verificationState = verificationState(doc).label,
                eventsDone = events.count { it.path("status").asText() == "passed" },
                eventsTotal = events.size(),
                status = stored.status.name,
            )
        }
        // Бюджеты элемента: свёртка каждого требования, у которого она применима
        val budgets = ids.mapNotNull { id ->
            val bar = budgetBarFor(id) ?: return@mapNotNull null
            val name = req.objects.current(id)?.doc?.path("mop")?.path("name")?.asText("") ?: id
            (name.ifBlank { id }) to bar
        }.toMap()
        return ComponentSpecification(componentId, rows, budgets)
    }

    private fun row(id: String, tree: RequirementTree): RequirementRow {
        val stored = req.objects.current(id)!!
        val doc = stored.doc
        val closing = doc.path("verification_events").firstOrNull { it.path("closes").asBoolean(false) }
        val event = closing ?: doc.path("verification_events").firstOrNull()
        val bar = budgetBarFor(id)
        return RequirementRow(
            id = id,
            depth = tree.depthOf(id),
            hasChildren = tree.children[id]?.isNotEmpty() == true,
            statement = doc.path("statement").asText(""),
            category = doc.path("category").asText("").ifBlank { null },
            status = stored.status.name,
            condition = condition(doc),
            budget = bar,
            budgetOverrun = bar?.overrun == true,
            verificationState = verificationState(doc).label,
            method = event?.path("method")?.asText("")?.ifBlank { null },
            approach = event?.path("approach")?.asText("")?.ifBlank { null },
            planIssues = verificationPlanIssues(doc),
        )
    }

    /** Свёртка бюджета готовой полосой: клиент её только рисует. */
    private fun budgetBarFor(id: String): BudgetBar? {
        val rollup = req.rollupFor(id)
        if (!rollup.applicable || rollup.error != null || rollup.limit == null) return null
        val children = orbita.req.rollupChildIds(id, req.links.linksFrom(id, "derive")).mapNotNull { childId ->
            val childDoc = req.objects.current(childId)?.doc ?: return@mapNotNull null
            val value = childDoc.path("mop").path("value").let { v ->
                if (v.isNumber) v.asDouble() else v.path("value").takeIf { it.isNumber }?.asDouble()
            } ?: return@mapNotNull null
            BudgetSegment(childId, value)
        }
        if (children.isEmpty()) return null
        return budgetSegments(rollup.limit!!, children)
    }

    private fun condition(doc: com.fasterxml.jackson.databind.JsonNode): ConditionView? {
        val mop = doc.path("mop")
        if (mop.isMissingNode || mop.isEmpty) return null
        val value = mop.path("value")
        val unit = (if (value.isObject) value.path("unit").asText("") else "").ifBlank { null }
        return ConditionView(
            name = mop.path("name").asText("").ifBlank { null },
            operator = mop.path("operator").asText("").ifBlank { null },
            value = if (value.isNumber) value.asDouble() else value.path("value").takeIf { it.isNumber }?.asDouble(),
            valueMax = mop.path("value_max").takeIf { it.isNumber }?.asDouble(),
            tolerance = mop.path("tolerance").takeIf { it.isNumber }?.asDouble(),
            unit = unit,
            unitLabel = unit?.let { unitLabels.asFunction()(it) },
            rendered = renderConstraint(mop, unitLabels.asFunction()),
        )
    }

    private fun currentRequirements() =
        req.objects.listCurrent().filter { it.type == "requirement" && it.status != Lifecycle.Cancelled }
}
