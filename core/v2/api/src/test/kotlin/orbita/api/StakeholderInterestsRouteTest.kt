// Интерес — отдельное понятие у стороны (шип 4 §1.5): маршруты заводят и правят
// интересы списком, как бы их ни принесли — строкой экрана или перечнем.
package orbita.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.api.internal.V2Router
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.KernelFactory
import orbita.kernel.schema.Interests
import orbita.library.api.LibraryFactory
import orbita.process.api.ProcessFactory
import orbita.readiness.api.ReadinessFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StakeholderInterestsRouteTest {
    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val шаблон = mapper.readTree(
        TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые/ШАБЛОН-ФАЗЫ-PRE-A-NASA.json").toFile(),
    )
    private val проект = "PJ-9812"
    private val область = Area.Project(проект)
    private val п = mapOf("project" to проект)

    private val router: V2Router by lazy {
        V2Router(
            store, links,
            ProcessFactory.engine(
                template = { шаблон },
                evaluator = ReadinessFactory.gateEvaluator(store, links, scenesDone = { emptySet() }, gatesPassed = { emptySet() }),
                passedGates = { mutableSetOf() }, gatePlan = { emptyMap() },
            ),
            LibraryFactory.shelves(store) { шаблон },
            orbita.knowledge.api.KnowledgeFactory.intake(store, links, mapper),
            orbita.formulation.api.FormulationFactory.formulation(store, links), mapper,
        )
    }

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        router.handle("POST", "/v2/projects", emptyMap(), """{"name":"Интересы сторон","code":"$проект","author":"Чернов Д."}""")
    }

    @Test
    fun `строка экрана и перечень эталона ложатся списком интересов`() {
        val строкой = router.handle("POST", "/v2/stakeholders", п,
            """{"name":"Минтранс России","role":"customer","interest":"телематика; контроль исполнения","author":"Иванов И."}""")!!
        assertEquals(201, строкой.code)
        val минтранс = store.byCode(область, строкой.body.path("code").asText())!!
        assertEquals(listOf("телематика", "контроль исполнения"), Interests.словами(минтранс.doc.path("interest")))

        val перечнем = router.handle("POST", "/v2/stakeholders", п,
            """{"name":"Росавиация","role":"regulator","interest":["прозрачность полётов","идентификация вне покрытия"],"author":"Иванов И."}""")!!
        val росавиация = store.byCode(область, перечнем.body.path("code").asText())!!
        assertEquals(listOf("прозрачность полётов", "идентификация вне покрытия"), Interests.словами(росавиация.doc.path("interest")))
    }

    @Test
    fun `правка одной строкой сохраняет цитату нетронутого интереса`() {
        val код = router.handle("POST", "/v2/stakeholders", п,
            """{"name":"Минтранс России","role":"customer","interest":"телематика","author":"Иванов И."}""")!!
            .body.path("code").asText()
        // Цитата приходит от документа (§3); здесь она подложена прямо в запись.
        val запись = store.byCode(область, код)!!
        val сЦитатой = запись.doc.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        сЦитатой.putArray("interest").add(mapper.createObjectNode().put("statement", "телематика").put("quote", "…требования к транспортной телематике…"))
        store.update(запись.id, сЦитатой, orbita.kernel.api.Provenance(orbita.kernel.api.Channel.MANUAL, "тест"))

        router.handle("PATCH", "/v2/entities/$код", п, """{"fields":{"interest":"телематика; ледовая обстановка"},"author":"Иванов И."}""")!!
        val после = Interests.прочитать(store.byCode(область, код)!!.doc.path("interest"))
        assertEquals(listOf("телематика", "ледовая обстановка"), после.map { it.statement })
        assertEquals("…требования к транспортной телематике…", после[0].quote, "цитата нетронутого интереса цела")
        assertNull(после[1].quote)
    }
}
