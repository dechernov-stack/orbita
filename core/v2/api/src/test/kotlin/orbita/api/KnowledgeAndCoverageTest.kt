// Волна 2: знания и матрица покрытия.
//
// Главное свойство — честная граница: система НЕ выдумывает факты. Пока
// материал не разобран, задание говорит об этом прямо, а задание вне
// каталога не додумывается — предлагается ближайшее и спрашивается.
//
// Матрица покрытия не хранится: она считается по связям, поэтому разойтись
// с действительностью ей не с чем.
package orbita.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.api.internal.V2Router
import orbita.formulation.api.FormulationFactory
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

class KnowledgeAndCoverageTest {

    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val пройденные = mutableMapOf<String, MutableSet<String>>()

    private val шаблон = mapper.readTree(
        TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые/ШАБЛОН-ФАЗЫ-PRE-A-NASA.json").toFile(),
    )

    private val router: V2Router by lazy {
        V2Router(
            store, links,
            ProcessFactory.engine(
                template = { шаблон },
                evaluator = ReadinessFactory.gateEvaluator(
                    store, links,
                    scenesDone = { emptySet() },
                    gatesPassed = { p -> пройденные.getOrPut(p) { mutableSetOf() } },
                ),
                passedGates = { p -> пройденные.getOrPut(p) { mutableSetOf() } },
                gatePlan = { emptyMap() },
            ),
            LibraryFactory.shelves(store) { шаблон },
            KnowledgeFactory.intake(store, mapper),
            FormulationFactory.formulation(store, links),
            mapper,
        )
    }

    private val проект = "PJ-9200"
    private val параметры = mapOf("project" to проект)

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        пройденные.clear()
        router.handle("POST", "/v2/projects", emptyMap(), """{"name":"Знания","code":"$проект"}""")
    }

    @Test
    fun `неразобранный материал не даёт выдуманных фактов`() {
        val материал = router.handle("POST", "/v2/materials", параметры,
            """{"name":"Записка миссии","kind":"mission_note","text":"текст записки"}""")!!
        val код = материал.body.path("code").asText()
        assertEquals("SD-0001", код)

        val задание = router.handle("POST", "/v2/intake", параметры,
            """{"material":"$код","intent":"разбери по сущностям"}""")!!
        assertEquals(0, задание.body.path("facts").size(), "фактов нет — и выдумывать их нельзя")
        assertEquals(0, задание.body.path("plan").size(), "плана из ничего не бывает")
        assertTrue(
            "не разобран" in задание.body.path("note").asText(),
            "система обязана сказать, почему пусто: ${задание.body.path("note").asText()}",
        )
    }

    @Test
    fun `задание вне каталога не додумывается`() {
        val код = router.handle("POST", "/v2/materials", параметры,
            """{"name":"Даташит","kind":"datasheet","text":"…"}""")!!.body.path("code").asText()
        // факт есть — значит дело не в пустом разборе, а именно в задании
        store.create(
            "FT-0001", "fact", Area.Project(проект), "2",
            mapper.readTree("""{"subject":"платформа","predicate":"масса","value":"12","unit":"kg",
                "anchor":"b7","mark":"В","material":"$код"}"""),
            Provenance(Channel.PACKAGE, "разбор"),
        )

        val задание = router.handle("POST", "/v2/intake", параметры,
            """{"material":"$код","intent":"сделай красиво"}""")!!
        assertEquals(0, задание.body.path("plan").size(), "по непонятному заданию план не строится")
        val примечание = задание.body.path("note").asText()
        assertTrue("вне каталога" in примечание, примечание)
        assertTrue("гадать система не станет" in примечание, примечание)
    }

    @Test
    fun `задание из каталога даёт план с предпросмотром последствий`() {
        val код = router.handle("POST", "/v2/materials", параметры,
            """{"name":"Платформа Спутникс","kind":"datasheet","text":"…"}""")!!.body.path("code").asText()
        (1..3).forEach { n ->
            store.create(
                "FT-000$n", "fact", Area.Project(проект), "2",
                mapper.readTree("""{"subject":"платформа","predicate":"параметр $n","value":"$n","unit":"kg",
                    "anchor":"b$n","mark":"В","material":"$код"}"""),
                Provenance(Channel.PACKAGE, "разбор"),
            )
        }

        val задание = router.handle("POST", "/v2/intake", параметры,
            """{"material":"$код","intent":"рассмотрим как базовую платформу"}""")!!
        assertEquals(3, задание.body.path("facts").size())
        val план = задание.body.path("plan")
        assertTrue(план.size() >= 2, "кандидат базового решения проверяется ещё и ограничениями: $план")
        assertTrue(
            план.any { "ограничени" in it.path("title").asText() || "Р-код" in it.path("effect").asText() },
            "план обязан сверить кандидата с Р-кодами: $план",
        )
        план.forEach {
            assertTrue(
                it.path("effect").asText().isNotBlank(),
                "каждое действие показывает, что создаст, ДО нажатия",
            )
        }
    }

    @Test
    fun `матрица покрытия считается по связям и называет пробелы`() {
        router.handle("POST", "/v2/intent", параметры,
            """{"for_whom":"перевозчики","what":"телеметрия","where":"СМП","horizon":"2033","accepted":true}""")
        val сторона = router.handle("POST", "/v2/stakeholders", параметры,
            """{"name":"Минтранс России","role":"customer"}""")!!.body.path("code").asText()
        router.handle("POST", "/v2/stakeholders", параметры, """{"name":"Росавиация","role":"regulator"}""")
        val нужда = router.handle("POST", "/v2/needs", параметры,
            """{"statement":"нужна телеметрия груза в пути","owner":"$сторона"}""")!!.body.path("code").asText()

        val доЦелей = router.handle("GET", "/v2/coverage", параметры, null)!!.body
        assertEquals(1, доЦелей.path("total").asInt())
        assertEquals(0, доЦелей.path("covered").asInt())
        val строка = доЦелей.path("needs")[0]
        assertEquals("нет ни цели, ни сервиса", строка.path("gap").asText())
        assertEquals("Минтранс России", строка.path("owner").asText())
        assertTrue(
            доЦелей.path("stakeholders_without_needs").toString().contains("Росавиация"),
            "край матрицы виден: сторона без нужд названа",
        )

        router.handle("POST", "/v2/goals", параметры,
            """{"statement":"отслеживаемость перевозок","year":2033,"covers":["$нужда"]}""")
        val сЦелью = router.handle("GET", "/v2/coverage", параметры, null)!!.body
        assertEquals("нет сервиса: нечем закрыть", сЦелью.path("needs")[0].path("gap").asText())

        router.handle("POST", "/v2/services", параметры,
            """{"name":"передача коротких сообщений","qos_class":"B′","covers":["$нужда"]}""")
        val покрыто = router.handle("GET", "/v2/coverage", параметры, null)!!.body
        assertEquals(1, покрыто.path("covered").asInt())
        assertTrue(покрыто.path("needs")[0].path("gap").isNull, "пробелов не осталось")
        assertTrue("покрыты все нужды" in покрыто.path("summary").asText())
    }
}
