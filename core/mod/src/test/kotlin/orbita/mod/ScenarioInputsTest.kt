// Входы моделирования как хранимые объекты (CR-005…007, ADR-021).
//
// Перенос эталона spec/scenario_inputs_semantics.py один в один: 26 проверок.
// Расхождение реализации с эталоном — дефект реализации, а не эталона.
package orbita.mod

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.model.CoreType
import orbita.mod.model.MissingMelException
import orbita.mod.model.MissingOrbitFractionException
import orbita.mod.model.SCENARIO_REF_FIELDS
import orbita.mod.model.becomesStale
import orbita.mod.model.inputVersionsComplete
import orbita.mod.model.melBySubsystem
import orbita.mod.model.melDryMass
import orbita.mod.model.orbitEnergyBalance
import orbita.mod.model.resolveScenario
import orbita.mod.model.resultKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class ScenarioInputsTest {

    private val mapper = ObjectMapper()

    private val store: Map<String, CoreType> = mapOf(
        "CN-0001" to CoreType.Constellation,
        "SP-0001" to CoreType.Spacecraft,
        "DM-0001" to CoreType.DemandMap,
        "GS-0001" to CoreType.GroundStations,
        "PA-0001" to CoreType.ProtocolAdapter,
    )

    private val lookup: (String) -> CoreType? = { store[it] }

    private val scenario: ObjectNode = mapper.readTree(
        """{"id":"SC-0001","constellation_ref":"CN-0001","spacecraft_ref":"SP-0001",
            "demand_map_ref":"DM-0001","ground_stations_ref":"GS-0001",
            "protocol_adapter_ref":"PA-0001","rng_seed":42,
            "input_versions":{"CN-0001":"1","SP-0001":"2","DM-0001":"1",
                              "GS-0001":"1","PA-0001":"3"},
            "module_versions":{"ballistics":"0.4.0"}}"""
    ) as ObjectNode

    /** Копия сценария с изменённым полем: исходный документ не портится. */
    private fun withField(field: String, value: String?): JsonNode {
        val copy = scenario.deepCopy()
        if (value == null) copy.remove(field) else copy.put(field, value)
        return copy
    }

    // ---------- Разрешение ссылок сценария ----------

    @Test
    fun `полный сценарий разрешается`() =
        assertEquals(emptyList<String>(), resolveScenario(scenario, lookup))

    @Test
    fun `отсутствующий объект выявлен`() =
        assertTrue(resolveScenario(withField("demand_map_ref", "DM-9999"), lookup).any { "отсутствует" in it })

    @Test
    fun `ссылка неизвестного вида выявлена`() =
        assertTrue(
            resolveScenario(withField("spacecraft_ref", "XX-0001"), lookup)
                .any { "не соответствует" in it },
        )

    @Test
    fun `незаданная ссылка выявлена`() =
        assertTrue(resolveScenario(withField("ground_stations_ref", null), lookup).any { "не задана" in it })

    /**
     * ГЛАВНАЯ проверка CR-005: требуемый тип диктует ПОЛЕ, а не префикс.
     * Подстановка SP-0001 в поле карты спроса согласована сама с собой —
     * префикс и объект одного вида, — и проверка по префиксу её пропускает.
     */
    @Test
    fun `подмена объекта другого вида выявлена`() {
        val problems = resolveScenario(withField("demand_map_ref", "SP-0001"), lookup)
        assertTrue(problems.any { "ожидался" in it }, problems.toString())
    }

    @Test
    fun `объект с несоответствующим типом в хранилище выявлен`() {
        val wrong: (String) -> CoreType? = { id -> if (id == "DM-0001") CoreType.Component else store[id] }
        assertTrue(resolveScenario(scenario, wrong).any { "имеет тип" in it })
    }

    @Test
    fun `все пять входов проверяются`() = assertEquals(5, SCENARIO_REF_FIELDS.size)

    // ---------- Версии входов и воспроизводимость ----------

    @Test
    fun `версии зафиксированы для всех входов`() =
        assertEquals(emptyList<String>(), inputVersionsComplete(scenario))

    @Test
    fun `пропущенная версия выявлена`() {
        val copy = scenario.deepCopy()
        copy.putObject("input_versions").put("CN-0001", "1")
        assertTrue(inputVersionsComplete(copy).isNotEmpty())
    }

    @Test
    fun `ключ результата воспроизводим`() =
        assertEquals(resultKey(scenario), resultKey(scenario.deepCopy()))

    @Test
    fun `ключ включает зерно`() = assertTrue("seed=42" in resultKey(scenario))

    @Test
    fun `ключ включает версии модулей`() = assertTrue("ballistics=0.4.0" in resultKey(scenario))

    @Test
    fun `другое зерно даёт другой ключ`() {
        val copy = scenario.deepCopy().put("rng_seed", 43)
        assertTrue(resultKey(copy) != resultKey(scenario))
    }

    @Test
    fun `изменение версии входа обесценивает результат`() =
        assertTrue(becomesStale(scenario, "DM-0001", "2"))

    @Test
    fun `та же версия результат не обесценивает`() =
        assertFalse(becomesStale(scenario, "DM-0001", "1"))

    @Test
    fun `версия модуля входит в ключ`() {
        val copy = scenario.deepCopy()
        copy.putObject("module_versions").put("ballistics", "0.5.0")
        assertTrue(resultKey(copy) != resultKey(scenario))
    }

    // ---------- CR-006: ведомость масс ----------

    private val mel: List<JsonNode> = mapper.readTree(
        """[{"name":"Корпус","subsystem":"structure","mass_kg":8,"maturity":"existing"},
            {"name":"СЭП","subsystem":"power","mass_kg":6,"maturity":"modified"},
            {"name":"Приёмник","subsystem":"comms","mass_kg":2,"maturity":"new","quantity":2},
            {"name":"ПН","subsystem":"payload","mass_kg":10,"maturity":"new"}]"""
    ).toList()

    private fun items(json: String): List<JsonNode> = mapper.readTree(json).toList()

    @Test
    fun `масса считается по ведомости с резервами`() {
        val nominal = mel.sumOf { it.path("mass_kg").asDouble() }
        assertTrue(melDryMass(mel) > nominal)
    }

    @Test
    fun `кратность позиции учтена`() = assertEquals(4.0, melBySubsystem(mel)["comms"])

    @Test
    fun `новая позиция даёт больший резерв, чем существующая`() {
        val new = melDryMass(items("""[{"name":"x","subsystem":"power","mass_kg":10,"maturity":"new"}]"""))
        val old = melDryMass(items("""[{"name":"x","subsystem":"power","mass_kg":10,"maturity":"existing"}]"""))
        assertTrue(new > old)
    }

    @Test
    fun `разбивка по подсистемам собирается`() =
        assertEquals(setOf("structure", "power", "comms", "payload"), melBySubsystem(mel).keys)

    /** Молчаливый ноль выглядит как результат — поэтому его нет (CR-006). */
    @Test
    fun `отсутствие ведомости — ошибка, а не ноль`() {
        assertThrows(MissingMelException::class.java) { melDryMass(emptyList()) }
    }

    // ---------- CR-007: доля витка режима ----------

    private val modes: List<JsonNode> = mapper.readTree(
        """[{"name":"standby","power_w":12,"orbit_fraction":0.55},
            {"name":"rx","power_w":25,"orbit_fraction":0.30},
            {"name":"downlink","power_w":45,"orbit_fraction":0.15}]"""
    ).toList()

    @Test
    fun `баланс считается по заданным долям`() =
        assertTrue(orbitEnergyBalance(60.0, modes, 1.594).isFinite())

    /** Продолжение находки про баланс, равный нулю по построению. */
    @Test
    fun `баланс не равен нулю по построению`() =
        assertTrue(abs(orbitEnergyBalance(60.0, modes, 1.594)) > 1e-9)

    @Test
    fun `незаданная доля витка — ошибка, а не равномерное деление`() {
        assertThrows(MissingOrbitFractionException::class.java) {
            orbitEnergyBalance(60.0, items("""[{"name":"rx","power_w":25}]"""), 1.594)
        }
    }

    @Test
    fun `доли, не дающие единицу, отклонены`() {
        assertThrows(IllegalArgumentException::class.java) {
            orbitEnergyBalance(
                60.0,
                items(
                    """[{"name":"a","power_w":10,"orbit_fraction":0.4},
                        {"name":"b","power_w":10,"orbit_fraction":0.4}]"""
                ),
                1.594,
            )
        }
    }

    @Test
    fun `рост потребления снижает баланс`() {
        val greedy = items(
            """[{"name":"standby","power_w":30,"orbit_fraction":0.55},
                {"name":"rx","power_w":25,"orbit_fraction":0.30},
                {"name":"downlink","power_w":45,"orbit_fraction":0.15}]"""
        )
        assertTrue(orbitEnergyBalance(60.0, greedy, 1.594) < orbitEnergyBalance(60.0, modes, 1.594))
    }
}
