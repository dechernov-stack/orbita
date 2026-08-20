// Режимы и географические маски (TZ-KA-009, Р4/ADR-004).
// Поведение — эталон spec/spacecraft_semantics.py, один в один.
//
// Расписание режимов КА в первой очереди формируется СТАТИЧЕСКИМИ масками,
// генерируемыми из карты спроса и зон наземных станций. Модель и Монте-Карло
// от способа формирования не зависят: маски выдают те же доли витка, что
// модель хранит в `modes[].orbit_fraction`, — потребители расписания
// (энергетика PowerModel, документ аппарата) не отличают источник.
//
// Динамический планировщик — ИНТЕРФЕЙС-ЗАГЛУШКА: вызов даёт явную ошибку,
// а не тихое статическое расписание под видом динамического. Цена интерфейса
// мала — одна функция «сценарий → расписание»; переход к динамике во второй
// очереди не потребует пересмотра модели (ADR-004).
package orbita.ka

import com.fasterxml.jackson.databind.JsonNode
import orbita.bal.footprintRadiusKm
import java.security.MessageDigest
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Точка маски: широта и долгота, градусы. */
data class MaskPoint(val lat: Double, val lon: Double)

/**
 * Статические маски режимов: зоны приёма (ячейки спроса), зоны сброса
 * (станции) и версия. Версия — свёртка содержимого: изменение карты спроса
 * или состава станций обязано перегенерировать маску, и это видно по версии.
 */
data class GeoMasks(
    val rxCells: List<MaskPoint>,
    val rxRadiusKm: Double,
    val downlinkCells: List<MaskPoint>,
    val downlinkRadiusKm: Double,
    val version: String,
)

/**
 * Маски из карты спроса и набора станций. В маску приёма входят только
 * ячейки с НЕНУЛЕВЫМ весом: ячейка без спроса — не повод включать приёмник.
 *
 * Радиус зоны приёма — по углу места ГРАНИЦЫ ОБСЛУЖИВАНИЯ, а не видимости:
 * там, где линия не замыкается, слушать нечего (TZ-MOD-006).
 */
fun buildMasks(
    demandMap: JsonNode,
    groundStations: JsonNode,
    altKm: Double,
    serviceElevDeg: Double = 25.0,
    stationMinElevDeg: Double = 10.0,
): GeoMasks {
    val rx = demandMap.path("cells")
        .filter { c -> c.path("demand").any { it.path("weight").asDouble(0.0) > 0.0 } }
        .map { MaskPoint(it.path("lat_deg").asDouble(), it.path("lon_deg").asDouble()) }
        .sortedWith(compareBy({ it.lat }, { it.lon }))
    val dl = groundStations.path("stations")
        .map { MaskPoint(it.path("lat_deg").asDouble(), it.path("lon_deg").asDouble()) }
        .sortedWith(compareBy({ it.lat }, { it.lon }))

    val md = MessageDigest.getInstance("SHA-256")
    (rx + dl).forEach { md.update("${it.lat}|${it.lon};".toByteArray()) }
    md.update("$altKm|$serviceElevDeg|$stationMinElevDeg".toByteArray())

    return GeoMasks(
        rxCells = rx,
        rxRadiusKm = footprintRadiusKm(altKm, serviceElevDeg),
        downlinkCells = dl,
        downlinkRadiusKm = footprintRadiusKm(altKm, stationMinElevDeg),
        version = md.digest().joinToString("") { "%02x".format(it) }.take(16),
    )
}

/**
 * Режим в точке трассы. Приоритет: сброс > приём > дежурство — над станцией
 * аппарат сбрасывает буфер, даже если под ним есть спрос.
 */
fun classifyPoint(lat: Double, lon: Double, masks: GeoMasks): String = when {
    masks.downlinkCells.any { maskArcKm(lat, lon, it.lat, it.lon) <= masks.downlinkRadiusKm } -> "downlink"
    masks.rxCells.any { maskArcKm(lat, lon, it.lat, it.lon) <= masks.rxRadiusKm } -> "rx"
    else -> "standby"
}

/** Доли витка по маскам вдоль трассы. Сумма равна единице по построению. */
fun modeFractions(track: List<MaskPoint>, masks: GeoMasks): Map<String, Double> {
    require(track.isNotEmpty()) { "трасса пуста: доли витка не определены" }
    val counts = linkedMapOf("standby" to 0, "rx" to 0, "downlink" to 0)
    for (p in track) counts.merge(classifyPoint(p.lat, p.lon, masks), 1, Int::plus)
    return counts.mapValues { (_, c) -> c.toDouble() / track.size }
}

/**
 * Циклограмма из масок: те же `ModeSlot`, что модель строит из
 * `modes[].orbit_fraction`. Потребление режимов приходит из МОДЕЛИ аппарата —
 * маски знают, ГДЕ аппарат что делает, а не сколько это стоит.
 */
fun maskSchedule(
    track: List<MaskPoint>,
    masks: GeoMasks,
    modePowerW: Map<String, Double>,
): List<ModeSlot> = modeFractions(track, masks).map { (name, fraction) ->
    val power = modePowerW[name]
        ?: throw IllegalArgumentException("режим '$name' не описан в модели аппарата: нечем заполнить потребление")
    ModeSlot(name = name, fraction = fraction, powerW = power)
}

/** Интерфейс-заглушка динамического планировщика (ADR-004): явный отказ,
 * а не тихое статическое расписание под видом динамического. */
object DynamicScheduler {
    @Suppress("UNUSED_PARAMETER")
    fun schedule(scenarioRef: String): List<ModeSlot> = throw NotImplementedError(
        "динамический планировщик не входит в первую очередь (TZ-KA-009, ADR-004): " +
            "расписание режимов формируется статическими географическими масками",
    )
}

private fun maskArcKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dl = Math.toRadians(lon2 - lon1)
    val c = sin(p1) * sin(p2) + cos(p1) * cos(p2) * cos(dl)
    return orbita.bal.RE_KM * acos(max(-1.0, min(1.0, c)))
}
