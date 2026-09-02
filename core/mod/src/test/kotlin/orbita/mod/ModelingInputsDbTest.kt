// Ограничения БД миграции V008 (CR-005/ADR-021) на РЕАЛЬНОЙ базе.
//
// Проверяется не то, что код умеет отказать, а то, что база не пропустит
// объект мимо кода. Ограничение в приложении обходится другим приложением;
// ограничение в схеме — нет.
package orbita.mod

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.model.Lifecycle
import orbita.mod.store.ModelViolationException
import orbita.mod.store.ObjectStore
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModelingInputsDbTest {

    private val mapper = ObjectMapper()
    private val objects = ObjectStore(TestDb.conn)

    @BeforeAll
    fun setup() = TestDb.truncateAll()

    private fun create(id: String, type: String, json: String) = objects.create(
        id = id,
        type = type,
        doc = mapper.readTree(json),
        status = Lifecycle.Draft,
        version = "1",
        createdBy = "test",
    )

    /** Полный сценарий: все пять ссылок типизированы, версии зафиксированы. */
    private fun scenarioJson(
        demandMapRef: String = "DM-0001",
        inputVersions: String = """{"CN-0001":"1","CU-0001":"1","DM-0001":"1","GS-0001":"1","PA-0001":"1"}""",
    ) = """{"id":"SC-0001","name":"проверка","constellation_ref":"CN-0001","carrier_ref":"CU-0001",
            "demand_map_ref":"$demandMapRef","ground_stations_ref":"GS-0001",
            "protocol_adapter_ref":"PA-0001","delivery_mode":"store_and_forward",
            "epoch":"2026-03-20T00:00:00Z","duration_s":86400,"rng_seed":42,
            "input_versions":$inputVersions}"""

    @Test
    fun `новые виды объектов принимаются базой`() {
        listOf(
            "CN-0100" to "constellation", "SP-0100" to "spacecraft", "DM-0100" to "demand_map",
            "TP-0100" to "terminal_profile", "GS-0100" to "ground_stations", "PA-0100" to "protocol_adapter",
        ).forEach { (id, type) -> create(id, type, """{"id":"$id"}""") }
    }

    /**
     * Ссылка сценария обязана быть типизированной. До CR-005 в поле карты
     * спроса стоял идентификатор компонента, и база это принимала.
     */
    @Test
    fun `сценарий с нетипизированной ссылкой отклоняется базой`() {
        val e = assertThrows<ModelViolationException> {
            create("SC-0900", "scenario", scenarioJson(demandMapRef = "CM-0010"))
        }
        assertTrue(e.message!!.contains("scenario_refs_typed"), e.message!!)
    }

    /** Без версий входов результат невоспроизводим (TZ-COM-006). */
    @Test
    fun `сценарий без версий входов отклоняется базой`() {
        val e = assertThrows<ModelViolationException> {
            create("SC-0901", "scenario", scenarioJson(inputVersions = "{}"))
        }
        assertTrue(e.message!!.contains("scenario_input_versions"), e.message!!)
    }

    /** ССО без LTAN: прецессия не определена (TZ-BAL-003). */
    @Test
    fun `ССО без LTAN отклоняется базой`() {
        val e = assertThrows<ModelViolationException> {
            create(
                "CN-0900", "constellation",
                """{"id":"CN-0900","kind":"walker_delta",
                    "walker":{"inclination_deg":97.8,"total":30,"planes":3,"phasing":1,
                              "altitude_km":600,"sso":true}}""",
            )
        }
        assertTrue(e.message!!.contains("constellation_sso_ltan"), e.message!!)
    }

    @Test
    fun `ССО с LTAN принимается`() {
        create(
            "CN-0901", "constellation",
            """{"id":"CN-0901","kind":"walker_delta",
                "walker":{"inclination_deg":97.8,"total":30,"planes":3,"phasing":1,
                          "altitude_km":600,"sso":true,"ltan_h":10.5}}""",
        )
    }

    @Test
    fun `идентификатор нового вида проходит шаблон, чужой префикс — нет`() {
        create("DM-0901", "demand_map", """{"id":"DM-0901"}""")
        assertThrows<ModelViolationException> { create("XX-0001", "demand_map", """{"id":"XX-0001"}""") }
    }
}
