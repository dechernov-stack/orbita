// Словарь (шип 4 §2): термин ищется по синониму, дубль не заводится, кандидат
// принимается, отклоняется с причиной или сливается синонимом в принятый.
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

class GlossaryRoutesTest {
    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val шаблон = mapper.readTree(
        TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые/ШАБЛОН-ФАЗЫ-PRE-A-NASA.json").toFile(),
    )
    private val проект = "PJ-9813"
    private val область = Area.Project(проект)
    private val п = mapOf("project" to проект)
    private val полка = Provenance(Channel.SHELF, "поставка v2")
    // Хозяин словаря — ведущий СИ (§0.3): принимает, сливает, отклоняет.
    private val си = Actor("ivanov", "Иванов И.", setOf("lead_se"))

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
        router.handle("POST", "/v2/projects", emptyMap(), """{"name":"Словарь","code":"$проект","author":"Чернов Д."}""")
        store.create("GT-SE-001", "glossary_term", Area.Library, null, mapper.readTree(
            """{"term_ru":"Нужда","term_en":"Need","class":"se_concept","definition":"потребность стейкхолдера, решения не содержит","source":"NASA SEH"}"""), полка, status = "accepted")
        store.create("GT-ST-001", "glossary_term", Area.Library, null, mapper.readTree(
            """{"term_ru":"Ространснадзор","class":"stakeholder","definition":"Федеральная служба по надзору в сфере транспорта",
               "synonyms":["Федеральная служба по надзору в сфере транспорта","ФСНСТ"],"object_code":null}""".replace(",\"object_code\":null","")), полка, status = "accepted")
    }

    private fun список(q: String? = null, статус: String? = null) =
        router.handle("GET", "/v2/glossary", п + listOfNotNull(q?.let { "q" to it }, статус?.let { "status" to it }), null)!!.body

    @Test
    fun `термин находится по синониму и по коду, класс — словом истины`() {
        val поСинониму = список(q = "федеральная служба по надзору").path("items")
        assertEquals(listOf("GT-ST-001"), поСинониму.map { it.path("code").asText() })
        assertEquals("сторона", поСинониму[0].path("class_word").asText())
        assertEquals("library", поСинониму[0].path("area").asText())
        val поАнглийски = список(q = "need").path("items").map { it.path("code").asText() }
        assertTrue("GT-SE-001" in поАнглийски, "$поАнглийски")
        assertEquals(2, список().path("items").size(), "без иглы — весь словарь")
    }

    @Test
    fun `дубль по синониму не заводится — ответ называет принятый термин`() {
        val ответ = router.handle("POST", "/v2/glossary", п,
            """{"term_ru":"ФСНСТ","class":"stakeholder","definition":"то же","author":"Иванов И."}""")!!
        assertEquals(409, ответ.code)
        assertEquals("GT-ST-001", ответ.body.path("code").asText())
        assertTrue("уже есть" in ответ.body.path("error").asText(), ответ.body.toString())
    }

    @Test
    fun `кандидат проекта принимается с определением, отклоняется только с причиной`() {
        val кандидат = router.handle("POST", "/v2/glossary", п,
            """{"term_ru":"Минтранс","class":"stakeholder","status":"candidate","quote":"…Минтранс России…","author":"разбор"}""")!!
        assertEquals(201, кандидат.code)
        val код = кандидат.body.path("code").asText()
        assertEquals("candidate", кандидат.body.path("status").asText())
        assertEquals(1, список(статус = "candidate").path("candidates").asInt())

        val принят = router.handle("POST", "/v2/glossary/$код/accept", п,
            """{"definition":"Министерство транспорта Российской Федерации","author":"Иванов И."}""", си)!!
        assertEquals("accepted", принят.body.path("status").asText())
        assertEquals("Министерство транспорта Российской Федерации", store.byCode(область, код)!!.doc.path("definition").asText())

        val другой = router.handle("POST", "/v2/glossary", п,
            """{"term_ru":"минтранс","class":"stakeholder","status":"candidate","author":"разбор"}""")!!
        assertEquals(409, другой.code, "то же написание в другом регистре — тот же термин: ${другой.body}")
        val ещё = router.handle("POST", "/v2/glossary", п,
            """{"term_ru":"Министерство","class":"stakeholder","status":"candidate","author":"разбор"}""")!!.body.path("code").asText()
        val безПричины = assertFailsWith<IllegalArgumentException> {
            router.handle("POST", "/v2/glossary/$ещё/reject", п, """{"author":"Иванов И."}""", си)
        }
        assertTrue("без причины" in (безПричины.message ?: ""), безПричины.message)
        val отклонён = router.handle("POST", "/v2/glossary/$ещё/reject", п, """{"author":"Иванов И.","reason":"не термин, а обрывок"}""", си)!!
        assertEquals("obsolete", отклонён.body.path("status").asText())
    }

    @Test
    fun `хозяин словаря — ведущий СИ, инженер предлагает кандидата, принимает СИ (§0_3)`() {
        val инженер = Actor("petrova", "Петрова М.", setOf("specialist"))

        // Инженер предлагает термин кандидатом — можно (кандидат с его именем).
        val кандидат = router.handle("POST", "/v2/glossary", п,
            """{"term_ru":"Абонентский терминал","class":"se_concept","status":"candidate","author":"Петрова М."}""", инженер)!!
        assertEquals(201, кандидат.code, кандидат.body.toString())
        val код = кандидат.body.path("code").asText()

        // Инженер НЕ принимает и НЕ заводит принятый термин — хозяин словаря СИ.
        val отказ = assertFailsWith<orbita.process.api.RoleRefusedException> {
            router.handle("POST", "/v2/glossary/$код/accept", п, """{"author":"Петрова М."}""", инженер)
        }
        assertTrue("ведущий системный инженер" in (отказ.message ?: ""), отказ.message)
        assertFailsWith<orbita.process.api.RoleRefusedException> {
            router.handle("POST", "/v2/glossary", п,
                """{"term_ru":"Космический аппарат","class":"se_concept","status":"accepted","author":"Петрова М."}""", инженер)
        }

        // Ведущий СИ принимает кандидата — можно.
        val принят = router.handle("POST", "/v2/glossary/$код/accept", п, """{"author":"Иванов И."}""", си)!!
        assertEquals("accepted", принят.body.path("status").asText(), принят.body.toString())
    }

    @Test
    fun `словарь без актора и служебной личностью — отказ, ворота закрыты по умолчанию (§0_3 правка 28_09)`() {
        val инженер = Actor("petrova", "Петрова М.", setOf("specialist"))
        val код = router.handle("POST", "/v2/glossary", п,
            """{"term_ru":"Ретранслятор","class":"se_concept","status":"candidate","author":"Петрова М."}""", инженер)!!
            .body.path("code").asText()

        // Нет актора — ворота закрыты (не «все роли»).
        assertFailsWith<orbita.process.api.RoleRefusedException> {
            router.handle("POST", "/v2/glossary/$код/accept", п, """{"author":"аноним"}""")
        }
        // Служебная личность (ADR-071) словарь не ведёт, даже с ролью lead_se.
        val служебный = Actor("service:orbita-tools", "служебный orbita-tools", setOf("lead_se"))
        val отказ = assertFailsWith<orbita.process.api.RoleRefusedException> {
            router.handle("POST", "/v2/glossary/$код/accept", п, """{"author":"служебный"}""", служебный)
        }
        assertTrue("служебная личность" in (отказ.message ?: ""), отказ.message)
        // Ведущий СИ — принимает.
        assertEquals("accepted", router.handle("POST", "/v2/glossary/$код/accept", п, "{}", си)!!.body.path("status").asText())
    }

    @Test
    fun `привязка проекта — стороны по синониму к термину, незнакомые кандидатами, повтор ничего не меняет`() {
        // КТ2 (24.09): стороны, заведённые до словаря, привязываются кнопкой —
        // словарь показывает кандидатов и на проекте, где документы читались раньше.
        router.handle("POST", "/v2/stakeholders", п, """{"name":"ФСНСТ","role":"regulator","author":"Иванов И."}""")
        router.handle("POST", "/v2/stakeholders", п, """{"name":"Росморпорт","role":"operator","author":"Иванов И."}""")
        val итог = router.handle("POST", "/v2/glossary/link", п, """{"author":"Иванов И."}""")!!
        assertEquals(200, итог.code)
        assertEquals(2, итог.body.path("linked").asInt(), итог.body.toString())
        assertEquals(1, итог.body.path("candidates").asInt(), "незнакомая сторона — один кандидат")
        val кандидат = store.list(область, "glossary_term").single { it.status == "candidate" }
        assertEquals("Росморпорт", кандидат.doc.path("term_ru").asText())
        // Дефект из отчёта шипа 5: кандидат привязки — «привязка стороны проекта», не «разбор документа».
        assertEquals("привязка стороны проекта", кандидат.doc.path("source").asText())
        val фснст = store.list(область, "stakeholder").single { it.doc.path("name").asText() == "ФСНСТ" }
        assertTrue(links.from(фснст.id, "named_by").any { it.to == store.byCode(Area.Library, "GT-ST-001")!!.id }, "по синониму — к принятому термину")
        val снова = router.handle("POST", "/v2/glossary/link", п, """{"author":"Иванов И."}""")!!
        assertEquals(0, снова.body.path("linked").asInt(), "повтор: привязывать нечего")
        assertEquals(1, список(статус = "candidate").path("candidates").asInt())
    }

    @Test
    fun `слияние делает кандидата синонимом принятого термина и снимает его`() {
        val кандидат = router.handle("POST", "/v2/glossary", п,
            """{"term_ru":"Федеральная служба по надзору в сфере транспорта (Ространснадзор)","class":"stakeholder","status":"candidate","author":"разбор"}""")!!
            .body.path("code").asText()
        val итог = router.handle("POST", "/v2/glossary/$кандидат/merge", п, """{"into":"GT-ST-001","author":"Иванов И."}""", си)!!
        assertEquals("GT-ST-001", итог.body.path("code").asText())
        val синонимы = store.byCode(Area.Library, "GT-ST-001")!!.doc.path("synonyms").map { it.asText() }
        assertTrue("Федеральная служба по надзору в сфере транспорта (Ространснадзор)" in синонимы, "$синонимы")
        assertEquals("obsolete", store.byCode(область, кандидат)!!.status)
        assertTrue("слито в GT-ST-001" in store.byCode(область, кандидат)!!.doc.path("notes").asText())
        assertTrue(список(q = "ространснадзор").path("items").none { it.path("code").asText() == кандидат }, "снятый кандидат не находится")
    }
}
