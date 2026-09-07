// Документ — снимок сцен (ЗАДАНИЕ-ДОКУМЕНТЫ-СЕЙЧАС, приёмка).
//
// Проверяется не «структура завелась», а то, ради чего документ поднят
// перед фронтом волны 4: прошли сцены — раздел заполнился сам; не прошли —
// раздел говорит, какой сцены ждёт; число в тезисе без опоры получает
// помету; печать даёт настоящий PDF, а не «похожий на».
package orbita.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.api.internal.DocRoutes
import orbita.api.internal.V2Router
import orbita.documents.api.DocumentsFactory
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.LinkRegistry
import orbita.library.api.LibraryFactory
import orbita.process.api.ProcessFactory
import orbita.readiness.api.ReadinessFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentsTest {

    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links: LinkRegistry = KernelFactory.linkRegistry(TestDbV2.conn)
    private val пройденные = mutableMapOf<String, MutableSet<String>>()

    private val полки = TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые")
    private val шаблонФазы = mapper.readTree(полки.resolve("ШАБЛОН-ФАЗЫ-PRE-A-NASA.json").toFile())

    private val документы = DocumentsFactory.documents(
        store, links,
        template = { код ->
            val файл = полки.resolve("ШАБЛОН-" + код.uppercase() + ".json").toFile()
            if (файл.isFile) mapper.readTree(файл) else null
        },
        mapper = mapper,
    )

    private val router: V2Router by lazy {
        val движок = ProcessFactory.engine(
            template = { шаблонФазы },
            evaluator = ReadinessFactory.gateEvaluator(
                store, links,
                scenesDone = { emptySet() },
                gatesPassed = { p -> пройденные.getOrPut(p) { mutableSetOf() } },
            ),
            passedGates = { p -> пройденные.getOrPut(p) { mutableSetOf() } },
            outputCounter = { проект, вид ->
                store.list(Area.Project(проект), вид)
                    .count { вид != "intent" || it.status == "accepted" }
            },
            gatePlan = { emptyMap() },
        )
        V2Router(
            store, links, движок,
            LibraryFactory.shelves(store) { шаблонФазы },
            orbita.knowledge.api.KnowledgeFactory.intake(store, links, mapper),
            orbita.formulation.api.FormulationFactory.formulation(store, links),
            mapper,
            docRoutes = DocRoutes(store, документы, mapper) { шаблонФазы },
        )
    }

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        пройденные.clear()
    }

    /** Сцены 2–4 прожиты: замысел принят, три стороны с нуждами, цель. */
    private fun постановка(проект: String) {
        val п = mapOf("project" to проект)
        router.handle("POST", "/v2/projects", emptyMap(), """{"name":"Документы","code":"$проект"}""")
        router.handle("POST", "/v2/intent", п,
            """{"for_whom":"перевозчики","what":"телеметрия груза","where":"СМП","horizon":"2033","accepted":true}""")
        (1..3).forEach { n ->
            router.handle("POST", "/v2/stakeholders", п, """{"name":"Сторона $n","role":"customer"}""")
        }
        store.list(Area.Project(проект), "stakeholder").forEach { сторона ->
            router.handle("POST", "/v2/needs", п,
                """{"statement":"нужда ${сторона.doc.path("name").asText()}","owner":"${сторона.code}"}""")
        }
        router.handle("POST", "/v2/goals", п, """{"statement":"отслеживаемость перевозок","year":2033}""")
    }

    private fun раздел(документ: JsonNode, номер: String): JsonNode =
        документ.path("sections").single { it.path("no").asText() == номер }

    @Test
    fun `прожитые сцены заполняют первый раздел сами`() {
        постановка("PJ-9200")
        val п = mapOf("project" to "PJ-9200")
        val заведён = router.handle("POST", "/v2/documents", п, """{"template":"mcreport","author":"Иванов И."}""")!!
        assertEquals(201, заведён.code)

        val документ = router.handle("GET", "/v2/documents/mcreport", п, null)!!.body
        assertEquals(11, документ.path("total").asInt(), "MCReport по приложению 3 — одиннадцать разделов")

        val первый = раздел(документ, "§1")
        assertTrue(первый.path("complete").asBoolean(), "сцены 2–4 прожиты — §1 полон: ${первый.path("waiting")}")
        val стороны = первый.path("elements").single { it.path("code").asText() == "1.2" }
        assertEquals(3, стороны.path("rows").size(), "три стороны попали в документ сами")
        assertTrue(
            стороны.path("rows")[0].last().asText().contains("нужда"),
            "нужда стороны пришла связью, а не переписыванием: ${стороны.path("rows")[0]}",
        )
    }

    @Test
    fun `непрожитый раздел называет сцену, которой ждёт`() {
        постановка("PJ-9201")
        val документ = router.handle("GET", "/v2/documents/mcreport", mapOf("project" to "PJ-9201"), null)!!.body
        val альтернативы = раздел(документ, "§2")
        assertTrue(!альтернативы.path("complete").asBoolean(), "вариантов ещё нет — §2 не полон")
        val ждёт = альтернативы.path("waiting").joinToString("; ") { it.asText() }
        assertTrue("сцен 7" in ждёт, "раздел обязан назвать сцену-источник, а не молчать: $ждёт")
        assertEquals(1, документ.path("complete").asInt(), "полнота считается разделами: полон только §1")
    }

    @Test
    fun `число в тезисе без опоры получает помету`() {
        постановка("PJ-9202")
        val п = mapOf("project" to "PJ-9202")
        router.handle("POST", "/v2/documents", п, """{"template":"mcreport","author":"Иванов И."}""")
        val ответ = router.handle("POST", "/v2/documents/mcreport/statement", п,
            """{"section":"§1","text":"Стороны миссии: 3. Целевой охват — 97 % перевозок.","author":"Иванов И."}""")!!
        assertEquals(201, ответ.code)
        val тезис = ответ.body.path("elements").last()
        val пометы = тезис.path("notes").joinToString("; ") { it.asText() }
        assertTrue("97 %" in пометы, "число без опоры названо: $пометы")
        assertTrue("3" !in пометы.substringBefore("97"), "число, которое есть в таблице, пометы не получает: $пометы")
    }

    @Test
    fun `печать даёт настоящий PDF`() {
        постановка("PJ-9203")
        val п = mapOf("project" to "PJ-9203")
        router.handle("POST", "/v2/documents", п, """{"template":"mcreport","author":"Иванов И."}""")
        val ответ = router.handle("GET", "/v2/documents/mcreport/print", п, null)!!
        val байты = ответ.binary
        assertTrue(байты != null && байты.size > 1000, "печать обязана дать файл, а не заглушку")
        assertEquals("%PDF", байты!!.copyOfRange(0, 4).decodeToString(), "файл обязан быть PDF")
        assertEquals("application/pdf", ответ.contentType)
    }

    @Test
    fun `в печати нет служебных ключей — ссылка идёт именем`() {
        постановка("PJ-9205")
        val п = mapOf("project" to "PJ-9205")
        router.handle("POST", "/v2/documents", п, """{"template":"mcreport","author":"Иванов И."}""")
        // Носитель требования в модели — идентификатор узла; в печати он
        // обязан стать именем. Заготовка кладётся хранилищем: маршруты
        // требований живут в другом контуре, а проверяем мы печать.
        val узел = store.create(
            code = "SC", kind = "component", area = Area.Project("PJ-9205"), bornIn = "7",
            doc = mapper.readTree("""{"name":"Космический аппарат","level":1,"nature":"node"}"""),
            provenance = orbita.kernel.api.Provenance(orbita.kernel.api.Channel.MANUAL, "тест"),
        )
        store.create(
            code = "RQ-P-01", kind = "requirement", area = Area.Project("PJ-9205"), bornIn = "8",
            doc = mapper.readTree(
                """{"level":"project","title":"передача сообщений",
                    "statement":"КА должен передавать сообщения не реже раза в сутки.",
                    "category":"functional","carrier":"${узел.id}",
                    "acceptance_criteria":"проверено на испытании"}""",
            ),
            provenance = orbita.kernel.api.Provenance(orbita.kernel.api.Channel.MANUAL, "тест"),
        )

        val документ = router.handle("GET", "/v2/documents/mcreport", п, null)!!.body
        val разделы = документ.path("sections").toString()
        assertTrue(
            !Regex("""[a-z_]+-[0-9a-f]{6,}""").containsMatchIn(разделы),
            "идентификатор в печатных строках: печать обязана быть человеческим текстом",
        )
        assertTrue("Космический аппарат" in разделы, "носитель назван именем узла")
    }

    @Test
    fun `мероприятие знает, в какой раздел уходит его работа`() {
        постановка("PJ-9204")
        val п = mapOf("project" to "PJ-9204")
        router.handle("POST", "/v2/documents", п, """{"template":"mcreport","author":"Иванов И."}""")
        val подсказки = router.handle("GET", "/v2/documents/hints", п + ("scene" to "3"), null)!!.body
        val строки = подсказки.path("items")
        // Сцена 3 питает ОБА документа фазы: §1 отчёта и §2 ConOps. Порядок
        // подсказок держать тестом нельзя — их даёт список документов; важно,
        // что мероприятие видит каждое место, куда уходит его работа.
        val места = строки.associate {
            "${it.path("document").asText()}${it.path("section").asText()}" to it
        }
        val отчёт = места["Отчёт о концепции миссии§1"]
        assertTrue(отчёт != null, "сцена 3 наполняет §1 отчёта: ${места.keys}")
        assertTrue(отчёт!!.path("elements").asInt() >= 3, "в §1 три запроса")
        assertTrue(
            места.keys.any { it.startsWith("Концепция применения") && it.endsWith("§2") },
            "и §2 концепции применения: ${места.keys}",
        )
    }
}
