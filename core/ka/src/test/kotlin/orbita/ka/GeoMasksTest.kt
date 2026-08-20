// Режимы и географические маски (TZ-KA-009, Р4/ADR-004).
// Перенос эталона spec/spacecraft_semantics.py, раздел TZ-KA-009, один в один.
package orbita.ka

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GeoMasksTest {

    private val mapper = ObjectMapper()

    // ячейка спроса на 100° в.д. — заведомо дальше зоны сброса станции (~1660 км)
    private val demandMap = mapper.readTree(
        """{"cells":[
             {"cell_id":"c1","lat_deg":55.0,"lon_deg":37.0,"demand":[{"weight":0.6}]},
             {"cell_id":"c2","lat_deg":50.0,"lon_deg":100.0,"demand":[{"weight":0.4}]},
             {"cell_id":"c3","lat_deg":0.0,"lon_deg":-140.0,"demand":[{"weight":0.0}]}
           ]}""",
    )
    private val stations = mapper.readTree(
        """{"stations":[{"id":"GST-MSK","lat_deg":55.75,"lon_deg":37.62}]}""",
    )
    private val masks = buildMasks(demandMap, stations, altKm = 550.0)

    @Test
    fun `ячейка с нулевым весом в маску приёма не входит`() {
        assertEquals(2, masks.rxCells.size)
        assertTrue(masks.rxCells.none { it.lat == 0.0 && it.lon == -140.0 })
    }

    @Test
    fun `зоны станций образуют маску сброса`() =
        assertEquals(1, masks.downlinkCells.size)

    /** Перегенерация при изменении карты спроса — видна по версии маски. */
    @Test
    fun `изменение карты спроса перегенерирует маску`() {
        val smaller = mapper.readTree(
            """{"cells":[{"cell_id":"c1","lat_deg":55.0,"lon_deg":37.0,"demand":[{"weight":0.6}]}]}""",
        )
        assertTrue(buildMasks(smaller, stations, 550.0).version != masks.version)
    }

    @Test
    fun `изменение состава станций перегенерирует маску`() {
        val none = mapper.readTree("""{"stations":[]}""")
        assertTrue(buildMasks(demandMap, none, 550.0).version != masks.version)
    }

    @Test
    fun `над станцией — сброс, даже если есть спрос`() =
        assertEquals("downlink", classifyPoint(55.75, 37.62, masks))

    @Test
    fun `над спросом без станции — приём`() =
        assertEquals("rx", classifyPoint(50.0, 100.0, masks))

    @Test
    fun `над пустым океаном — дежурство`() =
        assertEquals("standby", classifyPoint(-10.0, -140.0, masks))

    private val track = listOf(
        MaskPoint(55.75, 37.62), MaskPoint(50.0, 100.0),
        MaskPoint(-10.0, -140.0), MaskPoint(-40.0, 170.0),
    )

    @Test
    fun `доли витка в сумме дают единицу`() =
        assertEquals(1.0, modeFractions(track, masks).values.sum(), 1e-12)

    @Test
    fun `доли отражают классификацию точек`() =
        assertEquals(
            mapOf("standby" to 0.5, "rx" to 0.25, "downlink" to 0.25),
            modeFractions(track, masks),
        )

    @Test
    fun `заглушка планировщика даёт явную ошибку`() {
        val e = assertThrows<NotImplementedError> { DynamicScheduler.schedule("SC-0001") }
        assertTrue("ADR-004" in e.message!!)
    }

    @Nested
    @DisplayName("Замена источника расписания не меняет потребителей")
    inner class SameShape {

        private val powers = mapOf("standby" to 6.0, "rx" to 9.0, "downlink" to 14.0)

        /**
         * Сгенерированные маской слоты — тот же тип и то же место, что ручные
         * из modes[].orbit_fraction: энергетика PowerModel принимает оба
         * источника без единого изменения. Это и есть «модуль потоков
         * не меняется»: потребители расписания не отличают источник.
         */
        @Test
        fun `циклограмма из масок ложится в ту же энергомодель, что ручная`() {
            val fromMasks = maskSchedule(track, masks, powers)
            val handWritten = listOf(
                ModeSlot("standby", 0.55, 6.0), ModeSlot("rx", 0.3, 9.0), ModeSlot("downlink", 0.15, 14.0),
            )
            val presets = PlatformPresets()
            val a = presets.byId("cubesat_16u").powerModel(fromMasks)
            val b = presets.byId("cubesat_16u").powerModel(handWritten)
            // обе циклограммы считаются одним и тем же путём — до чисел
            assertTrue(a.consumedWh(550.0, beaconWh = 0.5) > 0)
            assertTrue(b.consumedWh(550.0, beaconWh = 0.5) > 0)
            assertEquals(a.modes.map { it.name }.toSet(), b.modes.map { it.name }.toSet())
            assertEquals(1.0, fromMasks.sumOf { it.fraction }, 1e-12)
        }

        @Test
        fun `режим вне модели аппарата — отказ, а не ноль потребления`() {
            val e = assertThrows<IllegalArgumentException> {
                maskSchedule(track, masks, mapOf("standby" to 6.0))
            }
            assertTrue("не описан в модели аппарата" in e.message!!)
        }
    }

    @Test
    fun `пустая трасса — отказ, а не нули`() {
        assertThrows<IllegalArgumentException> { modeFractions(emptyList(), masks) }
    }
}
