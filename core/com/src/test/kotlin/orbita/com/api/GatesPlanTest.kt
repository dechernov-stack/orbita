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
    fun `источник сроков - плановые даты вех, длительности дат не выводят`() {
        // Ответ по О-10 §2: длительность — производная (интервал), отдельно
        // не хранится и дат не выводит; расчётные сроки работ живут в WBS
        // и встретятся с датами вех в О-20 разрывом, не полем ввода
        val g = gates().associateBy { it["gate"].asText() }
        // PDR: явной даты нет — даты НЕТ (цепочка длительностей умерла)
        assertTrue(g["PDR"]!!["due"].isNull) { g["PDR"].toString() }
        assertTrue(!g["PDR"]!!.has("computed"))
        // Launch: явная due — единственный источник
        assertEquals("2028-06-01", g["Launch"]!!["due"].asText())
        // интервал читается между ЯВНЫМИ датами
        assertTrue(g["Launch"]!!["days_from_prev"].isInt) { g["Launch"].toString() }
        // дальние вехи вне горизонта ворот
        assertTrue(!g["PDR"]!!["in_scope"].asBoolean())
        assertTrue(g["KDP-B"]!!["in_scope"].asBoolean())
    }
}
