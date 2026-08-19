// Контур требований на хранилище (TZ-REQ-001, TZ-REQ-003, TZ-REQ-005,
// TZ-REQ-006, TZ-REQ-007): правила границы, базирование, отчёты целостности.
package orbita.req

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.Lifecycle
import orbita.mod.schema.SchemaRegistry
import orbita.mod.store.BaselineChangeException
import orbita.mod.store.ModelViolationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.assertThrows
import java.time.OffsetDateTime

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ReqStoreTest {

    private val mapper = ObjectMapper()
    private val req = ReqService(TestDb.conn, SchemaRegistry(RepoPaths.schemasDir()))

    private fun requirementJson(
        id: String,
        statement: String = "Система должна обеспечивать вероятность доставки не менее 0,9 за сутки.",
        tracesUp: String = """[{"ref":"SV-0001","consumer_class":"A_prime"}]""",
        allocated: String = """[{"component":"CM-0001","kind":"full"}]""",
        mop: String = """{"name":"delivery_probability_daily","operator":"ge","value":{"value":0.9,"unit":"1","provenance":{"source":"manual"}}}""",
        events: String = VERIFICATION_EVENTS,
    ) = """
        {"id":"$id","level":"system","statement":"$statement","category":"performance",
         "traces_up":$tracesUp,"allocated_to":$allocated,"mop":$mop,
         $events,
         "lifecycle":{"status":"Draft","version":"1"},"owner":"ведущий системный инженер"}
    """

    /**
     * План верификации событиями (CR-003): предварительный расчёт по модели
     * и закрывающее квалификационное испытание.
     */
    private val VERIFICATION_EVENTS = """"verification_events":[{"id":"VE-0001","method":"analysis","phase":"PhaseA","level":"system","kind":"preliminary","approach":"Прогон эталонного сценария с фиксированным зерном ГПСЧ и сверка доли доставленных сообщений.","means":"Модель Монте-Карло","status":"passed","closes":false},{"id":"VE-0002","method":"test","phase":"PhaseD","level":"system","kind":"qualification","approach":"Натурные испытания канала на полигоне с фиксацией доли доставленных сообщений в протоколе.","means":"Полигонный стенд","status":"passed","closes":true,"design_version":"v1"}]"""

    @BeforeAll
    fun setup() {
        TestDb.truncateAll()
        req.ingestNeed(
            """{"id":"ND-0001","statement":"Оперативный сбор данных с датчиков в удалённых районах.",
                "stakeholder":{"name":"Оператор системы","role":"operator","priority":2},
                "lifecycle":{"status":"Draft","version":"1"}}"""
        )
        req.ingestNeed(
            """{"id":"ND-0002","statement":"Контроль состояния протяжённой инфраструктуры на севере.",
                "stakeholder":{"name":"Недропользователь","role":"customer","priority":1},
                "lifecycle":{"status":"Draft","version":"1"}}"""
        )
        req.ingestService(
            """{"id":"SV-0001","name":"Сбор телеметрии датчиков","traces_up":["ND-0001"],
                "qos_profiles":[{"consumer_class":"A_prime","moe":[{"id":"MOE-0001","name":"delivery_probability_daily",
                  "target":{"value":0.9,"unit":"1","provenance":{"source":"manual"}}}]}],
                "lifecycle":{"status":"Draft","version":"1"}}"""
        )
        req.ingestComponent(
            """{"id":"CM-0001","name":"Космический аппарат","kind":"system",
                "lifecycle":{"status":"Draft","version":"1"}}"""
        )
        req.ingestComponent(
            """{"id":"CM-0002","name":"Наземный сегмент","kind":"segment","segment":"ground",
                "lifecycle":{"status":"Draft","version":"1"}}"""
        )
        req.ingestRequirement(requirementJson("RQ-0010"))
    }

    @Test
    fun `ссылка требования на сервис без класса потребителя отклоняется`() {
        val e = assertThrows<ModelViolationException> {
            req.ingestRequirement(requirementJson("RQ-0011", tracesUp = """[{"ref":"SV-0001"}]"""))
        }
        assertTrue("consumer_class" in e.message!! && "Р9" in e.message!!)
    }

    @Test
    fun `распределение на несуществующий элемент отклоняется`() {
        val e = assertThrows<ModelViolationException> {
            req.ingestRequirement(requirementJson("RQ-0012", allocated = """[{"component":"CM-0999"}]"""))
        }
        assertTrue("TZ-REQ-005" in e.message!!)
    }

    @Test
    fun `связи выводятся из документа, матрица не заполняется вручную`() {
        assertEquals(listOf("SV-0001"), req.links.linksTo("RQ-0010", "trace").map { it.fromId })
        assertEquals("A_prime", req.links.linksTo("RQ-0010", "trace").single().consumerClass)
        assertEquals(listOf("CM-0001"), req.links.linksFrom("RQ-0010", "allocation").map { it.toId })
        assertEquals(listOf("ND-0001", "SV-0001"), req.links.ancestors("RQ-0010").map { it.id }.sorted())
    }

    @Test
    fun `требование с нарушением правил качества не переводится в Baseline`() {
        req.ingestRequirement(
            requirementJson("RQ-0013", statement = "Обеспечивается приём данных с достаточной скоростью.")
        )
        val e = assertThrows<BaselineBlockedException> { req.promote("RQ-0013", Lifecycle.Baseline) }
        assertTrue(e.reasons.any { "модального" in it })
        assertTrue(e.reasons.any { "неизмеримое" in it })
        // нарушения не блокируют Draft: объект сохранён
        assertEquals(Lifecycle.Draft, req.objects.current("RQ-0013")!!.status)
    }

    @Test
    fun `требование с незакрытым TBD не переводится в Baseline`() {
        req.ingestRequirement(
            requirementJson(
                "RQ-0014",
                mop = """{"name":"delivery_probability_daily","operator":"ge","tbd":true,
                          "value":{"value":0.9,"unit":"1","provenance":{"source":"manual"}}}""",
            )
        )
        val e = assertThrows<BaselineBlockedException> { req.promote("RQ-0014", Lifecycle.Baseline) }
        assertTrue(e.reasons.any { "TBD" in it })
    }

    @Test
    @Order(1)
    fun `пригодное требование базируется, история статусов сохраняется`() {
        req.promote("RQ-0010", Lifecycle.Preliminary, at = OffsetDateTime.parse("2027-01-01T00:00:00Z"))
        req.promote("RQ-0010", Lifecycle.Approved, at = OffsetDateTime.parse("2027-02-01T00:00:00Z"))
        req.promote("RQ-0010", Lifecycle.Baseline, at = OffsetDateTime.parse("2027-03-01T00:00:00Z"))
        assertEquals(Lifecycle.Baseline, req.objects.current("RQ-0010")!!.status)
        // история переходов доступна; срез на дату отдаёт статус на момент
        assertEquals(
            Lifecycle.Preliminary,
            req.objects.sliceAt(OffsetDateTime.parse("2027-01-15T00:00:00Z"))
                .single { it.id == "RQ-0010" }.status,
        )
        // Baseline меняется только процедурой изменения
        assertThrows<BaselineChangeException> { req.promote("RQ-0010", Lifecycle.Approved) }
    }

    @Test
    fun `нужда без сервисов-потомков выявляется отчётом`() {
        val report = req.needsWithoutServices()
        assertTrue("ND-0002" in report)
        assertFalse("ND-0001" in report)
    }

    @Test
    fun `элементы без назначенных требований выявляются отчётом`() {
        val report = req.elementsWithoutRequirements()
        assertTrue("CM-0002" in report)
        assertFalse("CM-0001" in report)
    }

    @Test
    @Order(2)
    fun `изменение источника помечает требование к пересмотру`() {
        assertFalse("RQ-0010" in req.reviewCandidates())
        req.objects.change(
            "SV-0001",
            req.objects.current("SV-0001")!!.doc,
            changeRef = "CR-100",
            at = OffsetDateTime.parse("2027-06-01T00:00:00Z"),
        )
        assertTrue("RQ-0010" in req.reviewCandidates())
    }

    @Test
    fun `свидетельство прежней конфигурации помечается неприменимым`() {
        // CR-003: свидетельство — объект EV-NNNN, привязанный к конфигурации
        req.ingestEvidence(
            """{"id":"EV-0001","kind":"analysis_report","maturity":"preliminary",
                "source":{"scenario_ref":"SC-0001"},"configuration":"C1","date":"2026-03-01T00:00:00Z"}"""
        )
        req.ingestEvidence(
            """{"id":"EV-0002","kind":"test_report","maturity":"final",
                "source":{"document":"Протокол испытаний 12-2027"},
                "configuration":"C1","date":"2027-06-01T00:00:00Z"}"""
        )
        val preliminary = req.objects.current("EV-0001")!!.doc
        val finalDoc = req.objects.current("EV-0002")!!.doc

        assertEquals(EvidenceState.Valid, evidenceState(finalDoc, "C1"))
        // после изменения конструкции протокол относится к другому изделию
        assertEquals(EvidenceState.NotApplicable, evidenceState(finalDoc, "C2"))
        // цепочка «расчёт по модели → испытание» упорядочена по времени
        assertEquals(listOf("EV-0001", "EV-0002"), evidenceChain(listOf(finalDoc, preliminary)))
    }

    @Test
    fun `предварительный расчёт не закрывает требование, испытание закрывает`() {
        val prelim = """{"id":"VE-0001","method":"analysis","phase":"PhaseA","level":"system",
            "kind":"preliminary","status":"passed","closes":false,"means":"Модель MEL",
            "approach":"Расчёт по модели с резервами по зрелости элементов и сверкой с аналогами."}"""
        val planned = """{"id":"VE-0002","method":"test","phase":"PhaseD","level":"system",
            "kind":"qualification","status":"planned","closes":true,"design_version":"v1",
            "means":"Полигонный стенд",
            "approach":"Натурные испытания канала с фиксацией доли доставленных сообщений в протоколе."}"""
        req.ingestRequirement(
            requirementJson("RQ-0060", events = """"verification_events":[$prelim,$planned]""")
        )
        assertEquals(VerificationState.PreliminarilyConfirmed, req.verificationStateOf("RQ-0060"))

        // успешное закрывающее событие переводит требование в «верифицировано»
        val doc = req.objects.current("RQ-0060")!!.doc.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        (doc.path("verification_events").get(1) as com.fasterxml.jackson.databind.node.ObjectNode)
            .put("status", "passed")
        req.objects.change("RQ-0060", doc, at = OffsetDateTime.parse("2027-12-01T00:00:00Z"))
        assertEquals(VerificationState.Verified, req.verificationStateOf("RQ-0060"))
    }

    @Test
    fun `предварительное событие не может быть закрывающим — отклоняет БД`() {
        val bad = """"verification_events":[{"id":"VE-0001","method":"analysis","phase":"PhaseA",
            "level":"system","kind":"preliminary","status":"passed","closes":true,
            "means":"Модель","approach":"Расчёт по модели с резервами и сверкой с аналогами платформ."}]"""
        val e = assertThrows<java.sql.SQLException> {
            req.objects.create("RQ-0061", "requirement", mapper.readTree(requirementJson("RQ-0061", events = bad)))
        }
        assertTrue("preliminary_not_closing" in (e.message ?: "")) { e.message ?: "" }
    }

    @Test
    fun `требование-потомок на элементе из чужой ветви выявляется отчётом`() {
        req.ingestComponent(
            """{"id":"CM-0004","name":"Бортовой комплекс","kind":"subsystem","parent":"CM-0001",
                "lifecycle":{"status":"Draft","version":"1"}}"""
        )
        req.ingestRequirement(
            requirementJson("RQ-0070", allocated = """[{"component":"CM-0001","kind":"full"}]""")
        )
        // потомок на подчинённом элементе — согласовано
        req.ingestRequirement(
            requirementJson("RQ-0071", allocated = """[{"component":"CM-0004","kind":"full"}]""")
                .let { it.dropLast(it.length - it.lastIndexOf("}")) + ""","derives_from":["RQ-0070"]}""" }
        )
        assertTrue(req.inconsistentAllocations().none { it.second == "RQ-0071" })

        // потомок на элементе из чужой ветви — выявляется
        req.ingestComponent(
            """{"id":"CM-0005","name":"Наземная станция","kind":"subsystem","parent":"CM-0002",
                "lifecycle":{"status":"Draft","version":"1"}}"""
        )
        req.ingestRequirement(
            requirementJson("RQ-0072", allocated = """[{"component":"CM-0005","kind":"full"}]""")
                .let { it.dropLast(it.length - it.lastIndexOf("}")) + ""","derives_from":["RQ-0070"]}""" }
        )
        val stray = req.inconsistentAllocations().single { it.second == "RQ-0072" }
        assertTrue("вне области родителя" in stray.third) { stray.third }

        // спецификация элемента собирается запросом, без отдельной сущности
        assertEquals(listOf("RQ-0071"), req.specificationOf("CM-0004"))
    }

    @Test
    fun `интерфейсное требование на одном элементе отклоняется`() {
        req.ingestInterface(
            """{"id":"IF-0001","name":"Стык борт — наземный сегмент","kind":"interface",
                "owners":["CM-0001","CM-0002"],"lifecycle":{"status":"Draft","version":"1"}}"""
        )
        // интерфейсное требование на элементе — отклоняется
        val e = assertThrows<ModelViolationException> {
            req.ingestRequirement(
                requirementJson("RQ-0080", allocated = """[{"component":"CM-0001","kind":"full"}]""")
                    .replace(""""category":"performance"""", """"category":"interface"""")
            )
        }
        assertTrue("интерфейс" in e.message!!) { e.message!! }

        // на интерфейсе с двумя сторонами — принимается
        req.ingestRequirement(
            requirementJson("RQ-0081", allocated = """[{"interface":"IF-0001","kind":"full"}]""")
                .replace(""""category":"performance"""", """"category":"interface"""")
        )
        assertEquals(listOf("IF-0001"), req.links.linksFrom("RQ-0081", "allocation").map { it.toId })
    }

    @Test
    fun `производное требование не входит в свёртку бюджета родителя`() {
        req.ingestRequirement(
            requirementJson(
                "RQ-0090",
                statement = "Масса бортового комплекса не должна превышать 40 кг.",
                mop = """{"name":"Масса","operator":"le","rollup":"sum",
                          "value":{"value":40,"unit":"kg","provenance":{"source":"manual"}}}""",
            )
        )
        // распределённый потомок — в бюджете
        req.ingestRequirement(
            requirementJson(
                "RQ-0091",
                statement = "Масса приёмного тракта не должна превышать 30 кг.",
                mop = """{"name":"Масса","operator":"le",
                          "value":{"value":30,"unit":"kg","provenance":{"source":"manual"}}}""",
            ).let { it.dropLast(it.length - it.lastIndexOf("}")) + ""","derives_from":["RQ-0090"]}""" }
        )
        // производное требование — вне бюджета, хотя величина превысила бы его
        req.ingestRequirement(
            requirementJson(
                "RQ-0092",
                statement = "Масса технологической оснастки не должна превышать 25 кг.",
                mop = """{"name":"Масса","operator":"le",
                          "value":{"value":25,"unit":"kg","provenance":{"source":"manual"}}}""",
            ).let { it.dropLast(it.length - it.lastIndexOf("}")) + ""","derives_from":["RQ-0090"]}""" }
        )
        // вид декомпозиции — свойство связи: помечаем требование производным
        req.deriveAs("RQ-0090", "RQ-0092", "derived")
        val rollup = req.rollupFor("RQ-0090")
        assertEquals(30.0, rollup.aggregate) { "производное требование не входит в свёртку" }
        assertEquals(true, rollup.consistent)
        assertEquals(10.0, rollup.remaining!!, 1e-9)
    }

    @Test
    fun `валидация с привязкой к требованию отклоняется`() {
        // схема ловит привязку к требованию шаблоном цели, прикладное правило — вторым барьером
        val e = assertThrows<orbita.mod.schema.SchemaValidationException> {
            req.ingestValidation(
                """{"id":"VA-0002","target":"RQ-0010","conops_ref":"CO-0001","product_kind":"model",
                    "method":"demonstration","status":"planned"}"""
            )
        }
        assertTrue(e.errors.any { it.path == "/target" }) { e.errors.toString() }
        assertTrue(
            validationIssues(mapper.readTree("""{"target":"RQ-0010","conops_ref":"CO-1","product_kind":"model"}"""))
                .any { "а не к ожиданию" in it }
        )

        // привязка к нужде — принимается
        req.ingestValidation(
            """{"id":"VA-0003","target":"ND-0001","conops_ref":"CO-0001","product_kind":"model",
                "method":"demonstration","status":"planned"}"""
        )
        assertEquals("ND-0001", req.objects.current("VA-0003")!!.doc.path("target").asText())
    }

    @Test
    fun `объект валидации без цели отклоняется БД`() {
        val e = assertThrows<java.sql.SQLException> {
            req.objects.create(
                "VA-0004", "validation",
                mapper.readTree("""{"id":"VA-0004","conops_ref":"CO-0001","product_kind":"model"}"""),
            )
        }
        assertTrue("validation_target" in (e.message ?: "")) { e.message ?: "" }
    }
}
