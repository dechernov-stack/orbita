// Вызов службы фоновой задачей (ADR-069, живой отказ ПМИ-5 11.09): задание
// идёт, база пуста, пока модель думает; ответ применяется при опросе на
// потоке запросов; отказ провайдера — состояние задания, не потеря.
package orbita.com.api

import orbita.ai.ProviderAnswer
import orbita.ai.ProviderTransport
import orbita.ai.ProviderUnavailableException
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.schema.SchemaRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AiJobsTest {
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val PROJECT = "PJ-1202"
    private val PROFILE = """
        {"id":"AP-0002","name":"Порождение материала постановки","purpose":"нужды и цели из записки миссии",
         "kinds":["mission_to_needs","mission_to_goals","services_to_requirements"],
         "transport":"any","model_hint":"claude-sonnet-4-5",
         "statement_rules":["формулировка требования содержит модальное «должна»"],
         "glossary":[],"prohibitions":["bent-pipe не предлагать (Р1)"],
         "require_source":true,
         "lifecycle":{"status":"Draft","version":"1"}}"""

    @BeforeEach
    fun clean() {
        TestDb.truncateAll()
        TestDb.conn.createStatement().use { it.execute("DELETE FROM ai_calls") }
        boundary.ingest(
            orbita.mod.model.CoreType.Project,
            """{"id":"$PROJECT","name":"Служба фоном","phase":"pre_phase_a",
                "mission_intent":{"text":"Группировка передаёт телеметрию перевозчикам в Арктике к 2033 году."},
                "milestones":[{"gate":"MCR"}],"lifecycle":{"status":"Draft","version":"1"}}""",
        )
        boundary.ingest(orbita.mod.model.CoreType.AiProfile, PROFILE, "test", PROJECT)
    }

    private fun calls(): Int = TestDb.conn.createStatement().use { st ->
        st.executeQuery("SELECT count(*) FROM ai_calls").use { rs -> rs.next(); rs.getInt(1) }
    }

    private fun дождаться(jobs: AiJobs, id: String): AiJobs.Job {
        var job = jobs.poll(PROJECT, id)!!
        var попыток = 0
        while (job.status == "running" && попыток < 100) { Thread.sleep(50); job = jobs.poll(PROJECT, id)!!; попыток += 1 }
        return job
    }

    @Test
    fun `задание идёт, база пуста, ответ применяется при опросе`() {
        val отпустить = CountDownLatch(1)
        val дошло = CountDownLatch(1)
        val transport = ProviderTransport { _, _ ->
            дошло.countDown()
            отпустить.await(10, TimeUnit.SECONDS)
            ProviderAnswer("[]", "claude-sonnet-4-5", 1200, 20)
        }
        val jobs = AiJobs(AiService(boundary, transport))
        val job = jobs.start("mission_to_goals", "AP-0002", PROJECT, "Платформа IoT.", "Чернов Д.", AiJobs.Mode.ASK)
        assertEquals("running", job.status, "ответ не ждём: задание идёт")
        assertTrue(дошло.await(5, TimeUnit.SECONDS), "сеть звонит в фоне")
        assertEquals("running", jobs.poll(PROJECT, job.id)!!.status)
        assertEquals(0, calls(), "до ответа в журнале ничего")

        отпустить.countDown()
        val готово = дождаться(jobs, job.id)
        assertEquals("done", готово.status, готово.applyError ?: "")
        assertNotNull(готово.run, "отчёт есть")
        assertEquals(1, calls(), "вызов записан в журнал при опросе, на потоке запросов")
        val вид = jobs.view(готово)
        assertEquals("done", вид.path("status").asText())
        assertTrue(вид.has("report"))
        assertEquals(1, jobs.list(PROJECT).size)
        assertNull(jobs.poll("PJ-другой", job.id), "задание чужого проекта не видно")
    }

    @Test
    fun `отказ провайдера — задание failed с причиной, журнал знает об отказе`() {
        val transport = ProviderTransport { _, _ -> throw ProviderUnavailableException("нет ключа") }
        val jobs = AiJobs(AiService(boundary, transport))
        val job = jobs.start("mission_to_goals", "AP-0002", PROJECT, "Платформа IoT.", "Чернов Д.", AiJobs.Mode.ASK)
        val готово = дождаться(jobs, job.id)
        assertEquals("failed", готово.status)
        assertTrue(готово.run!!.report.path("reason").asText().contains("нет ключа"))
        assertEquals(1, calls(), "отказ провайдера тоже в журнале — как у синхронного вызова")
    }

    @Test
    fun `сырой режим — текст как пришёл, журнал заполнен`() {
        val transport = ProviderTransport { _, _ -> ProviderAnswer("{\"kind\":\"x\"}", "claude-sonnet-4-5", 10, 5) }
        val jobs = AiJobs(AiService(boundary, transport))
        val job = jobs.start("mission_to_goals", "AP-0002", PROJECT, "Платформа IoT.", "Чернов Д.", AiJobs.Mode.RAW)
        val готово = дождаться(jobs, job.id)
        assertEquals("done", готово.status)
        assertEquals("{\"kind\":\"x\"}", готово.raw!!.text)
        assertEquals("{\"kind\":\"x\"}", jobs.view(готово).path("raw").path("text").asText())
        assertEquals(1, calls())
    }
}
