// Гибридное ядро Монте-Карло (TZ-FLW-001, ADR-014).
//
// Схема: детерминированная аналитика на ячейку и пролёт (предложенная нагрузка,
// коллизии и ёмкость из адаптера), затем розыгрыш только существенно случайных
// измерений — по ВЫБОРКЕ ПРЕДСТАВИТЕЛЕЙ с весами по численности популяции.
// События видимости берутся из предрасчёта (ADR-013): пропагатор внутри цикла
// реализаций не вызывается — это архитектурное условие бюджета TZ-COM-004.
package orbita.flw

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.concurrent.ForkJoinPool
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln

/** Режим доставки сценария (schemas/core/scenario: delivery_mode). */
enum class DeliveryMode(val wireId: String) {
    StoreAndForward("store_and_forward"),
    IslInPlane("isl_in_plane"),
    IslFullMesh("isl_full_mesh"),
}

/**
 * Параметры контура оперативного управления C'. Все — ИЗ ПРОФИЛЯ терминала
 * (TZ-USR-001): время решения внешней системы ИС не моделирует и не уточняет,
 * оно входит в бюджет как заданное значение (ловушка 5).
 */
data class ControlLoop(
    val requiredReactionS: Double,
    val detectionS: Double,
    val externalDecisionS: Double,
    val executionS: Double,
)

/** Популяция терминалов одного класса в ячейке спроса. */
data class PopulationSlice(
    val cellId: String,
    val consumerClass: String,
    val terminals: Double,
    val msgsPerTerminalDay: Double,
    /** Вес ячейки в карте спроса — численность, а не число представителей (ловушка 4). */
    val weight: Double,
    /** Политика надёжности B'/A': попытки в пролёте и допустимое число пролётов (Р6). */
    val attemptsPerPass: Int = 1,
    val maxPasses: Int = 1,
    val controlLoop: ControlLoop? = null,
    /**
     * Множитель интенсивности: суточный и сезонный профили (orbita.usr.intensityAt)
     * либо всплеск событийного трафика (mmppRate). Общий для всей популяции ячейки —
     * так выражается КОРРЕЛЯЦИЯ событий внутри ячейки (TZ-FLW-003): независимые
     * розыгрыши на терминал этот худший случай скрывают.
     */
    val intensityFactor: Double = 1.0,
) {
    /**
     * Ключ потока ГПСЧ. Адресуется УСТОЙЧИВОЙ идентичностью популяции, а не
     * позицией в списке: иначе перестановка входа молча меняет результат
     * (ловушка 3 в другой одежде).
     */
    val rngEntityIndex: Int get() = "$cellId|$consumerClass".hashCode() and 0x7fffffff
}

/** Пролёт над целью из предрасчёта геометрии (contracts/visibility). */
data class CellPass(val cellId: String, val startS: Double, val endS: Double, val inServiceZone: Boolean)

/** Параметры канала — ТОЛЬКО из адаптера протокола и профиля (TZ-NET-001, TZ-USR-002). */
data class ChannelParams(
    val capacityMsgsPerPass: Double,
    val timeOnAirS: Double,
    val beaconPeriodS: Double,
    val maxAlmanacAgeS: Double,
    /** Множитель интенсивности в деградированном режиме (TZ-USR-002, ADR-005). */
    val degradedRateFactor: Double = 1.0,
)

/**
 * Ёмкости участков за КА (TZ-FLW-007). Неизвестная ёмкость остаётся `null`:
 * участок не попадает в карту загрузки. Пустое поле честнее правдоподобного
 * коэффициента (CLAUDE.md §5).
 */
data class SegmentCapacity(
    val onboardBufferMsgs: Int? = null,
    val feederMsgsPerContact: Double? = null,
    val islMsgsPerContact: Double? = null,
)

/** Итог по классу потребителей: метрики считаются раздельно, без усреднения (Р9). */
data class ClassResult(
    val consumerClass: String,
    val offeredMsgs: Double,
    val carriedMsgs: Double,
    val deliveryProbability: Double,
    val meanAttempts: Double,
    val degradedShare: Double,
    val blindLossMsgs: Double,
    val latencyS: List<Double>,
    /** Только C': P(T ≤ T_треб) и средние по участкам бюджета. */
    val reactionWithinRequired: Double? = null,
    val reactionSamples: List<Double> = emptyList(),
    val budgetMeans: Map<String, Double> = emptyMap(),
) {
    fun latencyPercentile(p: Double): Double? {
        if (latencyS.isEmpty()) return null
        val sorted = latencyS.sorted()
        val idx = ceil(p * sorted.size).toInt().coerceIn(1, sorted.size) - 1
        return sorted[idx]
    }
}

/** Результат прогона; сериализуется в нормативный contracts/flow-result. */
data class FlowRunResult(
    val scenarioRef: String,
    val runs: Int,
    val rngSeed: Long,
    val deliveryMode: DeliveryMode,
    val byClass: List<ClassResult>,
    val loads: Map<String, Double>,
    val bufferOverflowMsgs: Double,
    val wallTimeS: Double? = null,
) {
    val offeredMsgs: Double get() = byClass.sumOf { it.offeredMsgs }
    val carriedMsgs: Double get() = byClass.sumOf { it.carriedMsgs }
    val blindLossMsgs: Double get() = byClass.sumOf { it.blindLossMsgs }
    val retransmissionRatio: Double
        get() = if (carriedMsgs > 0) offeredMsgs / carriedMsgs else Double.POSITIVE_INFINITY

    /** Узкое место — участок с наибольшей загрузкой (TZ-FLW-007). */
    fun bottleneckSegment(): Pair<String, Double>? = if (loads.isEmpty()) null else bottleneck(loads)

    fun toContractJson(mapper: ObjectMapper = ObjectMapper()): ObjectNode {
        val root = mapper.createObjectNode()
        root.put("scenario_ref", scenarioRef)
        root.put("runs", runs)
        wallTimeS?.let { root.put("wall_time_s", it) }

        val load = root.putObject("load")
        load.put("offered_msgs", offeredMsgs)
        load.put("carried_msgs", carriedMsgs)
        if (carriedMsgs > 0) load.put("retransmission_ratio", retransmissionRatio)
        load.put("buffer_overflow_losses", bufferOverflowMsgs)
        load.put("blind_transmission_losses", blindLossMsgs)

        val arr = root.putArray("by_class")
        byClass.forEach { c ->
            val n = arr.addObject()
            n.put("consumer_class", c.consumerClass)
            n.put("delivery_probability", c.deliveryProbability.coerceIn(0.0, 1.0))
            n.put("mean_attempts", c.meanAttempts.coerceAtLeast(1.0))
            if (c.latencyS.isNotEmpty()) {
                val lat = n.putObject("latency_percentiles_s")
                lat.put("p50", c.latencyPercentile(0.50)!!)
                lat.put("p90", c.latencyPercentile(0.90)!!)
                lat.put("p95", c.latencyPercentile(0.95)!!)
                lat.put("p99", c.latencyPercentile(0.99)!!)
            }
            c.reactionWithinRequired?.let { p ->
                val b = n.putObject("reaction_time_budget")
                b.put("p_within_required", p)
                c.budgetMeans.forEach { (part, v) -> b.put("${part}_s", v) }
            }
        }

        if (loads.isNotEmpty()) {
            val bn = root.putArray("bottlenecks")
            loads.entries.sortedByDescending { it.value }.forEach { (segment, util) ->
                bn.addObject().put("location", segment).put("utilization", util)
            }
        }
        return root
    }
}

class MonteCarloEngine(private val mapper: ObjectMapper = ObjectMapper()) {

    /**
     * Число обращений к предрасчёту геометрии. Индексация пролётов выполняется
     * ОДИН раз до цикла реализаций; счётчик — исполняемая формулировка условия
     * «геометрия не пересчитывается внутри цикла» (TZ-FLW-001, ACCEPTANCE 2).
     */
    @Volatile
    var geometryLookups: Int = 0
        private set

    /** Число обращений к геометрии, случившихся внутри цикла реализаций. */
    @Volatile
    var geometryLookupsInLoop: Int = 0
        private set

    /**
     * Прогон сценария. [userPasses] и [relayContacts] — предрасчитанные события
     * видимости (ADR-013): функция их только читает.
     *
     * @param relayContacts контакты релейного множества с наземной станцией.
     *   Множество задаёт [mode]: при store-and-forward это контакты самого
     *   аппарата, при ISL — контакты всей связной группы. Интервал между ними
     *   и определяет ожидание нисходящего канала.
     */
    fun run(
        scenarioRef: String,
        populations: List<PopulationSlice>,
        userPasses: List<CellPass>,
        relayContacts: List<CellPass>,
        channel: ChannelParams,
        horizonS: Double,
        runs: Int,
        rngSeed: Long,
        mode: DeliveryMode = DeliveryMode.StoreAndForward,
        segments: SegmentCapacity = SegmentCapacity(),
        parallelism: Int = 1,
    ): FlowRunResult {
        require(runs >= 1) { "runs must be >= 1" }
        require(horizonS > 0) { "horizon must be positive" }
        require(parallelism >= 1) { "parallelism must be >= 1" }

        // Единственное обращение к геометрии — до цикла реализаций.
        val passesByCell = userPasses.filter { it.inServiceZone }.groupBy { it.cellId }
        geometryLookups++
        val relayIntervalS = meanIntervalS(relayContacts, horizonS)
        val lookupsBeforeLoop = geometryLookups

        // Порядок входа не влияет: срезы сортируются по устойчивому ключу,
        // а свёртка идёт строго по индексу — параллельность не меняет ни
        // потока ГПСЧ, ни порядка сложения (TZ-FLW-002).
        val pool = if (parallelism > 1) ForkJoinPool(parallelism) else null
        val classResults = try {
            populations.sortedWith(compareBy({ it.consumerClass }, { it.cellId }))
                .groupBy { it.consumerClass }
                .map { (klass, slices) ->
                    aggregateClass(
                        klass, slices, passesByCell, relayIntervalS,
                        channel, horizonS, runs, rngSeed, mode, pool,
                    )
                }.sortedBy { it.consumerClass }
        } finally {
            pool?.shutdown()
        }
        geometryLookupsInLoop = geometryLookups - lookupsBeforeLoop

        val offered = classResults.sumOf { it.offeredMsgs }
        val carried = classResults.sumOf { it.carriedMsgs }
        val serviceContacts = userPasses.count { it.inServiceZone }
        val relayCount = relayContacts.size

        // Загрузка участков: только там, где ёмкость известна (TZ-FLW-007).
        val loads = buildMap {
            if (serviceContacts > 0) {
                put("user_uplink", offered / (serviceContacts * channel.capacityMsgsPerPass))
            }
            segments.onboardBufferMsgs?.let { buf ->
                // накопление между сбросами на НС: сколько сообщений ждёт в буфере
                val perContact = if (relayCount > 0) carried / relayCount else carried
                put("onboard_buffer", perContact / buf)
            }
            segments.feederMsgsPerContact?.takeIf { relayCount > 0 }?.let { cap ->
                put("feeder_downlink", carried / (relayCount * cap))
            }
            if (mode != DeliveryMode.StoreAndForward) {
                segments.islMsgsPerContact?.takeIf { relayCount > 0 }?.let { cap ->
                    put("isl", carried / (relayCount * cap))
                }
            }
        }

        // Потери переполнения — ОТДЕЛЬНОЙ статьёй от канальных (TZ-FLW-007, TZ-KA-008).
        val overflow = segments.onboardBufferMsgs?.let { buf ->
            val perContact = if (relayCount > 0) carried / relayCount else carried
            (perContact - buf).coerceAtLeast(0.0) * maxOf(relayCount, 1)
        } ?: 0.0

        return FlowRunResult(
            scenarioRef = scenarioRef,
            runs = runs,
            rngSeed = rngSeed,
            deliveryMode = mode,
            byClass = classResults,
            loads = loads,
            bufferOverflowMsgs = overflow,
        )
    }

    /** Спрос-взвешенная доставка по ячейкам — вход вектора KPI (TZ-BAL-005). */
    fun demandWeightedDelivery(result: FlowRunResult, populations: List<PopulationSlice>): Double {
        val byClass = result.byClass.associateBy { it.consumerClass }
        val reps = populations.mapNotNull { s ->
            byClass[s.consumerClass]?.let { Representative(s.weight, it.deliveryProbability) }
        }
        return weightedEstimate(reps)
    }

    /** Аналитика и розыгрыши одного среза — чистая функция от входа и ключа ГПСЧ. */
    private data class SliceOutcome(
        val offered: Double,
        val carried: Double,
        val baseMsgs: Double,
        val attemptsWeighted: Double,
        val degraded: Double,
        val weight: Double,
        val blindLoss: Double,
        val latencyS: List<Double>,
        val reactionS: List<Double>,
        val budgetSum: Map<String, Double>,
        val budgetCount: Int,
    )

    private fun aggregateClass(
        klass: String,
        slices: List<PopulationSlice>,
        passesByCell: Map<String, List<CellPass>>,
        relayIntervalS: Double,
        channel: ChannelParams,
        horizonS: Double,
        runs: Int,
        rngSeed: Long,
        mode: DeliveryMode,
        pool: ForkJoinPool?,
    ): ClassResult {
        val outcomes = arrayOfNulls<SliceOutcome>(slices.size)
        val compute = { i: Int ->
            outcomes[i] = simulateSlice(slices[i], passesByCell, relayIntervalS, channel, horizonS, runs, rngSeed)
        }
        if (pool == null) {
            slices.indices.forEach(compute)
        } else {
            // срезы независимы: поток ГПСЧ адресуется ключом, запись идёт
            // в свою ячейку массива, свёртка ниже — строго по индексу
            pool.submit { slices.indices.toList().parallelStream().forEach(compute) }.get()
        }

        var offered = 0.0
        var carried = 0.0
        var attemptsWeighted = 0.0
        var baseTotal = 0.0
        var degradedWeighted = 0.0
        var weightSum = 0.0
        var blindLoss = 0.0
        val latency = mutableListOf<Double>()
        val reaction = mutableListOf<Double>()
        val budgetSum = BUDGET_PARTS.associateWith { 0.0 }.toMutableMap()
        var budgetCount = 0

        outcomes.forEach { o ->
            o!!
            offered += o.offered
            carried += o.carried
            baseTotal += o.baseMsgs
            attemptsWeighted += o.attemptsWeighted
            degradedWeighted += o.degraded * o.weight
            weightSum += o.weight
            blindLoss += o.blindLoss
            latency += o.latencyS
            reaction += o.reactionS
            o.budgetSum.forEach { (k, v) -> budgetSum[k] = budgetSum.getValue(k) + v }
            budgetCount += o.budgetCount
        }

        val required = slices.firstNotNullOfOrNull { it.controlLoop?.requiredReactionS }
        return ClassResult(
            consumerClass = klass,
            offeredMsgs = offered,
            carriedMsgs = carried,
            deliveryProbability = if (baseTotal > 0) (carried / baseTotal).coerceIn(0.0, 1.0) else 0.0,
            meanAttempts = if (baseTotal > 0) attemptsWeighted / baseTotal else 1.0,
            degradedShare = if (weightSum > 0) degradedWeighted / weightSum else 0.0,
            blindLossMsgs = blindLoss,
            latencyS = latency,
            reactionWithinRequired = if (required != null && reaction.isNotEmpty()) pWithin(reaction, required) else null,
            reactionSamples = reaction,
            budgetMeans = if (budgetCount > 0) budgetSum.mapValues { it.value / budgetCount } else emptyMap(),
        )
    }

    /**
     * Один срез популяции: детерминированная аналитика ячейки, затем розыгрыш
     * ТОЛЬКО существенно случайного — ожидания пролёта и числа использованных
     * пролётов. Функция чистая: результат зависит от входа и ключа ГПСЧ,
     * но не от того, в каком потоке и в каком порядке она вызвана.
     */
    private fun simulateSlice(
        slice: PopulationSlice,
        passesByCell: Map<String, List<CellPass>>,
        relayIntervalS: Double,
        channel: ChannelParams,
        horizonS: Double,
        runs: Int,
        rngSeed: Long,
    ): SliceOutcome {
        val entityIndex = slice.rngEntityIndex
        val passes = passesByCell[slice.cellId].orEmpty()
        val passCount = maxOf(passes.size, 1)
        val passIntervalS = meanIntervalS(passes, horizonS)
        // доля времени, когда над ячейкой есть аппарат: шанс слепой передачи
        val visibleFraction = (passes.sumOf { it.endS - it.startS } / horizonS).coerceIn(0.0, 1.0)

        val degraded = degradedShare(passIntervalS, channel.beaconPeriodS, channel.maxAlmanacAgeS)
        // деградированный терминал передаёт реже (TZ-USR-002, ADR-005)
        val rateFactor = 1.0 - degraded * (1.0 - channel.degradedRateFactor)
        val baseMsgs = slice.terminals * slice.msgsPerTerminalDay * slice.intensityFactor * rateFactor

        // аналитика на ячейку: предложенная нагрузка с повторами и обслуженная
        val capacity = passCount * channel.capacityMsgsPerPass
        val load = offeredWithRetries(baseMsgs, capacity, maxAttempts = slice.attemptsPerPass)
        // Попытки в пролёте УЖЕ учтены обратной связью нагрузки: pPass —
        // доставка за один пролёт целиком. Повторно раскрывать их в
        // deliveryWithBackoff нельзя, иначе повторы считаются дважды.
        val pPass = if (baseMsgs > 0) (load.delivered / baseMsgs).coerceIn(0.0, 1.0) else 0.0
        val delivery = deliveryWithBackoff(pPass, attemptsPerPass = 1, passes = slice.maxPasses)

        // слепая передача: терминал без альманаха бьёт наугад и попадает
        // в окно видимости с вероятностью visibleFraction (TZ-FLW-008)
        val blindLost = baseMsgs * degraded * (1.0 - visibleFraction)

        val latency = ArrayList<Double>(runs)
        val reaction = ArrayList<Double>(if (slice.controlLoop != null) runs else 0)
        val budgetSum = BUDGET_PARTS.associateWith { 0.0 }.toMutableMap()
        var budgetCount = 0

        repeat(runs) { run ->
            // ожидание пролёта: момент готовности сообщения равномерен в цикле
            val waitFirstS = CounterRng.uniform(rngSeed, run, entityIndex, DRAW_WAIT) * passIntervalS
            // число использованных пролётов — обратная функция геометрического
            // распределения с вероятностью успеха pPass на пролёт (Р6/ADR-006)
            val passesUsed = geometricDraw(pPass, slice.maxPasses, rngSeed, run, entityIndex)
            val uplinkWaitS = waitFirstS + latencyTailS(passIntervalS, passesUsed)
            latency += uplinkWaitS + channel.timeOnAirS

            slice.controlLoop?.let { loop ->
                // Ожидание нисходящего канала — до ближайшего контакта релейного
                // множества с НС. Само множество задаётся режимом доставки
                // (relaySetContacts): при store-and-forward это один аппарат,
                // при ISL — плоскость или вся группировка.
                val downlinkWaitS =
                    CounterRng.uniform(rngSeed, run, entityIndex, DRAW_DOWNLINK) * relayIntervalS
                val parts = mapOf(
                    "detection" to loop.detectionS,
                    "uplink_wait" to uplinkWaitS,
                    "uplink_transit" to channel.timeOnAirS,
                    // из профиля, не моделируется (ловушка 5)
                    "external_decision" to loop.externalDecisionS,
                    "downlink_wait" to downlinkWaitS,
                    "downlink_transit" to channel.timeOnAirS,
                    "execution" to loop.executionS,
                )
                reaction += reactionTimeS(parts)
                parts.forEach { (k, v) -> budgetSum[k] = budgetSum.getValue(k) + v }
                budgetCount++
            }
        }

        return SliceOutcome(
            offered = load.offered,
            carried = (baseMsgs - blindLost) * delivery,
            baseMsgs = baseMsgs,
            // передач на доставленное сообщение — прямо из модели повторов
            attemptsWeighted = baseMsgs * load.retransmissionRatio,
            degraded = degraded,
            weight = slice.weight,
            blindLoss = blindLost,
            latencyS = latency,
            reactionS = reaction,
            budgetSum = budgetSum,
            budgetCount = budgetCount,
        )
    }

    private companion object {
        const val DRAW_WAIT = 1
        const val DRAW_PASSES = 2
        const val DRAW_DOWNLINK = 3

        /** Обратная функция геометрического распределения, срезанная на [maxPasses]. */
        fun geometricDraw(pPass: Double, maxPasses: Int, seed: Long, run: Int, entity: Int): Int {
            if (pPass >= 1.0 || pPass <= 0.0) return if (pPass >= 1.0) 1 else maxPasses
            val u = CounterRng.uniform(seed, run, entity, DRAW_PASSES).coerceIn(1e-15, 1.0 - 1e-15)
            return (1 + floor(ln(1 - u) / ln(1 - pPass)).toInt()).coerceIn(1, maxPasses)
        }
    }
}

/**
 * Средний интервал между событиями на горизонте. Пустое расписание — интервал
 * равен всему горизонту: ждать придётся дольше горизонта, но экстраполировать
 * за него нечем.
 */
fun meanIntervalS(events: List<CellPass>, horizonS: Double): Double =
    if (events.isEmpty()) horizonS else horizonS / events.size

/**
 * Контакты релейного множества, определённого режимом доставки (TZ-FLW-006):
 * сообщение уходит вниз с первого контакта ЛЮБОГО аппарата, до которого оно
 * может дойти. Объединение расписаний точное — предрасчёт знает контакты
 * каждого аппарата, никакого усреднения здесь не вводится.
 */
fun relaySetContacts(
    contactsBySat: Map<String, List<CellPass>>,
    collectingSat: String,
    mode: DeliveryMode,
    planeOf: (String) -> String = { it },
): List<CellPass> = when (mode) {
    DeliveryMode.StoreAndForward -> contactsBySat[collectingSat].orEmpty()
    DeliveryMode.IslInPlane ->
        contactsBySat.filterKeys { planeOf(it) == planeOf(collectingSat) }.values.flatten()
    DeliveryMode.IslFullMesh -> contactsBySat.values.flatten()
}.sortedBy { it.startS }
