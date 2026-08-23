// Затравка data/pilot (блок A, §3.6 задания): 41 требование миссии «Ротор-Л»
// грузится одним действием через POST /import/objects. Тест — потребитель
// файла: затравка, разошедшаяся со схемами, ломает сборку, а не замер
// плотности на приёмке.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.schema.SchemaRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PilotSeedTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val server = HttpApi(boundary).start(0)
    private val base = "http://127.0.0.1:${server.address.port}/api"
    private val client = HttpClient.newHttpClient()

    @BeforeAll
    fun load() {
        TestDb.truncateAll()
        val payload = Files.readString(RepoPaths.repoRoot().resolve("data/pilot/pilot.json"))
        val r: HttpResponse<String> = client.send(
            HttpRequest.newBuilder(URI.create("$base/import/objects"))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(payload, Charsets.UTF_8)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(201, r.statusCode()) { r.body() }
    }

    @AfterAll
    fun stop() = server.stop(0)

    @Test
    fun `затравка даёт 41 требование в проекте PJ-0100`() {
        val reqs = boundary.objects.listCurrent("PJ-0100").filter { it.type == "requirement" }
        assertEquals(41, reqs.size)
        assertTrue(reqs.all { it.projectId == "PJ-0100" })
    }

    @Test
    fun `нить трассировки затравки сшита - у требований есть источники и распределение`() {
        // затравка — материал замера плотности, но нить в ней настоящая
        assertEquals(emptyList<String>(), boundary.links.traceBreaks("PJ-0100"))
        val unallocated = boundary.links.unallocatedSystemRequirements("PJ-0100")
        assertEquals(emptyList<String>(), unallocated)
    }

    @Test
    fun `в затравке есть профили службы ИИ - порождающий и рецензионный`() {
        val profiles = boundary.objects.listCurrent("PJ-0100").filter { it.type == "ai_profile" }
        assertEquals(2, profiles.size)
        // порождающий профиль несёт запреты проекта и правило основания
        val generative = profiles.first { !it.doc.path("review_only").asBoolean(false) }
        assertTrue(generative.doc.path("prohibitions").any { "bent-pipe" in it.asText() })
        assertTrue(generative.doc.path("require_source").asBoolean())
        // рецензия — профиль службы, а не проверка фикстур скриптом
        assertTrue(profiles.any { it.doc.path("review_only").asBoolean(false) })
    }

    @Test
    fun `зрелость к SRR считается по затравке и называет разрывы`() {
        val report = boundary.maturity.build("SRR", projectId = "PJ-0100")
        // черновая затравка не готова к SRR — и отчёт обязан сказать, чем
        assertTrue(!report.ready())
        assertTrue(report.blockingReasons().isNotEmpty())
        // TRL-блокер из затравки виден поимённо
        assertTrue(report.gapsByType["technology"].orEmpty().any { it.id == "TL-0101" })
    }
}
