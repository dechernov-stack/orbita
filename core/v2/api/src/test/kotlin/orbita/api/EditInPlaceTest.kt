// З-03 (ЗАМЕЧАНИЯ-ПМИ5-СЦЕНА-3): принятое редактируется на месте — новая
// версия с провенансом «правка инженера», связи и код целы; поле вне схемы
// и служебные виды — отказ словами; пустое значение снимает поле.
package orbita.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.api.api.Actor
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EditInPlaceTest {
    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val полки = TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые")
    private val шаблон = mapper.readTree(полки.resolve("ШАБЛОН-ФАЗЫ-PRE-A-NASA.json").toFile())
    private val проект = "PJ-9809"
    private val область = Area.Project(проект)
    private val провенанс = Provenance(Channel.MANUAL, "Чернов Д.")
    private val п = mapOf("project" to проект)
    private val си = Actor("ivanov", "Иванов И.", setOf("lead_se"))

    private fun router(): V2Router {
        val движок = ProcessFactory.engine(
            template = { шаблон },
            evaluator = ReadinessFactory.gateEvaluator(store, links, scenesDone = { emptySet() }, gatesPassed = { emptySet() }),
            passedGates = { mutableSetOf() }, gatePlan = { emptyMap() },
        )
        return V2Router(
            store, links, движок, LibraryFactory.shelves(store) { шаблон },
            orbita.knowledge.api.KnowledgeFactory.intake(store, links, mapper),
            orbita.formulation.api.FormulationFactory.formulation(store, links), mapper,
        )
    }

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        router().handle("POST", "/v2/projects", emptyMap(), """{"name":"Правка на месте","code":"$проект","author":"Чернов Д."}""")
    }

    @Test
    fun `сторона и нужда правятся на месте — новая версия, провенанс правки, связь носителя цела`() {
        val r = router()
        r.handle("POST", "/v2/stakeholders", п, """{"name":"Минтранс России","role":"customer","interest":"телематика","author":"Иванов И."}""", си)
        r.handle("POST", "/v2/needs", п, """{"statement":"Непрерывный мониторинг","owner":"SK-0001","author":"Иванов И."}""", си)
        val сторона = store.byCode(область, "SK-0001")!!
        assertEquals(1, сторона.version)

        val ответ = r.handle("PATCH", "/v2/entities/SK-0001", п,
            """{"fields":{"name":"Минтранс России / Ространснадзор","influence":"decides","power":5,"interest":""},"author":"Иванов И.","reason":"по пакету сцены 3"}""", си)!!
        assertEquals(200, ответ.code, ответ.body.toString())
        assertEquals(2, ответ.body.path("version").asInt())
        assertEquals(4, ответ.body.path("changed").asInt(), "имя, влияние, сила; интерес снят — тоже правка")
        val после = store.byCode(область, "SK-0001")!!
        assertEquals("Минтранс России / Ространснадзор", после.doc.path("name").asText())
        assertEquals("decides", после.doc.path("influence").asText())
        assertEquals(5, после.doc.path("power").asInt())
        assertTrue(после.doc.get("interest") == null, "пустое значение снимает поле")
        assertEquals(сторона.id, после.id, "id стабилен — связи живут")
        assertTrue(после.provenance.author.startsWith("правка инженера: Иванов И."), после.provenance.author)
        assertTrue(после.provenance.author.contains("по пакету сцены 3"))
        val нужда = store.byCode(область, "ND-0001")!!
        assertEquals(listOf(после.id), links.to(нужда.id, "owns").map { it.from }, "носитель нужды остался")

        val нуждаПосле = r.handle("PATCH", "/v2/entities/ND-0001", п, """{"fields":{"statement":"Непрерывный мониторинг вне наземного покрытия"},"author":"Иванов И."}""", си)!!
        assertEquals(2, нуждаПосле.body.path("version").asInt())
        // повтор того же — версия не плодится
        val повтор = r.handle("PATCH", "/v2/entities/ND-0001", п, """{"fields":{"statement":"Непрерывный мониторинг вне наземного покрытия"},"author":"Иванов И."}""", си)!!
        assertEquals(0, повтор.body.path("changed").asInt())
        assertEquals(2, store.byCode(область, "ND-0001")!!.version)
    }

    @Test
    fun `поле вне схемы, служебный вид и смена кода — отказ словами, версия не плодится`() {
        val r = router()
        r.handle("POST", "/v2/stakeholders", п, """{"name":"ГКРЧ","role":"regulator","interest":"частоты","author":"Иванов И."}""", си)
        assertFailsWith<Exception>("поле вне схемы") {
            r.handle("PATCH", "/v2/entities/SK-0001", п, """{"fields":{"colour":"red"},"author":"Иванов И."}""", си)
        }
        assertEquals(1, store.byCode(область, "SK-0001")!!.version, "отказ схемы не плодит версию")
        val е = assertFailsWith<IllegalArgumentException> {
            r.handle("PATCH", "/v2/entities/SK-0001", п, """{"fields":{"code":"SK-0099"},"author":"Иванов И."}""", си)
        }
        assertTrue("код и вид стабильны" in е.message!!, е.message)
        val е2 = assertFailsWith<IllegalArgumentException> {
            r.handle("PATCH", "/v2/entities/$проект", п, """{"fields":{"name":"Другое имя"},"author":"Иванов И."}""", си)
        }
        assertTrue("на месте не правятся" in е2.message!!, е2.message)
    }
}
