// Маршруты A4/A5 (ПМИ-5): функции, обмены и элементы обмена, бюджеты,
// логические компоненты — записями реестра со ссылками кодами; ссылка в
// пустоту отказывает до записи; условия сцен читают их.
package orbita.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.api.internal.ArchRoutes
import orbita.architecture.api.ArchitectureFactory
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.Provenance
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArchFeedTest {
    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val проект = "PJ-9805"
    private val область = Area.Project(проект)
    private val п = Provenance(Channel.MANUAL, "Чернов Д.")
    private val архитектура = ArchitectureFactory.architecture(store, links, mapper)
    private val маршруты = ArchRoutes(store, архитектура, mapper)
    private val проверки = ArchitectureFactory.gateChecks(store, архитектура)
    private val q = mapOf("project" to проект)

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        store.create(проект, "project", область, "1", mapper.readTree("""{"name":"Корм A4/A5","standard":"NASA-7120","phase":"Phase A"}"""), п)
        store.create("EL-SC", "component", область, "7", mapper.readTree("""{"name":"КА","kind":"element","level":2,"nature":"node"}"""), п)
        store.create("EL-UT", "component", область, "7", mapper.readTree("""{"name":"Терминал","kind":"element","level":2,"nature":"node"}"""), п)
        маршруты.handle("POST", "/v2/interfaces", q, """{"code":"IF-S-USER","name":"КА — терминал","type":"rf","a":"EL-SC","b":"EL-UT","direction":"both","requirement_classes":["interface"]}""")
    }

    @Test
    fun `каркас состава берётся с полки по её же правилу, а повтор ничего не удваивает`() {
        // Проход владельца 18.09: сцена 7 говорила «состав берётся каркасом
        // класса миссии», а дороги взять его не было — узлы набивались руками.
        // Правило взятия живёт в самой полке: «уровни 0–3 всегда».
        store.create(
            "PBS-9002", "pbs_template", Area.Library, null,
            mapper.readTree(
                """{"rules":["взятие создаёт уровни 0–3 всегда, 4–5 — по классу миссии"],"nodes":[
                {"code":"SYS","parent":null,"name":"Система","kind":"system","level":0},
                {"code":"SEG-SP","parent":"SYS","name":"Космический сегмент","kind":"segment","level":1},
                {"code":"SC","parent":"SEG-SP","name":"Космический аппарат","kind":"element","level":2},
                {"code":"OBC-SW","parent":"SC","name":"ПО БКУ","kind":"unit","level":5}]}""",
            ),
            п,
        )

        val что = assertNotNull(маршруты.handle("GET", "/v2/components/frame", q, null))
        assertEquals("PBS-9002", что.body.path("shelf").asText())
        assertEquals(3, что.body.path("levels").asInt(), "глубина взята из правила полки, а не из кода")
        assertEquals(3, что.body.path("nodes").asInt(), "узел пятого уровня в каркас не идёт")

        val взято = assertNotNull(маршруты.handle("POST", "/v2/components/frame", q, """{"author":"инженер"}"""))
        assertEquals(201, взято.code, взято.body.toString())
        assertEquals(3, взято.body.path("created").asInt(), взято.body.toString())

        val состав = store.list(область, "component").associateBy { it.code }
        // В проекте уже были свои узлы (EL-SC, EL-UT) — каркас их не трогает.
        assertTrue(состав.keys.containsAll(setOf("SYS", "SEG-SP", "SC")), "каркас лёг: ${состав.keys}")
        assertTrue("OBC-SW" !in состав.keys, "узел пятого уровня в каркас не идёт")
        assertEquals(состав.getValue("SYS").id, состав.getValue("SEG-SP").doc.path("parent").asText(), "родитель — связью на заведённый узел")
        assertEquals("node", состав.getValue("SC").doc.path("nature").asText(), "каркас PBS — носители; поведение заводит инженер")
        assertEquals("PBS-9002:SC", состав.getValue("SC").doc.path("template_ref").asText(), "видно, из какой полки узел")

        val повтор = assertNotNull(маршруты.handle("POST", "/v2/components/frame", q, """{"author":"инженер"}"""))
        assertEquals(0, повтор.body.path("created").asInt(), "повтор ничего не удваивает: ${повтор.body}")
        assertEquals(3, повтор.body.path("already").asInt())
        assertTrue(проверки.of(проект, "composition_min:3")!!.passed, "сцена 7 этим и закрывается")
    }

    @Test
    fun `функции, обмены, элементы обмена, бюджеты и логические компоненты заводятся маршрутами и закрывают условия`() {
        assertEquals(false, проверки.of(проект, "node_functions_min:EL-SC:1")!!.passed)
        val ф = маршруты.handle("POST", "/v2/functions", q, """{"code":"FN-TX","name":"передать кадр","layer":"SA","allocated_to":["EL-SC"],"author":"Иванов И."}""")!!
        assertEquals(201, ф.code, ф.body.toString())
        assertTrue(проверки.of(проект, "node_functions_min:EL-SC:1")!!.passed)
        assertTrue(проверки.of(проект, "functions_allocated")!!.passed)
        val список = маршруты.handle("GET", "/v2/functions", q, null)!!.body.path("items")
        assertEquals("EL-SC", список.first().path("allocated_to").first().asText(), "ссылка наружу — кодом")

        val обмен = маршруты.handle("POST", "/v2/exchanges", q, """{"code":"EX-TM","name":"кадр телеметрии","interface":"IF-S-USER","source_function":"FN-TX","payload":"кадр 32 байта","author":"Иванов И."}""")!!
        assertEquals(201, обмен.code, обмен.body.toString())
        assertEquals(false, проверки.of(проект, "node_exchange_items_min:EL-SC:1")!!.passed)
        val элемент = маршруты.handle("POST", "/v2/exchange-items", q, """{"code":"EI-TM","name":"кадр ТМ","type":"flow","elements":[{"name":"crc","data_type":"u16"}],"exchanges":["EX-TM"],"author":"Иванов И."}""")!!
        assertEquals(201, элемент.code, элемент.body.toString())
        assertTrue(проверки.of(проект, "node_exchange_items_min:EL-SC:1")!!.passed)
        assertTrue(проверки.of(проект, "node_exchange_items_min:EL-UT:1")!!.passed, "стык двусторонний — обмен виден обоим узлам")

        assertEquals(false, проверки.of(проект, "budgets_min:mass,power,link")!!.passed)
        listOf("mass", "power", "link").forEach { вид ->
            assertEquals(201, маршруты.handle("POST", "/v2/budgets", q, """{"kind":"$вид","root":"EL-SC","reserve_policy":"20 % системный + 10 % на узел","author":"Иванов И."}""")!!.code)
        }
        assertTrue(проверки.of(проект, "budgets_min:mass,power,link")!!.passed)

        assertEquals(false, проверки.of(проект, "logical_components_deployed")!!.passed)
        assertEquals(201, маршруты.handle("POST", "/v2/logical-components", q, """{"name":"бортовой обработчик","functions":["FN-TX"],"deployed_to":["EL-SC"],"author":"Иванов И."}""")!!.code)
        assertTrue(проверки.of(проект, "logical_components_deployed")!!.passed)
        val лк = маршруты.handle("GET", "/v2/logical-components", q, null)!!.body.path("items").first()
        assertTrue(лк.path("code").asText().startsWith("LC-"), лк.toString())
    }

    @Test
    fun `ссылка в пустоту и чужой вид — отказ до записи`() {
        val е = assertFailsWith<IllegalArgumentException> {
            маршруты.handle("POST", "/v2/functions", q, """{"name":"функция","allocated_to":["NOPE"],"author":"Иванов И."}""")
        }
        assertTrue("никуда не ведёт" in е.message!!, е.message)
        assertTrue(store.list(область, "function").isEmpty(), "ничего не записано")
        val е2 = assertFailsWith<IllegalArgumentException> {
            маршруты.handle("POST", "/v2/exchanges", q, """{"name":"обмен","interface":"EL-SC","author":"Иванов И."}""")
        }
        assertTrue("поле ждёт interface" in е2.message!!, е2.message)
        val е3 = assertFailsWith<IllegalArgumentException> {
            маршруты.handle("POST", "/v2/budgets", q, """{"kind":"mass","root":"EL-SC","author":"Иванов И."}""")
        }
        assertTrue("reserve_policy" in е3.message!!, е3.message)
    }
}
