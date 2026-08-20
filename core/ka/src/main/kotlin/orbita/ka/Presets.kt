// Библиотека пресетов платформ (TZ-KA-001): редактируемая конфигурация
// (JSON-ресурс, переопределение — ORBITA_PLATFORM_PRESETS), не код.
//
// Неполный пресет ВЫЯВЛЯЕТСЯ, а не подставляет умолчания (шаг 10.3). Разница
// принципиальная: пропущенная масса, взятая как ноль, даёт правдоподобный
// массовый бюджет с запасом на пустом месте, и заметить это в отчёте нечем.
// Поэтому библиотека отказывается собраться целиком, а не портит одну строку.
package orbita.ka

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.model.requireLibraryComplete
import java.nio.file.Files
import java.nio.file.Path

data class PlatformPreset(
    val id: String,
    val name: String,
    val dryMassKg: Double,
    val saAreaM2: Double,
    val saEfficiency: Double,
    val batteryWh: Double,
    val batteryMaxDod: Double,
    val busPowerW: Double,
    val payloadPowerW: Double,
    val pointingAccuracyDeg: Double,
    val designLifeYears: Double,
    /** Линии, которые платформа несёт: состав радиокомплекса зависит от класса. */
    val links: List<String>,
    /** Основание оценки: откуда взяты числа и когда они перестанут быть оценкой. */
    val source: String,
) {
    fun powerModel(modes: List<ModeSlot> = emptyList()): PowerModel = PowerModel(
        sa = SolarArray(saAreaM2, saEfficiency),
        battery = Battery(batteryWh, batteryMaxDod),
        busPowerW = busPowerW,
        payloadPowerW = payloadPowerW,
        modes = modes,
    )
}

class PlatformPresets(json: String = defaultJson()) {

    val presets: List<PlatformPreset> = mapper.readTree(json).path("presets").toList()
        .also { requireLibraryComplete(it, REQUIRED_FIELDS, "библиотека пресетов платформ") }
        .map { p ->
            PlatformPreset(
                id = p.path("id").asText(),
                name = p.path("name").asText(),
                dryMassKg = p.path("dry_mass_kg").asDouble(),
                saAreaM2 = p.path("sa_area_m2").asDouble(),
                saEfficiency = p.path("sa_efficiency").asDouble(),
                batteryWh = p.path("battery_wh").asDouble(),
                batteryMaxDod = p.path("battery_max_dod").asDouble(),
                busPowerW = p.path("bus_power_w").asDouble(),
                payloadPowerW = p.path("payload_power_w").asDouble(),
                pointingAccuracyDeg = p.path("pointing_accuracy_deg").asDouble(),
                designLifeYears = p.path("design_life_years").asDouble(),
                links = p.path("links").map { it.asText() },
                source = p.path("source").asText(),
            )
        }

    fun byId(id: String): PlatformPreset =
        presets.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("unknown platform preset '$id'; known: ${presets.map { it.id }}")

    companion object {
        private val mapper = ObjectMapper()

        /**
         * Обязательные поля пресета. Числовой ноль здесь — отсутствие: платформа
         * без массы, без площади СБ или без срока службы не пресет, а пробел
         * в библиотеке.
         */
        val REQUIRED_FIELDS: List<String> = listOf(
            "id", "name", "dry_mass_kg", "sa_area_m2", "sa_efficiency",
            "battery_wh", "battery_max_dod", "bus_power_w", "payload_power_w",
            "pointing_accuracy_deg", "design_life_years", "links", "source",
        )

        fun defaultJson(): String {
            System.getenv("ORBITA_PLATFORM_PRESETS")?.let { return Files.readString(Path.of(it)) }
            return PlatformPresets::class.java.getResourceAsStream("/orbita/ka/platform-presets.json")!!
                .use { it.readAllBytes().decodeToString() }
        }
    }
}
