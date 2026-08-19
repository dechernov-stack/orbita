// Прикладной контур аппарата (TZ-KA-001…010): пресеты, массовый бюджет с ΔV
// увода, энергетика с обязательным маяком, зоны обслуживания по паре
// «линия × профиль», согласованность маяка с профилями, реестр TPM.
package orbita.ka

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.bal.decayYears
import orbita.bal.deorbitDeltaVMs
import orbita.bal.eclipseFraction
import orbita.bal.seasonBetaBoundsDeg
import orbita.mod.RepoPaths
import orbita.mod.schema.SchemaRegistry
import orbita.mod.schema.ValidationError
import orbita.mod.store.ModelViolationException
import orbita.net.LoRaWanAdapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SpacecraftModelTest {

    private val mapper = ObjectMapper()
    private val registry = SchemaRegistry(RepoPaths.schemasDir())
    private val adapter = LoRaWanAdapter()
    private val presets = PlatformPresets()

    /** Параметры канала — только из адаптера (TZ-KA-007). */
    private fun legFor(modeId: String, eirpDbw: Double, gOverT: Double = -15.0) = LinkLeg(
        id = "L-$modeId", eirpDbw = eirpDbw, altKm = 550.0, freqHz = 868e6,
        gOverTDbk = gOverT,
        bitrateBps = adapter.mode(modeId).bitrateBps,
        requiredEbn0Db = adapter.mode(modeId).requiredEbn0Db,
    )

    @Test
    fun `пресеты платформ - редактируемая конфигурация в диапазоне Р2`() {
        assertEquals(
            listOf("cubesat_12u", "cubesat_16u", "micro_25_50kg", "micro_50_100kg"),
            presets.presets.map { it.id },
        )
        presets.presets.forEach { p ->
            assertTrue(withinPlatformRange(p.dryMassKg)) { "${p.id}: ${p.dryMassKg} кг вне Р2" }
        }
    }

    @Test
    fun `массовый бюджет с ΔV увода отклоняет выход за Р2`() {
        val items = listOf(
            MassItem("структура", 20.0, Maturity.Existing),
            MassItem("СЭП", 18.0, Maturity.Modified),
            MassItem("ПН", 25.0, Maturity.New),
        )
        val dry = dryMassQuantity(items)
        assertTrue(dry.provenance is orbita.mod.model.Provenance.Computed)
        assertEquals(emptyList<ValidationError>(), registry.validate("common/quantity", dry.toJson(mapper)))

        // ΔV увода приходит из баллистики, здесь не вычисляется
        val dv = deorbitDeltaVMs(altKm = 550.0)
        assertTrue(dv > 100 && dv < 200) { "ΔV увода $dv м/с" }
        val wet = wetMassKg(dry.value, dv)
        assertTrue(wet > dry.value) { "заправленная масса должна быть больше сухой" }
        requireWithinPlatformRange(wet, "wet mass")

        // тяжёлая конфигурация отклоняется со ссылкой на Р2/ADR-002
        val heavy = dryMassKg(listOf(MassItem("платформа", 90.0, Maturity.New)))
        val e = assertThrows<ModelViolationException> { requireWithinPlatformRange(heavy) }
        assertTrue("ADR-002" in e.message!! && "Р2" in e.message!!)
    }

    @Test
    fun `потребление маяка входит в циклограмму и снижает скважность ПН`() {
        val preset = presets.byId("cubesat_12u")
        // ПН класса 50–100 кг на платформе 12U: рабочая точка, где скважность
        // ограничена энергетикой, а не насыщена (при избытке энергии она равна 1
        // и разница от маяка не видна — величина неинформативна)
        val model = preset.powerModel(listOf(ModeSlot("rx", 0.6, 4.0), ModeSlot("downlink", 0.05, 6.0)))
            .copy(payloadPowerW = 120.0)
        val orbitS = orbita.bal.orbitalPeriodS(550.0)
        val beaconWh = beaconEnergyWh(60.0, beaconPayloadBytes(BeaconFormat.OrbitModel), 13, 300.0, 6.0, orbitS)
        assertTrue(beaconWh > 0)

        val withBeacon = model.allowedPayloadDutyCycle(550.0, 0.0, beaconWh)
        val withoutBeacon = model.allowedPayloadDutyCycle(550.0, 0.0, 0.0)
        assertTrue(withBeacon.value < withoutBeacon.value) { "маяк обязан снижать доступную скважность" }
        assertTrue(withBeacon.value > 0 && withBeacon.value < 1.0) { "скважность ${withBeacon.value} насыщена" }
        assertTrue(withBeacon.provenance is orbita.mod.model.Provenance.Computed)
        // при избытке энергии скважность честно упирается в единицу
        assertEquals(1.0, preset.powerModel().allowedPayloadDutyCycle(550.0, 0.0, beaconWh).value)

        // деградация СБ к концу срока службы уменьшает генерацию
        assertTrue(model.generatedWh(550.0, 0.0, preset.designLifeYears) < model.generatedWh(550.0, 0.0, 0.0))
    }

    @Test
    fun `энергобаланс худшего витка выявляет несостоятельную конфигурацию`() {
        val model = presets.byId("cubesat_12u").powerModel().copy(payloadPowerW = 120.0)
        val orbitS = orbita.bal.orbitalPeriodS(550.0)
        val beaconWh = beaconEnergyWh(60.0, 40, 13, 300.0, 6.0, orbitS)
        val (worstBeta, bestBeta) = seasonBetaBoundsDeg(53.0)
        assertTrue(eclipseFraction(550.0, worstBeta) > eclipseFraction(550.0, bestBeta))

        // при нулевой скважности ПН баланс положительный
        assertTrue(model.worstOrbitBalanceWh(550.0, worstBeta, beaconWh, payloadDuty = 0.0) > 0)
        // при полной загрузке ПН — отрицательный: конфигурация несостоятельна
        assertTrue(model.worstOrbitBalanceWh(550.0, worstBeta, beaconWh, payloadDuty = 1.0) < 0)
        // глубина разряда АБ на теневом участке в пределах допустимой
        val dod = model.batteryDod(550.0, worstBeta, loadW = model.busPowerW)
        assertTrue(dod > 0 && dod < model.battery.maxDod) { "DoD=$dod" }
    }

    @Test
    fun `зоны обслуживания различаются по линиям и профилям с указанием фактора`() {
        // A': приём сильной восходящей линии; C': слабая нисходящая до малого терминала.
        // Уровни подобраны под РЕАЛЬНОЕ требуемое Eb/N0 из адаптера (+6,3 дБ у SF12),
        // а не под условное −6 дБ эталона: с ним слабая линия не замкнулась бы нигде.
        val zoneA = ServiceZones.compute("KA-1", legFor("SF12", -16.0), "T-AGRO", requiredMarginDb = 3.0)
        val zoneC = ServiceZones.compute("KA-1", legFor("SF12", -26.0), "T-SEA", requiredMarginDb = 3.0)
        assertNotNull(zoneA); assertNotNull(zoneC)
        assertTrue(zoneC!!.serviceElevDeg > zoneA!!.serviceElevDeg) {
            "A'=${zoneA.serviceElevDeg} C'=${zoneC.serviceElevDeg}"
        }
        assertEquals("geometry", zoneA.limitingFactor)
        assertEquals("link_margin", zoneC.limitingFactor)
        assertTrue(zoneA.radiusKm > zoneC.radiusKm && zoneA.areaKm2 > zoneC.areaKm2)

        // документы валидны по нормативной схеме service-zone
        listOf(zoneA, zoneC).forEach { z ->
            val doc = ServiceZones.toContractJson(z, mapper)
            assertEquals(emptyList<ValidationError>(), registry.validate("contracts/service-zone", doc)) {
                registry.validate("contracts/service-zone", doc).toString()
            }
        }
        // незамыкающаяся линия зоны не даёт вовсе
        assertNull(ServiceZones.compute("KA-1", legFor("SF12", -45.0), "T-SEA", 3.0))
        // разные режимы адаптера дают разные зоны на одной линии: быстрый SF7
        // требует большей мощности сигнала, поэтому его зона не шире
        val zoneFast = ServiceZones.compute("KA-1", legFor("SF7", -16.0), "T-AGRO", 3.0)
        assertTrue(zoneFast == null || zoneFast.serviceElevDeg >= zoneA.serviceElevDeg)
    }

    @Test
    fun `доплеровский сдвиг сравнивается с полосой захвата приёмника`() {
        val tolerance = adapter.mode("SF12").dopplerToleranceHz
        val zone = ServiceZones.compute(
            "KA-1", legFor("SF12", -16.0), "T-AGRO", requiredMarginDb = 3.0,
            dopplerToleranceHz = tolerance,
        )!!
        val d = zone.doppler!!
        assertTrue(d.maxShiftHz > 0 && d.maxRateHzS > 0)
        assertEquals(d.maxShiftHz <= tolerance, d.withinCapture)
        // узкая полоса захвата выявляет несовместимость
        val narrow = doppler(550.0, 868e6, 5.0, toleranceHz = 100.0)
        assertFalse(narrow.withinCapture)
    }

    @Test
    fun `несогласованность маяка с профилями терминалов выявляется отчётом`() {
        val profiles = listOf(
            Triple("T-AGRO", 172800.0, 8.0),   // допуск 48 ч, 8 пролётов — согласован
            Triple("T-SEA", 3600.0, 4.0),      // допуск 1 ч при 4 пролётах — нет
        )
        val mismatches = beaconMismatches(beaconPeriodS = 60.0, profiles = profiles)
        assertEquals(listOf("T-SEA"), mismatches.map { it.terminalProfileRef })

        // формат маяка выбирается из трёх; модель орбиты — самая экономная
        assertTrue(
            beaconPayloadBytes(BeaconFormat.OrbitModel) < beaconPayloadBytes(BeaconFormat.PassSchedule) &&
                beaconPayloadBytes(BeaconFormat.PassSchedule) < beaconPayloadBytes(BeaconFormat.FullAlmanac)
        )
    }

    @Test
    fun `буфер сопоставляется с худшим интервалом до сброса, потери учитываются отдельно`() {
        val need = requiredBufferMsgs(msgsPerS = 0.2, worstGapS = 5400.0)
        assertEquals(1080, need)
        var queue = emptyList<BufferedMsg>()
        var dropped = 0
        var admitted = 0
        repeat(20) { i ->
            val r = bufferAdmit(queue, BufferedMsg(if (i % 3 == 0) "C_prime" else "A_prime", i.toDouble()), capacity = 10)
            queue = r.queue
            if (r.dropped != null) dropped++ else admitted++
        }
        val losses = BufferLosses(dropped, admitted)
        assertTrue(losses.overflowDropped > 0) { "переполнение должно фиксироваться отдельно" }
        assertTrue(losses.overflowRate > 0 && losses.overflowRate < 1)
        // C' переживает вытеснение: в очереди остаются приоритетные сообщения
        assertTrue(queue.count { it.klass == "C_prime" } >= queue.count { it.klass == "A_prime" })
    }

    @Test
    fun `реестр TPM обновляется автоматически и помечает выход за резерв`() {
        val items = listOf(MassItem("платформа", 14.0, Maturity.New), MassItem("ПН", 4.0, Maturity.Existing))
        val registry = TpmRegistry()
        registry.put(
            Tpm(
                name = "dry_mass",
                current = TpmRegistry.computed(dryMassKg(items), "kg"),
                target = TpmRegistry.manual(22.0, "kg"),
                requiredMarginPct = 5.0, lowerIsBetter = true,
                trend = listOf(TpmTrendPoint("2026-06-01T00:00:00Z", "SRR", dryMassKg(items))),
            )
        )
        val mass = registry.get("dry_mass")!!
        assertTrue(mass.marginPct.isFinite())

        // ухудшение параметра помечается, тренд сохраняется
        val heavier = items + MassItem("допблок", 4.0, Maturity.New)
        registry.put(
            mass.copy(
                current = TpmRegistry.computed(dryMassKg(heavier), "kg"),
                trend = listOf(TpmTrendPoint("2026-09-01T00:00:00Z", "SDR", dryMassKg(heavier))),
            )
        )
        val updated = registry.get("dry_mass")!!
        assertTrue(updated.breached) { "резерв ${updated.marginPct}% должен быть меньше требуемых 5%" }
        assertEquals(2, updated.trend.size)
        assertEquals(listOf("dry_mass"), registry.breached().map { it.name })

        // остальные TPM реестра: энергобаланс, запас линии, ёмкость, буфер, ΔV
        val orbitS = orbita.bal.orbitalPeriodS(550.0)
        val model = presets.byId("cubesat_16u").powerModel()
        val beaconWh = beaconEnergyWh(60.0, 24, 13, 300.0, 6.0, orbitS)
        registry.put(Tpm("worst_orbit_balance",
            TpmRegistry.computed(model.worstOrbitBalanceWh(550.0, 0.0, beaconWh, 0.2), "W*h"),
            TpmRegistry.manual(0.0, "W*h"), 0.0, lowerIsBetter = false))
        registry.put(Tpm("worst_link_margin",
            TpmRegistry.computed(linkMarginDb(legFor("SF12", -16.0), 10.0), "dB"),
            TpmRegistry.manual(3.0, "dB"), 0.0, lowerIsBetter = false))
        registry.put(Tpm("deorbit_delta_v",
            TpmRegistry.computed(deorbitDeltaVMs(550.0), "m/s"),
            TpmRegistry.manual(200.0, "m/s"), 0.0, lowerIsBetter = true))
        assertEquals(4, registry.all().size)
        assertTrue(decayYears(550.0, 50.0, 0.5).isFinite())
    }
}
