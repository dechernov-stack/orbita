// Круг 4: схема — карта потока фазы. Ловушки владельца, которые держит этот
// тест: схема — ВЫЧИСЛЕННАЯ ПРОЕКЦИЯ, а не рисунок (координат не хранит никто,
// изменилась зависимость на полке — раскладка перестроилась сама); процент
// точки берётся из готовности, а не заводится вторым счётом; активность на
// узлах — люди, служебные учётки на схему не выходят.
package orbita.com.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
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
class PhaseFlowTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)

    private fun задача(id: String, order: Int, name: String, depends: String = "", выходВид: String = "mission_goal") = """
        {"id":"$id","phase":"pre_phase_a","order":$order,"name":"$name",
         "why":"Зачем эта задача — текстом с полки.",
         ${if (depends.isBlank()) "" else "\"depends_on\":[\"$depends\"],"}
         "steps":[{"title":"Собрать цели","screen":"aiservice",
                   "done_when":{"check":"objects","type":"$выходВид","label":"цели приняты"}}],
         "output":{"artifact":"Д2 · Постановка","document_code":"semp","gate":"MCR","maturity":"draft",
                   "done_when":{"check":"objects","type":"$выходВид","min":1}},
         "lifecycle":{"status":"Draft","version":"1"}}"""

    @BeforeEach
    fun clean() {
        TestDb.truncateAll()
        boundary.ingest(
            CoreType.Project,
            """{"id":"PJ-2001","name":"Поток","phase":"pre_phase_a",
                "mission_intent":{"text":"Группировка IoT для логистики."},
                "milestones":[{"gate":"MCR","due":"2026-12-01"},{"gate":"SRR","due":"2027-04-01"}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2001",
        )
        boundary.ingest(CoreType.PhaseTask, задача("PW-9001", 1, "Постановка"), "test", ObjectStore.LIBRARY_PROJECT)
        boundary.ingest(
            CoreType.PhaseTask,
            задача("PW-9002", 2, "Концепция", depends = "PW-9001", выходВид = "decision"),
            "test", ObjectStore.LIBRARY_PROJECT,
        )
    }

    private fun схема(): ObjectNode = PhaseFlow.toJson(boundary, "PJ-2001")

    private fun узлы(v: JsonNode): Map<String, JsonNode> =
        v.path("nodes").associateBy { it.path("id").asText() }

    @Test
    fun `узлы — задачи, точки — ромбами, рёбра — артефактами именами`() {
        val v = схема()
        val n = узлы(v)
        assertEquals(2, v.path("nodes").count { it.path("kind").asText() == "task" })
        val точка = n.getValue("MCR")
        assertEquals("gate", точка.path("kind").asText())
        assertTrue(точка.path("points").asText().split(" ").size == 4) {
            "ромб рисуется по четырём вершинам, посчитанным сервером: ${точка.path("points").asText()}"
        }
        // ребро зависимости подписано артефактом предшественника и несёт код
        val ребро = v.path("edges").first {
            it.path("from").asText() == "PW-9001" && it.path("to").asText() == "PW-9002"
        }
        assertEquals("Д2 Постановка", ребро.path("label").asText())
        assertEquals("semp", ребро.path("document_code").asText()) {
            "подпись ребра обязана открывать артефакт, а не быть просто надписью"
        }
        assertTrue(ребро.path("path").asText().startsWith("M ")) { "путь ребра считает сервер" }
        // и выход задачи ведёт к своей точке — «задача → артефакт → точка»
        assertTrue(v.path("edges").any { it.path("from").asText() == "PW-9002" && it.path("to").asText() == "MCR" })
    }

    @Test
    fun `процент точки — из готовности, а не второй счёт`() {
        val точка = узлы(схема()).getValue("MCR")
        val note = точка.path("note").asText()
        assertTrue(точка.has("pct") || note.isNotBlank()) { "процент либо честное «проверок нет»: $note" }
        if (точка.has("pct")) {
            val pct = точка.path("pct").asDouble()
            assertTrue(pct in 0.0..100.0) { "доля закрытых проверок: $pct" }
            val проверки = boundary.gatePassing.readiness("MCR", "PJ-2001")
            val применимо = проверки.count { it.state != "na" }
            val закрыто = проверки.count { it.state == "closed" }
            assertTrue(note.startsWith("$закрыто из $применимо")) {
                "числа на схеме — те же, что в готовности к точке: «$note»"
            }
        }
    }

    /**
     * Ловушка задания: редактор схемы, ручные координаты и «сохранить
     * раскладку» запрещены. Проверяется не запретом в коде, а поведением:
     * изменилась зависимость на полке — раскладка стала другой сама.
     */
    @Test
    fun `раскладка не хранится — смена зависимости перестраивает схему`() {
        val было = узлы(схема()).getValue("PW-9002").path("x").asDouble()
        val задача = boundary.objects.current("PW-9002")!!
        boundary.editing.update(
            CoreType.PhaseTask, "PW-9002",
            mapper.readTree("""{"depends_on":[]}""") as ObjectNode,
            задача.version, "test", changeRef = "снят вход задачи",
        )
        val стало = узлы(схема()).getValue("PW-9002").path("x").asDouble()
        assertTrue(стало < было) {
            "без предшественника задача уходит на первый ярус: было $было, стало $стало"
        }
        // и никакой раскладки в модели не появилось — координаты нигде не лежат
        assertTrue(
            boundary.objects.listCurrent("PJ-2001").none { "layout" in it.type || "flow" in it.type },
        ) { "схема — проекция, а не объект модели" }
    }

    @Test
    fun `схема — чистая проекция, два обращения дают одну картинку`() {
        assertEquals(схема().toString(), схема().toString()) {
            "картинка зависит только от состояния, а не от того, кто когда смотрел"
        }
    }

    /**
     * Живость процесса: узел несёт последнюю активность именем человека.
     * Служебные учётки (ServiceAuthors — один список служебности на систему)
     * на схему не выходят: «ci-runner · вчера» о движении команды не говорит.
     */
    @Test
    fun `активность узла — человек, служебная запись движением не считается`() {
        boundary.ingest(
            CoreType.Decision,
            """{"id":"DN-9001","question":"Какая архитектура полезной нагрузки?",
                "alternatives":[{"name":"regenerative"},{"name":"bent-pipe"}],
                "status":"open","lifecycle":{"status":"Draft","version":"1"}}""",
            "ci-runner", "PJ-2001",
        )
        val служебная = узлы(схема()).getValue("PW-9002")
        assertFalse(служебная.has("activity")) { "служебная правка движением не считается" }
        assertFalse(служебная.path("recent").asBoolean()) { "и подсветку «за неделю» не даёт" }

        boundary.ingest(
            CoreType.MissionGoal,
            """{"id":"MG-9101","kind":"goal","statement":"Отслеживаемость грузов",
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "Чернов Дмитрий", "PJ-2001",
        )
        val живая = узлы(схема()).getValue("PW-9001")
        assertEquals("Чернов Дмитрий", живая.path("activity").path("author").asText())
        assertEquals("ЧД", живая.path("activity").path("initials").asText()) { "аватарка — инициалами" }
        assertTrue(живая.path("recent").asBoolean()) { "правка сегодня — движение за неделю" }
        assertTrue(живая.path("people").any { it.path("initials").asText() == "ЧД" })
    }

    /** За последними воротами фазы — следующая фаза свёрнутым облаком. */
    @Test
    fun `за воротами — следующая фаза облаком`() {
        val облако = узлы(схема())["cloud"]
        assertTrue(облако != null) { "SRR в паспорте — за MCR обязана виднеться Phase A" }
        assertEquals("Phase A", облако!!.path("name").asText())
    }

    /**
     * Приёмка владельца по настоящему сиду: схема Phase A — 11 узлов-задач,
     * три точки ромбами, рёбра с Д-кодами. Синтетика двух задач такого не
     * ловит: там нет ни ветвления, ни трёх точек.
     */
    @Test
    fun `схема Phase A по настоящему сиду — 12 задач, три точки, Д-коды на рёбрах`() {
        TestDb.truncateAll()
        boundary.ingest(
            CoreType.Project,
            """{"id":"PJ-2002","name":"Фаза A","phase":"phase_a",
                "mission_intent":{"text":"Группировка IoT для логистики."},
                "milestones":[{"gate":"SRR","due":"2026-11-01"},{"gate":"SDR","due":"2027-02-01"},
                              {"gate":"KDP-B","due":"2027-05-01"},{"gate":"PDR","due":"2027-10-01"}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2002",
        )
        val пакет = mapper.readTree(
            RepoPaths.repoRoot().resolve("docs/tz/manual-run/packets/12-задачи-фазы-phase-a.json").toFile(),
        )
        пакет.path("objects").forEach {
            boundary.ingest(CoreType.PhaseTask, it.toString(), "test", ObjectStore.LIBRARY_PROJECT)
        }
        val v = PhaseFlow.toJson(boundary, "PJ-2002")
        // патч контента: задача 1 разделена на «Развёртывание фазы» и «SEMP»
        assertEquals(12, v.path("nodes").count { it.path("kind").asText() == "task" })
        val точки = v.path("nodes").filter { it.path("kind").asText() == "gate" }.map { it.path("gate").asText() }
        assertEquals(listOf("SRR", "SDR", "KDP-B"), точки) {
            "точки идут порядком паспорта, а не порядком задач: $точки"
        }
        val подписи = v.path("edges").mapNotNull { it.path("label").asText("").ifBlank { null } }
        assertTrue(подписи.any { it.startsWith("Д2") } && подписи.any { it.startsWith("Д3") }) {
            "рёбра подписаны артефактами регламента: $подписи"
        }
        // «клик ребра Д2 открывает документ»: код шаблона едет вместе с подписью
        val д2 = v.path("edges").first { it.path("label").asText().startsWith("Д2") }
        assertEquals("semp", д2.path("document_code").asText())
        // поток идёт слева направо: точка правее своих задач
        val n = узлы(v)
        val srr = n.getValue("SRR").path("x").asDouble()
        assertTrue(n.getValue("PW-0101").path("x").asDouble() < srr) { "задача левее своей точки" }
        assertTrue(n.getValue("PW-0104").path("x").asDouble() > srr) { "работа за SRR — правее ворот" }
    }

    /**
     * Круг 4 §2: нить потока в рамке ведения — вход, выход и потребители.
     * Живёт в том же ответе «Работы фазы»: цепочка одна, копии у рамки нет.
     */
    @Test
    fun `нить потока называет вход, выход и потребителей`() {
        val items = PhaseWork.toJson(boundary, "PJ-2001").path("items").associateBy { it.path("id").asText() }
        val первая = items.getValue("PW-9001").path("flow")
        assertEquals("Д2 · Постановка", первая.path("out").path("artifact").asText())
        assertEquals("не готов", первая.path("out").path("state").asText())
        val ждут = первая.path("consumers").map { it.path("name").asText() }
        assertTrue("Концепция" in ждут) { "видно, кого кормишь: $ждут" }
        assertTrue(ждут.any { it.contains("MCR") }) { "и к какой точке зреет выход: $ждут" }

        val вторая = items.getValue("PW-9002").path("flow")
        val вход = вторая.path("in").first { it.path("kind").asText() == "task" }
        assertEquals("PW-9001", вход.path("id").asText())
        assertFalse(вход.path("ready").asBoolean()) { "вход ещё не готов — и это видно в нити" }
    }
}
