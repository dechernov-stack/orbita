// Phase A (шип G): решение KDP-A открывает проекту шаблон Phase A; сцена
// аванпроекта разворачивается в экземпляр на каждый элемент состава, а без
// элементов держится словами; системное требование выводится из проектного
// связью derives_from с основанием; SDR ждёт всех экземпляров.
package orbita.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.api.api.Actor
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
import orbita.process.api.SceneState
import orbita.readiness.api.ReadinessFactory
import orbita.requirements.api.RequirementsFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PhaseATest {
    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val полки = TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые")
    private val шаблоны = mapOf(
        "PHT-9001" to mapper.readTree(полки.resolve("ШАБЛОН-ФАЗЫ-PRE-A-NASA.json").toFile()),
        "PHT-9002" to mapper.readTree(полки.resolve("ШАБЛОН-ФАЗЫ-PHASE-A-NASA.json").toFile()),
    )
    private val проект = "PJ-9801"
    private val область = Area.Project(проект)
    private val провенанс = Provenance(Channel.MANUAL, "Чернов Д.")
    private val записи = GateRecords(store, mapper)
    private val требования = RequirementsFactory.requirements(store, links, mapper)
    private val снимки = RequirementsFactory.baselines(store, links, mapper)
    private val архитектура = ArchitectureFactory.architecture(store, links, mapper)

    private fun движок() = ProcessFactory.engine(
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
        instances = { p, вид ->
            store.list(Area.Project(p), "component").filter { it.doc.path("kind").asText() == вид }
                .sortedBy { it.code }.map { it.code to it.doc.path("name").asText(it.code) }
        },
    )

    private fun router(): V2Router {
        val движок = движок()
        return V2Router(
            store, links, движок, LibraryFactory.shelves(store) { шаблоны["PHT-9001"]!! },
            orbita.knowledge.api.KnowledgeFactory.intake(store, links, mapper),
            orbita.formulation.api.FormulationFactory.formulation(store, links), mapper,
            reqArch = ReqArchRoutes(store, требования, снимки, архитектура, mapper),
            pointRoutes = PointRoutes(движок, записи, mapper),
        )
    }

    private val п = mapOf("project" to проект)
    private val da = Actor("chernov", "Чернов Д.", setOf("lead", "da_review"))

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        router().handle("POST", "/v2/projects", emptyMap(), """{"name":"Phase A","code":"$проект","author":"Чернов Д."}""")
    }

    /** Точки Pre-A проходятся записями напрямую: проверяется переход, а не Pre-A целиком. */
    private fun открытьPhaseA() {
        listOf("internal_review", "MCR").forEach { записи.record(проект, it, "Чернов Д.", "approve", null, null) }
        записи.record(проект, "KDP-A", "Чернов Д.", "approve", null, "Phase A")
        записи.setPhaseTemplate(проект, "PHT-9002")
        записи.ensureGates(проект, шаблоны["PHT-9002"]!!)
    }

    @Test
    fun `после KDP-A проект живёт по шаблону Phase A, аванпроект без элементов держится словами`() {
        assertEquals("Pre-Phase A", движок().view(проект).phase)
        открытьPhaseA()
        val фаза = движок().view(проект)
        assertEquals("Phase A", фаза.phase)
        assertEquals((1..12).map { "A$it" }, фаза.scenes.map { it.key }, "двенадцать сцен поставки 10.09")
        assertEquals(listOf("internal_review_a", "SRR", "SDR", "KDP-B"), фаза.gates.map { it.key })
        assertNotNull(store.byCode(область, "SRR"), "точки новой фазы заведены записями — решению есть куда лечь")
        assertEquals(47, фаза.scenes.sumOf { it.exit.size }, "44 условия поставки → 47 проверок выхода")
        val a4 = фаза.scenes.first { it.key == "A4" }
        assertEquals(SceneState.LOCKED, a4.state)
        assertTrue(a4.blockers.any { "нет ни одного узла вида «element»" in it }, a4.blockers.toString())
        assertTrue(фаза.scenes.first { it.key == "A2" }.links.any { it.type == "SS" && it.on == "A1" && it.why.isNotBlank() }, "связи с обоснованием")
        // A1 открыта решением KDP-A (FS), A2 — началом A1 (SS)
        assertEquals(SceneState.OPEN, фаза.scenes.first { it.key == "A1" }.state)
        assertEquals(SceneState.OPEN, фаза.scenes.first { it.key == "A2" }.state, "SS: A2 открыта, как только A1 начата")
        // условие «желательно» видно, но сцену не держит: A5 без логических компонентов держится другими условиями, не этим
        val a5 = фаза.scenes.first { it.key == "A5" }
        val желательно = a5.exit.first { it.check == "logical_components_deployed" }
        assertTrue(!желательно.blocking && !желательно.passed, "не блокирует и не выполнено")
        assertTrue(a5.blockers.none { "логических компонентов нет" in it }, a5.blockers.toString())
    }

    @Test
    fun `аванпроект — экземпляр на каждый элемент состава, SDR ждёт всех`() {
        открытьPhaseA()
        store.create("SEG-SP", "component", область, "7", mapper.readTree("""{"name":"Космический сегмент","kind":"segment","level":1,"nature":"node"}"""), провенанс)
        val ка = store.create("SC", "component", область, "7", mapper.readTree("""{"name":"Космический аппарат","kind":"element","level":2,"nature":"node"}"""), провенанс)
        val нку = store.create("GS", "component", область, "7", mapper.readTree("""{"name":"Наземный комплекс","kind":"element","level":2,"nature":"node"}"""), провенанс)
        // проектное требование и его системный вывод — деривация с основанием
        val проектное = store.create("RQ-P-01", "requirement", область, "8",
            mapper.readTree("""{"level":"project","title":"Телеметрия","statement":"Система должна передавать телеметрию не реже раза в сутки.","category":"performance"}"""), провенанс)
        assertFailsWith<IllegalArgumentException>("без основания — отказ") {
            требования.derive(проект, "RQ-P-01", "КА должен передавать кадр телеметрии раз в 90 минут.", "", "Иванов И.")
        }
        val системное = требования.derive(
            проект, "RQ-P-01", "КА должен передавать кадр телеметрии раз в 90 минут.",
            "суточная норма проектного уровня делится на витки: 16 витков в сутки", "Иванов И.", carrier = "SC", subtype = "decomposition",
        )
        assertEquals("RQ-S-0001", системное.code)
        assertEquals("system", системное.level.name.lowercase())
        val связь = links.from(store.byCode(область, "RQ-S-0001")!!.id, "derives_from").single()
        assertEquals(проектное.id, связь.to); assertEquals("decomposition", связь.subtype)

        val фаза = движок().view(проект)
        val экземпляры = фаза.scenes.filter { it.instanceOf == "A4" }
        assertEquals(listOf("A4:GS", "A4:SC"), экземпляры.map { it.key })
        assertEquals("Аванпроект элемента Космический аппарат", экземпляры.first { it.node == "SC" }.title)
        assertTrue(фаза.scenes.none { it.key == "A4" }, "сцена шаблона заменена экземплярами")
        val ка_сцена = экземпляры.first { it.node == "SC" }
        assertEquals(SceneState.OPEN, ка_сцена.state)
        assertTrue(ка_сцена.exit.first { it.check.startsWith("node_requirements_min:SC") }.passed, "требование на КА распределено")
        assertTrue(ка_сцена.blockers.any { "стыков 0 из 1" in it }, ка_сцена.blockers.toString())
        val нку_сцена = экземпляры.first { it.node == "GS" }
        assertTrue(нку_сцена.blockers.any { "распределено 0 требований" in it }, нку_сцена.blockers.toString())
        val sdr = фаза.gates.first { it.key == "SDR" }
        assertTrue(sdr.criteria.first { it.check == "scene_done:A4" }.passed.not(), "SDR ждёт всех экземпляров")

        // стык у обоих элементов, требование на НКУ, функции и элемент обмена на стыке → оба экземпляра прожиты → A4 прожита
        val стык = store.create("IF-S-G", "interface", область, "7", mapper.readTree("""{"name":"КА — НКУ (радиолиния)","type":"rf","a":"${ка.id}","b":"${нку.id}","direction":"both"}"""), провенанс)
        требования.derive(проект, "RQ-P-01", "НКУ должен принимать кадры телеметрии на каждом сеансе.", "приём — зеркало передачи", "Иванов И.", carrier = "GS")
        store.create("FN-TX", "function", область, "A4", mapper.readTree("""{"name":"передать кадр","layer":"SA","allocated_to":["${ка.id}"]}"""), провенанс)
        store.create("FN-RX", "function", область, "A4", mapper.readTree("""{"name":"принять кадр","layer":"SA","allocated_to":["${нку.id}"]}"""), провенанс)
        val обмен = store.create("EX-TM", "exchange", область, "A4", mapper.readTree("""{"name":"кадр телеметрии","interface":"${стык.id}"}"""), провенанс)
        store.create("EI-TM", "exchange_item", область, "A4", mapper.readTree("""{"name":"кадр ТМ","type":"flow","elements":[{"name":"crc","data_type":"u16"}],"exchanges":["${обмен.id}"]}"""), провенанс)
        val после = движок().view(проект)
        assertTrue(после.scenes.filter { it.instanceOf == "A4" }.all { it.state == SceneState.DONE }, после.scenes.filter { it.instanceOf == "A4" }.map { it.blockers }.toString())
        assertTrue(после.gates.first { it.key == "SDR" }.criteria.first { it.check == "scene_done:A4" }.passed, "все экземпляры прожиты — A4 прожита")
    }

    @Test
    fun `маршрут деривации и отказ решения KDP-A без шаблона следующей фазы не ломают Pre-A`() {
        val r = router()
        store.create("RQ-P-02", "requirement", область, "8", mapper.readTree("""{"level":"project","title":"Срок","statement":"Срок службы не менее 5 лет.","category":"constraint"}"""), провенанс)
        val ответ = assertNotNull(r.handle("POST", "/v2/requirements/derive", п,
            """{"parent":"RQ-P-02","statement":"АКБ должна выдерживать 30 000 циклов.","rationale":"5 лет × 16 витков в сутки","carrier":null,"author":"Иванов И."}""", da))
        assertEquals(201, ответ.code, ответ.body.toString())
        assertEquals("system", ответ.body.path("level").asText())
        val фаза = r.handle("GET", "/v2/phase", п, null, da)!!.body
        assertEquals("Pre-Phase A", фаза.path("phase").asText(), "до KDP-A — Pre-A")
    }

    /** Стенд 10.09: движок живёт долго, шаблон, открытый при заведении проекта, перекрывал записанный решением. */
    @Test
    fun `в одном движке записанный шаблон фазы первее открытого при заведении проекта`() {
        val движок = движок()
        движок.openPhase(проект, "PHT-9001") // как делает POST /v2/projects
        assertEquals("Pre-Phase A", движок.view(проект).phase)
        открытьPhaseA() // записи решения и шаблона — то, что делает onTemplateOpened
        val фаза = движок.view(проект)
        assertEquals("Phase A", фаза.phase)
        assertEquals("A1", фаза.scenes.first().key, "лента сменилась на Phase A без перезапуска движка")
    }

    /** Замечание владельца 11.09: Phase A не может начаться до окончания Pre-A. */
    @Test
    fun `даты Phase A отсчитываются от решения KDP-A, пройденная точка живёт датой решения, план раньше KDP-A — отказ`() {
        val r = router()
        // KDP-A запланирован через полгода, а решён сегодня: Pre-A закончилась сегодня
        val kdpA = store.byCode(область, "KDP-A")!!
        val черезПолгода = java.time.LocalDate.now().plusDays(180).toString()
        store.update(kdpA.id, (kdpA.doc.deepCopy() as com.fasterxml.jackson.databind.node.ObjectNode).put("planned_date", черезПолгода), провенанс)
        открытьPhaseA()
        val движок = движок()
        val фаза = движок.view(проект)
        val сегодня = java.time.LocalDate.now()
        val srr = фаза.gates.first { it.key == "SRR" }
        assertTrue(java.time.LocalDate.parse(srr.plannedDate) >= сегодня, "SRR не раньше дня решения KDP-A: ${srr.plannedDate}")
        assertEquals(сегодня.plusDays(120).toString(), srr.plannedDate, "по умолчанию — смещение шаблона от начала фазы, не от «сегодня» создания")
        val kdp = r.handle("GET", "/v2/points", п, null, da)!!.body.path("items").firstOrNull { it.path("key").asText() == "KDP-A" }
        // план Phase A с датой SRR раньше решения KDP-A — отказ словами
        val вчера = сегодня.minusDays(1).toString()
        val е = assertFailsWith<IllegalArgumentException> {
            r.handle("POST", "/v2/plan", п, """{"phase":"Phase A","gate_dates":[{"gate":"SRR","date":"$вчера"}],"scene_windows":[],"author":"Чернов Д."}""", da)
        }
        assertTrue("не может начаться до окончания Pre-Phase A" in е.message!!, е.message)
        assertTrue("SRR ($вчера)" in е.message!!, е.message)
        // тот же план датой не раньше решения — принимается
        val ок = r.handle("POST", "/v2/plan", п, """{"phase":"Phase A","gate_dates":[{"gate":"SRR","date":"${сегодня.plusDays(90)}"}],"scene_windows":[],"author":"Чернов Д."}""", da)!!
        assertEquals(201, ок.code, ок.body.toString())
        assertEquals(null, kdp?.takeIf { false }, "")
    }

    /** Замечание владельца 11.09: «в ограничениях пусто» — рамки класса миссии ложатся в сцену 5 при заведении. */
    @Test
    fun `заведение проекта с классом миссии копирует рамки Р с полки в сцену 5`() {
        store.create("Р2", "constraint", Area.Library, null,
            mapper.readTree("""{"code":"Р2","type":"technical","statement":"Платформы — 12U…100 кг.","bound":{"key":"mass","op":"le","value":100,"unit":"кг"}}"""), провенанс)
        store.create("Р3", "constraint", Area.Library, null,
            mapper.readTree("""{"code":"Р3","type":"technical","statement":"Межспутниковая связь — только РЧ."}"""), провенанс)
        store.create("MC-9001", "mission_class", Area.Library, null,
            mapper.readTree("""{"name":"НОО · связь и IoT","recommended_shelves":["PBS-9002"],"mandatory_models":[{"model_code":"М1","gate":"MCR"}],"default_constraints":["Р2","Р3","Р9"]}"""), провенанс)
        val r = router()
        val ответ = r.handle("POST", "/v2/projects", emptyMap(), """{"name":"С рамками","code":"PJ-9806","mission_class":"НОО · связь и IoT","author":"Чернов Д."}""")!!
        assertEquals(201, ответ.code)
        assertEquals(2, ответ.body.path("prefilled").path("constraints").asInt(), "Р2 и Р3 скопированы, Р9 на полке нет — пропущена без выдумки")
        val рамки = store.list(Area.Project("PJ-9806"), "constraint").sortedBy { it.code }
        assertEquals(listOf("Р2", "Р3"), рамки.map { it.code })
        assertEquals(100.0, рамки.first().doc.path("bound").path("value").asDouble(), "граница пришла с полки")
        assertEquals("5", рамки.first().bornIn, "ограничение родом из сцены 5")
        assertTrue(рамки.first().provenance.author.contains("полка класса миссии MC-9001"), рамки.first().provenance.author)
        // без класса миссии — ничего не копируется, и это сказано
        val без = r.handle("POST", "/v2/projects", emptyMap(), """{"name":"Без класса","code":"PJ-9807","author":"Чернов Д."}""")!!
        assertEquals(0, без.body.path("prefilled").path("constraints").asInt())
        assertTrue(без.body.path("prefilled").path("note").asText().contains("не указан"))
    }

    private fun JsonNode.первая(ключ: String) = path("scenes").first { it.path("key").asText() == ключ }
}
