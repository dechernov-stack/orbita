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
import orbita.process.api.GateEvaluator
import orbita.process.api.LaneView
import orbita.process.api.OutputCounter
import orbita.process.api.OutputView
import orbita.process.api.GateView
import orbita.process.api.PhaseView
import orbita.process.api.ProcessEngine
import orbita.process.api.SceneState
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
) : ProcessEngine {

    private val шаблоныПроектов = mutableMapOf<String, String>()

    override fun openPhase(project: String, templateCode: String): PhaseView {
        шаблоныПроектов[project] = templateCode
        return view(project)
    }

    override fun view(project: String): PhaseView {
        val код = шаблоныПроектов[project] ?: "PHT-9001"
        val док = шаблон(код)
        val пройдены = пройденныеТочки(project)

        // Сцены считаются по порядку: состояние следующей зависит от предыдущих
        val прожитые = mutableSetOf<String>()
        val потоки = входныеПотоки()
        val окна = окнаСцен?.invoke(project).orEmpty()

        // Кто кого ждёт: считается по самому шаблону — второй карты связей
        // между сценами нет, и разойтись ей не с чем.
        val ждут = mutableMapOf<String, MutableList<String>>()
        док.path("scenes").forEach { сцена ->
            сцена.path("entry").forEach { условие ->
                val ссылка = условие.path("check").asText().removePrefix("scene_done:")
                if (условие.path("check").asText().startsWith("scene_done:")) {
                    ждут.getOrPut(ссылка) { mutableListOf() } +=
                        "${сцена.path("key").asText()} · ${сцена.path("title").asText()}"
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

        val сцены = док.path("scenes").sortedBy { it.path("order").asInt() }.map { сцена ->
            val ключ = сцена.path("key").asText()
            val условияВхода = сцена.path("entry").map { условие ->
                val причина = проверить(project, условие.path("check").asText(), прожитые, пройдены)
                ConditionView(
                    условие.path("title").asText(условие.path("check").asText()),
                    условие.path("check").asText(), причина == null, причина,
                )
            }
            val условияВыхода = сцена.path("exit").map { условие ->
                val причина = проверить(project, условие.path("check").asText(), прожитые, пройдены)
                ConditionView(
                    условие.path("title").asText(условие.path("check").asText()),
                    условие.path("check").asText(), причина == null, причина,
                )
            }
            val причиныВхода = условияВхода.mapNotNull { it.why }
            val причиныВыхода = условияВыхода.mapNotNull { it.why }
            val состояние = when {
                причиныВхода.isNotEmpty() -> SceneState.LOCKED
                причиныВыхода.isEmpty() -> SceneState.DONE
                else -> SceneState.OPEN
            }
            if (состояние == SceneState.DONE) прожитые += ключ
            SceneView(
                key = ключ,
                title = сцена.path("title").asText(),
                order = сцена.path("order").asInt(),
                role = сцена.path("role").asText(""),
                question = сцена.path("question").asText(""),
                state = состояние,
                blockers = if (состояние == SceneState.LOCKED) причиныВхода else причиныВыхода,
                steps = сцена.path("steps").map { шаг ->
                    val проверка = шаг.path("check").asText("")
                    StepView(
                        title = шаг.path("title").asText(),
                        place = шаг.path("place").asText(""),
                        hint = шаг.path("hint").asText(""),
                        // Шаг закрыт своим условием, если оно названо; иначе —
                        // вместе со сценой: врать про «сделано» шаг не должен.
                        done = if (проверка.isBlank()) состояние == SceneState.DONE
                        else проверить(project, проверка, прожитые, пройдены) == null,
                    )
                },
                entry = условияВхода,
                exit = условияВыхода,
                output = сцена.path("output").asText(""),
                awaitedBy = ждут[ключ].orEmpty(),
                inputFlows = сцена.path("process_ref").flatMap { потоки[it.asText()].orEmpty() },
                window = окна[ключ],
                activities = мероприятия(project, сцена, ключ, причиныВхода, ждутМероприятие),
            )
        }

        val план = планТочек(project)
        val точки = док.path("points").sortedBy { it.path("order").asInt() }.map { точка ->
            val ключ = точка.path("key").asText()
            val блокирующие = точка.path("criteria")
                .filter { it.path("blocking").asBoolean(true) }
                .mapNotNull { проверить(project, it.path("check").asText(), прожитые, пройдены) }
            GateView(
                key = ключ,
                title = точка.path("title").asText(),
                order = точка.path("order").asInt(),
                plannedDate = план[ключ] ?: LocalDate.now().plusDays(точка.path("offset_days").asLong(30)).toString(),
                passed = ключ in пройдены,
                blocking = блокирующие,
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
            phase = док.path("phase").asText(""),
            currentScene = сцены.firstOrNull { it.state == SceneState.OPEN }?.key,
            scenes = сцены,
            gates = точки,
            lanes = дорожки,
        )
    }

    override fun passGate(project: String, gate: String, decidedBy: String): PhaseView {
        val вид = view(project)
        val точка = вид.gates.firstOrNull { it.key == gate }
            ?: error("точки «$gate» нет в фазе проекта")
        // Отказ приходит от движка, а не от интерфейса: блокирующее
        // невыполненное условие не даёт зафиксировать точку.
        require(точка.blocking.isEmpty()) {
            "точка «${точка.title}» держится: " + точка.blocking.joinToString("; ")
        }
        пройденныеТочки(project).add(gate)
        return view(project)
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
        пройдены: Set<String>,
    ): String? = when {
        check.startsWith("scene_done:") ->
            if (check.removePrefix("scene_done:") in прожитые) null
            else "сцена ${check.removePrefix("scene_done:")} ещё не прожита"

        check.startsWith("gate_passed:") ->
            if (check.removePrefix("gate_passed:") in пройдены) null
            else "точка ${check.removePrefix("gate_passed:")} ещё не пройдена"

        else -> оценщик.why(project, check)
    }
}
