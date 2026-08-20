// Адаптер LoRaWAN / LR-FHSS (TZ-NET-001, TZ-NET-002, TZ-NET-006).
// Единственный источник параметров канала: режимы, MAC, модель коллизий.
// Наружу отдаётся ТОЛЬКО документ contracts/protocol-adapter (TZ-COM-007);
// другие модули не импортируют классы адаптера. Замена протокола — новая
// реализация плюс пересборка; динамической загрузки нет.
package orbita.net

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.math.log10

/**
 * Режим физического уровня. requiredEbn0Db — свойство ПРОТОКОЛА:
 * появление такого числа вне core/net означает нарушение абстракции
 * (STEP-3, ловушка 3).
 */
data class PhyMode(
    val modeId: String,
    val bitrateBps: Double,
    val requiredEbn0Db: Double,
    val dopplerToleranceHz: Double,
)

class LoRaWanAdapter(private val includeLrFhss: Boolean = true) {

    // Собственного идентификатора у адаптера больше нет: после CR-005/ADR-021
    // адаптер — хранимый объект модели, и идентификатор ему выдаёт модель
    // (PA-NNNN), а не код. Два идентификатора у одной сущности разошлись бы.
    val name = "lorawan"
    val version = "0.1"

    /**
     * Режимы SF7–SF12 (полоса 125 кГц): скорость по спецификации LoRaWAN,
     * требуемое Eb/N0 выведено из порогового SNR демодулятора CSS
     * (Semtech SX127x: −7.5…−20 дБ): Eb/N0 = SNR_min + 10·lg(BW/R).
     * LR-FHSS (DR8/DR9) — вариант спутникового канала. Значения — типовые
     * проектные, подлежат калибровке (TZ-NET-004).
     */
    val modes: List<PhyMode> = buildList {
        val bw = 125_000.0
        listOf(
            Triple("SF7", 5470.0, -7.5), Triple("SF8", 3125.0, -10.0),
            Triple("SF9", 1758.0, -12.5), Triple("SF10", 977.0, -15.0),
            Triple("SF11", 537.0, -17.5), Triple("SF12", 293.0, -20.0),
        ).forEach { (id, rate, snrMin) ->
            add(PhyMode(id, rate, snrMin + 10 * log10(bw / rate), dopplerToleranceHz = bw / 4))
        }
        if (includeLrFhss) {
            add(PhyMode("LR-FHSS-DR8", 162.0, 4.5, dopplerToleranceHz = 25_000.0))
            add(PhyMode("LR-FHSS-DR9", 325.0, 4.5, dopplerToleranceHz = 25_000.0))
        }
    }

    val overheadBytes = 13          // заголовок LoRaWAN MAC
    val ackSupported = true
    val downlinkSupported = true    // обязателен: маяк эфемерид (Р5) и ACK (Р6)

    fun mode(modeId: String): PhyMode =
        modes.firstOrNull { it.modeId == modeId }
            ?: throw IllegalArgumentException("unknown mode '$modeId'; known: ${modes.map { it.modeId }}")

    /** Время эфира кадра, с: (полезная нагрузка + оверхед MAC) / скорость режима. */
    fun timeOnAirS(modeId: String, payloadBytes: Int): Double =
        (payloadBytes + overheadBytes) * 8.0 / mode(modeId).bitrateBps

    /**
     * Документ contracts/protocol-adapter — форма обмена и хранения (ADR-021).
     * [objectId] выдаёт модель: адаптер сам себе идентификатор не назначает.
     */
    fun toContractJson(objectId: String, mapper: ObjectMapper = ObjectMapper()): ObjectNode {
        val root = mapper.createObjectNode()
        root.put("id", objectId).put("name", name)
        val phy = root.putObject("phy")
        phy.put("modulation", "css")
        val arr = phy.putArray("modes")
        modes.forEach { m ->
            arr.addObject()
                .put("mode_id", m.modeId)
                .put("bitrate_bps", m.bitrateBps)
                .put("required_ebn0_db", m.requiredEbn0Db)
                .put("time_on_air_ms_per_byte", 8000.0 / m.bitrateBps)
                .put("doppler_tolerance_hz", m.dopplerToleranceHz)
        }
        phy.put("capture_effect", true)
        root.putObject("mac")
            .put("access", "aloha")
            .put("overhead_bytes", overheadBytes)
            .put("ack_supported", ackSupported)
            .put("downlink_supported", downlinkSupported)
            .put("duty_cycle_enforced", true)
        root.putObject("collision_model")
            .put("type", "analytic_capture")
            .put("orthogonal_modes", true)
        root.putObject("calibration")
            .put("reference", "none")
            .put("dataset_ref", "spec/reference/net_aloha_reference.json")
            .put("max_deviation_pct", 5.0)
        return root
    }
}

/**
 * Совместимость адаптера с решениями проекта (TZ-NET-002): адаптер без
 * поддержки нисходящего канала несовместим с Р5 (маяк эфемерид, ADR-005)
 * и Р6 (эфемеридный backoff по ACK, ADR-006).
 */
fun validateAdapterContract(doc: com.fasterxml.jackson.databind.JsonNode): List<String> = buildList {
    if (!doc.path("mac").path("downlink_supported").asBoolean(false)) {
        add("TZ-NET-002 (Р5/ADR-005, Р6/ADR-006): adapter without downlink support is incompatible — ephemeris beacon and ACK require a downlink")
    }
}
