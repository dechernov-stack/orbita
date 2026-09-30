// Приём функций и обменов одним пакетом (§2.2b шипа 6, CODE-30-09 §1).
//
// Здесь проверяется НЕ деривер (у него свой тест) и НЕ сверка, а СКВОЗНОЙ
// приём настоящей сверкой: модель отвечает функциями и обменами за один вызов,
// обмен ссылается на функцию ЛОКАЛЬНЫМ КЛЮЧОМ; приём одним нажатием заводит
// функции первыми, затем разрешает ключи обменов в коды заведённых функций.
// Конец не принят → обмен ОТЛОЖЕН, а не отказ.
//
// Сверка — настоящая (детерминированная ступень): важно, что обмен реально
// заводится с кодами функций в полях, а шаг сценария получает свою функцию.
// Канал модели подменён: ответ задаёт тест, живого вызова нет.
package orbita.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.ai.api.AiFactory
import orbita.ai.api.Answer
import orbita.ai.api.Transport
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

class FunctionExchangeAcceptTest {

    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val знания = KnowledgeFactory.intake(store, links, mapper)
    private val транспорт = КаналФункцийИОбменов()
    private val служба = AiFactory.service(store, транспорт, mapper)
    private val сверка = KnowledgeFactory.reconcile(store, links, mapper, intake = знания)
    private val deriveFn = AiFactory.deriveFunctions(store, знания, служба, mapper)
    private val маршруты = SynthesisRoutes(
        store, AiFactory.synthesisJobs(store, знания, служба, mapper), сверка, mapper, links,
    )
    private val п = mapOf("project" to ПРОЕКТ)

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        схема()
    }

    @Test
    fun `пакет из трёх функций и двух обменов — примут все, обмены с кодами функций`() {
        транспорт.ответ = ОТВЕТ
        val прогон = deriveFn.derive(ПРОЕКТ, "инженер")
        val строки = строкиДифа(прогон.id)
        assertEquals(5, строки.size, "три функции и два обмена: $строки")

        val ответ = маршруты.handle(
            "POST", "/v2/synthesis/runs/${прогон.id}/accept", п,
            тело(строки.values.toList()),
        )!!

        assertEquals(201, ответ.code, ответ.body.toString())
        assertEquals(5, ответ.body.path("accepted").asInt(), "приняты все пять: ${ответ.body}")
        val созданные = ответ.body.path("created").map { it.asText() }
        val функции = созданные.filter { store.byCode(область(), it)?.kind == "function" }
        val обмены = созданные.filter { store.byCode(область(), it)?.kind == "exchange" }
        assertEquals(3, функции.size, "три функции заведены: $созданные")
        assertEquals(2, обмены.size, "два обмена заведены: $созданные")

        // Концы обмена — КОДЫ функций, а не локальные ключи (разрешились приёмом).
        обмены.forEach { код ->
            val обмен = store.byCode(область(), код)!!.doc
            assertTrue(
                обмен.path("source_function").asText().startsWith("FU"),
                "источник обмена — код функции, не ключ: ${обмен.path("source_function").asText()}",
            )
            assertTrue(
                обмен.path("target").asText().startsWith("FU"),
                "получатель обмена — код функции: ${обмен.path("target").asText()}",
            )
        }

        // §2 CODE-30-09: шаг сценария получил свою функцию, проза осталась.
        val цепочка = store.byCode(область(), "FC-1")!!.doc
        val сФункцией = цепочка.path("steps").count { it.path("function").asText().startsWith("FU") }
        assertEquals(3, сФункцией, "все три шага получили функции: ${цепочка.path("steps")}")
        assertEquals(
            "терминал передаёт сообщение",
            цепочка.path("steps").first().path("what").asText(),
            "проза шага осталась на месте",
        )
    }

    @Test
    fun `отклонена функция — её обмен отложен со словами, второй принят`() {
        транспорт.ответ = ОТВЕТ
        val прогон = deriveFn.derive(ПРОЕКТ, "инженер")
        val строки = строкиДифа(прогон.id)
        // Отклоняем f2 (её не выбираем): x1 (…→f2) обязан отложиться, x2 (…→f3) — приняться.
        val f2 = кодПоИмени(прогон.id, "принять сообщение")
        val выбраны = строки.values.filterNot { it == f2 }
        assertEquals(4, выбраны.size, "выбраны все, кроме одной функции")

        val ответ = маршруты.handle(
            "POST", "/v2/synthesis/runs/${прогон.id}/accept", п, тело(выбраны),
        )!!

        assertEquals(201, ответ.code, ответ.body.toString())
        // Приняты две функции (f1, f3) и один обмен (x2) — три.
        assertEquals(3, ответ.body.path("accepted").asInt(), "две функции и обмен-получатель-f3: ${ответ.body}")
        val ждут = ответ.body.path("pending")
        val отложенные = ждут.filter { it.path("verdict").asText() == "отложено" }
        assertEquals(1, отложенные.size, "ровно один обмен отложен: $ждут")
        val почему = отложенные.single().path("why").asText()
        assertTrue("не принята" in почему, "сказано, что конец не принят: $почему")
        assertTrue("f2" in почему, "назван неразрешённый ключ: $почему")

        // Второй обмен (…→f3) реально заведён.
        val обмены = ответ.body.path("created").map { it.asText() }
            .filter { store.byCode(область(), it)?.kind == "exchange" }
        assertEquals(1, обмены.size, "обмен, оба конца которого приняты, заведён: ${ответ.body.path("created")}")
    }

    // --- срез стенда ---------------------------------------------------------

    /** Диф прогона строками {ключ-опознания → код карточки}; ключ — concept+имя/цель. */
    private fun строкиДифа(прогон: String): Map<String, String> {
        val диф = маршруты.handle("GET", "/v2/synthesis/runs/$прогон", п, null)!!.body.path("diff").path("new")
        val карта = linkedMapOf<String, String>()
        диф.forEachIndexed { i, узел ->
            карта["$i:${узел.path("concept").asText()}"] = узел.path("proposal").asText()
        }
        return карта
    }

    /** Код карточки функции по её имени (payload.name из дифа). */
    private fun кодПоИмени(прогон: String, имя: String): String {
        val диф = маршруты.handle("GET", "/v2/synthesis/runs/$прогон", п, null)!!.body.path("diff").path("new")
        return диф.first { it.path("concept").asText() == "function" && it.path("payload").path("name").asText() == имя }
            .path("proposal").asText()
    }

    private fun тело(коды: List<String>): String {
        val массив = коды.joinToString(",") { "\"$it\"" }
        return """{"chosen":[$массив],"author":"инженер","reason":"сцена 9"}"""
    }

    private fun область() = Area.Project(ПРОЕКТ)
    private fun пров() = Provenance(Channel.MANUAL, "инженер")

    /** Проект + три узла + сценарий FC-1, три шага которого несут эти узлы участниками. */
    private fun схема() {
        store.create(
            ПРОЕКТ, "project", область(), "1",
            mapper.createObjectNode().put("name", "Стенд функций и обменов").put("knowledge_v2", true), пров(),
        )
        listOf("C-0007" to "Приёмный модуль", "C-0008" to "Шлюз", "C-0009" to "ЦУП-адаптер").forEach { (код, имя) ->
            store.create(
                код, "component", область(), null,
                mapper.createObjectNode().put("name", имя).put("kind", "element"), пров(),
            )
        }
        val ц = mapper.createObjectNode().put("name", "Доставка сообщения")
        val шаги = ц.putArray("steps")
        шаги.addObject().put("what", "терминал передаёт сообщение").put("actor", "терминал").put("component", "C-0007")
        шаги.addObject().put("what", "шлюз принимает сообщение").put("actor", "шлюз").put("component", "C-0008")
        шаги.addObject().put("what", "шлюз пересылает в ЦУП").put("actor", "шлюз").put("component", "C-0009")
        store.create("FC-1", "functional_chain", область(), "9", ц, пров())
    }

    private companion object {
        const val ПРОЕКТ = "PJ-9640"

        /** Три функции (f1·f2·f3) и два обмена: x1 (f1→f2) и x2 (f1→f3). */
        val ОТВЕТ = """
            {"functions":[
              {"id":"f1","chain":"FC-1","step":"терминал передаёт сообщение","node":"C-0007","name":"передать сообщение"},
              {"id":"f2","chain":"FC-1","step":"шлюз принимает сообщение","node":"C-0008","name":"принять сообщение"},
              {"id":"f3","chain":"FC-1","step":"шлюз пересылает в ЦУП","node":"C-0009","name":"переслать в ЦУП"}
            ],
            "exchanges":[
              {"id":"x1","source":"f1","target":"f2","name":"сообщение терминала","payload":"пакет данных"},
              {"id":"x2","source":"f1","target":"f3","name":"копия в ЦУП","payload":"копия пакета"}
            ]}
        """.trimIndent()
    }
}

/** Канал-подмена: сеть не нужна, ответ задаётся тестом. */
private class КаналФункцийИОбменов : Transport {
    var ответ: String = """{"functions":[]}"""

    override fun ask(prompt: String, model: String?, maxTokens: Int?, schema: JsonNode?): Answer =
        Answer(text = ответ, model = "модель-теста", tokensIn = 60, tokensOut = 120)
}
