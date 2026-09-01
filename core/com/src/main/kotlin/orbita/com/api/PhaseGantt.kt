// Круг 5 «Работы»: настоящий Гант. Полотно рисует БИБЛИОТЕКА (frappe-gantt
// 1.2.2, MIT) — полосы, стрелки зависимостей, перетаскивание дат, шкала и
// режимы День/Неделя/Месяц из коробки. Решение владельца: самострой SVG не
// пишем; здесь — только данные и правила поверх.
//
// Наше в этом файле:
//   · ПЛАН руководителя — источник дат полосы. План есть → полоса по плану;
//     плана нет → расчётная сетка прежних долей, серым классом и с честной
//     подписью «план не задан» (ловушка 3: фикция не выдаётся за план);
//   · «план против факта» — предупреждение считает сервер, на полосе оно
//     становится классом-окантовкой; ждущая задача с далёким дедлайном
//     красной не бывает;
//   · план НЕ влияет на статусы (ловушка 1): статусы посчитаны PhaseWork из
//     состояния проекта и о плане не знают;
//   · автосдвига соседей нет (ловушка 2): конфликт плана со стрелкой
//     зависимости подсвечивается классом, решает человек;
//   · процентов выполнения не существует (ловушка 4): progress всегда 0.
package orbita.com.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.model.CoreType
import java.time.LocalDate
import java.time.temporal.ChronoUnit

object PhaseGantt {

    private val mapper = ObjectMapper()

    /** Без плана «не успевает» считается по-старому: точка ближе недели. */
    private const val TIGHT_DAYS = 7L

    private data class Plan(val start: LocalDate, val end: LocalDate, val author: String)

    private fun plansOf(passport: JsonNode): Map<String, Plan> =
        passport.path("work_plan").mapNotNull { p ->
            val task = p.path("task").asText("")
            val start = runCatching { LocalDate.parse(p.path("start").asText()) }.getOrNull()
            val end = runCatching { LocalDate.parse(p.path("end").asText()) }.getOrNull()
            if (task.isBlank() || start == null || end == null) null
            else task to Plan(start, end, p.path("author").asText(""))
        }.toMap()

    private fun milestonesOf(passport: JsonNode): List<Pair<String, LocalDate>> =
        passport.path("milestones").mapNotNull { m ->
            val gate = m.path("gate").asText("")
            val due = runCatching { LocalDate.parse(m.path("due").asText()) }.getOrNull()
            if (gate.isBlank() || due == null) null else gate to due
        }

    /**
     * Строки полотна в форме, которую библиотека принимает как есть
     * (`id · name · start · end · dependencies · custom_class · progress`),
     * плюс наши поля для попапа: они не нужны библиотеке и ей не мешают.
     */
    fun toJson(
        boundary: Boundary,
        projectId: String,
        login: String? = null,
        expand: Set<String> = emptySet(),
    ): ObjectNode {
        val out = mapper.createObjectNode()
        val passport = boundary.objects.current(projectId)?.doc ?: mapper.createObjectNode()
        val tasks = PhaseWork.of(boundary, projectId)
        // Право на план: план работ фазы ведёт руководитель проекта. При
        // выключенных учётках права не спрашиваются — стенд одноголовый.
        val canPlan = login == null || boundary.auth.roleIn(projectId, login) == "lead"
        out.put("can_plan", canPlan)
        out.put("right", "план работ фазы ведёт руководитель проекта")
        if (tasks.isEmpty()) {
            out.put("empty_why", "задач фазы на полке нет — план ставить нечему")
            out.putArray("tasks")
            return out
        }
        val plans = plansOf(passport)
        val milestones = milestonesOf(passport)
        val today = LocalDate.now()
        val byId = tasks.associateBy { it.id }

        // Интервал сетки — тот же, что делил ленту: от «сегодня» либо самой
        // ранней точки до последней точки фазы. Он нужен ТОЛЬКО задачам без
        // плана: у планов даты свои.
        val gateDates = tasks.mapNotNull { t -> t.gate?.let { g -> milestones.firstOrNull { it.first == g }?.second } }
        val gridFrom = (gateDates + listOf(today)).min()

        // ---- связи с типами: библиотека рисует одну стрелку, тип приходит
        // отдельно — им красится стрелка и объясняется попап (круг 6).
        // Конфликт плана считается ПО ТИПУ: у SS сравниваются старты, у FF —
        // окончания, у FS — старт преемника с концом предшественника, а INPUT
        // сроков не касается вовсе: это условие готовности, а не срок.
        val links = out.putArray("links")
        val конфликтные = HashSet<String>()
        tasks.forEach { t ->
            t.dependsOn.forEach { dep ->
                val pred = byId[dep.task] ?: return@forEach
                val a = plans[pred.id]
                val b = plans[t.id]
                val конфликт = a != null && b != null && when (dep.type) {
                    "FS" -> b.start < a.end
                    "SS" -> b.start < a.start
                    "FF" -> b.end < a.end
                    else -> false
                }
                if (конфликт) { конфликтные += t.id; конфликтные += pred.id }
                links.addObject()
                    .put("from", pred.id)
                    .put("to", t.id)
                    .put("type", dep.type)
                    .put("conflict", конфликт)
                    .put(
                        "words",
                        PhaseWork.linkWords(dep.type, "${pred.order} · ${pred.name}"),
                    )
                    .put("note", dep.note ?: "")
            }
        }

        val arr = out.putArray("tasks")
        tasks.forEach { t ->
            val plan = plans[t.id]
            val окно = window(t, plan, gridFrom, milestones, today)
            val alarm = alarmOf(t, plan, today, milestones)
            val конфликт = t.id in конфликтные
            val n = arr.addObject()
            n.put("id", t.id)
            n.put("name", "${t.order} · ${t.name}")
            n.put("start", окно.first.toString())
            n.put("end", окно.second.toString())
            n.put("progress", 0)
            n.put("dependencies", t.dependsOn.joinToString(",") { it.task })
            n.put("custom_class", cssClass(t, plan != null, alarm != null, конфликт))
            // ---- наше, для попапа: слова «ждёт: …» живут здесь, не в подписи
            n.put("kind", "task")
            n.put("order", t.order)
            n.put("title", t.name)
            n.put("status", t.status)
            n.put("status_text", statusText(t))
            n.put("why", t.why)
            n.put("planned", plan != null)
            plan?.let { n.put("plan_author", it.author) }
            n.put("gaps", t.gaps.size)
            n.put("steps_done", t.steps.count { it.done })
            n.put("steps_total", t.steps.size)
            n.put("tier", t.tier)
            n.put("tiers", t.tiers)
            t.gate?.let { n.put("gate", it) }
            t.waitsOn?.let { n.put("waits_on", it) }
            n.put("artifact", t.artifact)
            alarm?.let { n.put("alarm", it) }
            n.put("conflict", конфликт)
            n.put(
                "window_why",
                if (plan != null) "план: ${окно.first} — ${окно.second}, поставил ${plan.author}"
                else "план не задан — полоса показывает расчётную долю интервала до точки " +
                    "по порядку зависимостей (ярус ${t.tier} из ${t.tiers}). Потяните полосу, чтобы задать план",
            )
            n.put("expanded", t.id in expand)

            // ---- Круг 6: шаги — дочерними полосами при раскрытии. Библиотека
            // плоская, поэтому шаги идут обычными строками с отступом в имени и
            // своим классом. Планов шагам не заводят (ловушка 2): окно шага —
            // доля окна задачи по ЕГО ЯРУСУ, то есть порядок, а не сроки.
            if (t.id in expand && t.steps.isNotEmpty()) {
                val длина = ChronoUnit.DAYS.between(окно.first, окно.second).coerceAtLeast(1)
                val ярусов = t.steps.maxOf { it.tiers }.coerceAtLeast(1)
                t.steps.forEachIndexed { i, шаг ->
                    val начало = окно.first.plusDays(длина * (шаг.tier - 1) / ярусов)
                    val конец = окно.first.plusDays(длина * шаг.tier / ярусов)
                    // стрелку рисуем только у FS: SS-шаги идут вместе, и
                    // стрелка «после окончания» соврала бы о них
                    val предки = шаг.after.filter { it.type == "FS" }
                        .mapNotNull { a -> t.steps.getOrNull(a.step - 1)?.let { "${t.id}#${a.step - 1}" } }
                    val sn = arr.addObject()
                    sn.put("id", "${t.id}#$i")
                    sn.put("name", "— ${шаг.title}")
                    sn.put("start", начало.toString())
                    sn.put("end", if (конец.isAfter(начало)) конец.toString() else начало.plusDays(1).toString())
                    sn.put("progress", 0)
                    sn.put("dependencies", предки.joinToString(","))
                    sn.put("custom_class", if (шаг.done) "pw-step-done" else "pw-step")
                    sn.put("kind", "step")
                    sn.put("parent", t.id)
                    sn.put("step_index", i)
                    sn.put("title", шаг.title)
                    sn.put("done", шаг.done)
                    sn.put("tier", шаг.tier)
                    sn.put("tiers", ярусов)
                    шаг.hint?.let { sn.put("hint", it) }
                    шаг.tally?.let { sn.put("tally", it) }
                    sn.put("why", шаг.why)
                    val связи = sn.putArray("links")
                    шаг.after.forEach { a ->
                        val имя = t.steps.getOrNull(a.step - 1)?.title ?: "шаг ${a.step}"
                        связи.addObject()
                            .put("type", a.type)
                            .put("words", PhaseWork.linkWords(a.type, "«$имя»"))
                    }
                    sn.put(
                        "window_why",
                        "шаг ${шаг.tier} яруса из $ярусов: окно — доля окна задачи по порядку шагов. " +
                            "Это порядок, а не сроки: планов шагам не заводят" +
                            (if (шаг.after.isEmpty()) ". Связей с соседними шагами в полке нет — шаг начальный" else ""),
                    )
                }
            }
        }

        // Вехи — полосами нулевой длины: ромб рисует CSS по классу.
        // KDP-B обязана быть на полотне: точки берутся из паспорта целиком,
        // а не из дат задач.
        milestones.filter { (gate, _) -> tasks.any { it.gate == gate } }.forEach { (gate, due) ->
            val held = passport.path("milestones")
                .any { it.path("gate").asText() == gate && it.path("held").asBoolean(false) }
            arr.addObject()
                .put("id", "gate:$gate")
                .put("name", gate)
                .put("start", due.toString())
                .put("end", due.toString())
                .put("progress", 0)
                .put("dependencies", "")
                .put("custom_class", "pw-ms")
                .put("kind", "gate")
                .put("gate", gate)
                .put("held", held)
                .put("window_why", "точка $gate: $due" + if (held) " — пройдена" else "")
        }

        // Вертикали вех через всё полотно: колонки-подсветки библиотеки
        // (holidays), а не своя графика. Порядок обязан совпадать с лентой
        // цикла — расхождение дат называется конфликтом вех, а не молчится.
        val линии = out.putArray("milestone_lines")
        val вехиФазы = milestones.filter { (gate, _) -> tasks.any { it.gate == gate } }
        вехиФазы.forEach { (gate, due) -> линии.addObject().put("date", due.toString()).put("name", gate) }
        val порядокПаспорта = passport.path("milestones").map { it.path("gate").asText() }
        val поДате = вехиФазы.sortedBy { it.second }.map { it.first }
        val поЦиклу = вехиФазы.map { it.first }.sortedBy { порядокПаспорта.indexOf(it) }
        if (поДате != поЦиклу) {
            out.put(
                "gate_conflict",
                "конфликт вех: по датам точки идут ${поДате.joinToString(" → ")}, " +
                    "а по ленте цикла — ${поЦиклу.joinToString(" → ")}. Даты вех расходятся с порядком прохождения",
            )
        }

        out.put("planned", plans.keys.count { it in byId })
        out.put("total", tasks.size)
        return out
    }

    /**
     * Окно полосы: план, если он задан. Иначе — расчётная сетка прежних долей
     * (ярус зависимостей внутри интервала до своей точки). Сетка серая и
     * подписана: за план она себя не выдаёт.
     */
    private fun window(
        t: PhaseWork.TaskState,
        plan: Plan?,
        gridFrom: LocalDate,
        milestones: List<Pair<String, LocalDate>>,
        today: LocalDate,
    ): Pair<LocalDate, LocalDate> {
        if (plan != null) return plan.start to plan.end
        val gate = t.gate?.let { g -> milestones.firstOrNull { it.first == g }?.second }
            ?: today.plusDays(14)
        val дней = ChronoUnit.DAYS.between(gridFrom, gate).coerceAtLeast(t.tiers.toLong())
        val наЯрус = дней.toDouble() / t.tiers.coerceAtLeast(1)
        val начало = gridFrom.plusDays((наЯрус * (t.tier - 1)).toLong())
        val конец = gridFrom.plusDays((наЯрус * t.tier).toLong())
        return начало to if (конец.isAfter(начало)) конец else начало.plusDays(1)
    }

    /**
     * «План против факта». Красное — только предупреждение и только по делу:
     * задача не начата после планового старта либо выход не готов после
     * планового конца. Где плана нет — по-старому: точка ближе недели при
     * неготовом выходе. Ждущая задача с далёким дедлайном красной не бывает.
     */
    private fun alarmOf(
        t: PhaseWork.TaskState,
        plan: Plan?,
        today: LocalDate,
        milestones: List<Pair<String, LocalDate>>,
    ): String? {
        if (t.outputDone) return null
        if (plan != null) {
            if (today > plan.end) return "выход не готов, а плановый конец ${plan.end} позади"
            if (today > plan.start && t.steps.none { it.done }) {
                return "плановый старт ${plan.start} позади, а работа не начата"
            }
            return null
        }
        val gate = t.gate?.let { g -> milestones.firstOrNull { it.first == g }?.second } ?: return null
        val дней = ChronoUnit.DAYS.between(today, gate)
        return when {
            дней < 0 -> "точка ${t.gate} позади, а выход не готов"
            дней < TIGHT_DAYS -> "до точки ${t.gate} осталось дней: $дней, а выход не готов; плана у задачи нет"
            else -> null
        }
    }

    /**
     * Один класс на полосу: библиотека кладёт его через classList.add, и
     * строка с пробелами её сломала бы. Поэтому состояние кодируется одним
     * словом, а порядок важности такой: конфликт → нет плана → предупреждение
     * → статус.
     */
    private fun cssClass(t: PhaseWork.TaskState, planned: Boolean, alarm: Boolean, conflict: Boolean): String =
        when {
            conflict -> "pw-conflict"
            !planned && alarm -> "pw-grid-alarm"
            !planned -> "pw-grid"
            alarm -> "pw-${t.status}-alarm"
            else -> "pw-${t.status}"
        }

    private fun statusText(t: PhaseWork.TaskState): String = when (t.status) {
        "done" -> "выполнена"
        "in_progress" -> t.steps.count { it.done }.let { сделано ->
            if (сделано < t.steps.size) "в работе · шаг ${сделано + 1} из ${t.steps.size}"
            else "в работе · шаги пройдены, выход не готов"
        }
        "waiting" -> "ожидает"
        else -> "доступна"
    }

    /**
     * Постановка плана задачи. Даты приходят датами: их даёт библиотека из
     * перетаскивания полосы либо инженер полями карточки — считать пиксели
     * клиенту не приходится.
     *
     * Соседей не двигаем: конфликт со стрелкой зависимости показывается, а
     * решает человек.
     */
    fun plan(
        boundary: Boundary,
        projectId: String,
        request: JsonNode,
        author: String,
        login: String? = null,
        expand: Set<String> = emptySet(),
    ): ObjectNode {
        require(author.isNotBlank()) { "TZ-COM-005: field 'author' is required for editing" }
        val task = request.path("task").asText("")
        require(task.isNotBlank()) { "нужно поле 'task' — какой задаче ставится план" }
        require('#' !in task) { "планов шагам не заводят: сроки — у задач и вех, у шагов — порядок" }
        val known = PhaseWork.of(boundary, projectId).map { it.id }.toSet()
        require(task in known) { "задача '$task' не принадлежит работам текущей фазы" }
        if (login != null && boundary.auth.roleIn(projectId, login) != "lead") {
            throw RightDeniedException(
                "план работ фазы ведёт руководитель проекта (ваша роль — " +
                    "${boundary.auth.roleIn(projectId, login) ?: "без роли в проекте"})",
            )
        }
        val passport = boundary.objects.current(projectId)
            ?: throw NoSuchElementException("project '$projectId' not found")
        val текущие = (passport.doc.path("work_plan").deepCopy<JsonNode>() as? ArrayNode)
            ?: mapper.createArrayNode()
        val остальные = mapper.createArrayNode()
        текущие.forEach { p -> if (p.path("task").asText() != task) остальные.add(p) }

        val снять = request.path("clear").asBoolean(false)
        if (!снять) {
            val start = LocalDate.parse(request.path("start").asText())
            val end = LocalDate.parse(request.path("end").asText())
            require(!end.isBefore(start)) { "плановый конец раньше начала: $start — $end" }
            остальные.addObject()
                .put("task", task)
                .put("start", start.toString())
                .put("end", end.toString())
                .put("author", author)
                .put("at", java.time.OffsetDateTime.now().toString())
        }
        val changes = mapper.createObjectNode()
        changes.set<ArrayNode>("work_plan", остальные)
        boundary.editing.update(
            CoreType.Project, projectId, changes, passport.version, author,
            changeRef = if (снять) "план работ фазы: план задачи $task снят"
            else "план работ фазы: задаче $task поставлен план",
        )
        return toJson(boundary, projectId, login, expand)
    }

    /** Право не подошло: отказ обязан называть право, а не просто запрещать. */
    class RightDeniedException(message: String) : RuntimeException(message)
}
