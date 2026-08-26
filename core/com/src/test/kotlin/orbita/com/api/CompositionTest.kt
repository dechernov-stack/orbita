// В2.1–2.2: определение/вхождение, дерево с ацикличностью, свёртка бюджетов
// по кратности, WBS своей структурой со свёрткой стоимости и сроков.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.CoreType
import orbita.mod.schema.SchemaRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompositionTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val server = HttpApi(boundary).start(0)
    private val base = "http://127.0.0.1:${server.address.port}/api"
    private val client = HttpClient.newHttpClient()

    @BeforeAll
    fun setup() {
        TestDb.truncateAll()
        boundary.ingest(
            CoreType.Project,
            """{"id":"PJ-2301","name":"Состав","phase":"pre_phase_a",
                "milestones":[{"gate":"MCR"}],"lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2301",
        )
        // определения: КА (масса 80) и БЦВМ (масса 2)
        boundary.ingest(
            CoreType.Component,
            """{"id":"CM-0001","name":"КА","kind":"system",
                "parameters":[{"name":"mass","quantity":{"value":80,"unit":"kg",
                    "provenance":{"source":"manual","author":"t"}}}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2301",
        )
        boundary.ingest(
            CoreType.Component,
            """{"id":"CM-0002","name":"БЦВМ","kind":"subsystem",
                "parameters":[{"name":"mass","quantity":{"value":2,"unit":"kg",
                    "provenance":{"source":"manual","author":"t"}}}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2301",
        )
    }

    private fun get(path: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create("$base$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `дерево вхождений с кратностью и свёртка бюджетов`() {
        // группировка из 3 КА; в каждом КА — 2 БЦВМ (основная и резерв)
        boundary.ingest(
            CoreType.ComponentUsage,
            """{"id":"CU-0001","definition_ref":"CM-0001","quantity":3,"role":"КА группировки",
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2301",
        )
        boundary.ingest(
            CoreType.ComponentUsage,
            """{"id":"CU-0002","definition_ref":"CM-0002","parent_usage":"CU-0001",
                "quantity":2,"role":"основная и резерв",
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2301",
        )
        val budgets = mapper.readTree(get("/views/composition/budgets?project=PJ-2301").body())
        // КА: 80×3 = 240; БЦВМ: 2×(2×3) = 12; итого масса 252
        assertEquals(252.0, budgets["totals"]["mass"]["value"].asDouble(), 1e-9) { budgets.toString() }
        val bcvm = budgets["rows"].first { it["usage"].asText() == "CU-0002" }
        assertEquals(6, bcvm["multiplier"].asInt())
        assertEquals(12.0, bcvm["parameters"]["mass"]["value"].asDouble(), 1e-9)
    }

    @Test
    fun `цикл вхождений отклоняется, развитие определения даёт связь`() {
        // попытка сделать родителем собственного потомка — цикл
        val e = assertThrows<Exception> {
            boundary.editing.update(
                CoreType.ComponentUsage, "CU-0001",
                mapper.readTree("""{"parent_usage":"CU-0002"}"""),
                boundary.objects.current("CU-0001")!!.version, "test",
            )
        }
        assertTrue("cycle" in (e.message ?: "")) { e.message ?: "" }

        // развитие: v2 определения помнит v1
        boundary.ingest(
            CoreType.Component,
            """{"id":"CM-0003","name":"БЦВМ v2","kind":"subsystem","evolves_from":"CM-0002",
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2301",
        )
        boundary.req.syncLinks("component", "CM-0003",
            boundary.objects.current("CM-0003")!!.doc, "PJ-2301")
        val evolves = boundary.links.linksTo("CM-0003", "evolves")
        assertEquals("CM-0002", evolves.single().fromId)
    }

    @Test
    fun `WBS своей структурой - стоимость суммой, сроки максимумом`() {
        boundary.ingest(
            CoreType.WbsElement,
            """{"id":"WB-0001","name":"Программа","code":"1","lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2301",
        )
        boundary.ingest(
            CoreType.WbsElement,
            """{"id":"WB-0002","name":"Изготовление","code":"1.1","parent":"WB-0001",
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2301",
        )
        boundary.ingest(
            CoreType.WbsElement,
            """{"id":"WB-0003","name":"Испытания","code":"1.2","parent":"WB-0001",
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2301",
        )
        boundary.ingest(
            CoreType.CostEstimate,
            """{"id":"CE-0001","name":"Изготовление КА","kind":"rom","wbs_ref":"WB-0002",
                "total_low":{"value":100,"unit":"MUSD","provenance":{"source":"manual","author":"t"}},"total_high":{"value":160,"unit":"MUSD","provenance":{"source":"manual","author":"t"}},"schedule_months_low":10,"schedule_months_high":14,
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2301",
        )
        boundary.ingest(
            CoreType.CostEstimate,
            """{"id":"CE-0002","name":"Испытания","kind":"rom","wbs_ref":"WB-0003",
                "total_low":{"value":40,"unit":"MUSD","provenance":{"source":"manual","author":"t"}},"total_high":{"value":70,"unit":"MUSD","provenance":{"source":"manual","author":"t"}},"schedule_months_low":6,"schedule_months_high":9,
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2301",
        )
        val roll = mapper.readTree(get("/views/wbs/rollup?project=PJ-2301").body())
        val root = roll["elements"].first { it["id"].asText() == "WB-0001" }
        assertEquals(140.0, root["total_low"].asDouble(), 1e-9) { root.toString() }
        assertEquals(230.0, root["total_high"].asDouble(), 1e-9)
        // работы ветвей параллельны: срок корня — максимум, не сумма
        assertEquals(10.0, root["schedule_months_low"].asDouble(), 1e-9)
        assertEquals(14.0, root["schedule_months_high"].asDouble(), 1e-9)
    }
}
