// Представление экрана 5 «Модель космического аппарата» (STEP-7-9 §9.1).
//
// Все бюджеты — массовый, энергетический, радиолиний, маяка — считаются ЗДЕСЬ
// вызовами core/ka. Клиент не складывает массы и не сравнивает запас с нулём:
// это правила, а не отрисовка (STEP-7-9, ловушка 2).
//
// Резерв по зрелости не «добавляется в конце»: MEL-политика применяется
// поэлементно (TZ-KA-002), поэтому сумма с резервом больше суммы номиналов
// на разную величину при разном составе.
package orbita.out

import orbita.ka.Battery
import orbita.ka.BeaconFormat
import orbita.ka.LinkLeg
import orbita.ka.MassItem
import orbita.ka.Maturity
import orbita.ka.ModeSlot
import orbita.ka.PlatformPreset
import orbita.ka.PlatformPresets
import orbita.ka.PowerModel
import orbita.ka.SolarArray
import orbita.ka.Tpm
import orbita.ka.TpmRegistry
import orbita.ka.beaconEnergyWh
import orbita.ka.beaconDownlinkLoad
import orbita.ka.beaconPayloadBytes
import orbita.ka.dryMassKg
import orbita.ka.limitingFactor
import orbita.ka.linkMarginDb
import orbita.ka.serviceElevationDeg
import orbita.ka.wetMassKg
import orbita.ka.withinPlatformRange
import orbita.mod.model.MissingMelException
import orbita.mod.model.MissingOrbitFractionException
import orbita.bal.orbitalPeriodS
import orbita.net.LoRaWanAdapter
import kotlin.math.abs

/** Строка массового бюджета: номинал, зрелость, резерв по зрелости. */
data class MassRow(
    val name: String,
    val massKg: Double,
    val maturity: String,
    val marginPct: Double,
    val withMarginKg: Double,
)

data class MassBudgetView(
    val items: List<MassRow>,
    val nominalKg: Double,
    val systemMarginPct: Double,
    val dryMassKg: Double,
    val wetMassKg: Double,
    val deltaVMs: Double,
    /** Р2/ADR-002: диапазон 12U–100 кг; вне него конфигурация недопустима. */
    val withinPlatformRange: Boolean,
)

/**
 * Энергетика худшего витка.
 *
 * Баланс считается при ЗАЯВЛЕННОЙ скважности ПН, а не при допустимой.
 * Подставить сюда допустимую значило бы получить ноль по построению:
 * допустимая скважность и есть та, при которой генерация равна потреблению,
 * и такой «баланс» никогда ни о чём не предупредит.
 */
data class PowerView(
    val altKm: Double,
    val worstBetaDeg: Double,
    val generatedWh: Double,
    val consumedWh: Double,
    val balanceWh: Double,
    val beaconWh: Double,
    /** Заявленная скважность ПН — вход модели. */
    val plannedPayloadDuty: Double,
    /** Допустимая энергетикой скважность — вычислена (TZ-KA-004). */
    val allowedPayloadDuty: Double,
    val batteryDod: Double,
    val batteryMaxDod: Double,
    val balanceOk: Boolean,
    val dutyOk: Boolean,
    val dodOk: Boolean,
)

/** Радиолиния: запас на границе зоны и то, чем зона ограничена. */
data class LinkRow(
    val id: String,
    val role: String,
    val bandHz: Double,
    val eirpDbw: Double,
    val bitrateBps: Double,
    val requiredMarginDb: Double,
    /** Запас в надире — лучший случай геометрии. */
    val marginAtZenithDb: Double,
    /** Запас на минимальном угле места — рабочая граница. */
    val marginAtMinElevDb: Double,
    /** Угол места границы зоны; null — линия не замыкается нигде. */
    val serviceElevationDeg: Double?,
    val limitingFactor: String,
    val closes: Boolean,
)

data class BeaconView(
    val format: String,
    val periodS: Double,
    val payloadBytes: Int,
    val downlinkLoad: Double,
    val energyWhPerOrbit: Double,
)

data class TpmRow(
    val name: String,
    val current: Double,
    val unit: String,
    val target: Double,
    val marginPct: Double,
    val requiredMarginPct: Double,
    val breached: Boolean,
    val lowerIsBetter: Boolean,
)

data class SpacecraftView(
    val id: String,
    val preset: String?,
    val mass: MassBudgetView,
    val power: PowerView,
    val links: List<LinkRow>,
    val beacon: BeaconView?,
    val tpm: List<TpmRow>,
    val issues: List<String>,
)

/** Пресет платформы как строка выбора (TZ-KA-001). */
data class PresetRow(
    val id: String,
    val name: String,
    val dryMassKg: Double,
    val saAreaM2: Double,
    val batteryWh: Double,
    val busPowerW: Double,
    val payloadPowerW: Double,
    val designLifeYears: Double,
)

/** Условия оценки: высота, худшая бета, минимальный угол места, срок в орбите. */
data class SpacecraftConditions(
    val altKm: Double = 550.0,
    val worstBetaDeg: Double = 0.0,
    val minElevDeg: Double = 5.0,
    val yearsInOrbit: Double = 0.0,
    /** Заявленная скважность ПН: вход модели, а не следствие энергетики. */
    val plannedPayloadDuty: Double = 0.5,
)

class SpacecraftViews(
    private val presets: PlatformPresets = PlatformPresets(),
    private val adapter: LoRaWanAdapter = LoRaWanAdapter(),
) {

    fun presetRows(): List<PresetRow> = presets.presets.map {
        PresetRow(
            id = it.id, name = it.name, dryMassKg = it.dryMassKg, saAreaM2 = it.saAreaM2,
            batteryWh = it.batteryWh, busPowerW = it.busPowerW,
            payloadPowerW = it.payloadPowerW, designLifeYears = it.designLifeYears,
        )
    }

    /**
     * Сборка экрана по модели КА (схема `contracts/spacecraft`) и условиям.
     *
     * Ведомость масс берётся из документа (`platform.mel`, CR-006): она часть
     * модели, а не состояние экрана. Её отсутствие — ошибка, а не ноль:
     * молчаливый ноль выглядит как результат.
     */
    fun build(
        doc: com.fasterxml.jackson.databind.JsonNode,
        conditions: SpacecraftConditions = SpacecraftConditions(),
    ): SpacecraftView {
        val massItems = doc.path("platform").path("mel").map { item ->
            MassItem(
                name = item.path("name").asText(""),
                massKg = item.path("mass_kg").asDouble() * item.path("quantity").asInt(1),
                maturity = Maturity.of(item.path("maturity").asText("")),
            )
        }
        if (massItems.isEmpty()) {
            throw MissingMelException(
                "ведомость масс не задана: политику резервов не к чему применить (CR-006)",
            )
        }
        val presetId = doc.path("preset").asText("").ifBlank { null }
        val preset = presetId?.let { presets.byId(it) }
        val platform = doc.path("platform")
        val power = powerModel(platform, preset, doc.path("modes"))

        // Энергия маяка входит в циклограмму НЕокруглённой: округление —
        // последний шаг перед экраном, и обратно в расчёт оно не возвращается.
        val beaconWh = beaconEnergyWh(doc, conditions)
        val beacon = beaconView(doc, beaconWh)

        val raw = Raw(
            dryMassKg = dryMass(platform, massItems),
            power = power,
            beaconWh = beaconWh,
            legs = doc.path("payload").path("links").map { leg(it, conditions) },
        )

        val mass = massView(platform, massItems, raw.dryMassKg)
        val powerView = powerView(power, conditions, beaconWh)
        val links = raw.legs.map { linkRow(it, conditions) }
        val tpm = tpmRows(raw, conditions, platform)

        return SpacecraftView(
            id = doc.path("id").asText(""),
            preset = presetId,
            mass = mass,
            power = powerView,
            links = links,
            beacon = beacon,
            tpm = tpm,
            issues = issues(mass, powerView, links, beacon),
        )
    }

    /**
     * НЕокруглённые величины модели. Округление — последний шаг перед экраном;
     * правила (диапазон платформы, выход TPM за резерв, замыкание линии)
     * считаются только по этим значениям. Иначе округление до четвёртой
     * значащей цифры однажды втянуло бы конфигурацию обратно в допуск.
     */
    private data class Raw(
        val dryMassKg: Double,
        val power: PowerModel,
        val beaconWh: Double,
        val legs: List<Leg>,
    )

    /** Участок линии вместе с требуемым запасом: требование задаётся моделью. */
    private data class Leg(val leg: LinkLeg, val role: String, val requiredMarginDb: Double)

    private fun dryMass(platform: com.fasterxml.jackson.databind.JsonNode, items: List<MassItem>): Double {
        val systemMargin = systemMarginPct(platform)
        return if (items.isEmpty()) platform.path("dry_mass_kg").asDouble(0.0)
        else dryMassKg(items, systemMargin)
    }

    private fun systemMarginPct(platform: com.fasterxml.jackson.databind.JsonNode): Double =
        platform.path("mel_margin_policy").path("system_margin_pct")
            .takeIf { it.isNumber }?.asDouble() ?: 10.0

    private fun massView(
        platform: com.fasterxml.jackson.databind.JsonNode,
        items: List<MassItem>,
        dry: Double,
    ): MassBudgetView {
        val systemMargin = systemMarginPct(platform)
        val dv = deltaV(platform)
        return MassBudgetView(
            items = items.map {
                MassRow(
                    name = it.name,
                    massKg = sig(it.massKg),
                    maturity = it.maturity.name,
                    marginPct = it.maturity.marginPct,
                    withMarginKg = sig(it.massKg * (1 + it.maturity.marginPct / 100.0)),
                )
            },
            nominalKg = sig(items.sumOf { it.massKg }),
            systemMarginPct = systemMargin,
            dryMassKg = sig(dry),
            wetMassKg = sig(wetMassKg(dry, dv)),
            deltaVMs = sig(dv),
            // признак диапазона считается по НЕокруглённой массе: округление
            // не должно втягивать конфигурацию обратно в допустимый диапазон
            withinPlatformRange = withinPlatformRange(dry),
        )
    }

    private fun deltaV(platform: com.fasterxml.jackson.databind.JsonNode): Double =
        platform.path("propulsion").path("delta_v_budget_ms").let { b ->
            listOf("phasing", "maintenance", "collision_avoidance", "deorbit")
                .sumOf { b.path(it).takeIf { v -> v.isNumber }?.asDouble() ?: 0.0 }
        }

    private fun powerModel(
        platform: com.fasterxml.jackson.databind.JsonNode,
        preset: PlatformPreset?,
        modes: com.fasterxml.jackson.databind.JsonNode,
    ): PowerModel {
        val p = platform.path("power")
        // Доля витка берётся из модели и обязана быть задана (CR-007). Раньше
        // здесь виток делился поровну: это давало правдоподобное число, которое
        // ни о чём не говорило, — та же болезнь, что баланс, равный нулю
        // по построению. Отсутствие доли — ошибка расчёта, а не повод делить.
        val missing = modes.filter { it.path("orbit_fraction").isMissingNode || it.path("orbit_fraction").isNull }
            .map { it.path("name").asText("") }
        if (missing.isNotEmpty()) {
            throw MissingOrbitFractionException("доля витка не задана для режимов: $missing")
        }
        val slots = modes.map { m ->
            ModeSlot(
                name = m.path("name").asText(""),
                fraction = m.path("orbit_fraction").asDouble(),
                powerW = m.path("power_w").asDouble(),
            )
        }
        if (slots.isNotEmpty() && abs(slots.sumOf { it.fraction } - 1.0) > 1e-6) {
            throw IllegalArgumentException(
                "доли витка в сумме дают ${slots.sumOf { it.fraction }}, а не 1",
            )
        }
        return PowerModel(
            sa = SolarArray(
                areaM2 = p.path("sa_area_m2").asDouble(preset?.saAreaM2 ?: 0.0),
                efficiency = p.path("sa_efficiency").asDouble(preset?.saEfficiency ?: 0.0),
                degradationPctPerYear = p.path("sa_degradation_pct_per_year").asDouble(2.0),
                mounting = p.path("sa_mounting").asText("body_fixed"),
            ),
            battery = Battery(
                capacityWh = p.path("battery_wh").asDouble(preset?.batteryWh ?: 0.0),
                maxDod = p.path("battery_max_dod").asDouble(preset?.batteryMaxDod ?: 0.3),
            ),
            busPowerW = preset?.busPowerW ?: 0.0,
            payloadPowerW = preset?.payloadPowerW ?: 0.0,
            modes = slots,
        )
    }

    private fun powerView(power: PowerModel, c: SpacecraftConditions, beaconWh: Double): PowerView {
        val allowed = power.allowedPayloadDutyCycle(c.altKm, c.worstBetaDeg, beaconWh, c.yearsInOrbit).value
        val planned = c.plannedPayloadDuty
        val generated = power.generatedWh(c.altKm, c.worstBetaDeg, c.yearsInOrbit)
        val consumed = power.consumedWh(c.altKm, beaconWh, planned)
        val dod = power.batteryDod(c.altKm, c.worstBetaDeg, power.busPowerW + power.payloadPowerW * planned)
        // Признаки считаются по НЕокруглённым величинам: округление не должно
        // превращать отрицательный баланс в нулевой и снимать предупреждение.
        return PowerView(
            altKm = c.altKm,
            worstBetaDeg = c.worstBetaDeg,
            generatedWh = sig(generated),
            consumedWh = sig(consumed),
            balanceWh = sig(generated - consumed),
            beaconWh = sig(beaconWh),
            plannedPayloadDuty = planned,
            allowedPayloadDuty = sig(allowed),
            batteryDod = sig(dod),
            batteryMaxDod = power.battery.maxDod,
            balanceOk = generated - consumed >= 0.0,
            dutyOk = allowed >= planned,
            dodOk = dod <= power.battery.maxDod,
        )
    }

    /**
     * Участок радиолинии. Скорость и требуемое Eb/N0 берутся из адаптера
     * протокола (TZ-NET-001), а не задаются на экране: иначе бюджет считался
     * бы по произвольным цифрам.
     */
    private fun leg(link: com.fasterxml.jackson.databind.JsonNode, c: SpacecraftConditions): Leg {
        val mode = adapter.modes.first()
        val txW = link.path("tx_power_w").asDouble(0.0)
        val gainDbi = link.path("antenna").path("gain_dbi").asDouble(0.0)
        return Leg(
            leg = LinkLeg(
                id = link.path("id").asText(""),
                eirpDbw = 10 * Math.log10(maxOf(txW, 1e-9)) + gainDbi,
                altKm = c.altKm,
                freqHz = link.path("band_hz").asDouble(0.0),
                gOverTDbk = link.path("g_over_t_db_k").takeIf { it.isNumber }?.asDouble() ?: 0.0,
                bitrateBps = mode.bitrateBps,
                requiredEbn0Db = mode.requiredEbn0Db,
            ),
            role = link.path("role").asText(""),
            requiredMarginDb = link.path("required_margin_db").takeIf { it.isNumber }?.asDouble() ?: 3.0,
        )
    }

    private fun linkRow(row: Leg, c: SpacecraftConditions): LinkRow {
        val leg = row.leg
        val serviceElev = serviceElevationDeg(leg, row.requiredMarginDb, c.minElevDeg)
        return LinkRow(
            id = leg.id,
            role = row.role,
            bandHz = leg.freqHz,
            eirpDbw = sig(leg.eirpDbw),
            bitrateBps = leg.bitrateBps,
            requiredMarginDb = row.requiredMarginDb,
            marginAtZenithDb = sig(linkMarginDb(leg, 90.0)),
            marginAtMinElevDb = sig(linkMarginDb(leg, c.minElevDeg)),
            serviceElevationDeg = sig(serviceElev),
            limitingFactor = serviceElev?.let { limitingFactor(it, c.minElevDeg) } ?: "линия не замыкается",
            closes = serviceElev != null,
        )
    }

    /** Энергия маяка за виток — слагаемое циклограммы, не опция (TZ-KA-006). */
    private fun beaconEnergyWh(
        doc: com.fasterxml.jackson.databind.JsonNode,
        c: SpacecraftConditions,
    ): Double {
        val b = doc.path("payload").path("ephemeris_beacon")
        val format = beaconFormat(b) ?: return 0.0
        val downlink = doc.path("payload").path("links")
            .firstOrNull { it.path("role").asText() == "user_downlink" }
        return beaconEnergyWh(
            periodS = b.path("period_s").asDouble(0.0),
            payloadBytes = beaconPayloadBytesOf(b, format),
            overheadBytes = adapter.overheadBytes,
            bitrateBps = adapter.modes.first().bitrateBps,
            txPowerW = downlink?.path("tx_power_w")?.asDouble(0.0) ?: 0.0,
            orbitS = orbitalPeriodS(c.altKm),
        )
    }

    private fun beaconFormat(b: com.fasterxml.jackson.databind.JsonNode): BeaconFormat? =
        when (b.path("format").asText("")) {
            "pass_schedule" -> BeaconFormat.PassSchedule
            "full_almanac" -> BeaconFormat.FullAlmanac
            "orbit_model" -> BeaconFormat.OrbitModel
            else -> null
        }

    private fun beaconPayloadBytesOf(
        b: com.fasterxml.jackson.databind.JsonNode,
        format: BeaconFormat,
    ): Int = b.path("payload_bytes").takeIf { it.isNumber }?.asInt() ?: beaconPayloadBytes(format)

    private fun beaconView(doc: com.fasterxml.jackson.databind.JsonNode, energyWh: Double): BeaconView? {
        val b = doc.path("payload").path("ephemeris_beacon")
        val format = beaconFormat(b) ?: return null
        val period = b.path("period_s").asDouble(0.0)
        val payload = beaconPayloadBytesOf(b, format)
        return BeaconView(
            format = b.path("format").asText(""),
            periodS = period,
            payloadBytes = payload,
            downlinkLoad = sig(
                beaconDownlinkLoad(period, payload, adapter.overheadBytes, adapter.modes.first().bitrateBps),
            ),
            energyWhPerOrbit = sig(energyWh),
        )
    }

    /**
     * Реестр TPM пересобирается из модели, а не заполняется вручную
     * (TZ-KA-010). Цели — из требований к аппарату; здесь берутся
     * границы, которые модель задаёт сама: диапазон платформы,
     * неотрицательный баланс, требуемый запас худшей линии.
     */
    private fun tpmRows(
        raw: Raw,
        c: SpacecraftConditions,
        platform: com.fasterxml.jackson.databind.JsonNode,
    ): List<TpmRow> {
        val registry = TpmRegistry()
        registry.put(
            Tpm(
                name = "Сухая масса",
                current = TpmRegistry.computed(raw.dryMassKg, "kg"),
                target = TpmRegistry.manual(platform.path("dry_mass_kg").asDouble(raw.dryMassKg), "kg"),
                requiredMarginPct = 0.0,
                lowerIsBetter = true,
            ),
        )
        val worst = raw.legs.minByOrNull { linkMarginDb(it.leg, c.minElevDeg) }
        if (worst != null) {
            registry.put(
                Tpm(
                    name = "Запас худшей линии",
                    current = TpmRegistry.computed(linkMarginDb(worst.leg, c.minElevDeg), "dB"),
                    target = TpmRegistry.manual(worst.requiredMarginDb, "dB"),
                    requiredMarginPct = 0.0,
                    lowerIsBetter = false,
                ),
            )
        }
        // Энергетика входит в TPM скважностью, а не балансом: у баланса цель —
        // ноль, а резерв в процентах от нуля не определён. Допустимая
        // скважность против заявленной — та же величина в пригодной форме.
        if (c.plannedPayloadDuty > 0.0) {
            val allowed = raw.power
                .allowedPayloadDutyCycle(c.altKm, c.worstBetaDeg, raw.beaconWh, c.yearsInOrbit).value
            registry.put(
                Tpm(
                    name = "Скважность ПН",
                    current = TpmRegistry.computed(allowed, "1"),
                    target = TpmRegistry.manual(c.plannedPayloadDuty, "1"),
                    requiredMarginPct = 0.0,
                    lowerIsBetter = false,
                ),
            )
        }
        val load = raw.power.busPowerW + raw.power.payloadPowerW * c.plannedPayloadDuty
        registry.put(
            Tpm(
                name = "Глубина разряда АБ",
                current = TpmRegistry.computed(raw.power.batteryDod(c.altKm, c.worstBetaDeg, load), "1"),
                target = TpmRegistry.manual(raw.power.battery.maxDod, "1"),
                requiredMarginPct = 0.0,
                lowerIsBetter = true,
            ),
        )
        // Признак выхода за резерв берётся по неокруглённым величинам;
        // округляются только числа, которые уходят на экран.
        return registry.all().map {
            TpmRow(
                name = it.name,
                current = sig(it.current.value),
                unit = it.current.unit,
                target = sig(it.target.value),
                marginPct = sig(it.marginPct),
                requiredMarginPct = it.requiredMarginPct,
                breached = it.breached,
                lowerIsBetter = it.lowerIsBetter,
            )
        }
    }

    private fun issues(
        mass: MassBudgetView,
        power: PowerView,
        links: List<LinkRow>,
        beacon: BeaconView?,
    ): List<String> = buildList {
        if (!mass.withinPlatformRange) {
            add("сухая масса ${"%.1f".format(mass.dryMassKg)} кг вне диапазона платформы 12–100 кг (Р2/ADR-002)")
        }
        if (!power.balanceOk) {
            add("баланс худшего витка отрицателен: ${"%.1f".format(power.balanceWh)} Вт·ч")
        }
        if (!power.dutyOk) {
            add(
                "заявленная скважность ПН ${power.plannedPayloadDuty} выше допустимой " +
                    "энергетикой ${"%.3f".format(power.allowedPayloadDuty)}",
            )
        }
        if (!power.dodOk) {
            add("глубина разряда АБ ${"%.2f".format(power.batteryDod)} выше допустимой ${power.batteryMaxDod}")
        }
        links.filterNot { it.closes }.forEach { add("${it.id}: линия не замыкается ни на одном угле места") }
        links.filter { it.closes && it.limitingFactor == "link_margin" }
            .forEach { add("${it.id}: зона обслуживания ограничена запасом линии, а не геометрией") }
        if (beacon == null) add("маяк эфемерид не задан — Р5/ADR-005 требует его обязательно")
    }
}
