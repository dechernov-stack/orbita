// Оценщик условий ворот (реализация порта process.GateEvaluator).
//
// Ворота остаются НАШИМИ правилами: движок только исполняет их. Здесь
// условие переводится в вопрос к данным домена, а отказ — в причину
// словами, которую увидит инженер («у стейкхолдера СХ-2 нет ни одной
// нужды»), а не в голое «нельзя».
package orbita.readiness.internal

import orbita.kernel.api.Area
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.kernel.api.QosClass
import orbita.process.api.GateEvaluator
import orbita.readiness.api.ExtraChecks

class DomainGateEvaluator(
    private val store: EntityStore,
    private val links: LinkRegistry,
    /** Сцены, признанные пройденными: их считает движок и передаёт сюда. */
    private val сценыПройдены: (String) -> Set<String>,
    private val воротаПройдены: (String) -> Set<String>,
    /** Условия, которые знают модули выше слоем (требования, архитектура). */
    private val дополнительные: ExtraChecks? = null,
    /** Русское имя вида для причины отказа; по умолчанию — код вида. */
    private val имяВида: (String) -> String = { it },
    /**
     * Чем закрывается нужда (`service` · `constraint` · `programme` ·
     * `requirement`). Правило живёт в истине онтологии, а она слоем выше —
     * поэтому приходит функцией; по умолчанию сервис, как было до 17.09.
     */
    private val видПокрытия: (Entity) -> String? = { СЕРВИС },
) : GateEvaluator {

    /**
     * Записи вида без снятых с учёта: «Отменить пакет» ставит cancelled, а
     * проверки сцен считали такие записи живыми — «нужд без цели: 60» при 33
     * живых (216, 17.09).
     */
    /**
     * Непокрытые нужды прочих видов — счётчиком рядом с причиной: они сцену не
     * держат, но и молчать о них нельзя (истина: «помета к MCR»).
     */
    private fun прочееПокрытие(непокрытые: List<Entity>): String {
        val прочие = непокрытые.filter { видПокрытия(it) != СЕРВИС }
            .groupingBy { видПокрытия(it) ?: "не названо" }
            .eachCount()
        if (прочие.isEmpty()) return ""
        return "; сверх того ждут иного покрытия (сцену не держат): " +
            прочие.entries.sortedBy { it.key }.joinToString(", ") { (вид, n) -> "$вид $n" }
    }

    private fun живые(область: Area, вид: String): List<Entity> =
        store.list(область, вид).filter { it.status != "cancelled" }

    private companion object {
        /** Вид покрытия, который считает выход сцены 6 (истина онтологии). */
        const val СЕРВИС: String = "service"
    }

    override fun why(project: String, check: String): String? {
        val область = Area.Project(project)
        val (имя, аргумент) = check.split(":", limit = 2).let {
            it[0] to it.getOrNull(1)
        }
        return when (имя) {
            // Проект опознаётся КОДОМ: он же стоит в области сущностей
            // («project:PJ-0001»), и связывать одно с другим через внутренний
            // идентификатор значило бы держать два имени одного и того же.
            "project_exists" ->
                if (store.byCode(область, project) != null) null else "проект ещё не заведён"

            "points_planned" -> {
                // ТОЧКИ ФАЗЫ, а не все вехи: у технологии своя веха
                // («TRL 6 достигнут»), и её дата приходит планом созревания,
                // а не сценой 1. Считая её здесь, мы заново открывали сцену 1
                // каждый раз, когда инженер заводил критическую технологию.
                val точки = живые(область, "gate").filter {
                    it.doc.path("kind").asText("phase") != "technology"
                }
                if (точки.isEmpty()) "точки фазы не заведены"
                else точки.firstOrNull { it.doc.path("planned_date").asText("").isBlank() }
                    ?.let { "у точки «${it.doc.path("title").asText(it.code)}» нет даты" }
            }

            "intent_accepted" -> {
                val замысел = живые(область, "intent").firstOrNull()
                when {
                    замысел == null -> "замысел не задан: без него сцена 3 закрыта"
                    замысел.status != "accepted" -> "замысел ещё не принят — примите его на сцене 2"
                    else -> null
                }
            }

            "stakeholders_min" -> {
                val нужно = (аргумент ?: "3").toInt()
                val есть = живые(область, "stakeholder").size
                if (есть >= нужно) null
                else "стейкхолдеров $есть из $нужно: круг шире потребителей — регуляторы, операторы, учреждаемые"
            }

            "each_stakeholder_has_need" -> {
                val без = живые(область, "stakeholder")
                    .filter { links.from(it.id, "owns").isEmpty() }
                if (без.isEmpty()) null
                else "без нужд: " + без.joinToString(", ") { it.doc.path("name").asText(it.code) }
            }

            "each_need_has_goal" -> {
                val без = живые(область, "need")
                    .filter { нужда -> links.to(нужда.id, "covers").none { it.from.startsWith("goal") } }
                if (без.isEmpty()) null
                else "нужд без цели: ${без.size} — " +
                    без.take(3).joinToString("; ") { it.doc.path("statement").asText(it.code).take(60) }
            }

            "constraints_min" -> {
                val нужно = (аргумент ?: "1").toInt()
                val есть = живые(область, "constraint").count {
                    !it.doc.path("removed").asBoolean(false)
                }
                if (есть >= нужно) null
                else "ограничений $есть из $нужно: рамки проекта задаются здесь и дальше работают запретами"
            }

            // Истина 17.09 (`need.coverage_rule`): выход считает ТОЛЬКО нужды,
            // которые закрываются сервисом. Нужда регулятора закрывается
            // ограничением, нужда поставщика — пакетом WBS; они шли сюда и
            // держали сцену 6 навсегда (ПМИ-7: четыре нужды Роскосмоса и ГКРЧ).
            // Остальные виды покрытия — счётчиком, не блокером.
            "each_need_has_service" -> {
                val непокрытые = живые(область, "need")
                    .filter { нужда -> links.to(нужда.id, "covers").none { it.from.startsWith("service") } }
                val без = непокрытые.filter { видПокрытия(it) == СЕРВИС }
                if (без.isEmpty()) null
                else "нужд без сервиса: ${без.size} — " +
                    без.take(3).joinToString("; ") { it.doc.path("statement").asText(it.code).take(60) } +
                    прочееПокрытие(непокрытые)
            }

            // Решение владельца 12.09 («ЗНАНИЯ-V2-ПРИНЯТЫ» §1): класс
            // обслуживания у нужды обязателен, но значение TBR допустимо — до
            // ЭТОЙ сцены. Здесь TBR и закрывается: нужда, которую сервис уже
            // покрывает, обязана нести класс, иначе сервис не с чем сверить.
            // Нужда без сервиса сюда не попадает — о ней говорит соседнее
            // условие, и повторять его второй фразой ни к чему.
            "covered_need_has_qos_class" -> {
                val без = живые(область, "need")
                    .filter { видПокрытия(it) == СЕРВИС }
                    .filter { нужда -> links.to(нужда.id, "covers").any { it.from.startsWith("service") } }
                    .filter { нужда -> !QosClass.assigned(нужда.doc.path(QosClass.FIELD)) }
                if (без.isEmpty()) null
                else "нужд с классом ${QosClass.TBR}: ${без.size} — " +
                    без.take(3).joinToString("; ") { нужда ->
                        val кто = нужда.doc.path(QosClass.FIELD).path("owner").asText("")
                        нужда.doc.path("statement").asText(нужда.code).take(50) +
                            (if (кто.isBlank()) "" else " (ждёт: $кто)")
                    }
            }

            // Порог владельца (ВОЛНА-0-ПРИНЯТА §2): план обязателен у ПЕРВОЙ
            // доступной сцены. Строгий порог «у всех» делает сцену
            // непроходимой в первый день фазы, а мягкий «хоть где-нибудь» —
            // бессмысленным.
            "phase_plan_started" -> {
                val план = живые(область, "plan").lastOrNull()
                val окна = план?.doc?.path("scene_windows")?.map { it.path("scene").asText() }.orEmpty()
                val нужнаСцена = аргумент
                when {
                    план == null -> "план работ фазы не задан: ленте нечего показывать"
                    окна.isEmpty() -> "в плане нет ни одного окна сцены"
                    нужнаСцена != null && нужнаСцена !in окна ->
                        "у сцены $нужнаСцена нет окна в плане работ фазы"
                    else -> null
                }
            }

            "gate_dates_planned" -> {
                val план = живые(область, "plan").lastOrNull()
                val даты = план?.doc?.path("gate_dates")?.count { it.path("date").asText("").isNotBlank() } ?: 0
                val точек = живые(область, "gate").count {
                    it.doc.path("kind").asText("phase") != "technology"
                }
                if (точек > 0 && даты >= точек) null
                else "даты точек в плане: $даты из $точек"
            }

            // Замечания обзора — событие: пока хоть одно открыто, точка
            // держится, а сцена возврата снова в работе (шип D).
            "findings_closed" -> {
                val точка = аргумент ?: return "условие «$check» не назвало точку"
                val открытые = живые(область, "finding").filter {
                    it.status == "open" && it.doc.path("gate").asText() == точка
                }
                if (открытые.isEmpty()) null
                else "открыто замечаний ${точка}: ${открытые.size} — " + открытые.take(3).joinToString("; ") {
                    "«${it.doc.path("text").asText().take(60)}» → сцена ${it.doc.path("returns_to_scene").asText("?")}"
                }
            }

            // Позиция экспертизы с кодом «требуется» для записи реестра:
            // проверяется наличие хотя бы одной записи вида. Зрелость
            // реестра к точке считать нечем — и это не выдумывается.
            "exists" -> {
                val вид = аргумент ?: return "условие «$check» не назвало вид"
                if (живые(область, вид).any { it.status != "cancelled" }) null
                else "нет ни одной записи «${имяВида(вид)}»"
            }

            // Поле знаний к точке (шип E п. 5, правила поля знаний YAML):
            // допущение без подтверждения к точке — разрыв; тема с принятыми
            // фактами обязана разрешиться в сущность; противоречие с участием
            // принятого факта — блокирующий разрыв.
            "assumptions_confirmed" -> {
                val точка = аргумент ?: return "условие «$check» не назвало точку"
                val открытые = живые(область, "fact").filter {
                    it.doc.path("disposition").asText("") == "assumed" &&
                        it.doc.path("assumption").path("confirm_by").asText("") == точка
                }
                if (открытые.isEmpty()) null
                else "допущений без подтверждения к $точка: ${открытые.size} — " + открытые.take(3).joinToString("; ") {
                    "${it.code} «${it.doc.path("predicate").asText().take(50)}» (владелец ${it.doc.path("assumption").path("owner").asText("—")})"
                }
            }

            "topics_resolved" -> {
                val факты = живые(область, "fact")
                val сПринятыми = факты.filter { it.doc.path("disposition").asText("") == "adopted" }
                    .map { it.doc.path("topic").asText("") }.filter { it.isNotBlank() }.toSet()
                val неразрешённые = живые(область, "topic").filter {
                    it.code in сПринятыми && it.doc.path("resolved_to").asText("").isBlank()
                }
                if (неразрешённые.isEmpty()) null
                else "тем с принятыми фактами без сущности: ${неразрешённые.size} — " +
                    неразрешённые.take(3).joinToString("; ") { "${it.code} «${it.doc.path("label").asText().take(50)}»" }
            }

            "facts_consistent" -> {
                val факты = живые(область, "fact").associateBy { it.code }
                val споры = факты.values.filter { ф ->
                    ф.doc.path("disposition").asText("") == "adopted" &&
                        ф.doc.path("conflicts").any { к ->
                            val другой = факты[к.asText()] ?: return@any false
                            другой.doc.path("disposition").asText("") !in setOf("rejected", "superseded") &&
                                !другой.doc.path("superseded").asBoolean(false)
                        }
                }
                if (споры.isEmpty()) null
                else "принятых фактов с неразрешённым противоречием: ${споры.size} — " + споры.take(3).joinToString("; ") { ф ->
                    "${ф.code} «${ф.doc.path("predicate").asText().take(40)} = ${ф.doc.path("value").asText()}» против " +
                        ф.doc.path("conflicts").joinToString(", ") { it.asText() } + " — решите диспозицией"
                }
            }

            "scene_done" -> {
                val ключ = аргумент ?: return "условие «$check» не назвало сцену"
                if (ключ in сценыПройдены(project)) null else "сцена $ключ ещё не прожита"
            }

            "gate_passed" -> {
                val ключ = аргумент ?: return "условие «$check» не назвало точку"
                if (ключ in воротаПройдены(project)) null else "точка $ключ ещё не пройдена"
            }

            // Условие, которого оценщик не знает, — не «выполнено по умолчанию»:
            // тихо пропустить ворота хуже, чем честно сказать, что правило не
            // реализовано (ТЗ §6.2: не гадать). Сначала спрашиваем тех, кто
            // вправе знать правило (требования, архитектура).
            // Разбор именно `when`, а не `?.let ?: …`: у выполненного условия
            // ответ — null, и элвис принял бы его за «никто не знает правила».
            else -> when (val ответ = дополнительные?.of(project, check)) {
                null -> "условие «$check» ещё не реализовано в оценщике готовности"
                else -> if (ответ.passed) null else ответ.why ?: "условие «$check» не выполнено"
            }
        }
    }

}
