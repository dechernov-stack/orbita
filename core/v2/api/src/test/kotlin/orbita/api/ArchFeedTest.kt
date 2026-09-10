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
