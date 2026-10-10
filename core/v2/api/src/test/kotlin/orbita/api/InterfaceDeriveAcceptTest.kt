// Сквозной приём вывода стыков из обменов реальной сверкой (§2.3 шипа 6):
// маршрут /v2/interfaces/derive-node кладёт предложения ручным прогоном,
// приём той же сверкой через /v2/synthesis/runs/{run}/accept ЗАВОДИТ стык
// (стороны id, тип из перечня). Классы требований (истина требует поле
// required у вида interface) сверка спрашивает у человека на приёме —
// правкой в форме, как ручной стык. Без помощника — 501 с указанием, что
// делать. Канал модели подменён.
package orbita.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.ai.api.AiFactory
import orbita.ai.api.Answer
import orbita.ai.api.Transport
import orbita.api.internal.KnowledgeRoutes
import orbita.api.internal.SynthesisRoutes
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.Provenance
import orbita.knowledge.api.KnowledgeFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InterfaceDeriveAcceptTest {

    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val знания = KnowledgeFactory.intake(store, links, mapper)
    private val транспорт = КаналСтыков()
    private val служба = AiFactory.service(store, транспорт, mapper)
    private val сверка = KnowledgeFactory.reconcile(store, links, mapper, intake = знания)
    private val синтезМаршруты = SynthesisRoutes(
        store, AiFactory.synthesisJobs(store, знания, служба, mapper), сверка, mapper, links,
        units = { единица -> единица == "кг" },
    )
    private val п = mapOf("project" to ПРОЕКТ)

    private var узел7Id: String = ""
    private var узел8Id: String = ""

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        схема()
    }

    @Test
    fun `маршрут без помощника — 501, что делать`() {
        val маршруты = KnowledgeRoutes(
            знания, AiFactory.atomize(store, знания, служба, mapper), служба, mapper, store = store,
        )

        val ответ = маршруты.handle("POST", "/v2/interfaces/derive-node", п, """{"node":"C-0007"}""")!!

        assertEquals(501, ответ.code, ответ.body.toString())
        assertTrue(ответ.body.path("what_to_do").asText("").isNotBlank(), "наказ в ответе: ${ответ.body}")
    }

    @Test
    fun `стык из обмена через маршрут — приём той же сверкой, стык заведён`() {
        транспорт.ответ = """{"interfaces":[
            {"id":"i1","a":"C-0007","b":"C-0008","name":"передача сообщений",
             "type":"data","direction":"uni","why":"обмен EX-1 между функциями узлов"}
        ]}"""
        val маршруты = KnowledgeRoutes(
            знания, AiFactory.atomize(store, знания, служба, mapper), служба, mapper, store = store,
            deriveIf = AiFactory.deriveInterfaces(store, знания, служба, mapper),
        )

        val вывод = маршруты.handle(
            "POST", "/v2/interfaces/derive-node", п, """{"node":"C-0007","author":"инженер"}""",
        )!!
        assertEquals(201, вывод.code, вывод.body.toString())
        assertEquals(1, вывод.body.path("proposals").asInt(), "предложение в прогоне: ${вывод.body}")
        val кодПрогона = вывод.body.path("run").asText()

        val прогон = синтезМаршруты.handle("GET", "/v2/synthesis/runs/$кодПрогона", п, null)!!
        val карточка = прогон.body.path("diff").path("new").first().path("proposal").asText()

        val приём = синтезМаршруты.handle(
            "POST", "/v2/synthesis/runs/$кодПрогона/accept", п,
            """{"chosen":["$карточка"],"author":"инженер","reason":"обмен EX-1",
                "edits":{"$карточка":{"requirement_classes":["interface"]}}}""",
        )!!
        assertEquals(201, приём.code, приём.body.toString())
        assertEquals(1, приём.body.path("accepted").asInt(), "стык заведён: ${приём.body}")

        // Стык создан со сторонами id и классами требований из карточки узла.
        val код = приём.body.path("created").map { it.asText() }
            .single { store.byCode(область(), it)?.kind == "interface" }
        val стык = store.byCode(область(), код)!!
        assertEquals(узел7Id, стык.doc.path("a").asText(), "сторона a — узел (id)")
        assertEquals(узел8Id, стык.doc.path("b").asText(), "сторона b — соседний узел (id)")
        assertEquals("передача сообщений", стык.doc.path("name").asText())
        assertEquals("data", стык.doc.path("type").asText())
        assertEquals("uni", стык.doc.path("direction").asText())
        assertEquals(
            "interface", стык.doc.path("requirement_classes").first().asText(),
            "классы требований — правкой инженера на приёме, как спрашивает сверка",
        )
    }

    // --- срез стенда ---------------------------------------------------------

    @Test
    fun `код стыка не лезет под код замысла — префикс IN у обоих, уникальность на область`() {
        // День живой модели шипа 6 (PJ-ПМИ7): минтер считал занятые коды
        // только внутри вида, стык получал код замысла и приём падал на
        // entity_code_in_area. Замысел IN-0001 в проекте — стык обязан
        // стать IN-0002.
        store.create(
            "IN-0001", "intent", область(), null,
            mapper.readTree("""{"for_whom":"стенд","what":"код IN-0001 занят"}"""), пров(),
        )
        транспорт.ответ = """{"interfaces":[
            {"id":"i1","a":"C-0007","b":"C-0008","name":"передача сообщений",
             "type":"data","direction":"uni","why":"обмен EX-1"}
        ]}"""
        val маршруты = KnowledgeRoutes(
            знания, AiFactory.atomize(store, знания, служба, mapper), служба, mapper, store = store,
            deriveIf = AiFactory.deriveInterfaces(store, знания, служба, mapper),
        )
        val вывод = маршруты.handle(
            "POST", "/v2/interfaces/derive-node", п, """{"node":"C-0007","author":"инженер"}""",
        )!!
        assertEquals(201, вывод.code, вывод.body.toString())
        val кодПрогона = вывод.body.path("run").asText()
        val прогон = синтезМаршруты.handle("GET", "/v2/synthesis/runs/$кодПрогона", п, null)!!
        val карточка = прогон.body.path("diff").path("new").first().path("proposal").asText()

        val приём = синтезМаршруты.handle(
            "POST", "/v2/synthesis/runs/$кодПрогона/accept", п,
            """{"chosen":["$карточка"],"author":"инженер","reason":"IN-0001 занят замыслом",
                "edits":{"$карточка":{"requirement_classes":["interface"]}}}""",
        )!!
        assertEquals(201, приём.code, приём.body.toString())
        assertEquals(1, приём.body.path("accepted").asInt(), "стык заведён: ${приём.body}")
        assertEquals(
            "IN-0002", приём.body.path("created").map { it.asText() }
                .single { store.byCode(область(), it)?.kind == "interface" },
            "код стыка — следующий свободный на область, не на вид",
        )
    }

    private fun область() = Area.Project(ПРОЕКТ)
    private fun пров() = Provenance(Channel.MANUAL, "инженер")

    /** Проект + два узла с классами требований + функции на них + обмен. */
    private fun схема() {
        store.create(
            ПРОЕКТ, "project", область(), null,
            mapper.createObjectNode().put("name", "Стенд стыков").put("knowledge_v2", true), пров(),
        )
        узел7Id = store.create(
            "C-0007", "component", область(), null,
            mapper.createObjectNode().put("name", "Приёмный модуль").put("kind", "element"), пров(),
        ).id
        узел8Id = store.create(
            "C-0008", "component", область(), null,
            mapper.createObjectNode().put("name", "Шлюз").put("kind", "element"), пров(),
        ).id
        store.create(
            "FU-1", "function", область(), null,
            mapper.createObjectNode().put("name", "передать сообщение").put("layer", "SA")
                .set("allocated_to", mapper.createArrayNode().add(узел7Id)), пров(),
        )
        store.create(
            "FU-2", "function", область(), null,
            mapper.createObjectNode().put("name", "принять сообщение").put("layer", "SA")
                .set("allocated_to", mapper.createArrayNode().add(узел8Id)), пров(),
        )
        store.create(
            "EX-1", "exchange", область(), null,
            mapper.createObjectNode().put("name", "сообщение терминала")
                .put("source_function", "FU-1").put("target", "FU-2").put("payload", "пакет данных"), пров(),
        )
    }

    private companion object {
        const val ПРОЕКТ = "PJ-9671"
    }
}

/** Канал-подмена: сеть не нужна, ответ задаёт тест, промпты видны. */
private class КаналСтыков : Transport {
    var ответ: String = """{"interfaces":[]}"""

    override fun ask(prompt: String, model: String?, maxTokens: Int?, schema: JsonNode?): Answer =
        Answer(text = ответ, model = "модель-теста", tokensIn = 60, tokensOut = 90)
}
