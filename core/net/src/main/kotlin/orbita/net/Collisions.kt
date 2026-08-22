// Модель коллизий и ёмкости (TZ-NET-003): быстрая аналитическая, пошаговая
// симуляция событий не используется. Калибровка — внешним опубликованным
// источником (TZ-NET-004, spec/reference/net_aloha_reference.json): LoRaSim,
// Bor et al. MSWiM 2016 — база e^(−2G) сходится с их simple-каналом (0.5042
// против 0.51 при N=200), измеренное поднятие захватом до 0.80 калибрует
// captureFraction ≈ 0.674 для одно-SF сценария. Аналитические свойства ALOHA
// (максимум при G=0.5, монотонность) остаются второй линией сверки.
package orbita.net

import kotlin.math.exp

/** Пропускная способность чистой ALOHA: S = G·e^(−2G); максимум 1/(2e) при G = 0.5. */
fun pureAlohaThroughput(offeredLoad: Double): Double =
    offeredLoad * exp(-2.0 * offeredLoad)

/**
 * Вероятность успешной доставки кадра при нагрузке [offeredLoad] (Эрланг на канал).
 * Эффект захвата снимает долю [captureFraction] коллизий (сильный сигнал выживает);
 * квазиортогональность режимов делит нагрузку на [orthogonalModes] независимых
 * подканалов. P = e^(−2·G_eff).
 */
fun deliveryProbability(
    offeredLoad: Double,
    captureFraction: Double = 0.0,
    orthogonalModes: Int = 1,
): Double {
    require(offeredLoad >= 0) { "offered load must be non-negative" }
    require(captureFraction in 0.0..1.0) { "capture fraction must be in [0,1]" }
    require(orthogonalModes >= 1) { "orthogonal modes must be >= 1" }
    val gEff = offeredLoad / orthogonalModes * (1.0 - captureFraction)
    return exp(-2.0 * gEff)
}

/** Ёмкость линии, сообщений/с: передаётся наружу параметром (TZ-NET-003). */
fun linkCapacityMsgsPerS(bitrateBps: Double, frameBits: Double, atLoad: Double = 0.5): Double =
    pureAlohaThroughput(atLoad) * bitrateBps / frameBits
