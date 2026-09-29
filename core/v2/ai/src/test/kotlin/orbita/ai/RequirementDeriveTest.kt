// Деривация требований вниз (§2.1 шипа 6) — образец помощника Phase A.
//
// Проверяется НЕ «модель ответила», а машинные ворота помощника: уровень
// дочернего требования выводится из ВИДА УЗЛА (диф 29.09), модель его не
// выбирает; без ступени и без родителя деривации нет; родитель обязан быть в
// срезе и ступенью строго ВЫШЕ по порядку правила, не перечня. Годное ложится
// предложением с основанием-РОДИТЕЛЕМ (Basis.Entity) в РУЧНОЙ прогон с пакетом
// деривации. Канал к модели подменён.
//
// Единицу из справочника и «сумму долей бюджета ≤ родителя» ставит помощник
// мер (§2.1-C2) — здесь их ещё нет.
package orbita.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.ai.api.AiFactory
import orbita.ai.api.Answer
import orbita.ai.api.Basis
import orbita.ai.api.Transport
import orbita.ai.internal.RequirementDeriver
import orbita.ai.internal.Synthesizer
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Provenance
import orbita.knowledge.api.Canonical
import orbita.knowledge.api.KnowledgeFactory
import orbita.knowledge.api.Measures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RequirementDeriveTest {

    private val mapper = ObjectMapper()
    private val store = ПамятьПоля()
    private val знания = KnowledgeFactory.intake(store, null, mapper)
    private val канал = КаналДеривации()
    private val служба = AiFactory.service(store, канал, mapper)
    private val меры = ЗаглушкаМер(setOf("кг", "Вт", "мин"))
    private val деривер = RequirementDeriver(store, знания, служба, меры, mapper)
    private val синтез = Synthesizer(store, знания, служба, mapper)

    @Test
    fun `годная деривация — уровень из вида узла, основание-родитель, ручной прогон`() {
        схема(видУзла = "element") // узел-элемент под системным корнем с требованием RQ-S-1
        канал.ответ = """
            {"requirements":[
              {"id":"r1","derives_from":"RQ-S-1",
               "title":"Масса приёмного модуля","statement":"Приёмный модуль не тяжелее 4 кг.",
               "measure":{"value":"4","unit":"кг"},"why":"доля массового бюджета КА"}
            ]}
        """.trimIndent()

        val прогон = деривер.deriveInto(ПРОЕКТ, "C-0007", АВТОР, синтез)

        assertEquals("manual", прогон.trigger, "прогон помощника — ручной")
        val предложение = прогон.diff.new.single()
        assertEquals("requirement", предложение.concept)
        assertEquals("element", предложение.payload["level"], "уровень выведен из вида узла, не из ответа модели")
        assertTrue("кг" in (предложение.payload["measure"] ?: ""), "показатель с единицей справочника — в поле")
        val основание = предложение.basis.single()
        assertTrue(основание is Basis.Entity, "основание деривации — родительское требование, не факт")
        assertEquals("RQ-S-1", (основание as Basis.Entity).code, "основание — код родителя из среза")
        assertEquals(узелId, предложение.payload["carrier"], "носитель дочернего требования — этот узел (id)")

        // Помощника отличает package_kind на карточке, не код понятия (диф 28.09).
        val карточка = store.list(Area.Project(ПРОЕКТ), "proposal").single()
        assertEquals("requirement_derivation", карточка.doc.path("package_kind").asText())
    }

    @Test
    fun `ворота — родителя нет среди требований узла и предков — отбито`() {
        схема(видУзла = "element")
        канал.ответ = """
            {"requirements":[
              {"id":"r1","derives_from":"RQ-НЕТ","statement":"Требование без родителя.","why":"—"}
            ]}
        """.trimIndent()

        val прогон = деривер.deriveInto(ПРОЕКТ, "C-0007", АВТОР, синтез)

        assertTrue(прогон.diff.new.isEmpty(), "требование с несуществующим родителем в прогон не идёт")
        assertTrue("RQ-НЕТ" in (прогон.note ?: ""), "отказ назван поимённо: ${прогон.note}")
    }

    @Test
    fun `ворота — ступень узла не ниже ступени родителя — отбито`() {
        // Узел вида system → ступень требований system, родитель тоже system:
        // это не деривация вниз (сравнение по порядку правила, не перечня).
        схема(видУзла = "system")
        канал.ответ = """
            {"requirements":[
              {"id":"r1","derives_from":"RQ-S-1","statement":"То же по ступени, не ниже.","why":"—"}
            ]}
        """.trimIndent()

        val прогон = деривер.deriveInto(ПРОЕКТ, "C-0007", АВТОР, синтез)

        assertTrue(прогон.diff.new.isEmpty(), "равная ступень — не деривация")
        assertTrue("не выше" in (прогон.note ?: ""), "отказ называет причину: ${прогон.note}")
    }

    @Test
    fun `ворота — единица показателя вне справочника — отбито`() {
        схема(видУзла = "element")
        канал.ответ = """
            {"requirements":[
              {"id":"r1","derives_from":"RQ-S-1","statement":"Модуль не тяжелее 4 мдж.",
               "measure":{"value":"4","unit":"мдж"},"why":"опечатка единицы"}
            ]}
        """.trimIndent()

        val прогон = деривер.deriveInto(ПРОЕКТ, "C-0007", АВТОР, синтез)

        assertTrue(прогон.diff.new.isEmpty(), "мерное требование с единицей вне справочника не идёт")
        assertTrue("мдж" in (прогон.note ?: ""), "отказ называет единицу: ${прогон.note}")
    }

    @Test
    fun `ворота — вид узла не даёт ступени — пустой прогон, модель не зовём`() {
        паспорт()
        компонент("C-0100", parent = null, kind = "") // узел без вида: ступени нет

        val прогон = деривер.deriveInto(ПРОЕКТ, "C-0100", АВТОР, синтез)

        assertTrue(прогон.diff.new.isEmpty(), "без ступени предложений нет")
        assertTrue("ступени" in (прогон.note ?: ""), "причина названа: ${прогон.note}")
        assertTrue(канал.промпты.isEmpty(), "без ступени модель не зовём — ворота машинные, до вызова")
    }

    @Test
    fun `ворота — у узла и предков нет требований — пустой прогон с причиной`() {
        паспорт()
        компонент("C-0100", parent = null, kind = "element") // ступень есть, родителя нет

        val прогон = деривер.deriveInto(ПРОЕКТ, "C-0100", АВТОР, синтез)

        assertTrue(прогон.diff.new.isEmpty(), "без родителя предложений нет")
        assertTrue("нет" in (прогон.note ?: "").lowercase(), "причина названа: ${прогон.note}")
        assertTrue(канал.промпты.isEmpty(), "без родителя модель не зовём")
    }

    // --- срез стенда ---------------------------------------------------------

    /** id узла-носителя C-0007 — carrier дочерних требований хранится им. */
    private var узелId: String = ""

    /** Проект + системный корень C-0001, узел C-0007 (вид задаётся) под ним, требование RQ-S-1 на корне. */
    private fun схема(видУзла: String) {
        паспорт()
        val корень = компонент("C-0001", parent = null, kind = "system")
        узелId = компонент("C-0007", parent = корень, kind = видУзла)
        требование("RQ-S-1", level = "system", carrier = корень, statement = "Масса КА не более 100 кг.")
    }

    private fun паспорт() {
        store.create(
            ПРОЕКТ, "project", Area.Project(ПРОЕКТ), null,
            mapper.createObjectNode().put("name", "Стенд деривации").put("knowledge_v2", true),
            Provenance(Channel.MANUAL, АВТОР),
        )
    }

    /** Узел состава; parent — id корня; kind — вид узла (пусто = без вида). Возвращает id. */
    private fun компонент(код: String, parent: String?, kind: String): String {
        val документ = mapper.createObjectNode().put("name", "узел $код")
        parent?.let { документ.put("parent", it) }
        kind.takeIf { it.isNotBlank() }?.let { документ.put("kind", it) }
        return store.create(
            код, "component", Area.Project(ПРОЕКТ), null, документ, Provenance(Channel.MANUAL, АВТОР),
        ).id
    }

    private fun требование(код: String, level: String, carrier: String, statement: String) {
        val документ = mapper.createObjectNode()
            .put("level", level).put("title", statement.take(60)).put("statement", statement)
            .put("category", "performance").put("carrier", carrier).put("ears_pattern", "ubiquitous")
        store.create(код, "requirement", Area.Project(ПРОЕКТ), null, документ, Provenance(Channel.MANUAL, АВТОР))
    }

    private companion object {
        const val ПРОЕКТ = "PRJ"
        const val АВТОР = "инженер"
    }
}

/** Канал-подмена: сеть не нужна, ответ задаётся тестом, промпты видны. */
private class КаналДеривации : Transport {
    var ответ: String = """{"requirements":[]}"""
    val промпты = mutableListOf<String>()

    override fun ask(prompt: String, model: String?, maxTokens: Int?, schema: JsonNode?): Answer {
        промпты += prompt
        return Answer(text = ответ, model = "модель-теста", tokensIn = 50, tokensOut = 80)
    }
}

/** Меры-заглушка: единица известна, если она в наборе; канон — по значению value. */
private class ЗаглушкаМер(private val известные: Set<String>) : Measures {
    override fun dimension(unit: String): String? = if (unit.trim() in известные) "dim:${unit.trim()}" else null

    override fun canonical(measure: JsonNode): Canonical? {
        val единица = measure.path("unit").asText("").trim()
        val размерность = dimension(единица) ?: return null
        val значение = measure.path("value").asText("").toDoubleOrNull() ?: return null
        return Canonical(значение, единица, размерность)
    }
}
