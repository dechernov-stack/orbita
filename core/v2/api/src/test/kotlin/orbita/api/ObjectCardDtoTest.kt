// Карточка объекта (шип 5 §1.3) читает истину: виджет поля и виды ссылок —
// у вида; «версия N · кто · когда» — у строки перечня; история — отдельным
// чтением. Только чтение: маршрутов записи шип не добавляет.
package orbita.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.api.api.Actor
import orbita.api.internal.V2Router
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.Provenance
import orbita.knowledge.api.KnowledgeFactory
import orbita.library.api.LibraryFactory
import orbita.process.api.ProcessFactory
import orbita.readiness.api.ReadinessFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObjectCardDtoTest {
    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val проект = "PJ-9880"
    private val п = mapOf("project" to проект)
    private val си = Actor("ivanov", "Иванов И.", setOf("lead_se"))
    private val шаблон = mapper.readTree(TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые/ШАБЛОН-ФАЗЫ-PRE-A-NASA.json").toFile())

    private val роутер: V2Router by lazy {
        val движок = ProcessFactory.engine(
            template = { шаблон },
            evaluator = ReadinessFactory.gateEvaluator(store, links, scenesDone = { emptySet() }, gatesPassed = { emptySet() }),
            passedGates = { mutableSetOf() }, gatePlan = { emptyMap() },
        )
        V2Router(store, links, движок, LibraryFactory.shelves(store) { шаблон }, KnowledgeFactory.intake(store, links, mapper),
            orbita.formulation.api.FormulationFactory.formulation(store, links), mapper)
    }

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        роутер.handle("POST", "/v2/projects", emptyMap(), """{"name":"Карточка объекта","code":"$проект","author":"Чернов Д."}""")
    }

    @Test
    fun `вид несёт виджеты полей и виды ссылок из истины`() {
        val вид = роутер.handle("GET", "/v2/kinds/requirement", emptyMap(), null)!!.body
        assertEquals("measure", вид.path("widgets").path("measure").asText())
        assertEquals("text", вид.path("widgets").path("statement").asText())
        assertEquals("enum", вид.path("widgets").path("level").asText())
        assertEquals(listOf("component", "interface", "scenario"), вид.path("ref_kinds").path("carrier").map { it.asText() })
        val сторона = роутер.handle("GET", "/v2/kinds/stakeholder", emptyMap(), null)!!.body
        assertEquals("computed", сторона.path("widgets").path("influence").asText(), "влияние считает система — только чтение")
        assertEquals("table", сторона.path("widgets").path("interest").asText())
    }

    @Test
    fun `подсказка поля для показа — коды полей и значений словами истины, сама истина как есть`() {
        // Ответ владельца 26.09 (вопрос §7 шипа 5): код поля в примечании — его label.
        val узел = роутер.handle("GET", "/v2/kinds/component", emptyMap(), null)!!.body
        val род = узел.path("note_words").path("nature").asText()
        assertTrue("поведение (ПО)" in род && "узел (железо)" in род, род)
        assertTrue("behaviour" !in род && " node" !in род, род)
        assertTrue("behaviour" in узел.path("notes").path("nature").asText(), "истина не меняется: примечание как есть")
        val проект = роутер.handle("GET", "/v2/kinds/project", emptyMap(), null)!!.body
        assertTrue("«Текущая фаза»" in проект.path("note_words").path("phase_template").asText(), проект.path("note_words").toString())
        val риск = роутер.handle("GET", "/v2/kinds/risk", emptyMap(), null)!!.body
        val срок = риск.path("note_words").path("due_point").asText()
        assertTrue("gate.kind" !in срок && "(точка / ворота)" in срок, срок)
    }

    @Test
    fun `строка перечня несёт версию, автора и время, история — версии с изменёнными полями`() {
        val запись = store.create("SK-0001", "stakeholder", Area.Project(проект), "3",
            mapper.createObjectNode().put("name", "Минтранс России").put("role", "customer"), Provenance(Channel.MANUAL, "Петрова М."), status = "accepted")
        val правка = роутер.handle("PATCH", "/v2/entities/SK-0001", п, """{"fields":{"power":4},"author":"Иванов И.","reason":"сила стороны"}""", си)!!
        assertEquals(200, правка.code, правка.body.toString())
        val строки = роутер.handle("GET", "/v2/entities", п + ("kind" to "stakeholder"), null)!!.body.path("items")
        val строка = строки.single()
        assertEquals(2, строка.path("version").asInt())
        assertTrue(строка.path("author").asText().isNotBlank())
        assertTrue(строка.path("updated_at").asText().startsWith("20"))
        assertEquals("manual", строка.path("channel").asText(), "канал провенанса — своё, не с полки")
        val история = роутер.handle("GET", "/v2/entities/SK-0001/history", п, null)!!.body
        assertEquals(listOf(1, 2), история.path("items").map { it.path("version").asInt() })
        assertEquals("Петрова М.", история.path("items")[0].path("author").asText())
        assertEquals(listOf("power"), история.path("items")[1].path("changed").map { it.asText() })
        assertTrue(запись.id.isNotBlank())
    }
}
