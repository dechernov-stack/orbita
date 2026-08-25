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

/**
 * Полный вердикт базирования: что блокирует, что из блокирующего отводимо
 * (правила качества формулировок — эвристики, инженер вправе отвести с
 * обоснованием), что уже отведено. TBD, полнота плана верификации и
 * распределение интерфейсного требования НЕ отводимы: это не эвристики.
 */
data class BaselineVerdict(
    val blocking: List<String>,
    /** Подмножество blocking, которое инженер вправе отвести вейвером. */
    val waivable: Set<String>,
    /** Уже отведённые: текст правила → обоснование инженера. */
    val waived: Map<String, String>,
) {
    val ok: Boolean get() = blocking.isEmpty()
}

class Baselining(private val quality: QualityControl = QualityControl()) {

    /**
     * (пригодно, причины блокировки). Пустые причины = требование базируется.
     *
     * [nodes] — дерево изделия проекта (элементы и интерфейсы): распределение
     * интерфейсного требования проверяется по нему. Параметр обязателен
     * намеренно: с пустым деревом проверка сказала бы «интерфейс не имеет двух
     * сторон» про исправное требование, и умолчание прятало бы это молча.
     */
    fun canBaseline(req: JsonNode, nodes: Map<String, ProductNode>): Pair<Boolean, List<String>> =
        verdict(req, nodes).let { it.ok to it.blocking }

    /** Полный вердикт: блокирующее, отводимое, отведённое. */
    fun verdict(req: JsonNode, nodes: Map<String, ProductNode>): BaselineVerdict {
        // Вейвер действует на ТОЧНЫЙ текст замечания: изменилось правило —
        // изменился текст, вейвер гаснет, решение пересматривается
        val waivers = req.path("quality_waivers").associate {
            it.path("rule").asText() to it.path("rationale").asText()
        }
        val qualityIssues = quality.check(req)
        val waived = qualityIssues.filter { it in waivers }
        val reasons = mutableListOf<String>()
        reasons += qualityIssues.filterNot { it in waivers }
        if (hasOpenTbd(req)) reasons += "незакрытые TBD/TBR"
        // CR-003: интерфейсное требование распределяется на интерфейс с двумя
        // сторонами. Проверка полноты — условие БАЗИРОВАНИЯ, а не сохранения
        // (та же ловушка 5): на шаге «требования из сервисов» дерева изделия
        // ещё нет, и черновик интерфейсного требования обязан записываться
        // без распределения. Ссылочная целостность самого allocated_to
        // остаётся на записи (TZ-REQ-005): туда пишут только то, что есть.
        interfaceAllocationValid(req, nodes).let { (ok, why) -> if (!ok && why != null) reasons += why }
        // CR-003: верификация описывается событиями; требование должно иметь план
        val events = req.path("verification_events")
        if (!events.isArray || events.isEmpty) {
            reasons += "не назначен метод верификации"
        } else {
            // CR-002/CR-003: содержательность каждого события и корректность плана
            reasons += verificationPlanIssues(req)
            if (verificationState(req) == VerificationState.PlanIncomplete) {
                reasons += VerificationState.PlanIncomplete.label
            }
        }
        return BaselineVerdict(
            blocking = reasons,
            waivable = qualityIssues.toSet(),
            waived = waived.associateWith { waivers[it] ?: "" },
        )
    }
}
