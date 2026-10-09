// Сквозной приём вывода анкеты узла из паспорта изделия реальной сверкой
// (§2.4 шипа 6): маршрут /v2/parameters/derive-datasheet кладёт предложения
// ручным прогоном, приём той же сверкой через /v2/synthesis/runs/{run}/accept
// ЗАВОДИТ величину анкеты (цель id, мера числом, источник — документ с якорем,
// происхождение datasheet). Класс зрелости, обязательность к точке и
// неопределённость (истина требует их у вида parameter) сверка спрашивает у
// человека на приёме — правкой в форме, как ручная величина. Без помощника —
// 501 с указанием, что делать. Канал модели подменён.
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

class ParameterDeriveAcceptTest {

    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val знания = KnowledgeFactory.intake(store, links, mapper)
    private val транспорт = КаналАнкеты()
    private val служба = AiFactory.service(store, транспорт, mapper)
    private val сверка = KnowledgeFactory.reconcile(store, links, mapper, intake = знания)
    private val синтезМаршруты = SynthesisRoutes(
        store, AiFactory.synthesisJobs(store, знания, служба, mapper), сверка, mapper, links,
        units = { единица -> единица == "кг" || единица == "Вт" },
    )
    private val п = mapOf("project" to ПРОЕКТ)
    private val полкаКомпонентов = mapper.readTree(TestDbV2.repoRoot.resolve("docs/tz/v2/ПОЛКА-ШАБЛОНЫ-КОМПОНЕНТОВ.json").toFile())

    private var узелId: String = ""
    private var точкаId: String = ""

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

        val ответ = маршруты.handle("POST", "/v2/parameters/derive-datasheet", п, """{"node":"C-0007"}""")!!

        assertEquals(501, ответ.code, ответ.body.toString())
        assertTrue(ответ.body.path("what_to_do").asText("").isNotBlank(), "наказ в ответе: ${ответ.body}")
    }

    @Test
    fun `величина из паспорта через маршрут — приём той же сверкой, параметр заведён`() {
        транспорт.ответ = """
            {"parameters":[
              {"id":"p1","key":"mass_dry","value":"120","unit":"кг",
               "quote":"Масса сухая 120 кг","anchor":"s1#1","doc":"SD-0001"}
            ]}
        """.trimIndent()
        val маршруты = KnowledgeRoutes(
            знания, AiFactory.atomize(store, знания, служба, mapper), служба, mapper, store = store,
            deriveQp = AiFactory.deriveParameters(store, знания, служба, mapper),
        )

        val вывод = маршруты.handle(
            "POST", "/v2/parameters/derive-datasheet", п, """{"node":"C-0007","author":"инженер"}""",
        )!!
        assertEquals(201, вывод.code, вывод.body.toString())
        assertEquals(1, вывод.body.path("proposals").asInt(), "предложение в прогоне: ${вывод.body}")
        val кодПрогона = вывод.body.path("run").asText()

        val прогон = синтезМаршруты.handle("GET", "/v2/synthesis/runs/$кодПрогона", п, null)!!
        val карточка = прогон.body.path("diff").path("new").first().path("proposal").asText()

        // Класс зрелости, обязательность к точке и неопределённость (их требует
        // вид parameter) — правкой инженера на приёме, как спрашивает сверка.
        val приём = синтезМаршруты.handle(
            "POST", "/v2/synthesis/runs/$кодПрогона/accept", п,
            """{"chosen":["$карточка"],"author":"инженер","reason":"паспорт SD-0001",
                "edits":{"$карточка":{"required_to":"$точкаId","maturity_class":"off_the_shelf","uncertainty":10}}}""",
        )!!
        assertEquals(201, приём.code, приём.body.toString())
        assertEquals(1, приём.body.path("accepted").asInt(), "величина заведена: ${приём.body}")

        val код = приём.body.path("created").map { it.asText() }
            .single { store.byCode(область(), it)?.kind == "parameter" }
        val величина = store.byCode(область(), код)!!
        assertEquals(узелId, величина.doc.path("target").asText(), "цель — узел (id)")
        assertEquals("mass_dry", величина.doc.path("key").asText())
        assertEquals(120.0, величина.doc.path("measure").path("value").asDouble(), "мера — число из цитаты")
        assertEquals("кг", величина.doc.path("measure").path("unit").asText())
        assertEquals("datasheet", величина.doc.path("origin").asText(), "происхождение — паспорт изделия")
        assertEquals("s1#1", величина.doc.path("source").path("anchor").asText(), "источник — якорь блока канона")
        assertEquals("SD-0001", величина.doc.path("source").path("material").asText())
        assertEquals(точкаId, величина.doc.path("required_to").asText(), "обязательность к точке — правкой при приёме")
        assertEquals("off_the_shelf", величина.doc.path("maturity_class").asText())
        assertEquals(10, величина.doc.path("uncertainty").asInt())
    }

    // --- срез стенда ---------------------------------------------------------

    private fun область() = Area.Project(ПРОЕКТ)
    private fun пров() = Provenance(Channel.MANUAL, "инженер")

    /** Проект + узел с поставщиком полем + сторона + паспорт + точка + полка + единицы. */
    private fun стенд() {
        store.create(
            ПРОЕКТ, "project", область(), null,
            mapper.createObjectNode().put("name", "Стенд анкеты").put("knowledge_v2", true), пров(),
        )
        store.create(
            "SH-1", "stakeholder", область(), null,
            mapper.createObjectNode().put("name", "Завод «Орбита»").put("role", "supplier"), пров(),
        )
        узелId = store.create(
            "C-0007", "component", область(), null,
            mapper.createObjectNode().put("name", "Приёмный модуль").put("kind", "element")
                .put("template_ref", "TC-SC").put("supplier", "SH-1"),
            пров(),
        ).id
        val поставщик = store.byCode(область(), "SH-1")!!
        store.create(
            "SD-0001", "material", область(), null,
            mapper.createObjectNode().put("name", "Паспорт изделия").put("role", "supplier")
                .put("origin", поставщик.id)
                .put("text", "Паспорт изделия\nМасса сухая 120 кг.\nСредняя мощность 60 Вт."),
            пров(),
        )
        точкаId = store.create(
            "GT-SDR", "gate", область(), null,
            mapper.createObjectNode().put("phase", "Phase A").put("key", "SDR")
                .put("title", "SDR пройден").put("kind", "phase").put("planned_date", "2026-11-01")
                .set("criteria", mapper.createArrayNode()),
            пров(),
        ).id
        store.create("CTS-9001", "component_template_shelf", Area.Library, null, полкаКомпонентов, пров())
        единица("кг", "масса")
        единица("Вт", "мощность")
    }

    private fun единица(символ: String, размерность: String) {
        store.create(
            "U-$символ", "unit", Area.Library, null,
            mapper.createObjectNode().put("symbol", символ).put("name", символ).put("dimension", размерность)
                .put("factor", 1.0).put("canonical", true).put("conversion_type", "linear"),
            пров(),
        )
    }

    private companion object {
        const val ПРОЕКТ = "PJ-9672"
    }
}

/** Канал-подмена: сеть не нужна, ответ задаёт тест, промпты видны. */
private class КаналАнкеты : Transport {
    var ответ: String = """{"parameters":[]}"""

    override fun ask(prompt: String, model: String?, maxTokens: Int?, schema: JsonNode?): Answer =
        Answer(text = ответ, model = "модель-теста", tokensIn = 60, tokensOut = 90)
}
