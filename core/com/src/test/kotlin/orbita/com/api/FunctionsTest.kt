// ADR-047 (шип 1b): функция — слой между нуждами и узлами состава. Функция
// следует из нужды/ConOps связями trace, распределяется на узел и станцию
// связями allocation; матрица «функции × узлы» показывает распределение и
// называет функции без носителя; граф видит функцию узлом.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.CoreType
import orbita.mod.schema.SchemaRegistry
import orbita.mod.store.ModelViolationException
import org.junit.jupiter.api.Assertions.assertEquals
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
class FunctionsTest {
    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val server = HttpApi(boundary).start(0)
    private val base = "http://127.0.0.1:${server.address.port}/api"
    private val client = HttpClient.newHttpClient()
    private val project = "PJ-2601"

    @BeforeAll
    fun setup() {
        TestDb.truncateAll()
        boundary.ingest(CoreType.Project, """{"id":"$project","name":"Функции","phase":"pre_phase_a","milestones":[{"gate":"MCR"}],"lifecycle":{"status":"Draft","version":"1"}}""", "test", project)
        boundary.ingest(CoreType.Need, """{"id":"ND-0001","statement":"Сбор телеметрии датчиков в районах без связи","stakeholder":{"name":"Оператор","role":"operator","priority":5},"lifecycle":{"status":"Draft","version":"1"}}""", "test", project)
        boundary.ingest(CoreType.Component, """{"id":"CM-0001","name":"Космический сегмент","kind":"segment","segment":"space","lifecycle":{"status":"Draft","version":"1"}}""", "test", project)
        boundary.ingest(CoreType.Component, """{"id":"CM-0002","name":"Полезная нагрузка","kind":"subsystem","parent":"CM-0001","profile":{"role":"payload"},"lifecycle":{"status":"Draft","version":"1"}}""", "test", project)
        boundary.ingest(CoreType.Component, """{"id":"CM-0003","name":"Станция приёма","kind":"element","lifecycle":{"status":"Draft","version":"1"}}""", "test", project)
        boundary.ingest(CoreType.Component, """{"id":"CM-0004","name":"Терминал","kind":"element","lifecycle":{"status":"Draft","version":"1"}}""", "test", project)
        boundary.ingest(
            CoreType.Function,
            """{"id":"FN-0001","name":"сбор телеметрии","statement":"Система собирает телеметрию датчиков терминалов.",
                "level":"system","traces_up":[{"ref":"ND-0001"}],
                "allocated_to":[{"component":"CM-0002","kind":"partial","rationale":"приём на борту"},{"component":"CM-0003","kind":"partial","rationale":"сброс на станцию"}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", project,
        )
        boundary.ingest(
            CoreType.Function,
            """{"id":"FN-0002","name":"передача команд","traces_up":[{"ref":"ND-0001"}],"lifecycle":{"status":"Draft","version":"1"}}""",
            "test", project,
        )
    }

    private fun get(path: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create("$base$path" + (if ("?" in path) "&" else "?") + "project=$project")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `функция следует из нужды и распределена на ПН и станцию связями`() {
        assertEquals(listOf("ND-0001"), boundary.links.linksTo("FN-0001", "trace").map { it.fromId })
        assertEquals(listOf("CM-0002", "CM-0003"), boundary.links.linksFrom("FN-0001", "allocation").map { it.toId }.sorted())
        val e = assertThrows(ModelViolationException::class.java) {
            boundary.ingest(CoreType.Function, """{"id":"FN-0009","name":"x","traces_up":[{"ref":"ND-0099"}],"lifecycle":{"status":"Draft","version":"1"}}""", "test", project)
        }
        assertTrue(e.message!!.contains("ND-0099"), e.message)
    }

    @Test
    fun `матрица функции x узлы показывает распределение и функции без носителя`() {
        val r = get("/reports/function-matrix")
        assertEquals(200, r.statusCode(), r.body())
        val m = mapper.readTree(r.body())
        val rows = m.path("rows").associateBy { it.path("function").asText() }
        assertEquals(listOf("CM-0002", "CM-0003"), rows.getValue("FN-0001").path("nodes").map { it.path("id").asText() })
        assertEquals("partial", rows.getValue("FN-0001").path("nodes")[0].path("kind").asText())
        assertEquals(listOf("FN-0002"), m.path("unallocated").map { it.asText() })
        // узлы состава без единой функции — тоже разрыв, столбцами матрицы
        assertTrue(m.path("nodes_without_functions").map { it.asText() }.containsAll(listOf("CM-0001", "CM-0004")), m.path("nodes_without_functions").toString())
        assertEquals(listOf("CM-0001", "CM-0002", "CM-0003", "CM-0004"), m.path("columns").map { it.path("id").asText() })
    }

    @Test
    fun `граф видит функцию узлом между нуждой и носителем`() {
        val g = mapper.readTree(get("/views/trace-graph?focus=FN-0001&depth=1").body())
        val kinds = g.path("nodes").associate { it.path("id").asText() to it.path("kind").asText() }
        assertEquals("function", kinds["FN-0001"])
        assertEquals("need", kinds["ND-0001"])
        assertEquals("node", kinds["CM-0002"])
        val g2 = mapper.readTree(get("/views/trace-graph?focus=CM-0002&depth=1").body())
        assertEquals(listOf("FN-0001"), g2.path("groups").path("functions").map { it.asText() })
        assertTrue(g2.path("functions_note").asText().isBlank() || !g2.has("functions_note"), "слой функций заведён — примечания об его отсутствии нет")
    }
}
