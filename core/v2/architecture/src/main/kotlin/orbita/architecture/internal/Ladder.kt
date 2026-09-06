// Лестница зрелости — ДАННЫМИ полки, а не памятью инженера.
//
// Полка `component_template_shelf` несёт для каждого рода компонента набор
// граней по ступеням (MCR → SRR → SDR → PDR). Правило монотонности: грань,
// обязательная на ступени N, обязательна и на всех следующих.
package orbita.architecture.internal

import orbita.kernel.api.Area
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore

internal class Ladder(private val store: EntityStore) {

    /** Порядок ступеней; всё, что левее точки, уже обязано быть закрыто. */
    val ступени = listOf("MCR", "SRR", "SDR", "PDR")

    private val полка by lazy {
        store.list(Area.Library, "component_template_shelf").maxByOrNull { it.version }
    }

    /**
     * Имена граней в полке несут уточнения в скобках («functions(list)»,
     * «exchange_items(data ifaces)») и разные написания одного и того же
     * («budget_compute» и «budgets»). Карточка знает грани по ключам, поэтому
     * имя полки приводится к ключу здесь — в одном месте.
     */
    fun ключГрани(имя: String): String {
        val голое = имя.substringBefore("(").trim()
        return when {
            голое == "trl" -> "technologies"
            голое == "questionnaire" || голое == "resources_params" || голое == "criticality" -> "parameters"
            голое.startsWith("budget") -> "budgets"
            голое.startsWith("verification") || голое == "wcet" || голое == "test_plan" -> "verification"
            голое.startsWith("product_version") || голое == "configuration_item" -> "configuration"
            голое == "icd_signed" || голое == "icd" || голое == "icd_draft" -> "documents"
            голое == "deployed_on" -> "deployment"
            голое == "state_machine" -> "modes"
            голое == "site_or_count" || голое == "capacity_rough" || голое == "class" ||
                голое == "link_params" || голое == "battery" || голое == "cost_target" ||
                голое == "protocol" || голое == "autonomy" || голое == "availability" ||
                голое == "redundancy" || голое == "staffing" || голое == "session_energy" ||
                голое == "certification_path" || голое == "bom_cost" || голое == "measured_energy" ||
                голое == "params" || голое == "procurement" || голое == "loads" -> "parameters"
            голое == "constraint_refs" || голое == "contract" || голое == "acceptance_tests" -> "documents"
            else -> голое
        }
    }

    /**
     * Грани, которые регламент требует независимо от решения конструктора
     * (ГЛУБИНА §6): их можно потребовать раньше, но нельзя снять. Масса и
     * мощность к MCR — это MEL, а идентичность — сам узел.
     */
    private val жёсткие = setOf("identity", "parameters")

    /** Грани, обязательные к точке `gate` для рода `nature` (с монотонностью). */
    fun обязательные(nature: String, gate: String): Map<String, String> {
        val умолчания = полка?.doc?.path("facet_defaults")?.path(nature) ?: return emptyMap()
        val предел = ступени.indexOf(gate.uppercase()).let { if (it < 0) ступени.lastIndex else it }
        val результат = LinkedHashMap<String, String>()
        ступени.take(предел + 1).forEach { ступень ->
            умолчания.path(ступень).forEach { грань ->
                // Первая ступень, на которой грань появилась, и есть её срок:
                // монотонность значит «не пропадает», а не «переносится».
                результат.putIfAbsent(ключГрани(грань.asText()), ступень)
            }
        }
        return результат
    }

    /**
     * Лестница шаблона — умолчание, а не приговор: у узла есть
     * `maturity_tailoring[]` (ГЛУБИНА §6). Раньше — можно; позже и снять —
     * только с обоснованием, и жёсткую грань не снимает даже оно.
     *
     * @return грани к точке с учётом правок конструктора и список того, что
     *   он отклонил (для SEMP §9 и FA §6 — со следом, не молча)
     */
    fun обязательныеДляУзла(узел: Entity, nature: String, gate: String): Map<String, String> {
        val базовые = обязательные(nature, gate).toMutableMap()
        val порядок = ступени.indexOf(gate.uppercase()).let { if (it < 0) ступени.lastIndex else it }

        узел.doc.path("maturity_tailoring").forEach { правка ->
            val грань = ключГрани(правка.path("target").asText(""))
            val действие = правка.path("action").asText("")
            val кТочке = правка.path("to_gate").asText("").ifBlank { null }
            // Обоснование обязательно: правка лестницы уходит в отклонения
            // от нормы, а отклонение без причины неотличимо от забывчивости.
            if (правка.path("rationale").asText("").isBlank() || правка.path("by").asText("").isBlank()) return@forEach

            when (действие) {
                "remove" -> if (грань !in жёсткие) базовые.remove(грань)
                "add" -> if (кТочке == null || ступени.indexOf(кТочке.uppercase()) <= порядок) {
                    базовые[грань] = кТочке ?: gate.uppercase()
                }
                "shift" -> {
                    val новая = кТочке?.uppercase() ?: return@forEach
                    if (грань in жёсткие) return@forEach
                    if (ступени.indexOf(новая) > порядок) базовые.remove(грань) else базовые[грань] = новая
                }
            }
        }
        return базовые
    }
}
