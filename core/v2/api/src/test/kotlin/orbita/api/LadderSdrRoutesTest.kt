// Сквозной прогон двух фаз (27.09): лестница элемента к SDR закрывается
// ОБЩИМИ средствами карточки объекта — заведением записи и связью одним
// вызовом на все виды, правкой на месте, — а не новыми панелями.
//
// Таблица «до»: у A4 «нечем» были КЕ, поставщик, привязка модели и
// методы верификации; риск со ссылкой на узел из карточки грань не видела
// (карточка пишет код, грань ждала id); у A5 не было «выведено из
// проектного с обоснованием». Тест проходит ту же лестницу маршрутами,
// которыми ходит карточка, и проверяет, что каждая грань закрывается, а
// обходные пути (статус верификации правкой поля) — отказ словами.
package orbita.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.api.internal.GateRecords
import orbita.api.internal.PointRoutes
import orbita.api.internal.ReqArchRoutes
import orbita.api.internal.V2Router
import orbita.architecture.api.ArchitectureFactory
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.Provenance
import orbita.library.api.LibraryFactory
import orbita.process.api.ProcessFactory
import orbita.readiness.api.ReadinessFactory
import orbita.requirements.api.RequirementsFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LadderSdrRoutesTest {
    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val полки = TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые")
    private val шаблоны = mapOf(
        "PHT-9001" to mapper.readTree(полки.resolve("ШАБЛОН-ФАЗЫ-PRE-A-NASA.json").toFile()),
        "PHT-9002" to mapper.readTree(полки.resolve("ШАБЛОН-ФАЗЫ-PHASE-A-NASA.json").toFile()),
    )
    private val проект = "PJ-9927"
    private val область = Area.Project(проект)
    private val провенанс = Provenance(Channel.MANUAL, "Иванов И.")
    private val записи = GateRecords(store, mapper)
    private val требования = RequirementsFactory.requirements(store, links, mapper)
    private val снимки = RequirementsFactory.baselines(store, links, mapper, hardGates = orbita.knowledge.schema.GeneratedOntology.hardGatesOnly)
    private val архитектура = ArchitectureFactory.architecture(store, links, mapper)

    private fun router(): V2Router {
        val движок = ProcessFactory.engine(
            template = { код -> шаблоны[код] ?: error("шаблона $код нет") },
            evaluator = ReadinessFactory.gateEvaluator(
                store, links, scenesDone = { emptySet() }, gatesPassed = { записи.passed(it) },
                extra = { p, у ->
                    RequirementsFactory.gateChecks(store, снимки, links).of(p, у)
                        ?: ArchitectureFactory.gateChecks(store, архитектура).of(p, у)
                },
            ),
            passedGates = { записи.passed(it) }, gatePlan = { emptyMap() },
            findings = { записи.findings(it) }, decisions = { записи.decisions(it) }, phaseOf = { записи.phaseOf(it) },
            onDecision = { p, т, кем, исход, помета, фаза -> записи.record(p, т, кем, исход, помета, фаза) },
            phaseTemplateOf = { записи.phaseTemplateOf(it) },
            onTemplateOpened = { p, код -> записи.setPhaseTemplate(p, код); записи.ensureGates(p, шаблоны[код]!!) },
        )
        return V2Router(
            store, links, движок, LibraryFactory.shelves(store) { шаблоны["PHT-9001"]!! },
            orbita.knowledge.api.KnowledgeFactory.intake(store, links, mapper),
            orbita.formulation.api.FormulationFactory.formulation(store, links), mapper,
            reqArch = ReqArchRoutes(store, требования, снимки, архитектура, mapper),
            pointRoutes = PointRoutes(движок, записи, mapper),
        )
    }

    private val п = mapOf("project" to проект)

    private fun вызов(метод: String, путь: String, тело: String? = null): JsonNode {
        val ответ = assertNotNull(router().handle(метод, путь, п, тело), "$метод $путь — нет маршрута")
        assertTrue(ответ.code in 200..299, "$метод $путь → ${ответ.code}: ${ответ.body}")
        return ответ.body
    }

    private fun граньПуста(узел: String, грань: String): Boolean =
        router().handle("GET", "/v2/components/$узел", п + ("gate" to "SDR"), null)!!.body
            .path("facets").first { it.path("key").asText() == грань }.path("lines").isEmpty

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        router().handle("POST", "/v2/projects", emptyMap(), """{"name":"Лестница к SDR","code":"$проект","author":"Чернов Д."}""")
        store.create(
            "TCS-9002", "component_template_shelf", Area.Library, null,
            mapper.readTree(TestDbV2.repoRoot.resolve("docs/tz/v2/ПОЛКА-ШАБЛОНЫ-КОМПОНЕНТОВ.json").toFile()),
            Provenance(Channel.PACKAGE, "поставка"),
        )
        store.create("EL-SC", "component", область, "A5", mapper.readTree("""{"name":"КА","kind":"element","level":2,"nature":"node"}"""), провенанс)
        store.create("SK-0001", "stakeholder", область, "3", mapper.readTree("""{"name":"АО «Решетнёв»","role":"supplier"}"""), провенанс)
    }

    @Test
    fun `КЕ узла заводится общим маршрутом — грань «Конфигурация» закрыта`() {
        assertTrue(граньПуста("EL-SC", "configuration"))
        val ке = вызов("POST", "/v2/entities", """{"kind":"configuration_item","fields":{"component":"EL-SC","type":"аппаратура","responsible":"chernov"},"author":"Иванов И."}""")
        assertEquals("CI-0001", ке.path("code").asText())
        val запись = store.byCode(область, "CI-0001")!!
        assertEquals("HW", запись.doc.path("type").asText(), "русское слово перечня легло кодом")
        assertEquals(store.byCode(область, "EL-SC")!!.id, запись.doc.path("component").asText(), "код узла лёг ссылкой на запись")
        assertTrue(!граньПуста("EL-SC", "configuration"))
    }

    @Test
    fun `общий маршрут не заводит вид с собственной формой и отбивает поле вне истины словами`() {
        val своя = assertFailsWith<IllegalArgumentException> {
            router().handle("POST", "/v2/entities", п, """{"kind":"requirement","fields":{"statement":"x"}}""")
        }
        assertTrue("своя форма" in своя.message!!, своя.message)
        val вне = assertFailsWith<IllegalArgumentException> {
            router().handle("POST", "/v2/entities", п, """{"kind":"configuration_item","fields":{"component":"EL-SC","type":"HW","responsible":"chernov","colour":"red"}}""")
        }
        assertTrue("поля «colour»" in вне.message!!, вне.message)
        val безТипа = assertFailsWith<IllegalArgumentException> {
            router().handle("POST", "/v2/entities", п, """{"kind":"configuration_item","fields":{"component":"EL-SC","responsible":"chernov"}}""")
        }
        assertTrue("обязательны" in безТипа.message!! && "Тип" in безТипа.message!!, безТипа.message)
        val чужойУзел = assertFailsWith<IllegalArgumentException> {
            router().handle("POST", "/v2/entities", п, """{"kind":"configuration_item","fields":{"component":"SK-0001","type":"HW","responsible":"chernov"}}""")
        }
        assertTrue("а поле ждёт" in чужойУзел.message!!, чужойУзел.message)
    }

    @Test
    fun `функция заводится общим маршрутом на узел — грань «Функции» закрыта`() {
        assertTrue(граньПуста("EL-SC", "functions"))
        вызов("POST", "/v2/entities", """{"kind":"function","fields":{"name":"передать кадр телеметрии","layer":"SA","allocated_to":["EL-SC"]}}""")
        assertTrue(!граньПуста("EL-SC", "functions"))
    }

    @Test
    fun `поставщик узла — полем узла (диф 27-09-b), грань «Поставщик» закрыта`() {
        assertTrue(граньПуста("EL-SC", "supplier"))
        // Пикер карточки пишет код стороны правкой на месте — общим маршрутом.
        вызов("PATCH", "/v2/entities/EL-SC", """{"fields":{"supplier":"SK-0001"},"author":"Иванов И."}""")
        assertTrue(!граньПуста("EL-SC", "supplier"))
        // «Владеет» — носитель нужды: поставщик им больше не пишется
        assertTrue(links.to(store.byCode(область, "EL-SC")!!.id, "owns").isEmpty())
    }

    @Test
    fun `миграция при старте переносит связь «владеет» сторона → узел в поле поставщика`() {
        val узел = store.byCode(область, "EL-SC")!!
        val сторона = store.byCode(область, "SK-0001")!!
        links.link("owns", сторона.id, узел.id, провенанс)
        val итог = ArchitectureFactory.migrateSuppliers(store, links)
        assertTrue("перенесено 1" in итог, итог)
        assertEquals(сторона.id, store.byCode(область, "EL-SC")!!.doc.path("supplier").asText())
        assertTrue(links.to(узел.id, "owns").isEmpty(), "связь к узлу снята")
        assertTrue(!граньПуста("EL-SC", "supplier"))
        assertTrue("перенесено 0" in ArchitectureFactory.migrateSuppliers(store, links), "идемпотентна")
    }

    @Test
    fun `«выведено из» — связью с обоснованием и уточнением, без обоснования — отказ`() {
        store.create("RQ-P-01", "requirement", область, "8", mapper.readTree("""{"level":"project","title":"ТМ","statement":"Система должна передавать телеметрию раз в сутки."}"""), провенанс)
        store.create("RQ-S-01", "requirement", область, "A5", mapper.readTree("""{"level":"system","title":"ТМ КА","statement":"КА должен передавать кадр ТМ раз за виток.","carrier":"EL-SC"}"""), провенанс)
        val без = assertFailsWith<IllegalArgumentException> {
            router().handle("POST", "/v2/links", п, """{"type":"derives_from","from":"RQ-S-01","to":"RQ-P-01","subtype":"derivation"}""")
        }
        assertTrue("обоснование обязательно" in без.message!!, без.message)
        вызов("POST", "/v2/links", """{"type":"derives_from","from":"RQ-S-01","to":"RQ-P-01","subtype":"derivation","rationale":"суточная норма делится на 16 витков"}""")
        val связь = links.from(store.byCode(область, "RQ-S-01")!!.id, "derives_from").single()
        assertEquals("derivation", связь.subtype)
        // грань карточки читает связи записи общим маршрутом: родитель кодом и обоснование
        val связи = router().handle("GET", "/v2/links", п + mapOf("code" to "RQ-S-01", "type" to "derives_from"), null)!!.body.path("items")
        assertEquals("RQ-P-01", связи[0].path("other").asText())
        assertEquals("from", связи[0].path("direction").asText())
        assertEquals("суточная норма делится на 16 витков", связи[0].path("rationale").asText())
        val условие = RequirementsFactory.gateChecks(store, снимки, links).of(проект, "each_system_requirement_derived")!!
        assertTrue(условие.passed, "у системного — родитель с обоснованием: ${условие.why}")
        // и снять связь можно тем же общим маршрутом
        вызов("POST", "/v2/links/remove", """{"id":"${связь.id}"}""")
        assertTrue(links.from(store.byCode(область, "RQ-S-01")!!.id, "derives_from").isEmpty())
    }

    @Test
    fun `риск со ссылкой на узел КОДОМ (пикер карточки) грань «Риски» видит`() {
        val риск = store.create("RSK-0001", "risk", область, "11", mapper.readTree("""{"statement":"масса не сойдётся"}"""), провенанс, status = "open")
        вызов("PATCH", "/v2/entities/${риск.code}", """{"fields":{"refs":["EL-SC"]},"author":"Чернов Д."}""")
        assertTrue(!граньПуста("EL-SC", "risks"))
    }

    @Test
    fun `грань «Верификация» к SDR — методы верификации требований узла`() {
        assertTrue(граньПуста("EL-SC", "verification"))
        store.create("RQ-S-02", "requirement", область, "A5", mapper.readTree("""{"level":"system","title":"ТМ","statement":"КА должен передавать кадр ТМ.","carrier":"EL-SC","verification_method":"test"}"""), провенанс)
        assertTrue(!граньПуста("EL-SC", "verification"))
    }

    @Test
    fun `запись модели — входы правятся на месте, статус верификации правкой поля не пишется`() {
        val масса = store.create("EL-SC.mass_dry", "parameter", область, "A4", mapper.readTree("""{"target":"EL-SC","key":"mass_dry","measure":{"value":92,"unit":"кг"}}"""), провенанс)
        val модель = store.create("М6", "system_model", область, "A6", mapper.readTree("""{"template_code":"М6","inputs":[],"verification_status":"unverified"}"""), провенанс, status = "not_built")
        assertTrue(граньПуста("EL-SC", "models"))
        вызов("PATCH", "/v2/entities/${модель.code}", """{"fields":{"inputs":[{"param_ref":"${масса.code}"}]},"author":"Иванов И."}""")
        assertTrue(!граньПуста("EL-SC", "models"), "модель читает параметр узла — грань «Модели» закрыта")
        val обход = assertFailsWith<IllegalArgumentException> {
            router().handle("PATCH", "/v2/entities/${модель.code}", п, """{"fields":{"verification_status":"verified"}}""")
        }
        assertTrue("действием" in обход.message!!, обход.message)
    }
}
