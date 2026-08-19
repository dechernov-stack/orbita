// Ядро Монте-Карло: свойства, которые эталон задаёт не формулами, а условиями
// прогона — воспроизводимость при параллельности, отсутствие пересчёта
// геометрии в цикле, разница режимов доставки, обвал за порогом повторов.
package orbita.flw

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.schema.SchemaRegistry
import orbita.usr.DemandCell
import orbita.usr.intensityAt
import orbita.usr.worstCaseHourMonth
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.abs

class MonteCarloTest {

    private val mapper = ObjectMapper()
    private val registry = SchemaRegistry(RepoPaths.schemasDir())
    private val horizonS = 86_400.0

    // Расписание пролётов: предрасчёт геометрии (ADR-013), сюда приходит готовым.
    private fun passes(cellId: String, count: Int, durationS: Double = 420.0) =
        (0 until count).map { i ->
            val start = horizonS / count * i
            CellPass(cellId, start, start + durationS, inServiceZone = true)
        }

    private val channel = ChannelParams(
        capacityMsgsPerPass = 900.0,
        timeOnAirS = 1.5,
        beaconPeriodS = 600.0,
        maxAlmanacAgeS = 86_400.0,
        degradedRateFactor = 0.3,
    )

    private val controlLoop = ControlLoop(
        requiredReactionS = 120.0,
        detectionS = 2.0,
        externalDecisionS = 20.0,   // из профиля, не моделируется
        executionS = 3.0,
    )

    private fun populations() = listOf(
        PopulationSlice("C-001", "A_prime", terminals = 4_000.0, msgsPerTerminalDay = 2.0, weight = 4_000.0),
        PopulationSlice(
            "C-001", "B_prime", terminals = 800.0, msgsPerTerminalDay = 4.0, weight = 800.0,
            attemptsPerPass = 4, maxPasses = 3,
        ),
        PopulationSlice(
            "C-002", "A_prime", terminals = 1_500.0, msgsPerTerminalDay = 2.0, weight = 1_500.0,
        ),
        PopulationSlice(
            "C-002", "C_prime", terminals = 60.0, msgsPerTerminalDay = 6.0, weight = 60.0,
            attemptsPerPass = 4, maxPasses = 2, controlLoop = controlLoop,
        ),
    )

    private fun userPasses() = passes("C-001", 14) + passes("C-002", 11)

    private fun run(
        mode: DeliveryMode = DeliveryMode.StoreAndForward,
        relay: List<CellPass> = passes("GS-01", 6),
        pops: List<PopulationSlice> = populations(),
        runs: Int = 200,
        parallelism: Int = 1,
        segments: SegmentCapacity = SegmentCapacity(),
        engine: MonteCarloEngine = MonteCarloEngine(mapper),
    ) = engine.run(
        scenarioRef = "SC-0001",
        populations = pops,
        userPasses = userPasses(),
        relayContacts = relay,
        channel = channel,
        horizonS = horizonS,
        runs = runs,
        rngSeed = 42,
        mode = mode,
        segments = segments,
        parallelism = parallelism,
    )

    // ---------- TZ-FLW-001 ----------

    @Test
    @DisplayName("TZ-FLW-001: результат валиден против contracts/flow-result")
    fun `результат валиден против нормативного контракта`() {
        val doc = run(segments = SegmentCapacity(onboardBufferMsgs = 20_000, feederMsgsPerContact = 9_000.0))
            .toContractJson(mapper)
        val errors = registry.validate("contracts/flow-result", doc)
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }

    @Test
    @DisplayName("TZ-FLW-001: геометрия не пересчитывается внутри цикла реализаций")
    fun `геометрия не пересчитывается внутри цикла реализаций`() {
        val engine = MonteCarloEngine(mapper)
        run(runs = 500, engine = engine)
        assertEquals(1, engine.geometryLookups, "предрасчёт опрошен более одного раза")
        assertEquals(0, engine.geometryLookupsInLoop, "обращение к геометрии внутри цикла")
    }

    @Test
    @DisplayName("TZ-FLW-001: оценка взвешена по численности популяций")
    fun `спрос-взвешенная доставка лежит между классовыми`() {
        val engine = MonteCarloEngine(mapper)
        val result = run(engine = engine)
        val weighted = demandWeightedDelivery(result, populations())
        val byClass = result.byClass.map { it.deliveryProbability }
        assertTrue(weighted >= byClass.min() && weighted <= byClass.max(), "$weighted vs $byClass")
    }

    // ---------- TZ-FLW-002 ----------

    @Test
    @DisplayName("TZ-FLW-002: два прогона с одним зерном дают идентичный результат")
    fun `два прогона с одним зерном идентичны`() {
        assertEquals(run().toContractJson(mapper), run().toContractJson(mapper))
    }

    @Test
    @DisplayName("TZ-FLW-002: результат не зависит от числа потоков")
    fun `результат не зависит от числа потоков`() {
        val base = run(parallelism = 1).toContractJson(mapper)
        listOf(2, 4, 8).forEach { threads ->
            assertEquals(base, run(parallelism = threads).toContractJson(mapper), "потоков: $threads")
        }
    }

    @Test
    @DisplayName("TZ-FLW-002: перестановка входа не меняет результат")
    fun `порядок популяций на входе не влияет`() {
        val base = run(pops = populations()).toContractJson(mapper)
        assertEquals(base, run(pops = populations().reversed()).toContractJson(mapper))
        assertEquals(base, run(pops = populations().shuffled(java.util.Random(7))).toContractJson(mapper))
    }

    @Test
    @DisplayName("TZ-FLW-002: другое зерно даёт другой результат")
    fun `другое зерно даёт другой результат`() {
        val engine = MonteCarloEngine(mapper)
        val other = engine.run(
            scenarioRef = "SC-0001", populations = populations(), userPasses = userPasses(),
            relayContacts = passes("GS-01", 6), channel = channel, horizonS = horizonS,
            runs = 200, rngSeed = 43,
        )
        assertTrue(other.toContractJson(mapper) != run().toContractJson(mapper))
    }

    // ---------- TZ-FLW-003 ----------

    @Test
    @DisplayName("TZ-FLW-003: всплеск в ячейке повышает нагрузку и снижает доставку")
    fun `коррелированный всплеск ухудшает доставку`() {
        val calm = run()
        val burst = run(
            pops = populations().map {
                if (it.cellId == "C-001") it.copy(intensityFactor = mmppRate(1.0, 6.0, inBurst = true)) else it
            },
        )
        assertTrue(burst.offeredMsgs > calm.offeredMsgs, "нагрузка не выросла")
        val calmA = calm.byClass.first { it.consumerClass == "A_prime" }.deliveryProbability
        val burstA = burst.byClass.first { it.consumerClass == "A_prime" }.deliveryProbability
        assertTrue(burstA < calmA, "$burstA vs $calmA")
    }

    @Test
    @DisplayName("TZ-FLW-003: суточный и сезонный профили применяются к интенсивности прогона")
    fun `профили активности применяются к интенсивности`() {
        // Профили считает usr (TZ-USR-005), применяет вызывающий: множитель
        // приходит в срез популяции. Связку нужно предъявить целиком, иначе
        // профили посчитаны и никуда не применены.
        val diurnal = List(24) { if (it in 8..18) 1.6 else 0.5 }
        val seasonal = List(12) { if (it in 5..7) 1.4 else 0.9 }
        val cell = DemandCell(
            id = "C-001", lat = 30.0,
            terminals = mapOf("A_prime" to 4_000.0), msgsPerDay = mapOf("A_prime" to 8_000.0),
            areaKm2 = 1.0e4, weight = 4_000.0,
        )
        val flat = intensityAt(cell, hour = 3, month = 0)
        val (worstHour, worstMonth) = worstCaseHourMonth(cell, diurnal, seasonal)
        val peak = intensityAt(cell, worstHour, worstMonth, diurnal, seasonal)
        assertTrue(peak > flat, "профиль не поднял интенсивность: $flat → $peak")

        val peaked = run(
            pops = populations().map {
                if (it.cellId == "C-001") it.copy(intensityFactor = peak / flat) else it
            },
        )
        assertTrue(peaked.offeredMsgs > run().offeredMsgs, "пиковый профиль не изменил нагрузку")
    }

    // ---------- TZ-FLW-004 ----------

    @Test
    @DisplayName("TZ-FLW-004: до порога повторы держат доставку, за порогом — обвал")
    fun `лавина повторов на уровне прогона`() {
        fun withCapacity(cap: Double): Pair<Double, Double> {
            val engine = MonteCarloEngine(mapper)
            val result = engine.run(
                scenarioRef = "SC-0001",
                populations = listOf(
                    PopulationSlice(
                        "C-001", "B_prime", terminals = 2_000.0, msgsPerTerminalDay = 4.0,
                        weight = 2_000.0, attemptsPerPass = 4, maxPasses = 1,
                    ),
                ),
                userPasses = passes("C-001", 14), relayContacts = passes("GS-01", 6),
                channel = channel.copy(capacityMsgsPerPass = cap),
                horizonS = horizonS, runs = 50, rngSeed = 42,
            )
            val c = result.byClass.single()
            return c.deliveryProbability to c.meanAttempts
        }

        val (deliveryWide, attemptsWide) = withCapacity(4_000.0)
        val (deliveryTight, attemptsTight) = withCapacity(1_500.0)
        val (deliveryOver, attemptsOver) = withCapacity(600.0)

        // до порога: доставка та же, платим повторами
        assertTrue(
            abs(deliveryTight - deliveryWide) < 1e-6,
            "доставка поплыла до порога: $deliveryWide → $deliveryTight",
        )
        assertTrue(attemptsTight > attemptsWide, "$attemptsWide → $attemptsTight")
        // за порогом: обвал
        assertTrue(deliveryOver < 0.5 * deliveryWide, "$deliveryWide → $deliveryOver")
        assertTrue(attemptsOver > 5 * attemptsTight, "$attemptsTight → $attemptsOver")
    }

    @Test
    @DisplayName("TZ-FLW-004: отчёт содержит offered, carried и долю повторов")
    fun `отчёт содержит нагрузку и долю повторов`() {
        val doc = run().toContractJson(mapper)["load"]
        assertTrue(doc["offered_msgs"].asDouble() > doc["carried_msgs"].asDouble())
        assertTrue(doc["retransmission_ratio"].asDouble() > 1.0)
    }

    // ---------- TZ-FLW-005 ----------

    @Test
    @DisplayName("TZ-FLW-005: больше пролётов — короче хвост задержки B'")
    fun `больше пролётов — короче хвост задержки`() {
        fun tailP99(passCount: Int): Double {
            val engine = MonteCarloEngine(mapper)
            val slice = PopulationSlice(
                "C-001", "B_prime", terminals = 800.0, msgsPerTerminalDay = 4.0,
                weight = 800.0, attemptsPerPass = 4, maxPasses = 3,
            )
            val r = engine.run(
                scenarioRef = "SC-0001", populations = listOf(slice),
                userPasses = passes("C-001", passCount), relayContacts = passes("GS-01", 6),
                channel = channel, horizonS = horizonS, runs = 500, rngSeed = 42,
            )
            return r.byClass.single().latencyPercentile(0.99)!!
        }
        assertTrue(tailP99(28) < tailP99(14), "хвост не сократился")
    }

    // ---------- TZ-FLW-006 ----------

    @Test
    @DisplayName("TZ-FLW-006: перечень режимов доставки совпадает со схемой сценария")
    fun `перечень режимов доставки совпадает со схемой`() {
        val schema = mapper.readTree(
            RepoPaths.schemasDir().resolve("core/scenario.schema.json").toFile(),
        )
        val fromSchema = schema["properties"]["delivery_mode"]["enum"].map { it.asText() }.toSet()
        assertEquals(fromSchema, DeliveryMode.entries.map { it.wireId }.toSet())
    }

    @Test
    @DisplayName("TZ-FLW-006: при редких пролётах контур C' не закрывается ни в одном режиме")
    fun `при редких пролётах контур C не закрывается`() {
        // Базовый сценарий: 11 пролётов в сутки над ячейкой C-002 — одно
        // ожидание восходящего канала в среднем около часа. Требование в 120 с
        // не выполняется, и ISL этого не спасает: это ответ модели, а не изъян.
        DeliveryMode.entries.forEach { mode ->
            val p = run(mode = mode).byClass
                .first { it.consumerClass == "C_prime" }.reactionWithinRequired!!
            assertEquals(0.0, p, "режим $mode неожиданно закрыл контур")
        }
    }

    @Test
    @DisplayName("TZ-FLW-006: три режима доставки дают разные P(T ≤ T_треб)")
    fun `три режима доставки дают разные вероятности`() {
        // Плотная группировка: восходящий канал перестаёт быть определяющим,
        // и разницу делает ожидание нисходящего — то есть режим доставки.
        val cellPasses = passes("C-CTRL", 288, durationS = 120.0)
        val slice = PopulationSlice(
            "C-CTRL", "C_prime", terminals = 60.0, msgsPerTerminalDay = 6.0, weight = 60.0,
            attemptsPerPass = 4, maxPasses = 2,
            controlLoop = controlLoop.copy(requiredReactionS = 3_600.0),
        )
        // Контакты каждого аппарата с НС — из предрасчёта; релейное множество
        // определяется режимом (relaySetContacts), а не подгоняется вручную.
        val contactsBySat = mapOf(
            "SAT-1-1" to passes("GS", 4),
            "SAT-1-2" to passes("GS", 4).map { it.copy(startS = it.startS + 5_400.0) },
            "SAT-2-1" to passes("GS", 4).map { it.copy(startS = it.startS + 2_700.0) },
            "SAT-2-2" to passes("GS", 4).map { it.copy(startS = it.startS + 8_100.0) },
        )
        val planeOf = { sat: String -> sat.substringBeforeLast('-') }

        val p = DeliveryMode.entries.associateWith { mode ->
            MonteCarloEngine(mapper).run(
                scenarioRef = "SC-0002", populations = listOf(slice), userPasses = cellPasses,
                relayContacts = relaySetContacts(contactsBySat, "SAT-1-1", mode, planeOf),
                channel = channel, horizonS = horizonS, runs = 2_000, rngSeed = 42, mode = mode,
            ).byClass.single().reactionWithinRequired!!
        }
        assertEquals(3, p.values.toSet().size, "режимы неразличимы: $p")
        assertTrue(
            p.getValue(DeliveryMode.StoreAndForward) < p.getValue(DeliveryMode.IslInPlane),
            "ISL в плоскости не лучше store-and-forward: $p",
        )
        assertTrue(
            p.getValue(DeliveryMode.IslInPlane) < p.getValue(DeliveryMode.IslFullMesh),
            "полная сетка не лучше плоскости: $p",
        )
    }

    @Test
    @DisplayName("TZ-FLW-006: бюджет выводится по участкам, решение внешней системы — как задано")
    fun `бюджет выводится по участкам`() {
        val doc = run().toContractJson(mapper)["by_class"]
            .first { it["consumer_class"].asText() == "C_prime" }["reaction_time_budget"]
        BUDGET_PARTS.forEach { part ->
            assertTrue(doc.has("${part}_s"), "нет участка $part")
        }
        assertEquals(controlLoop.externalDecisionS, doc["external_decision_s"].asDouble())
    }

    @Test
    @DisplayName("TZ-FLW-006: сходимость хвоста требует не меньше выборок, чем сходимость среднего")
    fun `сходимость хвоста медленнее сходимости среднего`() {
        val samples = run(runs = 3_000).byClass
            .first { it.consumerClass == "C_prime" }.reactionSamples
        val nMean = convergenceN(samples, tol = 0.01) { it.average() }
        val nTail = convergenceN(samples, tol = 0.01) { percentile(it, 0.99) }
        assertTrue(nMean != null, "среднее не сошлось на 3000 выборках")
        assertTrue(nTail == null || nTail >= nMean!!, "хвост сошёлся раньше среднего: $nTail vs $nMean")
    }

    @Test
    @DisplayName("TZ-FLW-001: сходимость по числу представителей — отдельно для среднего и хвоста")
    fun `сходимость по числу представителей контролируется раздельно`() {
        // Разнородная популяция: 200 ячеек с разной численностью и разной
        // частотой пролётов. Оценка по K представителям сходится к оценке
        // по всей популяции — но у хвоста это происходит позже (ловушка 2).
        // Замер на этой выборке: среднее стабильно с 30 представителей,
        // P99 — только с 90.
        val cells = (0 until 200).map { i ->
            val passCount = 4 + (CounterRng.uniform(11, 0, i, 0) * 20).toInt()
            val terminals = 200.0 + CounterRng.uniform(11, 0, i, 1) * 3_000.0
            PopulationSlice(
                "C-%03d".format(i), "C_prime", terminals, 6.0, weight = terminals,
                attemptsPerPass = 4, maxPasses = 2,
                controlLoop = controlLoop.copy(requiredReactionS = 7_200.0),
            ) to passes("C-%03d".format(i), passCount)
        }

        val counts = (10..200 step 10).toList()
        val estimates = counts.map { k ->
            val head = cells.take(k)
            val result = MonteCarloEngine(mapper).run(
                scenarioRef = "SC-0003", populations = head.map { it.first },
                userPasses = head.flatMap { it.second }, relayContacts = passes("GS-01", 8),
                channel = channel, horizonS = horizonS, runs = 100, rngSeed = 42,
            ).byClass.single()
            result.reactionSamples.average() to percentile(result.reactionSamples, 0.99)
        }

        val nMean = firstStableIndex(estimates.map { it.first }, tol = 0.02)
        val nTail = firstStableIndex(estimates.map { it.second }, tol = 0.02)
        assertTrue(nMean != null, "среднее не сошлось на 200 представителях")
        assertTrue(nTail == null || nTail >= nMean!!) {
            "хвост сошёлся раньше среднего: ${nTail?.let { counts[it] }} vs ${counts[nMean!!]}"
        }
    }

    @Test
    @DisplayName("TZ-FLW-001: выборка представителей сверена с полным перебором (ADR-014)")
    fun `оценка по представителям совпадает с полным перебором`() {
        // ADR-014 требует не только контроля сходимости, но и сверки с полным
        // перебором на малом сценарии: выборка представителей смещает хвосты.
        // Малый сценарий: 60 ячеек, по три на каждую частоту пролётов. Выборка
        // стратифицирована — по одному представителю на страту с утроенным
        // весом. Систематическая выборка «каждая четвёртая» здесь была бы
        // смещена: она пропускает целые страты.
        val all = (0 until 60).map { i ->
            val passCount = 4 + i / 3
            PopulationSlice(
                "C-%03d".format(i), "B_prime", terminals = 500.0, msgsPerTerminalDay = 4.0,
                weight = 500.0, attemptsPerPass = 4, maxPasses = 3,
            ) to passes("C-%03d".format(i), passCount)
        }
        val sample = all.filterIndexed { i, _ -> i % 3 == 0 }
            .map { (slice, p) -> slice.copy(weight = slice.weight * 3) to p }

        fun estimate(cells: List<Pair<PopulationSlice, List<CellPass>>>): Triple<Double, Double, Double> {
            val r = MonteCarloEngine(mapper).run(
                scenarioRef = "SC-0004", populations = cells.map { it.first },
                userPasses = cells.flatMap { it.second }, relayContacts = passes("GS-01", 8),
                channel = channel, horizonS = horizonS, runs = 400, rngSeed = 42,
            ).byClass.single()
            return Triple(
                r.deliveryProbability,
                weightedEstimate(r.latency),
                r.latencyPercentile(0.99)!!,
            )
        }

        val (deliveryFull, latencyFull, tailFull) = estimate(all)
        val (deliverySample, latencySample, tailSample) = estimate(sample)

        // доставка — оценка среднего: расхождение в пределах процента
        assertTrue(abs(deliverySample - deliveryFull) < 0.01, "$deliveryFull vs $deliverySample")
        // средняя задержка — в пределах 2 %
        assertTrue(
            abs(latencySample - latencyFull) < 0.02 * latencyFull,
            "средняя задержка: $latencyFull vs $latencySample",
        )
        // хвост сходится хуже среднего (ADR-014, ловушка 2) — допуск шире, но конечный
        assertTrue(
            abs(tailSample - tailFull) < 0.10 * tailFull,
            "P99: $tailFull vs $tailSample",
        )
    }

    // ---------- TZ-FLW-007 ----------

    @Test
    @DisplayName("TZ-FLW-007: узкое место названо, потери буфера — отдельной статьёй")
    fun `узкое место и потери буфера`() {
        val result = run(
            segments = SegmentCapacity(
                onboardBufferMsgs = 200, feederMsgsPerContact = 9_000.0, islMsgsPerContact = 9_000.0,
            ),
        )
        val (segment, load) = result.bottleneckSegment()!!
        assertEquals("onboard_buffer", segment, "загрузки: ${result.loads}")
        assertTrue(load > 1.0, "$load")
        assertTrue(result.bufferOverflowMsgs > 0.0)
        // потери переполнения не смешаны с канальными
        assertTrue(result.carriedMsgs > 0.0 && result.bufferOverflowMsgs != result.blindLossMsgs)
    }

    @Test
    @DisplayName("TZ-FLW-007: потери переполнения распределяются по приоритетам буфера")
    fun `потери переполнения следуют политике приоритетов`() {
        val result = run(
            segments = SegmentCapacity(onboardBufferMsgs = 200, feederMsgsPerContact = 9_000.0),
        )
        val lost = result.byClass.associate { it.consumerClass to it.bufferOverflowMsgs }
        // вытесняется худший приоритет: A' теряет первым, C' — последним (TZ-KA-008)
        assertTrue(lost.getValue("A_prime") > 0.0, "A' не потерял ничего при переполнении")
        assertTrue(lost.getValue("C_prime") <= lost.getValue("B_prime"), "$lost")
        assertTrue(lost.getValue("B_prime") <= lost.getValue("A_prime"), "$lost")
        // сумма долей равна общей потере: ничего не потерялось и не удвоилось
        assertEquals(result.bufferOverflowMsgs, lost.values.sum(), 1e-6)
    }

    @Test
    @DisplayName("TZ-FLW-007: ISL появляется в карте загрузки только в режимах с ISL")
    fun `ISL появляется только в режимах с ISL`() {
        val segments = SegmentCapacity(feederMsgsPerContact = 9_000.0, islMsgsPerContact = 9_000.0)
        assertTrue("isl" !in run(mode = DeliveryMode.StoreAndForward, segments = segments).loads)
        assertTrue("isl" in run(mode = DeliveryMode.IslFullMesh, segments = segments).loads)
    }

    @Test
    @DisplayName("TZ-FLW-007: неизвестная ёмкость участка не даёт выдуманной загрузки")
    fun `неизвестная ёмкость участка не попадает в карту`() {
        val loads = run().loads
        assertEquals(setOf("user_uplink"), loads.keys, "появились участки без заданной ёмкости")
    }

    // ---------- TZ-FLW-008 ----------

    @Test
    @DisplayName("TZ-FLW-008: редкие пролёты дают деградацию и отдельные потери слепой передачи")
    fun `потери слепой передачи учитываются отдельно`() {
        val engine = MonteCarloEngine(mapper)
        val slice = PopulationSlice("C-009", "A_prime", 1_000.0, 2.0, weight = 1_000.0)
        val rare = engine.run(
            scenarioRef = "SC-0001", populations = listOf(slice),
            userPasses = passes("C-009", 1), relayContacts = passes("GS-01", 6),
            channel = channel.copy(maxAlmanacAgeS = 3_600.0),
            horizonS = horizonS, runs = 50, rngSeed = 42,
        )
        val cls = rare.byClass.single()
        assertTrue(cls.degradedShare > 0.0, "деградации нет при одном пролёте в сутки")
        assertTrue(cls.blindLossMsgs > 0.0, "потери слепой передачи не выделены")
        val doc = rare.toContractJson(mapper)["load"]
        assertTrue(doc["blind_transmission_losses"].asDouble() > 0.0)
    }

    @Test
    @DisplayName("TZ-FLW-008: редкий маяк увеличивает долю деградированных терминалов")
    fun `редкий маяк увеличивает долю деградированных`() {
        fun shareWithBeacon(periodS: Double): Double {
            val engine = MonteCarloEngine(mapper)
            return engine.run(
                scenarioRef = "SC-0001",
                populations = listOf(PopulationSlice("C-001", "A_prime", 1_000.0, 2.0, weight = 1_000.0)),
                userPasses = passes("C-001", 14), relayContacts = passes("GS-01", 6),
                channel = channel.copy(beaconPeriodS = periodS, maxAlmanacAgeS = 7_200.0),
                horizonS = horizonS, runs = 20, rngSeed = 42,
            ).byClass.single().degradedShare
        }
        assertTrue(shareWithBeacon(43_200.0) > shareWithBeacon(600.0))
    }
}
