// Адаптер протокола, модель коллизий, регуляторные ограничения
// (TZ-NET-001, TZ-NET-002, TZ-NET-003, TZ-NET-004, TZ-NET-005).
package orbita.net

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.schema.SchemaRegistry
import orbita.mod.schema.ValidationError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.E
import kotlin.math.abs

class NetTest {

    private val mapper = ObjectMapper()
    private val registry = SchemaRegistry(RepoPaths.schemasDir())
    private val adapter = LoRaWanAdapter()

    @Test
    fun `документ адаптера валиден по нормативной схеме protocol-adapter`() {
        val errors = registry.validate("contracts/protocol-adapter", adapter.toContractJson(mapper))
        assertEquals(emptyList<ValidationError>(), errors)
    }

    @Test
    fun `требуемое Eb-N0 SF12 физично и совпадает с эталоном аппарата`() {
        // порог SNR −20 дБ в полосе 125 кГц при 250-293 бит/с даёт ≈ +6 дБ;
        // значение ниже предела Шеннона (−1,59 дБ) было бы нефизичным
        val ebn0 = adapter.mode("SF12").requiredEbn0Db
        assertTrue(ebn0 > -1.59) { "Eb/N0 $ebn0 дБ ниже предела Шеннона" }
        assertTrue(abs(ebn0 - 6.3) < 0.5) { "Eb/N0 $ebn0 дБ расходится с эталоном (+6,3)" }
    }

    @Test
    fun `набор режимов SF и LR-FHSS согласован`() {
        val sf7 = adapter.mode("SF7")
        val sf12 = adapter.mode("SF12")
        assertTrue(sf12.bitrateBps < sf7.bitrateBps) { "SF12 медленнее SF7" }
        // чувствительность растёт с SF: требуемая мощность сигнала (Eb/N0 + 10·lg R) у SF12 ниже,
        // хотя само Eb/N0 у CSS почти константа (~6 дБ)
        val requiredPowerDb = { m: PhyMode -> m.requiredEbn0Db + 10 * kotlin.math.log10(m.bitrateBps) }
        assertTrue(requiredPowerDb(sf12) < requiredPowerDb(sf7) - 10)
        assertTrue(adapter.modes.any { it.modeId.startsWith("LR-FHSS") })
        // время эфира: полезная нагрузка + оверхед MAC, делённые на скорость
        assertEquals((24 + 13) * 8.0 / sf12.bitrateBps, adapter.timeOnAirS("SF12", 24), 1e-12)
    }

    @Test
    fun `адаптер без нисходящего канала отклоняется как несовместимый с Р5-Р6`() {
        val doc = adapter.toContractJson(mapper)
        (doc.path("mac") as com.fasterxml.jackson.databind.node.ObjectNode).put("downlink_supported", false)
        val problems = validateAdapterContract(doc)
        assertTrue(problems.any { "ADR-005" in it && "ADR-006" in it }) { problems.toString() }
        assertEquals(emptyList<String>(), validateAdapterContract(adapter.toContractJson(mapper)))
    }

    @Test
    fun `калибровка против зафиксированных аналитических свойств ALOHA`() {
        val ref = mapper.readTree(
            RepoPaths.repoRoot().resolve("spec/reference/net_aloha_reference.json").toFile()
        )
        val tolPct = ref["max_deviation_pct"].asDouble() / 100.0
        // максимум чистой ALOHA при G=0.5 равен 1/(2e)
        val atHalf = pureAlohaThroughput(0.5)
        assertEquals(1.0 / (2.0 * E), atHalf, 1e-12)
        ref["cases"].forEach { c ->
            val expected = c["pure_aloha_throughput"].asDouble()
            val actual = pureAlohaThroughput(c["load"].asDouble())
            assertTrue(abs(actual - expected) <= tolPct * expected) { "load=${c["load"]}: $actual vs $expected" }
        }
        // предельные случаи и монотонность вероятности доставки
        assertEquals(1.0, deliveryProbability(0.0), 1e-12)
        assertTrue(deliveryProbability(5.0) < ref["properties"]["delivery_at_high_load_below"]["value"].asDouble())
        var prev = 1.0
        for (g in listOf(0.1, 0.3, 0.5, 1.0, 2.0)) {
            val p = deliveryProbability(g)
            assertTrue(p < prev) { "P(deliver) должна убывать с нагрузкой" }
            prev = p
        }
    }

    @Test
    fun `эффект захвата и квазиортогональность повышают доставку`() {
        val base = deliveryProbability(1.0)
        assertTrue(deliveryProbability(1.0, captureFraction = 0.3) > base)
        assertTrue(deliveryProbability(1.0, orthogonalModes = 6) > base)
    }

    @Test
    fun `регуляторные ограничения применяются к популяции`() {
        val eu = Regions.byName("EU868")
        // превышение duty cycle выявляется при сборке сценария
        val bad = validatePopulation(eu, terminals = 5000, msgsPerDay = 4.0, timeOnAirS = 0.4, eirpDbm = 14.0)
        assertTrue(bad.any { "duty cycle" in it }) { bad.toString() }
        val ok = validatePopulation(eu, terminals = 200, msgsPerDay = 4.0, timeOnAirS = 0.4, eirpDbm = 14.0)
        assertEquals(emptyList<String>(), ok)
        // предел мощности
        val hot = validatePopulation(eu, 100, 1.0, 0.1, eirpDbm = 30.0)
        assertTrue(hot.any { "EIRP" in it })
        // dwell time в US915
        val us = validatePopulation(Regions.byName("US915"), 100, 1.0, timeOnAirS = 1.2, eirpDbm = 20.0)
        assertTrue(us.any { "dwell" in it })
    }
}
