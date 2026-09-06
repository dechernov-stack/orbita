// Фабрика движка процесса: наружу отдаётся порт ProcessEngine.
package orbita.process.api

import com.fasterxml.jackson.databind.JsonNode
import orbita.process.internal.TemplateProcessEngine

object ProcessFactory {
    fun engine(
        template: (String) -> JsonNode,
        evaluator: GateEvaluator,
        passedGates: (String) -> MutableSet<String>,
        gatePlan: (String) -> Map<String, String>,
        /** Справочник процессов ЖЦ: входные потоки сцен приходят данными. */
        processReference: (() -> JsonNode?)? = null,
        /** Окна плана работ фазы: сцена → (начало, конец). */
        sceneWindows: ((String) -> Map<String, Pair<String, String>>)? = null,
    ): ProcessEngine = TemplateProcessEngine(
        шаблон = template,
        оценщик = evaluator,
        пройденныеТочки = passedGates,
        планТочек = gatePlan,
        окнаСцен = sceneWindows,
        процессы = processReference,
    )
}
