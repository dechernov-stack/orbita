// Эфемеридный backoff, бюджет времени реакции C', узкие места и деградация
// (TZ-FLW-005…008). Эталон spec/flows_semantics.py, один в один.
package orbita.flw

import orbita.mod.store.ModelViolationException
import kotlin.math.pow

// ---------- TZ-FLW-005: эфемеридный backoff ----------

/**
 * Вероятность доставки за несколько пролётов с ожиданием между ними
 * (Р6/ADR-006). Обмен «надёжность за хвост задержки».
 */
fun deliveryWithBackoff(pSuccess: Double, attemptsPerPass: Int, passes: Int): Double {
    val pPass = 1 - (1 - pSuccess).pow(attemptsPerPass)
    return 1 - (1 - pPass).pow(passes)
}

/** Хвост задержки удлиняется на интервал до следующего аппарата. */
fun latencyTailS(passIntervalS: Double, passesUsed: Int): Double =
    passIntervalS * (passesUsed - 1)

// ---------- TZ-FLW-006: бюджет времени реакции C' ----------

/**
 * Участки бюджета времени реакции. external_decision — параметр внешней
 * системы: ИС его не моделирует, но учитывает (TZ-USR-001, ловушка 5).
 */
val BUDGET_PARTS = listOf(
    "detection", "uplink_wait", "uplink_transit",
    "external_decision", "downlink_wait", "downlink_transit", "execution",
)

/** Полный бюджет; неполный отклоняется (TZ-FLW-006). */
fun reactionTimeS(parts: Map<String, Double>): Double {
    val missing = BUDGET_PARTS.filterNot { it in parts }
    if (missing.isNotEmpty()) {
        throw ModelViolationException("TZ-FLW-006: неполный бюджет: $missing")
    }
    return BUDGET_PARTS.sumOf { parts.getValue(it) }
}

/** Доля реализаций, уложившихся в требуемое время: P(T ≤ T_треб). */
fun pWithin(samples: List<Double>, requiredS: Double): Double =
    samples.count { it <= requiredS }.toDouble() / samples.size

/**
 * То же по выборке ПРЕДСТАВИТЕЛЕЙ: вклад реализации взвешен численностью
 * популяции, которую представитель замещает. Без веса крупная и мелкая ячейки
 * входят в распределение одинаково, и показатель класса смещается в пользу
 * мелких популяций (ловушка 4) — как и оценка среднего.
 */
fun pWithinWeighted(samples: List<Representative>, requiredS: Double): Double {
    val total = samples.sumOf { it.weight }
    if (total <= 0) return 0.0
    return samples.filter { it.value <= requiredS }.sumOf { it.weight } / total
}

// ---------- TZ-FLW-001: выборка по представителям ----------

data class Representative(val weight: Double, val value: Double)

/**
 * Оценка по представителям, взвешенная по ЧИСЛЕННОСТИ популяции, а не по
 * числу представителей (ловушка 4): иначе малые популяции получают
 * систематическое преимущество.
 */
fun weightedEstimate(reps: List<Representative>): Double {
    val w = reps.sumOf { it.weight }
    return if (w > 0) reps.sumOf { it.weight * it.value } / w else 0.0
}

/** Стандартная ошибка оценки Монте-Карло: убывает как 1/√N. */
fun mcStdErr(values: List<Double>): Double {
    if (values.size <= 1) return 0.0
    val mean = values.average()
    val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
    return Math.sqrt(variance) / Math.sqrt(values.size.toDouble())
}

/** Оценка квантиля уровня [p] по выборке (ближайший ранг). */
fun percentile(values: List<Double>, p: Double): Double {
    require(values.isNotEmpty()) { "empty sample" }
    val sorted = values.sorted()
    val idx = Math.ceil(p * sorted.size).toInt().coerceIn(1, sorted.size) - 1
    return sorted[idx]
}

/** Квантиль по выборке представителей, взвешенный численностью популяции. */
fun weightedPercentile(samples: List<Representative>, p: Double): Double {
    require(samples.isNotEmpty()) { "empty sample" }
    val sorted = samples.sortedBy { it.value }
    val target = p * sorted.sumOf { it.weight }
    var acc = 0.0
    sorted.forEach { s ->
        acc += s.weight
        if (acc >= target) return s.value
    }
    return sorted.last().value
}

/**
 * Индекс в ряду оценок, начиная с которого ряд стабилен: относительное
 * изменение не превышает [tol] два шага подряд. null — не сошлось.
 *
 * Средняя оценка сходится быстро, хвостовая — нет (ловушка 2). Поэтому
 * сходимость контролируется РАЗДЕЛЬНО для каждого показателя, а не одним
 * числом реализаций или представителей на весь прогон.
 */
fun firstStableIndex(estimates: List<Double>, tol: Double): Int? {
    var stable = 0
    for (i in 1 until estimates.size) {
        val prev = estimates[i - 1]
        val cur = estimates[i]
        if (cur != 0.0 && Math.abs(cur - prev) / Math.abs(cur) <= tol) {
            if (++stable == 2) return i
        } else {
            stable = 0
        }
    }
    return null
}

/**
 * Число выборок, при котором оценка перестаёт меняться больше чем на [tol]
 * два шага подряд; null — сходимость не достигнута.
 */
fun convergenceN(
    samples: List<Double>,
    tol: Double,
    step: Int = 50,
    estimator: (List<Double>) -> Double,
): Int? {
    val counts = generateSequence(step) { it + step }.takeWhile { it <= samples.size }.toList()
    val estimates = counts.map { estimator(samples.take(it)) }
    return firstStableIndex(estimates, tol)?.let { counts[it] }
}

// ---------- TZ-FLW-007 / 008 ----------

/** Узкое место — участок с наибольшей загрузкой. */
fun bottleneck(loads: Map<String, Double>): Pair<String, Double> =
    loads.maxByOrNull { it.value }!!.toPair()

/**
 * Доля терминалов в деградированном режиме (Р5/ADR-005): растёт с редкостью
 * пролётов и с редкостью маяка. Потери слепой передачи учитываются отдельно.
 */
fun degradedShare(passIntervalS: Double, beaconPeriodS: Double, maxAlmanacAgeS: Double): Double {
    val refresh = maxOf(passIntervalS, beaconPeriodS)
    if (refresh <= maxAlmanacAgeS) return 0.0
    return minOf(1.0, 1 - maxAlmanacAgeS / refresh)
}
