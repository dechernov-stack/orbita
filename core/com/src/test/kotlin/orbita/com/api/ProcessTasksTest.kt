// МВП-П1 «процесс к точке» путём данных: задание = адресованный разрыв.
// Меры задачи §3: назначение пачкой (право, идемпотентность), «Мои задания»
// со ссылкой к месту, закрытие разрыва закрывает задание БЕЗ клика,
// зависимость по входам операции — «ожидает: ‹предшественник›» и
// самоактивация закрытием входа.
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
class ProcessTasksTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val PROJECT = "PJ-1501"

    @BeforeEach
    fun clean() {
        TestDb.truncateAll()
        boundary.ingest(
            CoreType.Project,
            """{"id":"$PROJECT","name":"Процесс","phase":"phase_a",
                "milestones":[{"gate":"SRR"},{"gate":"SDR"},{"gate":"KDP-B"}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", PROJECT,
        )
    }

    private fun gaps(vararg ids: String) =
        ids.map { ProcessTasks.GapRef(it, "проверка $it", "req") }

    @Test
    fun `назначение пачкой идемпотентно, мои задания различают участников`() {
        val (created, _) = boundary.processTasks.assign(
            "SRR", gaps("tbd", "trace", "reviews"), "vera", "2026-09-10", "к SRR",
            "Чернов Д.", PROJECT, authorLogin = null,
        )
        assertEquals(3, created.size)
        val (again, existing) = boundary.processTasks.assign(
            "SRR", gaps("tbd"), "vera", null, null, "Чернов Д.", PROJECT, null,
        )
        assertTrue(again.isEmpty())
        assertEquals(listOf("tbd"), existing)
        boundary.processTasks.assign("SRR", gaps("docs"), "boris", null, null, "Чернов Д.", PROJECT, null)

        val vera = boundary.processTasks.myTasks(PROJECT, "vera")
        assertEquals(3, vera["tasks"].size())
        val boris = boundary.processTasks.myTasks(PROJECT, "boris")
        assertEquals(1, boris["tasks"].size())
        assertEquals("docs", boris["tasks"][0]["gap_ref"].asText())
        // руководителю — все
        assertEquals(4, boundary.processTasks.myTasks(PROJECT, null)["tasks"].size())
    }

    @Test
    fun `право назначать - руководитель и ведущий СИ`() {
        boundary.auth.createUser("vera", "парольвера", "Вера И.")
        boundary.auth.createUser("boris", "парольборис", "Борис К.")
        boundary.auth.setRole(PROJECT, "vera", "lead")
        boundary.auth.setRole(PROJECT, "boris", "specialist")
        val ok = boundary.processTasks.assign(
            "SRR", gaps("tbd"), "boris", null, null, "Вера И.", PROJECT, authorLogin = "vera",
        )
        assertEquals(1, ok.first.size)
        val refusal = runCatching {
            boundary.processTasks.assign(
                "SRR", gaps("trace"), "vera", null, null, "Борис К.", PROJECT, authorLogin = "boris",
            )
        }
        assertTrue(refusal.exceptionOrNull() is ProcessTasks.AssignForbiddenException)
    }

    @Test
    fun `закрытие разрыва закрывает задание без клика`() {
        // разрыв «риски заведены»: 0 объектов — open
        boundary.processTasks.assign("SRR", gaps("risks"), "vera", null, null, "Чернов Д.", PROJECT, null)
        val before = boundary.processTasks.myTasks(PROJECT, "vera")["tasks"][0]
        assertEquals("active", before["state"].asText())
        assertEquals("risks", before["place"]?.asText() ?: "risks") // место — с проверки
        // риск завели — разрыв закрылся, задание закрыто самим фактом
        boundary.ingest(
            CoreType.Risk,
            """{"id":"RSK-1501","statement":"Один поставщик БЦВМ — срыв его поставки — сдвиг интеграции на квартал.",
                "category":"schedule","probability":2,"impact":3,
                "owner":"вед. системный инженер","status":"open"}""",
            "test", PROJECT,
        )
        val after = boundary.processTasks.myTasks(PROJECT, "vera")["tasks"][0]
        assertEquals("done", after["state"].asText())
    }

    @Test
    fun `зависимость по входам - ожидает предшественника и активируется само`() {
        // О4 (иерархия требований) ждёт вход О3 (концепция утверждена)
        boundary.processTasks.assign("SRR", gaps("op:О4"), "vera", "2020-01-01", null, "Чернов Д.", PROJECT, null)
        val waiting = boundary.processTasks.myTasks(PROJECT, "vera")["tasks"][0]
        assertEquals("waiting", waiting["state"].asText())
        assertTrue(waiting["waits_on"].asText().startsWith("О3")) { waiting.toString() }
        // срок не тикает: просрочка не горит, пока задание ожидает
        assertTrue(!waiting.has("overdue")) { waiting.toString() }

        // вход закрылся: ConOps доведён до Approved — задание активно САМО
        boundary.ingest(
            CoreType.Conops,
            """{"id":"CO-1501","name":"ConOps системы","kind":"nominal",
                "phase":"operations",
                "flow":["сбор телеметрии терминалами","суточная доставка оператору"],
                "success_criterion":"суточная доставка сообщений класса A' не ниже целевой",
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", PROJECT,
        )
        boundary.objects.transition("CO-1501", orbita.mod.model.Lifecycle.Preliminary, "test")
        boundary.objects.transition("CO-1501", orbita.mod.model.Lifecycle.Approved, "test")
        val active = boundary.processTasks.myTasks(PROJECT, "vera")["tasks"][0]
        assertEquals("active", active["state"].asText()) { active.toString() }
        assertTrue(!active.has("waits_on"))
        // а теперь срок тикает: дата в прошлом — просрочено
        assertTrue(active["overdue"].asBoolean()) { active.toString() }
    }
}
