// Сквозной приём рисков из разрывов фазы реальной сверкой (§2.5 шипа 6):
// маршрут /v2/risks/derive-gaps кладёт предложения ручным прогоном, приём той
// же сверкой через /v2/synthesis/runs/{run}/accept ЗАВОДИТ риск (open): формулировка,
// cec {condition, event, consequence}, категория, срок-точка id вехи, ссылки
// на узел. Поля человека (вероятность, влияние, стратегия, меры, владелец —
// их называет сцена 11) сверка на приёме НЕ требует: правок нет. Дубль
// открытого риска — augment сверки, не второй риск. Без помощника — 501 с
// указанием, что делать. Канал модели подменён.
package orbita.api

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

class RiskDeriveAcceptTest {

    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val знания = KnowledgeFactory.intake(store, links, mapper)
    private val транспорт = КаналРазрывов()
    private val служба = AiFactory.service(store, транспорт, mapper)
    private val сверка = KnowledgeFactory.reconcile(store, links, mapper, intake = знания)
    private val синтезМаршруты = SynthesisRoutes(
        store, AiFactory.synthesisJobs(store, знания, служба, mapper), сверка, mapper, links,
        units = { единица -> единица == "кг" },
    )
    private val п = mapOf("project" to ПРОЕКТ)

    private lateinit var узелId: String
    private lateinit var точкаId: String

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        стенд()
    }

    @Test
    fun `маршрут без помощника — 501, что делать`() {
        val маршруты = KnowledgeRoutes(
            знания, AiFactory.atomize(store, знания, служба, mapper), служба, mapper, store = store,
        )

        val ответ = маршруты.handle("POST", "/v2/risks/derive-gaps", п, """{}""")!!

        assertEquals(501, ответ.code, ответ.body.toString())
        assertTrue(ответ.body.path("what_to_do").asText("").isNotBlank(), "наказ в ответе: ${ответ.body}")
    }

    @Test
    fun `риск из разрыва TRL через маршрут — приём той же сверкой, риск заведён open`() {
        транспорт.ответ = """
            {"risks":[
              {"id":"g1","statement":"Приёмный модуль не созреет к SDR",
               "condition":"TRL модуля — 4 при требуемом 6","event":"SDR наступает без созревания",
               "consequence":"модуль не проходит SDR"}
            ]}
        """.trimIndent()
        val маршруты = KnowledgeRoutes(
            знания, AiFactory.atomize(store, знания, служба, mapper), служба, mapper, store = store,
            deriveRisks = AiFactory.deriveRisks(store, знания, links, служба, mapper),
        )

        val вывод = маршруты.handle("POST", "/v2/risks/derive-gaps", п, """{"author":"инженер"}""")!!
        assertEquals(201, вывод.code, вывод.body.toString())
        assertTrue(вывод.body.path("proposals").asInt() > 0, "предложения в прогоне: ${вывод.body}")
        val кодПрогона = вывод.body.path("run").asText()

        val прогон = синтезМаршруты.handle("GET", "/v2/synthesis/runs/$кодПрогона", п, null)!!
        val карточки = прогон.body.path("diff").path("new").map { it.path("proposal").asText() }
        // риск из разрыва TRL: основание-разрыв с гранью TRL в карточке.
        val карточка = карточки.first { карточкаТrl(прогон.body, it) }

        // Правок нет: поля человека (вероятность, влияние, стратегия, меры,
        // владелец — истина онтологии риска) сверка на приёме не требует.
        val приём = синтезМаршруты.handle(
            "POST", "/v2/synthesis/runs/$кодПрогона/accept", п,
            """{"chosen":["$карточка"],"author":"инженер","reason":"разрыв TRL из стенда"}""",
        )!!
        assertEquals(201, приём.code, приём.body.toString())
        assertEquals(1, приём.body.path("accepted").asInt(), "риск заведён: ${приём.body}")

        val код = приём.body.path("created").map { it.asText() }
            .single { store.byCode(область(), it)?.kind == "risk" }
        val риск = store.byCode(область(), код)!!
        assertEquals("open", риск.status, "заведённый риск открыт (модель состояний вида)")
        assertEquals("Приёмный модуль не созреет к SDR", риск.doc.path("statement").asText())
        assertEquals("technical", риск.doc.path("category").asText())
        assertEquals("TRL модуля — 4 при требуемом 6", риск.doc.path("cec").path("condition").asText())
        assertEquals("модуль не проходит SDR", риск.doc.path("cec").path("consequence").asText())
        assertEquals(точкаId, риск.doc.path("due_point").asText(), "срок-точка — id вехи")
        assertEquals("C-0007", риск.doc.path("refs").first().asText(), "ссылка на узел разрыва")
    }

    @Test
    fun `дубль открытого риска — сверка даёт augment, второго риска нет`() {
        транспорт.ответ = """
            {"risks":[{"id":"g1","statement":"Приёмный модуль не созреет к SDR"}]}
        """.trimIndent()
        val маршруты = KnowledgeRoutes(
            знания, AiFactory.atomize(store, знания, служба, mapper), служба, mapper, store = store,
            deriveRisks = AiFactory.deriveRisks(store, знания, links, служба, mapper),
        )
        маршруты.handle("POST", "/v2/risks/derive-gaps", п, """{"author":"инженер"}""")!!.also {
            assertEquals(201, it.code, it.body.toString())
        }
        // Первый прогон принимаем: дубль сверяется с ЗАВЕДЁННЫМ риском.
        val первый = маршруты.handle("POST", "/v2/risks/derive-gaps", п, """{"author":"инженер"}""")!!
        assertEquals(201, первый.code, первый.body.toString())
        val кодПрогона = первый.body.path("run").asText()
        val прогон = синтезМаршруты.handle("GET", "/v2/synthesis/runs/$кодПрогона", п, null)!!
        val карточка = прогон.body.path("diff").path("new").map { it.path("proposal").asText() }
            .first { карточкаТrl(прогон.body, it) }
        val приём = синтезМаршруты.handle(
            "POST", "/v2/synthesis/runs/$кодПрогона/accept", п,
            """{"chosen":["$карточка"],"author":"инженер","reason":"разрыв TRL из стенда"}""",
        )!!
        assertEquals(201, приём.code, приём.body.toString())
        assertEquals(1, приём.body.path("accepted").asInt(), "первый прогон завёл риск: ${приём.body}")

        // Тот же стенд, тот же ответ — второй прогон по тому же разрыву.
        транспорт.ответ = """
            {"risks":[{"id":"g1","statement":"Приёмный модуль не созреет к SDR"}]}
        """.trimIndent()
        val второй = маршруты.handle("POST", "/v2/risks/derive-gaps", п, """{"author":"инженер"}""")!!
        assertEquals(201, второй.code, второй.body.toString())
        val кодВторого = второй.body.path("run").asText()

        val повтор = синтезМаршруты.handle("GET", "/v2/synthesis/runs/$кодВторого", п, null)!!
        val карточкаДубля = повтор.body.path("diff").path("new").map { it.path("proposal").asText() }
            .first { карточкаТrl(повтор.body, it) }
        // Приём дубля: сверка по ключу идентичности (statement_core) узнаёт
        // заведённый риск и выносит augment — автоматика акцепта узнанное не
        // заводит, строка ждёт решения человека (слить · уточнить · оспорить).
        val приёмДубля = синтезМаршруты.handle(
            "POST", "/v2/synthesis/runs/$кодВторого/accept", п,
            """{"chosen":["$карточкаДубля"],"author":"инженер","reason":"тот же разрыв ещё раз"}""",
        )!!
        assertEquals(201, приёмДубля.code, приёмДубля.body.toString())
        assertEquals(0, приёмДубля.body.path("accepted").asInt(), "второго риска нет: ${приёмДубля.body}")
        assertEquals(
            1, приёмДубля.body.path("pending").size(),
            "дубль ждёт решения человека: ${приёмДубля.body.path("pending")}",
        )
        assertEquals("дополнить", приёмДубля.body.path("pending").first().path("verdict").asText())
        assertEquals(
            1, store.list(область(), "risk").size,
            "в проекте по-прежнему один риск: ${store.list(область(), "risk").map { it.code }}",
        )
    }

    // --- срез стенда ---------------------------------------------------------

    private fun область() = Area.Project(ПРОЕКТ)
    private fun пров() = Provenance(Channel.MANUAL, "инженер")

    /** Карточка предложения — риск из разрыва TRL (грань основания TRL). */
    private fun карточкаТrl(тело: com.fasterxml.jackson.databind.JsonNode, карточка: String): Boolean =
        тело.path("diff").path("new").firstOrNull { it.path("proposal").asText() == карточка }
            ?.path("basis")?.any { "TRL" in it.path("facet").asText("") } == true

    /** Проект + узел + технология с разрывом TRL + веха SDR. */
    private fun стенд() {
        store.create(
            ПРОЕКТ, "project", область(), null,
            mapper.createObjectNode().put("name", "Стенд рисков").put("knowledge_v2", true), пров(),
        )
        узелId = store.create(
            "C-0007", "component", область(), null,
            mapper.createObjectNode().put("name", "Приёмный модуль").put("kind", "element"), пров(),
        ).id
        store.create(
            "T-1", "technology", область(), null,
            mapper.createObjectNode().put("name", "Приём тракта")
                .put("component", узелId)
                .put("trl_current", 4).put("trl_required", 6)
                .put("required_by", "GT-SDR"),
            пров(),
        )
        точкаId = store.create(
            "GT-SDR", "gate", область(), null,
            mapper.createObjectNode().put("phase", "Phase A").put("key", "SDR")
                .put("title", "SDR").put("kind", "phase").put("planned_date", "2099-01-01")
                .set("criteria", mapper.createArrayNode()),
            пров(),
        ).id
    }

    private companion object {
        const val ПРОЕКТ = "PJ-9673"
    }
}

/** Канал-подмена: сеть не нужна, ответ задаёт тест, промпты видны. */
private class КаналРазрывов : Transport {
    var ответ: String = """{"risks":[]}"""

    override fun ask(prompt: String, model: String?, maxTokens: Int?, schema: com.fasterxml.jackson.databind.JsonNode?): Answer =
        Answer(text = ответ, model = "модель-теста", tokensIn = 80, tokensOut = 120)
}
