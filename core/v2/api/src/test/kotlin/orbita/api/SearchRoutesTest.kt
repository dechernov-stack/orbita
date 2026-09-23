// Поиск по базе знаний (шип 4 §2): маршрут перестраивает индекс и отвечает
// находками словами — вид блока, откуда, отрывок; без вектора говорит об этом.
package orbita.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.api.internal.SearchRoutes
import orbita.api.internal.V2Router
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.Provenance
import orbita.library.api.LibraryFactory
import orbita.process.api.ProcessFactory
import orbita.readiness.api.ReadinessFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchRoutesTest {
    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val шаблон = mapper.readTree(TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые/ШАБЛОН-ФАЗЫ-PRE-A-NASA.json").toFile())
    private val проект = "PJ-9814"
    private val область = Area.Project(проект)
    private val п = mapOf("project" to проект)
    private val пров = Provenance(Channel.MANUAL, "Иванов И.")

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
            searchRoutes = SearchRoutes(
                orbita.knowledge.api.KnowledgeFactory.index(store, KernelFactory.textIndex(TestDbV2.conn, mapper), null, mapper), mapper,
            ),
        )
    }

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        router.handle("POST", "/v2/projects", emptyMap(), """{"name":"Поиск","code":"$проект","author":"Чернов Д."}""")
        store.create("NR-2216", "normative_document", Area.Library, null, mapper.readTree(
            """{"designation":"ПП РФ № 2216","title":"О передаче данных","clauses":[{"clause":"обязанность","text":"передавать координаты с интервалом не более 30 с"}]}"""), пров)
    }

    @Test
    fun `перестроение и поиск словами — находки с видом и отрывком, без вектора сказано прямо`() {
        val сводка = router.handle("POST", "/v2/index/rebuild", п, null)!!
        assertEquals(200, сводка.code)
        assertTrue(сводка.body.path("blocks").asInt() >= 1, сводка.body.toString())
        val ответ = router.handle("GET", "/v2/search", п + ("q" to "интервал"), null)!!
        assertEquals(200, ответ.code)
        val находки = ответ.body.path("items")
        assertEquals("пункт норматива", находки[0].path("kind_word").asText())
        assertEquals("NR-2216", находки[0].path("ref").asText())
        assertTrue("интервал" in находки[0].path("snippet").asText())
        assertEquals(false, ответ.body.path("vector").asBoolean(true) && ответ.body.path("note").asText().contains("гибридно"), "без провайдера — не гибридно")
        val пусто = router.handle("GET", "/v2/search", п, null)!!.body
        assertEquals(0, пусто.path("items").size())
        assertTrue("назовите" in пусто.path("note").asText())
    }
}
