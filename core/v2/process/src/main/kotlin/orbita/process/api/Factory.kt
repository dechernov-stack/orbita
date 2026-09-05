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
    ): ProcessEngine = TemplateProcessEngine(template, evaluator, passedGates, gatePlan)
}
