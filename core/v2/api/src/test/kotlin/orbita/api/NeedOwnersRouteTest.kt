// Нужда — много носителей (шип 4 §1.4, ОНТОЛОГИЯ-АУДИТ часть 4).
//
// Одна формулировка — одна нужда, носителей много: маршрут
// `POST /v2/needs/{code}/owner` ДОБАВЛЯЕТ сторону к носителям, `remove`
// снимает её, `replace` делает единственной; последнего носителя снять
// нельзя. Поле `stakeholders` записи — зеркало связей owns, его ставит
// система, и оно обязано сходиться со связями после каждого хода.
package orbita.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.api.internal.V2Router
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.KernelFactory
import orbita.library.api.LibraryFactory
import orbita.process.api.ProcessFactory
import orbita.readiness.api.ReadinessFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NeedOwnersRouteTest {
    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val шаблон = mapper.readTree(
        TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые/ШАБЛОН-ФАЗЫ-PRE-A-NASA.json").toFile(),
    )
    private val проект = "PJ-9811"
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
        router.handle("POST", "/v2/projects", emptyMap(), """{"name":"Носители нужды","code":"$проект","author":"Чернов Д."}""")
    }

    private fun сторона(имя: String): String =
        router.handle("POST", "/v2/stakeholders", п, """{"name":"$имя","role":"customer","author":"Иванов И."}""")!!
            .body.path("code").asText()

    private fun нужда(формулировка: String, вот: String): String =
        router.handle("POST", "/v2/needs", п, """{"statement":"$формулировка","owner":"$вот","author":"Иванов И."}""")!!
            .body.path("code").asText()

    private fun носитель(нужда: String, тело: String) =
        router.handle("POST", "/v2/needs/$нужда/owner", п, тело)!!

    /** Носители по связям owns — истина; поле записи обязано её повторять. */
    private fun поСвязям(нужда: String): Set<String> {
        val запись = store.byCode(область, нужда)!!
        return links.to(запись.id, "owns").map { store.byId(it.from)!!.code }.toSet()
    }

    private fun поПолю(нужда: String): Set<String> =
        store.byCode(область, нужда)!!.doc.path("stakeholders").map { store.byId(it.asText())!!.code }.toSet()

    @Test
    fun `сторона добавляется к носителям, а не заменяет их — поле stakeholders зеркалит связи`() {
        val минтранс = сторона("Минтранс России")
        val росморпорт = сторона("Росморпорт")
        val нд = нужда("Непрерывный мониторинг судов на СМП", минтранс)
        assertEquals(setOf(минтранс), поСвязям(нд))
        assertEquals(setOf(минтранс), поПолю(нд), "поле ставит система при заведении")

        val ответ = носитель(нд, """{"owner":"$росморпорт","author":"Иванов И."}""")
        assertEquals(200, ответ.code)
        assertEquals(listOf(минтранс, росморпорт), ответ.body.path("owners").map { it.asText() }.sorted())
        assertEquals(setOf(минтранс, росморпорт), поСвязям(нд), "первый носитель остался")
        assertEquals(поСвязям(нд), поПолю(нд), "поле сходится со связями")

        // повтор той же стороны связи не дублирует
        носитель(нд, """{"owner":"$росморпорт","author":"Иванов И."}""")
        assertEquals(2, links.to(store.byCode(область, нд)!!.id, "owns").size)
    }

    @Test
    fun `сторону можно назвать именем, а не кодом`() {
        val минтранс = сторона("Минтранс России")
        val росморпорт = сторона("Росморпорт")
        val нд = нужда("Ледовая обстановка в реальном времени", минтранс)
        val ответ = носитель(нд, """{"owner":"росморпорт","author":"Иванов И."}""")
        assertEquals(росморпорт, ответ.body.path("owner").asText())
        assertEquals(setOf(минтранс, росморпорт), поПолю(нд))
    }

    @Test
    fun `remove снимает сторону — последнего носителя снять нельзя`() {
        val минтранс = сторона("Минтранс России")
        val росморпорт = сторона("Росморпорт")
        val нд = нужда("Связь с судами вне зоны берега", минтранс)
        носитель(нд, """{"owner":"$росморпорт","author":"Иванов И."}""")

        val ответ = носитель(нд, """{"owner":"$минтранс","remove":true,"author":"Иванов И."}""")
        assertEquals(listOf(росморпорт), ответ.body.path("owners").map { it.asText() })
        assertEquals(setOf(росморпорт), поСвязям(нд))
        assertEquals(setOf(росморпорт), поПолю(нд))

        val отказ = assertFailsWith<IllegalArgumentException> {
            носитель(нд, """{"owner":"$росморпорт","remove":true,"author":"Иванов И."}""")
        }
        assertTrue(отказ.message!!.contains("хотя бы один носитель"), отказ.message)
        assertEquals(setOf(росморпорт), поСвязям(нд), "после отказа носитель на месте")
    }

    @Test
    fun `replace делает сторону единственным носителем`() {
        val минтранс = сторона("Минтранс России")
        val росморпорт = сторона("Росморпорт")
        val мчс = сторона("МЧС России")
        val нд = нужда("Оповещение о ЧС на трассе", минтранс)
        носитель(нд, """{"owner":"$росморпорт","author":"Иванов И."}""")

        val ответ = носитель(нд, """{"owner":"$мчс","replace":true,"author":"Иванов И."}""")
        assertEquals(listOf(мчс), ответ.body.path("owners").map { it.asText() })
        assertEquals(setOf(мчс), поСвязям(нд))
        assertEquals(setOf(мчс), поПолю(нд))
    }

    @Test
    fun `нужда заводится сразу с несколькими носителями списком owners`() {
        val минтранс = сторона("Минтранс России")
        val росморпорт = сторона("Росморпорт")
        val ответ = router.handle("POST", "/v2/needs", п,
            """{"statement":"Единая картина обстановки","owners":["$минтранс","$росморпорт"],"author":"Иванов И."}""")!!
        assertEquals(201, ответ.code)
        val нд = ответ.body.path("code").asText()
        assertEquals(setOf(минтранс, росморпорт), поСвязям(нд))
        assertEquals(setOf(минтранс, росморпорт), поПолю(нд))
    }

    @Test
    fun `неизвестная сторона и чужой вид отбиваются словами`() {
        val минтранс = сторона("Минтранс России")
        val нд = нужда("Мониторинг", минтранс)
        val нет = assertFailsWith<IllegalArgumentException> {
            носитель(нд, """{"owner":"Пентагон","author":"Иванов И."}""")
        }
        assertTrue(нет.message!!.contains("нет в проекте"), нет.message)
        val неНужда = assertFailsWith<IllegalArgumentException> {
            носитель(минтранс, """{"owner":"$минтранс","author":"Иванов И."}""")
        }
        assertTrue(неНужда.message!!.contains("не нужда"), неНужда.message)
    }
}
