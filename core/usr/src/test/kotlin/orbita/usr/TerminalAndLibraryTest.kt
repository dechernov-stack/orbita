// Профили терминалов, популяции и библиотека сценариев
// (TZ-USR-001, TZ-USR-002, TZ-USR-003, TZ-USR-006).
package orbita.usr

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.RepoPaths
import orbita.mod.schema.SchemaRegistry
import orbita.mod.store.ModelViolationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TerminalAndLibraryTest {

    private val mapper = ObjectMapper()
    private val registry = SchemaRegistry(RepoPaths.schemasDir())
    private val rules = TerminalRules(registry)
    private val library = ScenarioLibrary()

    private fun profile(klass: String, mutate: (ObjectNode) -> Unit = {}): ObjectNode =
        (mapper.readTree(
            """{"id": "TP-0001", "consumer_class": "$klass",
                "radio": {"eirp_dbm": 14, "rx_sensitivity_dbm": -137},
                "generation": {"model": "periodic", "rate_per_day": 24, "payload_bytes": 24},
                "ephemeris": {"knows_ephemeris": true, "max_almanac_age_s": 86400, "degraded_rate_factor": 0.2}}"""
        ) as ObjectNode).also(mutate)

    @Test
    fun `профиль C-prime без времени реакции отклоняется`() {
        val errors = rules.validate(profile("C_prime"))
        assertTrue(errors.any { "required_reaction_time_s" in it.message }) { errors.toString() }
        val okErrors = rules.validate(profile("C_prime") {
            it.putObject("control_loop").put("required_reaction_time_s", 300).put("external_decision_time_s", 60)
        })
        assertEquals(emptyList<Any>(), okErrors)
    }

    @Test
    fun `профиль B-prime без политики повторов отклоняется`() {
        val errors = rules.validate(profile("B_prime"))
        assertTrue(errors.any { "reliability_policy" in it.message }) { errors.toString() }
        val okErrors = rules.validate(profile("B_prime") {
            it.putObject("reliability_policy").put("ack_required", true).put("backoff", "ephemeris_wait")
        })
        assertEquals(emptyList<Any>(), okErrors)
    }

    @Test
    fun `knows_ephemeris=false отклоняется со ссылкой на ADR-005`() {
        val errors = rules.validate(profile("A_prime") {
            (it.path("ephemeris") as ObjectNode).put("knows_ephemeris", false)
        })
        assertTrue(errors.any { it.adr?.startsWith("ADR-005") == true }) { errors.toString() }
    }

    @Test
    fun `возраст альманаха сверх допустимого включает деградированный режим`() {
        val p = profile("A_prime")
        assertEquals(1.0, rules.rateFactor(p, almanacAgeS = 3600.0))
        assertEquals(0.2, rules.rateFactor(p, almanacAgeS = 90000.0))
    }

    @Test
    fun `время решения внешней системы принимается как параметр`() {
        val p = profile("C_prime") {
            it.putObject("control_loop").put("required_reaction_time_s", 300).put("external_decision_time_s", 61.5)
        }
        assertEquals(61.5, rules.externalDecisionTimeS(p))
    }

    @Test
    fun `модель подвижности вне static-route отклоняется со ссылкой на ADR-007`() {
        val e = assertThrows<ModelViolationException> {
            Populations.parse(mapper.readTree("""{"consumer_class":"B_prime","count":100,"mobility":{"model":"roaming"}}"""))
        }
        assertTrue("ADR-007" in e.message!!)
        val route = Populations.parse(mapper.readTree(
            """{"consumer_class":"B_prime","count":100,
                "mobility":{"model":"route","route":{"points":[{"lat":55,"lon":37},{"lat":60,"lon":40}],"speed_mps":20}}}"""
        ))
        assertEquals(2, route.route!!.points.size)
        assertEquals(20.0, route.route!!.speedMps)
    }

    @Test
    fun `прогноз роста применяется к горизонту оценки`() =
        assertEquals(121.0, Populations.grownCount(100.0, 10.0, 2.0), 1e-9)

    @Test
    fun `библиотека содержит шесть референсных сценариев`() =
        assertEquals(
            listOf("agro_monitoring", "metering", "pipeline_monitoring",
                "transport_tracking", "maritime_vessels", "eco_monitoring"),
            library.scenarios.map { it.id },
        )

    @Test
    fun `сценарий разворачивается в популяцию с валидным профилем терминала`() {
        library.scenarios.forEach { s ->
            val errors = rules.validate(s.terminalProfile)
            assertEquals(emptyList<Any>(), errors) { "${s.id}: $errors" }
            assertTrue(s.seeds.isNotEmpty()) { s.id }
            assertTrue(s.seeds.all { it.klass == s.consumerClass && it.msgsPerTerminalDay == s.msgsPerTerminalDay })
        }
    }

    @Test
    fun `состав библиотеки фиксируется версией и входит в состав карты спроса`() {
        assertTrue(library.version.isNotBlank())
        val seeds = library.expandSeeds("maritime_vessels")
        val cells = DemandMapBuilder.build(emptyList(), scenarios = seeds)
        val doc = DemandMapBuilder.toContractJson(
            "DM-0001", cells, terminalsPerCapita = 0.02,
            scenarioLibraryIds = listOf("maritime_vessels"), libraryVersion = library.version,
        )
        // документ карты валиден по нормативной схеме contracts/demand-map
        assertEquals(emptyList<Any>(), registry.validate("contracts/demand-map", doc)) {
            registry.validate("contracts/demand-map", doc).toString()
        }
        // версия карты меняется вместе с версией библиотеки
        val v1 = DemandMapBuilder.version(cells, library.version)
        val v2 = DemandMapBuilder.version(cells, library.version + "x")
        assertTrue(v1 != v2)
    }

    // ---------- Полнота библиотеки (шаг 10.3) ----------

    @Test
    fun `каждый сценарий несёт основание оценки`() {
        library.scenarios.forEach { s ->
            assertTrue(s.source.length > 40) { "${s.id}: основание оценки пустое или формальное" }
        }
    }

    /**
     * Неполная запись ВЫЯВЛЯЕТСЯ, а не подставляет умолчание. Проверяется на
     * настоящем ресурсе с вырезанным полем: библиотека отказывается собраться
     * целиком, а не отдаёт пять сценариев из шести.
     */
    @Test
    fun `сценарий без основания оценки отвергается`() {
        val e = assertThrows<IllegalArgumentException> { ScenarioLibrary(broken(0) { it.put("source", "") }) }
        assertTrue("agro_monitoring" in e.message!! && "source" in e.message!!) { e.message!! }
    }

    @Test
    fun `сценарий с нулевым темпом сообщений отвергается`() {
        val e = assertThrows<IllegalArgumentException> {
            ScenarioLibrary(broken(0) { it.put("msgs_per_terminal_day", 0) })
        }
        assertTrue("msgs_per_terminal_day" in e.message!!) { e.message!! }
    }

    @Test
    fun `сценарий без опорных ячеек отвергается`() {
        val e = assertThrows<IllegalArgumentException> { ScenarioLibrary(broken(5) { it.putArray("seeds") }) }
        assertTrue("eco_monitoring" in e.message!! && "seeds" in e.message!!) { e.message!! }
    }

    /** Настоящий ресурс библиотеки с испорченной записью под номером [index]. */
    private fun broken(index: Int, mutate: (ObjectNode) -> Unit): String {
        val root = mapper.readTree(ScenarioLibrary.defaultJson())
        mutate(root.path("scenarios")[index] as ObjectNode)
        return root.toString()
    }
}
