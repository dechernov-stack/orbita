// Круг 2 стартового потока: порядок дат вех — одно правило на сервере
// (создание и паспорт); файл исходного документа принимается с карточкой,
// текст извлекается сервером.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.schema.SchemaRegistry
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
class StartFlowRound2Test {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val server = HttpApi(boundary).start(0)
    private val base = "http://127.0.0.1:${server.address.port}/api"
    private val client = HttpClient.newHttpClient()

    @BeforeAll
    fun setup() {
        TestDb.truncateAll()
    }

    private fun post(path: String, body: ByteArray, contentType: String = "application/json; charset=utf-8"): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create("$base$path"))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `обратный порядок дат - отказ с именами точек, частичные даты законны`() {
        val bad = post(
            "/edit/project",
            """{"author":"т","doc":{"name":"Порядок дат","phase":"pre_phase_a",
                "milestones":[{"gate":"internal_review","due":"2027-03-15"},
                              {"gate":"MCR","due":"2027-01-20"},{"gate":"KDP-A"}]}}""".toByteArray(),
        )
        assertEquals(422, bad.statusCode()) { bad.body() }
        assertTrue("MCR не может быть раньше внутреннего обзора" in bad.body()) { bad.body() }

        // КТ-2 задана, КТ-1 нет — не ошибка; «дата не задана» — законно всегда
        val partial = post(
            "/edit/project",
            """{"author":"т","doc":{"name":"Частичные даты","phase":"pre_phase_a",
                "milestones":[{"gate":"internal_review"},{"gate":"MCR","due":"2027-01-20"},
                              {"gate":"KDP-A"}]}}""".toByteArray(),
        )
        assertEquals(201, partial.statusCode()) { partial.body() }

        // то же правило действует на паспорте (правка дат)
        val id = mapper.readTree(partial.body())["id"].asText()
        val cur = boundary.objects.current(id)!!
        val doc = cur.doc.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        (doc.path("milestones") as com.fasterxml.jackson.databind.node.ArrayNode).also { arr ->
            (arr[0] as com.fasterxml.jackson.databind.node.ObjectNode).put("due", "2027-06-01")
        }
        val edit = post(
            "/objects/$id/change?project=$id",
            mapper.writeValueAsBytes(
                mapper.createObjectNode().set<com.fasterxml.jackson.databind.node.ObjectNode>("doc", doc)
                    .put("change_ref", "правка дат").put("author", "т"),
            ),
        )
        assertEquals(422, edit.statusCode()) { edit.body() }
        assertTrue("MCR не может быть раньше внутреннего обзора" in edit.body())
    }

    @Test
    fun `файл принимается с карточкой, текст извлечён, файл отдается обратно`() {
        post(
            "/edit/project",
            """{"author":"т","doc":{"name":"Файлы","phase":"pre_phase_a",
                "milestones":[{"gate":"MCR"}]}}""".toByteArray(),
        ).also { assertEquals(201, it.statusCode()) { it.body() } }
        val pj = boundary.objects.listCurrent().first { it.type == "project" && it.doc.path("name").asText() == "Файлы" }.id

        val content = "Записка миссии: контроль перевозок опасных грузов, геопозиция раз в 30 с (ПП № 2216)."
        val up = post(
            "/sd-files?project=$pj&filename=записка.txt&name=Записка миссии&kind=mission_note&org=Минтранс&author=т"
                .replace(" ", "%20"),
            content.toByteArray(),
            "application/octet-stream",
        )
        assertEquals(201, up.statusCode()) { up.body() }
        val res = mapper.readTree(up.body())
        assertTrue(res["text_extracted"].asBoolean())
        val sdId = res["id"].asText()
        val card = boundary.objects.current(sdId)!!
        assertEquals("mission_note", card.doc.path("kind").asText())
        assertTrue("опасных грузов" in card.doc.path("text").asText())
        assertEquals("записка.txt", card.doc.path("file").path("name").asText())

        val back = client.send(
            HttpRequest.newBuilder(URI.create("$base/sd-files/$sdId")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, back.statusCode())
        assertTrue("опасных грузов" in back.body())
    }
}
