// Риски из разрывов фазы (§2.5 шипа 6) — помощник Phase A: машина находит
// разрывы сама (TRL ниже порога точки · пустые грани лестницы · запас бюджета
// ниже политики · цели без требований), модель лишь переформулирует готовое
// условие · событие · последствие.
//
// Проверяются машинные ворота: у каждого риска основание-разрыв со всеми
// четырьмя частями (без разрыва риска нет); разрыв модели вне среза — отказ
// поимённо; пустая формулировка от модели — отказ; модель недоступна — НЕ
// отказ, шаблонная формулировка (ИИ не на критическом пути); разрывов нет —
// пустой прогон, модель не зовём. Канал к модели подменён.
package orbita.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.ai.api.AiFactory
import orbita.ai.api.Answer
import orbita.ai.api.Basis
import orbita.ai.api.ProviderUnavailable
import orbita.ai.api.Transport
import orbita.ai.api.Verdict
import orbita.ai.internal.RiskDeriver
import orbita.ai.internal.Synthesizer
import orbita.architecture.api.ArchitectureFactory
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Link
import orbita.kernel.api.LinkRegistry
import orbita.kernel.api.Provenance
import orbita.knowledge.api.KnowledgeFactory
import orbita.programmatics.api.ProgrammaticsFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RiskDeriveTest {

    private val mapper = ObjectMapper()
    private lateinit var store: ПамятьПоля
    private lateinit var links: СвязиПоля
    private lateinit var знания: orbita.knowledge.api.Intake
    private lateinit var канал: КаналРазрывов
    private lateinit var служба: orbita.ai.api.AiService
    private lateinit var синтез: Synthesizer

    @BeforeTest
    fun создать() {
        store = ПамятьПоля()
        links = СвязиПоля()
        знания = KnowledgeFactory.intake(store, links, mapper)
        канал = КаналРазрывов()
        служба = AiFactory.service(store, канал, mapper)
        синтез = Synthesizer(store, знания, служба, mapper)
    }

    // Деривер собирается на прогон, как в фабрике (AiFactory.deriveRisks):
    // снимок справочника единиц в бюджетах должен быть свежим к данным стенда.
    private fun деривер() = RiskDeriver(
        store, links,
        ArchitectureFactory.architecture(store, links, mapper),
        ProgrammaticsFactory.programmatics(store, mapper),
        KnowledgeFactory.budgets(store, links, mapper),
        служба, mapper,
    )

    @Test
    fun `разрывы четырёх источников — риски с основанием-разрывом в ручной прогон`() {
        стенд()
        канал.ответ = """{"risks":[]}"""

        val прогон = деривер().deriveInto(ПРОЕКТ, АВТОР, синтез)

        assertEquals("manual", прогон.trigger, "прогон помощника — ручной")
        assertTrue(прогон.diff.new.isNotEmpty(), "предложения есть: ${прогон.note}")

        // TRL: основание-разрыв с четырьмя частями, категория technical,
        // срок-точка — id вехи, ссылки — узел.
        val trl = прогон.diff.new.single { "TRL" in (it.payload["statement"] ?: "") }
        assertEquals("risk", trl.concept)
        assertEquals(Verdict.NEW, trl.verdict, "годное здесь «новое»: вердикт выносит сверка при приёме")
        assertEquals("technical", trl.payload["category"])
        val trlОснование = trl.basis.single()
        assertTrue(trlОснование is Basis.Gap, "основание риска — разрыв: $trlОснование")
        assertEquals("trl", trlОснование.kind)
        assertEquals("C-0007", trlОснование.node)
        assertEquals("TRL 4→6", trlОснование.facet)
        assertEquals("GT-SDR", trlОснование.point)
        assertEquals(точкаId, trl.payload["due_point"], "срок-точка — id вехи, как у ручной формы")
        // Ссылки на узел: прогон пишет ссылки словами («C-0007»), а приём
        // заворачивает одиночную ссылку в массив (Reconciler, §2.5).
        assertEquals("C-0007", trl.payload["refs"], "ссылка риска на узел разрыва")
        val cec = mapper.readTree(trl.payload["cec"]!!)
        assertTrue(cec.path("condition").asText("").isNotBlank(), "условие есть")
        assertTrue(cec.path("event").asText("").isNotBlank(), "событие есть")
        assertTrue(cec.path("consequence").asText("").isNotBlank(), "последствие есть")
        // Поля человека (истина онтологии риска) машина не выдумывает.
        assertTrue("probability" !in trl.payload && "owner" !in trl.payload, "вероятность и владелец — поля сцены 11")

        // Бюджет: запас съеден, категория cost, узел — корень бюджета.
        val бюджет = прогон.diff.new.single { it.payload["category"] == "cost" }
        assertTrue("запас бюджета исчерпан" in (бюджет.payload["statement"] ?: ""), "формулировка про запас: ${бюджет.payload["statement"]}")
        val бюджетОснование = бюджет.basis.single() as Basis.Gap
        assertEquals("бюджет", бюджетОснование.kind)
        assertEquals("C-0007", бюджетОснование.node, "узел разрыва бюджета — корень бюджета")

        // Цель без требования: категория programmatic, узел-цель вне refs
        // (refs вида risk держат component|scene).
        val цель = прогон.diff.new.single { it.payload["category"] == "programmatic" }
        assertTrue("G-1" in (цель.payload["statement"] ?: ""), "формулировка называет цель: ${цель.payload["statement"]}")
        assertTrue("refs" !in цель.payload, "цель — не component|scene, в refs не кладём")

        // Лестница: грани узла пусты к точке — разрыв из порта архитектуры.
        val лестница = прогон.diff.new.mapNotNull { it.basis.filterIsInstance<Basis.Gap>().firstOrNull() }
            .firstOrNull { it.kind == "лестница" }
        assertTrue(лестница != null, "пустые грани лестницы — разрывы: ${прогон.note}")
        assertEquals("GT-SDR", лестница!!.point)
    }

    @Test
    fun `модель переформулировала — её формулировка и части cec в предложении`() {
        стенд()
        канал.ответ = """
            {"risks":[
              {"id":"g1","statement":"Созревание приёмного модуля не успевает к SDR",
               "condition":"TRL модуля держится на 4","event":"SDR наступает без TRL 6",
               "consequence":"модуль не проходит SDR"}
            ]}
        """.trimIndent()

        val прогон = деривер().deriveInto(ПРОЕКТ, АВТОР, синтез)
        val trl = прогон.diff.new.single { it.basis.filterIsInstance<Basis.Gap>().firstOrNull()?.kind == "trl" }

        assertEquals("Созревание приёмного модуля не успевает к SDR", trl.payload["statement"])
        val cec = mapper.readTree(trl.payload["cec"]!!)
        assertEquals("TRL модуля держится на 4", cec.path("condition").asText())
        assertEquals("SDR наступает без TRL 6", cec.path("event").asText())
        assertEquals("модуль не проходит SDR", cec.path("consequence").asText())
    }

    @Test
    fun `модель недоступна — шаблонные формулировки, прогон жив`() {
        стенд()
        канал.беда = "сеть недоступна"

        val прогон = деривер().deriveInto(ПРОЕКТ, АВТОР, синтез)

        assertTrue(прогон.diff.new.isNotEmpty(), "риски пришли шаблонными: ${прогон.note}")
        assertTrue("модель недоступна" in (прогон.note ?: ""), "помета о шаблоне: ${прогон.note}")
        assertTrue(прогон.diff.new.all { "Если " in (it.payload["statement"] ?: "") }, "формулировки шаблонные")
    }

    @Test
    fun `ворота — пустая формулировка модели — отказ поимённо, шаблон не подставляем`() {
        стенд()
        канал.ответ = """{"risks":[{"id":"g1","statement":"  "}]}"""

        val прогон = деривер().deriveInto(ПРОЕКТ, АВТОР, синтез)

        assertTrue(прогон.diff.new.none { it.basis.filterIsInstance<Basis.Gap>().firstOrNull()?.kind == "trl" },
            "риск с пустой формулировкой отбит: ${прогон.note}")
        assertTrue("пустая формулировка" in (прогон.note ?: ""), "отказ поимённо: ${прогон.note}")
    }

    @Test
    fun `ворота — модель ответила не по схеме — шаблонные формулировки, прогон жив`() {
        стенд()
        канал.ответ = """не json"""

        val прогон = деривер().deriveInto(ПРОЕКТ, АВТОР, синтез)

        assertTrue(прогон.diff.new.isNotEmpty(), "ответ не разобран — шаблон: ${прогон.note}")
        assertTrue("не разобран" in (прогон.note ?: ""), "помета о шаблоне: ${прогон.note}")
    }

    @Test
    fun `разрывов нет — пустой прогон, модель не зовём`() {
        // Проект без всего: TRL на месте (технологий нет), граней нет,
        // бюджетов нет, целей нет.
        store.create(
            ПРОЕКТ, "project", Area.Project(ПРОЕКТ), null,
            mapper.createObjectNode().put("name", "Пустой стенд").put("knowledge_v2", true), пров(),
        )
        store.create(
            "GT-SDR", "gate", Area.Project(ПРОЕКТ), null,
            mapper.createObjectNode().put("phase", "Phase A").put("key", "SDR")
                .put("title", "SDR").put("kind", "phase").put("planned_date", "2099-01-01")
                .set("criteria", mapper.createArrayNode()),
            пров(),
        )
        канал.ответ = """{"risks":[]}"""

        val прогон = деривер().deriveInto(ПРОЕКТ, АВТОР, синтез)

        assertTrue(прогон.diff.new.isEmpty(), "разрывов нет — предложений нет")
        assertTrue("разрывов фазы не нашлось" in (прогон.note ?: ""), "причина названа: ${прогон.note}")
        assertTrue(канал.промпты.isEmpty(), "модель не зовём — формулировать нечего")
    }

    // --- срез стенда ---------------------------------------------------------

    private var точкаId: String = ""

    /**
     * Проект + веха SDR + узел + технология с разрывом TRL + цель без
     * требования + бюджет с съеденным резервом + единица справочника.
     */
    private fun стенд() {
        store.create(
            ПРОЕКТ, "project", Area.Project(ПРОЕКТ), null,
            mapper.createObjectNode().put("name", "Стенд разрывов").put("knowledge_v2", true), пров(),
        )
        точкаId = store.create(
            "GT-SDR", "gate", Area.Project(ПРОЕКТ), null,
            mapper.createObjectNode().put("phase", "Phase A").put("key", "SDR")
                .put("title", "SDR").put("kind", "phase").put("planned_date", "2099-01-01")
                .set("criteria", mapper.createArrayNode()),
            пров(),
        ).id
        val узел = store.create(
            "C-0007", "component", Area.Project(ПРОЕКТ), null,
            mapper.createObjectNode().put("name", "Приёмный модуль").put("kind", "element"), пров(),
        )
        store.create(
            "T-1", "technology", Area.Project(ПРОЕКТ), null,
            mapper.createObjectNode().put("name", "Приём тракта")
                .put("component", узел.id)
                .put("trl_current", 4).put("trl_required", 6)
                .put("required_by", "GT-SDR"),
            пров(),
        )
        store.create(
            "G-1", "goal", Area.Project(ПРОЕКТ), null,
            mapper.createObjectNode().put("statement", "Принять сигнал")
                .put("measure", "доля принятых кадров").put("year", 2030)
                .set("needs", mapper.createArrayNode()),
            пров(),
        )
        // Бюджет: потолок 100 кг, резерв 20 % → потолок долей 80 кг; принято
        // 90 кг связанным требованием — запас съеден.
        val потолок = store.create(
            "R-CEIL", "requirement", Area.Project(ПРОЕКТ), null,
            mapper.createObjectNode().put("level", "system").put("code", "R-CEIL")
                .put("title", "Потолок массы").put("statement", "Масса не более 100 кг")
                .put("category", "technical").put("priority", "must")
                .put("carrier", "система").put("verification_method", "analysis")
                .put("source", "расчёт").put("ears_pattern", "—")
                .set("measure", mapper.createObjectNode().put("value", 100).put("unit", "кг")),
            пров(),
        )
        val доля = store.create(
            "R-SHARE", "requirement", Area.Project(ПРОЕКТ), null,
            mapper.createObjectNode().put("level", "element").put("code", "R-SHARE")
                .put("title", "Доля массы узла").put("statement", "Масса узла — 90 кг")
                .put("category", "technical").put("priority", "must")
                .put("carrier", "C-0007").put("verification_method", "analysis")
                .put("source", "расчёт").put("ears_pattern", "—")
                .set("measure", mapper.createObjectNode().put("value", 90).put("unit", "кг")),
            пров(),
        )
        store.create(
            "B-1", "budget", Area.Project(ПРОЕКТ), null,
            mapper.createObjectNode().put("kind", "mass").put("root", узел.id)
                .put("reserve_policy", "резерв 20 % до SDR").put("reserve_pct", 20)
                .put("requirement", потолок.id),
            пров(),
        )
        links.link("derives_from", доля.id, потолок.id, пров(канал = Channel.SERVICE), rationale = "доля массы")
        store.create(
            "U-кг", "unit", Area.Library, null,
            mapper.createObjectNode().put("symbol", "кг").put("name", "кг").put("dimension", "масса")
                .put("factor", 1.0).put("canonical", true).put("conversion_type", "linear"),
            пров(),
        )
        // Полка лестницы: у носителей (node) грань «функции» обязательна к
        // SRR — у голого узла она пуста, это и есть разрыв лестницы.
        store.create(
            "CTS-9001", "component_template_shelf", Area.Library, null,
            mapper.readTree("""{"facet_defaults":{"node":{"SRR":["functions"]}}}"""),
            пров(),
        )
    }

    private fun пров(канал: Channel = Channel.MANUAL) = Provenance(канал, АВТОР)

    private companion object {
        const val ПРОЕКТ = "PRJ"
        const val АВТОР = "инженер"
    }
}

/** Канал-подмена: сеть не нужна, ответ задаётся тестом, недоступность — бедой. */
private class КаналРазрывов : Transport {
    var ответ: String = """{"risks":[]}"""
    var беда: String? = null
    val промпты = mutableListOf<String>()

    override fun ask(prompt: String, model: String?, maxTokens: Int?, schema: JsonNode?): Answer {
        промпты += prompt
        беда?.let { throw ProviderUnavailable(it) }
        return Answer(text = ответ, model = "модель-теста", tokensIn = 80, tokensOut = 120)
    }
}

/** Реестр связей в памяти — для покрытия целей и суммы долей бюджета. */
private class СвязиПоля : LinkRegistry {
    private val связи = linkedMapOf<String, Link>()
    private var счётчик = 0

    override fun link(type: String, from: String, to: String, provenance: Provenance, rationale: String?, subtype: String?): Link {
        счётчик += 1
        val связь = Link("LN-$счётчик", type, from, to, rationale, provenance, subtype)
        связи[связь.id] = связь
        return связь
    }

    override fun unlink(id: String, provenance: Provenance) { связи.remove(id) }
    override fun byId(id: String): Link? = связи[id]
    override fun from(id: String, type: String?): List<Link> =
        связи.values.filter { it.from == id && (type == null || it.type == type) }

    override fun to(id: String, type: String?): List<Link> =
        связи.values.filter { it.to == id && (type == null || it.type == type) }

    override fun confirm(id: String, by: String): Link = связи.getValue(id)
}
