// Стыки из обменов (§2.3 шипа 6) — помощник Phase A на каркасе 2.1–2.2.
//
// Проверяются машинные ворота: обмен через границу узла даёт кандидата-сырца,
// внешний участник сценария — только заведённой стороной; сторона a — этот
// узел, сторона b — из среза; тип и направление — из перечня истины схем;
// повтор существующего стыка — confirm на принятый, а не второй экземпляр;
// пустой срез — прогон пустой, модель не зовём. Канал к модели подменён.
package orbita.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.ai.api.AiFactory
import orbita.ai.api.Answer
import orbita.ai.api.Basis
import orbita.ai.api.Transport
import orbita.ai.api.Verdict
import orbita.ai.internal.InterfaceDeriver
import orbita.ai.internal.Synthesizer
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Provenance
import orbita.knowledge.api.KnowledgeFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InterfaceDeriveTest {

    private val mapper = ObjectMapper()
    private val store = ПамятьПоля()
    private val знания = KnowledgeFactory.intake(store, null, mapper)
    private val канал = КаналСтыков()
    private val служба = AiFactory.service(store, канал, mapper)
    private val деривер = InterfaceDeriver(store, знания, служба, mapper)
    private val синтез = Synthesizer(store, знания, служба, mapper)

    @Test
    fun `обмен через границу — стык узла с соседом, основание-обмен в ручной прогон`() {
        схема()
        канал.ответ = """
            {"interfaces":[
              {"id":"i1","a":"C-0007","b":"C-0008","name":"передача сообщений",
               "type":"data","direction":"uni",
               "why":"обмен EX-1 между функциями узлов"}
            ]}
        """.trimIndent()

        val прогон = деривер.deriveInto(ПРОЕКТ, "C-0007", АВТОР, синтез)

        assertEquals("manual", прогон.trigger, "прогон помощника — ручной")
        val предложение = прогон.diff.new.single()
        assertEquals("interface", предложение.concept)
        assertEquals(узел7Id, предложение.payload["a"], "сторона a — этот узел (id)")
        assertEquals(узел8Id, предложение.payload["b"], "сторона b — соседний узел (id)")
        assertEquals("передача сообщений", предложение.payload["name"])
        assertEquals("data", предложение.payload["type"])
        assertEquals("uni", предложение.payload["direction"])
        val основание = предложение.basis.single()
        assertTrue(основание is Basis.Entity && основание.kind == "exchange" && основание.code == "EX-1",
            "основание стыка — обмен через границу: $основание")

        val карточка = store.list(Area.Project(ПРОЕКТ), "proposal").single()
        assertEquals("interface_from_exchange", карточка.doc.path("package_kind").asText())
    }

    @Test
    fun `обмен на внешнюю сторону — стык с внешней системой объектом`() {
        схема()
        // Второй обмен: функция узла → внешняя сторона.
        store.create(
            "EX-2", "exchange", Area.Project(ПРОЕКТ), null,
            mapper.createObjectNode().put("name", "доклад ЦУП")
                .put("source_function", "FU-1").put("target", "SH-1").put("payload", "сводка"),
            пров(),
        )
        канал.ответ = """
            {"interfaces":[
              {"id":"i1","a":"C-0007","b":"SH-1","name":"доклад в ЦУП",
               "type":"data","direction":"uni"}
            ]}
        """.trimIndent()

        val прогон = деривер.deriveInto(ПРОЕКТ, "C-0007", АВТОР, синтез)

        val предложение = прогон.diff.new.single()
        val b = mapper.readTree(предложение.payload["b"]!!)
        assertTrue(b.isObject, "внешняя сторона — объектом, как пишет ручная форма: $b")
        assertEquals("Оператор ЦУП", b.path("name").asText(), "имя внешней системы — имя стороны")
        assertEquals(сторонаId, b.path("owner").asText(), "владелец — id стороны")
    }

    @Test
    fun `внешний участник сценария без обмена — стык со стороной по шагу цепочки`() {
        паспорт()
        узлы()
        // Цепочка узла: второй шаг без участника-узла — актор внешний.
        val ц = mapper.createObjectNode().put("name", "Смена")
        val шаги = ц.putArray("steps")
        шаги.addObject().put("what", "модуль принимает пакет").put("actor", "модуль").put("component", "C-0007")
        шаги.addObject().put("what", "оператор подтверждает приём").put("actor", "Оператор ЦУП")
        store.create("FC-1", "functional_chain", Area.Project(ПРОЕКТ), null, ц, пров())
        store.create(
            "SH-1", "stakeholder", Area.Project(ПРОЕКТ), null,
            mapper.createObjectNode().put("name", "Оператор ЦУП").put("role", "оператор"), пров(),
        )
        канал.ответ = """
            {"interfaces":[
              {"id":"i1","a":"C-0007","b":"SH-1","name":"подтверждение приёма",
               "type":"hmi","direction":"uni"}
            ]}
        """.trimIndent()

        val прогон = деривер.deriveInto(ПРОЕКТ, "C-0007", АВТОР, синтез)

        val предложение = прогон.diff.new.single()
        val основание = предложение.basis.single()
        assertTrue(основание is Basis.Entity && основание.kind == "functional_chain" && основание.code == "FC-1",
            "основание — шаг сценария: $основание")
    }

    @Test
    fun `ворота — стороны b нет в срезе — отказ поимённо`() {
        схема()
        канал.ответ = """
            {"interfaces":[
              {"id":"i1","a":"C-0007","b":"C-НЕТ","name":"нечто","type":"data","direction":"uni"}
            ]}
        """.trimIndent()

        val прогон = деривер.deriveInto(ПРОЕКТ, "C-0007", АВТОР, синтез)

        assertTrue(прогон.diff.new.isEmpty(), "стык с чужой стороной в прогон не идёт")
        assertTrue("C-НЕТ" in (прогон.note ?: ""), "отказ называет сторону: ${прогон.note}")
    }

    @Test
    fun `ворота — тип вне перечня истины схем — отказ`() {
        схема()
        канал.ответ = """
            {"interfaces":[
              {"id":"i1","a":"C-0007","b":"C-0008","name":"нечто","type":"laser","direction":"uni"}
            ]}
        """.trimIndent()

        val прогон = деривер.deriveInto(ПРОЕКТ, "C-0007", АВТОР, синтез)

        assertTrue(прогон.diff.new.isEmpty(), "стык с типом вне перечня отбит")
        assertTrue("laser" in (прогон.note ?: ""), "отказ называет тип: ${прогон.note}")
    }

    @Test
    fun `ворота — направление вне перечня истины схем — отказ`() {
        схема()
        канал.ответ = """
            {"interfaces":[
              {"id":"i1","a":"C-0007","b":"C-0008","name":"нечто","type":"data","direction":"sideways"}
            ]}
        """.trimIndent()

        val прогон = деривер.deriveInto(ПРОЕКТ, "C-0007", АВТОР, синтез)

        assertTrue(прогон.diff.new.isEmpty(), "стык с направлением вне перечня отбит")
        assertTrue("sideways" in (прогон.note ?: ""), "отказ называет направление: ${прогон.note}")
    }

    @Test
    fun `ворота — сторона a не этот узел — отказ`() {
        схема()
        канал.ответ = """
            {"interfaces":[
              {"id":"i1","a":"C-0008","b":"C-0007","name":"нечто","type":"data","direction":"uni"}
            ]}
        """.trimIndent()

        val прогон = деривер.deriveInto(ПРОЕКТ, "C-0007", АВТОР, синтез)

        assertTrue(прогон.diff.new.isEmpty(), "стык не про этот узел отбит")
        assertTrue("не этот узел" in (прогон.note ?: ""), "отказ называет причину: ${прогон.note}")
    }

    @Test
    fun `ворота — повтор существующего стыка — confirm на принятый, не второй экземпляр`() {
        схема()
        // Стык этой пары и типа уже принят — соседи и в обратном порядке.
        store.create(
            "IF-1", "interface", Area.Project(ПРОЕКТ), "7",
            mapper.createObjectNode().put("name", "передача сообщений").put("type", "data")
                .put("direction", "uni").put("a", узел8Id).put("b", узел7Id),
            пров(),
        )
        канал.ответ = """
            {"interfaces":[
              {"id":"i1","a":"C-0007","b":"C-0008","name":"передача сообщений",
               "type":"data","direction":"uni"}
            ]}
        """.trimIndent()

        val прогон = деривер.deriveInto(ПРОЕКТ, "C-0007", АВТОР, синтез)

        assertTrue(прогон.diff.new.isEmpty(), "второй экземпляр не предлагается")
        val подтверждение = прогон.diff.confirm.single()
        assertEquals("IF-1", подтверждение.targetRef, "подтверждение ссылается на принятый стык")
        assertEquals("type", подтверждение.diffField)
        assertEquals("interface", подтверждение.concept)
    }

    @Test
    fun `ворота — обмен уже ссылается на стык — не повторяем его`() {
        схема()
        // Тот же обмен, но стык у него уже есть: выводить нечего, модель не зовём.
        store.create(
            "IF-1", "interface", Area.Project(ПРОЕКТ), "7",
            mapper.createObjectNode().put("name", "передача сообщений").put("type", "data")
                .put("direction", "uni").put("a", узел7Id).put("b", узел8Id),
            пров(),
        )
        // Поле interface кладём прямой правкой документа: стык у обмена есть.
        val обмен = store.byCode(Area.Project(ПРОЕКТ), "EX-1")!!
        val документ = обмен.doc.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        документ.put("interface", "IF-1")
        store.update(обмен.id, документ, пров())

        val прогон = деривер.deriveInto(ПРОЕКТ, "C-0007", АВТОР, синтез)

        assertTrue(прогон.diff.new.isEmpty(), "обмен со стыком повторно не выводится")
        assertTrue(канал.промпты.isEmpty(), "модель не зовём: срез пуст")
    }

    @Test
    fun `ворота — нет обменов через границу и внешних участников — пустой прогон, модель не зовём`() {
        паспорт()
        узлы()
        // Функции и обмен есть, но обмен — внутри другой пары узлов.
        store.create(
            "FU-9", "function", Area.Project(ПРОЕКТ), null,
            mapper.createObjectNode().put("name", "чужая функция").put("layer", "SA")
                .set("allocated_to", mapper.createArrayNode().add("нет-такого-узла")),
            пров(),
        )

        val прогон = деривер.deriveInto(ПРОЕКТ, "C-0007", АВТОР, синтез)

        assertTrue(прогон.diff.new.isEmpty(), "без среза предложений нет")
        assertTrue("нет обменов через границу" in (прогон.note ?: ""), "причина названа: ${прогон.note}")
        assertTrue(канал.промпты.isEmpty(), "модель не зовём — ворота до вызова")
    }

    // --- срез стенда ---------------------------------------------------------

    private var узел7Id: String = ""
    private var узел8Id: String = ""
    private var сторонаId: String = ""

    private fun паспорт() {
        store.create(
            ПРОЕКТ, "project", Area.Project(ПРОЕКТ), null,
            mapper.createObjectNode().put("name", "Стенд стыков").put("knowledge_v2", true),
            пров(),
        )
    }

    private fun узлы() {
        узел7Id = store.create(
            "C-0007", "component", Area.Project(ПРОЕКТ), null,
            mapper.createObjectNode().put("name", "Приёмный модуль").put("kind", "element"), пров(),
        ).id
        узел8Id = store.create(
            "C-0008", "component", Area.Project(ПРОЕКТ), null,
            mapper.createObjectNode().put("name", "Шлюз").put("kind", "element"), пров(),
        ).id
        сторонаId = store.create(
            "SH-1", "stakeholder", Area.Project(ПРОЕКТ), null,
            mapper.createObjectNode().put("name", "Оператор ЦУП").put("role", "оператор"), пров(),
        ).id
    }

    /** Проект + два узла + сторона + функции на обоих узлах + обмен между ними. */
    private fun схема() {
        паспорт()
        узлы()
        store.create(
            "FU-1", "function", Area.Project(ПРОЕКТ), null,
            mapper.createObjectNode().put("name", "передать сообщение").put("layer", "SA")
                .set("allocated_to", mapper.createArrayNode().add(узел7Id)),
            пров(),
        )
        store.create(
            "FU-2", "function", Area.Project(ПРОЕКТ), null,
            mapper.createObjectNode().put("name", "принять сообщение").put("layer", "SA")
                .set("allocated_to", mapper.createArrayNode().add(узел8Id)),
            пров(),
        )
        store.create(
            "EX-1", "exchange", Area.Project(ПРОЕКТ), null,
            mapper.createObjectNode().put("name", "сообщение терминала")
                .put("source_function", "FU-1").put("target", "FU-2").put("payload", "пакет данных"),
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
private class КаналСтыков : Transport {
    var ответ: String = """{"interfaces":[]}"""
    val промпты = mutableListOf<String>()

    override fun ask(prompt: String, model: String?, maxTokens: Int?, schema: JsonNode?): Answer {
        промпты += prompt
        return Answer(text = ответ, model = "модель-теста", tokensIn = 60, tokensOut = 90)
    }
}
