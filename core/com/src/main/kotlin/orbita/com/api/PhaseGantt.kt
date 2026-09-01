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
    fun toJson(boundary: Boundary, projectId: String, login: String? = null): ObjectNode {
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

        val arr = out.putArray("tasks")
        tasks.forEach { t ->
            val plan = plans[t.id]
            val окно = window(t, plan, gridFrom, milestones, today)
            val alarm = alarmOf(t, plan, today, milestones)
            val конфликт = t.dependsOn.any { pred ->
                val p = plans[pred]
                p != null && plan != null && plan.start < p.end
            } || tasks.any { next ->
                // конфликт виден с обеих сторон стрелки: и у предшественника
                next.dependsOn.contains(t.id) && plan != null && plans[next.id] != null &&
                    plans.getValue(next.id).start < plan.end
            }
            val n = arr.addObject()
            n.put("id", t.id)
            n.put("name", "${t.order} · ${t.name}")
            n.put("start", окно.first.toString())
            n.put("end", окно.second.toString())
            n.put("progress", 0)
            n.put("dependencies", t.dependsOn.joinToString(","))
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
    ): ObjectNode {
        require(author.isNotBlank()) { "TZ-COM-005: field 'author' is required for editing" }
        val task = request.path("task").asText("")
        require(task.isNotBlank()) { "нужно поле 'task' — какой задаче ставится план" }
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
        return toJson(boundary, projectId, login)
    }

    /** Право не подошло: отказ обязан называть право, а не просто запрещать. */
    class RightDeniedException(message: String) : RuntimeException(message)
}
