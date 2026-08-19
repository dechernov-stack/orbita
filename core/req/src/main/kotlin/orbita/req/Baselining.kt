// Условия перевода требования в Baseline (TZ-REQ-006, CR-002/ADR-018).
// Эталоны: spec/requirements_semantics.py::can_baseline и
// spec/verification_semantics.py. Полнота верификации — условие БАЗИРОВАНИЯ,
// а не сохранения: черновик допускается неполным, иначе работа встанет
// (CR-002, ловушка 5).
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
        } else {
            // метод назначен — проверяется содержательность описания (CR-002)
            reasons += verificationIssues(req)
        }
        return reasons.isEmpty() to reasons
    }
}
