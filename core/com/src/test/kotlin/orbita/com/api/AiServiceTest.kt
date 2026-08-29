// Служба ИИ (П5) путём данных: профиль → промпт службы → вызов → фильтр
// с правилом основания → журнал «сколько и почём» → акцепт дописывается
// к своему вызову. Транспорт провайдера подменён: сеть в тесте не нужна,
// проверяется служба, а не канал.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.ai.ProviderAnswer
import orbita.ai.ProviderTransport
import orbita.ai.ProviderUnavailableException
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.schema.SchemaRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiServiceTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)

    private val PROJECT = "PJ-1201"
    private val PROFILE = """
        {"id":"AP-0001","name":"Порождение материала постановки","purpose":"нужды и цели из записки миссии",
         "kinds":["mission_to_needs","mission_to_goals","services_to_requirements"],
         "transport":"any","model_hint":"claude-sonnet-4-5",
         "statement_rules":["формулировка требования содержит модальное «должна»"],
         "glossary":[{"term":"зона обслуживания","meaning":"подмножество footprint с замкнутым бюджетом линии",
                      "not":"зоной видимости"}],
         "prohibitions":["bent-pipe не предлагать (Р1)","оптический ISL не предлагать (Р3)"],
         "require_source":true,
         "lifecycle":{"status":"Draft","version":"1"}}"""

    @BeforeEach
    fun clean() {
        TestDb.truncateAll()
        TestDb.conn.createStatement().use { it.execute("DELETE FROM ai_calls") }
        boundary.ingest(
            orbita.mod.model.CoreType.Project,
            // Ф-05: у видов постановки промпт без замысла миссии не
            // собирается — замысел входит в фикстуру проекта службы
            """{"id":"$PROJECT","name":"Служба","phase":"pre_phase_a",
                "mission_intent":{"text":"Группировка передаёт телеметрию перевозчикам в Арктике к 2033 году."},
                "milestones":[{"gate":"MCR"}],"lifecycle":{"status":"Draft","version":"1"}}""",
        )
        boundary.ingest(orbita.mod.model.CoreType.AiProfile, PROFILE, "test", PROJECT)
        boundary.ingest(
            orbita.mod.model.CoreType.Need,
            """{"id":"ND-1201","statement":"Оператору нужен суточный сбор телеметрии.",
                "stakeholder":{"name":"Оператор","role":"operator"},
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", PROJECT,
        )
    }

    private fun service(transport: ProviderTransport) = AiService(boundary, transport)

    @Test
    fun `промпт собирает служба - ограничения, глоссарий, состояние модели`() {
        val (profile, prompt) = service { _, _ -> error("вызова быть не должно") }
            .compose("mission_to_needs", "AP-0001", PROJECT, "Платформа IoT: сбор телеметрии.")
        assertEquals("AP-0001", profile.id)
        // профиль внутри промпта: запреты, правила, глоссарий, правило основания
        assertTrue("bent-pipe" in prompt) { prompt }
        assertTrue("зона обслуживания" in prompt) { prompt }
        assertTrue("ОСНОВАНИЕ ЗНАЧЕНИЙ" in prompt) { prompt }
        // состояние модели: что уже есть в проекте
        assertTrue("ND-1201" in prompt) { prompt }
        // вход операции — от инженера
        assertTrue("Платформа IoT" in prompt) { prompt }
    }

    @Test
    fun `вид вне профиля не исполняется`() {
        val failure = runCatching {
            service { _, _ -> error("не должно дойти") }
                .compose("requirement_decomposition", "AP-0001", PROJECT, "x")
        }
        assertTrue(failure.isFailure)
        assertTrue("не разрешает вид пакета" in (failure.exceptionOrNull()?.message ?: ""))
    }

    @Test
    fun `прямой вызов - фильтр, правило основания и журнал сколько и почём`() {
        val answer = """
            [{"id":"RQ-1201","level":"project","category":"performance",
              "statement":"Система должна доставлять пакет за сутки.",
              "traces_up":[{"ref":"ND-1201"}],"owner":"вед. системный инженер",
              "mop":{"name":"Вероятность доставки","operator":"ge",
                     "value":{"value":0.9,"unit":"1","provenance":{"source":"manual"}}},
              "verification_events":[{"id":"VE-1201","method":"analysis","kind":"preliminary",
                "phase":"PhaseA","level":"system","status":"planned","closes":false,
                "approach":"расчётная проверка по имитационной модели потоков",
                "means":"модель Монте-Карло ядра"},
               {"id":"VE-1202","method":"test","kind":"qualification","phase":"PhaseC",
                "level":"system","status":"planned","closes":true,
                "approach":"квалификационные испытания на стенде радиолинии",
                "means":"испытательный стенд радиолинии","design_version":"К-1"}],
              "lifecycle":{"status":"Draft","version":"1"}}]"""
        val run = service { _, _ -> ProviderAnswer(answer, "claude-sonnet-4-5", 1200, 800) }
            .ask("services_to_requirements", "AP-0001", PROJECT, "Платформа IoT.", "Чернов Д.")

        assertEquals("direct", run.transport)
        assertEquals(1, run.report["proposed"].asInt())
        // значение без ссылки на источник — снято правилом основания, не молча
        assertEquals(1, run.report["no_source"].asInt())
        assertTrue(run.report["by_rule"].has("основание")) { run.report.toString() }

        val journal = service { _, _ -> error("не нужен") }.journal(PROJECT)
        assertEquals(1, journal["totals"]["calls"].asInt())
        assertEquals(1200, journal["calls"][0]["tokens_in"].asInt())
        assertEquals("claude-sonnet-4-5", journal["calls"][0]["model"].asText())
        assertEquals("AP-0001", journal["calls"][0]["profile"].asText())
        assertTrue(journal["calls"][0]["prompt"].asText().contains("bent-pipe"))

        // акцепт дописывается к своему вызову
        service { _, _ -> error("не нужен") }.markAccepted(run.callPk, 1, "Чернов Д.")
        val after = service { _, _ -> error("не нужен") }.journal(PROJECT)
        assertEquals(1, after["totals"]["accepted"].asInt())
    }

    @Test
    fun `ненастроенный прямой канал - состояние, а не ошибка, и след в журнале`() {
        val run = service { _, _ -> throw ProviderUnavailableException("прямой канал не настроен") }
            .ask("mission_to_needs", "AP-0001", PROJECT, "Платформа IoT.", "Чернов Д.")
        assertTrue(run.report["failed"].asBoolean())
        assertTrue("не настроен" in run.report["reason"].asText())
        val journal = service { _, _ -> error("не нужен") }.journal(PROJECT)
        assertEquals(1, journal["totals"]["calls"].asInt())
        assertTrue(journal["calls"][0]["failure"].asText().isNotBlank())
    }

    @Test
    fun `Б-01 - пакет вносится без модели, вид из самого пакета, журнал модель пакет`() {
        val svc = service { _, _ -> error("модель не вызывается — Б-01") }
        // голый массив: вид по префиксу id, профиль подобран по виду
        val bare = """
            [{"id":"ND-1301","statement":"Перевозчику нужен контроль рефрижераторов в пути.",
              "stakeholder":{"name":"Перевозчик","role":"end_user"},
              "lifecycle":{"status":"Draft","version":"1"}}]"""
        val run = svc.packet(bare, PROJECT, "Чернов Д.")
        assertEquals("package", run.transport)
        assertEquals(AiService.PACKET_MODEL, run.model)
        assertEquals("mission_to_needs", run.report["kind"].asText())
        assertEquals("AP-0001", run.report["profile"].asText())
        assertEquals(1, run.report["proposed"].asInt())
        assertEquals(1, run.report["shown"].size())
        val journal = svc.journal(PROJECT)
        assertEquals(AiService.PACKET_MODEL, journal["calls"][0]["model"].asText())
        assertEquals("package", journal["calls"][0]["transport"].asText())

        // обёртка несёт вид явно — правящим видам только так
        val wrapped = """{"kind":"mission_to_needs","items":[
            {"id":"ND-1302","statement":"Диспетчеру нужна сводка потерянных терминалов.",
             "stakeholder":{"name":"Диспетчер","role":"operator"},
             "lifecycle":{"status":"Draft","version":"1"}}]}"""
        assertEquals(1, svc.packet(wrapped, PROJECT, "Чернов Д.").report["shown"].size())

        // смешанные префиксы вид не выводят — отказ зовёт обёртку
        val mixed = """[{"id":"ND-1303","statement":"x"},{"id":"MG-1301","statement":"y"}]"""
        val refusal = runCatching { svc.packet(mixed, PROJECT, "Чернов Д.") }
        assertTrue("оберните" in (refusal.exceptionOrNull()?.message ?: "")) {
            refusal.exceptionOrNull()?.message ?: "нет отказа"
        }

        // вид без разрешающего профиля — отказ называет причину
        val risky = """[{"id":"RSK-1301","title":"z"}]"""
        val noProfile = runCatching { svc.packet(risky, PROJECT, "Чернов Д.") }
        assertTrue("нет профиля службы" in (noProfile.exceptionOrNull()?.message ?: "")) {
            noProfile.exceptionOrNull()?.message ?: "нет отказа"
        }
    }

    @Test
    fun `закрытый контур - тот же разбор, фильтр и журнал, транспорт package`() {
        val raw = """
            [{"id":"ND-1202","statement":"Агрохолдингу нужен суточный съём показаний.",
              "stakeholder":{"name":"Агрохолдинг","role":"end_user"},
              "lifecycle":{"status":"Draft","version":"1"}}]"""
        val run = service { _, _ -> error("прямого вызова быть не должно") }
            .submit("mission_to_needs", "AP-0001", PROJECT, "Платформа IoT.", raw, "Чернов Д.")
        assertEquals("package", run.transport)
        assertEquals(1, run.report["proposed"].asInt())
        assertEquals(1, run.report["shown"].size())
        val journal = service { _, _ -> error("не нужен") }.journal(PROJECT)
        assertEquals("package", journal["calls"][0]["transport"].asText())
        assertTrue(journal["calls"][0]["prompt"].asText().isNotBlank())
    }
}
