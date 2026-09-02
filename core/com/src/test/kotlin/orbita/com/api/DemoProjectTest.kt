// Демо-проект на РЕАЛЬНОЙ базе (STEP-7-9 §7.2, готовность шага 7).
//
// Проверяется не форма данных, а совпадение: отчёты целостности на заполненной
// базе дают те же результаты, что и эталон spec/demo_project.py. Расхождение
// означало бы, что реализация и эталон разошлись — а именно это заполнение
// демо-проекта и призвано ловить.
package orbita.com.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.schema.SchemaRegistry
import orbita.out.buildTree
import orbita.out.transferPackage
import orbita.out.verificationMatrixView
import orbita.req.Criticality
import orbita.req.VerificationState
import orbita.req.criticality
import orbita.req.registerSummary
import orbita.req.riskIssues
import orbita.req.verificationState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DemoProjectTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private lateinit var project: JsonNode

    @BeforeAll
    fun seed() {
        TestDb.truncateAll()
        project = DemoProject.load()
        DemoProject.seed(boundary, project)
    }

    private fun requirements(): List<JsonNode> = boundary.objects.listCurrent()
        .filter { it.type == "requirement" }.map { it.doc }.sortedBy { it.path("id").asText() }

    @Test
    @DisplayName("§7.2: заполнение выполняется одной операцией и попадает в базу")
    fun `демо-проект заполняется одной операцией`() {
        val byType = boundary.objects.listCurrent().groupingBy { it.type }.eachCount()
        assertEquals(3, byType["need"])
        assertEquals(2, byType["service"])
        assertEquals(9, byType["requirement"])
        // 8 узлов эталона + 8 узлов поддерева КА из модели аппарата (ADR-044)
        assertEquals(16, byType["component"])
        assertEquals(2, byType["interface"])
        assertEquals(2, byType["evidence"])
        assertEquals(2, byType["validation"])
        assertEquals(5, byType["risk"])
    }

    @Test
    @DisplayName("§7.2: демо-объекты помечены и отличимы от рабочих")
    fun `демо-объекты помечены`() {
        assertTrue(boundary.objects.listCurrent().all { it.createdBy == DEMO_AUTHOR })
        assertFalse(DemoProject.hasNonDemoObjects(boundary))
    }

    @Test
    @DisplayName("§7.2: свёртка бюджетов на базе совпадает с эталоном")
    fun `свёртка бюджетов совпадает с эталоном`() {
        val mass = boundary.req.rollupFor("RQ-0100")
        assertTrue(mass.applicable)
        assertEquals(90.0, mass.aggregate!!, 1e-9)
        assertEquals(true, mass.consistent)

        val reaction = boundary.req.rollupFor("RQ-0110")
        assertEquals(105.0, reaction.aggregate!!, 1e-9)
        assertEquals(true, reaction.consistent)

        // производное требование в бюджет не входит
        val children = orbita.req.rollupChildIds("RQ-0100", boundary.links.linksFrom("RQ-0100", "derive"))
        assertFalse("RQ-0130" in children)
    }

    @Test
    @DisplayName("§7.2: состояние верификации на базе совпадает с эталоном")
    fun `состояние верификации совпадает с эталоном`() {
        val docs = requirements().associateBy { it.path("id").asText() }
        assertEquals(VerificationState.PreliminarilyConfirmed, verificationState(docs.getValue("RQ-0100")))
        assertEquals(VerificationState.PlanIncomplete, verificationState(docs.getValue("RQ-0120")))
    }

    @Test
    @DisplayName("§7.2: матрица верификации на базе совпадает с эталоном")
    fun `матрица верификации совпадает с эталоном`() {
        val view = verificationMatrixView(requirements())
        assertEquals(5, view.rows.size)
        val shared = view.rows.filter { it.eventId == "VE-0010" }
        assertEquals(2, shared.size)
        assertEquals(setOf("RQ-0110", "RQ-0120"), shared.map { it.requirementId }.toSet())
        assertTrue(view.gaps.size >= 5, "разрывов: ${view.gaps.size}")
    }

    @Test
    @DisplayName("§7.2: дерево требований имеет два бюджетных корня")
    fun `дерево требований совпадает с эталоном`() {
        val ids = requirements().map { it.path("id").asText() }
        val links = ids.flatMap { boundary.links.linksFrom(it, "derive") }
        val tree = buildTree(ids, links)
        assertTrue("RQ-0100" in tree.roots)
        assertTrue("RQ-0110" in tree.roots)
    }

    @Test
    @DisplayName("§7.2: реестр рисков на базе совпадает с эталоном")
    fun `реестр рисков совпадает с эталоном`() {
        val risks = boundary.req.risks()
        assertEquals(5, risks.size)
        assertTrue(risks.all { riskIssues(it).isEmpty() })

        val summary = registerSummary(risks)
        assertEquals(listOf("RSK-0005"), summary.closedRetained)
        assertEquals(4, summary.active)
        assertEquals(setOf("RSK-0001", "RSK-0002", "RSK-0004"), summary.escalate.toSet())
        // редкое тяжёлое событие эскалируется наравне с частым
        assertTrue("RSK-0004" in summary.escalate)
        assertEquals(Criticality.High, criticality(1, 5))
        // риски охватывают все классы критичности
        assertEquals(
            setOf(Criticality.Low, Criticality.Medium, Criticality.High),
            project.path("risks").map { criticality(it.path("probability").asInt(), it.path("impact").asInt()) }
                .toSet(),
        )
    }

    @Test
    @DisplayName("§7.2: пакет передачи собирается, небазированное — предупреждение")
    fun `пакет передачи совпадает с эталоном`() {
        val model = mapper.createObjectNode()
        val reqs = model.putArray("requirements")
        requirements().forEach {
            reqs.addObject()
                .put("id", it.path("id").asText())
                .put("status", it.path("lifecycle").path("status").asText())
        }
        model.putArray("architecture")
        model.putArray("parameters")
        model.putArray("verification_matrix")
        model.putArray("modeling_reports")
        // Реестр рисков входит в состав пакета передачи (STEP-7-9 §7.1)
        model.putArray("risk_register")

        val pkg = transferPackage(model)
        assertTrue(pkg.complete)
        assertEquals(9, pkg.warnings.size, "все требования демо-проекта в Draft")
    }

    @Test
    @DisplayName("§7.2: отчёты целостности на базе совпадают с эталоном")
    fun `отчёты целостности совпадают с эталоном`() {
        // требований без источника нет
        assertEquals(emptyList<String>(), boundary.links.traceBreaks())
        // элементы без требований выявляются — в демо-проекте они есть намеренно
        assertTrue(boundary.req.elementsWithoutRequirements().isNotEmpty())
        // распределения согласованы с деревом изделия
        assertEquals(emptyList<Triple<String, String, String>>(), boundary.req.inconsistentAllocations())
        // спецификация платформы собирается
        assertEquals(listOf("RQ-0101"), boundary.req.specificationOf("CM-0011"))
    }
}
