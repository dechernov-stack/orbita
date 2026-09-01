// Живой проход: экран покрытия предлагал выбрать носителя из списка — а
// список пуст, потому что стейкхолдеров в проекте нет вовсе. Механика
// работала, пользы не было: выбирать не из чего.
//
// Имена при этом лежат в самих нуждах словами («Минтранс России»). Система
// заводит их объектами: ИМЯ — факт документа, РОЛЬ — решение инженера.
// Выдумывать роль система не вправе, а перепечатывать имена — незачем.
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
class CarriersFromNeedsTest {

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
            """{"id":"PJ-1950","name":"Носители","phase":"phase_a","milestones":[{"gate":"SRR"}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1950",
        )
        listOf(
            "ND-1950" to "Минтранс России",
            "ND-1951" to "Минтранс России",
            "ND-1952" to "АО «ГЛОНАСС»",
        ).forEach { (id, кто) ->
            boundary.ingest(
                CoreType.Need,
                """{"id":"$id","statement":"Нужда носителя $кто в передаче данных мониторинга",
                    "stakeholder":{"name":"$кто","role":"customer"},
                    "lifecycle":{"status":"Draft","version":"1"}}""",
                "test", "PJ-1950",
            )
        }
    }

    private fun post(path: String, body: String): HttpResponse<String> = client.send(
        HttpRequest.newBuilder(URI.create("$base$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    @Test
    fun `носители заводятся из имён, названных в нуждах, и связывают свои нужды`() {
        val response = post(
            "/views/stakeholders/from-needs?project=PJ-1950",
            """{"author":"инженер","carriers":[
                 {"name":"Минтранс России","role":"customer"},
                 {"name":"АО «ГЛОНАСС»","role":"operator"}]}""",
        )
        assertEquals(201, response.statusCode()) { response.body() }
        val отчёт = mapper.readTree(response.body())
        assertEquals(2, отчёт.path("count").asInt())
        val минтранс = отчёт.path("created").first { it.path("name").asText() == "Минтранс России" }
        assertEquals(2, минтранс.path("needs").asInt()) { "обе нужды этого носителя связаны разом" }

        // матрица перестала быть пустой: носители названы, нужды в их строках
        val матрица = StakeholderCoverage.toJson(boundary, "PJ-1950")
        assertEquals(2, матрица.path("stakeholders").asInt())
        assertEquals(0, матрица.path("without_stakeholder").size()) { "нужд без носителя не осталось" }
    }

    @Test
    fun `роль не выдумывается — без роли носитель не заводится`() {
        val response = post(
            "/views/stakeholders/from-needs?project=PJ-1950",
            """{"author":"инженер","carriers":[{"name":"Минтранс России","role":""}]}""",
        )
        assertEquals(201, response.statusCode())
        assertEquals(0, mapper.readTree(response.body()).path("count").asInt()) {
            "имя — факт документа, роль — решение инженера; без решения объект не создаётся"
        }
        assertTrue(
            boundary.objects.listCurrent("PJ-1950").none { it.type == "stakeholder" },
        ) { "пустая роль не должна порождать стейкхолдера" }
    }

    /**
     * Живой проход: «ТЭК» обобщился дважды и лёг на полку двумя записями.
     * Шаблон один на класс миссии — дубли полки хуже её отсутствия, потому
     * что следующий проект берёт наугад один из двух.
     */
    @Test
    fun `повторное обобщение не плодит профиль на полке`() {
        post(
            "/views/stakeholders/from-needs?project=PJ-1950",
            """{"author":"инженер","carriers":[{"name":"Минтранс России","role":"customer"}]}""",
        )
        val sk = boundary.objects.listCurrent("PJ-1950").first { it.type == "stakeholder" }
        post(
            "/views/stakeholders/generalize-batch?project=PJ-1950",
            """{"author":"инженер","ids":["${sk.id}"]}""",
        )
        // второй раз — тем же именем: профиль обязан остаться один
        val ещё = boundary.editing.create(
            CoreType.Stakeholder,
            mapper.readTree("""{"name":"Минтранс России","role":"regulator"}"""),
            "инженер", "PJ-1950",
        )
        post(
            "/views/stakeholders/generalize-batch?project=PJ-1950",
            """{"author":"инженер","ids":["${ещё.id}"]}""",
        )
        val профили = boundary.objects
            .listCurrent(orbita.mod.store.ObjectStore.LIBRARY_PROJECT)
            .filter { it.type == "stakeholder_profile" && it.doc.path("name").asText() == "Минтранс России" }
        assertEquals(1, профили.size) { "на полке обязан остаться один шаблон: ${профили.map { it.id }}" }
    }
}

