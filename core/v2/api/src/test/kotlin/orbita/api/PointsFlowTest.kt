// Точки через маршруты (шип D): замечание возвращает сцену, роль
// отказывает с именами, решение переживает пересборку роутера — потому что
// живёт в реестре, а не в памяти api.
package orbita.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.api.api.Actor
import orbita.api.internal.GateRecords
import orbita.api.internal.PointRoutes
import orbita.api.internal.V2Router
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.LinkRegistry
import orbita.library.api.LibraryFactory
import orbita.process.api.GateHeldException
import orbita.process.api.ProcessFactory
import orbita.process.api.RoleRefusedException
import orbita.readiness.api.ReadinessFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PointsFlowTest {
    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links: LinkRegistry = KernelFactory.linkRegistry(TestDbV2.conn)
    private val шаблон = mapper.readTree(
        TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые/ШАБЛОН-ФАЗЫ-PRE-A-NASA.json").toFile(),
    )
    private val проект = "PJ-9401"
    private val п = mapOf("project" to проект)
    private val рп = Actor("chernov", "Чернов Д.", setOf("lead", "da_review"))
    private val инженер = Actor("petrova", "Петрова М.", setOf("specialist"))

    /** Роутер собирается заново на каждый вызов: память api ничего не помнит. */
    private fun router(): V2Router {
        val записи = GateRecords(store, mapper)
        val движок = ProcessFactory.engine(
            template = { шаблон },
            evaluator = ReadinessFactory.gateEvaluator(
                store, links, scenesDone = { emptySet() }, gatesPassed = { записи.passed(it) },
            ),
            passedGates = { записи.passed(it) },
            gatePlan = { emptyMap() },
            findings = { записи.findings(it) },
            decisions = { записи.decisions(it) },
            phaseOf = { записи.phaseOf(it) },
            onDecision = { p, т, кем, исход, помета, фаза -> записи.record(p, т, кем, исход, помета, фаза) },
        )
        return V2Router(
            store, links, движок,
            LibraryFactory.shelves(store) { шаблон },
            orbita.knowledge.api.KnowledgeFactory.intake(store, links, mapper),
            orbita.formulation.api.FormulationFactory.formulation(store, links),
            mapper,
            pointRoutes = PointRoutes(движок, записи, mapper),
        )
    }

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        router().handle("POST", "/v2/projects", emptyMap(), """{"name":"Точки","code":"$проект","author":"Чернов Д."}""")
        router().handle("POST", "/v2/intent", п,
            """{"for_whom":"перевозчики","what":"телеметрия","where":"СМП","horizon":"2033","accepted":true}""")
    }

    private fun сцена(фаза: JsonNode, ключ: String) = фаза.path("scenes").single { it.path("key").asText() == ключ }
    private fun точка(ключ: String): JsonNode =
        router().handle("GET", "/v2/points", п, null)!!.body.path("items").single { it.path("key").asText() == ключ }

    @Test
    fun `замечание возвращает прожитую сцену в работу и закрывается работой обзора`() {
        assertEquals("done", сцена(router().handle("GET", "/v2/phase", п, null)!!.body, "2").path("state").asText())
        val заведено = router().handle("POST", "/v2/points/internal_review/findings", п,
            """{"text":"замысел не называет горизонт","returns_to_scene":"2","question":"Полностью ли определена миссия?"}""", рп)!!
        assertEquals(201, заведено.code)
        assertEquals("FND-0001", заведено.body.path("code").asText())
        val фаза = router().handle("GET", "/v2/phase", п, null)!!.body
        assertEquals("open", сцена(фаза, "2").path("state").asText(), "сцена 2 снова в работе — событием, не флагом")
        assertTrue(сцена(фаза, "2").path("blockers").any { "горизонт" in it.asText() })
        assertEquals(1, точка("internal_review").path("findings").size())

        val закрыто = router().handle("POST", "/v2/findings/FND-0001/close", п, """{"note":"горизонт дописан"}""", рп)!!
        assertEquals("closed", закрыто.body.path("status").asText())
        assertEquals("done", сцена(router().handle("GET", "/v2/phase", п, null)!!.body, "2").path("state").asText())
    }

    @Test
    fun `инженер не заводит замечание и не фиксирует точку — отказ с именами`() {
        val отказ = assertFailsWith<RoleRefusedException> {
            router().handle("POST", "/v2/points/internal_review/findings", п,
                """{"text":"что-то не так","returns_to_scene":"2"}""", инженер)
        }
        assertTrue("Петрова М." in отказ.message!!, отказ.message)
        val фиксация = assertFailsWith<RoleRefusedException> {
            router().handle("POST", "/v2/points/internal_review/decide", п, """{"outcome":"approve"}""", инженер)
        }
        assertTrue("руководитель проекта" in фиксация.message!! && "Петрова М." in фиксация.message!!, фиксация.message)
    }

    @Test
    fun `решение точки переживает пересборку роутера, MCR держится критериями`() {
        // внутренний обзор: план работ фазы у первой доступной сцены
        router().handle("POST", "/v2/plan", п,
            """{"gate_dates":[{"gate":"internal_review","date":"2026-10-05"},{"gate":"MCR","date":"2026-11-05"},{"gate":"KDP-A","date":"2026-12-05"}],"scene_windows":[{"scene":"1","start":"2026-09-10","end":"2026-09-11"},{"scene":"2","start":"2026-09-11","end":"2026-09-15"},{"scene":"3","start":"2026-09-15","end":"2026-09-25"},{"scene":"4","start":"2026-09-25","end":"2026-10-01"}],"author":"Чернов Д."}""")
        val до = точка("internal_review")
        assertTrue(!до.path("passed").asBoolean(), "до решения точка не пройдена: ${до.path("blocking")}")
        val решение = router().handle("POST", "/v2/points/internal_review/decide", п, """{"outcome":"approve","note":"к MCR готовы"}""", рп)!!
        assertEquals(200, решение.code, решение.body.toString())
        val после = точка("internal_review")
        assertTrue(после.path("passed").asBoolean(), "новый роутер читает решение из реестра")
        assertEquals("approve", после.path("decision").path("outcome").asText())
        assertEquals("Чернов Д.", после.path("decision").path("by").asText())

        val держит = assertFailsWith<GateHeldException> {
            router().handle("POST", "/v2/points/MCR/decide", п, """{"outcome":"approve"}""", рп)
        }
        assertTrue("сцена 3" in держит.message!!, держит.message)
        assertEquals("draft", store.byCode(Area.Project(проект), проект)!!.doc.path("phase").asText("draft"), "фаза не менялась")
    }

    @Test
    fun `return без замечаний — отказ, с замечанием точка возвращена`() {
        val отказ = assertFailsWith<IllegalArgumentException> {
            router().handle("POST", "/v2/points/internal_review/decide", п, """{"outcome":"return"}""", рп)
        }
        assertTrue("замечани" in отказ.message!!, отказ.message)
        router().handle("POST", "/v2/points/internal_review/findings", п, """{"text":"нужен горизонт","returns_to_scene":"2"}""", рп)
        router().handle("POST", "/v2/points/internal_review/decide", п, """{"outcome":"return"}""", рп)
        assertEquals("returned", store.byCode(Area.Project(проект), "internal_review")!!.status)
    }
}
