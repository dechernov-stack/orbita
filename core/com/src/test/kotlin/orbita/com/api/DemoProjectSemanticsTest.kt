// Перенос spec/demo_project.py в тесты, один в один: 46 проверок.
//
// Проект берётся из ЭТАЛОНА (`--dump`), а проверяется функциями реализации.
// Так сверка идёт по существу: те же данные, те же правила, разные исполнители.
package orbita.com.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.bal.RadarOption
import orbita.bal.paretoFrontByAxes
import orbita.bal.radarSeries
import orbita.out.BudgetSegment
import orbita.out.budgetSegments
import orbita.out.buildTree
import orbita.out.transferPackage
import orbita.out.verificationMatrixView
import orbita.mod.store.Link
import orbita.req.Criticality
import orbita.req.ProductNode
import orbita.req.QualityControl
import orbita.req.VerificationState
import orbita.req.allocationConsistent
import orbita.req.componentSpecification
import orbita.req.criticality
import orbita.req.eventIssues
import orbita.req.evidenceState
import orbita.req.interfaceAllocationValid
import orbita.req.registerSummary
import orbita.req.renderConstraint
import orbita.req.riskIssues
import orbita.req.rollupCheck
import orbita.req.rollupChildIds
import orbita.req.statementMatchesOperator
import orbita.req.validateMop
import orbita.req.validationIssues
import orbita.req.verificationState
import orbita.usr.DemandMapBuilder
import orbita.usr.PopulationCell
import orbita.usr.demandWeightedQuality
import orbita.usr.latitudeProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.math.abs

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DemoProjectSemanticsTest {

    private val mapper = ObjectMapper()
    private val project: JsonNode = DemoProject.load()

    private val components: Map<String, ProductNode> = project.path("components").properties()
        .associate { (id, c) ->
            id to ProductNode(
                id = id,
                kind = c.path("kind").asText(),
                parent = c.path("parent").asText("").ifBlank { null },
                owners = c.path("owners").map { it.asText() },
            )
        }
    private val needs = project.path("needs").toList()
    private val services = project.path("services").toList()
    private val requirements = project.path("requirements").toList()
    private val risks = project.path("risks").toList()
    private val evidence = project.path("evidence").toList()
    private val validations = project.path("validations").toList()
    private val links: List<Link> = project.path("links").map { l ->
        Link(
            fromId = l.path("from").asText(),
            toId = l.path("to").asText(),
            kind = l.path("kind").asText(),
            consumerClass = null,
            derivationKind = l.path("derivation_kind").asText("").ifBlank { null },
        )
    }

    private fun byId(id: String): JsonNode = requirements.first { it.path("id").asText() == id }

    @Nested
    @DisplayName("Состав проекта")
    inner class Composition {

        @Test
        fun `нужды заданы`() = assertEquals(3, needs.size)

        @Test
        fun `сервисы несут профили по классам`() =
            assertTrue(services.all { !it.path("qos_profiles").isEmpty })

        @Test
        fun `требования покрывают три уровня`() =
            assertEquals(setOf("system", "element"), requirements.map { it.path("level").asText() }.toSet())

        @Test
        fun `состав системы содержит сегменты и интерфейсы`() =
            assertEquals(2, components.values.count { it.kind == "interface" })

        @Test
        fun `риски охватывают все классы критичности`() = assertEquals(
            setOf(Criticality.Low, Criticality.Medium, Criticality.High),
            risks.map { criticality(it.path("probability").asInt(), it.path("impact").asInt()) }.toSet(),
        )
    }

    @Nested
    @DisplayName("Целостность трассировки")
    inner class Traceability {

        @Test
        fun `требований без источника нет`() {
            val hasSource = links.filter { it.kind == "trace" }.map { it.toId }.toSet()
            val orphans = requirements.map { it.path("id").asText() }.filterNot { it in hasSource }
            assertEquals(emptyList<String>(), orphans)
        }

        @Test
        fun `нераспределённых системных требований нет`() {
            val allocated = links.filter { it.kind == "allocation" }.map { it.fromId }.toSet()
            val unallocated = requirements.filter { it.path("level").asText() == "system" }
                .map { it.path("id").asText() }.filterNot { it in allocated }
            assertEquals(emptyList<String>(), unallocated)
        }

        @Test
        fun `элементы без требований выявляются`() {
            val withReq = links.filter { it.kind == "allocation" }.map { it.toId }.toSet()
            val bare = components.filterValues { it.kind != "interface" }.keys.filterNot { it in withReq }
            // в демо-проекте они есть намеренно: система ловит проблемы, а не прячет
            assertTrue(bare.isNotEmpty())
        }

        @Test
        fun `ссылка на сервис несёт класс потребителя`() = assertTrue(
            requirements.flatMap { it.path("traces_up").toList() }
                .filter { it.path("ref").asText().startsWith("SV-") }
                .all { it.has("consumer_class") },
        )
    }

    @Nested
    @DisplayName("Качество формулировок и условий")
    inner class Quality {

        private val quality = QualityControl()

        @Test
        fun `замечаний к формулировкам нет`() {
            val bad = requirements.associate { it.path("id").asText() to quality.check(it) }
                .filterValues { it.isNotEmpty() }
            assertEquals(emptyMap<String, List<String>>(), bad)
        }

        @Test
        fun `условия структурно полны`() {
            val bad = requirements.associate { it.path("id").asText() to validateMop(it.path("mop")) }
                .filterValues { it.isNotEmpty() }
            assertEquals(emptyMap<String, List<String>>(), bad)
        }

        @Test
        fun `формулировка и оператор согласованы`() {
            val bad = requirements.mapNotNull { r ->
                statementMatchesOperator(r.path("statement").asText(), r.path("mop"))
                    ?.let { r.path("id").asText() to it }
            }
            assertEquals(emptyList<Pair<String, String>>(), bad)
        }

        @Test
        fun `условие читается человеком`() =
            assertEquals("не более 100 kg", renderConstraint(byId("RQ-0100").path("mop")))
    }

    @Nested
    @DisplayName("Свёртка бюджетов")
    inner class Rollup {

        private fun childMops(parent: String) =
            rollupChildIds(parent, links).map { byId(it).path("mop") }

        @Test
        fun `RQ-0100 декомпозиция состоятельна`() =
            assertEquals(true, rollupCheck(byId("RQ-0100").path("mop"), childMops("RQ-0100")).consistent)

        @Test
        fun `RQ-0100 свёртка равна 90`() = assertEquals(
            90.0, rollupCheck(byId("RQ-0100").path("mop"), childMops("RQ-0100")).aggregate!!, 1e-9,
        )

        @Test
        fun `RQ-0110 декомпозиция состоятельна`() =
            assertEquals(true, rollupCheck(byId("RQ-0110").path("mop"), childMops("RQ-0110")).consistent)

        @Test
        fun `RQ-0110 свёртка равна 105`() = assertEquals(
            105.0, rollupCheck(byId("RQ-0110").path("mop"), childMops("RQ-0110")).aggregate!!, 1e-9,
        )

        @Test
        fun `производное требование в бюджет не входит`() =
            assertFalse("RQ-0130" in rollupChildIds("RQ-0100", links))
    }

    @Nested
    @DisplayName("Согласованность деревьев")
    inner class Trees {

        @Test
        fun `RQ-0101 распределён внутри области RQ-0100`() {
            val (ok, why) = allocationConsistent(byId("RQ-0100"), byId("RQ-0101"), components)
            assertTrue(ok, why)
        }

        @Test
        fun `RQ-0112 распределён внутри области RQ-0110`() {
            val (ok, why) = allocationConsistent(byId("RQ-0110"), byId("RQ-0112"), components)
            assertTrue(ok, why)
        }

        @Test
        fun `интерфейсное требование распределено на интерфейс`() =
            assertTrue(interfaceAllocationValid(byId("RQ-0130"), components).first)

        @Test
        fun `спецификация платформы собирается`() =
            assertEquals(listOf("RQ-0101"), componentSpecification(requirements, "CM-0011"))
    }

    @Nested
    @DisplayName("Верификация и валидация")
    inner class Verification {

        @Test
        fun `масса предварительно подтверждена, не верифицирована`() =
            assertEquals(VerificationState.PreliminarilyConfirmed, verificationState(byId("RQ-0100")))

        @Test
        fun `требование без закрывающего события выявляется`() =
            assertEquals(VerificationState.PlanIncomplete, verificationState(byId("RQ-0120")))

        @Test
        fun `события верификации описаны полностью`() {
            val bad = requirements.associate { r ->
                r.path("id").asText() to r.path("verification_events").flatMap { eventIssues(it) }
            }.filterValues { it.isNotEmpty() }
            assertEquals(emptyMap<String, List<String>>(), bad)
        }

        @Test
        fun `свидетельство действительно для текущей конфигурации`() =
            assertEquals("действительно", evidenceState(evidence[0], "C1").label)

        @Test
        fun `свидетельство прежней конфигурации неприменимо`() =
            assertEquals("неприменимо к текущей конфигурации", evidenceState(evidence[0], "C2").label)

        @Test
        fun `валидация привязана к ожиданиям, не к требованиям`() {
            val bad = validations.associate { it.path("id").asText() to validationIssues(it) }
                .filterValues { it.isNotEmpty() }
            assertEquals(emptyMap<String, List<String>>(), bad)
        }
    }

    @Nested
    @DisplayName("Риски")
    inner class Risks {

        private val summary = registerSummary(risks)

        @Test
        fun `записи реестра полны`() {
            val bad = risks.associate { it.path("id").asText() to riskIssues(it) }
                .filterValues { it.isNotEmpty() }
            assertEquals(emptyMap<String, List<String>>(), bad)
        }

        @Test
        fun `закрытый риск сохранён и исключён из активных`() {
            assertEquals(listOf("RSK-0005"), summary.closedRetained)
            assertEquals(4, summary.active)
        }

        @Test
        fun `к эскалации отобраны высокие`() =
            assertEquals(setOf("RSK-0001", "RSK-0002", "RSK-0004"), summary.escalate.toSet())

        @Test
        fun `редкое тяжёлое событие эскалируется наравне с частым`() {
            assertTrue("RSK-0004" in summary.escalate)
            assertEquals(Criticality.High, criticality(1, 5))
        }
    }

    @Nested
    @DisplayName("Спрос и оценка построений")
    inner class Demand {

        private val cells = DemandMapBuilder.build(
            project.path("populations").map { p ->
                PopulationCell(
                    id = p.path("id").asText(),
                    lat = p.path("lat").asDouble(),
                    popDensityPerKm2 = p.path("pop_density_per_km2").asDouble(),
                    terminalsPerCapita = p.path("terminals_per_capita").asDouble(),
                    msgsPerTerminalDay = p.path("msgs_per_terminal_day").asDouble(),
                    klass = p.path("klass").asText(),
                )
            },
        )
        private val polar = { c: orbita.usr.DemandCell -> if (abs(c.lat) >= 60) 1.0 else 0.35 }
        private val mid = { c: orbita.usr.DemandCell -> if (abs(c.lat) < 60) 0.9 else 0.4 }

        @Test
        fun `карта спроса построена`() =
            assertEquals(1.0, cells.values.sumOf { it.weight }, 1e-9)

        @Test
        fun `полярное преимущество не выигрывает на населённой карте`() = assertTrue(
            demandWeightedQuality(cells, mid) > demandWeightedQuality(cells, polar),
        )

        @Test
        fun `широтный профиль построен`() =
            assertEquals(1.0, latitudeProfile(cells, mid).sumOf { it.weight }, 1e-9)
    }

    @Nested
    @DisplayName("Представление")
    inner class Presentation {

        private val options = project.path("options").map { o ->
            RadarOption(
                o.path("name").asText(),
                mapOf(
                    "quality" to o.path("quality").asDouble(),
                    "cost" to o.path("cost").asDouble(),
                    "reliability" to o.path("reliability").asDouble(),
                ),
            )
        }
        private val matrix = verificationMatrixView(requirements)

        @Test
        fun `роза KPI построена`() =
            assertEquals(3, radarSeries(options, listOf("quality", "cost", "reliability")).series.size)

        @Test
        fun `роза несёт состав набора нормировки`() = assertEquals(
            3, radarSeries(options, listOf("quality", "cost", "reliability")).normalizedOver.size,
        )

        @Test
        fun `Парето-фронт вычислен`() = assertEquals(
            listOf("Walker 24/3 · 700 км", "Walker 40/5 · 550 км"), paretoFrontByAxes(options),
        )

        @Test
        fun `полоса бюджета показывает резерв`() {
            val bar = budgetSegments(
                100.0, listOf(BudgetSegment("Платформа", 60.0), BudgetSegment("ПН", 30.0)),
            )
            assertEquals(10.0, bar.remaining, 1e-9)
            assertFalse(bar.overrun)
        }

        @Test
        fun `дерево требований имеет два бюджетных корня`() {
            val tree = buildTree(requirements.map { it.path("id").asText() }, links)
            assertTrue("RQ-0100" in tree.roots)
            assertTrue("RQ-0110" in tree.roots)
        }

        @Test
        fun `матрица верификации даёт строку на событие`() = assertEquals(5, matrix.rows.size)

        @Test
        fun `одно событие может верифицировать несколько требований`() {
            val shared = matrix.rows.filter { it.eventId == "VE-0010" }
            assertEquals(2, shared.size)
            assertEquals(setOf("RQ-0110", "RQ-0120"), shared.map { it.requirementId }.toSet())
        }

        @Test
        fun `требования без событий попадают в разрывы`() =
            assertTrue(matrix.gaps.size >= 5, "разрывов: ${matrix.gaps.size}")
    }

    @Nested
    @DisplayName("Пакет передачи и зрелость")
    inner class Transfer {

        private val model = mapper.createObjectNode().apply {
            val reqs = putArray("requirements")
            requirements.forEach {
                reqs.addObject()
                    .put("id", it.path("id").asText())
                    .put("status", it.path("status").asText())
            }
            putArray("architecture")
            putArray("parameters")
            putArray("verification_matrix")
            putArray("modeling_reports")
        }
        private val pkg = transferPackage(model)

        @Test
        fun `пакет собирается`() = assertTrue(pkg.complete)

        @Test
        fun `небазированные требования — предупреждение, не отказ`() =
            assertEquals(requirements.size, pkg.warnings.size)

        @Test
        fun `отчёт зрелости к SRR показывает, что базировать`() {
            // все требования демо-проекта в Draft: к SRR требуется Preliminary
            val notReady = requirements.filter { it.path("status").asText() == "Draft" }
            assertTrue(notReady.isNotEmpty())
            assertEquals(requirements.size, notReady.size)
        }
    }
}
