// Условия сцен Phase A (поставка 10.09, УСЛОВИЯ-СЦЕН-PHASE-A): у каждого
// условия — тест на отказ и на проход. Условие только читает реестр.
package orbita.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.api.internal.GateRecords
import orbita.api.internal.PointChecks
import orbita.architecture.api.ArchitectureFactory
import orbita.documents.api.DocumentsFactory
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.Provenance
import orbita.programmatics.api.ProgrammaticsFactory
import orbita.readiness.api.CheckResult
import orbita.readiness.api.ExtraChecks
import orbita.requirements.api.RequirementsFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PhaseAConditionsTest {
    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val полки = TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые")
    private val проект = "PJ-9803"
    private val область = Area.Project(проект)
    private val п = Provenance(Channel.MANUAL, "Чернов Д.")
    private val шаблон: JsonNode = mapper.readTree(полки.resolve("ШАБЛОН-ФАЗЫ-PHASE-A-NASA.json").toFile())
    private val записи = GateRecords(store, mapper)
    private val требования = RequirementsFactory.requirements(store, links, mapper)
    private val снимки = RequirementsFactory.baselines(store, links, mapper)
    private val архитектура = ArchitectureFactory.architecture(store, links, mapper)
    private val программатика = ProgrammaticsFactory.programmatics(store, mapper)
    private val документы = DocumentsFactory.documents(
        store, links,
        template = { код -> полки.resolve("ШАБЛОН-" + код.uppercase() + ".json").toFile().takeIf { it.isFile }?.let { mapper.readTree(it) } },
        mapper = mapper,
        baselineRoot = java.nio.file.Files.createTempDirectory("orbita-baselines").toFile().also { it.deleteOnExit() },
    )
    private val проверки: List<ExtraChecks> = listOf(
        RequirementsFactory.gateChecks(store, снимки, links),
        ArchitectureFactory.gateChecks(store, архитектура),
        ProgrammaticsFactory.gateChecks(store, программатика),
        DocumentsFactory.gateChecks(документы),
        PointChecks(store, записи, документы) { шаблон },
    )

    private fun условие(check: String): CheckResult =
        проверки.firstNotNullOfOrNull { it.of(проект, check) } ?: error("условие «$check» никто не знает")

    private fun отказ(check: String, слово: String) {
        val р = условие(check)
        assertFalse(р.passed, "«$check» обязано отказать: ${р.why}")
        assertTrue(р.why.orEmpty().contains(слово), "«$check»: в причине нет «$слово»: ${р.why}")
    }

    private fun проход(check: String) {
        val р = условие(check)
        assertTrue(р.passed, "«$check» обязано пройти: ${р.why}")
    }

    private fun json(текст: String): JsonNode = mapper.readTree(текст)

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        store.create(проект, "project", область, "1", json("""{"name":"Условия Phase A","standard":"NASA-7120","phase":"Phase A","phase_template":"PHT-9002"}"""), п)
        записи.ensureGates(проект, шаблон)
    }

    /** Каждая проверка шаблона Phase A знакома хотя бы одному контуру — иначе условие врёт «выполнено». */
    @Test
    fun `все 47 проверок шаблона Phase A кто-то знает`() {
        val ключи = шаблон.path("scenes").flatMap { с -> с.path("exit").map { it.path("check").asText() } }
            .map { it.replace("{node}", "EL-X") }
        assertEquals(47, ключи.size)
        val движковые = setOf("scene_done", "scene_started", "gate_passed", "phase_plan_started")
        val неизвестные = ключи.filter { к ->
            к.substringBefore(":") !in движковые && проверки.none { it.of(проект, к) != null }
        }
        assertEquals(emptyList(), неизвестные, "проверки без контура")
    }

    @Test
    fun `A1 — даты точек фазы, ответственные и окна сцен`() {
        отказ("phase_points_dated", "даты не заданы")
        store.list(область, "gate").forEach { store.update(it.id, (it.doc.deepCopy() as com.fasterxml.jackson.databind.node.ObjectNode).put("planned_date", "2027-05-01"), п) }
        проход("phase_points_dated")
        отказ("scene_responsible_min:1", "план работ фазы не задан")
        store.create("PLAN-A", "plan", область, "A1", json("""{"phase":"Phase A","gate_dates":[],"scene_windows":[{"scene":"A1","start":"2027-01-01","end":"2027-02-01"}],"set_by":"Чернов Д."}"""), п)
        отказ("scene_responsible_min:1", "назначено 0 из 1")
        val план = store.list(область, "plan").last()
        store.update(план.id, json("""{"phase":"Phase A","gate_dates":[],"scene_windows":[{"scene":"A1","start":"2027-01-01","end":"2027-02-01","responsible":"chernov"}],"set_by":"Чернов Д."}"""), п)
        проход("scene_responsible_min:1")
    }

    @Test
    fun `A3 — сценарии и режимы`() {
        отказ("scenarios_min:3", "0 из 3")
        отказ("modes_defined", "машины режимов нет")
        repeat(3) { store.create("FC-$it", "functional_chain", область, "9", json("""{"name":"сценарий $it","steps":[]}"""), п) }
        store.create("SM-SC", "state_machine", область, "9", json("""{"owner":"SC","states":["safe","nominal"],"initial":"safe","transitions":[]}"""), п)
        проход("scenarios_min:3"); проход("modes_defined")
    }

    @Test
    fun `A4 — функции, ступень и элементы обмена узла экземпляра`() {
        val ка = store.create("EL-SC", "component", область, "7", json("""{"name":"КА","kind":"element","level":2,"nature":"node"}"""), п)
        val нку = store.create("EL-GS", "component", область, "7", json("""{"name":"НКУ","kind":"element","level":2,"nature":"node"}"""), п)
        отказ("node_functions_min:EL-SC:1", "0 функций из 1")
        store.create("FN-1", "function", область, "A4", json("""{"name":"передать кадр","layer":"SA","allocated_to":["${ка.id}"]}"""), п)
        проход("node_functions_min:EL-SC:1")
        отказ("node_functions_min:EL-GS:1", "0 функций")
        отказ("node_exchange_items_min:EL-SC:1", "0 из 1")
        val стык = store.create("IF-S-G", "interface", область, "7", json("""{"name":"КА — НКУ","type":"data","a":"${ка.id}","b":"${нку.id}","direction":"both"}"""), п)
        val обмен = store.create("EX-1", "exchange", область, "A4", json("""{"name":"кадр телеметрии","interface":"${стык.id}"}"""), п)
        store.create("EI-1", "exchange_item", область, "A4", json("""{"name":"кадр","type":"flow","elements":[{"name":"crc","data_type":"u16"}],"exchanges":["${обмен.id}"]}"""), п)
        проход("node_exchange_items_min:EL-SC:1")
        проход("node_exchange_items_min:EL-GS:1")
        отказ("node_functions_min:NOPE:1", "узла «NOPE» в составе нет")
    }

    @Test
    fun `A5 — сегменты, носители, функции, цепочки, бюджеты, логические компоненты`() {
        отказ("each_segment_has_requirement", "сегментов в составе нет")
        val сп = store.create("SEG-SP", "component", область, "7", json("""{"name":"Космический сегмент","kind":"segment","level":1,"nature":"node"}"""), п)
        store.create("SEG-GS", "component", область, "7", json("""{"name":"Наземный сегмент","kind":"segment","level":1,"nature":"node"}"""), п)
        val ка = store.create("EL-SC", "component", область, "7", json("""{"name":"КА","kind":"element","level":2,"nature":"node","parent":"${сп.id}"}"""), п)
        store.create("RQ-P-01", "requirement", область, "8", json("""{"level":"project","title":"Т","statement":"Система должна передавать телеметрию.","category":"performance","priority":"must"}"""), п)
        отказ("each_segment_has_requirement", "SEG-GS, SEG-SP")
        val системное = требования.derive(проект, "RQ-P-01", "КА должен передавать кадр раз за виток.", "деление суточной нормы", "Иванов И.", carrier = "EL-SC")
        отказ("each_segment_has_requirement", "SEG-GS")
        assertEquals("system", системное.level.name.lowercase())
        // носитель на самом сегменте тоже закрывает сегмент
        store.create("RQ-S-0090", "requirement", область, "A5", json("""{"level":"system","title":"Н","statement":"Наземный сегмент должен принимать кадры.","category":"functional","priority":"must","carrier":"SEG-GS"}"""), п)
        отказ("each_system_requirement_derived", "без родителя: 1")
        // без носителя на системном уровне
        store.create("RQ-S-0091", "requirement", область, "A5", json("""{"level":"system","title":"Б","statement":"Без носителя.","category":"functional","priority":"should"}"""), п)
        отказ("no_carrierless_requirement:system", "RQ-S-0091")
        assertFalse(условие("no_carrierless_requirement:system").why!!.contains("RQ-P-01"), "уровень project условие system не считает")
        отказ("functions_allocated", "функций слоя SA нет")
        store.create("FN-1", "function", область, "A5", json("""{"name":"передать кадр","layer":"SA","allocated_to":[]}"""), п)
        отказ("functions_allocated", "функций без узла: 1")
        store.update(store.byCode(область, "FN-1")!!.id, json("""{"name":"передать кадр","layer":"SA","allocated_to":["${ка.id}"]}"""), п)
        проход("functions_allocated")
        отказ("chains_realized", "цепочек нет")
        val цепь = store.create("FC-1", "functional_chain", область, "9", json("""{"name":"доставка сообщения","steps":[]}"""), п)
        отказ("chains_realized", "FC-1")
        store.create("RQ-SC-01", "requirement", область, "9", json("""{"level":"scenario","title":"Д","statement":"Сообщение должно быть доставлено за сутки.","category":"performance","priority":"must","carrier":"${цепь.id}"}"""), п)
        проход("chains_realized")
        отказ("budgets_min:mass,power,link", "бюджетов нет: mass, power, link")
        store.create("B-mass", "budget", область, "A5", json("""{"kind":"mass","root":"${ка.id}","reserve_policy":""}"""), п)
        store.create("B-power", "budget", область, "A5", json("""{"kind":"power","root":"${ка.id}","reserve_policy":"20 % системный + 10 % на узел"}"""), п)
        store.create("B-link", "budget", область, "A5", json("""{"kind":"link","root":"${ка.id}","reserve_policy":"3 дБ + 1 дБ"}"""), п)
        отказ("budgets_min:mass,power,link", "без политики резервов: B-mass")
        store.update(store.byCode(область, "B-mass")!!.id, json("""{"kind":"mass","root":"${ка.id}","reserve_policy":"20 % + 10 %"}"""), п)
        проход("budgets_min:mass,power,link")
        отказ("logical_components_deployed", "логических компонентов нет")
        store.create("LC-1", "logical_component", область, "A5", json("""{"name":"бортовой обработчик","functions":[],"deployed_to":[]}"""), п)
        отказ("logical_components_deployed", "LC-1")
        store.update(store.byCode(область, "LC-1")!!.id, json("""{"name":"бортовой обработчик","functions":[],"deployed_to":["${ка.id}"]}"""), п)
        проход("logical_components_deployed")
    }

    @Test
    fun `A6 — варианты, базовый с отклонёнными, модели, протокол в ICD §6`() {
        документы.ensure(проект, "icd", "Чернов Д.")
        отказ("document_section_started:icd:§6", "раздел §6")
        отказ("document_section_started:icd:§9", "нет раздела §9")
        отказ("variants_min:2", "0 из 2")
        repeat(2) { store.create("VAR-0$it", "constellation_variant", область, "7", json("""{"name":"V$it","subgroups":[],"working":true}"""), п) }
        проход("variants_min:2")
        отказ("baseline_concept_with_rejected", "базовый вариант не назван")
        store.create("BC-1", "baseline_concept", область, "7", json("""{"variant":"VAR-00","rationale":"покрытие Арктики","rejected":[]}"""), п)
        отказ("baseline_concept_with_rejected", "нет отклонённых")
        store.update(store.byCode(область, "BC-1")!!.id, json("""{"variant":"VAR-00","rationale":"покрытие Арктики","rejected":[{"variant":"VAR-01","reason":""}]}"""), п)
        отказ("baseline_concept_with_rejected", "без причины")
        store.update(store.byCode(область, "BC-1")!!.id, json("""{"variant":"VAR-00","rationale":"покрытие Арктики","rejected":[{"variant":"VAR-01","reason":"разрыв покрытия СМП"}]}"""), п)
        проход("baseline_concept_with_rejected")
        отказ("model_runs_verified:М1,М6", "М1 — модели в проекте нет")
        val м1 = store.create("М1", "system_model", область, "10", json("""{"template_code":"М1","inputs":[],"verification_status":"unverified"}"""), п)
        store.create("М6", "system_model", область, "10", json("""{"template_code":"М6","inputs":[],"verification_status":"verified"}"""), п)
        отказ("model_runs_verified:М1,М6", "М1 — нет ответа и не верифицирована")
        store.create("RUN-М1-1", "model_run", область, "10", json("""{"model":"${м1.id}","inputs_snapshot":{},"outputs":[{"key":"coverage","measure":{"value":1,"unit":"1"}}],"tool":"orekit","proxy":false,"at":"2027-01-01","by":"ivanov"}"""), п)
        отказ("model_runs_verified:М1,М6", "М1 — не верифицирована")
        store.update(м1.id, json("""{"template_code":"М1","inputs":[],"verification_status":"verified","last_run":"RUN-М1-1"}"""), п)
        отказ("model_runs_verified:М1,М6", "М6 — ответа нет")
        проход("document_section_started:icd:§6") // строки 6.2 — варианты, 6.1 — базовый с отклонёнными
    }

    /** Требование, пригодное к базированию: носитель, источник, верификация, критерий, шаблон EARS. */
    private fun полное(код: String, уровень: String, носитель: String, формулировка: String) = store.create(
        код, "requirement", область, "8",
        json("""{"level":"$уровень","title":"$код","statement":"$формулировка","category":"functional","priority":"must",
            "carrier":"$носитель","verification_method":"test","acceptance_criteria":"в журнале есть кадр","ears_pattern":"ubiquitous",
            "source":[{"kind":"need","ref":"ND-0001"}]}"""), п,
    )

    @Test
    fun `A7 — условные элементы и резервы технологий`() {
        val ка = store.create("EL-SC", "component", область, "7", json("""{"name":"КА","kind":"element","level":2,"nature":"node"}"""), п)
        полное("RQ-S-0001", "system", ка.id, "КА должен передавать кадр телеметрии раз за виток.")
        проход("conditional_requirements_listed") // незрелых технологий нет — условных элементов нет
        store.create("TECH-1", "technology", область, "10", json("""{"name":"приёмный тракт","component":"${ка.id}","trl_current":4,"trl_required":6,"required_by":"SDR","fallback":""}"""), п)
        отказ("conditional_requirements_listed", "снимка нет")
        отказ("technology_fallback_named:4", "без резерва: приёмный тракт")
        store.update(store.byCode(область, "TECH-1")!!.id, json("""{"name":"приёмный тракт","component":"${ка.id}","trl_current":4,"trl_required":6,"required_by":"SDR","fallback":"покупной тракт"}"""), п)
        проход("technology_fallback_named:4")
        снимки.baseline(проект, "functional-SRR", orbita.requirements.api.BaselineKind.FUNCTIONAL, "SRR", "Чернов Д.")
        проход("conditional_requirements_listed")
        проход("baseline_taken:functional")
        отказ("baseline_taken:allocated", "«allocated» ещё не сделан")
    }

    @Test
    fun `A8 — владелец, меры по критическим`() {
        store.create("RSK-0001", "risk", область, "11", json("""{"statement":"Частоты не выделены","cec":"","category":"regulatory","probability":4,"impact":4,"strategy":"mitigate","measures":"","owner":"","due_point":"SRR"}"""), п)
        отказ("each_risk_has_owner", "RSK-0001")
        отказ("critical_risks_have_measures:12", "RSK-0001")
        store.update(store.byCode(область, "RSK-0001")!!.id, json("""{"statement":"Частоты не выделены","cec":"","category":"regulatory","probability":4,"impact":4,"strategy":"mitigate","measures":"заявка в ГКРЧ","owner":"Минтранс","due_point":"SRR"}"""), п)
        проход("each_risk_has_owner"); проход("critical_risks_have_measures:12")
        store.create("RSK-0002", "risk", область, "11", json("""{"statement":"Мелкий","cec":"","category":"cost","probability":2,"impact":2,"strategy":"accept","measures":"","owner":"РП","due_point":"SDR"}"""), п)
        проход("critical_risks_have_measures:12")
    }

    @Test
    fun `A9 — ОСЗ по нормативу с привязкой и план обеспечения`() {
        отказ("document_started:sma", "документа по шаблону «sma» в проекте нет")
        документы.ensure(проект, "sma", "Чернов Д.")
        отказ("document_started:sma", "ещё пуст")
        отказ("oda_compliant", "оценки засорения нет")
        store.create("ODA-1", "debris_assessment", область, "11", json("""{"variant":"VAR-00","active_lifetime_years":3.8,"deorbit_dv":"30 м/с","passive_lifetime_years":30,"normative_active":"","normative_passive":"","compliant_active":true,"compliant_passive":false,"atmosphere_model":"NRLMSISE","ballistic_coefficient":1,"model_run":""}"""), п)
        отказ("oda_compliant", "пассивный сход вне норматива")
        отказ("oda_normative_bound", "normative_active, normative_passive")
        store.update(store.byCode(область, "ODA-1")!!.id, json("""{"variant":"VAR-00","active_lifetime_years":3.8,"deorbit_dv":"30 м/с","passive_lifetime_years":11,"normative_active":"IADC 02-01","normative_passive":"IADC 02-01","compliant_active":true,"compliant_passive":true,"atmosphere_model":"NRLMSISE","ballistic_coefficient":1,"model_run":""}"""), п)
        проход("oda_compliant"); проход("oda_normative_bound")
        проход("document_started:sma") // строки ОСЗ в §1 — документ начат
    }

    @Test
    fun `A10 — метод оценки и сроки против точек`() {
        отказ("estimate_method:parametric,bottom_up", "оценки нет")
        store.create("CE-1", "cost_estimate", область, "12", json("""{"scope":"05","range":{"min":1,"max":2,"unit":"млн ₽"},"assumptions":"аналог","method":"rom","date":"2027-01-01"}"""), п)
        отказ("estimate_method:parametric,bottom_up", "только методом rom")
        store.create("CE-2", "cost_estimate", область, "A10", json("""{"scope":"05","range":{"min":1,"max":2,"unit":"млн ₽"},"assumptions":"параметрика","method":"parametric","date":"2027-06-01"}"""), п)
        проход("estimate_method:parametric,bottom_up")
        отказ("plan_consistent_with_gates", "WBS не взят")
        store.create("05", "wbs_package", область, "12", json("""{"name":"Космический сегмент","cross_cutting":false,"pbs_refs":["EL-SC"],"phase_scenes":[]}"""), п)
        отказ("plan_consistent_with_gates", "нет сроков")
        программатика.planPackage(проект, "05", "2027-01-01", "2028-06-01", "Чернов Д.")
        отказ("plan_consistent_with_gates", "нет дат")
        store.list(область, "gate").forEach { store.update(it.id, (it.doc.deepCopy() as com.fasterxml.jackson.databind.node.ObjectNode).put("planned_date", "2027-11-01"), п) }
        отказ("plan_consistent_with_gates", "05 → 2028-06-01")
        программатика.planPackage(проект, "05", "2027-01-01", "2027-10-01", "Чернов Д.")
        проход("plan_consistent_with_gates")
    }

    @Test
    fun `A11 — Project Plan и FA новой версии с отклонениями`() {
        отказ("document_started:projectplan", "нет")
        документы.ensure(проект, "projectplan", "Чернов Д.")
        документы.addStatement(проект, "projectplan", "§10", "утверждает РП; актуализация к каждой точке", emptyList(), "Чернов Д.")
        проход("document_started:projectplan")
        документы.ensure(проект, "fa", "Чернов Д.")
        отказ("document_version_min:fa:2", "версии 1 из 2")
        отказ("document_section_started:fa:§6", "раздел §6")
        документы.addStatement(проект, "fa", "§6", "отклонение: PDR совмещён с SDR по классу миссии", emptyList(), "Чернов Д.")
        проход("document_version_min:fa:2"); проход("document_section_started:fa:§6")
    }

    @Test
    fun `A12 — позиции экспертиз и матрица зрелости`() {
        отказ("expertise_positions_reviewed:SDR", "без вердикта")
        val позиции = шаблон.path("points").first { it.path("key").asText() == "SDR" }.path("expertise").path("control_items")
        val все = mapper.createArrayNode()
        позиции.forEach { все.addObject().put("artifact", it.path("artifact").asText()).put("verdict", "принято") }
        записи.recordPositions(проект, "SDR", все, "Чернов Д.")
        проход("expertise_positions_reviewed:SDR")
        отказ("expertise_positions_reviewed:NOPE", "в шаблоне фазы нет")
        val р = условие("maturity_matrix_complete:KDP-B")
        assertFalse(р.passed)
        assertTrue(р.why!!.contains("Д2 · SEMP — документа «semp» нет"), р.why)
        // Матрица без дыр: документы заведены, F базированы, виды есть
        listOf("fa", "semp", "conops", "opscon", "icd", "projectplan").forEach { код ->
            документы.ensure(проект, код, "Чернов Д.")
            документы.document(проект, код, "KDP-B").sections.forEach { р ->
                runCatching { документы.addStatement(проект, код, р.no, "тезис к KDP-B", emptyList(), "Чернов Д.") }
            }
        }
        listOf("semp", "conops").forEach { код ->
            runCatching { документы.baseline(проект, код, "KDP-B", "Чернов Д.") }.onFailure { error("базирование $код: ${it.message}") }
        }
        store.create("RQ-P-01", "requirement", область, "8", json("""{"level":"project","title":"Т","statement":"Телеметрия.","category":"performance","priority":"must"}"""), п)
        store.create("TECH-1", "technology", область, "10", json("""{"name":"тракт","component":"","trl_current":6,"trl_required":6,"required_by":"SDR"}"""), п)
        store.create("RSK-0001", "risk", область, "11", json("""{"statement":"р","cec":"","category":"cost","probability":1,"impact":1,"strategy":"accept","measures":"","owner":"РП","due_point":"SDR"}"""), п)
        store.create("ODA-1", "debris_assessment", область, "11", json("""{"variant":"VAR-00","active_lifetime_years":3,"deorbit_dv":"30","passive_lifetime_years":11,"normative_active":"IADC","normative_passive":"IADC","compliant_active":true,"compliant_passive":true,"atmosphere_model":"m","ballistic_coefficient":1,"model_run":""}"""), п)
        store.create("CE-1", "cost_estimate", область, "12", json("""{"scope":"05","range":{"min":1,"max":2,"unit":"млн ₽"},"assumptions":"а","method":"parametric","date":"2027-06-01"}"""), п)
        val после = условие("maturity_matrix_complete:KDP-B")
        assertTrue(после.passed, после.why)
        assertNotNull(записи.positions(проект, "SDR").path(позиции.first().path("artifact").asText()).path("verdict").asText())
    }
}
