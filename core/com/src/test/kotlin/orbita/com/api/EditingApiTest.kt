// Рабочий слой по HTTP (шаг 15 §1): ввод, правка, отмена и история.
//
// База ПУСТА: seedDemo здесь намеренно не вызывается — проверяется тот самый
// путь, которым инженер начинает проект с нуля.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.schema.SchemaRegistry
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EditingApiTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val server = HttpApi(boundary).start(0)
    private val base = "http://127.0.0.1:${server.address.port}/api"
    private val client = HttpClient.newHttpClient()

    @BeforeEach
    fun clean() {
        TestDb.truncateAll()
        // ADR-022: рабочий слой пишет в проект; пустой проект — не пустой портфель
        boundary.ingest(
            orbita.mod.model.CoreType.Project,
            """{"id":"PJ-0001","name":"Тестовый проект","phase":"phase_a",
                "milestones":[{"gate":"SRR"},{"gate":"SDR"}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
        )
    }

    @AfterAll
    fun stop() = server.stop(0)

    private fun send(method: String, path: String, body: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("$base$path"))
            .header("Content-Type", "application/json; charset=utf-8")
            .method(
                method,
                body?.let { HttpRequest.BodyPublishers.ofString(it, Charsets.UTF_8) }
                    ?: HttpRequest.BodyPublishers.noBody(),
            ).build()
        return client.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
    }

    /**
     * Автор идёт ТЕЛОМ, а не заголовком: имена инженеров русские, а значение
     * заголовка HTTP обязано быть ASCII — клиент отказывается собрать такой
     * запрос ещё до отправки. Проверено здесь: первая версия теста падала
     * на `invalid header value: "инженер А"`.
     */
    private fun withAuthor(author: String, vararg fields: Pair<String, String>): String =
        (listOf("\"author\":\"$author\"") + fields.map { "\"${it.first}\":${it.second}" })
            .joinToString(",", "{", "}")

    private val needDoc = """
        {"statement":"Оператор должен получать телеметрию терминалов не реже раза в сутки.",
         "stakeholder":{"name":"Оператор сети","role":"operator","priority":2}}
    """.trimIndent()

    private fun createNeed(author: String = "инженер А") =
        send("POST", "/edit/need", withAuthor(author, "doc" to needDoc))

    @Test
    @DisplayName("шаг 15: нужда создаётся из пустого проекта и получает ND-0001")
    fun `создание из пустого проекта`() {
        val r = createNeed()
        assertEquals(201, r.statusCode()) { r.body() }
        val body = mapper.readTree(r.body())
        assertEquals("ND-0001", body.path("id").asText())
        assertEquals("Draft", body.path("status").asText())
        assertEquals("1", body.path("version").asText())
        assertEquals("manual", body.path("doc").path("provenance").path("source").asText())
        assertEquals("инженер А", body.path("doc").path("provenance").path("author").asText())
    }

    @Test
    @DisplayName("шаг 15: изменение без автора не принимается")
    fun `правка без автора отклонена`() {
        val r = send("POST", "/edit/need", """{"doc":$needDoc}""")
        assertEquals(400, r.statusCode()) { r.body() }
        assertTrue(r.body().contains("author")) { r.body() }
    }

    @Test
    @DisplayName("шаг 15: правка на устаревшей версии — отказ с чужим значением и автором")
    fun `конфликт версий по HTTP`() {
        val id = mapper.readTree(createNeed().body()).path("id").asText()

        val byA = withAuthor(
            "инженер А", "base_version" to "\"1\"",
            "changes" to """{"statement":"Формулировка, записанная инженером А."}""",
        )
        assertEquals(200, send("PATCH", "/edit/$id", byA).statusCode())

        val stale = withAuthor(
            "инженер Б", "base_version" to "\"1\"",
            "changes" to """{"statement":"Формулировка, записанная инженером Б."}""",
        )
        val r = send("PATCH", "/edit/$id", stale)
        assertEquals(409, r.statusCode()) { r.body() }
        val body = mapper.readTree(r.body())
        assertTrue(body.path("conflict").asBoolean()) { r.body() }
        assertEquals("1", body.path("your_base").asText())
        assertEquals("2", body.path("current_version").asText())
        assertEquals("инженер А", body.path("changed_by").asText())
        assertEquals(
            "Формулировка, записанная инженером А.",
            body.path("their_values").path("statement").asText(),
        ) { "инженеру показано чужое значение, а не только номер версии" }
    }

    @Test
    @DisplayName("шаг 15: история версий, отмена действия и отмена объекта")
    fun `история отмена действия и отмена объекта`() {
        val id = mapper.readTree(createNeed().body()).path("id").asText()
        send(
            "PATCH", "/edit/$id",
            withAuthor(
                "инженер А", "base_version" to "\"1\"",
                "changes" to """{"statement":"Формулировка после правки инженера А."}""",
            ),
        )

        val history = mapper.readTree(send("GET", "/edit/$id/history").body())
        assertEquals(2, history.size())
        assertEquals("инженер А", history[0].path("author").asText())
        assertTrue(history[1].path("current").asBoolean()) { "текущей помечена последняя версия" }

        val undone = send("POST", "/edit/$id/undo", withAuthor("инженер Б"))
        assertEquals(200, undone.statusCode()) { undone.body() }
        assertEquals(
            "Оператор должен получать телеметрию терминалов не реже раза в сутки.",
            mapper.readTree(undone.body()).path("doc").path("statement").asText(),
        ) { "восстановлено содержание предыдущей версии" }

        val cancelled = send("POST", "/edit/$id/cancel", withAuthor("инженер А"))
        assertEquals(200, cancelled.statusCode()) { cancelled.body() }
        assertEquals("Cancelled", mapper.readTree(cancelled.body()).path("status").asText())
        assertEquals("4", mapper.readTree(cancelled.body()).path("version").asText())
        // объект остаётся: на него могут ссылаться
        assertEquals(200, send("GET", "/objects/$id").statusCode())
    }

    @Test
    @DisplayName("шаг 15: отменять нечего — 409, а не молчаливое согласие")
    fun `отмена действия единственной версии`() {
        val id = mapper.readTree(createNeed().body()).path("id").asText()
        val r = send("POST", "/edit/$id/undo", withAuthor("инженер А"))
        assertEquals(409, r.statusCode()) { r.body() }
    }

    @Test
    @DisplayName("шаг 15: форма видит, что мешает базированию, до попытки перевода")
    fun `замечания к базированию`() {
        // требование не бывает сиротой (TZ-COM-002): сначала нужда, к ней трассировка
        val needId = mapper.readTree(createNeed().body()).path("id").asText()
        val draft = """
            {"level":"system","statement":"Аппарат ограничен по сухой массе.","category":"performance",
             "owner":"инженер А","traces_up":[{"ref":"$needId"}],
             "lifecycle":{"status":"Draft","version":"1"}}
        """.trimIndent()
        val created = send("POST", "/edit/requirement", withAuthor("инженер А", "doc" to draft))
        assertEquals(201, created.statusCode()) { created.body() }
        val id = mapper.readTree(created.body()).path("id").asText()

        val issues = mapper.readTree(send("GET", "/edit/$id/issues").body())
        assertTrue(!issues.path("can_baseline").asBoolean()) { issues.toString() }
        assertTrue(issues.path("issues").size() >= 2) { "причины названы поимённо: $issues" }
    }
}
