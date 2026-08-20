// Контур целиком: три функции шага (генерация из сервисов, массовое улучшение
// формулировок, декомпозиция), отчёт по отбраковке и прямой API-канал.
// Эталон эти свойства не покрывает — они проверяются здесь.
package orbita.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.schema.SchemaRegistry
import orbita.req.ProductNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AiLoopTest {

    private val mapper = ObjectMapper()
    private val registry = SchemaRegistry(RepoPaths.schemasDir())
    private val builder = PromptPackageBuilder(registry = registry, mapper = mapper)
    private val screening = ProposalScreening()

    private fun json(s: String): JsonNode = mapper.readTree(s)

    // ---------- TZ-AI-001: схема ответа — из реестра, не второй копией ----------

    @Test
    @DisplayName("TZ-AI-001: схема ответа берётся из нормативной схемы целевого объекта")
    fun `схема ответа соответствует объекту модели`() {
        val pkg = builder.build("services_to_requirements", json("""{"service":"SV-0001"}"""), "задание")
        assertEquals(registry.raw("core/requirement"), pkg.responseSchema)
        assertEquals(emptyList<String>(), packageIssues(pkg))
    }

    @Test
    @DisplayName("TZ-AI-001: целевой объект может быть частью схемы")
    fun `событие верификации берётся подсхемой требования`() {
        val pkg = builder.build("verification_approach", json("""{"requirement":"RQ-0001"}"""), "задание")
        val expected = registry.raw("core/requirement").at("/properties/verification_events/items")
        assertEquals(expected, pkg.responseSchema)
        assertTrue(pkg.responseSchema!!.path("required").map { it.asText() }.contains("method"))
    }

    @Test
    @DisplayName("TZ-AI-001: вид без описанного объекта модели пакет не собирает")
    fun `реестр рисков не собирается пока нет схемы`() {
        // Реестр рисков назван в задании как вид первой очереди, но объект «риск»
        // в модели не описан. Схема ответа обязана соответствовать объекту модели,
        // поэтому пакет не собирается — вместо выдуманной схемы явный отказ.
        val e = assertThrows<UnmodelledTargetException> {
            builder.build("risk_register", json("""{"scenario":"SC-0001"}"""), "задание")
        }
        assertTrue("risk_register" in e.message!!, e.message!!)
    }

    @Test
    @DisplayName("TZ-AI-001: перечень видов расширяется без изменения кода")
    fun `перечень видов конфигурируем`() {
        val custom = PackageKinds.fromJson(
            """{"kinds":[{"id":"interface_control","input":"стык","output":"требования к стыку",
               "target_schema":"core/requirement"}]}""",
        )
        val b = PromptPackageBuilder(kinds = custom, registry = registry, mapper = mapper)
        val pkg = b.build("interface_control", json("""{"interface":"IF-0001"}"""), "задание")
        assertEquals("interface_control", pkg.kind)
        // а вид из набора по умолчанию в этом наборе неизвестен
        assertThrows<UnknownPackageKindException> {
            b.build("services_to_requirements", json("""{"a":1}"""), "задание")
        }
    }

    // ---------- §1.3: отчёт по отбраковке ----------

    @Test
    @DisplayName("STEP-5 §1.3: отчёт показывает предложенное, отброшенное и правила")
    fun `отчёт по пакету доступен`() {
        val report = screening.screen(listOf(GOOD, json(NO_MODAL), json(VAGUE)))
        assertEquals(3, report.proposed)
        assertEquals(1, report.shown.size)
        assertEquals(2, report.rework.size)
        assertTrue(report.byRule.getValue("качество") >= 2, report.byRule.toString())

        // контекст переделки пригоден для повторного пакета: он и есть контекст
        val context = report.reworkContext(mapper)
        val repeat = builder.build("requirement_quality", context, "Исправь перечисленные замечания.")
        assertEquals(emptyList<String>(), packageIssues(repeat))
        assertEquals(2, context.path("rework").size())
        assertTrue(context.path("rework")[0].path("issues").size() > 0)
    }

    @Test
    @DisplayName("STEP-5 §1.3: отбракованное инженеру не показывается")
    fun `отбракованное не попадает в показанное`() {
        val report = screening.screen(listOf(GOOD, json(NO_MODAL), json(VAGUE)))
        val shownIds = report.shown.map { it.path("id").asText() }
        report.rework.forEach { assertFalse(it.item.path("id").asText() in shownIds) }
    }

    // ---------- §2.2: правила дешевле обращения к модели ----------

    @Test
    @DisplayName("STEP-5 §2.2: детерминированные замечания находятся без обращения к модели")
    fun `к модели уходит только то, что требует переформулирования`() {
        val batch = listOf(GOOD, json(NO_MODAL), json(VAGUE))
        val toModel = screening.needsModel(batch)
        // состоятельное требование в модель не отправляется — платить не за что
        assertFalse(toModel.any { it.path("id").asText() == GOOD.path("id").asText() })
        assertEquals(2, toModel.size)
        // и замечание названо правилом, а не моделью
        assertTrue(
            screening.deterministicIssues(json(VAGUE)).any { "неизмеримое определение" in it },
            screening.deterministicIssues(json(VAGUE)).toString(),
        )
    }

    // ---------- §2.3: декомпозиция ----------

    private val tree = mapOf(
        "CM-0001" to ProductNode("CM-0001", "element"),
        "CM-0002" to ProductNode("CM-0002", "element", parent = "CM-0001"),
        "CM-0009" to ProductNode("CM-0009", "element"),
        "IF-0001" to ProductNode("IF-0001", "interface", owners = listOf("CM-0002", "CM-0009")),
    )

    private val parent = json(
        """{"id":"RQ-0001","statement":"Сухая масса аппарата должна быть не более 60 кг.",
           "allocated_to":[{"component":"CM-0001"}],
           "mop":{"name":"Сухая масса","operator":"le","rollup":"sum","value":{"value":60,"unit":"kg"}}}""",
    )

    private fun child(id: String, component: String, mass: Double) = json(
        """{"id":"$id","category":"performance",
           "statement":"Масса подсистемы должна быть не более $mass кг.",
           "allocated_to":[{"component":"$component"}],
           "mop":{"name":"Масса","operator":"le","value":{"value":$mass,"unit":"kg"}},
           "verification":{"method":"test","means":"весы поверенные",
             "approach":"Взвешивание собранной подсистемы на поверенных весах до установки на аппарат."}}""",
    )

    @Test
    @DisplayName("STEP-5 §2.3: потомок вне области родителя отбраковывается")
    fun `декомпозиция за пределы области родителя отбраковывается`() {
        val ctx = ScreeningContext(productTree = tree, parentRequirement = parent)
        val inside = screening.issues(child("RQ-0002", "CM-0002", 30.0), ctx)
        val outside = screening.issues(child("RQ-0003", "CM-0009", 30.0), ctx)
        assertEquals(emptyList<String>(), inside)
        assertTrue(outside.any { it.startsWith("трассировка:") && "вне области родителя" in it }, outside.toString())
    }

    @Test
    @DisplayName("STEP-5 §2.3: превышение бюджета в свёртке отбраковывается")
    fun `свёртка бюджета проверяется по всей группе потомков`() {
        val ctx = ScreeningContext(
            productTree = tree, parentRequirement = parent,
            siblings = listOf(child("RQ-0002", "CM-0002", 40.0)),
        )
        val fits = screening.issues(child("RQ-0004", "CM-0002", 15.0), ctx)
        val busts = screening.issues(child("RQ-0005", "CM-0002", 30.0), ctx)
        assertEquals(emptyList<String>(), fits)
        assertTrue(busts.any { it.startsWith("свёртка:") }, busts.toString())
    }

    @Test
    @DisplayName("STEP-5 §2.3: интерфейсное требование на элемент отбраковывается")
    fun `интерфейсное требование обязано указывать интерфейс`() {
        val onComponent = json(
            """{"id":"RQ-0006","category":"interface",
               "statement":"Стык должен обеспечивать передачу не более 100 кбит/с.",
               "allocated_to":[{"component":"CM-0002"}],
               "mop":{"name":"Скорость","operator":"le","value":{"value":100,"unit":"kbit/s"}},
               "verification":{"method":"test","means":"стенд стыковки",
                 "approach":"Прогон обмена на стенде стыковки с записью фактической скорости."}}""",
        )
        val issues = screening.issues(onComponent, ScreeningContext(productTree = tree))
        assertTrue(issues.any { "распределено на элемент, а не на интерфейс" in it }, issues.toString())
    }

    // ---------- TZ-AI-004: отчёт по неакцептованным ----------

    @Test
    @DisplayName("TZ-AI-004: отчёт «объекты с неакцептованными предложениями»")
    fun `неакцептованные предложения перечисляются отчётом`() {
        val a = asProposal(json("""{"id":"RQ-0007"}"""), "PP-0001", "llm-a", mapper)
        val b = accept(asProposal(json("""{"id":"RQ-0008"}"""), "PP-0001", "llm-a", mapper), by = "инженер")
        val manual = json("""{"id":"RQ-0009","provenance":{"source":"manual"}}""")
        assertEquals(listOf("RQ-0007"), pendingProposals(listOf(a, b, manual)))
    }

    @Test
    @DisplayName("TZ-AI-004: акцепт без автора не принимается")
    fun `акцепт без автора отклоняется`() {
        val p = asProposal(json("""{"id":"RQ-0007"}"""), "PP-0001", "llm-a", mapper)
        assertThrows<IllegalArgumentException> { accept(p, by = "  ") }
    }

    // ---------- TZ-AI-005: прямой канал ----------

    private val validRequirement = """{"id":"RQ-0100","level":"system","category":"performance",
        "statement":"Система должна обеспечивать доставку не менее 0,9.",
        "traces_up":[{"ref":"SV-0001"}],"verification_events":[],
        "lifecycle":{"status":"Draft","version":"0.1"},"owner":"вед. системный инженер"}"""

    @Test
    @DisplayName("TZ-AI-005: результат валидируется по схеме целевого объекта")
    fun `результат прямого канала валидируется схемой`() {
        val ok = DirectChannel({ _, _ -> json(validRequirement) }, registry)
            .invoke("decompose_to_components", json("""{"requirement":"RQ-0001"}"""))
        assertTrue(ok is ChannelResult.Success, ok.toString())

        val bad = DirectChannel({ _, _ -> json("""{"id":"не по шаблону"}""") }, registry)
            .invoke("decompose_to_components", json("""{"requirement":"RQ-0001"}"""))
        assertTrue(bad is ChannelResult.Rejected)
        assertTrue((bad as ChannelResult.Rejected).errors.isNotEmpty())
    }

    @Test
    @DisplayName("TZ-AI-005: отказ API не нарушает состояние модели")
    fun `отказ API не меняет модель`() {
        val model = mutableListOf(json(validRequirement))
        val before = model.toList()
        val channel = DirectChannel({ _, _ -> throw java.io.IOException("соединение разорвано") }, registry)
        val result = channel.invoke("decompose_to_components", json("""{"requirement":"RQ-0001"}"""))
        assertTrue(result is ChannelResult.Failed)
        assertTrue("отказ API" in (result as ChannelResult.Failed).reason)
        assertEquals(before, model, "состояние модели изменилось при отказе API")
    }

    @Test
    @DisplayName("TZ-AI-005: перечень операций канала конфигурируем")
    fun `операция вне перечня не выполняется`() {
        val channel = DirectChannel(
            { _, _ -> json(validRequirement) }, registry,
            operations = mapOf("only_this" to StructuralOperation("only_this", "core/requirement")),
        )
        assertEquals(setOf("only_this"), channel.operationIds)
        val result = channel.invoke("decompose_to_components", json("{}"))
        assertTrue(result is ChannelResult.Failed)
        assertTrue("не входит в перечень" in (result as ChannelResult.Failed).reason)
    }

    // ---------- фикстуры ----------

    private val GOOD = json(
        """{"id":"RQ-9001","category":"performance",
           "statement":"Система должна обеспечивать доставку не менее 0,9.",
           "mop":{"name":"доставка","operator":"ge","value":{"value":0.9,"unit":"1"}},
           "verification":{"method":"analysis","means":"модель потоков",
             "approach":"Прогон Монте-Карло по предрасчитанному расписанию пролётов, 500 реализаций."}}""",
    )

    private val NO_MODAL = """{"id":"RQ-9002","category":"performance",
        "statement":"Обеспечение доставки не менее 0,9.",
        "mop":{"name":"доставка","operator":"ge","value":{"value":0.9,"unit":"1"}},
        "verification":{"method":"analysis","means":"модель потоков",
          "approach":"Прогон Монте-Карло по предрасчитанному расписанию пролётов, 500 реализаций."}}"""

    private val VAGUE = """{"id":"RQ-9003","category":"performance",
        "statement":"Система должна обеспечивать достаточную доставку данных."}"""
}
