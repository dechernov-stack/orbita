// Библиотека пресетов платформ (TZ-KA-001): редактируемая конфигурация
// (JSON-ресурс, переопределение — ORBITA_PLATFORM_PRESETS), не код.
package orbita.ka

import com.fasterxml.jackson.databind.ObjectMapper
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

    val presets: List<PlatformPreset> = mapper.readTree(json).path("presets").map { p ->
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
        )
    }

    fun byId(id: String): PlatformPreset =
        presets.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("unknown platform preset '$id'; known: ${presets.map { it.id }}")

    companion object {
        private val mapper = ObjectMapper()

        fun defaultJson(): String {
            System.getenv("ORBITA_PLATFORM_PRESETS")?.let { return Files.readString(Path.of(it)) }
            return PlatformPresets::class.java.getResourceAsStream("/orbita/ka/platform-presets.json")!!
                .use { it.readAllBytes().decodeToString() }
        }
    }
}
