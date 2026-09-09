// Движок процесса: шаблон фазы → состояние сцен и точек.
//
// Состояние НЕ хранится ручным статусом: оно вычисляется по условиям входа
// и выхода, которые считает внешний оценщик. Отсюда следует главное свойство
// волны 1 — сцена 3 закрыта без замысла не потому, что кто-то поставил флаг,
// а потому что условие «intent_accepted» не выполнено.
//
// Flowable держит состояние дела (какие stage активны, какие milestone
// пройдены); здесь — проекция этого состояния в вид, понятный интерфейсу.
package orbita.process.internal

import com.fasterxml.jackson.databind.JsonNode
import orbita.process.api.ActivityState
import orbita.process.api.ActivityView
import orbita.process.api.ConditionView
import orbita.process.api.DecisionView
import orbita.process.api.ExpertiseView
import orbita.process.api.FindingView
import orbita.process.api.GateEvaluator
import orbita.process.api.GateHeldException
import orbita.process.api.PositionView
import orbita.process.api.RoleRefusedException
import orbita.process.api.LaneView
import orbita.process.api.OutputCounter
import orbita.process.api.OutputView
import orbita.process.api.GateView
import orbita.process.api.PhaseView
import orbita.process.api.ProcessEngine
import orbita.process.api.SceneState
import orbita.process.api.SceneLinkView
import orbita.process.api.SceneView
import orbita.process.api.StepView
import java.time.LocalDate

class TemplateProcessEngine(
    /** Шаблон фазы читается из полки: контента в коде нет. */
    private val шаблон: (String) -> JsonNode,
    private val оценщик: GateEvaluator,
    /** Пройденные точки хранит вызывающий (kernel), движок их только читает. */
    private val пройденныеТочки: (String) -> MutableSet<String>,
    private val планТочек: (String) -> Map<String, String>,
    /**
     * Окна плана работ фазы: сцена → (начало, конец). Порог владельца:
     * план обязателен у ПЕРВОЙ доступной сцены, остальные окна до
     * внутреннего обзора — помета «план не задан», а не разрыв.
     */
    private val окнаСцен: ((String) -> Map<String, Pair<String, String>>)? = null,
    /**
     * Счёт выходов мероприятий: движок спрашивает числа у домена, а сам
     * их не хранит — иначе состояние схемы разошлось бы с данными.
     */
    private val счётВыходов: OutputCounter? = null,
    /**
     * Справочник процессов ЖЦ (полка Романова): откуда берутся входные
     * потоки сцены. Методология приходит ДАННЫМИ — в интерфейсе её имена
     * не показываются, только смысл потока.
     */
    private val процессы: (() -> JsonNode?)? = null,
    /** Замечания обзора: движок читает их как события возврата в сцену. */
    private val замечания: ((String) -> List<FindingView>)? = null,
    /** Решения точек — последнее по каждой. */
    private val решения: ((String) -> Map<String, DecisionView>)? = null,
    /** Фаза проекта по данным (после KDP-A — «Phase A»); пусто — из шаблона. */
    private val фазаПроекта: ((String) -> String?)? = null,
    /**
     * Запись решения: проект, точка, кем, исход, помета, открываемая фаза.
     * Пусто — решение живёт только в памяти (тесты).
     */
    private val наРешение: ((String, String, String, String, String?, String?) -> Unit)? = null,
    /**
     * Шаблон фазы проекта по данным (шип G): после KDP-A проект живёт по
     * шаблону Phase A, и это записано в проекте, а не в памяти движка.
     */
    private val шаблонФазыПроекта: ((String) -> String?)? = null,
    /** Решение точки с `opens_template` записывает проекту новый шаблон фазы. */
    private val наОткрытиеШаблона: ((String, String) -> Unit)? = null,
    /**
     * Экземпляры сцены на узлы состава (Романов, стадия 2: аванпроект
     * рекурсивен): проект · вид узла (segment · element) → (код, имя).
     * Пусто — сцена одна и держится словами «узлов нет».
     */
    private val экземпляры: ((String, String) -> List<Pair<String, String>>)? = null,
) : ProcessEngine {

    private val шаблоныПроектов = mutableMapOf<String, String>()

    /** Шаблон фазы: открытый явно → записанный в проекте → Pre-A. */
    private fun кодШаблона(project: String): String =
        шаблоныПроектов[project] ?: шаблонФазыПроекта?.invoke(project)?.takeIf { it.isNotBlank() } ?: "PHT-9001"

    override fun openPhase(project: String, templateCode: String): PhaseView {
        шаблоныПроектов[project] = templateCode
        return view(project)
    }

    override fun view(project: String): PhaseView {
        val код = кодШаблона(project)
        val док = шаблон(код)
        val пройдены = пройденныеТочки(project)
        val всеЗамечания = замечания?.invoke(project).orEmpty()
        val открытые = всеЗамечания.filter { it.status == "open" }

        // Сцены считаются по порядку: состояние следующей зависит от предыдущих
        val прожитые = mutableSetOf<String>()
        val потоки = входныеПотоки()
        val окна = окнаСцен?.invoke(project).orEmpty()

        // Кто кого ждёт: считается по самому шаблону — второй карты связей
        // между сценами нет, и разойтись ей не с чем.
        val ждут = mutableMapOf<String, MutableList<String>>()
        док.path("scenes").forEach { сцена ->
            сцена.path("entry").forEach { условие ->
                val проверка = условие.path("check").asText()
                if (проверка.startsWith("scene_done:") || проверка.startsWith("scene_started:")) {
                    ждут.getOrPut(проверка.substringAfter(':')) { mutableListOf() } +=
                        "${сцена.path("key").asText()} · ${сцена.path("title").asText().replace("{node}", "…")}"
                }
            }
        }
        док.path("points").forEach { точка ->
            точка.path("criteria").forEach { условие ->
                val проверка = условие.path("check").asText()
                if (проверка.startsWith("scene_done:")) {
                    ждут.getOrPut(проверка.removePrefix("scene_done:")) { mutableListOf() } +=
                        "◆ ${точка.path("title").asText()}"
                }
            }
        }

        // Кто ждёт мероприятие: вход одного назван выходом (именем) другого.
        // Карта строится по шаблону целиком — и сцены, и дорожки.
        val ждутМероприятие = mutableMapOf<String, MutableList<String>>()
        fun собрать(дело: JsonNode) {
            дело.path("inputs").forEach { вход ->
                ждутМероприятие.getOrPut(вход.path("what").asText()) { mutableListOf() } +=
                    дело.path("code").asText()
            }
        }
        док.path("scenes").forEach { с -> с.path("activities").forEach { собрать(it) } }
        док.path("lanes").forEach { д -> д.path("activities").forEach { собрать(it) } }

        val начатые = mutableSetOf<String>()

        /** Одна сцена шаблона в виде: ключ, подстановка `{node}` в условия и заголовки. */
        fun вид(сцена: JsonNode, ключ: String, узелКод: String?, узелИмя: String?): SceneView {
            fun усл(т: String) = if (узелКод == null) т else т.replace("{node}", узелКод)
            fun имя(т: String) = if (узелИмя == null) т else т.replace("{node}", узелИмя)
            val условияВхода = сцена.path("entry").map { условие ->
                val проверка = усл(условие.path("check").asText())
                val причина = проверить(project, проверка, прожитые, начатые, пройдены)
                ConditionView(имя(условие.path("title").asText(проверка)), проверка, причина == null, причина)
            }
            // Замечание обзора с возвратом сюда — СОБЫТИЕ, не флаг: пока оно
            // открыто, выход сцены держится им, и прожитая сцена снова в
            // работе; закрыли — условие исчезает, сцена прожита вновь.
            val возвраты = открытые.filter { it.scene == ключ }.map { з ->
                ConditionView(
                    "замечание ${з.code}: ${з.text}", "finding_closed:${з.code}", false,
                    "замечание «${з.text}» (${з.gate}) открыто — закройте его работой в этой сцене",
                )
            }
            val условияВыхода = сцена.path("exit").map { условие ->
                val проверка = усл(условие.path("check").asText())
                val причина = проверить(project, проверка, прожитые, начатые, пройдены)
                ConditionView(имя(условие.path("title").asText(проверка)), проверка, причина == null, причина)
            } + возвраты
            val причиныВхода = условияВхода.mapNotNull { it.why }
            val причиныВыхода = условияВыхода.mapNotNull { it.why }
            val состояние = when {
                причиныВхода.isNotEmpty() -> SceneState.LOCKED
                причиныВыхода.isEmpty() -> SceneState.DONE
                else -> SceneState.OPEN
            }
            if (состояние == SceneState.DONE) прожитые += ключ
            if (состояние != SceneState.LOCKED) начатые += ключ
            return SceneView(
                key = ключ,
                title = имя(сцена.path("title").asText()),
                order = сцена.path("order").asInt(),
                role = сцена.path("role").asText(""),
                question = имя(сцена.path("question").asText("")),
                state = состояние,
                blockers = if (состояние == SceneState.LOCKED) причиныВхода else причиныВыхода,
                steps = сцена.path("steps").map { шаг ->
                    val проверка = усл(шаг.path("check").asText(""))
                    StepView(
                        title = имя(шаг.path("title").asText()),
                        place = шаг.path("place").asText(""),
                        hint = имя(шаг.path("hint").asText("")),
                        // Шаг закрыт своим условием, если оно названо; иначе —
                        // вместе со сценой: врать про «сделано» шаг не должен.
                        done = if (проверка.isBlank()) состояние == SceneState.DONE
                        else проверить(project, проверка, прожитые, начатые, пройдены) == null,
                    )
                },
                entry = условияВхода,
                exit = условияВыхода,
                output = имя(сцена.path("output").asText("")),
                awaitedBy = ждут[ключ.substringBefore(':')].orEmpty(),
                inputFlows = сцена.path("process_ref").flatMap { потоки[it.asText()].orEmpty() },
                window = окна[ключ] ?: окна[ключ.substringBefore(':')],
                activities = мероприятия(project, сцена, ключ, причиныВхода, ждутМероприятие),
                links = сцена.path("depends").map { д ->
                    SceneLinkView(д.path("on").asText(""), д.path("type").asText("FS"), д.path("why").asText(""))
                },
                instanceOf = if (узелКод == null) null else ключ.substringBefore(':'),
                node = узелКод,
            )
        }

        val сцены = док.path("scenes").sortedBy { it.path("order").asInt() }.flatMap { сцена ->
            val ключ = сцена.path("key").asText()
            val на = сцена.path("instantiate_per").asText("").ifBlank { null }
                ?: return@flatMap listOf(вид(сцена, ключ, null, null))
            // Аванпроект рекурсивен: экземпляр сцены на каждый узел нужного
            // вида. Узлов нет — сцена одна и держится словами: где их завести.
            val узлы = экземпляры?.invoke(project, на).orEmpty()
            if (узлы.isEmpty()) {
                val причина = "в составе нет ни одного узла вида «$на» — заведите сегменты и элементы состава (сцена 7, «Концепция»)"
                val пустая = вид(сцена, ключ, "—", "—")
                return@flatMap listOf(
                    пустая.copy(
                        title = сцена.path("title").asText().replace("{node}", "—"),
                        state = SceneState.LOCKED,
                        blockers = listOf(причина),
                        entry = listOf(ConditionView("узлы вида «$на» в составе", "instances:$на", false, причина)) + пустая.entry,
                        instanceOf = null, node = null,
                    ),
                )
            }
            val виды = узлы.map { (код, имя) -> вид(сцена, "$ключ:$код", код, имя) }
            // Сцена шаблона прожита, когда прожиты все её экземпляры; начата — когда начат хотя бы один.
            if (виды.all { it.state == SceneState.DONE }) прожитые += ключ
            if (виды.any { it.state != SceneState.LOCKED }) начатые += ключ
            виды
        }

        val план = планТочек(project)
        val решенияТочек = решения?.invoke(project).orEmpty()
        val помета = док.path("maturity_note").asText("").ifBlank { null }
        val точки = док.path("points").sortedBy { it.path("order").asInt() }.map { точка ->
            val ключ = точка.path("key").asText()
            // Критерии целиком — ✓ и ☐ с причиной: точка показывает, из чего
            // она состоит, а не только чего не хватает.
            val критерии = точка.path("criteria").map { у ->
                val причина = проверить(project, у.path("check").asText(), прожитые, начатые, пройдены)
                ConditionView(
                    у.path("title").asText(у.path("check").asText()),
                    у.path("check").asText(), причина == null, причина,
                    blocking = у.path("blocking").asBoolean(true),
                )
            }
            val экспертиза = точка.path("expertise").takeIf { it.isObject }?.let { э ->
                ExpertiseView(
                    goal = э.path("goal").asText(""),
                    questions = э.path("validation_questions").map { it.asText() },
                    results = э.path("results").map { it.asText() },
                    mainOutcome = э.path("main_outcome").map { it.asText() },
                    positions = э.path("control_items").map { п ->
                        val код = п.path("maturity").asText("")
                        val ссылка = п.path("our_ref").asText("").ifBlank { null }
                        val проверка = MaturityTable.check(код, ссылка, ключ)
                        val причина = проверка?.let { проверить(project, it, прожитые, начатые, пройдены) }
                        PositionView(
                            artifact = п.path("artifact").asText(""),
                            maturity = код,
                            ourRef = ссылка,
                            check = проверка,
                            passed = проверка?.let { причина == null },
                            why = причина ?: MaturityTable.words(код, ссылка),
                            blocking = проверка != null && MaturityTable.blocking(код, ссылка),
                        )
                    },
                    source = э.path("source").asText("").ifBlank { null },
                )
            }
            // Матрица зрелости комплекта: код к ЭТОЙ точке решает, что проверять.
            val матрица = точка.path("maturity_matrix").map { строка ->
                val коды = строка.path("codes").fields().asSequence().associate { (к, в) -> к to в.asText() }
                val код = коды[ключ] ?: "x"
                val ссылка = строка.path("our_ref").asText("").ifBlank { null }
                val проверка = MaturityTable.check(код, ссылка, ключ)
                val причина = проверка?.let { проверить(project, it, прожитые, начатые, пройдены) }
                PositionView(
                    artifact = строка.path("artifact").asText(""),
                    maturity = код,
                    ourRef = ссылка,
                    check = проверка,
                    passed = проверка?.let { причина == null },
                    why = причина ?: MaturityTable.words(код, ссылка),
                    blocking = проверка != null && MaturityTable.blocking(код, ссылка),
                    codes = коды,
                )
            }
            val держат = критерии.filter { it.blocking && !it.passed }.mapNotNull { it.why } +
                экспертиза?.positions.orEmpty()
                    .filter { it.blocking && it.passed == false }
                    .map { "позиция экспертизы «${it.artifact}» (${it.maturity}): ${it.why}" } +
                матрица.filter { it.blocking && it.passed == false }
                    .map { "комплект: «${it.artifact}» — ${it.why}" }
            GateView(
                key = ключ,
                title = точка.path("title").asText(),
                order = точка.path("order").asInt(),
                plannedDate = план[ключ] ?: LocalDate.now().plusDays(точка.path("offset_days").asLong(30)).toString(),
                passed = ключ in пройдены,
                blocking = держат,
                role = точка.path("role").asText(""),
                criteria = критерии,
                expertise = экспертиза,
                findings = всеЗамечания.filter { it.gate == ключ },
                decision = решенияТочек[ключ],
                matrix = матрица,
                opensPhase = точка.path("opens_phase").asText("").ifBlank { null },
                checklistOf = точка.path("checklist_of").asText("").ifBlank { null },
                legendNote = помета,
            )
        }

        val дорожки = док.path("lanes").map { дорожка ->
            LaneView(
                key = дорожка.path("key").asText(),
                title = дорожка.path("title").asText(),
                of = дорожка.path("of").asText("scenes"),
                // Мероприятия дорожки без сцены (моделирование, управление):
                // они ждут артефактов из сцен и показываются схемой фазы.
                activities = дорожка.path("activities").map { дело ->
                    вид(project, дело, сценаКлюч = null, причиныВхода = emptyList(), ждут = ждутМероприятие)
                },
            )
        }

        return PhaseView(
            project = project,
            standard = док.path("standard").asText(""),
            // После решения KDP-A фаза проекта — из данных, а не из шаблона
            // Pre-A: шаблон Phase A придёт шипом G, переход же случился здесь.
            phase = фазаПроекта?.invoke(project)?.takeIf { it.isNotBlank() } ?: док.path("phase").asText(""),
            currentScene = сцены.firstOrNull { it.state == SceneState.OPEN }?.key,
            scenes = сцены,
            gates = точки,
            lanes = дорожки,
        )
    }

    override fun passGate(project: String, gate: String, decidedBy: String): PhaseView {
        val роль = view(project).gates.firstOrNull { it.key == gate }?.role
            ?: throw NoSuchElementException("точки «$gate» нет в фазе проекта")
        return decide(project, gate, decidedBy, setOf(роль), "approve", null)
    }

    override fun decide(
        project: String,
        gate: String,
        by: String,
        roles: Set<String>,
        outcome: String,
        note: String?,
    ): PhaseView {
        val вид = view(project)
        val точка = вид.gates.firstOrNull { it.key == gate }
            ?: throw NoSuchElementException("точки «$gate» нет в фазе проекта")
        // Роль решает не интерфейс: спрятанная кнопка правом не является.
        val роль = точка.role
        if (роль.isNotBlank() && роль !in roles) {
            throw RoleRefusedException(
                "решение по точке «${точка.title}» принимает ${имяРоли(project, роль)}; " +
                    "ваша роль — ${roles.joinToString(", ") { имяРоли(project, it) }.ifBlank { "нет" }} ($by)",
            )
        }
        when (outcome) {
            "approve" -> {
                if (точка.passed) throw IllegalArgumentException("точка «${точка.title}» уже пройдена")
                // Отказ приходит от движка, а не от интерфейса: блокирующее
                // невыполненное условие не даёт зафиксировать точку.
                if (точка.blocking.isNotEmpty()) {
                    throw GateHeldException("точка «${точка.title}» держится: " + точка.blocking.joinToString("; "))
                }
            }
            "return" -> if (точка.findings.none { it.status == "open" }) {
                throw IllegalArgumentException(
                    "вернуть точку «${точка.title}» можно только с открытыми замечаниями: " +
                        "заведите замечание с возвратом в сцену",
                )
            }
            "defer" -> Unit
            else -> throw IllegalArgumentException("исход решения: approve · return · defer, получено «$outcome»")
        }
        val запись = наРешение
        if (запись != null) {
            запись(project, gate, by, outcome, note, точка.opensPhase.takeIf { outcome == "approve" })
            if (outcome == "approve") {
                шаблонПоТочке(project, gate)?.let { наОткрытиеШаблона?.invoke(project, it) }
            }
        } else if (outcome == "approve") {
            пройденныеТочки(project).add(gate)
        }
        return view(project)
    }

    /** Шаблон следующей фазы у точки (`opens_template`), если она его открывает. */
    private fun шаблонПоТочке(project: String, gate: String): String? =
        шаблон(кодШаблона(project)).path("points").firstOrNull { it.path("key").asText() == gate }
            ?.path("opens_template")?.asText("")?.ifBlank { null }

    /** Имя роли — из шаблона фазы (`roles[].involvement` до двоеточия), не из кода. */
    private fun имяРоли(project: String, роль: String): String {
        val код = кодШаблона(project)
        return шаблон(код).path("roles").firstOrNull { it.path("role").asText() == роль }
            ?.path("involvement")?.asText()?.substringBefore(":")?.trim()?.ifBlank { null } ?: роль
    }

    private fun мероприятия(
        project: String,
        сцена: JsonNode,
        ключ: String,
        причиныВхода: List<String>,
        ждут: Map<String, List<String>>,
    ): List<ActivityView> = сцена.path("activities").map { дело ->
        вид(project, дело, ключ, причиныВхода, ждут)
    }

    /**
     * Мероприятие в вид: выходы считаются ПО ДАННЫМ, состояние выводится из
     * них. Ручного «готово» нет — блок закрывается, когда его выходы есть.
     */
    private fun вид(
        project: String,
        дело: JsonNode,
        сценаКлюч: String?,
        причиныВхода: List<String>,
        ждут: Map<String, List<String>> = emptyMap(),
    ): ActivityView {
        val выходы = дело.path("outputs").map { выход ->
            val вид = выход.path("kind").asText("")
            val рождаетсяВ = выход.path("produced_in").asText("").ifBlank { null }
            val сколько = if (вид.isBlank()) 0 else счётВыходов?.count(project, вид) ?: 0
            val минимум = выход.path("min").asInt(0)
            OutputView(
                what = выход.path("what").asText(""),
                kind = вид,
                min = минимум,
                count = сколько,
                producedIn = рождаетсяВ?.takeIf { it != сценаКлюч },
                // Выход, рождающийся в другой сцене, в завершение не входит:
                // он показывается стрелкой «→ сцена N», а не пустотой.
                satisfied = рождаетсяВ != null && рождаетсяВ != сценаКлюч || сколько >= минимум,
            )
        }
        val свои = выходы.filter { it.producedIn == null }
        // Измеряется только тот выход, у которого назван ВИД сущности:
        // мероприятие метода без вида нам считать нечем, и объявлять его
        // выполненным — врать. Такое остаётся «не начато».
        val измеримые = свои.filter { it.kind.isNotBlank() }
        val состояние = when {
            причиныВхода.isNotEmpty() -> ActivityState.BLOCKED
            измеримые.isEmpty() -> ActivityState.NOT_STARTED
            измеримые.all { it.satisfied } -> ActivityState.DONE
            измеримые.any { it.count > 0 } -> ActivityState.IN_PROGRESS
            else -> ActivityState.AVAILABLE
        }
        return ActivityView(
            code = дело.path("code").asText(),
            name = дело.path("name").asText(),
            goal = дело.path("goal").asText(""),
            role = дело.path("role").asText(""),
            track = дело.path("track").asText("design"),
            methodGroup = дело.path("method_group").asText(""),
            inputs = дело.path("inputs").map { it.path("what").asText() },
            outputs = выходы,
            surface = дело.path("surface").asText(""),
            state = состояние,
            blockedBy = причиныВхода,
            // «Откроет»: мероприятия, ждущие этой работы, и сцены, где
            // рождаются её выходы. Без этого рейка не отвечает на «зачем».
            opens = (ждут[дело.path("name").asText()].orEmpty() +
                выходы.mapNotNull { it.producedIn?.let { с -> "сцена $с" } }).distinct(),
        )
    }

    /**
     * Входные потоки по коду мероприятия из полки процессов ЖЦ.
     * Ссылка на другое мероприятие разворачивается в его НАЗВАНИЕ: инженеру
     * нужен смысл потока, а не код методологии.
     */
    private fun входныеПотоки(): Map<String, List<String>> {
        val полка = процессы?.invoke() ?: return emptyMap()
        val названия = mutableMapOf<String, String>()
        val входы = mutableMapOf<String, List<String>>()
        полка.path("stages").forEach { стадия ->
            стадия.path("tracks").fields().forEach { (_, группы) ->
                группы.forEach { группа ->
                    группа.path("activities").forEach { дело ->
                        названия[дело.path("code").asText()] = дело.path("name").asText()
                        входы[дело.path("code").asText()] = дело.path("inputs").map { it.asText() }
                    }
                }
            }
        }
        return входы.mapValues { (_, список) ->
            список.map { вход -> названия[вход.trim()] ?: вход }
        }
    }

    private fun проверить(
        project: String,
        check: String,
        прожитые: Set<String>,
        начатые: Set<String>,
        пройдены: Set<String>,
    ): String? = when {
        check.startsWith("scene_done:") ->
            if (check.removePrefix("scene_done:") in прожитые) null
            else "сцена ${check.removePrefix("scene_done:")} ещё не прожита"

        // Связь SS (start-to-start): сцена началась — вход открыт, ждать конца не надо.
        check.startsWith("scene_started:") ->
            if (check.removePrefix("scene_started:") in начатые) null
            else "сцена ${check.removePrefix("scene_started:")} ещё не начата"

        check.startsWith("gate_passed:") ->
            if (check.removePrefix("gate_passed:") in пройдены) null
            else "точка ${check.removePrefix("gate_passed:")} ещё не пройдена"

        else -> оценщик.why(project, check)
    }
}
