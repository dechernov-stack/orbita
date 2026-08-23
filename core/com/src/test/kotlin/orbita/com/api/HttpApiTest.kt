// HTTP API шага 1: валидация на входе (422 с path/rule/adr), процедура
// изменения Baseline (409 без основания), обходы трассировки за один запрос.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.Lifecycle
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
import java.time.OffsetDateTime

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpApiTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val server = HttpApi(boundary).start(0)
    private val base = "http://127.0.0.1:${server.address.port}/api"
    private val client = HttpClient.newHttpClient()

    @BeforeAll
    fun reset() {
        TestDb.truncateAll()
        // ADR-022: запись через API требует проекта-контейнера
        boundary.ingest(
            orbita.mod.model.CoreType.Project,
            """{"id":"PJ-0001","name":"Тестовый проект","phase":"phase_a",
                "milestones":[{"gate":"SRR"},{"gate":"SDR"}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
        )
    }

    @AfterAll
    fun stop() = server.stop(0)

    private fun post(path: String, body: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create("$base$path"))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun get(path: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create("$base$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `валидный объект создаётся и читается`() {
        val fixture = mapper.readTree(
            RepoPaths.repoRoot().resolve("spec/fixtures/example-valid.json").toFile()
        )["service"]
        val created = post("/objects/service", mapper.writeValueAsString(fixture))
        assertEquals(201, created.statusCode()) { created.body() }

        val got = get("/objects/SV-0001")
        assertEquals(200, got.statusCode())
        assertEquals("Сбор телеметрии датчиков", mapper.readTree(got.body())["doc"]["name"].asText())
    }

    @Test
    fun `невалидный вход отклоняется 422 с путём и правилом`() {
        val r = post("/objects/scenario", """{"id":"SC-0009","name":"нет зерна"}""")
        assertEquals(422, r.statusCode())
        val errors = mapper.readTree(r.body())["errors"]
        assertTrue(errors.size() > 0)
        assertTrue(errors.all { it.hasNonNull("path") && it.hasNonNull("rule") })
        assertTrue(errors.any { it["message"].asText().contains("rng_seed") }) { r.body() }
    }

    @Test
    fun `нарушение Р2 в контракте видно при расчёте со ссылкой на ADR-002`() {
        // Отдельного «проверить, не сохраняя» больше нет (Шаг 16 §2.1): расчёт по
        // ещё не сохранённой модели сам не считает по непрошедшему документу и
        // отвечает тем же перечнем ошибок.
        val r = post(
            "/views/spacecraft",
            """
            {"spacecraft": {"id": "KA-9",
             "platform": {"dry_mass_kg": 250,
               "power": {"sa_area_m2": 0.5, "sa_efficiency": 0.3, "battery_wh": 100},
               "attitude": {"pointing_accuracy_deg": 1.0}},
             "payload": {"architecture": "regenerative",
               "links": [{"id": "L1", "role": "user_uplink", "band_hz": 868.0e6, "tx_power_w": 2,
                          "antenna": {"type": "patch", "gain_dbi": 6}}],
               "onboard": {"buffer_mb": 64, "priority_policy": ["C_prime"]}}}}
            """,
        )
        assertEquals(422, r.statusCode())
        val res = mapper.readTree(r.body())
        assertTrue(res["errors"].any { it["adr"].asText("").startsWith("ADR-002") }) { r.body() }
    }

    @Test
    fun `изменение Baseline без основания отклоняется 409, с основанием проходит`() {
        boundary.objects.create(
            "RQ-0201", "requirement", mapper.createObjectNode().put("statement", "v1"),
            status = Lifecycle.Baseline, validFrom = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
        )
        val refused = post("/objects/RQ-0201/change", """{"doc":{"statement":"v2"}}""")
        assertEquals(409, refused.statusCode())
        assertTrue(mapper.readTree(refused.body())["error"].asText().contains("TZ-COM-003"))

        val accepted = post("/objects/RQ-0201/change", """{"doc":{"statement":"v2"},"change_ref":"CR-9"}""")
        assertEquals(200, accepted.statusCode())
        assertEquals("2", mapper.readTree(accepted.body())["version"].asText())
    }

    @Test
    fun `предки и потомки возвращаются за один запрос`() {
        listOf("ND-0301" to "need", "SV-0301" to "service", "RQ-0301" to "requirement")
            .forEach { (id, t) -> boundary.objects.create(id, t, mapper.createObjectNode()) }
        // связи — из документов (ADR-027): ручной POST /links для trace запрещён
        assertEquals(
            409,
            post("/links", """{"from":"ND-0301","to":"SV-0301","kind":"trace"}""").statusCode(),
        )
        boundary.req.syncLinks(
            "need", "ND-0301",
            mapper.createObjectNode().apply { putArray("traces_down").add("SV-0301") },
        )
        boundary.req.syncLinks(
            "requirement", "RQ-0301",
            mapper.createObjectNode().apply {
                putArray("traces_up").addObject().put("ref", "SV-0301")
            },
        )

        val up = mapper.readTree(get("/objects/RQ-0301/ancestors").body())
        assertEquals(listOf("ND-0301", "SV-0301"), up.map { it["id"].asText() }.sorted())
        val down = mapper.readTree(get("/objects/ND-0301/descendants").body())
        assertEquals(listOf("RQ-0301", "SV-0301"), down.map { it["id"].asText() }.sorted())
    }

    @Test
    fun `promote незрелого требования блокируется с причинами`() {
        boundary.req.ingestNeed(
            """{"id":"ND-0401","statement":"Наблюдение за инфраструктурой в арктической зоне.",
                "stakeholder":{"name":"Оператор","role":"operator"},"lifecycle":{"status":"Draft","version":"1"}}"""
        )
        boundary.req.ingestService(
            """{"id":"SV-0401","name":"Мониторинг объектов","traces_up":["ND-0401"],
                "qos_profiles":[{"consumer_class":"A_prime","moe":[{"id":"MOE-0401","name":"delivery_probability_daily",
                  "target":{"value":0.9,"unit":"1","provenance":{"source":"manual"}}}]}],
                "lifecycle":{"status":"Draft","version":"1"}}"""
        )
        val created = post(
            "/objects/requirement",
            """{"id":"RQ-0401","level":"system","statement":"Обеспечивается доставка данных при необходимости.",
                "category":"functional","traces_up":[{"ref":"SV-0401","consumer_class":"A_prime"}],
                "verification_events":[{"id":"VE-0001","method":"analysis","phase":"PhaseA","level":"system","kind":"qualification","status":"planned","closes":true,"design_version":"v1"}],"lifecycle":{"status":"Draft","version":"1"},
                "owner":"ведущий системный инженер"}""".trimIndent(),
        )
        assertEquals(201, created.statusCode()) { created.body() }

        val refused = post("/objects/RQ-0401/promote", """{"status":"Baseline"}""")
        assertEquals(409, refused.statusCode())
        val reasons = mapper.readTree(refused.body())["reasons"]
        assertTrue(reasons.size() > 0) { refused.body() }

        val accepted = post("/objects/RQ-0401/promote", """{"status":"Preliminary"}""")
        assertEquals(200, accepted.statusCode())
        assertEquals("Preliminary", mapper.readTree(accepted.body())["status"].asText())
    }

    @Test
    fun `отчёт зрелости и матрицы доступны по HTTP`() {
        val maturity = get("/reports/maturity?gate=SRR")
        assertEquals(200, maturity.statusCode())
        val m = mapper.readTree(maturity.body())
        assertEquals("SRR", m["gate"].asText())
        assertTrue(m.has("gaps_by_type") && m.has("open_tbd") && m.has("trace_breaks"))

        val matrix = get("/reports/trace-matrix")
        assertEquals(200, matrix.statusCode())
        assertTrue(mapper.readTree(matrix.body()).has("rows"))
    }
}
