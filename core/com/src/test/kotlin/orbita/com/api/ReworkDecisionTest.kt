// Находка живого прохода ПМИ-3: внешний контур вернул восемь сервисов, все
// восемь сняты правилом основания («предложено 8 · показано 0 · снято 8»).
// Правило право: служба пометила придуманные целевые значения как manual, а
// от службы это не принимается. Но РЕШЕНИЕ ЧЕЛОВЕКА, которого правило
// требует, принять было негде — снятое висело счётчиком без единого действия.
//
// Здесь: инженер подписывает значения своим именем, и подпись ставится
// ТОЛЬКО там, где основания нет. Чужой источник своим именем не подписывают.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.CoreType
import orbita.mod.schema.SchemaRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReworkDecisionTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val server = HttpApi(boundary).start(0)
    private val base = "http://127.0.0.1:${server.address.port}/api"
    private val client = HttpClient.newHttpClient()

    @BeforeEach
    fun clean() {
        TestDb.truncateAll()
        boundary.ingest(
            CoreType.Project,
            """{"id":"PJ-1909","name":"Проход","phase":"pre_phase_a",
                "milestones":[{"gate":"MCR"}],"lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1909",
        )
        boundary.ingest(
            CoreType.Need,
            """{"id":"ND-1909","statement":"Перевозчику нужна телеметрия груза в пути",
                "stakeholder":{"name":"Перевозчик","role":"customer","priority":1},
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1909",
        )
    }

    /** Сервис со ДВУМЯ величинами: одна с настоящим основанием, вторая — нет. */
    private val предложение = """
        [{"id":"SV-1901","name":"Телеметрия грузов","traces_up":["ND-1909"],
          "qos_profiles":[{"consumer_class":"A_prime","moe":[
            {"id":"MOE-1901","name":"age_of_information",
             "target":{"value":30,"unit":"s","provenance":{"source":"imported",
               "import":{"dataset":"ПП №2216, п. 3","dataset_version":"1",
                         "retrieved_at":"2026-08-31","terms":"норматив"}}}},
            {"id":"MOE-1902","name":"delivery_probability_daily",
             "target":{"value":0.9,"unit":"1","provenance":{"source":"manual"}}}]}],
          "lifecycle":{"status":"Draft","version":"1"}}]
    """.trimIndent()

    private fun принять(автор: String): HttpResponse<String> = client.send(
        HttpRequest.newBuilder(URI.create("$base/ai/accept-rework?project=PJ-1909"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                """{"author":"$автор","items":$предложение}""", Charsets.UTF_8,
            )).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    @Test
    fun `решение инженера подписывает только величины без основания`() {
        val ответ = принять("Ведущий СИ")
        assertEquals(201, ответ.statusCode()) { ответ.body().take(300) }
        val записанный = boundary.objects.listCurrent("PJ-1909")
            .first { it.type == "service" }
        val moe = записанный.doc.path("qos_profiles")[0].path("moe")

        // величина с настоящим основанием не тронута: подписывать чужой
        // источник своим именем нельзя
        val сОснованием = moe[0].path("target").path("provenance")
        assertEquals("imported", сОснованием.path("source").asText())
        assertTrue("2216" in сОснованием.path("import").path("dataset").asText())

        // придуманная величина подписана: видно, кто отвечает
        val подписанная = moe[1].path("target").path("provenance")
        assertEquals("manual", подписанная.path("source").asText())
        assertEquals("Ведущий СИ", подписанная.path("author").asText()) {
            "«я так решил» обязано быть подписанным: $подписанная"
        }
        assertTrue(подписанная.path("timestamp").asText().isNotBlank()) { "и датированным" }
    }

    @Test
    fun `без автора решение не принимается`() {
        val ответ = client.send(
            HttpRequest.newBuilder(URI.create("$base/ai/accept-rework?project=PJ-1909"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                    """{"items":$предложение}""", Charsets.UTF_8,
                )).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertTrue(ответ.statusCode() >= 400) { "решение без автора — это ничьё решение" }
        assertTrue("author" in ответ.body()) { ответ.body().take(200) }
    }

    @Test
    fun `подписанное проходит правило основания`() {
        принять("Ведущий СИ")
        val записанный = boundary.objects.listCurrent("PJ-1909").first { it.type == "service" }
        assertTrue(orbita.req.sourceIssues(записанный.doc).isEmpty()) {
            "после подписи величин правилу основания придраться не к чему: " +
                orbita.req.sourceIssues(записанный.doc).toString()
        }
    }
}
