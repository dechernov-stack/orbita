// Сквозной приём деривации требований вниз реальной сверкой (§2.1-C2 хвост,
// CODE-30-09 §3): предложение деривации → приём → требование ЗАВЕДЕНО, связь
// `derives_from` на родителя с обоснованием, `source` = родитель.
//
// До сих пор путь деривации был проверен только на ОТКАЗЕ (бюджет). Здесь —
// приём настоящей сверкой: важно, что сущность создаётся, связь ставится и
// источник записи указывает на родителя, из которого требование выведено.
// Канал модели подменён: ответ задаёт тест.
package orbita.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
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

class RequirementDeriveAcceptTest {

    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val знания = KnowledgeFactory.intake(store, links, mapper)
    private val транспорт = КаналДеривации()
    private val служба = AiFactory.service(store, транспорт, mapper)
    private val сверка = KnowledgeFactory.reconcile(store, links, mapper, intake = знания)
    private val маршруты = SynthesisRoutes(
        store, AiFactory.synthesisJobs(store, знания, служба, mapper), сверка, mapper, links,
        units = { единица -> единица == "кг" },
    )
    private val п = mapOf("project" to ПРОЕКТ)

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        схема()
    }

    @Test
    fun `предложение деривации принято реальной сверкой — требование, derives_from, source=родитель`() {
        транспорт.ответ = """{"requirements":[
            {"id":"r1","derives_from":"RQ-CEIL","title":"Масса приёмного модуля",
             "statement":"Приёмный модуль не тяжелее 40 кг.",
             "measure":{"value":"≤ 40","unit":"кг"},"category":"performance","why":"доля массы"}
        ]}"""
        val прогон = AiFactory.deriveRequirements(store, знания, служба, mapper, links).derive(ПРОЕКТ, "C-0007", "инженер")
        val карточка = маршруты.handle("GET", "/v2/synthesis/runs/${прогон.id}", п, null)!!
            .body.path("diff").path("new").first().path("proposal").asText()

        val ответ = маршруты.handle(
            "POST", "/v2/synthesis/runs/${прогон.id}/accept", п,
            """{"chosen":["$карточка"],"author":"инженер","reason":"сцена 8"}""",
        )!!

        assertEquals(201, ответ.code, ответ.body.toString())
        assertEquals(1, ответ.body.path("accepted").asInt(), "требование заведено: ${ответ.body}")
        // Требование создано.
        val кодРебёнка = ответ.body.path("created").map { it.asText() }
            .single { store.byCode(область(), it)?.kind == "requirement" }
        val ребёнок = store.byCode(область(), кодРебёнка)!!
        assertEquals("requirement", ребёнок.kind)

        // Связь derives_from на родителя — в ответе и в реестре, с обоснованием.
        assertTrue(
            ответ.body.path("links").any { it.asText().startsWith("derives_from") && "RQ-CEIL" in it.asText() },
            "связь derives_from названа в ответе: ${ответ.body.path("links")}",
        )
        val родитель = store.byCode(область(), "RQ-CEIL")!!
        val связь = links.from(ребёнок.id).single { it.type == "derives_from" }
        assertEquals(родитель.id, связь.to, "derives_from ведёт на родителя")
        assertTrue(связь.rationale?.isNotBlank() == true, "у связи derives_from есть обоснование: ${связь.rationale}")

        // Источник записи — родитель (source из основания, model_basis_rule).
        val источник = ребёнок.doc.path("source")
        assertTrue(источник.isArray && источник.size() > 0, "источник записан: $источник")
        assertEquals("requirement", источник.first().path("kind").asText(), "источник — вид requirement: $источник")
        assertEquals("RQ-CEIL", источник.first().path("ref").asText(), "источник ссылается на родителя: $источник")
    }

    // --- срез стенда ---------------------------------------------------------

    private fun область() = Area.Project(ПРОЕКТ)
    private fun пров() = Provenance(Channel.MANUAL, "инженер")

    /** Проект + КА (system) + узел-элемент под ним + родитель-потолок RQ-CEIL (≤100 кг). Без бюджета. */
    private fun схема() {
        store.create(
            ПРОЕКТ, "project", область(), "1",
            mapper.createObjectNode().put("name", "Стенд деривации").put("knowledge_v2", true), пров(),
        )
        единица("кг", "mass")
        val корень = store.create(
            "C-0001", "component", область(), null,
            mapper.createObjectNode().put("name", "КА").put("kind", "system"), пров(),
        ).id
        store.create(
            "C-0007", "component", область(), null,
            mapper.createObjectNode().put("name", "Приёмный модуль").put("kind", "element").put("parent", корень), пров(),
        )
        store.create("RQ-CEIL", "requirement", область(), "8", требованиеДок("system", корень, "Масса КА не более 100 кг.", "≤ 100", "кг"), пров())
    }

    private fun единица(символ: String, размерность: String) {
        store.create(
            "U-$символ", "unit", Area.Library, null,
            mapper.createObjectNode().put("symbol", символ).put("name", символ).put("dimension", размерность)
                .put("factor", 1.0).put("canonical", true).put("conversion_type", "linear"),
            пров(),
        )
    }

    private fun требованиеДок(уровень: String, носитель: String, формулировка: String, значение: String, единица: String): ObjectNode {
        val d = mapper.createObjectNode().put("level", уровень).put("title", формулировка.take(60))
            .put("statement", формулировка).put("category", "performance").put("carrier", носитель).put("ears_pattern", "ubiquitous")
        d.putObject("measure").put("value", значение).put("unit", единица)
        return d
    }

    private companion object {
        const val ПРОЕКТ = "PJ-9660"
    }
}

/** Канал-подмена: ответ деривации задаёт тест. */
private class КаналДеривации : Transport {
    var ответ: String = """{"requirements":[]}"""

    override fun ask(prompt: String, model: String?, maxTokens: Int?, schema: JsonNode?): Answer =
        Answer(text = ответ, model = "модель-теста", tokensIn = 50, tokensOut = 100)
}
