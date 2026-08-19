// Потоки, коллизии и обратная связь нагрузки (TZ-FLW-003, TZ-FLW-004).
// Эталон spec/flows_semantics.py, один в один.
//
// Ключевое свойство (STEP-4 §1.4, ловушка 1): до порога повторы КОМПЕНСИРУЮТ
// деградацию канала — доставка не меняется, растут только повторы. За порогом
// происходит обвал. Модель, показывающая плавную деградацию, неверна.
package orbita.flw

import orbita.net.pureAlohaThroughput
import kotlin.math.exp

/** Суперпозиция независимых пуассоновских потоков — снова пуассоновский. */
fun superpose(rates: List<Double>): Double = rates.sum()

fun poissonPmf(k: Int, lambda: Double): Double {
    var fact = 1.0
    for (i in 2..k) fact *= i
    return exp(-lambda) * Math.pow(lambda, k.toDouble()) / fact
}

/** Модулированный пуассоновский поток: фон и всплеск событий (шторм будит датчики). */
fun mmppRate(baseRate: Double, burstMultiplier: Double, inBurst: Boolean): Double =
    baseRate * (if (inBurst) burstMultiplier else 1.0)

/**
 * Чистая ALOHA: S = G·e^(−2G), максимум 0,184 при G = 0,5. Кривая берётся
 * из модели коллизий адаптера (TZ-NET-003) — второй реализации той же
 * физики в ИС быть не должно. Захват сильного сигнала повышает пропускную
 * способность; здесь он выражен множителем к пропускной способности,
 * в адаптере — снижением эффективной нагрузки: это две разные ручки одной
 * модели, а не два ответа на один вопрос.
 */
fun alohaThroughput(g: Double, capture: Boolean = false): Double {
    val s = pureAlohaThroughput(g)
    return if (capture) s * 1.4 else s
}

/** Обслуженная нагрузка при заданной ёмкости канала. */
fun carriedFromOffered(offered: Double, capacity: Double): Double {
    if (capacity <= 0) return 0.0
    return alohaThroughput(offered / capacity) * capacity
}

/** Предложенная и обслуженная нагрузка с учётом повторов. */
data class LoadResult(val offered: Double, val delivered: Double) {
    /** Повторов на доставленное сообщение: показатель лавины (TZ-FLW-004). */
    val retransmissionRatio: Double get() = if (delivered > 0) offered / delivered else Double.POSITIVE_INFINITY
}

/**
 * Повторы как обратная связь: неудача порождает новую попытку и входит
 * в ПРЕДЛОЖЕННУЮ нагрузку. При падении ёмкости она растёт нелинейно.
 */
fun offeredWithRetries(baseMsgs: Double, capacity: Double, maxAttempts: Int = 4): LoadResult {
    var offered = 0.0
    var delivered = 0.0
    var pending = baseMsgs
    repeat(maxAttempts) {
        if (pending <= 0) return@repeat
        offered += pending
        val got = minOf(pending, carriedFromOffered(offered, capacity))
        delivered += got
        pending -= got
    }
    return LoadResult(offered, delivered)
}
