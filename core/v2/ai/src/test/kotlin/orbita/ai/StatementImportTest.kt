// Эталон постановки → предложения (ПМИ-7): добор того, чего чтение не дало.
//
// Эталон настоящий — `docs/tz/v2/фикстуры/постановка-из-записки.json`,
// документ настоящий — записка миссии. Модели здесь нет: проверяется, что
// каждая строка эталона ложится предложением с якорем КАНОНА (не шаблона),
// следом-фактом без цитаты и с номером источника, и что требования идут
// предложениями, а не сущностями.
package orbita.ai

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.ai.api.AiFactory
import orbita.ai.api.Answer
import orbita.ai.api.Transport
import orbita.ai.internal.StatementImporter
import orbita.ai.internal.Synthesizer
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.EntityStore
import orbita.kernel.api.Provenance
import orbita.knowledge.api.Authority
import orbita.knowledge.api.KnowledgeFactory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StatementImportTest {

    private val mapper = ObjectMapper()
    private val store: EntityStore = ПамятьПоля()
    private val знания = KnowledgeFactory.intake(store, null, mapper)
    private val служба = AiFactory.service(store, БезСети(), mapper)
    private val синтез = Synthesizer(store, знания, служба, mapper)
    private val импорт = StatementImporter(store, знания, mapper)

    private val корень = File(System.getenv("ORBITA_REPO_ROOT") ?: ".")
    private val записка = корень.resolve("docs/tz/v2/поставка-09-15/ЗАПИСКА-МИССИИ-IoT.md")
    private val эталон = корень.resolve("docs/tz/v2/фикстуры/постановка-из-записки.json")

    private fun проект(): String {
        store.create(
            ПРОЕКТ, "project", Area.Project(ПРОЕКТ), "1",
            mapper.createObjectNode().put("name", "Эталон").put("knowledge_v2", true),
            Provenance(Channel.MANUAL, АВТОР),
        )
        return знания.putMaterial(
            ПРОЕКТ, "Записка миссии IoT", "mission_memo", записка.readText(), АВТОР,
            authority = Authority.MANDATORY, role = "charter",
        )
    }

    @Test
    fun `эталон ложится предложениями по мере владельца`() {
        val материал = проект()

        val запуск = импорт.import(ПРОЕКТ, материал, mapper.readTree(эталон), АВТОР, синтез)

        val поПонятиям = запуск.diff.new.groupingBy { it.concept }.eachCount()
        assertEquals(14, поПонятиям["stakeholder"], "стороны эталона: $поПонятиям")
        assertEquals(43, поПонятиям["need"], "нужды эталона — все 43, по сторонам: $поПонятиям")
        assertEquals(9, поПонятиям["goal"])
        assertEquals(8, поПонятиям["service"])
        assertEquals(13, поПонятиям["constraint"])
        assertEquals(4, поПонятиям["milestone"])
        assertEquals(12, поПонятиям["risk"])
        assertEquals(12, поПонятиям["requirement"], "требования — предложениями в сцену 8, не сущностями")
        assertEquals(1, поПонятиям["intent"])
        assertTrue(store.list(Area.Project(ПРОЕКТ), "requirement").isEmpty(), "ни одно требование не заведено")
    }

    @Test
    fun `замысел плоско, этап с родом и диапазоном, риск без оценки человека`() {
        // На копии ПМИ-7 (216, 17.09) из эталона не завелись замысел, четыре этапа
        // и двенадцать рисков: грань замысла ехала JSON-объектом, у этапа не было
        // рода, мера снижения не ложилась в «measures».
        val материал = проект()
        val запуск = импорт.import(ПРОЕКТ, материал, mapper.readTree(эталон), АВТОР, синтез)
        val всё = запуск.diff.new

        val замысел = всё.single { it.concept == "intent" }.payload
        assertTrue(!замысел.getValue("what").startsWith("{"), "грань замысла — текст, не объект: ${замысел["what"]}")
        val этап = всё.first { it.concept == "milestone" }.payload
        assertEquals("program_stage", этап["kind"], "этап записки — программный этап")
        assertTrue(этап.getValue("range").contains("\"unit\""), "span эталона — range схемы: ${этап["range"]}")
        assertTrue("span" !in этап, "поля вне схемы вида нет")
        val риск = всё.first { it.concept == "risk" }.payload
        // Истина 17.09: probability · impact · strategy · measures ставит человек
        // на сцене 11 — из документа едут только формулировка и класс.
        assertTrue(
            listOf("probability", "impact", "strategy", "measures", "mitigation").none { it in риск },
            "оценку и меру снижения эталон не приносит: $риск",
        )
        assertTrue(риск.getValue("statement").isNotBlank(), "формулировка риска едет")
    }

    @Test
    fun `повторный импорт эталона даёт те же предложения, а не ноль`() {
        // Урезанный эталон (замысел + требования) после полного дал 0 предложений:
        // факты уже лежали, приём их не удваивал, а импорт искал только новые.
        val материал = проект()
        val первый = импорт.import(ПРОЕКТ, материал, mapper.readTree(эталон), АВТОР, синтез)
        val второй = импорт.import(ПРОЕКТ, материал, mapper.readTree(эталон), АВТОР, синтез)

        assertEquals(первый.diff.new.size, второй.diff.new.size, "повтор не теряет предложений")
        assertEquals(
            первый.diff.new.map { it.basis.single().factId }.toSet(),
            второй.diff.new.map { it.basis.single().factId }.toSet(),
            "основания повтора — те же факты",
        )
    }

    @Test
    fun `требование эталона едет полями схемы, а не словами эталона`() {
        // На стенде в записи требования оказались «mop» (слово эталона) и
        // «category: проектное» — перечня схемы такой класс не знает
        // (18.09). Показатель зовётся `measure`; класс, уровень и метод
        // верификации ставит инженер на сцене 8.
        val материал = проект()
        val запуск = импорт.import(ПРОЕКТ, материал, mapper.readTree(эталон), АВТОР, синтез)

        val требование = запуск.diff.new.first { it.concept == "requirement" }.payload
        assertTrue("mop" !in требование, "слова эталона в полях нет: $требование")
        assertTrue(требование.getValue("measure").contains("unit"), "показатель — полем measure: ${требование["measure"]}")
        listOf("category", "normative_basis", "priority", "verification_method").forEach {
            assertTrue(it !in требование, "«$it» ставит инженер на сцене 8, из эталона не едет")
        }
        assertTrue(требование.getValue("statement").isNotBlank() && требование.getValue("title").isNotBlank())
    }

    @Test
    fun `якорь эталона ремапится на канон стенда по номеру раздела`() {
        val материал = проект()

        val запуск = импорт.import(ПРОЕКТ, материал, mapper.readTree(эталон), АВТОР, синтез)

        // §3 «Потребители и стейкхолдеры» в каноне записки — раздел s10, §5 — s12.
        val сторона = запуск.diff.new.first { it.concept == "stakeholder" }
        assertEquals("s10", сторона.basis.single().anchor, "сторона ведёт в §3 канона")
        val цель = запуск.diff.new.first { it.concept == "goal" }
        assertEquals("s12", цель.basis.single().anchor, "цель ведёт в §5 канона")
    }

    @Test
    fun `след эталона без цитаты, с номером источника и кодом роли`() {
        val материал = проект()

        val запуск = импорт.import(ПРОЕКТ, материал, mapper.readTree(эталон), АВТОР, синтез)

        val минтранс = запуск.diff.new.first { it.payload["name"] == "Минтранс России / Ространснадзор" }
        assertEquals("customer", минтранс.payload["role"], "роль эталона «заказчик» → код истины схем")
        assertTrue("influence" !in минтранс.payload, "влияние считает система, значение эталона снято")
        val след = assertNotNull(store.byCode(Area.Project(ПРОЕКТ), минтранс.basis.single().factId))
        assertTrue(след.doc.path("quote").isNull, "у факта внешнего контура цитаты нет — null, как у эксперта")

        val цель = запуск.diff.new.first { it.concept == "goal" && it.payload["statement"]?.startsWith("Достичь стратегического") == true }
        val следЦели = assertNotNull(store.byCode(Area.Project(ПРОЕКТ), цель.basis.single().factId))
        assertEquals("И1", следЦели.doc.path("source_mark_no").asText(), "номер источника «И1» сохранён отдельным полем")
        assertEquals("И", следЦели.doc.path("mark").asText())
    }

    private companion object {
        const val ПРОЕКТ = "PJ-ETALON"
        const val АВТОР = "внешний контур"
    }
}

/** Импорту сеть не нужна: канал отказывает громко, если его вдруг позовут. */
private class БезСети : Transport {
    override fun ask(prompt: String, model: String?, maxTokens: Int?, schema: com.fasterxml.jackson.databind.JsonNode?): Answer =
        error("эталон импортируется без вызова модели")
}
