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
                "verification":{"method":"analysis","phase":"PhaseA","means":"Прогон эталонного сценария Монте-Карло",
                  "approach":"Прогон эталонного сценария с фиксированным зерном ГПСЧ и сверка доли доставленных сообщений с целевым значением."},
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
                "verification":{"method":"analysis"},
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
        assertEquals("analysis", row.method)
    }

    @Test
    fun `пустые ячейки перечисляются отдельно как разрывы`() {
        val gaps = matrices.traceMatrix().gaps
        assertTrue(gaps.any { it.requirementId == "RQ-0011" && it.missing == "element" }) { gaps.toString() }
        assertTrue(gaps.none { it.requirementId == "RQ-0010" })
    }

    @Test
    fun `устаревшее свидетельство помечается в матрице верификации`() {
        var row = matrices.verificationMatrix().single { it.requirementId == "RQ-0010" }
        assertEquals("выполнено", row.status)
        assertEquals(false, row.staleEvidence)

        TestDb.conn.createStatement().use { it.execute("UPDATE results SET stale = true WHERE pk = $evidencePk") }
        row = matrices.verificationMatrix().single { it.requirementId == "RQ-0010" }
        assertEquals("не проверено", row.status)
        assertEquals(true, row.staleEvidence)
        TestDb.conn.createStatement().use { it.execute("UPDATE results SET stale = false WHERE pk = $evidencePk") }
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
        val rows = matrices.verificationMatrix()
        val complete = rows.single { it.requirementId == "RQ-0010" }
        assertTrue(!complete.approach.isNullOrBlank()) { "подход обязан быть в матрице" }
        assertEquals("Прогон эталонного сценария Монте-Карло", complete.means)
        // критерий выводится из условия требования с подписью единицы (CR-001 п.6)
        assertEquals("delivery_probability_daily: не менее 0.9 ", complete.successCriterion)
        assertEquals(emptyList<String>(), complete.issues)

        // требование с одним лишь методом попадает в матрицу с перечнем замечаний
        val bare = rows.single { it.requirementId == "RQ-0011" }
        assertEquals(null, bare.approach)
        assertTrue(bare.issues.any { "как именно" in it }) { bare.issues.toString() }
    }
}
