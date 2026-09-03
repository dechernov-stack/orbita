// ADR-048: внешний элемент модели — носитель наравне с узлами состава; связь
// требования с ним обязана нести обоснование; без адаптера обновление
// честно отказывает, fixture поднимает баннер; в модель система не пишет.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.CoreType
import orbita.mod.schema.SchemaRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CapellaStructuresTest {
    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val server = HttpApi(boundary).start(0)
    private val base = "http://127.0.0.1:${server.address.port}/api"
    private val client = HttpClient.newHttpClient()
    private val project = "PJ-2701"

    @BeforeAll
    fun setup() {
        TestDb.truncateAll()
        boundary.ingest(CoreType.Project, """{"id":"$project","name":"Внешняя модель","phase":"pre_phase_a","milestones":[{"gate":"SRR"}],"lifecycle":{"status":"Draft","version":"1"}}""", "test", project)
        boundary.ingest(CoreType.Need, """{"id":"ND-0001","statement":"Сбор телеметрии","stakeholder":{"name":"Оператор","role":"operator","priority":5},"lifecycle":{"status":"Draft","version":"1"}}""", "test", project)
        boundary.ingest(
            CoreType.ModelElement,
            """{"id":"ME-0001","source_tool":"capella","model_id":"fixture","uuid":"fixture-la-onboard-router","type":"LogicalComponent","layer":"LA",
                "name":"Бортовой маршрутизатор","fixture":true,"lifecycle":{"status":"Draft","version":"1"}}""",
            "test", project,
        )
        boundary.ingest(
            CoreType.Requirement,
            """{"id":"RQ-0001","level":"system","statement":"Система должна маршрутизировать сообщения на борту.","category":"functional",
                "traces_up":[{"ref":"ND-0001"}],"verification_events":[],"owner":"вед. СИ","lifecycle":{"status":"Draft","version":"1"}}""",
            "test", project,
        )
    }

    private fun get(path: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create("$base$path" + (if ("?" in path) "&" else "?") + "project=$project")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun post(path: String, body: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create("$base$path" + (if ("?" in path) "&" else "?") + "project=$project"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `связь с внешним элементом делает его носителем требования - без носителя закрывается`() {
        assertTrue(boundary.screens.requirementTree(project).rows.first { it.id == "RQ-0001" }.noCarrierGap)
        val refused = assertThrows(Exception::class.java) {
            boundary.ingest(CoreType.ArchLink, """{"id":"AR-0009","requirement":"RQ-0001","element":"ME-0001","relation":"satisfied_by","rationale":"","lifecycle":{"status":"Draft","version":"1"}}""", "test", project)
        }
        assertTrue((refused.message ?: "").contains("rationale") || (refused.message ?: "").contains("обоснован"), refused.message)
        boundary.ingest(
            CoreType.ArchLink,
            """{"id":"AR-0001","requirement":"RQ-0001","element":"ME-0001","relation":"satisfied_by","rationale":"маршрутизация реализована логическим компонентом модели","lifecycle":{"status":"Draft","version":"1"}}""",
            "test", project,
        )
        val link = boundary.links.linksFrom("RQ-0001", "allocation").single()
        assertEquals("ME-0001", link.toId)
        assertTrue(link.rationale!!.contains("satisfied_by"))
        val row = boundary.screens.requirementTree(project).rows.first { it.id == "RQ-0001" }
        assertFalse(row.noCarrierGap, "внешний элемент — носитель наравне с узлами")
        assertEquals(listOf("ME-0001"), row.allocatedTo)
        // матрица трассировки видит элемент столбцом «элементы»
        val m = mapper.readTree(get("/reports/trace-matrix").body())
        assertEquals(listOf("ME-0001"), m.path("rows")[0].path("elements").map { it.asText() })
    }

    @Test
    fun `граф видит внешний элемент узлом-ссылкой, impact называет его`() {
        val g = mapper.readTree(get("/views/trace-graph?focus=RQ-0001&depth=1").body())
        val kinds = g.path("nodes").associate { it.path("id").asText() to it.path("kind").asText() }
        assertEquals("external", kinds["ME-0001"])
        assertEquals(listOf("ME-0001"), g.path("groups").path("external").map { it.asText() })
    }

    @Test
    fun `без адаптера обновление отказывает, fixture поднимает баннер, в модель никто не пишет`() {
        val r = post("/library/capella/refresh", "{}")
        assertEquals(409, r.statusCode(), r.body())
        assertTrue(r.body().contains("fixture"), r.body())
        val view = mapper.readTree(get("/views/external-model").body())
        assertTrue(view.path("fixture_banner").asBoolean(), view.toString())
        assertEquals(1, view.path("elements").size())
        assertEquals("ME-0001", view.path("elements")[0].path("id").asText())
        assertEquals(1, view.path("links").size())
        assertFalse(view.path("adapter_enabled").asBoolean())
    }
}
