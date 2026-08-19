// Условия перевода требования в Baseline (TZ-REQ-006). Поведение — эталон
// spec/requirements_semantics.py::can_baseline: контроль качества пройден,
// нет незакрытых TBD/TBR, назначен метод верификации.
package orbita.req

import com.fasterxml.jackson.databind.JsonNode
import orbita.mod.store.StoreException

class BaselineBlockedException(val reasons: List<String>) :
    StoreException("TZ-REQ-006: baseline blocked: " + reasons.joinToString("; "))

class Baselining(private val quality: QualityControl = QualityControl()) {

    /** (пригодно, причины блокировки). Пустые причины = требование базируется. */
    fun canBaseline(req: JsonNode): Pair<Boolean, List<String>> {
        val reasons = mutableListOf<String>()
        reasons += quality.check(req)
        if (hasOpenTbd(req)) reasons += "незакрытые TBD/TBR"
        if (req.path("verification").path("method").asText("").isBlank()) {
            reasons += "не назначен метод верификации"
        }
        return reasons.isEmpty() to reasons
    }
}
