// Счётчиковый ГПСЧ (TZ-FLW-002, ADR-014). Эталон spec/flows_semantics.py,
// один в один.
//
// Поток адресуется КЛЮЧОМ «зерно × реализация × сущность × розыгрыш», а не
// последовательным состоянием: результат не зависит ни от порядка выполнения
// потоков, ни от числа ядер. Обычный ГПСЧ ломает воспроизводимость при
// параллельности незаметно — на одном ядре тесты проходят (ловушка 3).
package orbita.flw

import java.security.MessageDigest
import kotlin.math.ln
import kotlin.math.sqrt

object CounterRng {

    /** Равномерное [0,1) по ключу. Одинаковый ключ — всегда одинаковое число. */
    fun uniform(seed: Long, runIndex: Int, entityIndex: Int, draw: Int = 0): Double {
        val key = "$seed:$runIndex:$entityIndex:$draw".toByteArray()
        // BLAKE2b недоступен в стандартном JCA; SHA-256 даёт то же свойство
        // адресуемости: значение определяется ключом, а не историей вызовов.
        val h = MessageDigest.getInstance("SHA-256").digest(key)
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (h[i].toLong() and 0xFF)
        // старший бит отбрасывается: беззнаковое деление на 2^63
        return (v ushr 1).toDouble() / (1L shl 62).toDouble() / 2.0
    }

    /** Экспоненциальное распределение (интервалы пуассоновского потока). */
    fun exponential(rate: Double, seed: Long, runIndex: Int, entityIndex: Int, draw: Int = 0): Double {
        require(rate > 0) { "rate must be positive" }
        val u = uniform(seed, runIndex, entityIndex, draw).coerceIn(1e-15, 1.0 - 1e-15)
        return -ln(1.0 - u) / rate
    }

    /** Пуассоновское число событий (метод Кнута; для малых λ достаточно). */
    fun poisson(lambda: Double, seed: Long, runIndex: Int, entityIndex: Int, draw: Int = 0): Int {
        require(lambda >= 0) { "lambda must be non-negative" }
        if (lambda == 0.0) return 0
        val limit = Math.exp(-lambda)
        var k = 0
        var p = 1.0
        do {
            p *= uniform(seed, runIndex, entityIndex, draw * 1000 + k)
            k++
        } while (p > limit && k < 10_000)
        return k - 1
    }

    /** Нормальное распределение (Бокс–Мюллер) — для разбросов задержек. */
    fun normal(mean: Double, sigma: Double, seed: Long, runIndex: Int, entityIndex: Int, draw: Int = 0): Double {
        val u1 = uniform(seed, runIndex, entityIndex, draw * 2).coerceIn(1e-15, 1.0)
        val u2 = uniform(seed, runIndex, entityIndex, draw * 2 + 1)
        return mean + sigma * sqrt(-2.0 * ln(u1)) * Math.cos(2 * Math.PI * u2)
    }
}
