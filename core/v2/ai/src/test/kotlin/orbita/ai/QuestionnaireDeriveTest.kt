// Анкета узла из паспорта изделия (§2.4 шипа 6) — помощник Phase A на каркасе
// 2.1–2.3.
//
// Проверяются машинные ворота: ключ — из анкеты узла (полка шаблонов);
// документ — из среза (сторона-поставщик полем узла, диф 27.09-b; документ
// роли supplier с происхождением-поставщиком); цитата — дословно в каноне
// документа, якорь — из якорей канона; единица — из справочника; число без
// цитаты — отказ (сторож чисел, истина онтологии понятия parameter). Срез
// пуст — прогон пустой, модель не зовём. Канал к модели подменён.
package orbita.ai

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.ai.api.AiFactory
import orbita.ai.api.Answer
import orbita.ai.api.Basis
import orbita.ai.api.Transport
import orbita.ai.api.Verdict
import orbita.ai.internal.QuestionnaireDeriver
import orbita.ai.internal.Synthesizer
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Provenance
import orbita.knowledge.api.KnowledgeFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuestionnaireDeriveTest {

    private val mapper = ObjectMapper()
    private val store = ПамятьПоля()
    private val знания = KnowledgeFactory.intake(store, null, mapper)
    private val канал = КаналАнкеты()
    private val служба = AiFactory.service(store, канал, mapper)
    private val деривер = QuestionnaireDeriver(store, знания, служба, mapper)
    private val синтез = Synthesizer(store, знания, служба, mapper)

    @Test
    fun `паспорт поставщика — величина анкеты с цитатой и якорем, основание-документ в ручной прогон`() {
        стенд()
        канал.ответ = """
            {"parameters":[
              {"id":"p1","key":"mass_dry","value":"120","unit":"кг",
               "quote":"Масса сухая 120 кг","anchor":"s1#1","doc":"SD-0001"}
            ]}
        """.trimIndent()

        val прогон = деривер.deriveInto(ПРОЕКТ, "C-0007", АВТОР, синтез)

        assertEquals("manual", прогон.trigger, "прогон помощника — ручной")
        val предложение = прогон.diff.new.single()
        assertEquals("parameter", предложение.concept)
        assertEquals(Verdict.NEW, предложение.verdict, "годная здесь «новая»: вердикт выносит сверка при приёме")
        assertEquals(узелId, предложение.payload["target"], "сторона target — узел (id, как ручная форма)")
        assertEquals("mass_dry", предложение.payload["key"])
        val мера = mapper.readTree(предложение.payload["measure"]!!)
        assertEquals(120.0, мера.path("value").asDouble(), "число из цитаты — в мере")
        assertEquals("кг", мера.path("unit").asText())
        assertEquals("datasheet", предложение.payload["origin"], "происхождение — паспорт изделия")
        val источник = mapper.readTree(предложение.payload["source"]!!)
        assertEquals("SD-0001", источник.path("material").asText())
        assertEquals("s1#1", источник.path("anchor").asText())
        val основание = предложение.basis.single()
        assertTrue(основание is Basis.Fact && основание.material == "SD-0001" && основание.anchor == "s1#1",
            "основание величины — след-факт паспорта с якорем: $основание")

        // След-факт лежит рядом с прогоном: величина с цитатой, якорем и ключом анкеты.
        val факт = store.list(Area.Project(ПРОЕКТ), "fact").single()
        assertEquals("quantity", факт.doc.path("kind").asText())
        assertEquals("mass_dry", факт.doc.path("param_key").asText())
        assertEquals("Масса сухая 120 кг", факт.doc.path("quote").asText())
        assertEquals("s1#1", факт.doc.path("anchor").asText())
        assertEquals("SD-0001", факт.doc.path("material").asText())
        assertEquals(120.0, факт.doc.path("value").path("value").asDouble())

        val карточка = store.list(Area.Project(ПРОЕКТ), "proposal").single()
        assertEquals("parameter_from_datasheet", карточка.doc.path("package_kind").asText())
    }

    @Test
    fun `ворота — ключ вне анкеты узла — отказ поимённо`() {
        стенд()
        канал.ответ = """
            {"parameters":[
              {"id":"p1","key":"laser","value":"5","unit":"Вт",
               "quote":"Средняя мощность 60 Вт","anchor":"s1#2","doc":"SD-0001"}
            ]}
        """.trimIndent()

        val прогон = деривер.deriveInto(ПРОЕКТ, "C-0007", АВТОР, синтез)

        assertTrue(прогон.diff.new.isEmpty(), "величина с ключом вне анкеты в прогон не идёт")
        assertTrue("laser" in (прогон.note ?: ""), "отказ называет ключ: ${прогон.note}")
    }

    @Test
    fun `ворота — цитаты нет в каноне документа — отказ`() {
        стенд()
        канал.ответ = """
            {"parameters":[
              {"id":"p1","key":"mass_dry","value":"120","unit":"кг",
               "quote":"такого в документе нет","anchor":"s1#1","doc":"SD-0001"}
            ]}
        """.trimIndent()

        val прогон = деривер.deriveInto(ПРОЕКТ, "C-0007", АВТОР, синтез)

        assertTrue(прогон.diff.new.isEmpty(), "придуманная цитата отбита")
        assertTrue("такого в документе нет" in (прогон.note ?: ""), "отказ называет цитату: ${прогон.note}")
    }

    @Test
    fun `ворота — единица вне справочника — отказ`() {
        стенд()
        канал.ответ = """
            {"parameters":[
              {"id":"p1","key":"mass_dry","value":"120","unit":"попугаи",
               "quote":"Масса сухая 120 кг","anchor":"s1#1","doc":"SD-0001"}
            ]}
        """.trimIndent()

        val прогон = деривер.deriveInto(ПРОЕКТ, "C-0007", АВТОР, синтез)

        assertTrue(прогон.diff.new.isEmpty(), "величина с единицей вне справочника отбита")
        assertTrue("попугаи" in (прогон.note ?: ""), "отказ называет единицу: ${прогон.note}")
    }

    @Test
    fun `ворота — число без цитаты — отказ сторожа чисел`() {
        стенд()
        канал.ответ = """
            {"parameters":[
              {"id":"p1","key":"mass_dry","value":"120","unit":"кг",
               "quote":"","anchor":"s1#1","doc":"SD-0001"}
            ]}
        """.trimIndent()

        val прогон = деривер.deriveInto(ПРОЕКТ, "C-0007", АВТОР, синтез)

        assertTrue(прогон.diff.new.isEmpty(), "число без цитаты не существует (истина онтологии)")
        assertTrue("сторож чисел" in (прогон.note ?: ""), "отказ называет сторожа: ${прогон.note}")
    }

    @Test
    fun `ворота — якорь вне канона документа — отказ`() {
        стенд()
        канал.ответ = """
            {"parameters":[
              {"id":"p1","key":"mass_dry","value":"120","unit":"кг",
               "quote":"Масса сухая 120 кг","anchor":"s9#9","doc":"SD-0001"}
            ]}
        """.trimIndent()

        val прогон = деривер.deriveInto(ПРОЕКТ, "C-0007", АВТОР, синтез)

        assertTrue(прогон.diff.new.isEmpty(), "цитата с якорем вне канона отбита")
        assertTrue("s9#9" in (прогон.note ?: ""), "отказ называет якорь: ${прогон.note}")
    }

    @Test
    fun `ворота — значение не числом — отказ`() {
        стенд()
        канал.ответ = """
            {"parameters":[
              {"id":"p1","key":"mass_dry","value":"нечисло","unit":"кг",
               "quote":"Масса сухая 120 кг","anchor":"s1#1","doc":"SD-0001"}
            ]}
        """.trimIndent()

        val прогон = деривер.deriveInto(ПРОЕКТ, "C-0007", АВТОР, синтез)

        assertTrue(прогон.diff.new.isEmpty(), "величина не числом отбита")
        assertTrue("нечисло" in (прогон.note ?: ""), "отказ называет значение: ${прогон.note}")
    }

    @Test
    fun `ворота — поставщик узла не назван — пустой прогон, модель не зовём`() {
        стенд(поставщикУзлу = false)
        канал.ответ = """{"parameters":[]}"""

        val прогон = деривер.deriveInto(ПРОЕКТ, "C-0007", АВТОР, синтез)

        assertTrue(прогон.diff.new.isEmpty(), "без поставщика предложений нет")
        assertTrue("поставщик узла не назван" in (прогон.note ?: ""), "причина названа: ${прогон.note}")
        assertTrue(канал.промпты.isEmpty(), "модель не зовём — ворота до вызова")
    }

    @Test
    fun `ворота — поставщик не заведён стороной — пустой прогон, модель не зовём`() {
        стенд()
        // Узел ссылается на сторону, которой в проекте нет: срез её не находит.
        val узел = store.byCode(Area.Project(ПРОЕКТ), "C-0007")!!
        val документ = узел.doc.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        документ.put("supplier", "SH-НЕТ")
        store.update(узел.id, документ, пров())

        val прогон = деривер.deriveInto(ПРОЕКТ, "C-0007", АВТОР, синтез)

        assertTrue(прогон.diff.new.isEmpty(), "стороны нет — предложений нет")
        assertTrue("не заведён стороной" in (прогон.note ?: ""), "причина названа: ${прогон.note}")
        assertTrue(канал.промпты.isEmpty(), "модель не зовём — ворота до вызова")
    }

    @Test
    fun `ворота — у поставщика нет документа — пустой прогон, модель не зовём`() {
        стенд()
        // Второй материал — с чужим происхождением: не паспорт нашего поставщика.
        store.create(
            "SD-0002", "material", Area.Project(ПРОЕКТ), null,
            mapper.createObjectNode().put("name", "Чужая спецификация").put("role", "supplier")
                .put("origin", "сторона-чужая")
                .put("text", "Спецификация\nМасса 1 кг."),
            пров(),
        )
        // Паспорт снимаем: документа поставщика в проекте нет.
        val паспорт = store.byCode(Area.Project(ПРОЕКТ), "SD-0001")!!
        store.update(паспорт.id, паспорт.doc, пров(), status = "cancelled")
        канал.ответ = """{"parameters":[]}"""

        val прогон = деривер.deriveInto(ПРОЕКТ, "C-0007", АВТОР, синтез)

        assertTrue(прогон.diff.new.isEmpty(), "без паспорта предложений нет")
        assertTrue("нет документа" in (прогон.note ?: ""), "причина названа: ${прогон.note}")
        assertTrue(канал.промпты.isEmpty(), "модель не зовём — ворота до вызова")
    }

    @Test
    fun `ворота — анкета узла не собрана — пустой прогон, модель не зовём`() {
        стенд(полка = false)
        канал.ответ = """{"parameters":[]}"""

        val прогон = деривер.deriveInto(ПРОЕКТ, "C-0007", АВТОР, синтез)

        assertTrue(прогон.diff.new.isEmpty(), "без ключей анкеты предложений нет")
        assertTrue("анкета узла не собрана" in (прогон.note ?: ""), "причина названа: ${прогон.note}")
        assertTrue(канал.промпты.isEmpty(), "модель не зовём — ворота до вызова")
    }

    // --- срез стенда ---------------------------------------------------------

    private var узелId: String = ""

    /**
     * Проект + узел с поставщиком полем + сторона-поставщик + паспорт изделия
     * с каноном + полка шаблонов с анкетой + единицы справочника.
     */
    private fun стенд(поставщикУзлу: Boolean = true, полка: Boolean = true) {
        store.create(
            ПРОЕКТ, "project", Area.Project(ПРОЕКТ), null,
            mapper.createObjectNode().put("name", "Стенд анкеты").put("knowledge_v2", true),
            пров(),
        )
        store.create(
            "SH-1", "stakeholder", Area.Project(ПРОЕКТ), null,
            mapper.createObjectNode().put("name", "Завод «Орбита»").put("role", "supplier"), пров(),
        )
        val узел = mapper.createObjectNode().put("name", "Приёмный модуль").put("kind", "element")
            .put("template_ref", "TC-SC")
        if (поставщикУзлу) узел.put("supplier", "SH-1")
        узелId = store.create("C-0007", "component", Area.Project(ПРОЕКТ), null, узел, пров()).id
        val поставщик = store.byCode(Area.Project(ПРОЕКТ), "SH-1")!!
        store.create(
            "SD-0001", "material", Area.Project(ПРОЕКТ), null,
            mapper.createObjectNode().put("name", "Паспорт изделия").put("role", "supplier")
                .put("origin", поставщик.id)
                .put("text", "Паспорт изделия\nМасса сухая 120 кг.\nСредняя мощность 60 Вт."),
            пров(),
        )
        if (полка) {
            store.create(
                "CTS-9001", "component_template_shelf", Area.Library, null,
                mapper.readTree(
                    """{"templates":[{"code":"TC-SC","ladder":{"A4":{"params":[
                       {"key":"mass_dry","unit":"кг"},{"key":"power_avg","unit":"Вт"}]}}}]}"""
                ),
                пров(),
            )
        }
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

    private fun пров() = Provenance(Channel.MANUAL, АВТОР)

    private companion object {
        const val ПРОЕКТ = "PRJ"
        const val АВТОР = "инженер"
    }
}

/** Канал-подмена: сеть не нужна, ответ задаётся тестом, промпты видны. */
private class КаналАнкеты : Transport {
    var ответ: String = """{"parameters":[]}"""
    val промпты = mutableListOf<String>()

    override fun ask(prompt: String, model: String?, maxTokens: Int?, schema: com.fasterxml.jackson.databind.JsonNode?): Answer {
        промпты += prompt
        return Answer(text = ответ, model = "модель-теста", tokensIn = 60, tokensOut = 90)
    }
}
