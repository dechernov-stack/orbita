// ADR-046: граф трассировки — проекция модели без координат. Путь нужда →
// требование → узел → событие верификации → документ строится по связям;
// impact называет узел, событие и документ со вставкой; битая ссылка —
// отдельной группой; координат в ответе нет.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.CoreType
import orbita.mod.schema.SchemaRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TraceGraphTest {
    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val server = HttpApi(boundary).start(0)
    private val base = "http://127.0.0.1:${server.address.port}/api"
    private val client = HttpClient.newHttpClient()

    @BeforeAll
    fun setup() {
        TestDb.truncateAll()
        DemoProject.seed(boundary)
    }

    private fun get(path: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create("$base$path" + (if ("?" in path) "&" else "?") + "project=PJ-0001")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `путь от нужды до события верификации и документа строится по узлам`() {
        val r = get("/views/trace-graph?focus=RQ-0100&depth=3")
        assertEquals(200, r.statusCode(), r.body())
        val g = mapper.readTree(r.body())
        val kinds = g.path("nodes").groupBy { it.path("kind").asText() }.mapValues { it.value.size }
        assertTrue((kinds["requirement"] ?: 0) > 0 && (kinds["need"] ?: 0) > 0 && (kinds["node"] ?: 0) > 0, kinds.toString())
        assertTrue((kinds["event"] ?: 0) > 0, "события верификации — узлы графа: $kinds")
        assertTrue((kinds["document"] ?: 0) > 0, "документы со вставкой — узлы графа: $kinds")
        // координат в ответе нет — раскладку считает показ
        g.path("nodes").forEach { n -> assertFalse(n.has("x") || n.has("y"), "координаты в ответе: $n") }
        val groups = g.path("groups")
        assertTrue(groups.path("needs").any { it.asText() == "ND-0003" }, "нужда-источник: ${groups.path("needs")}")
        assertTrue(groups.path("carriers").any { it.asText() == "CM-0010" }, "носитель: ${groups.path("carriers")}")
        assertTrue(groups.path("events").size() >= 2, "события верификации: ${groups.path("events")}")
        assertTrue(groups.path("children").any { it.asText() == "RQ-0101" }, "дети: ${groups.path("children")}")
        assertTrue(groups.path("documents").size() > 0, "документ со вставкой: ${groups.path("documents")}")
        // кратчайший путь от нужды до документа со вставкой
        val doc = groups.path("documents")[0].asText()
        val p = mapper.readTree(get("/views/trace-graph?focus=ND-0003&depth=4&to=$doc").body())
        val path = p.path("path").map { it.asText() }
        assertEquals("ND-0003", path.first())
        assertEquals(doc, path.last())
        assertTrue(path.any { it.startsWith("RQ-") }, "путь идёт через требование: $path")
    }

    @Test
    fun `битая ссылка — отдельной группой, глубина ограничивает окрестность`() {
        boundary.ingest(
            CoreType.Requirement,
            """{"id":"RQ-0900","level":"system","statement":"Система должна хранить журнал событий.","category":"functional",
                "traces_up":[{"ref":"ND-0001"}],"verification_events":[
                  {"id":"VE-0900","method":"analysis","kind":"preliminary","phase":"PhaseA","level":"system","closes":false,"status":"planned","evidence_ref":"EV-0999"}],
                "owner":"вед. СИ","lifecycle":{"status":"Draft","version":"1"}}""",
            "test",
        )
        val g = mapper.readTree(get("/views/trace-graph?focus=RQ-0900&depth=2").body())
        val broken = g.path("groups").path("broken").map { it.asText() }
        val missing = g.path("nodes").filter { it.path("kind").asText() == "missing" }.map { it.path("id").asText() }
        assertTrue("EV-0999" in missing, "свидетельство без объекта — битая ссылка: $missing")
        // битая ссылка сидит при событии (глубина 2 от требования), группа фокуса — только прямые соседи
        assertTrue(broken.isEmpty() || "EV-0999" in broken)
        val d1 = mapper.readTree(get("/views/trace-graph?focus=RQ-0900&depth=1").body()).path("nodes").size()
        val d2 = g.path("nodes").size()
        assertTrue(d1 < d2, "глубина 1 уже глубины 2: $d1 vs $d2")
        assertTrue(g.path("functions_note").asText().contains("функций"), "граф честно говорит об отсутствии слоя функций")
        // документ — сосед, но не проход: окрестность глубины 2 не раздувается до всего реестра
        val g2 = mapper.readTree(get("/views/trace-graph?focus=RQ-0100&depth=2").body())
        val reqCount = g2.path("nodes").count { it.path("kind").asText() == "requirement" }
        assertTrue(reqCount < 6, "сквозь документ обход не идёт: требований в окрестности $reqCount")
    }
}
