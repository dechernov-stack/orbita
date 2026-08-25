// Планирование длительностями (находка прогона: «ориентируемся на
// длительности этапов — двигать сроки адекватно»): дата вехи — якорная due
// либо цепочка prev + duration_days; сдвиг якоря пересчитывает хвост.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.schema.SchemaRegistry
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GatesPlanTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val server = HttpApi(boundary).start(0)
    private val base = "http://127.0.0.1:${server.address.port}/api"
    private val client = HttpClient.newHttpClient()

    @BeforeAll
    fun setup() {
        TestDb.truncateAll()
        boundary.ingest(
            orbita.mod.model.CoreType.Project,
            """{"id":"PJ-1501","name":"План длительностями","phase":"phase_a",
                "milestones":[
                  {"gate":"SRR","due":"2026-10-02","held":true},
                  {"gate":"KDP-B","due":"2026-11-24"},
                  {"gate":"PDR","phase":"Phase B","duration_days":120},
                  {"gate":"CDR","phase":"Phase C","duration_days":200},
                  {"gate":"Launch","phase":"Phase D","due":"2028-06-01"}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1501",
        )
    }

    @AfterAll
    fun stop() = server.stop(0)

    private fun gates(): com.fasterxml.jackson.databind.JsonNode =
        mapper.readTree(
            client.send(
                HttpRequest.newBuilder(URI.create("$base/views/gates?project=PJ-1501")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            ).body(),
        )["gates"]

    @Test
    fun `дата вехи выводится цепочкой от якоря, якорь сильнее расчёта`() {
        val g = gates().associateBy { it["gate"].asText() }
        // PDR: якоря нет — KDP-B (якорь 2026-11-24) + 120 дн.
        assertEquals("2027-03-24", g["PDR"]!!["due"].asText())
        assertTrue(g["PDR"]!!["computed"].asBoolean()) { g["PDR"].toString() }
        assertEquals(120, g["PDR"]!!["days_from_prev"].asInt())
        // CDR: от расчётной PDR + 200 дн.
        assertEquals("2027-10-10", g["CDR"]!!["due"].asText())
        assertTrue(g["CDR"]!!["computed"].asBoolean())
        // Launch: явная due — якорь, расчёт её не перебивает
        assertEquals("2028-06-01", g["Launch"]!!["due"].asText())
        assertTrue(!g["Launch"]!!.has("computed"))
        // дальние вехи вне горизонта ворот
        assertTrue(!g["PDR"]!!["in_scope"].asBoolean())
        assertTrue(g["KDP-B"]!!["in_scope"].asBoolean())
    }

    @Test
    fun `сдвиг якоря пересчитывает хвост`() {
        // KDP-B сдвинулась на месяц — PDR и CDR уехали следом, Launch (якорь) нет
        val cur = boundary.objects.current("PJ-1501")!!
        val doc = cur.doc.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        (doc.path("milestones")[1] as com.fasterxml.jackson.databind.node.ObjectNode)
            .put("due", "2026-12-24")
        boundary.objects.change("PJ-1501", doc, createdBy = "test")
        val g = gates().associateBy { it["gate"].asText() }
        assertEquals("2027-04-23", g["PDR"]!!["due"].asText())
        assertEquals("2027-11-09", g["CDR"]!!["due"].asText())
        assertEquals("2028-06-01", g["Launch"]!!["due"].asText())
    }
}
