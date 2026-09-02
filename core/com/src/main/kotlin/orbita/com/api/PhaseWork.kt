// «Работа фазы» — главный экран проекта после мастера. Владелец: «мастер
// доводит до постановки и обрывается; дальше месяцы работы команды по
// регламенту, и именно у неё нет лица в продукте».
//
// Здесь считается всё, что показывает экран, и НИЧЕГО не хранится:
//   · статус задачи — из состояния проекта (ожидает → доступна → в работе →
//     выполнена). Ручных статусов, процентов и «сделано» не существует;
//   · шаги — из полки, сделанность каждого — проверяемым условием;
//   · разрывы задачи — те же разрывы готовности к точке, взятые РАЗРЕЗОМ по
//     операциям задачи. Второй готовности не заводится;
//   · окна ленты живут в Ганте (круг 5, PhaseGantt): их источник — ПЛАН
//     руководителя, а где плана нет — расчётная сетка ярусов, серая и
//     подписанная. Здесь остаются лишь даты точек: конец — дата точки, к
//     которой зреет выход. Ручных длительностей и сроков задач нет.
package orbita.com.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.store.ObjectStore
import orbita.mod.store.StoredObject
import java.time.LocalDate

object PhaseWork {

    private val mapper = ObjectMapper()

    data class StepState(
        val title: String,
        val hint: String?,
        val screen: String?,
        val kind: String?,
        /** Шаблон документа, если шаг ведёт в создание документа (Phase A, SEMP). */
        val documentCode: String?,
        /** Мини-итог: что уже сделано этим шагом — «стейкхолдеров: 5». */
        val tally: String?,
        val done: Boolean,
        val why: String,
        /**
         * Круг 6: порядок шагов — не догадка. Связи с соседними шагами живут в
         * полке (`after`), из них считается ЯРУС шага внутри задачи: шаги
         * одного яруса идут параллельно. Умолчания «следом за предыдущим» нет.
         */
        val after: List<StepDep>,
        val tier: Int,
        val tiers: Int,
    )

    /**
     * Связь шага с соседним шагом: своей задачи либо чужой (круг 8). Точная
     * правда о порядке живёт на уровне шагов: «2.3 Базировать ConOps» ждёт
     * «1.3 Базировать SEMP», хотя «2.1 Дорастить сценарии» идёт параллельно.
     */
    data class StepDep(val step: Int, val type: String, val task: String? = null)

    /**
     * Круг 6: связь задачи с предшественником вместе с ТИПОМ. Регламент
     * итеративно-параллелен, и рисовать всё «после окончания» — неправда:
     * тип обязателен и приходит с полки.
     */
    data class Dep(val task: String, val type: String, val note: String?)

    data class TaskState(
        val id: String,
        val order: Int,
        val name: String,
        val why: String,
        val status: String,
        val waitsOn: String?,
        val inputReady: Boolean,
        val inputWhy: String,
        val steps: List<StepState>,
        val gaps: List<Gap>,
        val artifact: String,
        val gate: String?,
        val outputDone: Boolean,
        val start: LocalDate?,
        val end: LocalDate?,
        /**
         * Круг 2: топологический ярус задачи в цепочке зависимостей внутри
         * межвехового интервала. Ярус 1 — задачи, чьи входы готовы сами
         * (always либо данные проекта); ярус N+1 — те, кто ждёт кого-то из
         * яруса N. Ярус — ПОРЯДОК РАБОТ, а не срок: длительностей у задач
         * по-прежнему нет.
         */
        val tier: Int,
        /** Сколько ярусов в интервале этой точки — знаменатель доли. */
        val tiers: Int,
        /**
         * Круг 4, поток: от кого пришёл вход и кто ждёт выход. Держится
         * здесь, а не считается заново схемой: «как течёт» и «что делать» —
         * одна и та же цепочка зависимостей, второй её копии не заводится.
         */
        val dependsOn: List<Dep>,
        val consumers: List<String>,
        val inputs: List<InputState>,
        /** Код документа выхода — по нему артефакт открывается с ребра схемы. */
        val documentCode: String?,
        val maturity: String?,
        /**
         * Чего задача КАСАЕТСЯ: виды объектов и коды документов, названные её
         * же условиями. По ним берётся последняя активность на схеме — счёт
         * тот же самый, что у сделанности шага и мини-итога.
         */
        val touchesTypes: List<String>,
        val touchesCodes: List<String>,
    )

    /** Условие входа задачи: как называется человеку и выполнено ли оно. */
    data class InputState(val label: String, val ready: Boolean)

    /**
     * Разрыв задачи — тот же разрыв готовности к точке, взятый разрезом. Круг 8:
     * он приходит АДРЕСОМ, а не строкой, — иначе с него нельзя назначить
     * задание ответственному за эту задачу.
     */
    data class Gap(
        val id: String,
        val title: String,
        val note: String,
        val place: String?,
        val gate: String,
    )

    /**
     * Условие полки → да/нет по состоянию проекта. Набор закрытый: система
     * обязана уметь ответить сама, иначе на экране появился бы ручной
     * переключатель «сделано», а его быть не должно.
     */
    /**
     * Круг 3, мини-итог шага: СКОЛЬКО уже есть по его условию — «стейкхолдеров:
     * 5». Без числа «что я сделал» приходится раскапывать по разделам, а
     * рамка ведения обязана показывать след работы на месте.
     *
     * Считается тем же условием, что и сделанность: другого источника у шага
     * нет, и расхождению взяться неоткуда.
     */
    private fun tally(
        condition: JsonNode,
        own: List<StoredObject>,
        passport: JsonNode,
        issued: Set<String>,
    ): String? = when (condition.path("check").asText()) {
        "objects" -> {
            val type = condition.path("type").asText("")
            val n = own.count { it.type == type && codeMatches(condition, it) }
            if (n > 0) "${labelOfType(type)}: $n" else null
        }
        "taken_from_library" -> {
            val n = passport.path("start_path").path("created_counts")
                .path(condition.path("type").asText("")).asInt(0)
            if (n > 0) "взято: $n" else null
        }
        "document_issued" -> {
            val code = condition.path("code").asText("")
            if (code in issued) "выпущен" else null
        }
        "passport_field" -> {
            // мини-итог обязан совпадать со сделанностью: пустой список плана —
            // это «не задано», а не «задано» (находка перезаливки полки)
            val node = passport.path(condition.path("field").asText(""))
            val есть = when {
                node.isMissingNode || node.isNull -> false
                node.isObject -> !node.isEmpty
                node.isArray -> node.size() > 0
                else -> node.asText("").isNotBlank()
            }
            if (есть) "задано" else null
        }
        else -> null
    }

    /**
     * Патч контента Phase A: условие `objects` с полем `code` сужает вид до
     * документа — «связные разделы SEMP написаны» считает только тексты
     * разделов шаблона semp, а не любой авторский текст проекта. Без `code`
     * поведение прежнее: считается весь вид.
     */
    private fun codeMatches(condition: JsonNode, o: StoredObject): Boolean {
        val code = condition.path("code").asText("")
        if (code.isBlank()) return true
        val own = o.doc.path("template_code").asText("").ifBlank { o.doc.path("code").asText("") }
        return own == code
    }

    /** Имя вида для мини-итога — человеку, а не машинное. */
    private fun labelOfType(type: String): String = when (type) {
        "stakeholder" -> "стейкхолдеров"
        "mission_goal" -> "целей"
        "need" -> "нужд"
        "service" -> "сервисов"
        "requirement" -> "требований"
        "component" -> "узлов"
        "interface" -> "интерфейсов"
        "risk" -> "рисков"
        "technology" -> "технологий"
        "decision" -> "решений"
        "review_item" -> "записей обзора"
        "cost_estimate" -> "оценок"
        "oda" -> "оценок ОСЗ"
        "wbs_element" -> "элементов ВС"
        "section_text" -> "разделов написано"
        else -> type
    }

    private fun holds(
        condition: JsonNode,
        own: List<StoredObject>,
        passport: JsonNode,
        gateChecks: Map<String, String>,
        issued: Set<String>,
    ): Boolean = when (condition.path("check").asText()) {
        "always" -> true
        "objects" -> {
            val type = condition.path("type").asText("")
            val min = condition.path("min").asInt(1)
            own.count { it.type == type && codeMatches(condition, it) } >= min
        }
        "passport_field" -> {
            val field = condition.path("field").asText("")
            val node = passport.path(field)
            when {
                node.isMissingNode || node.isNull -> false
                node.isObject -> !node.isEmpty
                node.isArray -> node.size() > 0
                else -> node.asText("").isNotBlank()
            }
        }
        "taken_from_library" -> {
            val type = condition.path("type").asText("")
            passport.path("start_path").path("created_counts").path(type).asInt(0) > 0
        }
        "gate_check" -> gateChecks[condition.path("gate_check_id").asText("")] == "closed"
        "document_issued" -> condition.path("code").asText("") in issued
        else -> false
    }

    private fun labelOf(condition: JsonNode): String =
        condition.path("label").asText("").ifBlank {
            when (condition.path("check").asText()) {
                "objects" -> "нужны объекты вида «${condition.path("type").asText()}»" +
                    condition.path("code").asText("").ifBlank { null }?.let { " документа «$it»" }.orEmpty()
                "passport_field" -> "нужно поле паспорта «${condition.path("field").asText()}»"
                "taken_from_library" -> "нужен набор библиотеки «${condition.path("type").asText()}»"
                "gate_check" -> "нужна закрытая проверка «${condition.path("gate_check_id").asText()}»"
                "document_issued" -> "нужен выпущенный документ «${condition.path("code").asText()}»"
                else -> "условие не названо"
            }
        }

    /**
     * Связи задачи с типами. Строка вместо объекта — неразмеченная связь
     * прежнего формата: читается как INPUT («нужен выход-артефакт»), то есть
     * ровно тем смыслом, который «ждёт» имело раньше. FS по умолчанию не
     * подставляется никогда: это была бы выдумка о регламенте.
     */
    private fun depsOf(doc: JsonNode, known: Set<String>): List<Dep> =
        doc.path("depends_on").mapNotNull { d ->
            if (d.isTextual) Dep(d.asText(), "INPUT", null)
            else Dep(
                d.path("task").asText(""),
                d.path("type").asText("INPUT"),
                d.path("note").asText("").ifBlank { null },
            )
        }.filter { it.task in known }

    /**
     * Блокирует ли связь работу. Тип решает:
     *   FS, INPUT — предшественник обязан ЗАКОНЧИТЬ (его выход готов);
     *   SS        — предшественник обязан НАЧАТЬСЯ (хоть один шаг сделан);
     *   FF        — не блокирует вовсе: это условие на окончание, не на старт.
     */
    private fun blocks(type: String, predOutputDone: Boolean, predStarted: Boolean): Boolean =
        when (type) {
            "FS", "INPUT" -> !predOutputDone
            "SS" -> !predStarted && !predOutputDone
            else -> false
        }

    /** Связь словами: «вместе с 4 · Архитектура (после её старта)». */
    fun linkWords(type: String, who: String): String = when (type) {
        "FS" -> "после окончания $who"
        "SS" -> "вместе с $who (после её старта)"
        "FF" -> "закончить не раньше $who"
        else -> "нужен выход-артефакт задачи $who"
    }

    /** Все условия задачи одним списком: вход, шаги, выход. */
    private fun conditionsOf(doc: JsonNode): List<JsonNode> =
        doc.path("input").toList() +
            doc.path("steps").map { it.path("done_when") } +
            listOf(doc.path("output").path("done_when"))

    fun of(boundary: Boundary, projectId: String): List<TaskState> {
        val passport = boundary.objects.current(projectId)?.doc ?: mapper.createObjectNode()
        val phase = passport.path("phase").asText("pre_phase_a")
        val own = boundary.objects.listCurrent(projectId).filter { it.status.name != "Cancelled" }
        val tasks = boundary.objects.listCurrent(ObjectStore.LIBRARY_PROJECT)
            .filter { it.type == "phase_task" && it.status.name != "Cancelled" }
            .filter { it.doc.path("phase").asText() == phase }
            .sortedBy { it.doc.path("order").asInt() }
        if (tasks.isEmpty()) return emptyList()

        // разрывы готовности к точке — ОДИН источник; здесь берётся разрез.
        // Второй готовности не заводится: те же проверки, другой разрез.
        val passing = GatePassing(boundary)
        val gate = passing.nextGate(projectId) ?: ""
        val checks = if (gate.isBlank()) emptyList() else passing.readiness(gate, projectId)
        val gateChecks = checks.associate { it.id to it.state }
        val gateDate = milestoneDate(passport, gate)
        val issued = own.filter { it.type == "document_issue" }
            .map { it.doc.path("code").asText("") }.filter { it.isNotBlank() }.toSet()

        val byId = tasks.associateBy { it.id }
        val states = LinkedHashMap<String, TaskState>()
        tasks.forEach { task ->
            val doc = task.doc
            val inputs = doc.path("input").toList()
            val unmet = inputs.filterNot { holds(it, own, passport, gateChecks, issued) }
            val stepDeps = doc.path("steps").map { st ->
                st.path("after").map { a ->
                    StepDep(
                        a.path("step").asInt(),
                        a.path("type").asText("FS"),
                        a.path("task").asText("").ifBlank { null },
                    )
                }
            }
            val stepTiers = stepTiers(stepDeps)
            val stepsTotal = stepTiers.maxOrNull() ?: 1
            val steps = doc.path("steps").mapIndexed { i, st ->
                StepState(
                    title = st.path("title").asText(),
                    hint = st.path("hint").asText("").ifBlank { null },
                    screen = st.path("screen").asText("").ifBlank { null },
                    kind = st.path("kind").asText("").ifBlank { null },
                    documentCode = st.path("document_code").asText("").ifBlank { null },
                    tally = tally(st.path("done_when"), own, passport, issued),
                    done = holds(st.path("done_when"), own, passport, gateChecks, issued),
                    why = labelOf(st.path("done_when")),
                    after = stepDeps[i],
                    tier = stepTiers[i],
                    tiers = stepsTotal,
                )
            }
            // Кого ждём — решает ТИП связи, а не общий закон «после окончания».
            // SS ждёт старта, FF не ждёт вовсе, FS и INPUT ждут выхода.
            val deps = depsOf(doc, byId.keys)
            val waitingDep = deps.firstOrNull { dep ->
                val pred = byId.getValue(dep.task)
                val out = pred.doc.path("output").path("done_when")
                val predDone = !out.isMissingNode && holds(out, own, passport, gateChecks, issued)
                val predStarted = pred.doc.path("steps")
                    .any { holds(it.path("done_when"), own, passport, gateChecks, issued) }
                blocks(dep.type, predDone, predStarted)
            }
            val waiting = waitingDep?.let { byId.getValue(it.task) }
            val outputDone = doc.path("output").path("done_when").let {
                !it.isMissingNode && holds(it, own, passport, gateChecks, issued)
            }
            // Разрез: разрывом ЗАДАЧИ считается открытая проверка, которая
            // либо названа условием её шага, либо чинится на экране её шага.
            // Так задача не заводит собственных чек-листов — она лишь смотрит
            // на общую готовность своим углом.
            val screens = (steps.mapNotNull { it.screen } +
                listOfNotNull(doc.path("output").path("document_code").asText("").ifBlank { null }
                    ?.let { "documents" })).toSet()
            val namedChecks = (doc.path("steps").mapNotNull {
                it.path("done_when").path("gate_check_id").asText("").ifBlank { null }
            } + listOfNotNull(
                doc.path("output").path("done_when").path("gate_check_id").asText("").ifBlank { null },
            )).toSet()
            val gaps = checks
                .filter { it.state == "open" }
                .filter { it.id in namedChecks || (it.place != null && it.place in screens) }
                .map { Gap(it.id, it.title, it.note, it.place, gate) }
            val status = when {
                outputDone -> "done"
                waiting != null -> "waiting"
                unmet.isNotEmpty() -> "waiting"
                steps.any { it.done } -> "in_progress"
                else -> "available"
            }
            // окно: старт — сегодня для доступной, дедлайн предшественника для
            // ожидающей; конец — дата точки выхода. Длительности не вводятся.
            val end = milestoneDate(passport, doc.path("output").path("gate").asText("")) ?: gateDate
            val start = when {
                status == "waiting" && waiting != null ->
                    milestoneDate(passport, waiting.doc.path("output").path("gate").asText("")) ?: end
                else -> null
            }
            states[task.id] = TaskState(
                id = task.id,
                order = doc.path("order").asInt(),
                name = doc.path("name").asText(),
                why = doc.path("why").asText(),
                status = status,
                waitsOn = waitingDep?.let { dep ->
                    val pred = byId.getValue(dep.task)
                    linkWords(
                        dep.type,
                        "${pred.doc.path("order").asInt()} · ${pred.doc.path("name").asText()}",
                    )
                } ?: unmet.firstOrNull()?.let { labelOf(it) },
                inputReady = unmet.isEmpty() && waiting == null,
                inputWhy = if (unmet.isEmpty() && waiting == null) "вход готов"
                else (unmet.map { labelOf(it) } + listOfNotNull(waiting?.doc?.path("name")?.asText()))
                    .joinToString("; "),
                steps = steps,
                gaps = gaps,
                artifact = doc.path("output").path("artifact").asText(""),
                gate = doc.path("output").path("gate").asText("").ifBlank { null },
                outputDone = outputDone,
                start = start,
                end = end,
                tier = 1,
                tiers = 1,
                dependsOn = deps,
                consumers = emptyList(),
                inputs = inputs.map { InputState(labelOf(it), holds(it, own, passport, gateChecks, issued)) },
                documentCode = doc.path("output").path("document_code").asText("").ifBlank { null },
                maturity = doc.path("output").path("maturity").asText("").ifBlank { null },
                touchesTypes = conditionsOf(doc)
                    .mapNotNull { it.path("type").asText("").ifBlank { null } }.distinct(),
                touchesCodes = conditionsOf(doc)
                    .mapNotNull { it.path("code").asText("").ifBlank { null } }.distinct(),
            )
        }
        // потребители — обратная сторона той же зависимости, не второй список
        val withConsumers = states.values.map { t ->
            t.copy(consumers = states.values.filter { c -> c.dependsOn.any { it.task == t.id } }.map { it.id })
        }
        return withTiers(withConsumers, byId)
    }

    /**
     * Круг 2 (правка модели владельца): окна вырождались в чёрточки — у задач
     * одной точки старт = конец = дата этой точки, и полоса не имела длины.
     * Длительности вводить нельзя, поэтому интервал делится ПО ПОРЯДКУ
     * ЗАВИСИМОСТЕЙ: топологический ярус внутри интервала своей точки даёт
     * долю, задачи одного яруса идут параллельными полосами одной доли.
     *
     * Это расчётная сетка, а не обещание сроков — так и подписано на ленте.
     */
    private fun withTiers(
        tasks: List<TaskState>,
        byId: Map<String, StoredObject>,
    ): List<TaskState> {
        val порядок = tasks.associateBy { it.id }
        val зависит = tasks.associate { t -> t.id to t.dependsOn.filter { it.task in порядок } }
        // Ярус = 1 + максимум ярусов предшественников. Круг 6: FF порядок не
        // двигает — «закончить не раньше» говорит об окончании, а не о старте,
        // и такие задачи живут в одном ярусе. Цикл невозможен: сторож сида
        // держит зависимости внутри фазы и по возрастанию.
        val ярусы = HashMap<String, Int>()
        fun ярус(id: String, глубина: Int = 0): Int = ярусы.getOrPut(id) {
            if (глубина > tasks.size) 1
            else (
                зависит[id].orEmpty()
                    .maxOfOrNull { d -> ярус(d.task, глубина + 1) + (if (d.type == "FF") 0 else 1) }
                    ?: 1
                )
        }
        tasks.forEach { ярус(it.id) }
        // знаменатель считается ПО ТОЧКЕ: у каждой точки своя сетка ярусов
        val поТочке = tasks.groupBy { it.gate }
        return tasks.map { t ->
            val свой = ярус(t.id)
            val всего = поТочке[t.gate].orEmpty().maxOfOrNull { ярус(it.id) } ?: 1
            t.copy(tier = свой, tiers = maxOf(1, всего))
        }
    }

    /**
     * Ярусы шагов внутри задачи по связям полки: FS уводит на ярус ниже, SS
     * оставляет рядом. Шаг без связей — начальный (первый ярус). Умолчания
     * «следом за предыдущим» нет: порядок обязан быть размечен, иначе шаги
     * честно показываются параллельными.
     */
    private fun stepTiers(deps: List<List<StepDep>>): List<Int> {
        val ярусы = IntArray(deps.size)
        fun ярус(i: Int, глубина: Int = 0): Int {
            if (ярусы[i] != 0) return ярусы[i]
            if (глубина > deps.size) return 1
            // межзадачные связи ярусов НЕ двигают: окно шага делит окно своей
            // задачи, а чужой порядок показывается стрелкой и словами
            val свой = deps[i]
                .filter { it.task == null }
                .filter { it.step - 1 in deps.indices && it.step - 1 != i }
                .maxOfOrNull { d -> ярус(d.step - 1, глубина + 1) + (if (d.type == "FS") 1 else 0) }
                ?: 1
            ярусы[i] = свой
            return свой
        }
        deps.indices.forEach { ярус(it) }
        return ярусы.toList()
    }

    private fun milestoneDate(passport: JsonNode, gate: String): LocalDate? {
        if (gate.isBlank()) return null
        val text = passport.path("milestones")
            .firstOrNull { it.path("gate").asText() == gate }
            ?.path("due")?.asText("") ?: return null
        return runCatching { LocalDate.parse(text) }.getOrNull()
    }

    /**
     * Круг 4, нить потока: вход · выход · потребители одной задачи. Рамка
     * ведения перестаёт быть туннелем — видно, откуда пришёл и кого кормишь.
     * Собирается из тех же зависимостей, что рисуют схему: одна цепочка.
     */
    fun flowOf(task: TaskState, byId: Map<String, TaskState>): ObjectNode {
        val flow = mapper.createObjectNode()
        val inArr = flow.putArray("in")
        task.dependsOn.forEach { dep ->
            val pred = byId[dep.task] ?: return@forEach
            inArr.addObject()
                .put("kind", "task")
                .put("id", pred.id)
                .put("order", pred.order)
                .put("name", pred.name)
                .put("artifact", pred.artifact)
                .put("link", dep.type)
                .put("link_words", linkWords(dep.type, "${pred.order} · ${pred.name}"))
                .put("ready", pred.outputDone)
        }
        task.inputs.forEach { c ->
            inArr.addObject().put("kind", "condition").put("name", c.label).put("ready", c.ready)
        }
        val out = flow.putObject("out")
        out.put("artifact", task.artifact)
        task.documentCode?.let { out.put("document_code", it) }
        task.gate?.let { out.put("gate", it) }
        task.maturity?.let { out.put("maturity", it) }
        out.put("ready", task.outputDone)
        out.put(
            "state",
            when {
                task.outputDone -> if (task.maturity == "baseline") "базирован" else "готов"
                else -> "не готов"
            },
        )
        val cons = flow.putArray("consumers")
        task.consumers.mapNotNull { byId[it] }.forEach { next ->
            cons.addObject()
                .put("kind", "task")
                .put("id", next.id)
                .put("order", next.order)
                .put("name", next.name)
        }
        task.gate?.let { g ->
            cons.addObject().put("kind", "gate").put("gate", g).put("name", "пакет $g")
        }
        return flow
    }

    fun toJson(boundary: Boundary, projectId: String): ObjectNode {
        val tasks = of(boundary, projectId)
        val byId = tasks.associateBy { it.id }
        val passport = boundary.objects.current(projectId)?.doc ?: mapper.createObjectNode()
        val out = mapper.createObjectNode()
        // Пустота обязана объяснять себя (правило-класс: пустой раздел —
        // приглашение, а не голый ноль). Задач фазы проекта может не быть
        // потому, что полка наполнена другой фазой, — так и скажем.
        if (tasks.isEmpty()) {
            val phase = passport.path("phase").asText("")
            val onShelf = boundary.objects.listCurrent(ObjectStore.LIBRARY_PROJECT)
                .filter { it.type == "phase_task" && it.status.name != "Cancelled" }
                .map { it.doc.path("phase").asText() }
                .distinct()
                .sorted()
            out.put("phase", phase)
            out.put(
                "empty_why",
                when {
                    onShelf.isEmpty() ->
                        "задач фазы на полке нет вовсе — залейте пакет задач регламента"
                    phase !in onShelf ->
                        "проект в фазе «$phase», а на полке наполнены задачи: " +
                            onShelf.joinToString(", ") +
                            ". Задачи этой фазы собираются той же схемой — наполнение регламентом ещё не сделано"
                    else -> "задач фазы «$phase» на полке нет"
                },
            )
        }
        out.put("tasks", tasks.size)
        out.put("in_progress", tasks.count { it.status == "in_progress" })
        out.put("available", tasks.count { it.status == "available" })
        out.put("waiting", tasks.count { it.status == "waiting" })
        out.put("done", tasks.count { it.status == "done" })
        // «следующий шаг» шапки — верхушка работы: первая незавершённая
        val next = tasks.firstOrNull { it.status == "in_progress" }
            ?: tasks.firstOrNull { it.status == "available" }
            ?: tasks.firstOrNull { it.status != "done" }
        next?.let { t ->
            val step = t.steps.firstOrNull { !it.done }
            val n = out.putObject("next")
            n.put("task", t.id)
            n.put("name", t.name)
            step?.let {
                n.put("step", it.title)
                it.screen?.let { s -> n.put("screen", s) }
                it.kind?.let { k -> n.put("kind", k) }
                it.documentCode?.let { c -> n.put("document_code", c) }
                it.tally?.let { t -> n.put("tally", t) }
            }
        }
        val arr = out.putArray("items")
        tasks.forEach { t ->
            val n = arr.addObject()
            n.put("id", t.id)
            n.put("order", t.order)
            n.put("name", t.name)
            n.put("why", t.why)
            n.put("status", t.status)
            t.waitsOn?.let { n.put("waits_on", it) }
            n.put("input_ready", t.inputReady)
            n.put("input_why", t.inputWhy)
            n.put("artifact", t.artifact)
            t.gate?.let { n.put("gate", it) }
            n.put("output_done", t.outputDone)
            t.start?.let { n.put("start", it.toString()) }
            t.end?.let { n.put("end", it.toString()) }
            n.put("tier", t.tier)
            n.put("tiers", t.tiers)
            // Круг 5: план задачи — намерение руководителя, показанное рядом с
            // задачей. На статус он НЕ влияет: статус выше посчитан без него.
            passport.path("work_plan").firstOrNull { it.path("task").asText() == t.id }?.let { p ->
                n.putObject("plan")
                    .put("start", p.path("start").asText(""))
                    .put("end", p.path("end").asText(""))
                    .put("author", p.path("author").asText(""))
            }
            // Круг 5: план задачи — намерение руководителя, показанное рядом с
            // задачей. На статус он НЕ влияет: статус выше посчитан без него.
            passport.path("work_plan").firstOrNull { it.path("task").asText() == t.id }?.let { p ->
                n.putObject("plan")
                    .put("start", p.path("start").asText(""))
                    .put("end", p.path("end").asText(""))
                    .put("author", p.path("author").asText(""))
            }
            val steps = n.putArray("steps")
            t.steps.forEachIndexed { i, s ->
                val sn = steps.addObject()
                sn.put("index", i)
                sn.put("tier", s.tier)
                sn.put("tiers", s.tiers)
                val after = sn.putArray("after")
                s.after.forEach { a ->
                    // связь может уходить в чужую задачу — тогда и называем её чужой
                    val чужая = a.task?.let { id -> tasks.firstOrNull { it.id == id } }
                    val имя = if (a.task == null) t.steps.getOrNull(a.step - 1)?.title ?: "шаг ${a.step}"
                    else чужая?.let { задача ->
                        "${задача.order}.${a.step} · ${задача.steps.getOrNull(a.step - 1)?.title ?: "шаг ${a.step}"}"
                    } ?: "${a.task} шаг ${a.step}"
                    val узел = after.addObject()
                        .put("step", a.step - 1)
                        .put("type", a.type)
                        .put("words", linkWords(a.type, "«$имя»"))
                    a.task?.let { узел.put("task", it) }
                }
                sn.put("title", s.title)
                s.hint?.let { sn.put("hint", it) }
                s.screen?.let { sn.put("screen", it) }
                s.kind?.let { sn.put("kind", it) }
                s.documentCode?.let { sn.put("document_code", it) }
                s.tally?.let { sn.put("tally", it) }
                sn.put("done", s.done)
                sn.put("why", s.why)
            }
            val gaps = n.putArray("gaps")
            t.gaps.forEach { g ->
                val узел = gaps.addObject()
                    .put("id", g.id)
                    .put("title", g.title)
                    .put("note", g.note)
                    .put("gate", g.gate)
                g.place?.let { узел.put("place", it) }
            }
            n.set<ObjectNode>("flow", flowOf(t, byId))
        }
        return out
    }
}
