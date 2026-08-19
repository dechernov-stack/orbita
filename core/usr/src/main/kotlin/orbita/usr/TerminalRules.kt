// Профили терминалов (TZ-USR-001, TZ-USR-002). Схема terminal-profile держит
// Р5/Р7 (эфемериды, подвижность — с ADR в тексте ошибки); классозависимые
// правила невыразимы схемой и живут здесь: C' без времени реакции и B' без
// политики повторов отклоняются.
package orbita.usr

import com.fasterxml.jackson.databind.JsonNode
import orbita.mod.schema.SchemaRegistry
import orbita.mod.schema.ValidationError

class TerminalRules(private val registry: SchemaRegistry) {

    /** Полная проверка профиля: нормативная схема + правила классов. */
    fun validate(doc: JsonNode): List<ValidationError> {
        val errors = registry.validate("contracts/terminal-profile", doc).toMutableList()
        when (doc.path("consumer_class").asText("")) {
            "C_prime" -> if (!doc.path("control_loop").path("required_reaction_time_s").isNumber) {
                errors += ValidationError(
                    "/control_loop/required_reaction_time_s", "required",
                    "TZ-USR-001: C_prime profile requires control_loop.required_reaction_time_s",
                )
            }
            "B_prime" -> if (doc.path("reliability_policy").let { it.isMissingNode || it.isNull || it.isEmpty }) {
                errors += ValidationError(
                    "/reliability_policy", "required",
                    "TZ-USR-001: B_prime profile requires reliability_policy (retries)",
                )
            }
        }
        return errors
    }

    /**
     * Деградированный режим (TZ-USR-002, Р5/ADR-005): возраст альманаха сверх
     * допустимого умножает интенсивность передач на degraded_rate_factor.
     * Штатный режим возвращает множитель 1.0.
     */
    fun rateFactor(profile: JsonNode, almanacAgeS: Double): Double {
        val eph = profile.path("ephemeris")
        val maxAge = eph.path("max_almanac_age_s").asDouble(Double.POSITIVE_INFINITY)
        return if (almanacAgeS > maxAge) eph.path("degraded_rate_factor").asDouble(0.0) else 1.0
    }

    /**
     * Время решения внешней системы — параметр, ИС его не вычисляет
     * (TZ-USR-001): значение читается из профиля как есть.
     */
    fun externalDecisionTimeS(profile: JsonNode): Double? =
        profile.path("control_loop").path("external_decision_time_s")
            .let { if (it.isNumber) it.asDouble() else null }
}
