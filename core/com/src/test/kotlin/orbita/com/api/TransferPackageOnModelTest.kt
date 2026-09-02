// Пакет передачи на заполненной базе (TZ-OUT-006, шаги 11.3–11.4).
//
// Правило полноты закрыто эталоном (spec/presentation_semantics.py). Здесь
// проверяется СБОРКА: одна операция действительно приносит все части из
// хранилища, предупреждения совпадают с состоянием демо-проекта, и отчёт
// зрелости к SRR приложен.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.schema.SchemaRegistry
import orbita.out.FULL_PACKAGE_PARTS
import orbita.out.ModelSnapshot
import orbita.out.SpacecraftConditions
import orbita.out.TransferPackages
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransferPackageOnModelTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)

    @BeforeAll
    fun seed() {
        TestDb.truncateAll()
        DemoProject.seed(boundary)
    }

    private fun assemble(): ObjectNode {
        val options = boundary.results.activeForScenario(DEMO_SCENARIO, "kpi").map { r ->
            (r.payload.deepCopy() as ObjectNode).put("stale", r.stale)
        }
        // ADR-044: аппарат собирается из узла КА дерева состава
        val spacecraft = boundary.carriers.contract(DemoProject.DEMO_SPACECRAFT)
        val model = ModelSnapshot.of(
            boundary.objects, mapper,
            options = options,
            budgets = ModelSnapshot.budgetsOf(
                boundary.spacecraft.build(spacecraft, SpacecraftConditions()),
                mapper,
            ),
            spacecraft = spacecraft,
        )
        return TransferPackages.assemble(
            model = model,
            verificationMatrix = boundary.matrices.verificationMatrix(),
            validationMatrix = boundary.matrices.validationMatrix(),
            maturity = boundary.maturity.build("SRR"),
            mapper = mapper,
        )
    }

    @Test
    @DisplayName("§11.3: одна операция приносит все части, пакет полон")
    fun `пакет собирается одной операцией и полон`() {
        val pkg = assemble()
        FULL_PACKAGE_PARTS.forEach { part ->
            assertTrue(pkg.has(part)) { "части $part нет в пакете" }
        }
        assertTrue(pkg.path("transfer").path("complete").asBoolean())
        assertEquals(0, pkg.path("transfer").path("missing").size())
    }

    @Test
    @DisplayName("§11.3: небазированное — предупреждение, не отказ")
    fun `небазированное перечислено предупреждением`() {
        val pkg = assemble()
        // все девять требований демо-проекта в Draft — и пакет всё равно собран
        assertEquals(9, pkg.path("transfer").path("warnings").size())
        assertTrue(pkg.path("transfer").path("complete").asBoolean())
    }

    @Test
    @DisplayName("§11.3: архитектура несёт распределение, а не только дерево")
    fun `архитектура с распределением`() {
        val architecture = assemble().path("architecture")
        // 10 узлов демо-состава + 8 узлов поддерева КА (ADR-044)
        assertEquals(18, architecture.path("components").size())
        assertTrue(architecture.path("allocations").size() > 0)
        assertTrue(
            architecture.path("allocations")
                .any { it.path("requirement").asText() == "RQ-0100" && it.path("component").asText() == "CM-0010" },
        )
    }

    @Test
    @DisplayName("§11.3: параметры несут запас и требуемый запас")
    fun `параметры с резервами`() {
        val parameters = assemble().path("parameters")
        assertTrue(parameters.size() >= 4) { "строк TPM: ${parameters.size()}" }
        parameters.forEach { p ->
            assertTrue(p.has("margin_pct") && p.has("required_margin_pct")) { p.toString() }
        }
    }

    @Test
    @DisplayName("§11.3: обе матрицы и реестр рисков в пакете")
    fun `матрицы и риски в пакете`() {
        val pkg = assemble()
        assertEquals(9, pkg.path("verification_matrix").size())
        assertEquals(2, pkg.path("validation_matrix").size())
        assertEquals(5, pkg.path("risk_register").size())
        assertEquals(3, pkg.path("modeling_reports").size())
    }

    /** §11.4: отчёт зрелости к SRR — что базировано, что нет, какие разрывы. */
    @Test
    @DisplayName("§11.4: отчёт зрелости к SRR приложен и несёт разрывы")
    fun `отчёт зрелости приложен`() {
        val maturity = assemble().path("maturity_report")
        assertEquals("SRR", maturity.path("gate").asText())
        // демо-проект намеренно неидеален: отчёт обязан это показывать
        assertFalse(maturity.path("gapsByType").isEmpty && maturity.path("unverified").isEmpty)
        assertTrue(maturity.path("unverified").size() > 0)
    }

    /** Часть, для которой нет материала, отсутствует — а не лежит пустым списком. */
    @Test
    @DisplayName("§11.3: пустая модель даёт пустой пакет с названными пропусками")
    fun `на пустой модели пропуски названы`() {
        val empty = mapper.createObjectNode()
        listOf("requirements", "components", "budgets", "options", "risks")
            .forEach { empty.putArray(it) }
        val pkg = TransferPackages.assemble(
            model = empty,
            verificationMatrix = emptyList(),
            validationMatrix = emptyList(),
            maturity = boundary.maturity.build("SRR"),
            mapper = mapper,
        )
        assertFalse(pkg.path("transfer").path("complete").asBoolean())
        val missing = pkg.path("transfer").path("missing").map { it.asText() }
        assertTrue("requirements" in missing && "parameters" in missing) { missing.toString() }
    }
}
