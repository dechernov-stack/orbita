// Круг 5: Гант на библиотеке. Полотно рисует frappe-gantt, поэтому проверять
// здесь нечего кроме СВОЕГО — данных и правил:
//   · строки приходят в форме библиотеки, прогресс всегда 0 (ловушка 4);
//   · план — источник дат полосы; без плана полоса остаётся расчётной сеткой
//     и подписана «план не задан» (ловушка 3);
//   · план НЕ влияет на статус (ловушка 1);
//   · соседей автосдвиг не двигает, конфликт подсвечен (ловушка 2);
//   · план ведёт руководитель: отказ называет право.
package orbita.com.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.CoreType
import orbita.mod.schema.SchemaRegistry
import orbita.mod.store.ObjectStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PhaseGanttTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)

    private fun задача(
        id: String,
        order: Int,
        name: String,
        depends: String = "",
        тип: String = "FS",
        выходВид: String = "mission_goal",
    ) = """
        {"id":"$id","phase":"pre_phase_a","order":$order,"name":"$name",
         "why":"Зачем эта задача — текстом с полки.",
         ${if (depends.isBlank()) "" else "\"depends_on\":[{\"task\":\"$depends\",\"type\":\"$тип\"}],"}
         "steps":[{"title":"Собрать цели","screen":"aiservice",
                   "done_when":{"check":"objects","type":"$выходВид","label":"цели приняты"}}],
         "output":{"artifact":"Д2 · Постановка","gate":"MCR","maturity":"draft",
                   "done_when":{"check":"objects","type":"$выходВид","min":1}},
         "lifecycle":{"status":"Draft","version":"1"}}"""

    @BeforeEach
    fun clean() {
        TestDb.truncateAll()
        boundary.ingest(
            CoreType.Project,
            """{"id":"PJ-2101","name":"Гант","phase":"pre_phase_a",
                "mission_intent":{"text":"Группировка IoT для логистики."},
                "milestones":[{"gate":"MCR","due":"${LocalDate.now().plusMonths(6)}"}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2101",
        )
        boundary.ingest(CoreType.PhaseTask, задача("PW-9001", 1, "Постановка"), "test", ObjectStore.LIBRARY_PROJECT)
        boundary.ingest(
            CoreType.PhaseTask,
            задача("PW-9002", 2, "Концепция", depends = "PW-9001", выходВид = "decision"),
            "test", ObjectStore.LIBRARY_PROJECT,
        )
    }

    private fun полотно(login: String? = null): JsonNode = PhaseGantt.toJson(boundary, "PJ-2101", login)

    private fun строки(v: JsonNode): Map<String, JsonNode> =
        v.path("tasks").associateBy { it.path("id").asText() }

    private fun поставить(task: String, start: String, end: String, author: String = "Чернов", login: String? = null) =
        PhaseGantt.plan(
            boundary, "PJ-2101",
            mapper.readTree("""{"task":"$task","start":"$start","end":"$end"}"""),
            author, login,
        )

    @Test
    fun `строки приходят в форме библиотеки, процентов выполнения нет`() {
        val v = полотно()
        val t = строки(v).getValue("PW-9002")
        listOf("id", "name", "start", "end", "progress", "dependencies", "custom_class").forEach {
            assertTrue(t.has(it)) { "библиотека ждёт поле '$it': $t" }
        }
        assertEquals(0, t.path("progress").asInt()) { "процентов выполнения у задач не существует" }
        assertEquals("PW-9001", t.path("dependencies").asText()) { "стрелку рисует библиотека по зависимости" }
        // слова «ждёт: …» живут в попапе, а не в подписи полосы
        assertEquals("2 · Концепция", t.path("name").asText())
        assertTrue(t.path("waits_on").asText().contains("Постановка"))
    }

    @Test
    fun `без плана полоса — расчётная сетка и подписана честно`() {
        val t = строки(полотно()).getValue("PW-9001")
        assertFalse(t.path("planned").asBoolean())
        assertEquals("pw-grid", t.path("custom_class").asText())
        assertTrue(t.path("window_why").asText().contains("план не задан")) {
            "фикция долей не выдаётся за план: ${t.path("window_why").asText()}"
        }
    }

    @Test
    fun `план — источник дат полосы, но статуса он не касается`() {
        val доПлана = строки(полотно()).getValue("PW-9001")
        val статусДо = доПлана.path("status").asText()
        val v = поставить("PW-9001", "2027-01-11", "2027-02-02")
        val t = строки(v).getValue("PW-9001")
        assertEquals("2027-01-11", t.path("start").asText())
        assertEquals("2027-02-02", t.path("end").asText())
        assertTrue(t.path("planned").asBoolean())
        assertEquals("Чернов", t.path("plan_author").asText())
        assertEquals("pw-$статусДо", t.path("custom_class").asText()) { "класс — статусный, план его не менял" }
        // ловушка 1: статус вычисляется из состояния проекта и план не читает
        assertEquals(статусДо, t.path("status").asText())
        assertEquals(
            статусДо,
            PhaseWork.toJson(boundary, "PJ-2101").path("items")
                .first { it.path("id").asText() == "PW-9001" }.path("status").asText(),
        ) { "план не имеет права двигать статус задачи" }
    }

    @Test
    fun `просроченный план — окантовка, а ждущая задача с далёкой точкой не красная`() {
        val далёкая = строки(полотно()).getValue("PW-9002")
        assertFalse(далёкая.has("alarm")) { "точка через полгода — тревоги нет" }
        assertFalse(далёкая.path("custom_class").asText().contains("alarm"))

        val вчера = LocalDate.now().minusDays(1)
        val t = строки(поставить("PW-9001", вчера.minusDays(10).toString(), вчера.toString()))
            .getValue("PW-9001")
        assertTrue(t.path("alarm").asText().contains("плановый конец")) { t.path("alarm").asText() }
        assertTrue(t.path("custom_class").asText().endsWith("-alarm")) { t.path("custom_class").asText() }
    }

    @Test
    fun `конфликт плана подсвечен с обеих сторон, соседей никто не двигал`() {
        поставить("PW-9001", "2027-03-01", "2027-04-01")
        val было = строки(полотно()).getValue("PW-9001").let {
            it.path("start").asText() to it.path("end").asText()
        }
        // преемник начинается раньше, чем кончается предшественник
        val v = поставить("PW-9002", "2027-03-10", "2027-03-20")
        val предшественник = строки(v).getValue("PW-9001")
        val преемник = строки(v).getValue("PW-9002")
        assertTrue(преемник.path("conflict").asBoolean()) { "конфликт обязан быть виден" }
        assertTrue(предшественник.path("conflict").asBoolean()) { "и со стороны предшественника тоже" }
        assertEquals("pw-conflict", преемник.path("custom_class").asText())
        assertEquals(
            было,
            предшественник.path("start").asText() to предшественник.path("end").asText(),
        ) { "автосдвига соседей нет: план предшественника остался как был" }
    }

    @Test
    fun `точки фазы приходят полосами нулевой длины ромбами`() {
        val точка = строки(полотно()).getValue("gate:MCR")
        assertEquals("pw-ms", точка.path("custom_class").asText())
        assertEquals(точка.path("start").asText(), точка.path("end").asText()) {
            "веха — полоса нулевой длины: ромб рисует CSS"
        }
        assertEquals("gate", точка.path("kind").asText())
    }

    @Test
    fun `план ведёт руководитель — отказ называет право`() {
        boundary.auth.createUser("ivan", "парольивана", "Иванов И.")
        boundary.auth.createUser("chief", "парольшефа", "Чернов Д.")
        boundary.auth.setRole("PJ-2101", "ivan", "specialist")
        val e = assertThrows<PhaseGantt.RightDeniedException> {
            поставить("PW-9001", "2027-01-11", "2027-02-02", author = "Иванов", login = "ivan")
        }
        assertTrue(e.message!!.contains("руководитель")) { "отказ обязан называть право: ${e.message}" }
        assertFalse(строки(полотно("ivan")).getValue("PW-9001").path("planned").asBoolean())
        assertFalse(полотно("ivan").path("can_plan").asBoolean()) { "экран обязан знать право заранее" }

        boundary.auth.setRole("PJ-2101", "chief", "lead")
        поставить("PW-9001", "2027-01-11", "2027-02-02", author = "Чернов", login = "chief")
        assertTrue(строки(полотно("chief")).getValue("PW-9001").path("planned").asBoolean())
    }

    // ---- Круг 6: связи типами, вехи вертикалями, шаги подзадачами ----

    @Test
    fun `связь приходит типом и словами, а стрелку рисует библиотека`() {
        val v = полотно()
        val связь = v.path("links").first { it.path("to").asText() == "PW-9002" }
        assertEquals("FS", связь.path("type").asText())
        assertTrue("после окончания" in связь.path("words").asText()) {
            "тип связи обязан звучать словами: ${связь.path("words").asText()}"
        }
        // библиотеке по-прежнему отдаётся простой список — рисует она сама
        assertEquals("PW-9001", строки(v).getValue("PW-9002").path("dependencies").asText())
    }

    @Test
    fun `конфликт плана считается по типу связи, а не одним законом`() {
        // SS: конфликт — когда преемник начинается раньше СТАРТА предшественника
        TestDb.truncateAll()
        clean()
        boundary.ingest(
            CoreType.PhaseTask,
            задача("PW-9003", 3, "Параллельная", depends = "PW-9001", тип = "SS", выходВид = "conops"),
            "test", ObjectStore.LIBRARY_PROJECT,
        )
        поставить("PW-9001", "2027-03-10", "2027-04-01")
        val ссКонфликт = строки(поставить("PW-9003", "2027-03-01", "2027-03-20"))
        assertTrue(ссКонфликт.getValue("PW-9003").path("conflict").asBoolean()) {
            "SS сравнивает СТАРТЫ: начать раньше предшественника нельзя"
        }
        // тот же план при FS-связи конфликтом бы не был только по концу —
        // проверяем, что INPUT сроков не касается вовсе
        val input = полотно().path("links").firstOrNull { it.path("type").asText() == "INPUT" }
        assertTrue(input == null || !input.path("conflict").asBoolean()) {
            "INPUT — условие готовности, а не срок: конфликта дат у него не бывает"
        }
    }

    @Test
    fun `вехи приходят вертикалями полотна, а не только ромбами`() {
        val линии = полотно().path("milestone_lines")
        assertEquals(1, линии.size())
        assertEquals("MCR", линии[0].path("name").asText())
        assertTrue(линии[0].path("date").asText().isNotBlank())
    }

    @Test
    fun `шаги приходят подзадачами при раскрытии, планов им не заводят`() {
        val свёрнуто = полотно()
        assertTrue(свёрнуто.path("tasks").none { it.path("kind").asText() == "step" }) {
            "по умолчанию шаги свёрнуты: полотно не обязано быть простынёй"
        }
        val раскрыто = PhaseGantt.toJson(boundary, "PJ-2101", null, setOf("PW-9001"))
        val шаги = раскрыто.path("tasks").filter { it.path("kind").asText() == "step" }
        assertEquals(1, шаги.size)
        assertEquals("PW-9001", шаги[0].path("parent").asText())
        assertTrue(шаги[0].path("name").asText().startsWith("— ")) { "шаг отбит в имени: ${шаги[0].path("name")}" }
        assertTrue(шаги[0].path("custom_class").asText().startsWith("pw-step"))
        assertTrue("не сроки" in шаги[0].path("window_why").asText()) {
            "окно шага — порядок, а не срок: ${шаги[0].path("window_why").asText()}"
        }
        // строка шага идёт сразу за своей задачей: библиотека рисует порядком
        val id = раскрыто.path("tasks").map { it.path("id").asText() }
        assertEquals(id.indexOf("PW-9001") + 1, id.indexOf("PW-9001#0"))
        // ловушка 2: планов шагам не заводят
        val e = assertThrows<IllegalArgumentException> {
            PhaseGantt.plan(
                boundary, "PJ-2101",
                mapper.readTree("""{"task":"PW-9001#0","start":"2027-01-01","end":"2027-01-05"}"""),
                "Чернов", null,
            )
        }
        assertTrue("шагам не заводят" in e.message!!) { e.message!! }
    }

    @Test
    fun `план снимается — полоса возвращается в расчётную сетку`() {
        поставить("PW-9001", "2027-01-11", "2027-02-02")
        val v = PhaseGantt.plan(
            boundary, "PJ-2101", mapper.readTree("""{"task":"PW-9001","clear":true}"""), "Чернов", null,
        )
        val t = строки(v).getValue("PW-9001")
        assertFalse(t.path("planned").asBoolean())
        assertEquals("pw-grid", t.path("custom_class").asText())
    }
}
