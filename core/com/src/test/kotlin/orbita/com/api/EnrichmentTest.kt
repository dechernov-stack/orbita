// Дозаполнение атрибутов требований службой ИИ (находка живого прогона:
// 140 требований без обоснования и показателя). Путь: служба сама отбирает
// дырявые и собирает вход; ответ — частичные правки по спец-схеме; акцепт
// применяется ПРАВКАМИ существующих объектов — с основанием для базированных;
// статус наследуется (ADR-031): закрытие TBD ничего не понижает.
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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EnrichmentTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val server = HttpApi(boundary).start(0)
    private val base = "http://127.0.0.1:${server.address.port}/api"
    private val client = HttpClient.newHttpClient()

    @BeforeAll
    fun setup() {
        TestDb.truncateAll()
        boundary.ingest(
            orbita.mod.model.CoreType.Project,
            """{"id":"PJ-1401","name":"Дозаполнение","phase":"phase_a",
                "milestones":[{"gate":"SRR"}],"lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1401",
        )
        boundary.ingest(
            orbita.mod.model.CoreType.Need,
            """{"id":"ND-1401","statement":"Оператору нужен суточный сбор телеметрии.",
                "stakeholder":{"name":"Оператор","role":"operator"},
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1401",
        )
        // требование БЕЗ rationale и mop, базированное — самый жёсткий случай
        boundary.ingest(
            orbita.mod.model.CoreType.Requirement,
            """{"id":"RQ-1401","level":"system","category":"performance",
                "statement":"Система должна доставлять пакет телеметрии за сутки.",
                "traces_up":[{"ref":"ND-1401"}],"owner":"вед. системный инженер",
                "verification_events":[{"id":"VE-1401","method":"analysis","kind":"preliminary",
                  "phase":"PhaseA","level":"system","status":"planned","closes":false,
                  "approach":"Расчётная проверка доли доставленных сообщений по модели потоков.",
                  "means":"Модель Монте-Карло"}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1401",
        )
        // Baseline низким уровнем хранилища: promote честно требует полный
        // план верификации, а тесту нужно лишь СОСТОЯНИЕ базированного
        boundary.objects.transition("RQ-1401", Lifecycle.Baseline)
        // профиль, разрешающий дозаполнение, — закон П5: без него ни compose,
        // ни submit не работают
        boundary.ingest(
            orbita.mod.model.CoreType.AiProfile,
            """{"id":"AP-1401","name":"Дозаполнение","purpose":"обоснования и показатели",
                "kinds":["requirement_enrichment"],"transport":"any",
                "require_source":true,
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1401",
        )
    }

    @AfterAll
    fun stop() = server.stop(0)

    private fun post(path: String, body: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create("$base$path"))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `служба сама отбирает дырявые и называет недостающее во входе`() {
        val stmt = boundary.ai.enrichmentStatement("PJ-1401", "")
        assertTrue("RQ-1401" in stmt) { stmt }
        assertTrue("rationale" in stmt && "mop" in stmt) { stmt }
        assertTrue("Всего дырявых: 1" in stmt) { stmt }
    }

    @Test
    fun `закрытый контур дозаполнения - разбор без пакета вида, фильтр по спец-схеме`() {
        // прежде здесь был 500 «схему ответа выводить не из чего»: screen
        // строил пакет вида безусловно, а у дозаполнения его нет (находка
        // живого прогона — тест бил мимо этой ветки)
        val raw = """[
            {"id":"RQ-1401",
             "rationale":"Следует из нужды ND-1401: суточный цикл сбора телеметрии.",
             "mop":{"name":"Вероятность суточной доставки","operator":"ge",
                    "value":{"value":0.9,"unit":"1",
                             "provenance":{"source":"imported",
                               "import":{"dataset":"постановка миссии","dataset_version":"1",
                                         "retrieved_at":"2026-08-25","terms":"внутренний документ проекта"}}}}},
            {"id":"НЕ-ИД","rationale":"мимо схемы"}]"""
        val r = post(
            "/ai/submit?project=PJ-1401",
            mapper.writeValueAsString(
                mapper.createObjectNode()
                    .put("kind", "requirement_enrichment")
                    .put("profile", "AP-1401")
                    .put("statement", "")
                    .put("raw", raw)
                    .put("author", "Инженер"),
            ),
        )
        assertEquals(200, r.statusCode()) { r.body() }
        val n = mapper.readTree(r.body())
        assertEquals(2, n["proposed"].asInt())
        assertEquals(1, n["shown"].size()) { r.body() }
        // вход собрала служба (промпт с дырявым поимённо) — проверено
        // отдельным тестом enrichmentStatement; здесь важен сам контур
    }

    @Test
    fun `правка с изъяном отклоняется поимённо, ничего не записано`() {
        val r = post(
            "/ai/enrich-apply?project=PJ-1401",
            """{"by":"Инженер","items":[
                {"id":"RQ-1401","mop":{"name":"Вероятность доставки","operator":"ge",
                 "value":{"value":0.9,"unit":"1"}}}]}""",
        )
        // величина без основания не проходит нормативную схему требования
        assertEquals(422, r.statusCode()) { r.body() }
        assertTrue("RQ-1401" in r.body()) { r.body() }
        assertEquals(Lifecycle.Baseline, boundary.objects.current("RQ-1401")!!.status)
    }

    @Test
    fun `акцепт применяется правкой базированного - с основанием, статус наследуется`() {
        val r = post(
            "/ai/enrich-apply?project=PJ-1401",
            """{"by":"Инженер","items":[
                {"id":"RQ-1401",
                 "rationale":"Следует из нужды ND-1401: суточный цикл сбора телеметрии оператора.",
                 "mop":{"name":"Вероятность суточной доставки","operator":"ge",
                        "value":{"value":0.9,"unit":"1",
                                 "provenance":{"source":"imported","import":{"dataset":"постановка миссии","dataset_version":"1","retrieved_at":"2026-08-25","terms":"внутренний документ проекта"}}}}}]}""",
        )
        assertEquals(201, r.statusCode()) { r.body() }
        val n = mapper.readTree(r.body())
        assertEquals(1, n["written"].asInt())

        val cur = boundary.objects.current("RQ-1401")!!
        // ADR-031: правка наследует статус — базированное осталось базированным
        assertEquals(Lifecycle.Baseline, cur.status)
        assertTrue(cur.doc.path("rationale").asText().contains("ND-1401"))
        assertEquals("ge", cur.doc.path("mop").path("operator").asText())
        // след основания — на закрытом интервале
        val ref = TestDb.conn.prepareStatement(
            "SELECT change_ref FROM objects WHERE id='RQ-1401' AND valid_to IS NOT NULL " +
                "ORDER BY pk DESC LIMIT 1",
        ).use { ps -> ps.executeQuery().use { rs -> rs.next(); rs.getString(1) } }
        assertTrue(ref != null && "дозаполнение" in ref) { "change_ref=$ref" }
        // дыр больше нет
        assertTrue(boundary.ai.enrichmentCandidates("PJ-1401").isEmpty())
    }
}
