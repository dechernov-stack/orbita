// Контур требований на хранилище (TZ-REQ-001, TZ-REQ-003, TZ-REQ-005,
// TZ-REQ-006, TZ-REQ-007): правила границы, базирование, отчёты целостности.
package orbita.req

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

    private val req = ReqService(TestDb.conn, SchemaRegistry(RepoPaths.schemasDir()))

    private fun requirementJson(
        id: String,
        statement: String = "Система должна обеспечивать вероятность доставки не менее 0,9 за сутки.",
        tracesUp: String = """[{"ref":"SV-0001","consumer_class":"A_prime"}]""",
        allocated: String = """["CM-0001"]""",
        mop: String = """{"name":"delivery_probability_daily","target":{"value":0.9,"unit":"1","provenance":{"source":"manual"}}}""",
    ) = """
        {"id":"$id","level":"system","statement":"$statement","category":"performance",
         "traces_up":$tracesUp,"allocated_to":$allocated,"mop":$mop,
         "verification":{"method":"analysis","phase":"PhaseA"},
         "lifecycle":{"status":"Draft","version":"1"},"owner":"ведущий системный инженер"}
    """

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
            req.ingestRequirement(requirementJson("RQ-0012", allocated = """["CM-0999"]"""))
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
                mop = """{"name":"delivery_probability_daily","tbd":true,
                          "target":{"value":0.9,"unit":"1","provenance":{"source":"manual"}}}""",
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
    fun `устаревшее свидетельство не засчитывается на хранилище`() {
        req.ingestRequirement(requirementJson("RQ-0015"))
        val res = req.results.insert(
            "SC-0001", "kpi-vector",
            com.fasterxml.jackson.databind.ObjectMapper().readTree("""{"value":0.94}"""),
            inputVersions = mapOf("CM-0001" to "1"), moduleVersion = "0.1", rngSeed = 7,
        )
        val cur = req.objects.current("RQ-0015")!!
        val withEvidence = (cur.doc.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>())
            .apply { withObject("/verification").put("evidence_ref", res.pk.toString()) }
        req.objects.change("RQ-0015", withEvidence)
        assertEquals(VerificationStatus.Passed, req.verificationStatusOf("RQ-0015"))

        TestDb.conn.createStatement().use { it.execute("UPDATE results SET stale = true WHERE pk = ${res.pk}") }
        assertEquals(VerificationStatus.NotVerified, req.verificationStatusOf("RQ-0015"))
    }
}
