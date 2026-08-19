// Матрицы и отчёт зрелости (TZ-OUT-003, TZ-OUT-004): формируются из связей и
// истории версий без ручного заполнения; stale-свидетельство помечается;
// отчёт строится на произвольную дату.
package orbita.out

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.Lifecycle
import orbita.mod.schema.SchemaRegistry
import orbita.req.ReqService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.OffsetDateTime

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MatricesAndMaturityTest {

    private val mapper = ObjectMapper()
    private val req = ReqService(TestDb.conn, SchemaRegistry(RepoPaths.schemasDir()))
    private val matrices = Matrices(req)
    private val maturity = MaturityReports(req)
    private var evidencePk = 0L

    @BeforeAll
    fun setup() {
        TestDb.truncateAll()
        req.ingestNeed(
            """{"id":"ND-0001","statement":"Оперативный сбор данных с датчиков в удалённых районах.",
                "stakeholder":{"name":"Оператор системы","role":"operator","priority":2},
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
        req.ingestRequirement(
            """{"id":"RQ-0010","level":"system",
                "statement":"Система должна обеспечивать вероятность доставки не менее 0,9 за сутки.",
                "category":"performance",
                "traces_up":[{"ref":"SV-0001","consumer_class":"A_prime"}],
                "allocated_to":[{"component":"CM-0001","kind":"full"}],
                "mop":{"name":"delivery_probability_daily","operator":"ge","value":{"value":0.9,"unit":"1","provenance":{"source":"manual"}}},
                "verification_events":[{"id":"VE-0001","method":"analysis","phase":"PhaseA","level":"system","kind":"preliminary","approach":"Прогон эталонного сценария с фиксированным зерном ГПСЧ и сверка доли доставленных сообщений.","means":"Модель Монте-Карло","status":"passed","closes":false},{"id":"VE-0002","method":"test","phase":"PhaseD","level":"system","kind":"qualification","approach":"Натурные испытания канала на полигоне с фиксацией доли доставленных сообщений в протоколе.","means":"Полигонный стенд","status":"passed","closes":true,"design_version":"v1"}],
                "lifecycle":{"status":"Draft","version":"1"},"owner":"ведущий системный инженер"}"""
        )
        // требование с разрывами: без распределения и без метода нет — метод обязателен схемой,
        // поэтому разрыв демонстрируется отсутствием элемента и TBD
        req.ingestRequirement(
            """{"id":"RQ-0011","level":"system",
                "statement":"Система должна передавать подтверждение доставки в пределах суток.",
                "category":"functional",
                "traces_up":[{"ref":"SV-0001","consumer_class":"B_prime"}],
                "mop":{"name":"ack_probability","operator":"ge","tbd":true,"value":{"value":0.95,"unit":"1","provenance":{"source":"manual"}}},
                "verification_events":[{"id":"VE-0001","method":"analysis","phase":"PhaseA","level":"system","kind":"qualification","status":"planned","closes":true,"design_version":"v1"}],
                "lifecycle":{"status":"Draft","version":"1"},"owner":"аналитик сервисов"}"""
        )
        val res = req.results.insert(
            "SC-0001", "kpi-vector", mapper.readTree("""{"value":0.94}"""),
            inputVersions = mapOf("CM-0001" to "1"), moduleVersion = "0.1", rngSeed = 42,
        )
        evidencePk = res.pk
        val cur = req.objects.current("RQ-0010")!!
        val withEvidence = (cur.doc.deepCopy<ObjectNode>())
            .apply { withObject("/verification").put("evidence_ref", res.pk.toString()) }
        req.objects.change("RQ-0010", withEvidence)
    }

    @Test
    fun `матрица трассировки формируется из связей без ручного заполнения`() {
        val m = matrices.traceMatrix()
        val row = m.rows.single { it.requirementId == "RQ-0010" }
        assertEquals(listOf("ND-0001"), row.needs)
        assertEquals("SV-0001" to "A_prime", row.services.single().let { it.id to it.consumerClass })
        assertEquals(listOf("CM-0001"), row.elements)
        // CR-003: метод берётся из закрывающего события — им является испытание
        assertEquals("test", row.method)
    }

    @Test
    fun `пустые ячейки перечисляются отдельно как разрывы`() {
        val gaps = matrices.traceMatrix().gaps
        assertTrue(gaps.any { it.requirementId == "RQ-0011" && it.missing == "element" }) { gaps.toString() }
        assertTrue(gaps.none { it.requirementId == "RQ-0010" })
    }

    @Test
    fun `матрица верификации даёт строку на каждое событие и состояние требования`() {
        val row = matrices.verificationMatrix().single { it.requirementId == "RQ-0010" }
        // «сначала расчёт по модели, потом испытание» — два события, не одна ячейка
        assertEquals(2, row.events.size)
        assertEquals(listOf("preliminary", "qualification"), row.events.map { it.kind })
        assertEquals(listOf(false, true), row.events.map { it.closes })
        assertEquals("верифицировано", row.state)
        assertEquals(emptyList<String>(), row.planIssues)

        // требование с одним лишь методом: план неполон по содержанию событий
        val bare = matrices.verificationMatrix().single { it.requirementId == "RQ-0011" }
        assertEquals("запланирована", bare.state)
        assertTrue(bare.planIssues.any { "как выполняется" in it }) { bare.planIssues.toString() }
    }

    @Test
    fun `предварительный расчёт не закрывает требование`() {
        // событие испытания переводится в «запланировано»: остаётся только предварительный успех
        val cur = req.objects.current("RQ-0010")!!
        val doc = cur.doc.deepCopy<ObjectNode>()
        (doc.path("verification_events").get(1) as ObjectNode).put("status", "planned")
        req.objects.change("RQ-0010", doc, at = cur.validFrom.plusDays(1))
        assertEquals("предварительно подтверждено", matrices.verificationMatrix()
            .single { it.requirementId == "RQ-0010" }.state)
        assertTrue("RQ-0010" in matrices.unverifiedRequirements())

        // возврат к успешному закрывающему событию
        val mid = req.objects.current("RQ-0010")!!
        val back = mid.doc.deepCopy<ObjectNode>()
        (back.path("verification_events").get(1) as ObjectNode).put("status", "passed")
        req.objects.change("RQ-0010", back, at = mid.validFrom.plusDays(1))
        assertEquals("верифицировано", matrices.verificationMatrix()
            .single { it.requirementId == "RQ-0010" }.state)
    }

    @Test
    fun `матрица валидации формируется отдельно от матрицы верификации`() {
        req.ingestValidation(
            """{"id":"VA-0001","target":"ND-0001","conops_ref":"CO-0003","product_kind":"model",
                "method":"demonstration","phase":"PhaseA","status":"planned",
                "approach":"Прогон сценария ConOps на модели фазы с участием представителя оператора."}"""
        )
        val validation = matrices.validationMatrix()
        assertEquals(listOf("VA-0001"), validation.map { it.validationId })
        assertEquals("ND-0001", validation.single().target)
        assertEquals("model", validation.single().productKind)
        assertEquals(emptyList<String>(), validation.single().issues)
        // валидация не смешивается с верификацией: наборы объектов разные
        assertTrue(matrices.verificationMatrix().none { it.requirementId == "VA-0001" })
    }

    @Test
    fun `отчёт зрелости группируется по типам и несёт блокирующие причины`() {
        val report = maturity.build("SRR")
        assertTrue("requirement" in report.gapsByType)
        assertTrue(report.gapsByType.getValue("requirement").any { it.id == "RQ-0011" })
        assertTrue(report.openTbd.any { it.id == "RQ-0011" && it.owner == "аналитик сервисов" })
        assertTrue(report.blockingReasons().isNotEmpty())
        assertEquals(false, report.ready())
    }

    @Test
    fun `отчёт зрелости формируется на произвольную дату по истории статусов`() {
        req.promote("RQ-0010", Lifecycle.Preliminary, at = OffsetDateTime.parse("2027-01-01T00:00:00Z"))
        req.promote("RQ-0010", Lifecycle.Approved, at = OffsetDateTime.parse("2027-02-01T00:00:00Z"))

        val early = maturity.build("SRR", at = OffsetDateTime.parse("2027-01-15T00:00:00Z"))
        assertEquals(
            "Preliminary",
            early.gapsByType.getValue("requirement").single { it.id == "RQ-0010" }.actual,
        )
        val later = maturity.build("SRR", at = OffsetDateTime.parse("2027-02-15T00:00:00Z"))
        assertEquals(
            "Approved",
            later.gapsByType.getValue("requirement").single { it.id == "RQ-0010" }.actual,
        )
    }

    @Test
    fun `матрица верификации содержит подход и критерий`() {
        val row = matrices.verificationMatrix().single { it.requirementId == "RQ-0010" }
        val closing = row.events.single { it.closes }
        assertTrue(!closing.approach.isNullOrBlank()) { "подход обязан быть в матрице" }
        assertEquals("Полигонный стенд", closing.means)
        // критерий выводится из условия требования с подписью единицы (CR-001 п.6)
        assertEquals("delivery_probability_daily: не менее 0.9 ", closing.successCriterion)
        assertEquals(emptyList<String>(), closing.issues)
    }
}
