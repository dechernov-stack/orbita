// Валидация на границе модуля (TZ-MOD-002): вход по схеме целевого типа,
// отклонение с путём/правилом/ADR; принятые объекты попадают в хранилище.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.CoreType
import orbita.mod.model.Lifecycle
import orbita.mod.schema.SchemaRegistry
import orbita.mod.schema.SchemaValidationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BoundaryTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)

    @BeforeAll
    fun reset() = TestDb.truncateAll()

    @Test
    fun `валидный сервис принимается и сохраняется текущей версией`() {
        val fixture = mapper.readTree(
            RepoPaths.repoRoot().resolve("spec/fixtures/example-valid.json").toFile()
        )["service"]
        val stored = boundary.ingest(CoreType.Service, mapper.writeValueAsString(fixture))
        assertEquals(Triple("SV-0001", "0.1", Lifecycle.Draft), Triple(stored.id, stored.version, stored.status))
        assertEquals("SV-0001", boundary.objects.current("SV-0001")?.id)
    }

    @Test
    fun `вход без обязательных полей отклоняется с путём и правилом`() {
        val e = assertThrows<SchemaValidationException> {
            boundary.ingest(CoreType.Service, """{"id":"SV-0009","name":"x"}""")
        }
        assertTrue(e.errors.isNotEmpty())
        assertTrue(e.errors.all { it.rule.isNotBlank() })
        assertTrue(e.errors.any { it.message.contains("qos_profiles") }) { e.errors.toString() }
    }

    @Test
    fun `контракт с нарушением Р1 отклоняется со ссылкой на ADR-001`() {
        val errors = boundary.validateContract(
            "contracts/spacecraft",
            """
            {"id": "KA-1",
             "platform": {"dry_mass_kg": 50,
               "power": {"sa_area_m2": 0.5, "sa_efficiency": 0.3, "battery_wh": 100},
               "attitude": {"pointing_accuracy_deg": 1.0}},
             "payload": {"architecture": "bent_pipe",
               "links": [{"id": "L1", "role": "user_uplink", "band_hz": 868.0e6, "tx_power_w": 2,
                          "antenna": {"type": "patch", "gain_dbi": 6}}],
               "onboard": {"buffer_mb": 64, "priority_policy": ["C_prime"]}}}
            """,
        )
        assertTrue(errors.any { it.adr?.startsWith("ADR-001") == true }) { errors.toString() }
    }
}
