// Представления экранов 4 и 5 (STEP-7-9 §9.1). Проверяется не отрисовка,
// а то, что экран получает ГОТОВЫЕ величины и не может показать иное число,
// чем расчётный модуль: доля от максимума, вес пояса, резерв по зрелости,
// энергия маяка в балансе.
package orbita.out

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.ka.MassItem
import orbita.ka.Maturity
import orbita.usr.PopulationCell
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DemandViewsTest {

    private val views = DemandViews()

    private val populations = listOf(
        PopulationCell("p15", 15.0, 20.0, 0.02, 4.0, "A_prime"),
        PopulationCell("p45", 45.0, 50.0, 0.02, 4.0, "A_prime"),
        PopulationCell("p70", 70.0, 0.5, 0.02, 4.0, "B_prime"),
    )

    /**
     * Веса нормированы. Допуск здесь — не небрежность: величины представления
     * округлены для чтения (Display.sig), поэтому сумма показанных весов
     * отличается от единицы на величину округления. Точную нормировку модели
     * проверяет эталон spec/demand_semantics.py и DemandSemanticsTest —
     * там сравнение идёт с 1e-9.
     */
    @Test
    fun `веса ячеек нормированы`() {
        val view = views.build(DemandLayers(population = populations))
        assertEquals(1.0, view.cells.sumOf { it.weight }, 1e-3)
    }

    @Test
    fun `округление представления не трогает величины модели`() {
        val raw = orbita.usr.DemandMapBuilder.build(populations)
        assertEquals(1.0, raw.values.sumOf { it.weight }, 1e-9)
        // на экран уходит округлённое, в модели остаётся точное
        val view = views.build(DemandLayers(population = populations))
        val cell = view.cells.first { it.id == "p15" }
        assertEquals(raw.getValue("p15").weight, cell.weight, 1e-3)
        assertTrue(cell.weight.toString().length < raw.getValue("p15").weight.toString().length)
    }

    @Test
    fun `яркость ячейки приходит долей от максимума`() {
        val view = views.build(DemandLayers(population = populations))
        val busiest = view.cells.maxBy { it.msgsPerDay }
        assertEquals(1.0, busiest.intensity, 1e-12)
        assertTrue(view.cells.all { it.intensity <= 1.0 && it.intensity >= 0.0 })
    }

    @Test
    fun `спрос разложен по классам и не усреднён`() {
        val view = views.build(DemandLayers(population = populations))
        assertEquals(setOf("A_prime", "B_prime"), view.byClass.keys)
        // ячейка полярной популяции несёт только свой класс
        assertEquals(setOf("B_prime"), view.cells.first { it.id == "p70" }.byClass.keys)
    }

    @Test
    fun `широтный профиль нормирован`() {
        val view = views.build(DemandLayers(population = populations))
        assertEquals(1.0, view.latitudeProfile.sumOf { it.weight }, 1e-3)
    }

    @Test
    fun `карта строится на одном слое сценариев без населения`() {
        val library = views.referenceScenarios()
        assertTrue(library.isNotEmpty(), "библиотека референсных сценариев пуста")
        val view = views.build(DemandLayers(scenarioIds = listOf(library.first().id)))
        assertTrue(view.cells.isNotEmpty())
        assertEquals(listOf("scenario_library"), view.layers)
        assertTrue(view.issues.any { it.contains("слоя населения нет") })
    }

    @Test
    fun `неизвестный сценарий библиотеки отклоняется`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            views.build(DemandLayers(population = populations, scenarioIds = listOf("нет такого")))
        }
        assertTrue(e.message!!.contains("неизвестные сценарии"))
    }

    @Test
    fun `пустая карта названа пустой, а не нулевой`() {
        val view = views.build(DemandLayers())
        assertTrue(view.issues.any { it.contains("карта пуста") })
        assertEquals(0.0, view.totalMsgsPerDay)
        assertTrue(view.layers.isEmpty())
    }

    /**
     * Пик берётся по худшему сочетанию «час × месяц», а не как среднее:
     * средний час не показывает, чем нагружена система в худшей точке.
     */
    @Test
    fun `пик выбирается по худшему часу, а не по среднему`() {
        val flat = views.build(DemandLayers(population = populations))
        val diurnal = List(24) { if (it == 20) 3.0 else 0.5 }
        val peaked = views.build(DemandLayers(population = populations, diurnal = diurnal))
        assertFalse(flat.peak.profiled)
        assertTrue(peaked.peak.profiled)
        assertEquals(20, peaked.peak.hour)
        assertTrue(peaked.peak.msgsPerS > flat.peak.msgsPerS)
    }

    @Test
    fun `профиль неверной длины отклоняется, а не дополняется`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            views.build(DemandLayers(population = populations, diurnal = List(12) { 1.0 }))
        }
        assertTrue(e.message!!.contains("24 значениями"))
    }

    @Test
    fun `версия карты меняется вместе с содержимым`() {
        val a = views.build(DemandLayers(population = populations))
        val b = views.build(DemandLayers(population = populations.dropLast(1)))
        assertTrue(a.version != b.version, "версия не отличила разные карты")
    }

    @Test
    fun `вклад популяции выражен долей суммарного спроса`() {
        val view = views.build(DemandLayers(population = populations))
        assertEquals(1.0, view.contributions.sumOf { it.share }, 1e-3)
        // самая населённая популяция средних широт даёт больший вклад, чем полярная
        assertEquals("p45", view.contributions.first().id)
    }
}

class SpacecraftViewsTest {

    private val mapper = ObjectMapper()
    private val views = SpacecraftViews()

    private val massItems = listOf(
        MassItem("Конструкция", 8.0, Maturity.Existing),
        MassItem("СЭП", 6.0, Maturity.Modified),
        MassItem("ПН", 9.0, Maturity.New),
    )

    private fun model(
        beacon: Boolean = true,
        txPowerW: Double = 2.0,
        gainDbi: Double = 6.0,
    ) = mapper.readTree(
        """{"id":"SC-0001","preset":"cubesat_16u",
            "platform":{"dry_mass_kg":30,
              "power":{"sa_area_m2":0.18,"sa_efficiency":0.29,"battery_wh":120},
              "attitude":{"pointing_accuracy_deg":1}},
            "payload":{"architecture":"regenerative",
              "links":[{"id":"RL-DN","role":"user_downlink","band_hz":868000000,
                "tx_power_w":$txPowerW,"g_over_t_db_k":-22,"required_margin_db":3,
                "antenna":{"type":"patch","gain_dbi":$gainDbi}}],
              "onboard":{"buffer_mb":64}
              ${if (beacon) ""","ephemeris_beacon":{"enabled":true,"period_s":60,"format":"orbit_model"}""" else ""}}}"""
    )

    @Test
    fun `резерв по зрелости применяется поэлементно, а не общей надбавкой`() {
        val view = views.build(model(), massItems)
        // 8·1.05 + 6·1.15 + 9·1.25 = 26.55; с системным резервом 10 % — 29.205,
        // на экране — округлённые до четырёх значащих 29.21
        assertEquals(29.21, view.mass.dryMassKg, 1e-9)
        assertEquals(23.0, view.mass.nominalKg, 1e-9)
        assertEquals(
            listOf(5.0, 15.0, 25.0),
            view.mass.items.map { it.marginPct },
        )
    }

    @Test
    fun `масса вне диапазона платформы помечается со ссылкой на ADR`() {
        val heavy = massItems + MassItem("Балласт", 90.0, Maturity.Existing)
        val view = views.build(model(), heavy)
        assertFalse(view.mass.withinPlatformRange)
        assertTrue(view.issues.any { it.contains("ADR-002") })
    }

    @Test
    fun `энергия маяка входит в баланс слагаемым`() {
        val withBeacon = views.build(model(beacon = true), massItems)
        val withoutBeacon = views.build(model(beacon = false), massItems)
        assertNotNull(withBeacon.beacon)
        assertNull(withoutBeacon.beacon)
        assertTrue(withBeacon.power.beaconWh > 0.0)
        // маяк отбирает энергию: допустимая скважность ПН с ним СТРОГО меньше
        assertTrue(
            withBeacon.power.allowedPayloadDuty < withoutBeacon.power.allowedPayloadDuty,
            "маяк не изменил допустимую скважность — значит, в циклограмму он не вошёл",
        )
    }

    /**
     * Баланс при ДОПУСТИМОЙ скважности равен нулю по построению: допустимая —
     * это ровно та, при которой генерация равна потреблению. Баланс обязан
     * считаться при заявленной, иначе он не может ни о чём предупредить.
     */
    @Test
    fun `баланс считается при заявленной скважности, а не при допустимой`() {
        val view = views.build(model(), massItems, SpacecraftConditions(plannedPayloadDuty = 0.2))
        assertEquals(0.2, view.power.plannedPayloadDuty)
        assertTrue(view.power.allowedPayloadDuty > 0.2)
        assertTrue(view.power.balanceWh > 0.0, "запас энергии при малой скважности не показан")
        assertTrue(view.power.balanceOk && view.power.dutyOk)
    }

    @Test
    fun `скважность выше допустимой названа нарушением, а не молча урезана`() {
        val view = views.build(model(), massItems, SpacecraftConditions(plannedPayloadDuty = 0.99))
        assertFalse(view.power.dutyOk)
        assertTrue(view.power.balanceWh < 0.0)
        assertTrue(view.issues.any { it.contains("выше допустимой") })
        assertTrue(view.tpm.first { it.name == "Скважность ПН" }.breached)
    }

    @Test
    fun `отсутствие маяка названо нарушением Р5`() {
        val view = views.build(model(beacon = false), massItems)
        assertTrue(view.issues.any { it.contains("ADR-005") })
    }

    @Test
    fun `линия без энергетики не замыкается и названа так`() {
        val view = views.build(model(txPowerW = 0.001, gainDbi = -20.0), massItems)
        val link = view.links.single()
        assertFalse(link.closes)
        assertNull(link.serviceElevationDeg)
        assertTrue(view.issues.any { it.contains("не замыкается") })
    }

    @Test
    fun `запас в надире не меньше запаса на границе зоны`() {
        val link = views.build(model(), massItems).links.single()
        assertTrue(link.marginAtZenithDb >= link.marginAtMinElevDb)
    }

    @Test
    fun `TPM собирается из модели, а не заполняется вручную`() {
        val view = views.build(model(), massItems)
        assertEquals(
            listOf("Сухая масса", "Запас худшей линии", "Скважность ПН", "Глубина разряда АБ"),
            view.tpm.map { it.name },
        )
        // масса «чем меньше, тем лучше», запас линии — наоборот
        assertTrue(view.tpm.first { it.name == "Сухая масса" }.lowerIsBetter)
        assertFalse(view.tpm.first { it.name == "Запас худшей линии" }.lowerIsBetter)
    }

    @Test
    fun `превышение массы над целью помечается выходом за резерв`() {
        val ok = views.build(model(), massItems)
        assertFalse(ok.tpm.first { it.name == "Сухая масса" }.breached)
        val heavy = massItems + MassItem("Довесок", 20.0, Maturity.New)
        val view = views.build(model(), heavy)
        assertTrue(view.tpm.first { it.name == "Сухая масса" }.breached)
    }

    /**
     * Округление — последний шаг перед экраном. Признак «в диапазоне платформы»
     * считается по неокруглённой массе: 100.04 кг вне диапазона, и показанные
     * «100.0» не должны втягивать конфигурацию обратно в допуск.
     */
    @Test
    fun `округление не отменяет вердикт о выходе за диапазон`() {
        // 86.4·1.05·1.1 = 99.792 — в диапазоне; 86.5·1.05·1.1 = 99.9075 — тоже
        val edge = listOf(MassItem("Платформа", 86.62, Maturity.Existing))
        val view = views.build(model(), edge)
        // масса 100.04 кг: на экране 100.0, но вердикт — вне диапазона
        assertEquals(100.0, view.mass.dryMassKg, 1e-9)
        assertFalse(view.mass.withinPlatformRange)
        assertTrue(view.issues.any { it.contains("ADR-002") })
    }

    @Test
    fun `пресеты платформ приходят из конфигурации`() {
        val presets = views.presetRows()
        assertTrue(presets.size >= 4, "пресетов платформ меньше четырёх: ${presets.size}")
        assertTrue(presets.all { it.dryMassKg > 0 && it.batteryWh > 0 })
    }
}
