// Канал промпт-пакетов целиком, без ключа (шаг 15 §3): сборка пакета → вставка
// ответа → структурный фильтр → diff → акцепт → ОБЪЕКТ В МОДЕЛИ.
//
// Последнее звено до шага 15 отсутствовало: маршрут акцепта возвращал
// размеченный объект и на этом заканчивался, экран рапортовал «принято полей»,
// а модель не менялась. Проверка ниже смотрит именно в хранилище — ответ
// маршрута сам по себе ничего не доказывает.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.schema.SchemaRegistry
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiLoopTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val server = HttpApi(boundary).start(0)
    private val base = "http://127.0.0.1:${server.address.port}/api"
    private val client = HttpClient.newHttpClient()

    @BeforeAll
    fun seed() {
        TestDb.truncateAll()
        DemoProject.seed(boundary)
    }

    @AfterAll
    fun stop() = server.stop(0)

    private fun post(path: String, body: String): HttpResponse<String> = client.send(
        HttpRequest.newBuilder(URI.create("$base$path"))
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8)).build(),
        HttpResponse.BodyHandlers.ofString(Charsets.UTF_8),
    )

    private val kind = "requirement_quality"
    private val context = """{"scope":"демо-проект «Орбита-IoT»"}"""
    private val task = "Проверь формулировки требований и предложи исправления."

    @Test
    @DisplayName("шаг 15 §3: канал промпт-пакетов проходится целиком и правит модель")
    fun `канал промпт-пакетов без ключа`() {
        // 1. пакет собирается — ключ для этого не нужен
        val pkg = post("/ai/packages", """{"kind":"$kind","context":$context,"task":"$task"}""")
        assertEquals(201, pkg.statusCode()) { pkg.body() }
        val pkgId = mapper.readTree(pkg.body()).path("id").asText()
        assertTrue(pkgId.isNotBlank()) { "у пакета есть идентификатор: ${pkg.body()}" }

        // 2. ответ «модели» вставляется обратно и разбирается локально
        val target = boundary.objects.current("RQ-0100")!!
        val improved = target.doc.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        // цель — требование с назначенной верификацией: без неё структурный фильтр
        // отбракует предложение сам, и до акцепта дело не дойдёт (проверено: RQ-0101
        // с пустым планом верификации ушёл в переделку с замечанием «метод не назначен»)
        improved.put(
            "statement",
            "Сухая масса космического аппарата не должна превышать 100 кг на момент выведения.",
        )
        val raw = mapper.writeValueAsString(mapper.createObjectNode().apply {
            set<com.fasterxml.jackson.databind.JsonNode>("items", mapper.createArrayNode().add(improved))
        })
        val answer = post(
            "/ai/answers",
            mapper.writeValueAsString(mapper.createObjectNode()
                .put("kind", kind).put("task", task).put("raw", raw)
                .set<com.fasterxml.jackson.databind.node.ObjectNode>("context", mapper.readTree(context))),
        )
        assertEquals(200, answer.statusCode()) { answer.body() }
        val report = mapper.readTree(answer.body())

        // 3. предложение прошло структурный фильтр и дошло до инженера
        val shown = report.path("shown")
        assertEquals(1, shown.size()) { "фильтр пропустил предложение: ${answer.body()}" }
        val entry = shown[0]

        // 4. diff показан и посчитан против конкретной версии
        assertTrue(entry.path("diff").has("statement")) { "diff называет изменённое поле: $entry" }
        assertEquals(target.version, entry.path("base_version").asText()) {
            "diff посчитан против версии, которую акцепт и проверит"
        }

        // 5. акцепт выбранного поля
        val accept = post(
            "/ai/accept",
            mapper.writeValueAsString(mapper.createObjectNode()
                .put("target_id", "RQ-0100")
                .put("package_id", pkgId)
                .put("llm", "внешний интерфейс")
                .put("by", "инженер А")
                .put("base_version", entry.path("base_version").asText())
                .set<com.fasterxml.jackson.databind.node.ObjectNode>(
                    "proposal", entry.path("item"),
                ).apply { putArray("selected").add("statement") }),
        )
        assertEquals(200, accept.statusCode()) { accept.body() }

        // 6. ГЛАВНОЕ: изменение действительно в модели, а не только в ответе
        val after = boundary.objects.current("RQ-0100")!!
        assertEquals(
            "Сухая масса космического аппарата не должна превышать 100 кг на момент выведения.",
            after.doc.path("statement").asText(),
        ) { "акцепт сохранён в модель" }
        assertNotEquals(target.version, after.version) { "акцепт создал новую версию" }
        assertEquals("инженер А", after.createdBy) { "автор изменения — акцептовавший инженер" }
        assertEquals("ai_proposed", after.doc.path("provenance").path("source").asText()) {
            "происхождение поля — предложение ИИ (TZ-AI-004)"
        }
        assertTrue(after.doc.path("provenance").path("ai").path("accepted").asBoolean()) {
            "предложение помечено акцептованным"
        }
        assertEquals("инженер А", after.doc.path("provenance").path("ai").path("accepted_by").asText())
    }

    @Test
    @DisplayName("шаг 15 §3: акцепт на устаревшей версии отклоняется, как и ручная правка")
    fun `акцепт подчиняется блокировке по версии`() {
        val before = boundary.objects.current("RQ-0102")!!
        val proposal = before.doc.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        proposal.put("statement", "Масса полезной нагрузки не должна превышать 30 кг по результатам взвешивания.")

        val r = post(
            "/ai/accept",
            mapper.writeValueAsString(mapper.createObjectNode()
                .put("target_id", "RQ-0102").put("package_id", "PKG-нет").put("llm", "внешний интерфейс")
                .put("by", "инженер Б")
                .put("base_version", "устаревшая")
                .set<com.fasterxml.jackson.databind.node.ObjectNode>("proposal", proposal)
                .apply { putArray("selected").add("statement") }),
        )
        assertEquals(409, r.statusCode()) { r.body() }
        assertTrue(mapper.readTree(r.body()).path("conflict").asBoolean()) { r.body() }
        assertEquals(before.version, boundary.objects.current("RQ-0102")!!.version) {
            "модель не тронута отклонённым акцептом"
        }
    }
}
