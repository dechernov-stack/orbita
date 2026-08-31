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
import orbita.req.VerificationState
import orbita.req.registerSummary
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
    /** project либо system: регламент делит спецификацию по уровню. */
    val level: String?,
    val status: String,
    val condition: ConditionView?,
    /** Свёртка бюджета; заполнена только у требований с распределёнными потомками. */
    val budget: BudgetBar?,
    val budgetOverrun: Boolean,
    val verificationState: String,
    val method: String?,
    val approach: String?,
    val planIssues: List<String>,
    /** Откуда следует (traces_up): нужды, сервисы, цели — «родители» смысла. */
    val sources: List<String>,
    /** На что распределено: элементы и интерфейсы (список после MCR, п. 6). */
    val allocatedTo: List<String>,
    /** Т-1: вид требования — до Г5 вычисляемый (numeric при mop, иначе text). */
    val kind: String,
    /** Обоснование — раскрытие строки рисует его без догрузки. */
    val rationale: String?,
    val version: String,
    val owner: String?,
    /** Происхождение из provenance.source: manual/imported/ai_proposed/computed. */
    val origin: String?,
    /** Родитель-требование (derive); null у корней. */
    val parentId: String?,
    /** Имя первого носителя — чтобы строка рисовалась без догрузок. */
    val carrierName: String?,
    /** Пометы (сервер считает по истории): показатель менялся после базирования. */
    val recalcAfterBaseline: Boolean,
    /** Документ правился после первого утверждения (Approved/Baseline). */
    val changedAfterApproval: Boolean,
    /** Разрывы СТРАТИФИЦИРОВАНЫ по уровням (РЕШЕНИЕ-НОСИТЕЛЬ-УРОВНИ):
     * настоящий сирота — системное требование без элемента/интерфейса. */
    val noCarrierGap: Boolean,
    /** Проектное требование без трассировки на нужду/источник — разрыв «без нужды». */
    val noNeedGap: Boolean,
    /**
     * Мягкие пометы формулировки (L-C1…L-C6, NASA SEH App. C): совет, а не
     * запрет — базирование они не держат. Считает сервер по словарю полки,
     * клиент показывает жёлтым с текстом подсказки.
     */
    val lint: List<orbita.req.LintNote> = emptyList(),
)

data class SystemRootRef(val id: String, val name: String?)

data class RequirementTreeView(
    val roots: List<String>,
    val children: Map<String, List<String>>,
    val rows: List<RequirementRow>,
    /** Нужды проекта без единого требования (прямо или через сервис) —
     * валидационная дыра «нужда не покрыта»; клик ведёт в «Постановку». */
    val needsUncovered: List<String>,
    /** Корень системы — определение ЕДИНСТВЕННОГО корневого вхождения;
     * носитель требований уровня проекта. Null — корня нет или их несколько. */
    val systemRoot: SystemRootRef?,
    /** Сколько корней у дерева состава: 0 — состава нет, >1 — корень не определён. */
    val compositionRoots: Int,
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

    /**
     * Линт формулировок словарём ПОЛКИ: список неопределённых слов растёт от
     * прогона к прогону, и инженер вносит найденное с экрана. Полки нет —
     * работает ресурс по умолчанию, поведение прежнее.
     *
     * Читается лениво и один раз на построение вида: словарь один на систему,
     * запрос на строку реестра был бы расточительством.
     */
    private val lintControl: orbita.req.QualityControl by lazy {
        val словарь = req.objects
            .listCurrent(orbita.mod.store.ObjectStore.LIBRARY_PROJECT)
            .firstOrNull { it.type == "quality_dictionary" && it.status.name != "Cancelled" }
        if (словарь == null) orbita.req.QualityControl()
        else orbita.req.QualityControl(orbita.req.QualityRules.fromShelf(словарь.doc))
    }

    fun requirementTree(projectId: String? = null): RequirementTreeView {
        val requirements = currentRequirements(projectId)
        val ids = requirements.map { it.id }
        val links = ids.flatMap { req.links.linksFrom(it, "derive") }
        val tree = buildTree(ids, links)
        val parents = tree.children.entries
            .flatMap { (parent, kids) -> kids.map { it to parent } }.toMap()
        // Пометы — по истории всех требований проекта ОДНИМ запросом: считать
        // по одному значило бы запрос на строку при каждой отрисовке реестра
        val marksById = req.objects.historyByType("requirement", projectId)
            .groupBy { it.id }.mapValues { rowMarks(it.value) }
        val carrierNames = mutableMapOf<String, String?>()
        val rows = requirements
            .map { r -> row(r, tree, parents[r.id], marksById[r.id], carrierNames) }
            .sortedBy { it.id }
        val usageRoots = compositionRoots(projectId)
        return RequirementTreeView(
            tree.roots, tree.children, rows,
            needsUncovered = uncoveredNeeds(projectId),
            systemRoot = usageRoots.singleOrNull()?.let { rootRef(it) },
            compositionRoots = usageRoots.size,
        )
    }

    /** Нужда покрыта, когда на её нити стоит требование: НД→требование или
     * НД→сервис→требование (trace хранится от источника к потребителю). */
    private fun uncoveredNeeds(projectId: String?): List<String> =
        req.objects.listCurrent(projectId)
            .filter { it.type == "need" && it.status != Lifecycle.Cancelled }
            .map { it.id }
            .filter { nd ->
                req.links.linksFrom(nd, "trace").none { first ->
                    first.toId.startsWith("RQ-") ||
                        req.links.linksFrom(first.toId, "trace").any { it.toId.startsWith("RQ-") }
                }
            }
            .sorted()

    /** Корневые вхождения дерева состава (без родителя). */
    private fun compositionRoots(projectId: String?) =
        req.objects.listCurrent(projectId)
            .filter { it.type == "component_usage" && it.status != Lifecycle.Cancelled }
            .filter { it.doc.path("parent_usage").asText("").isBlank() }

    private fun rootRef(root: orbita.mod.store.StoredObject): SystemRootRef? {
        val defId = root.doc.path("definition_ref").asText("")
        if (defId.isBlank()) return null
        return SystemRootRef(defId, req.objects.current(defId)?.doc?.path("name")?.asText("")?.ifBlank { null })
    }

    fun card(requirementId: String): RequirementCard {
        val stored = req.objects.current(requirementId)
            ?: throw NoSuchElementException("нет требования $requirementId")
        val tree = requirementTree(stored.projectId)
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

    /**
     * Экран 12: система в целом. Все сводки считаются здесь — клиент получает
     * готовые числа, включая критичность клеток матрицы рисков.
     */
    fun systemOverview(projectId: String? = null): SystemOverview {
        val requirements = currentRequirements()
        val components = req.objects.listCurrent(projectId)
            .filter { (it.type == "component" || it.type == "interface") && it.status != Lifecycle.Cancelled }
        val tree = requirementTree()

        val budgets = tree.rows.filter { it.budget != null }.associate { it.id to it.budget!! }
        val risks = req.risks()

        // Единый вход раздела «Проблемы» (шаг 16 §2.4): разрывы трассировки,
        // несогласованные распределения и цикл подчинённости добавлены СЮДА,
        // а их отчётные маршруты удалены — два источника одного перечня
        // разошлись бы молча.
        val problems = buildList {
            tree.rows.filter { it.verificationState == VerificationState.PlanIncomplete.label }
                .forEach { add("${it.id}: план верификации неполон — нет закрывающего события") }
            budgets.filterValues { it.overrun }
                .forEach { (id, bar) -> add("$id: бюджет превышен на ${bar.overrunValue}") }
            registerSummary(risks).escalate.forEach { add("$it: риск подлежит эскалации") }
            req.elementsWithoutRequirements().forEach { add("$it: на элемент не распределено ни одного требования") }
            req.links.traceBreaks(projectId).forEach { add("$it: требование без входящей нити трассировки") }
            req.inconsistentAllocations().forEach { (parent, child, why) ->
                add("$parent → $child: распределение несогласовано — $why")
            }
            if (treeCycle(tree.rows.flatMap { row -> req.links.linksFrom(row.id, "derive") })) {
                add("дерево требований содержит цикл подчинённости")
            }
        }

        return SystemOverview(
            requirements = requirements.size,
            components = components.size,
            verification = tree.rows.groupingBy { it.verificationState }.eachCount().toSortedMap(),
            budgets = budgets,
            budgetsOverrun = budgets.filterValues { it.overrun }.keys.sorted(),
            riskSummary = registerSummary(risks),
            riskMatrix = riskMatrix(
                risks.map {
                    it.path("id").asText() to (it.path("probability").asInt() to it.path("impact").asInt())
                },
            ),
            problems = problems,
        )
    }

    private fun row(
        stored: orbita.mod.store.StoredObject,
        tree: RequirementTree,
        parentId: String?,
        marks: RowMarks?,
        carrierNames: MutableMap<String, String?>,
    ): RequirementRow {
        val id = stored.id
        val doc = stored.doc
        val closing = doc.path("verification_events").firstOrNull { it.path("closes").asBoolean(false) }
        val event = closing ?: doc.path("verification_events").firstOrNull()
        val bar = budgetBarFor(id)
        val cond = condition(doc)
        val allocated = doc.path("allocated_to").mapNotNull {
            it.path("component").asText("").ifBlank { null }
                ?: it.path("interface").asText("").ifBlank { null }
        }
        val carrierName = allocated.firstOrNull()?.let { carrierId ->
            carrierNames.getOrPut(carrierId) {
                req.objects.current(carrierId)?.doc?.path("name")?.asText("")?.ifBlank { null }
            }
        }
        return RequirementRow(
            id = id,
            depth = tree.depthOf(id),
            hasChildren = tree.children[id]?.isNotEmpty() == true,
            statement = doc.path("statement").asText(""),
            category = doc.path("category").asText("").ifBlank { null },
            level = doc.path("level").asText("").ifBlank { null },
            status = stored.status.name,
            condition = cond,
            budget = bar,
            budgetOverrun = bar?.overrun == true,
            verificationState = verificationState(doc).label,
            method = event?.path("method")?.asText("")?.ifBlank { null },
            approach = event?.path("approach")?.asText("")?.ifBlank { null },
            planIssues = verificationPlanIssues(doc),
            sources = doc.path("traces_up").map { it.path("ref").asText() }.filter { it.isNotBlank() },
            allocatedTo = allocated,
            kind = if (cond != null) "numeric" else "text",
            rationale = doc.path("rationale").asText("").ifBlank { null },
            version = stored.version,
            owner = doc.path("owner").asText("").ifBlank { null },
            origin = doc.path("provenance").path("source").asText("").ifBlank { null },
            parentId = parentId,
            carrierName = carrierName,
            recalcAfterBaseline = marks?.recalcAfterBaseline == true,
            changedAfterApproval = marks?.changedAfterApproval == true,
            noCarrierGap = doc.path("level").asText("") != "project" && allocated.isEmpty(),
            noNeedGap = doc.path("level").asText("") == "project" &&
                doc.path("traces_up").none { it.path("ref").asText().isNotBlank() },
            lint = lintControl.lint(doc),
        )
    }

    /** Пометы Т-1 — семантика живёт здесь, клиент флаги только рисует. */
    private data class RowMarks(val recalcAfterBaseline: Boolean, val changedAfterApproval: Boolean)

    /** РЕШЕНИЯ-Т1 §1: пометы — о содержательности, не об арифметике версий.
     * Сравниваются содержательные поля против ЯКОРЯ; якорь передвигают
     * служебные правки (канонизация, перелинковка, миграции) — инженерская
     * правка горит, техническая волна — нет. */
    private val serviceAuthors = orbita.req.ServiceAuthors.all
    private val contentFields = listOf("statement", "mop", "allocated_to", "level", "category")

    /** Пусто/TBD: закрытие пустого значения — штатная работа, не ревизия. */
    private fun blankValue(n: com.fasterxml.jackson.databind.JsonNode): Boolean =
        n.isMissingNode || n.isNull ||
            (n.isTextual && n.asText().isBlank()) ||
            ((n.isArray || n.isObject) && n.isEmpty)

    private fun rowMarks(history: List<orbita.mod.store.StoredObject>): RowMarks {
        val current = history.lastOrNull() ?: return RowMarks(false, false)
        // Якорь — ПОСЛЕДНЕЕ утверждение: transition-строка (версия не
        // бампается) в Approved/Baseline — и переход, и подтверждение;
        // повторное базирование гасит помету (решение §2.4)
        var anchorAt = -1
        for (i in history.indices) {
            val approvedNow = history[i].status == Lifecycle.Approved || history[i].status == Lifecycle.Baseline
            val isTransition = i == 0 || history[i].version == history[i - 1].version
            if (approvedNow && isTransition) anchorAt = i
        }
        if (anchorAt < 0) return RowMarks(recalcAfterBaseline = false, changedAfterApproval = false)
        var anchor = history[anchorAt]
        for (i in anchorAt + 1 until history.size) {
            if (history[i].createdBy in serviceAuthors) anchor = history[i]
        }
        // Закрытие TBD — не помета (решение §1): переход пусто → значение не
        // ревизия; горит только изменение УЖЕ ЗАДАННОГО значения
        val changed = contentFields.any { f ->
            val was = anchor.doc.path(f)
            !blankValue(was) && current.doc.path(f) != was
        }
        // «Пересчитан после базирования» — только от механизма пересчёта
        // (mop.provenance.source = recomputed); механизма пока нет, флаг
        // честно ноль: молчащий флаг лучше врущего (РЕШЕНИЯ-Т1 §1.2)
        val recalc = history.any { it.status == Lifecycle.Baseline } &&
            current.doc.path("mop").path("provenance").path("source").asText("") == "recomputed"
        return RowMarks(recalcAfterBaseline = recalc, changedAfterApproval = changed)
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

    private fun currentRequirements(projectId: String? = null) =
        req.objects.listCurrent(projectId).filter { it.type == "requirement" && it.status != Lifecycle.Cancelled }
}
