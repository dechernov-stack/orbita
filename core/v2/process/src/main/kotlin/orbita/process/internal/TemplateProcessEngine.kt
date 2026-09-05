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
import orbita.process.api.GateEvaluator
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
        val сцены = док.path("scenes").sortedBy { it.path("order").asInt() }.map { сцена ->
            val ключ = сцена.path("key").asText()
            val причиныВхода = сцена.path("entry").mapNotNull { условие ->
                проверить(project, условие.path("check").asText(), прожитые, пройдены)
            }
            val причиныВыхода = сцена.path("exit").mapNotNull { условие ->
                проверить(project, условие.path("check").asText(), прожитые, пройдены)
            }
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
                    StepView(
                        title = шаг.path("title").asText(),
                        place = шаг.path("place").asText(""),
                        hint = шаг.path("hint").asText(""),
                        done = состояние == SceneState.DONE,
                    )
                },
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

        return PhaseView(
            project = project,
            standard = док.path("standard").asText(""),
            phase = док.path("phase").asText(""),
            currentScene = сцены.firstOrNull { it.state == SceneState.OPEN }?.key,
            scenes = сцены,
            gates = точки,
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
