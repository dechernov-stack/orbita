// «Работа фазы»: задачи регламента ведут инженера после мастера. Ловушки
// владельца, которые держит этот тест: ручных статусов нет (всё считается);
// второй готовности нет (разрывы — разрезом общей); ручных длительностей
// нет (окна — только от дат вех).
package orbita.com.api

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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PhaseWorkTest {

    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)

    private fun задача(
        id: String,
        order: Int,
        name: String,
        depends: String = "",
        тип: String = "INPUT",
        input: String = "",
        выходВид: String = "mission_goal",
        шаги: String = """
            {"title":"Собрать цели","hint":"служба соберёт по замыслу",
             "screen":"aiservice","kind":"mission_to_goals",
             "done_when":{"check":"objects","type":"mission_goal","label":"цели приняты"}}""",
    ) = """
        {"id":"$id","phase":"pre_phase_a","order":$order,"name":"$name",
         "why":"Зачем эта задача — текстом с полки, а не из кода экрана.",
         ${if (depends.isBlank()) "" else "\"depends_on\":[{\"task\":\"$depends\",\"type\":\"$тип\"}],"}
         ${if (input.isBlank()) "" else "\"input\":[$input],"}
         "steps":[$шаги],
         "output":{"artifact":"Постановка","gate":"MCR","maturity":"draft",
                   "done_when":{"check":"objects","type":"$выходВид","min":1}},
         "lifecycle":{"status":"Draft","version":"1"}}"""

    @BeforeEach
    fun clean() {
        TestDb.truncateAll()
        boundary.ingest(
            CoreType.Project,
            """{"id":"PJ-1908","name":"Работа","phase":"pre_phase_a",
                "mission_intent":{"text":"Группировка IoT для логистики."},
                "milestones":[{"gate":"MCR","due":"2026-12-01"}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1908",
        )
        boundary.ingest(CoreType.PhaseTask, задача("PW-9001", 1, "Постановка"), "test", ObjectStore.LIBRARY_PROJECT)
        boundary.ingest(
            CoreType.PhaseTask,
            // у второй задачи СВОЙ выход: иначе она «выполнялась» бы вместе с первой
            задача("PW-9002", 2, "Концепция", depends = "PW-9001", выходВид = "decision"),
            "test", ObjectStore.LIBRARY_PROJECT,
        )
    }

    private fun проект() = boundary.ingest(
        CoreType.Project,
        """{"id":"PJ-1908","name":"Работа","phase":"pre_phase_a",
            "mission_intent":{"text":"Группировка IoT для логистики."},
            "milestones":[{"gate":"MCR","due":"2026-12-01"}],
            "lifecycle":{"status":"Draft","version":"1"}}""",
        "test", "PJ-1908",
    )

    private fun состояния() =
        PhaseWork.toJson(boundary, "PJ-1908").path("items").associateBy { it.path("id").asText() }

    @Test
    fun `задача без входа доступна, зависимая ждёт предшественника ИМЕНЕМ`() {
        val view = PhaseWork.toJson(boundary, "PJ-1908")
        assertEquals(2, view.path("tasks").asInt())
        val items = view.path("items").associateBy { it.path("id").asText() }
        assertEquals("available", items.getValue("PW-9001").path("status").asText())
        val вторая = items.getValue("PW-9002")
        assertEquals("waiting", вторая.path("status").asText())
        assertTrue("Постановка" in вторая.path("waits_on").asText()) {
            "ожидание обязано называть, КОГО ждём: ${вторая.path("waits_on").asText()}"
        }
    }

    @Test
    fun `шаг гаснет сам, когда его условие выполнено — ручной отметки нет`() {
        boundary.ingest(
            CoreType.MissionGoal,
            """{"id":"MG-9101","kind":"goal","statement":"Отслеживаемость грузов",
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1908",
        )
        val items = PhaseWork.toJson(boundary, "PJ-1908").path("items").associateBy { it.path("id").asText() }
        val первая = items.getValue("PW-9001")
        assertEquals("done", первая.path("status").asText()) { "выход задачи готов — статус вычислен" }
        assertTrue(первая.path("steps")[0].path("done").asBoolean()) { "шаг погас сам" }
        // и зависимая ожила без единого клика: ждать ей больше некого
        val вторая = items.getValue("PW-9002")
        assertTrue(вторая.path("status").asText() != "waiting") {
            "предшественник выполнен — задача обязана ожить сама: ${вторая.path("status").asText()}"
        }
        assertFalse(вторая.has("waits_on")) { "ожидания больше нет" }
    }

    @Test
    fun `окно задачи берётся от даты вехи, а не вводится руками`() {
        val первая = PhaseWork.toJson(boundary, "PJ-1908").path("items")[0]
        assertEquals("2026-12-01", первая.path("end").asText()) {
            "конец окна — дата точки выхода из паспорта"
        }
        assertFalse(первая.has("duration")) { "длительностей у задачи не существует" }
        assertFalse(первая.has("progress")) { "процентов выполнения не существует" }
    }

    @Test
    fun `следующий шаг шапки — верхушка работы, а не выдумка`() {
        val view = PhaseWork.toJson(boundary, "PJ-1908")
        val next = view.path("next")
        assertEquals("PW-9001", next.path("task").asText())
        assertEquals("Собрать цели", next.path("step").asText())
        assertEquals("aiservice", next.path("screen").asText())
        assertEquals("mission_to_goals", next.path("kind").asText()) {
            "переход обязан вести к преднастроенной операции, а не просто на экран"
        }
    }

    /**
     * Правило-класс: пустой раздел — приглашение с причиной, а не голый ноль.
     * Проект в фазе, задач которой на полке ещё нет, обязан узнать об этом
     * словами: «наполнение регламентом не сделано», — а не смотреть в пустоту.
     */
    @Test
    fun `пустая работа фазы объясняет себя`() {
        val passport = boundary.objects.current("PJ-1908")!!
        boundary.editing.update(
            CoreType.Project, "PJ-1908",
            com.fasterxml.jackson.databind.ObjectMapper().readTree("""{"phase":"phase_a"}""")
                as com.fasterxml.jackson.databind.node.ObjectNode,
            passport.version, "test", changeRef = "переход фазы",
        )
        val view = PhaseWork.toJson(boundary, "PJ-1908")
        assertEquals(0, view.path("tasks").asInt())
        val why = view.path("empty_why").asText()
        assertTrue("phase_a" in why && "pre_phase_a" in why) {
            "пустота обязана назвать фазу проекта и то, что есть на полке: $why"
        }
    }

    /**
     * Круг 6, п. 1: тип связи решает, чего ждать. Регламент итеративно-
     * параллелен, и «после окончания» у всех подряд — неправда о нём.
     */
    @Test
    fun `SS ждёт старта предшественника, а не его окончания`() {
        TestDb.truncateAll()
        проект()
        // у предшественника СТАРТ и ОКОНЧАНИЕ — разные события: шаг закрывается
        // целями, а выход — решением. Иначе проверять SS было бы не на чем
        boundary.ingest(
            CoreType.PhaseTask,
            задача("PW-9001", 1, "Постановка", выходВид = "decision"),
            "test", ObjectStore.LIBRARY_PROJECT,
        )
        boundary.ingest(
            CoreType.PhaseTask,
            задача(
                "PW-9002", 2, "Концепция", depends = "PW-9001", тип = "SS", выходВид = "service",
                шаги = """{"title":"Сценарии","screen":"conops",
                           "done_when":{"check":"objects","type":"conops","label":"сценарии есть"}}""",
            ),
            "test", ObjectStore.LIBRARY_PROJECT,
        )
        val до = состояния()
        assertEquals("waiting", до.getValue("PW-9002").path("status").asText())
        assertTrue("после её старта" in до.getValue("PW-9002").path("waits_on").asText()) {
            "ожидание обязано называть тип связи словами: ${до.getValue("PW-9002").path("waits_on").asText()}"
        }
        // первый шаг предшественника закрыт — он стартовал, и SS отпускает
        boundary.ingest(
            CoreType.MissionGoal,
            """{"id":"MG-0001","kind":"goal","statement":"Покрыть логистику связью",
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1908",
        )
        // выход предшественника по-прежнему не готов — и это больше не важно:
        // SS отпускает по СТАРТУ (сама вторая задача уже пошла в работу)
        val после = состояния()
        assertEquals("available", после.getValue("PW-9002").path("status").asText()) {
            "SS не ждёт окончания: предшественник начат — можно идти вместе"
        }
        assertFalse(после.getValue("PW-9001").path("output_done").asBoolean()) {
            "проверка имеет смысл, только пока выход предшественника не готов"
        }
    }

    @Test
    fun `FF не блокирует старт вовсе, INPUT ждёт выход`() {
        TestDb.truncateAll()
        проект()
        boundary.ingest(CoreType.PhaseTask, задача("PW-9001", 1, "Постановка"), "test", ObjectStore.LIBRARY_PROJECT)
        boundary.ingest(
            CoreType.PhaseTask,
            задача("PW-9002", 2, "Точки", depends = "PW-9001", тип = "FF", выходВид = "decision"),
            "test", ObjectStore.LIBRARY_PROJECT,
        )
        boundary.ingest(
            CoreType.PhaseTask,
            задача("PW-9003", 3, "Записка", depends = "PW-9001", тип = "INPUT", выходВид = "conops"),
            "test", ObjectStore.LIBRARY_PROJECT,
        )
        val s = состояния()
        assertEquals("available", s.getValue("PW-9002").path("status").asText()) {
            "FF говорит об окончании, а не о старте: работать можно с самого начала"
        }
        assertEquals("waiting", s.getValue("PW-9003").path("status").asText())
        assertTrue("выход-артефакт" in s.getValue("PW-9003").path("waits_on").asText())
        assertEquals(
            s.getValue("PW-9001").path("tier").asInt(),
            s.getValue("PW-9002").path("tier").asInt(),
        ) { "FF-задачи кончаются вместе — и ярус у них общий" }
        assertEquals(2, s.getValue("PW-9003").path("tier").asInt())
    }

    /** Круг 6, п. 3: порядок шагов — связями полки, а не догадкой по номеру. */
    @Test
    fun `ярусы шагов считаются по связям, а не по порядку в списке`() {
        TestDb.truncateAll()
        проект()
        boundary.ingest(
            CoreType.PhaseTask,
            задача(
                "PW-9001", 1, "Развёртывание",
                шаги = """
                    {"title":"Орг-структура","screen":"stakeholders",
                     "done_when":{"check":"objects","type":"stakeholder"}},
                    {"title":"SEMP по шаблону","screen":"docs","after":[{"step":1,"type":"SS"}],
                     "done_when":{"check":"document_issued","code":"semp"}},
                    {"title":"Базировать","screen":"lifecycle",
                     "after":[{"step":1,"type":"FS"},{"step":2,"type":"FS"}],
                     "done_when":{"check":"objects","type":"mission_goal"}}""",
            ),
            "test", ObjectStore.LIBRARY_PROJECT,
        )
        val шаги = состояния().getValue("PW-9001").path("steps")
        assertEquals(listOf(1, 1, 2), шаги.map { it.path("tier").asInt() }) {
            "SS оставляет шаг рядом, FS уводит на ярус ниже: ${шаги.map { it.path("tier").asInt() }}"
        }
        assertEquals(2, шаги[0].path("tiers").asInt())
        assertTrue("вместе с" in шаги[1].path("after")[0].path("words").asText()) {
            "связь шага обязана называться словами: ${шаги[1].path("after")}"
        }
    }

    /**
     * Круг 2 (порядок работ) и круг 5 (Гант на библиотеке): доли интервала
     * ушли в полотно Ганта, а ЯРУС остался — он и есть порядок работ, по
     * которому чертится сетка задач без плана. Длительностей у задач нет.
     */
    @Test
    fun `ярус зависимостей считается — он и есть порядок работ`() {
        val items = PhaseWork.toJson(boundary, "PJ-1908").path("items").associateBy { it.path("id").asText() }
        assertEquals(1, items.getValue("PW-9001").path("tier").asInt()) { "вход готов сам — первый ярус" }
        assertEquals(2, items.getValue("PW-9002").path("tier").asInt()) { "ждёт первую — второй ярус" }
        assertEquals(2, items.getValue("PW-9001").path("tiers").asInt()) { "в интервале точки два яруса" }
        assertFalse(items.getValue("PW-9001").has("lane_width_pct")) {
            "долей интервала в «Работе фазы» больше нет: полотно рисует библиотека Ганта по плану"
        }
    }

    /**
     * Патч контента Phase A: шаг «Написать связные разделы» SEMP закрывается
     * ТОЛЬКО текстами разделов шаблона semp. Авторский текст другого документа
     * шаг не закрывает — иначе «связно написан SEMP» значило бы «кто-то
     * что-то написал».
     */
    @Test
    fun `условие objects с кодом считает только тексты своего документа`() {
        boundary.ingest(
            CoreType.PhaseTask,
            """{"id":"PW-9007","phase":"pre_phase_a","order":7,"name":"Связный SEMP",
                "why":"Проверка сужения условия кодом документа.",
                "steps":[{"title":"Написать связные разделы","screen":"aiservice","kind":"semp_draft",
                          "done_when":{"check":"objects","type":"section_text","code":"semp",
                                       "label":"связные разделы SEMP написаны"}}],
                "output":{"artifact":"Д2 · SEMP","gate":"MCR",
                          "done_when":{"check":"objects","type":"section_text","code":"semp"}},
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", ObjectStore.LIBRARY_PROJECT,
        )
        fun шаг() = PhaseWork.toJson(boundary, "PJ-1908").path("items")
            .first { it.path("id").asText() == "PW-9007" }.path("steps").first()

        // текст раздела ДРУГОГО документа шаг не закрывает
        boundary.ingest(
            CoreType.SectionText,
            """{"id":"ST-9001","template_code":"conops","section":3,"text":"Сценарии эксплуатации связно.",
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1908",
        )
        assertFalse(шаг().path("done").asBoolean()) { "чужой документ не закрывает шаг SEMP" }
        // подпись условия названа полкой — она и стоит в «готово, когда»
        assertEquals("связные разделы SEMP написаны", шаг().path("why").asText())

        boundary.ingest(
            CoreType.SectionText,
            """{"id":"ST-9002","template_code":"semp","section":3,"text":"Техническое резюме связно.",
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1908",
        )
        assertTrue(шаг().path("done").asBoolean()) { "текст раздела SEMP закрывает шаг" }
        assertEquals("разделов написано: 1", шаг().path("tally").asText()) { "мини-итог считает только semp" }
    }

    /** Находка перезаливки полки: пустой work_plan давал мини-итог «задано» при шаге «не сделан». */
    @Test
    fun `мини-итог поля паспорта не врёт про пустой список`() {
        boundary.ingest(
            CoreType.PhaseTask,
            """{"id":"PW-9008","phase":"pre_phase_a","order":8,"name":"План фазы",
                "why":"Проверка мини-итога поля паспорта.",
                "steps":[{"title":"План работ фазы","screen":"lifecycle",
                          "done_when":{"check":"passport_field","field":"work_plan","label":"план задан"}}],
                "output":{"artifact":"План","gate":"MCR",
                          "done_when":{"check":"passport_field","field":"work_plan"}},
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", ObjectStore.LIBRARY_PROJECT,
        )
        val проект = boundary.objects.current("PJ-1908")!!
        val пусто = ObjectMapper().createObjectNode().also { it.putArray("work_plan") }
        boundary.editing.update(CoreType.Project, "PJ-1908", пусто, проект.version, "test", changeRef = "пустой план")
        val шаг = PhaseWork.toJson(boundary, "PJ-1908").path("items")
            .first { it.path("id").asText() == "PW-9008" }.path("steps").first()
        assertFalse(шаг.path("done").asBoolean())
        assertFalse(шаг.has("tally")) { "пустой список — не «задано»: ${шаг.path("tally").asText()}" }
    }
}
