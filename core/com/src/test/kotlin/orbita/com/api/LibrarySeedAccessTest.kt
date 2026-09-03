// Наполнение полки при многопроектном портфеле (ADR-053). Второй проект в
// портфеле однажды сломал сид полок: отказ «в портфеле N проектов — укажите
// ?project» приходил ДО маршрута, из проверки прав, хотя полка живёт в области
// LIB и проекта не спрашивает вовсе. Класс держит свой портфель из двух
// проектов, поэтому живёт отдельно от общего набора HTTP-проверок.
package orbita.com.api

import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.CoreType
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
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation::class)
class LibrarySeedAccessTest {

    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val server = HttpApi(boundary).start(0)
    private val base = "http://127.0.0.1:${server.address.port}/api"
    private val client = HttpClient.newHttpClient()

    @BeforeAll
    fun setup() {
        TestDb.truncateAll()
        listOf("PJ-0001" to "Рабочий проект", "PJ-0002" to "Пример · полки Arcadia").forEach { (id, name) ->
            boundary.ingest(
                CoreType.Project,
                """{"id":"$id","name":"$name","phase":"pre_phase_a",
                    "milestones":[{"gate":"MCR"}],
                    "lifecycle":{"status":"Draft","version":"1"}}""",
            )
        }
    }

    @AfterAll
    fun stop() = server.stop(0)

    @Test
    @org.junit.jupiter.api.Order(2)
    fun `роль вне проекта берётся сильнейшая из имеющихся - иначе полка закрыта своему же руководителю`() {
        // Роли живут по проектам, у области LIB ролей нет. Учётка с ролью
        // руководителя в PJ-0001 правит полку: спрашивать её роль «в никаком
        // проекте» значило бы отказывать владельцу на его же полке.
        boundary.auth.createUser("chernov", "парольчернов", "Чернов Д.")
        boundary.auth.setRole("PJ-0001", "chernov", "lead")
        assertEquals(null, boundary.auth.roleIn(null, "chernov"))
        assertEquals("lead", boundary.auth.rolesOf("chernov")["PJ-0001"])
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    fun `полка наполняется без указания проекта, а маршрут проекта говорит о нём сам`() {
        val ответ = client.send(
            HttpRequest.newBuilder(URI.create("$base/library/objects"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """{"type":"glossary","author":"сид",
                            "doc":{"id":"GL-9101","name":"Проверочный глоссарий",
                                   "entries":[{"term":"стык","brief":"ребро дерева состава между двумя узлами"}],
                                   "lifecycle":{"status":"Draft","version":"1"}}}""",
                    ),
                ).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(201, ответ.statusCode(), ответ.body())

        val нужен = client.send(
            HttpRequest.newBuilder(URI.create("$base/views/architecture")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertTrue(нужен.body().contains("укажите ?project"), нужен.body().take(200))
    }
}
