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
        verification: String = VERIFICATION,
    ) = """
        {"id":"$id","level":"system","statement":"$statement","category":"performance",
         "traces_up":$tracesUp,"allocated_to":$allocated,"mop":$mop,
         "verification":$verification,
         "lifecycle":{"status":"Draft","version":"1"},"owner":"ведущий системный инженер"}
    """

    /** Содержательное описание проверки (CR-002): метод сам по себе не делает требование проверяемым. */
    private val VERIFICATION = """
        {"method":"analysis","phase":"PhaseA","means":"Прогон эталонного сценария Монте-Карло",
         "approach":"Прогон эталонного сценария с фиксированным зерном ГПСЧ и сверка доли доставленных сообщений с целевым значением показателя."}
    """.trimIndent()

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

    // ---------- CR-001 / CR-002 на хранилище ----------

    @Test
    fun `требование с показателем без оператора отклоняется схемой и БД`() {
        // схема: mop.operator обязателен
        val e = assertThrows<orbita.mod.schema.SchemaValidationException> {
            req.ingestRequirement(
                requirementJson(
                    "RQ-0020",
                    mop = """{"name":"delivery_probability_daily","value":{"value":0.9,"unit":"1","provenance":{"source":"manual"}}}""",
                )
            )
        }
        assertTrue(e.errors.any { "operator" in it.message }) { e.errors.toString() }

        // БД: страховка от обхода валидации схемы (V003, ограничение mop_has_operator)
        val raw = mapper.readTree(
            requirementJson(
                "RQ-0021",
                mop = """{"name":"delivery_probability_daily","value":{"value":0.9,"unit":"1","provenance":{"source":"manual"}}}""",
            )
        )
        val dbError = assertThrows<java.sql.SQLException> {
            req.objects.create("RQ-0021", "requirement", raw)
        }
        assertTrue("mop_has_operator" in (dbError.message ?: "")) { dbError.message ?: "" }
    }

    @Test
    fun `свёртка бюджетов по связям derive выявляет превышение с остатком`() {
        req.ingestRequirement(
            requirementJson(
                "RQ-0030",
                statement = "Сухая масса аппарата не должна превышать 100 кг.",
                mop = """{"name":"Сухая масса","operator":"le","rollup":"sum",
                          "value":{"value":100,"unit":"kg","provenance":{"source":"manual"}}}""",
            )
        )
        // декомпозиция: связь derive, не trace (ADR-017)
        listOf("RQ-0031" to 60, "RQ-0032" to 30).forEach { (id, kg) ->
            req.ingestRequirement(
                requirementJson(
                    id,
                    statement = "Масса составной части не должна превышать $kg кг.",
                    mop = """{"name":"Масса части","operator":"le",
                              "value":{"value":$kg,"unit":"kg","provenance":{"source":"manual"}}}""",
                ).let { it.dropLast(it.length - it.lastIndexOf("}")) + ""","derives_from":["RQ-0030"]}""" }
            )
        }
        val ok = req.rollupFor("RQ-0030")
        assertTrue(ok.applicable && ok.consistent == true) { "$ok" }
        assertEquals(10.0, ok.remaining!!, 1e-9)
        assertTrue(req.inconsistentDecompositions().none { it.first == "RQ-0030" })

        // добавление третьей части выводит сумму за родительский бюджет
        req.ingestRequirement(
            requirementJson(
                "RQ-0033",
                statement = "Масса служебной аппаратуры не должна превышать 20 кг.",
                mop = """{"name":"Масса части","operator":"le",
                          "value":{"value":20,"unit":"kg","provenance":{"source":"manual"}}}""",
            ).let { it.dropLast(it.length - it.lastIndexOf("}")) + ""","derives_from":["RQ-0030"]}""" }
        )
        val bad = req.rollupFor("RQ-0030")
        assertEquals(false, bad.consistent)
        assertTrue(bad.remaining!! < 0) { "остаток ${bad.remaining}" }
        assertTrue(req.inconsistentDecompositions().any { it.first == "RQ-0030" })
    }

    @Test
    fun `частичное распределение несёт вид и обоснование`() {
        req.ingestComponent(
            """{"id":"CM-0003","name":"Терминал потребителя","kind":"subsystem",
                "lifecycle":{"status":"Draft","version":"1"}}"""
        )
        req.ingestRequirement(
            requirementJson(
                "RQ-0040",
                allocated = """[{"component":"CM-0001","kind":"partial","rationale":"бортовая часть контура"},
                                {"component":"CM-0003","kind":"partial","rationale":"абонентская часть контура"}]""",
            )
        )
        val links = req.links.linksFrom("RQ-0040", "allocation")
        assertEquals(2, links.size)
        assertTrue(links.all { it.allocationKind == "partial" && !it.rationale.isNullOrBlank() }) { links.toString() }
    }

    @Test
    fun `требование без описания подхода к проверке не переводится в Baseline`() {
        req.ingestRequirement(
            requirementJson("RQ-0050", verification = """{"method":"analysis","phase":"PhaseA"}""")
        )
        val e = assertThrows<BaselineBlockedException> { req.promote("RQ-0050", Lifecycle.Baseline) }
        assertTrue(e.reasons.any { "как именно" in it }) { e.reasons.toString() }
        // черновик при этом сохраняется: полнота — условие базирования, не сохранения
        assertEquals(Lifecycle.Draft, req.objects.current("RQ-0050")!!.status)

        // пересказ формулировки тоже не проходит (порог доли общих значимых слов)
        req.ingestRequirement(
            requirementJson(
                "RQ-0051",
                statement = "Сухая масса космического аппарата не должна превышать 100 кг.",
                mop = """{"name":"Сухая масса","operator":"le",
                          "value":{"value":100,"unit":"kg","provenance":{"source":"manual"}}}""",
                verification = """{"method":"analysis","means":"MEL",
                  "approach":"Проверить, что сухая масса космического аппарата не превышает 100 кг."}""",
            )
        )
        val e2 = assertThrows<BaselineBlockedException> { req.promote("RQ-0051", Lifecycle.Baseline) }
        assertTrue(e2.reasons.any { "пересказывает" in it }) { e2.reasons.toString() }
    }
}
