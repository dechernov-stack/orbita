// Перенос исполняемого эталона spec/storage_semantics.py на PostgreSQL — один в один,
// 19 проверок в четырёх группах (STEP-1 §1.6). Названия проверок сохранены.
// Расхождение реализации с эталоном — дефект реализации, а не эталона (START-HERE).
package orbita.mod

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.model.Lifecycle
import orbita.mod.store.BaselineChangeException
import orbita.mod.store.CycleException
import orbita.mod.store.IdReuseException
import orbita.mod.store.LinkStore
import orbita.mod.store.ModelViolationException
import orbita.mod.store.ObjectStore
import orbita.mod.store.ParamStore
import orbita.mod.store.ResultStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.time.OffsetDateTime

private val mapper = ObjectMapper()
private fun ts(s: String): OffsetDateTime = OffsetDateTime.parse(s)
private fun doc(vararg pairs: Pair<String, String>) =
    mapper.createObjectNode().apply { pairs.forEach { (k, v) -> put(k, v) } }
private fun prov(json: String) = mapper.readTree(json)

class StorageSemanticsTest {

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("TZ-COM-002 / TZ-REQ-003: двунаправленный обход")
    inner class Group1Traversal {

        private val links = LinkStore(TestDb.conn)

        @BeforeAll
        fun setup() {
            TestDb.truncateAll()
            val objects = ObjectStore(TestDb.conn)
            listOf(
                "ND-0001" to "need", "SV-0001" to "service", "RQ-0001" to "requirement",
                "RQ-0002" to "requirement", "CM-0001" to "component",
            ).forEach { (id, type) -> objects.create(id, type, doc(), validFrom = ts("2026-01-01T00:00:00Z")) }
            listOf(
                "ND-0001" to "SV-0001", "SV-0001" to "RQ-0001",
                "RQ-0001" to "RQ-0002", "RQ-0002" to "CM-0001",
            ).forEach { (f, t) -> links.add(f, t) }
        }

        @Test
        fun `предки RQ-0002 до нужды`() =
            assertEquals(listOf("ND-0001", "RQ-0001", "SV-0001"), links.ancestors("RQ-0002").map { it.id }.sorted())

        @Test
        fun `потомки ND-0001 до элемента`() =
            assertEquals(
                listOf("CM-0001", "RQ-0001", "RQ-0002", "SV-0001"),
                links.descendants("ND-0001").map { it.id }.sorted(),
            )

        @Test
        fun `связь хранится один раз`() = assertEquals(4L, links.count())
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("TZ-COM-003 / TZ-MOD-007: базирование и версионность")
    inner class Group2Baselining {

        private val objects = ObjectStore(TestDb.conn)
        private var rejectedWithoutBasis: Throwable? = null
        private var idReuseRejected: Throwable? = null

        @BeforeAll
        fun setup() {
            TestDb.truncateAll()
            objects.create(
                "RQ-0100", "requirement", doc("statement" to "v1"),
                status = Lifecycle.Baseline, validFrom = ts("2026-01-01T00:00:00Z"),
            )
            rejectedWithoutBasis = runCatching {
                objects.change("RQ-0100", doc("statement" to "v2"), at = ts("2026-02-01T00:00:00Z"))
            }.exceptionOrNull()
            objects.change(
                "RQ-0100", doc("statement" to "v2"),
                changeRef = "CR-001", at = ts("2026-02-01T00:00:00Z"),
            )
            objects.create(
                "RQ-0101", "requirement", doc(),
                status = Lifecycle.Cancelled, validFrom = ts("2026-01-01T00:00:00Z"),
            )
            idReuseRejected = runCatching {
                objects.create("RQ-0100", "requirement", doc(), validFrom = ts("2026-03-01T00:00:00Z"))
            }.exceptionOrNull()
        }

        @Test
        fun `изменение Baseline без основания отклонено`() =
            assertInstanceOf(BaselineChangeException::class.java, rejectedWithoutBasis)

        @Test
        fun `новая версия текущая`() {
            val cur = objects.current("RQ-0100")!!
            assertEquals(
                Triple("2", Lifecycle.Draft, "v2"),
                Triple(cur.version, cur.status, cur.doc["statement"].asText()),
            )
        }

        @Test
        fun `предыдущая версия доступна`() {
            val old = objects.history("RQ-0100").single { it.validTo != null }
            assertEquals("1" to "v1", old.version to old.doc["statement"].asText())
        }

        @Test
        fun `текущая версия ровно одна`() =
            assertEquals(1, objects.history("RQ-0100").count { it.validTo == null })

        @Test
        fun `повторный ID отклонён`() =
            assertInstanceOf(IdReuseException::class.java, idReuseRejected)

        @Test
        fun `Cancelled сохраняется и доступен`() =
            assertEquals(Lifecycle.Cancelled, objects.current("RQ-0101")!!.status)

        @Test
        fun `срез на дату отдаёт версию 1`() =
            assertEquals(
                "1",
                objects.sliceAt(ts("2026-01-15T00:00:00Z")).single { it.id == "RQ-0100" }.version,
            )

        // Сверх эталона: страховка DDL от обхода процедуры прямым UPDATE (STEP-1, ловушка 3).
        @Test
        fun `прямой UPDATE Baseline-объекта пресекается триггером`() {
            objects.create(
                "RQ-0102", "requirement", doc("statement" to "b1"),
                status = Lifecycle.Baseline, validFrom = ts("2026-01-01T00:00:00Z"),
            )
            val e = assertThrows<java.sql.SQLException> {
                TestDb.conn.createStatement().use {
                    it.execute("""UPDATE objects SET doc = '{"statement":"hacked"}'::jsonb WHERE id = 'RQ-0102' AND valid_to IS NULL""")
                }
            }
            assertTrue(e.message!!.contains("TZ-COM-003"), "текст ошибки должен ссылаться на TZ-COM-003: ${e.message}")
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("TZ-MOD-004 / TZ-AI-004: единицы, происхождение, акцепт")
    inner class Group3Provenance {

        private val params = ParamStore(TestDb.conn)

        @BeforeAll
        fun setup() = TestDb.truncateAll()

        @Test
        fun `значение без единицы отклонено`() {
            assertThrows<ModelViolationException> {
                params.putRaw("CM-0001", "mass_no_unit", 50.0, "", prov("""{"source":"manual"}"""))
            }
        }

        @Test
        fun `значение без происхождения отклонено`() {
            assertThrows<ModelViolationException> {
                params.putRaw("CM-0001", "mass_no_prov", 50.0, "kg", prov("{}"))
            }
        }

        @Test
        fun `предложение ИИ без признака акцепта отклонено`() {
            assertThrows<ModelViolationException> {
                params.putRaw("CM-0001", "mass_ai", 50.0, "kg", prov("""{"source":"ai_proposed","ai":{}}"""))
            }
        }

        @Test
        fun `корректный параметр принят`() {
            params.putRaw("CM-0001", "mass", 50.0, "kg", prov("""{"source":"manual"}"""))
            assertEquals(50.0, params.get("CM-0001", "mass")!!.value)
        }

        // STEP-4 §0.2: третий случай NULL-семантики CHECK — предложение ИИ
        // с источником ai_proposed, но БЕЗ блока ai проходило ограничение V001
        // (оператор ? при NULL слева даёт NULL, а FALSE OR NULL считается
        // пройденным) и попадало в расчётные выборки. Исправлено миграцией V006.
        @Test
        fun `предложение ИИ без блока ai отклоняется`() {
            assertThrows<ModelViolationException> {
                params.putRaw("CM-0001", "mass_ai_no_block", 50.0, "kg", prov("""{"source":"ai_proposed"}"""))
            }
        }

        @Test
        fun `неакцептованные предложения выявляются отчётом`() {
            params.putRaw(
                "CM-0001", "power", 12.0, "W",
                prov("""{"source":"ai_proposed","ai":{"accepted":false,"prompt_package_id":"PP-1"}}"""),
            )
            val report = params.unacceptedAiProposals()
            assertEquals(listOf("power" to "PP-1"), report.map { it.name to it.promptPackageId })
            // и такое предложение не участвует в выборке для расчётов (TZ-AI-004)
            assertFalse(params.effectiveParams("CM-0001").any { it.name == "power" })
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("TZ-MOD-005: зависимости, циклы, каскад stale")
    inner class Group4Dependencies {

        private val params = ParamStore(TestDb.conn)
        private val results = ResultStore(TestDb.conn)
        private var resultPk = 0L
        private var staleMarked = 0

        @BeforeAll
        fun setup() {
            TestDb.truncateAll()
            // цепочка: cc → b → a (как в эталоне)
            listOf("a" to null, "b" to "a*2", "cc" to "b+1").forEach { (name, formula) ->
                params.putRaw("CM-0001", name, 1.0, "kg", prov("""{"source":"manual"}"""), formula)
            }
            params.addDependency("CM-0001", "b", "CM-0001", "a")
            params.addDependency("CM-0001", "cc", "CM-0001", "b")
            resultPk = results.insert(
                "SC-0001", "kpi-vector", mapper.createObjectNode(),
                inputVersions = mapOf("CM-0001" to "1"), moduleVersion = "0.1", rngSeed = 42,
            ).pk
            staleMarked = params.markStaleFor("CM-0001", "a")
        }

        @Test
        fun `цикл выявлен - a зависит от cc`() {
            assertTrue(params.wouldCreateCycle("CM-0001", "a", "CM-0001", "cc"))
            assertThrows<CycleException> { params.addDependency("CM-0001", "a", "CM-0001", "cc") }
        }

        @Test
        fun `не цикл - cc зависит от a`() =
            assertFalse(params.wouldCreateCycle("CM-0001", "cc", "CM-0001", "a"))

        @Test
        fun `результат помечен stale при изменении входа`() {
            assertEquals(1, staleMarked)
            assertTrue(results.byPk(resultPk)!!.stale)
        }

        @Test
        fun `зерно ГПСЧ сохранено с результатом`() =
            assertEquals(42L, results.byPk(resultPk)!!.rngSeed)
    }
}
