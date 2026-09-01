// Круг 5–7: Гант на библиотеке. Полотно рисует frappe-gantt, поэтому проверять
// здесь нечего кроме СВОЕГО — данных и правил:
//   · строки приходят в форме библиотеки, прогресс всегда 0 (ловушка 4);
//   · план — источник дат; без плана окно остаётся расчётной сеткой и
//     подписано «план не задан» (ловушка 3);
//   · план НЕ влияет на статус (ловушка 1);
//   · соседей автосдвиг не двигает, конфликт подсвечен (ловушка 2);
//   · план ведёт руководитель: отказ называет право;
//   · круг 6: тип связи приходит с полки и решает, что считается конфликтом;
//   · круг 7: шаги — строки «N.M» по умолчанию, полоса задачи с шагами
//     сводная и не тянется, точка закрывает свой интервал.
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

    private val точка = LocalDate.now().plusMonths(6)

    private fun задача(
        id: String,
        order: Int,
        name: String,
        depends: String = "",
        тип: String = "FS",
        выходВид: String = "mission_goal",
        шаги: String = """{"title":"Собрать цели","screen":"aiservice",
                           "done_when":{"check":"objects","type":"mission_goal","label":"цели приняты"}}""",
    ) = """
        {"id":"$id","phase":"pre_phase_a","order":$order,"name":"$name",
         "why":"Зачем эта задача — текстом с полки.",
         ${if (depends.isBlank()) "" else "\"depends_on\":[{\"task\":\"$depends\",\"type\":\"$тип\"}],"}
         "steps":[$шаги],
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
                "milestones":[{"gate":"MCR","due":"$точка"}],
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

    private fun полотно(login: String? = null, свёрнуты: Set<String> = emptySet()): JsonNode =
        PhaseGantt.toJson(boundary, "PJ-2101", login, свёрнуты)

    private fun строки(v: JsonNode): Map<String, JsonNode> =
        v.path("tasks").associateBy { it.path("id").asText() }

    private fun поставить(цель: String, start: String, end: String, author: String = "Чернов", login: String? = null) =
        PhaseGantt.plan(
            boundary, "PJ-2101",
            mapper.readTree("""{"task":"$цель","start":"$start","end":"$end"}"""),
            author, login,
        )

    // ---- форма и запреты ------------------------------------------------

    @Test
    fun `строки приходят в форме библиотеки, процентов выполнения нет`() {
        val v = полотно()
        val t = строки(v).getValue("PW-9002")
        listOf("id", "name", "start", "end", "progress", "dependencies", "custom_class").forEach {
            assertTrue(t.has(it)) { "библиотека ждёт поле '$it': $t" }
        }
        assertEquals(0, t.path("progress").asInt()) { "процентов выполнения у задач не существует" }
        assertEquals("PW-9001", t.path("dependencies").asText()) { "стрелку рисует библиотека по зависимости" }
        assertEquals("2 · Концепция", t.path("name").asText())
        assertTrue(t.path("waits_on").asText().contains("Постановка"))
    }

    @Test
    fun `связь приходит типом и словами, а стрелку рисует библиотека`() {
        val связь = полотно().path("links").first { it.path("to").asText() == "PW-9002" }
        assertEquals("FS", связь.path("type").asText())
        assertTrue("после окончания" in связь.path("words").asText()) {
            "тип связи обязан звучать словами: ${связь.path("words").asText()}"
        }
    }

    // ---- круг 7: строки шагов, сводная полоса, интервалы -----------------

    @Test
    fun `шаги — строки N M и развёрнуты по умолчанию`() {
        TestDb.truncateAll()
        проект()
        boundary.ingest(CoreType.PhaseTask, задачаСТремяШагами(), "test", ObjectStore.LIBRARY_PROJECT)
        val v = полотно()
        val id = v.path("tasks").map { it.path("id").asText() }
        assertEquals(listOf("PW-9001", "PW-9001#0", "PW-9001#1", "PW-9001#2", "gate:MCR"), id) {
            "шаги — строки полотна сразу под своей задачей, а не выпадающая подробность"
        }
        val шаг = строки(v).getValue("PW-9001#1")
        assertEquals("1.2 · SEMP по шаблону", шаг.path("name").asText()) { "нумерация сквозная ‹задача›.‹шаг›" }
        assertEquals("1.2", шаг.path("number").asText())
        // SS оставляет 1.1 и 1.2 рядом, FS уводит 1.3 ниже — видно окнами
        val ш1 = строки(v).getValue("PW-9001#0")
        val ш3 = строки(v).getValue("PW-9001#2")
        assertEquals(ш1.path("start").asText(), шаг.path("start").asText()) {
            "SS: шаги 1.1 и 1.2 начинаются вместе"
        }
        assertTrue(ш3.path("start").asText() >= ш1.path("end").asText()) {
            "FS: шаг 1.3 идёт после первых двух"
        }
        // свёрнутая задача прячет свои шаги
        val свёрнуто = полотно(свёрнуты = setOf("PW-9001"))
        assertTrue(свёрнуто.path("tasks").none { it.path("kind").asText() == "step" })
        assertTrue(строки(свёрнуто).getValue("PW-9001").path("collapsed").asBoolean())
    }

    @Test
    fun `полоса задачи с шагами — сводная и не тянется`() {
        TestDb.truncateAll()
        проект()
        boundary.ingest(CoreType.PhaseTask, задачаСТремяШагами(), "test", ObjectStore.LIBRARY_PROJECT)
        val t = строки(полотно()).getValue("PW-9001")
        assertTrue(t.path("summary").asBoolean()) { "у задачи с шагами полоса сводная" }
        assertEquals("pw-summary", t.path("custom_class").asText())
        val e = assertThrows<IllegalArgumentException> {
            поставить("PW-9001", "2027-01-11", "2027-02-02")
        }
        assertTrue("План ставится шагам" in e.message!!) { e.message!! }
    }

    @Test
    fun `план ставится шагу, сводная полоса вычисляется из шагов`() {
        TestDb.truncateAll()
        проект()
        boundary.ingest(CoreType.PhaseTask, задачаСТремяШагами(), "test", ObjectStore.LIBRARY_PROJECT)
        val статусДо = строки(полотно()).getValue("PW-9001").path("status").asText()
        val v = поставить("PW-9001#2", "2027-05-01", "2027-05-20")
        val шаг = строки(v).getValue("PW-9001#2")
        assertEquals("2027-05-01", шаг.path("start").asText())
        assertTrue(шаг.path("planned").asBoolean())
        assertEquals("pw-step-plan", шаг.path("custom_class").asText())
        val сводная = строки(v).getValue("PW-9001")
        assertEquals("2027-05-20", сводная.path("end").asText()) {
            "сводная полоса тянется до конца самого позднего шага"
        }
        assertTrue("вычислена из шагов" in сводная.path("window_why").asText())
        // ловушка 1: план статуса не касается
        assertEquals(статусДо, сводная.path("status").asText())
    }

    @Test
    fun `точка закрывает свой интервал, а задача за точкой — конфликт`() {
        val v = полотно()
        val интервал = v.path("intervals").first { it.path("gate").asText() == "MCR" }
        assertEquals(v.path("phase_start").asText(), интервал.path("from").asText()) {
            "первый интервал начинается началом фазы, а не «сегодня»"
        }
        assertEquals(точка.toString(), интервал.path("to").asText())
        // расчётные окна задач лежат ВНУТРИ интервала — ромб оказывается в конце
        строки(v).values.filter { it.path("kind").asText() == "task" }.forEach { t ->
            assertTrue(t.path("end").asText() <= точка.toString()) {
                "окно задачи ${t.path("id").asText()} уходит за свою точку: ${t.path("end").asText()}"
            }
        }
        // план за точкой — конфликт с обеих сторон
        val после = поставить("PW-9001#0", точка.plusDays(3).toString(), точка.plusDays(9).toString())
        val задача = строки(после).getValue("PW-9001")
        assertTrue(задача.path("conflict").asBoolean())
        assertTrue("после точки MCR" in задача.path("gate_overrun").asText()) {
            задача.path("gate_overrun").asText()
        }
        assertEquals("pw-summary-conflict", задача.path("custom_class").asText())
        assertTrue(строки(после).getValue("gate:MCR").path("conflict").asBoolean()) {
            "точку тоже подсвечиваем: двигать можно и её"
        }
    }

    /**
     * Живая находка круга 7: интервал короче числа ярусов (SRR через два дня,
     * а ярусов три) растягивал доли ЗА окно, и полотно рисовало «конфликт за
     * точкой» на ровном месте. Доля обязана оставаться внутри своего окна.
     */
    @Test
    fun `короткий интервал не рождает ложный конфликт за точкой`() {
        TestDb.truncateAll()
        val близкая = LocalDate.now().plusDays(2)
        boundary.ingest(
            CoreType.Project,
            """{"id":"PJ-2101","name":"Гант","phase":"pre_phase_a",
                "mission_intent":{"text":"Группировка IoT для логистики."},
                "milestones":[{"gate":"MCR","due":"$близкая"}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2101",
        )
        boundary.ingest(CoreType.PhaseTask, задачаСТремяШагами(), "test", ObjectStore.LIBRARY_PROJECT)
        boundary.ingest(
            CoreType.PhaseTask,
            задача("PW-9002", 2, "Концепция", depends = "PW-9001", выходВид = "decision"),
            "test", ObjectStore.LIBRARY_PROJECT,
        )
        val v = полотно()
        строки(v).values.filter { it.path("kind").asText() != "gate" }.forEach { r ->
            assertTrue(r.path("end").asText() <= близкая.toString()) {
                "строка ${r.path("id").asText()} вылезла за точку: ${r.path("end").asText()}"
            }
            assertFalse(r.path("conflict").asBoolean()) {
                "конфликта нет: планов никто не ставил, интервал просто короткий"
            }
        }
    }

    // ---- правила плана ---------------------------------------------------

    @Test
    fun `без плана полоса — расчётная сетка и подписана честно`() {
        val t = строки(полотно()).getValue("PW-9001")
        assertFalse(t.path("planned").asBoolean())
        assertTrue("план не задан" in t.path("window_why").asText() ||
            "Планов пока нет" in t.path("window_why").asText()) {
            "фикция долей не выдаётся за план: ${t.path("window_why").asText()}"
        }
    }

    @Test
    fun `просроченный план — окантовка, а ждущая задача с далёкой точкой не красная`() {
        val далёкая = строки(полотно()).getValue("PW-9002")
        assertFalse(далёкая.has("alarm")) { "точка через полгода — тревоги нет" }

        val вчера = LocalDate.now().minusDays(1)
        val t = строки(поставить("PW-9001#0", вчера.minusDays(10).toString(), вчера.toString()))
            .getValue("PW-9001")
        assertTrue(t.path("alarm").asText().contains("плановый конец")) { t.path("alarm").asText() }
        assertEquals("pw-summary-alarm", t.path("custom_class").asText())
    }

    @Test
    fun `конфликт плана подсвечен с обеих сторон, соседей никто не двигал`() {
        поставить("PW-9001#0", "2027-03-01", "2027-04-01")
        val было = строки(полотно()).getValue("PW-9001#0").let {
            it.path("start").asText() to it.path("end").asText()
        }
        val v = поставить("PW-9002#0", "2027-03-10", "2027-03-20")
        val предшественник = строки(v).getValue("PW-9001")
        val преемник = строки(v).getValue("PW-9002")
        assertTrue(преемник.path("conflict").asBoolean()) { "конфликт обязан быть виден" }
        assertTrue(предшественник.path("conflict").asBoolean()) { "и со стороны предшественника тоже" }
        assertEquals(
            было,
            строки(v).getValue("PW-9001#0").let { it.path("start").asText() to it.path("end").asText() },
        ) { "автосдвига соседей нет: план предшественника остался как был" }
    }

    @Test
    fun `точки фазы приходят полосами нулевой длины ромбами`() {
        val точкаСтрока = строки(полотно()).getValue("gate:MCR")
        assertEquals("pw-ms", точкаСтрока.path("custom_class").asText())
        assertEquals(точкаСтрока.path("start").asText(), точкаСтрока.path("end").asText()) {
            "веха — полоса нулевой длины: ромб рисует CSS"
        }
        assertEquals(1, полотно().path("milestone_lines").size()) { "и вертикаль через полотно" }
    }

    @Test
    fun `план ведёт руководитель — отказ называет право`() {
        boundary.auth.createUser("ivan", "парольивана", "Иванов И.")
        boundary.auth.createUser("chief", "парольшефа", "Чернов Д.")
        boundary.auth.setRole("PJ-2101", "ivan", "specialist")
        val e = assertThrows<PhaseGantt.RightDeniedException> {
            поставить("PW-9001#0", "2027-01-11", "2027-02-02", author = "Иванов", login = "ivan")
        }
        assertTrue(e.message!!.contains("руководитель")) { "отказ обязан называть право: ${e.message}" }
        assertFalse(строки(полотно("ivan")).getValue("PW-9001#0").path("planned").asBoolean())
        assertFalse(полотно("ivan").path("can_plan").asBoolean()) { "экран обязан знать право заранее" }

        boundary.auth.setRole("PJ-2101", "chief", "lead")
        поставить("PW-9001#0", "2027-01-11", "2027-02-02", author = "Чернов", login = "chief")
        assertTrue(строки(полотно("chief")).getValue("PW-9001#0").path("planned").asBoolean())
    }

    @Test
    fun `план снимается — полоса возвращается в расчётную сетку`() {
        поставить("PW-9001#0", "2027-01-11", "2027-02-02")
        val v = PhaseGantt.plan(
            boundary, "PJ-2101", mapper.readTree("""{"task":"PW-9001#0","clear":true}"""), "Чернов", null,
        )
        val шаг = строки(v).getValue("PW-9001#0")
        assertFalse(шаг.path("planned").asBoolean())
        assertEquals("pw-step", шаг.path("custom_class").asText())
    }

    // ---- круг 8: прогресс вычислен, ответственный, длительность ----------

    @Test
    fun `прогресс задачи вычислен из закрытых шагов и растёт сам`() {
        TestDb.truncateAll()
        проект()
        boundary.ingest(CoreType.PhaseTask, задачаСТремяШагами(), "test", ObjectStore.LIBRARY_PROJECT)
        val пусто = строки(полотно()).getValue("PW-9001")
        assertEquals(0, пусто.path("progress").asInt())
        assertTrue("0 из 3" in пусто.path("progress_why").asText()) { пусто.path("progress_why").asText() }

        // закрыт первый шаг — процент двигается САМ, руками его не ставят
        boundary.ingest(
            CoreType.Stakeholder,
            """{"id":"SK-9001","name":"ГКРЧ","role":"regulator",
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2101",
        )
        val после = строки(полотно()).getValue("PW-9001")
        assertEquals(33, после.path("progress").asInt()) { "1 из 3 закрыт — 33%" }
        assertTrue("1 из 3" in после.path("progress_why").asText()) {
            "процент не бывает голым числом: ${после.path("progress_why").asText()}"
        }
    }

    @Test
    fun `ответственного назначает руководитель, шаг наследует задачу`() {
        TestDb.truncateAll()
        проект()
        boundary.ingest(CoreType.PhaseTask, задачаСТремяШагами(), "test", ObjectStore.LIBRARY_PROJECT)
        val v = PhaseGantt.assign(
            boundary, "PJ-2101",
            mapper.readTree("""{"task":"PW-9001","who":"Чернов Д."}"""), "Чернов", null,
        )
        assertEquals("Чернов Д.", строки(v).getValue("PW-9001").path("assignee").asText())
        val шаг = строки(v).getValue("PW-9001#1")
        assertEquals("Чернов Д.", шаг.path("assignee").asText()) { "шаг наследует ответственного задачи" }
        assertFalse(шаг.path("assignee_own").asBoolean()) { "и это видно: своя запись у него не заведена" }

        val свой = PhaseGantt.assign(
            boundary, "PJ-2101",
            mapper.readTree("""{"task":"PW-9001#1","who":"Иванов И."}"""), "Чернов", null,
        )
        assertEquals("Иванов И.", строки(свой).getValue("PW-9001#1").path("assignee").asText())
        assertTrue(строки(свой).getValue("PW-9001#1").path("assignee_own").asBoolean())
        assertEquals("Чернов Д.", строки(свой).getValue("PW-9001#0").path("assignee").asText()) {
            "соседний шаг остался на наследовании"
        }

        // «Моя работа» ловит ответственного, а не только задания
        val моё = boundary.processTasks.myTasks("PJ-2101", "Иванов И.")
        assertEquals(1, моё.path("works").size())
        assertEquals("PW-9001#1", моё.path("works")[0].path("id").asText())
        assertEquals(1, моё.path("counts").path("works").asInt())

        // право: назначает руководитель
        boundary.auth.createUser("petr", "парольпетра", "Петров П.")
        boundary.auth.setRole("PJ-2101", "petr", "specialist")
        val e = assertThrows<PhaseGantt.RightDeniedException> {
            PhaseGantt.assign(
                boundary, "PJ-2101",
                mapper.readTree("""{"task":"PW-9001","who":"Петров"}"""), "Петров", "petr",
            )
        }
        assertTrue("руководитель" in e.message!!) { e.message!! }
    }

    @Test
    fun `длительность считается рабочими днями, а число двигает конец плана`() {
        TestDb.truncateAll()
        проект()
        boundary.ingest(CoreType.PhaseTask, задачаСТремяШагами(), "test", ObjectStore.LIBRARY_PROJECT)
        // понедельник 2027-05-03 … пятница 2027-05-07 — пять рабочих дней
        val v = поставить("PW-9001#0", "2027-05-03", "2027-05-07")
        assertEquals(5, строки(v).getValue("PW-9001#0").path("duration_days").asInt())
        assertTrue(строки(v).getValue("PW-9001#0").path("duration_planned").asBoolean())

        // ввод длительности числом — тот же план другими руками
        val трое = PhaseGantt.plan(
            boundary, "PJ-2101",
            mapper.readTree("""{"task":"PW-9001#0","start":"2027-05-03","duration_days":3}"""),
            "Чернов", null,
        )
        val шаг = строки(трое).getValue("PW-9001#0")
        assertEquals("2027-05-05", шаг.path("end").asText()) { "три рабочих дня от понедельника — среда" }
        assertEquals(3, шаг.path("duration_days").asInt())
        // выходные в счёт не идут: пятница + 3 рабочих дня — вторник
        val через = PhaseGantt.plan(
            boundary, "PJ-2101",
            mapper.readTree("""{"task":"PW-9001#0","start":"2027-05-07","duration_days":3}"""),
            "Чернов", null,
        )
        assertEquals("2027-05-11", строки(через).getValue("PW-9001#0").path("end").asText()) {
            "суббота и воскресенье рабочими днями не бывают"
        }
    }

    // ---- фикстуры --------------------------------------------------------

    private fun проект() = boundary.ingest(
        CoreType.Project,
        """{"id":"PJ-2101","name":"Гант","phase":"pre_phase_a",
            "mission_intent":{"text":"Группировка IoT для логистики."},
            "milestones":[{"gate":"MCR","due":"$точка"}],
            "lifecycle":{"status":"Draft","version":"1"}}""",
        "test", "PJ-2101",
    )

    /** Задача 1 регламента в миниатюре: 1.1 и 1.2 параллельны, 1.3 — после. */
    private fun задачаСТремяШагами() = задача(
        "PW-9001", 1, "Развёртывание",
        шаги = """
            {"title":"Орг-структура","screen":"stakeholders",
             "done_when":{"check":"objects","type":"stakeholder"}},
            {"title":"SEMP по шаблону","screen":"docs","after":[{"step":1,"type":"SS"}],
             "done_when":{"check":"document_issued","code":"semp"}},
            {"title":"Базировать","screen":"lifecycle",
             "after":[{"step":1,"type":"FS"},{"step":2,"type":"FS"}],
             "done_when":{"check":"objects","type":"mission_goal"}}""",
    )
}
