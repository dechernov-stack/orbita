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
//   · окна ленты — только производные от дат вех и зависимостей: старт —
//     готовность входа либо дедлайн предшественника, конец — дата точки, к
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

    /** До точки меньше этого — окно считается сжатым (разрыв «цепочка не успевает»). */
    private const val TIGHT_DAYS = 7L

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
    )

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
        val gaps: List<String>,
        val artifact: String,
        val gate: String?,
        val outputDone: Boolean,
        val start: LocalDate?,
        val end: LocalDate?,
        val tight: Boolean,
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
        val dependsOn: List<String>,
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
            val n = own.count { it.type == type }
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
            val field = condition.path("field").asText("")
            if (passport.path(field).isMissingNode) null else "задано"
        }
        else -> null
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
            own.count { it.type == type } >= min
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
                "objects" -> "нужны объекты вида «${condition.path("type").asText()}»"
                "passport_field" -> "нужно поле паспорта «${condition.path("field").asText()}»"
                "taken_from_library" -> "нужен набор библиотеки «${condition.path("type").asText()}»"
                "gate_check" -> "нужна закрытая проверка «${condition.path("gate_check_id").asText()}»"
                "document_issued" -> "нужен выпущенный документ «${condition.path("code").asText()}»"
                else -> "условие не названо"
            }
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
            val steps = doc.path("steps").map { st ->
                StepState(
                    title = st.path("title").asText(),
                    hint = st.path("hint").asText("").ifBlank { null },
                    screen = st.path("screen").asText("").ifBlank { null },
                    kind = st.path("kind").asText("").ifBlank { null },
                    documentCode = st.path("document_code").asText("").ifBlank { null },
                    tally = tally(st.path("done_when"), own, passport, issued),
                    done = holds(st.path("done_when"), own, passport, gateChecks, issued),
                    why = labelOf(st.path("done_when")),
                )
            }
            // предшественник, чей выход ещё не готов, — вот кого ждём
            val waiting = doc.path("depends_on").mapNotNull { byId[it.asText()] }
                .firstOrNull { pred ->
                    val out = pred.doc.path("output").path("done_when")
                    !out.isMissingNode && !holds(out, own, passport, gateChecks, issued)
                }
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
                .map { "${it.title}: ${it.note}" }
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
                waitsOn = waiting?.let { "${it.doc.path("order").asInt()} · ${it.doc.path("name").asText()}" }
                    ?: unmet.firstOrNull()?.let { labelOf(it) },
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
                tight = end != null && !outputDone && tightness(end),
                tier = 1,
                tiers = 1,
                dependsOn = doc.path("depends_on").map { it.asText() }.filter { it in byId },
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
            t.copy(consumers = states.values.filter { t.id in it.dependsOn }.map { it.id })
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
        val зависит = tasks.associate { t ->
            t.id to (byId[t.id]?.doc?.path("depends_on")?.map { it.asText() } ?: emptyList())
                .filter { it in порядок }
        }
        // ярус = 1 + максимум ярусов предшественников (цикл невозможен:
        // сторож сида держит зависимости внутри фазы и по возрастанию)
        val ярусы = HashMap<String, Int>()
        fun ярус(id: String, глубина: Int = 0): Int = ярусы.getOrPut(id) {
            if (глубина > tasks.size) 1
            else (зависит[id].orEmpty().maxOfOrNull { ярус(it, глубина + 1) + 1 } ?: 1)
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

    /** Окно сжато: до точки осталось меньше порога, а выход не готов. */
    private fun tightness(end: LocalDate): Boolean =
        java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), end) in 0 until TIGHT_DAYS

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
        task.dependsOn.mapNotNull { byId[it] }.forEach { pred ->
            inArr.addObject()
                .put("kind", "task")
                .put("id", pred.id)
                .put("order", pred.order)
                .put("name", pred.name)
                .put("artifact", pred.artifact)
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
        // Геометрия ленты — тоже расчёт, и место ему на сервере: клиент
        // рисует полосу по готовым долям, а не делит даты сам.
        // Интервал ленты — МЕЖВЕХОВОЙ: от сегодняшнего дня (или от самой
        // ранней точки, если она уже позади) до последней точки фазы. Брать
        // его из дат самих задач нельзя: у задач одной точки дата одна, и
        // интервал схлопывался в ноль — отсюда и полосы-чёрточки.
        val gates = tasks.mapNotNull { it.end }
        val from = listOfNotNull(gates.minOrNull(), LocalDate.now()).minOrNull()
        val to = gates.maxOrNull()
        val span = if (from != null && to != null)
            java.time.temporal.ChronoUnit.DAYS.between(from, to).coerceAtLeast(1) else 1L
        val out = mapper.createObjectNode()
        // Пустота обязана объяснять себя (правило-класс: пустой раздел —
        // приглашение, а не голый ноль). Задач фазы проекта может не быть
        // потому, что полка наполнена другой фазой, — так и скажем.
        if (tasks.isEmpty()) {
            val passport = boundary.objects.current(projectId)?.doc ?: mapper.createObjectNode()
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
        from?.let { out.put("lane_from", it.toString()) }
        to?.let { out.put("lane_to", it.toString()) }
        // Круг 2: шкала ленты — вехи ◆ именами и линия «сегодня». Положения
        // на шкале считает сервер: клиент не вычисляет (правило обхода).
        if (from != null && to != null) {
            val вехи = out.putArray("scale")
            passport.path("milestones").forEach { m ->
                val gate = m.path("gate").asText("")
                val due = runCatching { LocalDate.parse(m.path("due").asText("")) }.getOrNull()
                if (gate.isNotBlank() && due != null && !due.isBefore(from) && !due.isAfter(to)) {
                    val сдвиг = java.time.temporal.ChronoUnit.DAYS.between(from, due)
                    вехи.addObject()
                        .put("gate", gate)
                        .put("date", due.toString())
                        .put("at_pct", (сдвиг * 100.0 / span).coerceIn(0.0, 100.0))
                }
            }
            val сегодня = LocalDate.now()
            if (!сегодня.isBefore(from) && !сегодня.isAfter(to)) {
                val сдвиг = java.time.temporal.ChronoUnit.DAYS.between(from, сегодня)
                out.put("today_pct", (сдвиг * 100.0 / span).coerceIn(0.0, 100.0))
                out.put("today", сегодня.toString())
            }
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
            n.put("tight", t.tight)
            n.put("tier", t.tier)
            n.put("tiers", t.tiers)
            // Круг 2: окно — ДОЛЯ ЯРУСА внутри интервала до своей точки.
            // Прежняя раскладка брала дату предшественника, а у задач одной
            // точки она совпадала с датой выхода — полоса вырождалась в
            // чёрточку. Доли считает сервер: в клиенте расчётов нет.
            if (from != null && t.end != null) {
                val доТочки = java.time.temporal.ChronoUnit.DAYS.between(from, t.end)
                val ширинаИнтервала = (доТочки * 100.0 / span).coerceIn(0.0, 100.0)
                val доля = ширинаИнтервала / t.tiers
                n.put("lane_offset_pct", (доля * (t.tier - 1)).coerceIn(0.0, 100.0))
                n.put("lane_width_pct", доля.coerceIn(2.0, 100.0))
                // Границы доли ДАТАМИ — они и стоят в подсказке полосы.
                // Проценты нормированы лентой и на сдвиг точки не отвечают;
                // даты отвечают, и по ним видно, что сетка растянулась.
                val днейНаЯрус = доТочки.toDouble() / t.tiers
                val началоДоли = from.plusDays((днейНаЯрус * (t.tier - 1)).toLong())
                val конецДоли = from.plusDays((днейНаЯрус * t.tier).toLong())
                n.put("lane_start", началоДоли.toString())
                n.put("lane_end", конецДоли.toString())
            }
            val steps = n.putArray("steps")
            t.steps.forEach { s ->
                val sn = steps.addObject()
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
            t.gaps.forEach { gaps.add(it) }
            n.set<ObjectNode>("flow", flowOf(t, byId))
        }
        return out
    }
}
