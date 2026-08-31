// Находка живого прохода ПМИ-3, второй круг: отказ пачки по-прежнему называл
// строку ПЕРЕБИТЫМ именем (SV-0113), хотя обратный адрес уже был написан.
//
// Причина: отказ записи приходит ИСКЛЮЧЕНИЕМ (пачка откатывается целиком), и
// обогащение отчёта, стоявшее на пути возврата значения, не выполнялось вовсе —
// отчёт уходил к общему обработчику как есть.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
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
class AcceptBatchSourceIdTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val server = HttpApi(boundary).start(0)
    private val base = "http://127.0.0.1:${server.address.port}/api"
    private val client = HttpClient.newHttpClient()

    @BeforeEach
    fun clean() {
        TestDb.truncateAll()
        // чужой проект: там живут и занятый id сервиса, и нужда, на которую
        // пакет будет ссылаться через границу
        boundary.ingest(
            CoreType.Project,
            """{"id":"PJ-1910","name":"Чужой","phase":"pre_phase_a","milestones":[{"gate":"MCR"}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1910",
        )
        boundary.ingest(
            CoreType.Need,
            """{"id":"ND-1910","statement":"Нужда чужого проекта в телеметрии",
                "stakeholder":{"name":"Оператор","role":"operator"},
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1910",
        )
        boundary.ingest(
            CoreType.Service,
            """{"id":"SV-1910","name":"Занятый идентификатор","traces_up":["ND-1910"],
                "qos_profiles":[{"consumer_class":"A_prime","moe":[
                  {"id":"MOE-1910","name":"service_availability",
                   "target":{"value":0.9,"unit":"1","provenance":{"source":"manual"}}}]}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1910",
        )
        boundary.ingest(
            CoreType.Project,
            """{"id":"PJ-1911","name":"Свой","phase":"pre_phase_a","milestones":[{"gate":"MCR"}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1911",
        )
    }

    @Test
    fun `отказ называет строку тем именем, что стоит в пакете инженера`() {
        // id занят чужим проектом → перебивается; трасса ведёт за границу → отказ
        val items = """[{"id":"SV-1910","name":"Сервис из пакета","traces_up":["ND-1910"],
            "qos_profiles":[{"consumer_class":"A_prime","moe":[
              {"id":"MOE-1911","name":"service_availability",
               "target":{"value":0.95,"unit":"1","provenance":{"source":"manual"}}}]}],
            "lifecycle":{"status":"Draft","version":"1"}}]"""
        val response = client.send(
            HttpRequest.newBuilder(URI.create("$base/ai/accept-batch?project=PJ-1911"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                    """{"call":null,"llm":"проверка","by":"инженер","items":$items}""",
                ))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(422, response.statusCode()) { "пачка обязана быть отклонена: ${response.body()}" }
        val отчёт = mapper.readTree(response.body())
        val проблема = отчёт.path("problems")[0]
        assertTrue(проблема.path("id").asText() != "SV-1910") {
            "id в отказе — перебитый, иначе проверка бессмысленна: ${проблема.path("id").asText()}"
        }
        assertEquals("SV-1910", проблема.path("source_id").asText()) {
            "обратный адрес обязан быть: под этим именем строка стоит в списке инженера"
        }
    }
}
