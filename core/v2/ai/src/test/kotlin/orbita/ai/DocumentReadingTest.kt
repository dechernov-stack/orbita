// Чтение документа в постановку: один вызов, цитата у каждого пункта.
//
// РЕШЕНИЕ-ЧИТАТЬ-СМЫСЛ (владелец, 15.09). Проверяется НЕ «модель ответила», а
// то, что система делает с ответом: ворота стоят на цитате и якоре, ссылка
// внутри ответа разворачивается именем соседа, и за каждым понятием остаётся
// след-факт с якорем.
//
// Документ настоящий — записка миссии из поставки. Канал к модели подменён:
// сеть не нужна, а промпт виден целиком.
package orbita.ai

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.ai.api.Answer
import orbita.ai.api.AiFactory
import orbita.ai.api.Transport
import orbita.ai.internal.DocumentReader
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

class DocumentReadingTest {

    private val mapper = ObjectMapper()
    private val store: EntityStore = ПамятьПоля()
    private val знания = KnowledgeFactory.intake(store, null, mapper)
    private val транспорт = КаналЧтения()
    private val служба = AiFactory.service(store, транспорт, mapper)
    private val читатель = DocumentReader(store, знания, служба, mapper)

    private val корень = File(System.getenv("ORBITA_REPO_ROOT") ?: ".")
    private val записка: File = корень.resolve("docs/tz/v2/поставка-09-15/ЗАПИСКА-МИССИИ-IoT.md")

    private fun проект(): String {
        store.create(
            ПРОЕКТ, "project", Area.Project(ПРОЕКТ), "1",
            mapper.createObjectNode().put("name", "Чтение записки").put("knowledge_v2", true),
            Provenance(Channel.MANUAL, АВТОР),
        )
        return знания.putMaterial(
            ПРОЕКТ, "Записка миссии IoT", "mission_memo",
            записка.readText(), АВТОР, authority = Authority.MANDATORY,
        )
    }

    @Test
    fun `промпт несёт карту разделов с типом и колонками таблицы`() {
        val материал = проект()

        val промпт = читатель.prepare(ПРОЕКТ, материал)

        assertTrue("Разделы канона" in промпт, "карта разделов в промпте есть")
        assertTrue("s10 · таблица" in промпт, "таблица сторон названа таблицей: ${строкаКарты(промпт, "s10")}")
        assertTrue(
            "колонки: Кто, Роль/интерес" in промпт,
            "колонки таблицы названы: ${строкаКарты(промпт, "s10")}",
        )
        assertTrue("s12 · таблица" in промпт, "таблица целей: ${строкаКарты(промпт, "s12")}")
        // Ролей документа у материала нет — читаем как обстановку, и цели
        // проекта из него не выписываются: их рождает только устав.
        assertTrue("цели рождает только устав" in промпт, "самопроверка по роли документа")
    }

    @Test
    fun `подсказка происхождения и анти-примеры идут из истины, а не из кода`() {
        val материал = проект()

        val промпт = читатель.prepare(ПРОЕКТ, материал)

        // «Нужды берутся из перечня общих нужд · интереса каждой стороны ·
        // прямых формулировок нехватки» — это `from_facts_note` владельца.
        val нужда = orbita.knowledge.schema.GeneratedOntology.of("need")
        assertTrue(
            промпт.contains(нужда.fromFactsNote.orEmpty()),
            "подсказка происхождения нужды — дословно из истины",
        )
        assertTrue("интереса каждой стороны" in промпт, "интерес стороны назван нуждой-кандидатом")
        // Анти-примеры — тоже истина: «издатель акта — не сторона».
        orbita.knowledge.schema.GeneratedOntology.of("stakeholder").notFrom.forEach { анти ->
            assertTrue(анти in промпт, "анти-пример «$анти» в промпте есть")
        }
    }

    @Test
    fun `понятия приходят с цитатой, ссылка на соседа разворачивается именем`() {
        val материал = проект()
        транспорт.ответ = ОТВЕТ

        val итог = читатель.read(ПРОЕКТ, материал, АВТОР)

        val сторона = итог.proposals.single { it.concept == "stakeholder" }
        assertEquals("Минтранс России / Ространснадзор", сторона.payload["name"])
        assertEquals("customer", сторона.payload["role"])
        assertEquals("s10#3", сторона.anchor)

        val нужда = итог.proposals.single { it.concept == "need" }
        assertEquals(
            "Минтранс России / Ространснадзор",
            нужда.payload["stakeholder"],
            "ссылка `ref` развёрнута именем соседа: иначе связь owns→stakeholder не закрыть",
        )
    }

    @Test
    fun `за каждым понятием остаётся след-факт с тем же якорем`() {
        val материал = проект()
        транспорт.ответ = ОТВЕТ

        val итог = читатель.read(ПРОЕКТ, материал, АВТОР)

        val годные = итог.proposals.filter { it.fact != null }
        assertEquals(итог.proposals.size, годные.size, "след есть у каждого понятия: ${итог.proposals}")
        val сторона = итог.proposals.single { it.concept == "stakeholder" }
        val след = assertNotNull(store.byCode(Area.Project(ПРОЕКТ), сторона.fact!!), "след-факт заведён")
        assertEquals("fact", след.kind)
        assertEquals("s10#3", след.doc.path("anchor").asText(), "якорь следа — тот же, что у понятия")
        assertEquals(
            "Якорный заказчик, требования к транспортной телематике",
            след.doc.path("quote").asText(),
            "цитата легла В ФАКТ полем: истина схем 15.09 сделала `quote` обязательным",
        )
        assertEquals(
            "Минтранс России / Ространснадзор",
            след.doc.path("subject").asText(),
            "субъект следа — сама сторона",
        )
    }

    @Test
    fun `цитата не из текста и выдуманный якорь отбиваются поимённо`() {
        val материал = проект()
        транспорт.ответ = """
            {"stakeholders":[
              {"id":"s1","name":"Придуманное ведомство","role":"regulator",
               "quote":"такой строки в записке нет и никогда не было","anchor":"s10#3"},
              {"id":"s2","name":"Второе придуманное","role":"regulator",
               "quote":"Минтранс России / Ространснадзор","anchor":"s999#7"},
              {"id":"s3","name":"АО «ГЛОНАСС»","role":"operator",
               "quote":"точка подключения к ГАИС","anchor":"s10#4"}]}
        """.trimIndent()

        val итог = читатель.read(ПРОЕКТ, материал, АВТОР)

        assertEquals(1, итог.proposals.size, "прошло только то, у чего цитата настоящая: ${итог.proposals}")
        assertEquals("АО «ГЛОНАСС»", итог.proposals.single().payload["name"])
        assertTrue(
            итог.refused.any { "s1" in it && "дословно" in it },
            "отказ называет пункт и причину: ${итог.refused}",
        )
        assertTrue(
            итог.refused.any { "s2" in it && "якоря" in it },
            "выдуманный якорь назван: ${итог.refused}",
        )
        assertTrue("отбито воротами 2" in итог.note, итог.note)
    }

    private fun строкаКарты(промпт: String, якорь: String): String =
        промпт.lines().firstOrNull { it.trim().startsWith("$якорь ·") }.orEmpty()

    private companion object {
        const val ПРОЕКТ = "PJ-READ"
        const val АВТОР = "инженер"

        /** Цитаты — дословные куски настоящей записки: иначе ворота их не пустят. */
        val ОТВЕТ: String = """
            {"stakeholders":[
              {"id":"s1","name":"Минтранс России / Ространснадзор","role":"customer",
               "interest":"требования к транспортной телематике, контроль исполнения",
               "quote":"Якорный заказчик, требования к транспортной телематике",
               "anchor":"s10#3","confidence":0.9}],
             "needs":[
              {"id":"n1","statement":"единое оперативное управление транспортом",
               "stakeholder":{"ref":"s1"},
               "quote":"Минтранс фиксирует фрагментарность цифровых инициатив",
               "anchor":"s6#1","confidence":0.85}]}
        """.trimIndent()
    }
}

/** Канал-подмена: сеть не нужна, промпты видны. */
private class КаналЧтения : Transport {

    var ответ: String = """{"stakeholders":[]}"""
    val промпты = mutableListOf<String>()

    override fun ask(prompt: String, model: String?, maxTokens: Int?, schema: com.fasterxml.jackson.databind.JsonNode?): Answer {
        промпты += prompt
        return Answer(text = ответ, model = "модель-теста", tokensIn = 100, tokensOut = 200)
    }
}
