// ПМИ-4, реестр пакетов (раздел Р, правило 5): каждый пакет вставлен на
// одноразовом стенде — схема проходит, счётчики совпадают, отказов по форме
// нет; отсев по содержанию допустим и записан. Одноразовый стенд здесь —
// тестовая база: тот же канал пакетов, те же схемы и правила, что у живого
// вызова. В модель стенда при подготовке не пишется ничего.
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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Pmi4PacketsTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val dir = RepoPaths.repoRoot().resolve("docs/tz/manual-run-4/пакеты")

    /** Профиль службы стенда: виды пакетов постановки и требований, основание обязательно. */
    private val PROFILE = """
        {"id":"AP-0001","name":"Пакеты ПМИ-4","purpose":"проверка пакетов на тестовой базе",
         "kinds":["mission_to_needs","mission_to_goals","needs_to_services","services_to_requirements"],
         "transport":"any","model_hint":"пакет",
         "statement_rules":["формулировка требования содержит модальное «должна»"],
         "prohibitions":["bent-pipe не предлагать (Р1)"],
         "require_source":true,
         "lifecycle":{"status":"Draft","version":"1"}}"""

    @BeforeEach
    fun clean() {
        TestDb.truncateAll()
        TestDb.conn.createStatement().use { it.execute("DELETE FROM ai_calls") }
        boundary.ingest(
            CoreType.Project,
            """{"id":"PJ-2501","name":"ПМИ-4 проверка пакетов","phase":"pre_phase_a",
                "mission_intent":{"for_whom":"грузоперевозчики","what":"резервный канал координат",
                                  "where":"вне наземного покрытия","horizon":"2030"},
                "milestones":[{"gate":"MCR","due":"2026-12-01"}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2501",
        )
        boundary.ingest(CoreType.AiProfile, PROFILE, "test", "PJ-2501")
    }

    private fun raw(name: String) = dir.resolve(name).toFile().readText()

    private fun report(name: String) = boundary.ai.packet(raw(name), "PJ-2501", "Чернов").report

    @Test
    fun `Р01 замысел — один объект по схеме черновика замысла`() {
        // замысел вставляется на шаге Ш3 мастер-пути, не общим каналом пакетов:
        // проверяется схемой черновика — той же, что применяет принятие
        val node = mapper.readTree(raw("Р01-замысел.json"))
        assertEquals("mission_intent_from_docs", node.path("kind").asText())
        val errors = boundary.schemas.validate("core/mission-intent-draft", node)
        assertTrue(errors.isEmpty()) { "черновик замысла обязан проходить схему: $errors" }
        listOf("for_whom", "what", "where", "horizon").forEach { f ->
            assertTrue(node.path("intent").path(f).path("text").asText("").isNotBlank()) { "поле $f с якорями" }
        }
    }

    @Test
    fun `Р02 цели и нужды — по 11, отказов по форме нет`() {
        listOf("Р02-цели.json", "Р02-нужды.json").forEach { f ->
            val r = report(f)
            assertEquals(0, r.path("malformed").size()) { "$f: ${r.path("malformed").toPrettyString()}" }
            assertEquals(11, r.path("proposed").asInt()) { f }
        }
    }

    @Test
    fun `Р03 сервисы — 16 по форме, 4 с чужими ссылками без пары`() {
        val r = report("Р03-сервисы.json")
        assertEquals(0, r.path("malformed").size()) { r.path("malformed").toPrettyString() }
        assertEquals(16, r.path("proposed").asInt())
        val items = mapper.readTree(raw("Р03-сервисы.json")).path("items")
        val сироты = items.filter { it.path("traces_up").any { ref -> ref.asText().startsWith("ND-099") } }
        assertEquals(4, сироты.size) { "четыре сервиса нарочно ссылаются на нужды, которых нет" }
    }

    @Test
    fun `Р04 требования — ловушка без источника отсеяна с причиной, «при необходимости» принято`() {
        val r = report("Р04-требования.json")
        assertEquals(6, r.path("proposed").asInt()) { "предложено 4 + 2 ловушки: ${r.path("proposed")}" }
        // величина без происхождения не проходит схему — отсев по форме с причиной
        val сломанные = r.path("malformed").map { it.path("item").path("id").asText() }
        assertEquals(listOf("RQ-0090"), сломанные) { "число без источника обязано быть отсеяно, и только оно: $сломанные" }
        // «при необходимости» — по форме цело, но жёсткий свод качества (эталон
        // spec/requirements_semantics.py) шлёт его в доработку с названной
        // причиной: принять можно решением инженера, и тогда реестр покажет
        // помету L-C2. Автоматом такое не проходит — и не должно.
        // отчёт доработки — объект со списком items (так его читает и экран службы)
        val строки = r.path("rework").let { if (it.isArray) it else it.path("rework") }
        val доработка = строки.associate { it.path("item").path("id").asText() to it.path("issues").toString() }
        assertTrue("RQ-0091" in доработка) { "RQ-0091 обязано дойти до доработки, а не отсеяться по форме: $доработка" }
        assertTrue("неизмеримое определение" in доработка.getValue("RQ-0091")) { доработка.getValue("RQ-0091") }
        val показано = r.path("shown").map { it.path("item").path("id").asText() }
        assertTrue("RQ-0001" in показано) { "чистое требование проходит: $показано" }
    }

    @Test
    fun `Р05 урожай записки — по схеме разбора, 41 кандидат`() {
        val node = mapper.readTree(raw("Р05-урожай-записки.json"))
        assertEquals("document_semantic_parse", node.path("kind").asText())
        assertEquals(41, node.path("items").size())
        val errors = boundary.schemas.validate("core/document-harvest", node)
        assertTrue(errors.isEmpty()) { "урожай обязан проходить схему разбора: $errors" }
    }
}
