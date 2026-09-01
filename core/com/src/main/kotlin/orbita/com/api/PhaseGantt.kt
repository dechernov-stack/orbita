// Круг 5 «Работы»: настоящий Гант. Полотно рисует БИБЛИОТЕКА (frappe-gantt
// 1.2.2, MIT) — полосы, стрелки зависимостей, перетаскивание дат, шкала и
// режимы День/Неделя/Месяц из коробки. Решение владельца: самострой SVG не
// пишем; здесь — только данные и правила поверх.
//
// Круг 6 добавил типы связей (FS · SS · FF · INPUT) — они приходят с полки и
// решают, чего задача ждёт, каков её ярус и что считается конфликтом плана.
//
// Круг 7 переложил модель времени на ТОЧКИ, а строки — на шаги:
//   · фаза делится точками на интервалы: начало фазы → SRR → SDR → KDP-B.
//     Задача без плана размечается ВНУТРИ интервала своей точки, поэтому
//     ромб оказывается в конце интервала по построению, а не по удаче с датой;
//   · шаги — полноправные строки «N.M», развёрнутые по умолчанию;
//   · план ставится ШАГУ, а полоса задачи с шагами — СВОДНАЯ: она вычисляется
//     из шагов и не тянется. План задачи остаётся только у задач без шагов;
//   · задача, чьё окно уходит за свою точку, — конфликт: подсвечены обе,
//     двигает человек.
//
// Круг 8 снял запрет на проценты — но только для ВЫЧИСЛЕННЫХ: progress задачи
// это доля закрытых шагов, руками его не двигают. И добавил ответственного за
// работу (поле проекта, назначает руководитель) и длительность рабочими днями,
// которая считается из плана, а не вводится оценкой.
//
// Что не изменилось: план не влияет на статусы (ловушка 1), автосдвига
// соседей нет (ловушка 2), расчётная сетка не выдаётся за план (ловушка 3),
// ручного процента не существует (ловушка 1 круга 8).
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

    /** Если начала фазы взять неоткуда — интервал первой точки такой. */
    private const val FALLBACK_PHASE_DAYS = 14L

    private data class Plan(val start: LocalDate, val end: LocalDate, val author: String)

    private data class Window(val start: LocalDate, val end: LocalDate, val planned: Boolean)

    /** Ответственный за работу: кто ведёт задачу либо её шаг. */
    private data class Assignee(val who: String, val author: String)

    private fun assigneesOf(passport: JsonNode): Map<String, Assignee> =
        passport.path("work_assignees").mapNotNull { a ->
            val task = a.path("task").asText("")
            val who = a.path("who").asText("")
            if (task.isBlank() || who.isBlank()) null
            else task to Assignee(who, a.path("author").asText(""))
        }.toMap()

    /**
     * Длительность — РАБОЧИМИ днями и только из окна: это не оценка, а мера
     * того, что уже стоит на полотне. Считаем включительно: план на один день
     * длится один день.
     */
    private fun рабочихДней(окно: Window): Int {
        var d = окно.start
        var n = 0
        while (!d.isAfter(окно.end)) {
            if (d.dayOfWeek.value <= 5) n += 1
            d = d.plusDays(1)
        }
        return n
    }

    /** Конец плана по длительности: N рабочих дней от старта включительно. */
    private fun концаПоДлительности(start: LocalDate, дней: Int): LocalDate {
        var d = start
        var осталось = дней.coerceAtLeast(1)
        if (d.dayOfWeek.value <= 5) осталось -= 1
        while (осталось > 0) {
            d = d.plusDays(1)
            if (d.dayOfWeek.value <= 5) осталось -= 1
        }
        return d
    }

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
     *
     * [collapse] — задачи, чьи шаги свёрнуты. По умолчанию развёрнуты все:
     * иерархия строк — это и есть полотно, а не выпадающая подробность.
     */
    fun toJson(
        boundary: Boundary,
        projectId: String,
        login: String? = null,
        collapse: Set<String> = emptySet(),
    ): ObjectNode {
        val out = mapper.createObjectNode()
        val passport = boundary.objects.current(projectId)?.doc ?: mapper.createObjectNode()
        val tasks = PhaseWork.of(boundary, projectId)
        val canPlan = login == null || boundary.auth.roleIn(projectId, login) == "lead"
        out.put("can_plan", canPlan)
        out.put("right", "план работ фазы ведёт руководитель проекта")
        if (tasks.isEmpty()) {
            out.put("empty_why", "задач фазы на полке нет — план ставить нечему")
            out.putArray("tasks")
            return out
        }
        val plans = plansOf(passport)
        val assignees = assigneesOf(passport)
        val milestones = milestonesOf(passport)
        val today = LocalDate.now()
        val byId = tasks.associateBy { it.id }

        // ---- Интервалы фазы: начало фазы → точка → точка → точка.
        // Точка закрывает свой интервал по построению, поэтому ромб всегда
        // оказывается в его конце, а не там, куда пришлась дата.
        val gates = tasks.mapNotNull { it.gate }.distinct()
            .mapNotNull { g -> milestones.firstOrNull { it.first == g }?.let { g to it.second } }
            .sortedBy { it.second }
        val phaseStart = phaseStart(boundary, projectId, passport, gates.firstOrNull()?.second)
        val intervals = HashMap<String, Pair<LocalDate, LocalDate>>()
        var от = phaseStart
        gates.forEach { (gate, due) ->
            intervals[gate] = от to due
            от = due
        }
        out.put("phase_start", phaseStart.toString())
        val сетка = out.putArray("intervals")
        gates.forEach { (gate, due) ->
            сетка.addObject()
                .put("gate", gate)
                .put("from", intervals.getValue(gate).first.toString())
                .put("to", due.toString())
        }

        // ---- Окна: базовое окно задачи, окна её шагов, сводная полоса
        val базовые = HashMap<String, Window>()
        val окнаШагов = HashMap<String, List<Window>>()
        val сводные = HashMap<String, Window>()
        tasks.forEach { t ->
            val база = baseWindow(t, plans[t.id], intervals, today)
            базовые[t.id] = база
            val шаги = t.steps.mapIndexed { i, шаг ->
                val план = plans["${t.id}#$i"]
                if (план != null) Window(план.start, план.end, true)
                else доляЯруса(база, шаг.tier, t.steps.maxOf { it.tiers })
            }
            окнаШагов[t.id] = шаги
            сводные[t.id] = if (шаги.isEmpty()) база
            else Window(шаги.minOf { it.start }, шаги.maxOf { it.end }, шаги.any { it.planned })
        }

        // ---- Связи с типами (круг 6). Конфликт плана считается по типу связи
        // и по ДЕЙСТВУЮЩИМ окнам: план мог приехать с шагов, а не с задачи.
        val links = out.putArray("links")
        val конфликтныеСвязи = HashSet<String>()
        tasks.forEach { t ->
            t.dependsOn.forEach { dep ->
                val pred = byId[dep.task] ?: return@forEach
                val a = сводные.getValue(pred.id)
                val b = сводные.getValue(t.id)
                val естьПлан = a.planned || b.planned
                val конфликт = естьПлан && when (dep.type) {
                    "FS" -> b.start < a.end
                    "SS" -> b.start < a.start
                    "FF" -> b.end < a.end
                    else -> false
                }
                if (конфликт) { конфликтныеСвязи += t.id; конфликтныеСвязи += pred.id }
                links.addObject()
                    .put("from", pred.id)
                    .put("to", t.id)
                    .put("type", dep.type)
                    .put("conflict", конфликт)
                    .put("words", PhaseWork.linkWords(dep.type, "${pred.order} · ${pred.name}"))
                    .put("note", dep.note ?: "")
            }
        }

        // ---- Строки: задача, под ней её шаги. Порядок массива и есть порядок
        // строк полотна: библиотека рисует, что дано.
        val arr = out.putArray("tasks")
        val точкиСКонфликтом = HashSet<String>()
        tasks.forEach { t ->
            val окно = сводные.getValue(t.id)
            val планЗадачи = plans[t.id]
            val естьШаги = t.steps.isNotEmpty()
            val точка = t.gate?.let { g -> milestones.firstOrNull { it.first == g }?.second }
            // Круг 7: задача не может кончаться позже точки, к которой зреет
            val заТочкой = точка != null && окно.end.isAfter(точка)
            if (заТочкой) t.gate?.let { точкиСКонфликтом += it }
            val alarm = alarmOf(t, окно, точка, today)
            val конфликт = t.id in конфликтныеСвязи || заТочкой

            val n = arr.addObject()
            n.put("id", t.id)
            n.put("name", "${t.order} · ${t.name}")
            n.put("start", окно.start.toString())
            n.put("end", окно.end.toString())
            // Круг 8: прогресс ВЫЧИСЛЯЕТСЯ — доля закрытых шагов, а у задачи
            // без шагов 0 или 100 по вычисленному статусу. Руками его не
            // двигают: ручного процента не существует по-прежнему.
            val сделано = t.steps.count { it.done }
            n.put(
                "progress",
                if (естьШаги) сделано * 100 / t.steps.size else if (t.outputDone) 100 else 0,
            )
            n.put(
                "progress_why",
                if (естьШаги) "шагов закрыто: $сделано из ${t.steps.size} — процент вычислен, не выставлен"
                else if (t.outputDone) "выход готов — 100% по вычисленному статусу"
                else "выход не готов — 0% по вычисленному статусу",
            )
            n.put("dependencies", t.dependsOn.joinToString(",") { it.task })
            n.put("custom_class", cssClass(t, окно.planned, alarm != null, конфликт, естьШаги))
            n.put("kind", "task")
            n.put("order", t.order)
            n.put("title", t.name)
            n.put("status", t.status)
            n.put("status_text", statusText(t))
            n.put("why", t.why)
            n.put("planned", окно.planned)
            n.put("summary", естьШаги)
            n.put("collapsed", t.id in collapse)
            планЗадачи?.let { n.put("plan_author", it.author) }
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
            if (заТочкой && точка != null) {
                n.put(
                    "gate_overrun",
                    "задача ${t.order} заканчивается ${окно.end} — после точки ${t.gate} ($точка). " +
                        "Систему это не двигает: сдвиньте план или дату точки",
                )
            }
            n.put("window_why", windowWhy(t, окно, планЗадачи, естьШаги, intervals))
            n.put("duration_days", рабочихДней(окно))
            n.put("duration_planned", окно.planned)
            assignees[t.id]?.let {
                n.put("assignee", it.who)
                n.put("assignee_own", true)
            }

            if (естьШаги && t.id !in collapse) {
                t.steps.forEachIndexed { i, шаг ->
                    val w = окнаШагов.getValue(t.id)[i]
                    val планШага = plans["${t.id}#$i"]
                    // связи шагов рисуются ВСЕ: параллельность видна глазами
                    // связь может уходить в чужую задачу — стрелку рисуем и туда
                    val предки = шаг.after.map { a -> "${a.task ?: t.id}#${a.step - 1}" }
                    val sn = arr.addObject()
                    sn.put("id", "${t.id}#$i")
                    sn.put("name", "${t.order}.${i + 1} · ${шаг.title}")
                    sn.put("start", w.start.toString())
                    sn.put("end", if (w.end.isAfter(w.start)) w.end.toString() else w.start.plusDays(1).toString())
                    sn.put("progress", 0)
                    sn.put("dependencies", предки.joinToString(","))
                    sn.put("duration_days", рабочихДней(w))
                    sn.put("duration_planned", w.planned)
                    (assignees["${t.id}#$i"] ?: assignees[t.id])?.let { кто ->
                        sn.put("assignee", кто.who)
                        sn.put("assignee_own", assignees.containsKey("${t.id}#$i"))
                    }
                    sn.put(
                        "custom_class",
                        when {
                            шаг.done -> "pw-step-done"
                            планШага != null -> "pw-step-plan"
                            else -> "pw-step"
                        },
                    )
                    sn.put("kind", "step")
                    sn.put("parent", t.id)
                    sn.put("step_index", i)
                    sn.put("number", "${t.order}.${i + 1}")
                    sn.put("title", шаг.title)
                    sn.put("done", шаг.done)
                    sn.put("planned", планШага != null)
                    планШага?.let { sn.put("plan_author", it.author) }
                    sn.put("tier", шаг.tier)
                    sn.put("tiers", t.steps.maxOf { it.tiers })
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
                        if (планШага != null) "план шага: ${w.start} — ${w.end}, поставил ${планШага.author}"
                        else "плана нет: окно — доля окна задачи по ярусу ${шаг.tier} из " +
                            "${t.steps.maxOf { it.tiers }}. Это порядок, а не срок — потяните полосу, чтобы задать план",
                    )
                }
            }
        }

        // ---- Точки: ромбами строками и вертикалями через полотно
        val линии = out.putArray("milestone_lines")
        gates.forEach { (gate, due) ->
            линии.addObject().put("date", due.toString()).put("name", gate)
            val held = passport.path("milestones")
                .any { it.path("gate").asText() == gate && it.path("held").asBoolean(false) }
            val интервал = intervals.getValue(gate)
            arr.addObject()
                .put("id", "gate:$gate")
                .put("name", gate)
                .put("start", due.toString())
                .put("end", due.toString())
                .put("progress", 0)
                .put("dependencies", "")
                .put("custom_class", if (gate in точкиСКонфликтом) "pw-ms-conflict" else "pw-ms")
                .put("kind", "gate")
                .put("gate", gate)
                .put("held", held)
                .put("conflict", gate in точкиСКонфликтом)
                .put(
                    "window_why",
                    "точка $gate закрывает интервал ${интервал.first} — $due" +
                        (if (held) " · пройдена" else "") +
                        (if (gate in точкиСКонфликтом)
                            ". Есть задачи, чьё окно уходит за точку: сдвиньте их план или перенесите точку"
                        else ""),
                )
        }

        // Порядок точек по датам обязан совпадать с порядком прохождения
        val порядокПаспорта = passport.path("milestones").map { it.path("gate").asText() }
        val поДате = gates.map { it.first }
        val поЦиклу = gates.map { it.first }.sortedBy { порядокПаспорта.indexOf(it) }
        if (поДате != поЦиклу) {
            out.put(
                "gate_conflict",
                "конфликт вех: по датам точки идут ${поДате.joinToString(" → ")}, " +
                    "а по ленте цикла — ${поЦиклу.joinToString(" → ")}. Перенесите точку на жизненном цикле",
            )
        }
        if (gates.isNotEmpty() && !gates.first().second.isAfter(phaseStart)) {
            out.put(
                "gate_conflict",
                "конфликт вех: точка ${gates.first().first} (${gates.first().second}) не позже начала " +
                    "фазы ($phaseStart) — интервалу неоткуда взяться. Перенесите точку на жизненном цикле",
            )
        }

        // Режим шкалы советует СЕРВЕР: он знает длину фазы, а клиент гадал бы.
        // Фаза в пару недель в режиме «Неделя» схлопывается в чёрточки.
        val длинаФазы = ChronoUnit.DAYS.between(phaseStart, gates.lastOrNull()?.second ?: phaseStart)
        out.put(
            "view_mode",
            when {
                длинаФазы <= 45 -> "Day"
                длинаФазы <= 200 -> "Week"
                else -> "Month"
            },
        )
        out.put("planned", plans.keys.count { it.substringBefore('#') in byId })
        out.put("total", tasks.size)
        return out
    }

    /**
     * Начало фазы: последняя ПРОЙДЕННАЯ точка перед первой точкой фазы, иначе
     * день создания проекта. «Сегодня» началом фазы не бывает: полотно
     * показывает фазу, а не то, когда на него посмотрели.
     */
    private fun phaseStart(
        boundary: Boundary,
        projectId: String,
        passport: JsonNode,
        firstGate: LocalDate?,
    ): LocalDate {
        val пройденные = passport.path("milestones")
            .filter { it.path("held").asBoolean(false) }
            .mapNotNull { runCatching { LocalDate.parse(it.path("due").asText()) }.getOrNull() }
            .filter { firstGate == null || it.isBefore(firstGate) }
        пройденные.maxOrNull()?.let { return it }
        val создан = boundary.objects.history(projectId).firstOrNull()?.validFrom?.toLocalDate()
        if (создан != null && (firstGate == null || создан.isBefore(firstGate))) return создан
        return firstGate?.minusDays(FALLBACK_PHASE_DAYS) ?: LocalDate.now()
    }

    /**
     * Базовое окно задачи: план, если он задан. Иначе — доля яруса ВНУТРИ
     * интервала своей точки (круг 7): так ромб оказывается в конце интервала
     * по построению. Сетка серая и подписана — за план она себя не выдаёт.
     */
    private fun baseWindow(
        t: PhaseWork.TaskState,
        plan: Plan?,
        intervals: Map<String, Pair<LocalDate, LocalDate>>,
        today: LocalDate,
    ): Window {
        if (plan != null) return Window(plan.start, plan.end, true)
        val интервал = t.gate?.let { intervals[it] }
            ?: (today to today.plusDays(FALLBACK_PHASE_DAYS))
        return доляЯруса(Window(интервал.first, интервал.second, false), t.tier, t.tiers)
    }

    /**
     * Доля яруса ВНУТРИ окна: порядок работ, а не срок. Доля не имеет права
     * вылезти за окно — иначе интервал короче числа ярусов рождал бы
     * ложный «конфликт за точкой» на ровном месте. Когда дней меньше, чем
     * ярусов, доли просто накладываются: интервал короток, и это правда о нём.
     */
    private fun доляЯруса(окно: Window, ярус: Int, ярусов: Int): Window {
        val дней = ChronoUnit.DAYS.between(окно.start, окно.end).coerceAtLeast(0)
        val на = дней.toDouble() / ярусов.coerceAtLeast(1)
        val начало = окно.start.plusDays((на * (ярус - 1)).toLong()).coerceAtMost(окно.end)
        val край = окно.start.plusDays((на * ярус).toLong()).coerceAtMost(окно.end)
        val конец = if (край.isAfter(начало)) край else minOf(начало.plusDays(1), окно.end)
        return Window(начало, if (конец.isAfter(начало)) конец else окно.end, false)
    }

    /**
     * «План против факта». Красное — только предупреждение и только по делу:
     * работа не начата после планового старта либо выход не готов после
     * планового конца. Где плана нет — по-старому: точка ближе недели при
     * неготовом выходе. Ждущая задача с далёким дедлайном красной не бывает.
     */
    private fun alarmOf(
        t: PhaseWork.TaskState,
        окно: Window,
        точка: LocalDate?,
        today: LocalDate,
    ): String? {
        if (t.outputDone) return null
        if (окно.planned) {
            if (today > окно.end) return "выход не готов, а плановый конец ${окно.end} позади"
            if (today > окно.start && t.steps.none { it.done }) {
                return "плановый старт ${окно.start} позади, а работа не начата"
            }
            return null
        }
        if (точка == null) return null
        val дней = ChronoUnit.DAYS.between(today, точка)
        return when {
            дней < 0 -> "точка ${t.gate} позади, а выход не готов"
            дней < TIGHT_DAYS -> "до точки ${t.gate} осталось дней: $дней, а выход не готов; плана у задачи нет"
            else -> null
        }
    }

    private fun windowWhy(
        t: PhaseWork.TaskState,
        окно: Window,
        планЗадачи: Plan?,
        естьШаги: Boolean,
        intervals: Map<String, Pair<LocalDate, LocalDate>>,
    ): String = when {
        естьШаги && окно.planned ->
            "сводная полоса: ${окно.start} — ${окно.end}, вычислена из шагов. " +
                "Тянуть её нельзя — план ставится шагам"
        естьШаги ->
            "сводная полоса: ${окно.start} — ${окно.end}, вычислена из окон шагов. Планов пока нет — " +
                "окна шагов расчётные, доли интервала ${t.gate ?: "фазы"} по порядку. " +
                "Тяните шаги, чтобы задать план"
        планЗадачи != null ->
            "план: ${окно.start} — ${окно.end}, поставил ${планЗадачи.author}"
        else ->
            "план не задан — полоса показывает долю интервала " +
                (t.gate?.let { g -> intervals[g]?.let { "$g (${it.first} — ${it.second})" } } ?: "фазы") +
                " по порядку зависимостей (ярус ${t.tier} из ${t.tiers}). Потяните полосу, чтобы задать план"
    }

    /**
     * Один класс на полосу: библиотека кладёт его через classList.add, и
     * строка с пробелами её сломала бы. Порядок важности: конфликт → сводная
     * → нет плана → предупреждение → статус.
     */
    private fun cssClass(
        t: PhaseWork.TaskState,
        planned: Boolean,
        alarm: Boolean,
        conflict: Boolean,
        summary: Boolean,
    ): String = when {
        summary && conflict -> "pw-summary-conflict"
        summary && alarm -> "pw-summary-alarm"
        summary -> "pw-summary"
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
     * Постановка плана. Круг 7: план ставится ШАГУ (`PW-NNNN#k`) либо задаче
     * БЕЗ шагов; полоса задачи с шагами сводная и вычисляется из них.
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
        collapse: Set<String> = emptySet(),
    ): ObjectNode {
        require(author.isNotBlank()) { "TZ-COM-005: field 'author' is required for editing" }
        val цель = request.path("task").asText("")
        require(цель.isNotBlank()) { "нужно поле 'task' — какой задаче или шагу ставится план" }
        val задача = цель.substringBefore('#')
        val шаг = цель.substringAfter('#', "").toIntOrNull()
        val состояния = PhaseWork.of(boundary, projectId).associateBy { it.id }
        val t = состояния[задача]
            ?: throw IllegalArgumentException("задача '$задача' не принадлежит работам текущей фазы")
        if (шаг == null) {
            require(t.steps.isEmpty()) {
                "у задачи ${t.order} есть шаги — её полоса сводная и вычисляется из них. " +
                    "План ставится шагам"
            }
        } else {
            require(шаг in t.steps.indices) { "у задачи ${t.order} нет шага №${шаг + 1}" }
        }
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
        текущие.forEach { p -> if (p.path("task").asText() != цель) остальные.add(p) }

        val снять = request.path("clear").asBoolean(false)
        if (!снять) {
            val start = LocalDate.parse(request.path("start").asText())
            // Ввод длительности числом — эквивалент правки плана: конец
            // считается как N рабочих дней от старта. Это не оценка-статус,
            // а тот же план другими руками.
            val end = request.path("duration_days").takeIf { it.isNumber }
                ?.let { концаПоДлительности(start, it.asInt()) }
                ?: LocalDate.parse(request.path("end").asText())
            require(!end.isBefore(start)) { "плановый конец раньше начала: $start — $end" }
            остальные.addObject()
                .put("task", цель)
                .put("start", start.toString())
                .put("end", end.toString())
                .put("author", author)
                .put("at", java.time.OffsetDateTime.now().toString())
        }
        val changes = mapper.createObjectNode()
        changes.set<ArrayNode>("work_plan", остальные)
        val что = if (шаг == null) "задаче $задача" else "шагу ${t.order}.${шаг + 1}"
        boundary.editing.update(
            CoreType.Project, projectId, changes, passport.version, author,
            changeRef = if (снять) "план работ фазы: план $что снят"
            else "план работ фазы: $что поставлен план",
        )
        return toJson(boundary, projectId, login, collapse)
    }

    /**
     * Назначение ответственного за работу (круг 8). Назначает руководитель
     * проекта; шаг без своей записи наследует ответственного задачи.
     * На вычисленные статусы ответственный не влияет — он адресат, не оценка.
     */
    fun assign(
        boundary: Boundary,
        projectId: String,
        request: JsonNode,
        author: String,
        login: String? = null,
        collapse: Set<String> = emptySet(),
    ): ObjectNode {
        require(author.isNotBlank()) { "TZ-COM-005: field 'author' is required for editing" }
        val цель = request.path("task").asText("")
        require(цель.isNotBlank()) { "нужно поле 'task' — кому назначается ответственный" }
        val задача = цель.substringBefore('#')
        val шаг = цель.substringAfter('#', "").toIntOrNull()
        val t = PhaseWork.of(boundary, projectId).firstOrNull { it.id == задача }
            ?: throw IllegalArgumentException("задача '$задача' не принадлежит работам текущей фазы")
        if (шаг != null) require(шаг in t.steps.indices) { "у задачи ${t.order} нет шага №${шаг + 1}" }
        if (login != null && boundary.auth.roleIn(projectId, login) != "lead") {
            throw RightDeniedException(
                "ответственных за работы назначает руководитель проекта (ваша роль — " +
                    "${boundary.auth.roleIn(projectId, login) ?: "без роли в проекте"})",
            )
        }
        val passport = boundary.objects.current(projectId)
            ?: throw NoSuchElementException("project '$projectId' not found")
        val текущие = (passport.doc.path("work_assignees").deepCopy<JsonNode>() as? ArrayNode)
            ?: mapper.createArrayNode()
        val остальные = mapper.createArrayNode()
        текущие.forEach { a -> if (a.path("task").asText() != цель) остальные.add(a) }
        val снять = request.path("clear").asBoolean(false)
        val кто = request.path("who").asText("").trim()
        if (!снять) {
            require(кто.isNotBlank()) { "нужно поле 'who' — кто ведёт эту работу" }
            остальные.addObject()
                .put("task", цель)
                .put("who", кто)
                .put("author", author)
                .put("at", java.time.OffsetDateTime.now().toString())
        }
        val changes = mapper.createObjectNode()
        changes.set<ArrayNode>("work_assignees", остальные)
        val что = if (шаг == null) "задачи ${t.order}" else "шага ${t.order}.${шаг + 1}"
        boundary.editing.update(
            CoreType.Project, projectId, changes, passport.version, author,
            changeRef = if (снять) "работы фазы: ответственный $что снят"
            else "работы фазы: ответственный $что — $кто",
        )
        return toJson(boundary, projectId, login, collapse)
    }

    /** Право не подошло: отказ обязан называть право, а не просто запрещать. */
    class RightDeniedException(message: String) : RuntimeException(message)
}
