// Соответствие нарушений схем решениям Р1–Р9 (TZ-MOD §2, TZ-MOD-003, TZ-COM-008).
// Ограничения выражены в самих схемах (const/enum/minimum/maximum); здесь — только
// обратная привязка «где сработало правило → какой ADR нарушен» для текста ошибки.
package orbita.mod.schema

object AdrMap {

    private data class Rule(
        val schemaFile: String,   // схема, в которой определено правило (учитывает $ref)
        val pointer: Regex,       // путь до поля в проверяемом документе
        val adr: String,
        val decision: String,
    )

    private val rules = listOf(
        Rule("contracts/spacecraft", Regex("^/payload/architecture$"), "ADR-001", "Р1"),
        Rule("contracts/spacecraft", Regex("^/platform/(dry|wet)_mass_kg$"), "ADR-002", "Р2"),
        Rule("contracts/radio-link", Regex("(^|/)medium$"), "ADR-003", "Р3"),
        Rule("contracts/spacecraft", Regex("^/modes(/.*)?$"), "ADR-004", "Р4"),
        Rule("contracts/terminal-profile", Regex("^/ephemeris(/.*)?$"), "ADR-005", "Р5"),
        Rule("contracts/spacecraft", Regex("^/payload/ephemeris_beacon(/.*)?$"), "ADR-005", "Р5"),
        Rule("contracts/terminal-profile", Regex("^/reliability_policy/backoff$"), "ADR-006", "Р6"),
        Rule("contracts/terminal-profile", Regex("^/mobility(/.*)?$"), "ADR-007", "Р7"),
        Rule("contracts/demand-map", Regex("^/layers(/.*)?$"), "ADR-008", "Р8"),
        Rule("core/service", Regex("^/qos_profiles(/.*)?$"), "ADR-009", "Р9"),
    )

    /**
     * ADR для сработавшего правила валидации.
     * [schemaLocation] — абсолютный адрес правила в схеме (различает вложенные $ref:
     * radio-link внутри spacecraft); [instancePath] — JSON Pointer в документе.
     */
    fun adrFor(schemaLocation: String, instancePath: String): String? =
        rules.firstOrNull { r ->
            schemaLocation.contains("${r.schemaFile}.schema.json") && r.pointer.containsMatchIn(instancePath)
        }?.let { "${it.adr} (${it.decision})" }
}
