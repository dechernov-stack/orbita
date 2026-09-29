// Функции из шагов сценариев (§2.2 шипа 6) — помощник Phase A на каркасе 2.1.
//
// Проверяется НЕ «модель ответила», а машинные ворота: шаг без участника-узла
// функции не даёт; функция несёт узел-носитель (allocated_to), иначе отбита;
// годное ложится предложением с основанием-ШАГОМ (Basis.Entity сценария) в
// РУЧНОЙ прогон с пакетом. Канал к модели подменён.
package orbita.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.ai.api.AiFactory
import orbita.ai.api.Answer
import orbita.ai.api.Basis
import orbita.ai.api.Transport
import orbita.ai.internal.FunctionDeriver
import orbita.ai.internal.Synthesizer
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Provenance
import orbita.knowledge.api.KnowledgeFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FunctionDeriveTest {

    private val mapper = ObjectMapper()
    private val store = ПамятьПоля()
    private val знания = KnowledgeFactory.intake(store, null, mapper)
    private val канал = КаналФункций()
    private val служба = AiFactory.service(store, канал, mapper)
    private val деривер = FunctionDeriver(store, знания, служба, mapper)
    private val синтез = Synthesizer(store, знания, служба, mapper)

    @Test
    fun `годный вывод — функция с узлом-носителем и основанием-шагом в ручной прогон`() {
        схема()
        канал.ответ = """
            {"functions":[
              {"id":"f1","chain":"FC-1","step":"терминал передаёт сообщение","node":"C-0007",
               "name":"передать сообщение терминала","why":"шаг сценария с узлом"}
            ]}
        """.trimIndent()

        val прогон = деривер.deriveInto(ПРОЕКТ, АВТОР, синтез)

        assertEquals("manual", прогон.trigger, "прогон помощника — ручной")
        val предложение = прогон.diff.new.single()
        assertEquals("function", предложение.concept)
        assertEquals("передать сообщение терминала", предложение.payload["name"])
        assertEquals("SA", предложение.payload["layer"], "слой по умолчанию — SA")
        assertTrue(узелId in (предложение.payload["allocated_to"] ?: ""), "носитель функции — узел шага (id)")
        val основание = предложение.basis.single()
        assertTrue(основание is Basis.Entity, "основание функции — шаг сценария, не факт")
        assertEquals("functional_chain", (основание as Basis.Entity).kind)
        assertEquals("FC-1", основание.code, "основание — код сценария из среза")

        val карточка = store.list(Area.Project(ПРОЕКТ), "proposal").single()
        assertEquals("function_from_scenario", карточка.doc.path("package_kind").asText())
    }

    @Test
    fun `ворота — узла функции нет в составе — отбито`() {
        схема()
        канал.ответ = """
            {"functions":[
              {"id":"f1","chain":"FC-1","step":"шаг","node":"C-НЕТ","name":"сделать нечто"}
            ]}
        """.trimIndent()

        val прогон = деривер.deriveInto(ПРОЕКТ, АВТОР, синтез)

        assertTrue(прогон.diff.new.isEmpty(), "функция с несуществующим узлом в прогон не идёт")
        assertTrue("C-НЕТ" in (прогон.note ?: ""), "отказ называет узел: ${прогон.note}")
    }

    @Test
    fun `ворота — нет шагов сценариев с узлом — пустой прогон, модель не зовём`() {
        паспорт()
        // Сценарий без участника-узла: шаги есть, но component пуст.
        val ц = mapper.createObjectNode().put("name", "Связь")
        ц.putArray("steps").addObject().put("what", "оператор наблюдает").put("actor", "оператор")
        store.create("FC-2", "functional_chain", Area.Project(ПРОЕКТ), null, ц, пров())

        val прогон = деривер.deriveInto(ПРОЕКТ, АВТОР, синтез)

        assertTrue(прогон.diff.new.isEmpty(), "без шагов с узлом предложений нет")
        assertTrue("нет шагов" in (прогон.note ?: ""), "причина названа: ${прогон.note}")
        assertTrue(канал.промпты.isEmpty(), "без шагов с узлом модель не зовём — ворота до вызова")
    }

    // --- срез стенда ---------------------------------------------------------

    private var узелId: String = ""

    /** Проект + узел C-0007 + сценарий FC-1, шаг которого несёт этот узел участником. */
    private fun схема() {
        паспорт()
        узелId = store.create(
            "C-0007", "component", Area.Project(ПРОЕКТ), null,
            mapper.createObjectNode().put("name", "Приёмный модуль").put("kind", "element"), пров(),
        ).id
        val ц = mapper.createObjectNode().put("name", "Доставка сообщения")
        ц.putArray("steps").addObject()
            .put("what", "терминал передаёт сообщение").put("actor", "терминал").put("component", "C-0007")
        store.create("FC-1", "functional_chain", Area.Project(ПРОЕКТ), null, ц, пров())
    }

    private fun паспорт() {
        store.create(
            ПРОЕКТ, "project", Area.Project(ПРОЕКТ), null,
            mapper.createObjectNode().put("name", "Стенд функций").put("knowledge_v2", true),
            пров(),
        )
    }

    private fun пров() = Provenance(Channel.MANUAL, АВТОР)

    private companion object {
        const val ПРОЕКТ = "PRJ"
        const val АВТОР = "инженер"
    }
}

/** Канал-подмена: сеть не нужна, ответ задаётся тестом, промпты видны. */
private class КаналФункций : Transport {
    var ответ: String = """{"functions":[]}"""
    val промпты = mutableListOf<String>()

    override fun ask(prompt: String, model: String?, maxTokens: Int?, schema: JsonNode?): Answer {
        промпты += prompt
        return Answer(text = ответ, model = "модель-теста", tokensIn = 50, tokensOut = 80)
    }
}
