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
        /** Счёт выходов мероприятий по данным домена. */
        outputCounter: OutputCounter? = null,
        /** Замечания обзора проекта — события возврата в сцену (шип D). */
        findings: ((String) -> List<FindingView>)? = null,
        /** Решения точек — последнее по каждой. */
        decisions: ((String) -> Map<String, DecisionView>)? = null,
        /** Фаза проекта по данным; пусто — из шаблона. */
        phaseOf: ((String) -> String?)? = null,
        /** Запись решения: проект · точка · кем · исход · помета · открываемая фаза. */
        onDecision: ((String, String, String, String, String?, String?) -> Unit)? = null,
    ): ProcessEngine = TemplateProcessEngine(
        шаблон = template,
        оценщик = evaluator,
        пройденныеТочки = passedGates,
        планТочек = gatePlan,
        окнаСцен = sceneWindows,
        процессы = processReference,
        счётВыходов = outputCounter,
        замечания = findings,
        решения = decisions,
        фазаПроекта = phaseOf,
        наРешение = onDecision,
    )
}
