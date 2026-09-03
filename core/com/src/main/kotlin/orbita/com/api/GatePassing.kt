// Прохождение контрольной точки и возвраты (блок B, ADR-029). Точка
// проходится проверкой, а не кнопкой: пустой перечень незакрытого —
// единственное условие. Эталон — spec/process_backbone.py.
package orbita.com.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.model.CoreType
import orbita.mod.store.ModelViolationException
import orbita.mod.store.ObjectStore
import orbita.mod.store.StoredObject
import orbita.req.Operations
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Точка не готова: перечень незакрытого и операции, где это чинится. */
class GateNotReadyException(
    val gate: String,
    val issues: List<String>,
    val operations: List<String>,
) : RuntimeException("gate '$gate' is not ready: ${issues.size} issue(s)")

class GatePassing(
    private val boundary: Boundary,
    private val operations: Operations = Operations(),
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    /**
     * Незакрытое до точки (ADR-029 п. 2): статусные и TRL-разрывы — на всех
     * точках; TBD/TBR и разрывы трассировки — с базирования (SRR и далее).
     * Полная верификация точку Формулирования не блокирует — она закрывается
     * в Phase C/D и остаётся сведением отчёта зрелости.
     *
     * Блок C добавил сюда замечания обзора (RFA/RID): незакрытое критическое
     * замечание своей точки блокирует прохождение; comment/question/
     * recommendation — нет. И выпуски комплекта: Д-коды операций точки обязаны
     * иметь выпуск документа своего шаблона (комплект Д1–Д9 / Д1–Д10).
     */
    fun issues(gate: String, projectId: String): List<String> {
        val project = projectOf(projectId)
        val phase = project.doc.path("phase").asText()
        val report = boundary.maturity.build(gate, projectId = projectId)
        val rows = operations.states(phase, boundary.req.snapshotsAt(null, projectId))
        return buildList {
            // выходы операций точки: пустой проект не проходит точку молча —
            // отсутствие материала называется операцией, где он создаётся
            rows.filter { it.operation.gate == gate }.forEach { r ->
                when (r.state) {
                    orbita.req.OperationState.NotStarted -> add(
                        "${r.operation.code}: выход операции не создан " +
                            "(${r.operation.kinds.joinToString()}) — «${r.operation.name}»"
                    )
                    orbita.req.OperationState.InProgress -> add(
                        "${r.operation.code}: выход не достиг статуса " +
                            "${r.operation.requiredStatus} — «${r.operation.name}»"
                    )
                    // NotMeasurable — материал вне системы до блока C (ADR-029 п. 6):
                    // видимый пробел реестра, но не блокировка точки
                    else -> {}
                }
            }
            report.gapsByType.forEach { (type, gaps) ->
                gaps.forEach { g -> add("${g.id}: $type ${g.actual} ниже требуемого ${g.required}") }
            }
            if (gate in BASELINE_GATES) {
                report.openTbd.forEach { add("${it.id}: незакрытый TBD/TBR") }
                report.traceBreaks.forEach { add("$it: требование без входящей нити трассировки") }
            }
            // незакрытые критические замечания обзора точки (блок C)
            boundary.objects.listCurrent(projectId)
                .filter { it.type == "review_item" }
                .map { it.doc }
                .filter {
                    it.path("review_gate").asText() == gate &&
                        it.path("classification").asText() == "critical" &&
                        it.path("status").asText() != "closed"
                }
                .sortedBy { it.path("id").asText() }
                .forEach {
                    add("${it.path("id").asText()}: незакрытое критическое замечание обзора — " +
                        it.path("statement").asText(""))
                }
            // выпуски комплекта точки (блок C): Д-коды операций → шаблоны фазы
            val kit = orbita.out.DocumentKits.kit(phase)
            val issued = boundary.objects.listCurrent(projectId)
                .filter { it.type == "document_issue" }
                .map { it.doc.path("template").asText() }
                .toSet()
            rows.filter { it.operation.gate == gate }
                .flatMap { r -> r.operation.docs }
                .distinct().sorted()
                .forEach { d ->
                    val template = kit[d] ?: return@forEach
                    if (template !in issued) {
                        add("$d: документ не выпущен (шаблон $template — POST /export/documents/$template/issue)")
                    }
                }
        }
    }

    /** Проверка готовности (О-11): агрегат, не телефонная книга строк. */
    data class Check(
        val id: String,
        val group: String,
        val title: String,
        /** open | closed | na; «0 объектов» — open (разрыв, не зелёный ноль). */
        val state: String,
        val blocking: Boolean,
        /** Число словами: «7 из 41 не базировано», «0 объектов — не заведены». */
        val note: String,
        /** Экран починки — «к месту →»; null у закрытых. */
        val place: String?,
        val naRationale: String? = null,
        val naAuthor: String? = null,
        val naAt: String? = null,
    )

    /**
     * Структурная готовность точки (О-11): те же источники, что блокируют
     * pass (выходы операций · зрелость · TBD/трассировка · критические
     * замечания · комплект документов) — агрегатами с местом починки, плюс
     * вычисляемые предупреждения (нужды, верификация, сироты, служба ИИ,
     * риски). Готовность вычисляется, не отмечается: ручных галочек нет.
     * Неприменимость — tailoring в паспорте (gate_tailoring), с автором.
     */
    fun readiness(gate: String, projectId: String): List<Check> {
        val project = projectOf(projectId)
        val phase = project.doc.path("phase").asText()
        val na = project.doc.path("gate_tailoring")
            .filter { it.path("gate").asText() == gate }
            .associateBy { it.path("check").asText() }
        val checks = mutableListOf<Check>()
        fun add(
            id: String, group: String, title: String, open: Int, note: String,
            place: String?, blocking: Boolean, closedNote: String = "закрыто",
        ) {
            val n = na[id]
            checks += when {
                n != null -> Check(
                    id, group, title, "na", blocking, note, null,
                    n.path("rationale").asText(), n.path("author").asText(),
                    n.path("at").asText(""),
                )
                open > 0 -> Check(id, group, title, "open", blocking, note, place)
                else -> Check(id, group, title, "closed", blocking, closedNote, null)
            }
        }

        val rows = operations.states(phase, boundary.req.snapshotsAt(null, projectId))
        rows.filter { it.operation.gate == gate }.forEach { r ->
            val open = if (r.state == orbita.req.OperationState.NotStarted ||
                r.state == orbita.req.OperationState.InProgress
            ) 1 else 0
            val note = when (r.state) {
                orbita.req.OperationState.NotStarted ->
                    "выход не создан (${r.operation.kinds.joinToString()})"
                orbita.req.OperationState.InProgress ->
                    "выход не достиг статуса ${r.operation.requiredStatus}"
                else -> "выход готов"
            }
            add(
                "op:${r.operation.code}", "blocking",
                "${r.operation.code} · ${r.operation.name}",
                open, note, r.operation.screen, blocking = true, closedNote = "выход готов",
            )
        }
        val report = boundary.maturity.build(gate, projectId = projectId)
        report.gapsByType.forEach { (type, gaps) ->
            add(
                "maturity:$type", "blocking", "Зрелость: $type",
                gaps.size, "${gaps.size} ниже требуемого статуса", "req", blocking = true,
            )
        }
        // ADR-053 (ответ владельца 03.09 §2): способность — слой «зачем», и
        // непривязанная держит MCR: полка даёт подсказку, служба предлагает
        // кандидатов по тексту, связь ставит инженер. Оставить её предложением
        // навсегда значило бы иметь архитектуру без причины.
        if (gate == "MCR") {
            val ничьи = boundary.objects.listCurrent(projectId)
                .filter { it.type == "capability" && it.status != orbita.mod.model.Lifecycle.Cancelled }
                .filter { it.doc.path("traced_to").isEmpty }
            add(
                "capabilities_traced", "blocking", "Способности привязаны к целям и нуждам",
                ничьи.size,
                "${ничьи.size} способностей ни к чему не привязано: " +
                    ничьи.take(4).joinToString(", ") { it.doc.path("code").asText(it.id) },
                "architecture", blocking = true, closedNote = "у каждой способности есть основание",
            )
        }
        if (gate in BASELINE_GATES) {
            add(
                "tbd", "blocking", "TBD/TBR закрыты",
                report.openTbd.size, "${report.openTbd.size} незакрытых", "req", blocking = true,
            )
            add(
                "trace", "blocking", "Трассировка без разрывов",
                report.traceBreaks.size, "${report.traceBreaks.size} без входящей нити", "req", blocking = true,
            )
            // ADR-045: связи с обоснованием, противоречия разрешены, критерий
            // приёмки записан. Противоречие держит ворота; два других — пометы
            // к базированию: разрыв виден, но ворот не держит
            val reqRows = boundary.screens.requirementTree(projectId).rows.filter { it.status != "Cancelled" }
            val conflicts = reqRows.filter { it.conflictOpen }
            add(
                "conflicts", "blocking", "Противоречия разрешены",
                conflicts.size, "${conflicts.size} с неразрешённым противоречием: " + conflicts.take(4).joinToString(", ") { it.id },
                "req", blocking = true, closedNote = "противоречий нет",
            )
            val noWhy = reqRows.filter { it.linkNoRationale }
            add(
                "link_rationale", "statement", "Связи декомпозиции с обоснованием",
                noWhy.size, "${noWhy.size} связей без обоснования: " + noWhy.take(4).joinToString(", ") { it.id },
                "req", blocking = false, closedNote = "у каждой связи есть обоснование",
            )
            // ADR-050: покрытие по категории — функциональное требование
            // покрывается функцией, сценарное цепочкой; иллюстрация не в счёт.
            // К SDR и дальше это ворота, до того — приглашение.
            if (gate == "SDR" || gate == "KDP-B") {
                val byKind = reqRows.filterNot { it.covered }.groupBy { it.coverageKind }
                listOf(
                    "function" to ("coverage_function" to "Функциональные требования покрыты функциями"),
                    "chain" to ("coverage_chain" to "Сценарные требования покрыты цепочками"),
                ).forEach { (kind, названия) ->
                    val (id, title) = названия
                    val holes = byKind[kind].orEmpty()
                    add(
                        id, "blocking", title,
                        holes.size, "${holes.size} без покрытия: " + holes.take(4).joinToString(", ") { it.id },
                        "matrix", blocking = true, closedNote = "покрытие полное",
                    )
                }
            }
            // ADR-052: архитектура объясняет, ЧЕМ требование выполнено, поэтому
            // спрашивается и она сама: функция, не севшая на узел, ничего не
            // объясняет, а цепочка без сценарного требования — рисунок сценария,
            // за который никто не отвечает. К SDR это ворота.
            if (gate == "SDR" || gate == "KDP-B") {
                val матрица = boundary.matrices.functionMatrix(projectId)
                add(
                    "functions_allocated", "blocking", "Функции распределены на узлы",
                    матрица.unallocated.size,
                    "${матрица.unallocated.size} функций без узла: " + матрица.unallocated.take(4).joinToString(", "),
                    "matrix", blocking = true, closedNote = "каждая функция на носителе",
                )
                val покрытие = boundary.matrices.coverageMatrix(projectId)
                val цепочкиСТребованиями = покрытие.rows.flatMap { it.realizedBy }.toSet()
                val пустыеЦепочки = boundary.objects.listCurrent(projectId)
                    .filter { it.type == "function_chain" && it.status != orbita.mod.model.Lifecycle.Cancelled }
                    .map { it.id }.filter { it !in цепочкиСТребованиями }
                add(
                    "chains_requirements", "blocking", "На цепочках висят сценарные требования",
                    пустыеЦепочки.size,
                    "${пустыеЦепочки.size} цепочек без требования: " + пустыеЦепочки.take(4).joinToString(", "),
                    "matrix", blocking = true, closedNote = "у каждой цепочки есть требование",
                )
            }
            val noAcc = reqRows.filter { it.noAcceptanceGap }
            add(
                "acceptance", "statement", "Критерий приёмки записан",
                noAcc.size, "${noAcc.size} без критерия приёмки: " + noAcc.take(4).joinToString(", ") { it.id },
                "req", blocking = false, closedNote = "критерии записаны",
            )
            // ответ владельца 03.09 (п. 4): заголовок — помета всегда, к базированию — разрыв
            val noTitle = reqRows.filter { it.noTitleGap }
            add(
                "title", "statement", "Заголовок требования записан",
                noTitle.size, "${noTitle.size} без заголовка: " + noTitle.take(4).joinToString(", ") { it.id },
                "req", blocking = false, closedNote = "заголовки записаны",
            )
        }
        val critical = boundary.objects.listCurrent(projectId)
            .filter { it.type == "review_item" }
            .map { it.doc }
            .count {
                it.path("review_gate").asText() == gate &&
                    it.path("classification").asText() == "critical" &&
                    it.path("status").asText() != "closed"
            }
        add(
            "reviews", "blocking", "Критические замечания обзора закрыты",
            critical, "$critical открыто", "rfa", blocking = true,
        )
        val kit = orbita.out.DocumentKits.kit(phase)
        val issued = boundary.objects.listCurrent(projectId)
            .filter { it.type == "document_issue" }
            .map { it.doc.path("template").asText() }.toSet()
        val docsDue = rows.filter { it.operation.gate == gate }
            .flatMap { r -> r.operation.docs }.distinct()
            .mapNotNull { kit[it] }
        val unissued = docsDue.count { it !in issued }
        add(
            "docs", "blocking", "Комплект документов точки выпущен",
            unissued, "$unissued из ${docsDue.size} не выпущено", "docs", blocking = true,
        )

        // тематические — вычисляемые предупреждения (не блокируют pass)
        val tree = boundary.screens.requirementTree(projectId)
        add(
            "needs", "statement", "Нужды покрыты требованиями",
            tree.needsUncovered.size, "${tree.needsUncovered.size} нужды не покрыты", "needs", blocking = false,
        )
        val noVerif = tree.rows.count { it.method == null }
        add(
            "verification", "statement", "Верификация назначена",
            noVerif, "$noVerif из ${tree.rows.size} без события", "req", blocking = false,
        )
        val orphans = tree.rows.count { it.noCarrierGap }
        add(
            "carriers", "statement", "Системные требования распределены",
            orphans, "$orphans из ${tree.rows.size} без носителя", "req", blocking = false,
        )
        val pending = tree.rows.count { it.origin == "ai_proposed" && it.status == "Draft" }
        add(
            "ai", "ai", "Предложения службы разобраны",
            pending, "$pending ждут акцепта", "req", blocking = false,
        )
        val risks = boundary.objects.listCurrent(projectId)
            .filter { it.type == "risk" && it.status.name != "Cancelled" }
        add(
            "risks", "risks", "Риски заведены и живы",
            if (risks.isEmpty()) 1 else 0,
            if (risks.isEmpty()) "0 объектов — рисков не заведено" else "${risks.size} в реестре",
            "risks", blocking = false, closedNote = "${risks.size} в реестре",
        )
        // Д2 (ответ владельца): область приоритета, принятая из документа,
        // живёт заготовкой — имя есть, границы нет. Пустота не тихая: она
        // разрыв готовности карты спроса, и его закрывает инженер границей.
        val masks = boundary.objects.listCurrent(projectId)
            .filter { it.type == "geo_mask" && it.status.name != "Cancelled" }
        val maskless = masks.count { !it.doc.path("geometry").isObject }
        if (masks.isNotEmpty()) {
            add(
                "geo_masks", "statement", "Области приоритета обведены границей",
                maskless,
                "$maskless из ${masks.size} без геометрии: " +
                    masks.filter { !it.doc.path("geometry").isObject }
                        .joinToString(", ") { it.doc.path("name").asText(it.id) },
                "seeddemand", blocking = false,
                closedNote = "${masks.size} с границей",
            )
        }
        // Ф-06: анкеты библиотеки объявили потребные данные — незаполненное
        // обязательное поле становится разрывом «данные не заданы» с местом
        // починки, а не тихой дырой в модели.
        val dataMissing = DataRequests(boundary).missingSummary(projectId)
        if (dataMissing.isNotEmpty() || boundary.objects.listCurrent(ObjectStore.LIBRARY_PROJECT)
                .any { it.type == "property_form" }
        ) {
            add(
                "data_requests", "statement", "Характеристики носителей заданы",
                dataMissing.size,
                if (dataMissing.isEmpty()) "анкеты закрыты"
                else "${dataMissing.size} обязательных полей не заданы: " +
                    dataMissing.take(4).joinToString("; ") +
                    (if (dataMissing.size > 4) " и ещё ${dataMissing.size - 4}" else ""),
                "spacecraft", blocking = false, closedNote = "анкеты закрыты",
            )
        }
        // ADR-050: модель обязана дать ОТВЕТ к своей точке; «файл есть» не
        // считается. Незаданный вход чинится в анкете узла, а не здесь.
        val modelGaps = boundary.systemModels.gaps(projectId, gate)
        if (boundary.objects.listCurrent(projectId).any { it.type == "system_model" }) {
            val noAnswer = modelGaps.filter { it.what.startsWith("модель не дала ответа") }
            add(
                "models_answer", "blocking", "Модели дали ответ к точке",
                noAnswer.size, "${noAnswer.size} без ответа: " + noAnswer.take(4).joinToString(", ") { it.code },
                "models", blocking = true, closedNote = "ответы получены",
            )
            val noInput = modelGaps.filter { it.what.startsWith("вход модели не задан") }
            add(
                "models_inputs", "statement", "Входы моделей заданы",
                noInput.size, "${noInput.size} незаданных входов: " + noInput.take(3).joinToString("; ") { it.what },
                "datarequests", blocking = false, closedNote = "входы заданы",
            )
        }
        // Ф-13: у нужды обязан быть носитель — стейкхолдер проекта. Разрыв
        // МЯГКИЙ: связь дозревает к MCR, и на ранней фазе нужда без носителя
        // законна. Пока стейкхолдеров в проекте нет вовсе, спрашивать не о чем.
        val stakeholders = boundary.objects.listCurrent(projectId)
            .filter { it.type == "stakeholder" && it.status.name != "Cancelled" }
        val needs = boundary.objects.listCurrent(projectId)
            .filter { it.type == "need" && it.status.name != "Cancelled" }
        if (stakeholders.isNotEmpty() && needs.isNotEmpty()) {
            val orphan = needs.filter { it.doc.path("stakeholder_ref").asText("").isBlank() }
            add(
                "need_stakeholder", "statement", "У нужд назван носитель",
                orphan.size,
                if (orphan.isEmpty()) "у всех нужд есть стейкхолдер-носитель"
                else "${orphan.size} из ${needs.size} нужд без носителя: " +
                    orphan.take(3).joinToString("; ") { it.id } +
                    (if (orphan.size > 3) " и ещё ${orphan.size - 3}" else ""),
                "needs", blocking = false,
                closedNote = "носитель назван у всех ${needs.size}",
            )
        }
        val oda = boundary.objects.listCurrent(projectId).count { it.type == "oda" }
        add(
            "oda", "risks", "Оценка орбитального засорения присутствует",
            if (oda == 0) 1 else 0,
            if (oda == 0) "0 объектов — оценка не заведена" else "оценка есть",
            "oda", blocking = false, closedNote = "оценка есть",
        )
        return checks
    }

    /** Проект обязан существовать и вести перечень вех. */
    private fun projectOf(projectId: String): StoredObject =
        boundary.objects.current(projectId)?.takeIf { it.type == "project" }
            ?: throw NoSuchElementException("проект '$projectId' не найден")

    private fun milestonesOf(project: StoredObject): List<JsonNode> =
        project.doc.path("milestones").toList()

    /**
     * Ближайшая непройденная веха В ГОРИЗОНТЕ ИС; null — горизонт исчерпан.
     * Вехи Phase B–F (PDR, CDR…) — план в едином ряду точек, но ворот к ним
     * нет: ИС ведёт проект до конца Формулирования, дальние точки она
     * показывает, не проводит.
     */
    fun nextGate(projectId: String): String? =
        milestonesOf(projectOf(projectId))
            .firstOrNull {
                !it.path("held").asBoolean(false) &&
                    it.path("gate").asText() in boundary.req.gates.gateNames
            }
            ?.path("gate")?.asText()

    /**
     * Прохождение точки (ADR-029 п. 1, 3, 4): только ближайшая непройденная,
     * только без действующего возврата, только с пустым перечнем незакрытого.
     * Готовность фиксируется решением (Approve) и вехой held.
     */
    fun pass(gate: String, rationale: String, author: String, projectId: String): ObjectNode {
        val project = projectOf(projectId)
        val activeReturn = project.doc.path("return")
        if (activeReturn.isObject) {
            throw ModelViolationException(
                "ADR-029: действует возврат от точки ${activeReturn.path("gate").asText()} " +
                    "(${activeReturn.path("reason").asText()}) — сначала снимите его " +
                    "(POST /gates/return/resolve)"
            )
        }
        if (gate !in boundary.req.gates.gateNames) {
            throw ModelViolationException(
                "ADR-029: точка '$gate' за горизонтом Формулирования — ИС показывает её " +
                    "в плане, но не проводит (ворот к ней нет)"
            )
        }
        val next = nextGate(projectId)
            ?: throw ModelViolationException("ADR-029: все вехи проекта $projectId в горизонте ИС уже пройдены")
        if (gate != next) {
            throw ModelViolationException(
                "ADR-029: точки проходятся по порядку вех — ближайшая непройденная '$next', не '$gate'"
            )
        }
        val blocking = issues(gate, projectId)
        if (blocking.isNotEmpty()) {
            throw GateNotReadyException(
                gate, blocking,
                operations.ofGate(gate)
                    .filter { it.phase == project.doc.path("phase").asText() }
                    .map { "${it.code} — ${it.name}" },
            )
        }
        require(rationale.isNotBlank()) {
            "ADR-029: прохождение фиксируется решением — нужно основание (rationale)"
        }

        // решение — состав рассмотрения KDP (§7.3 БП-PPA, §7.4 БП-PA)
        val decision = mapper.createObjectNode()
        decision.put("question", "Прохождение точки $gate")
        val alts = decision.putArray("alternatives")
        listOf("Approve", "Return", "Terminate/Defer").forEach { alts.addObject().put("name", it) }
        decision.put("status", "decided")
        decision.put("selected", "Approve")
        decision.put("rationale", rationale)
        val stored = boundary.editing.create(CoreType.Decision, decision, author, projectId)

        // веха held — правка объекта проекта тем же рабочим слоем, с автором
        val milestones = project.doc.path("milestones").deepCopy<JsonNode>()
        milestones.forEach { m ->
            if (m.path("gate").asText() == gate) {
                (m as ObjectNode).put("held", true)
                m.put("held_at", OffsetDateTime.now(ZoneOffset.UTC).toString())
            }
        }
        val changes = mapper.createObjectNode()
        changes.set<ObjectNode>("milestones", milestones)
        // KDP A — вход в Phase A (§1.4 БП-PPA): решение «Approve» переводит
        // проект в следующую фазу, операции и комплекты меняются вместе с ней
        if (gate == "KDP-A") changes.put("phase", "phase_a")
        // Паспорт может быть базирован — и это не преграда: прохождение точки
        // само есть процедура с основанием (TZ-COM-003), основание — решение
        boundary.editing.update(
            CoreType.Project, projectId, changes, project.version, author,
            changeRef = "${stored.id}: прохождение точки $gate",
        )

        val out = mapper.createObjectNode()
        out.put("passed", true)
        out.put("gate", gate)
        out.put("decision", stored.id)
        out.put("next_gate", nextGate(projectId))
        return out
    }

    /**
     * Возврат (ADR-029 п. 5): состояние проекта, а не пометка в протоколе.
     * Цели — только из §5.1 своей фазы; фиксируется решением Return.
     */
    fun requestReturn(
        gate: String,
        to: List<String>,
        reason: String,
        author: String,
        projectId: String,
    ): ObjectNode {
        val project = projectOf(projectId)
        require(reason.isNotBlank()) { "ADR-029: возврат без причины не записывается" }
        val phase = project.doc.path("phase").asText()
        val allowed = operations.returnTargets(phase, gate)
        require(allowed.isNotEmpty()) { "ADR-029: точка '$gate' в фазе '$phase' возвратов не имеет" }
        val targets = to.ifEmpty { allowed }
        val illegal = targets.filterNot { it in allowed }
        require(illegal.isEmpty()) {
            "ADR-029 (§5.1): недопустимые цели возврата $illegal — разрешено $allowed"
        }

        val decision = mapper.createObjectNode()
        decision.put("question", "Заключение точки $gate")
        val alts = decision.putArray("alternatives")
        listOf("Approve", "Return", "Terminate/Defer").forEach { alts.addObject().put("name", it) }
        decision.put("status", "decided")
        decision.put("selected", "Return")
        decision.put("rationale", reason)
        val stored = boundary.editing.create(CoreType.Decision, decision, author, projectId)

        val ret = mapper.createObjectNode()
        ret.put("gate", gate)
        ret.putArray("to").also { arr -> targets.forEach(arr::add) }
        ret.put("reason", reason)
        ret.put("decided_by", author)
        ret.put("at", OffsetDateTime.now(ZoneOffset.UTC).toString())
        val changes = mapper.createObjectNode()
        changes.set<ObjectNode>("return", ret)
        boundary.editing.update(
            CoreType.Project, projectId, changes, project.version, author,
            changeRef = "${stored.id}: возврат от точки $gate",
        )

        val out = mapper.createObjectNode()
        out.put("returned", true)
        out.put("gate", gate)
        out.put("decision", stored.id)
        out.putArray("to").also { arr -> targets.forEach(arr::add) }
        return out
    }

    /** Снятие возврата — с автором и основанием; без него точки не проходятся. */
    fun resolveReturn(note: String, author: String, projectId: String): ObjectNode {
        val project = projectOf(projectId)
        val active = project.doc.path("return")
        if (!active.isObject) {
            throw ModelViolationException("ADR-029: действующего возврата нет — снимать нечего")
        }
        require(note.isNotBlank()) { "ADR-029: снятие возврата требует основания (note)" }
        val changes = mapper.createObjectNode()
        changes.putNull("return")
        boundary.editing.update(
            CoreType.Project, projectId, changes, project.version, author,
            changeRef = "снятие возврата: $note",
        )
        val out = mapper.createObjectNode()
        out.put("resolved", true)
        out.put("gate", active.path("gate").asText())
        out.put("note", note)
        return out
    }

    /** Состояние операций фазы проекта (ADR-029 п. 6) с целями возврата. */
    fun operationStates(projectId: String): ObjectNode {
        val project = projectOf(projectId)
        val phase = project.doc.path("phase").asText()
        val returnedTo = project.doc.path("return").path("to").map { it.asText() }.toSet()
        val rows = operations.states(
            phase,
            boundary.req.snapshotsAt(null, projectId),
            returnedTo,
        )
        val out = mapper.createObjectNode()
        out.put("project", projectId)
        out.put("phase", phase)
        out.put("next_gate", nextGate(projectId))
        val arr = out.putArray("operations")
        rows.forEach { r ->
            val n = arr.addObject()
            n.put("code", r.operation.code)
            n.put("name", r.operation.name)
            n.put("executor", r.operation.executor)
            n.put("gate", r.operation.gate)
            n.put("required_status", r.operation.requiredStatus)
            n.put("state", r.state.name)
            n.put("objects", r.objects)
            r.operation.screen?.let { n.put("screen", it) }
            // МВП-П1: порядок работы — данными; параллельность только из
            // карты фазы (входы-предшественники), не рисунком
            n.putArray("inputs").also { a -> r.operation.inputs.forEach(a::add) }
            if (r.returnedTo) n.put("returned_to", true)
            r.operation.docs.takeIf { it.isNotEmpty() }?.let { docs ->
                n.putArray("docs").also { a -> docs.forEach(a::add) }
                // операция про документ обязана вести к СВОЕМУ шаблону, а не
                // к первому попавшемуся на экране «Документы» (находка
                // прогона: О11 «План проекта» открывал спецификацию)
                val kit = orbita.out.DocumentKits.kit(phase)
                val templates = docs.mapNotNull { kit[it] }
                if (templates.isNotEmpty()) {
                    n.putArray("templates").also { a -> templates.forEach(a::add) }
                }
            }
        }
        return out
    }

    private companion object {
        /** Точки базирования: TBD/TBR и трассировка блокируют с SRR (§7.1 БП-PA). */
        val BASELINE_GATES = setOf("SRR", "SDR", "KDP-B")
    }
}
