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

class DocumentReadingTest {

    private val mapper = ObjectMapper()

    @Test
    fun `схема чтения требует связи с нуждами у целей и сервисов и цитату у замысла`() {
        // ПМИ-7 на 216: 0 из 9 целей пришли с `needs`, замысел — без цитаты; всё
        // это ворота отбивают ПОСЛЕ ответа. Схема обязана требовать сразу:
        // истина «covers→need>=1» у цели и сервиса, цитата у каждого пункта.
        val схема = orbita.ai.internal.AnswerSchemas.чтение(mapper, listOf("stakeholders", "goals", "services"))
        val свойства = схема.path("properties")
        fun обязательные(путь: String): List<String> =
            свойства.path(путь).path("items").path("required").map { it.asText() }
        assertTrue("needs" in обязательные("goals"), "цель: needs обязателен, а не подсказка")
        assertTrue("needs" in обязательные("services"), "сервис: needs обязателен")
        val замысел = свойства.path("intent").path("required").map { it.asText() }
        assertTrue("quote" in замысел, "замысел: цитата обязательна — без неё пункт не выписывается")
    }
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

        // След НУЖДЫ обязан называть свою сторону, а не безличное «сторона
        // проекта»: иначе все нужды документа оказываются про одну
        // несуществующую сторону и происхождение теряется.
        val нужда = итог.proposals.single { it.concept == "need" }
        val следНужды = assertNotNull(store.byCode(Area.Project(ПРОЕКТ), нужда.fact!!))
        assertEquals(
            "Минтранс России / Ространснадзор",
            следНужды.doc.path("subject").asText(),
            "субъект следа нужды — её носитель",
        )
        assertEquals("Минтранс России / Ространснадзор", нужда.payload["stakeholder"])
    }

    @Test
    fun `величина цели складывается парой под именем из истины схем`() {
        val материал = проект()
        транспорт.ответ = """
            {"stakeholders":[
              {"id":"s1","name":"Минтранс России / Ространснадзор","role":"customer",
               "quote":"Якорный заказчик, требования к транспортной телематике","anchor":"s10#3"}],
             "goals":[
              {"id":"g1","statement":"количество ТС, передающих данные",
               "value":"270 653","unit":"ТС","year":"2025",
               "quote":"Минтранс фиксирует фрагментарность цифровых инициатив","anchor":"s6#1"}]}
        """.trimIndent()

        val цель = читатель.read(ПРОЕКТ, материал, АВТОР).proposals.single { it.concept == "goal" }

        // Истина схем зовёт поле `measure` и требует пару: плоские value и unit
        // отбивались сверкой как «величина без единицы — не факт».
        assertEquals(
            """{"value":"270 653","unit":"ТС"}""",
            цель.payload["measure"],
            "значение и единица сложены парой под именем истины: ${цель.payload}",
        )
        assertTrue("value" !in цель.payload, "плоской половинки наружу не идёт: ${цель.payload}")
        assertEquals("2025", цель.payload["year"])
    }

    @Test
    fun `незакрытая связь названа сразу — по ней экран отделяет требующее внимания`() {
        val материал = проект()
        транспорт.ответ = """
            {"stakeholders":[
              {"id":"s1","name":"Минтранс России / Ространснадзор","role":"customer",
               "quote":"Якорный заказчик, требования к транспортной телематике","anchor":"s10#3"}],
             "goals":[
              {"id":"g1","statement":"количество ТС, передающих данные",
               "value":"270 653","unit":"ТС","year":"2025",
               "quote":"Минтранс фиксирует фрагментарность цифровых инициатив","anchor":"s6#1"}]}
        """.trimIndent()

        val итог = читатель.readInto(ПРОЕКТ, материал, АВТОР, Synthesizer(store, знания, служба, mapper))

        val цель = итог.diff.new.single { it.concept == "goal" }
        assertTrue(
            цель.missing.any { "covers" in it },
            "цель без нужд названа незакрытой связью сразу: ${цель.missing}",
        )
        val сторона = итог.diff.new.single { it.concept == "stakeholder" }
        assertTrue(сторона.missing.isEmpty(), "у стороны обязательных связей нет: ${сторона.missing}")
    }

    @Test
    fun `общая нужда даёт по пункту на каждую сторону — так набирается мера`() {
        // Эталон владельца: 43 нужды у 14 сторон, из них 20 — одна и та же
        // формулировка, повторённая по сторонам. Плоским списком модель писала
        // каждую формулировку ОДИН раз и давала 14 нужд; нужды живут внутри
        // стороны, и повтор берётся самой формой ответа.
        val материал = проект()
        транспорт.ответ = """
            {"stakeholders":[
              {"id":"s1","name":"Минтранс России / Ространснадзор","role":"customer",
               "quote":"Якорный заказчик, требования к транспортной телематике","anchor":"s10#3",
               "needs":[
                 {"id":"n1","statement":"единое оперативное управление транспортом",
                  "quote":"Минтранс фиксирует фрагментарность цифровых инициатив","anchor":"s6#1"},
                 {"id":"n2","statement":"контроль исполнения требований к телематике",
                  "quote":"Якорный заказчик, требования к транспортной телематике","anchor":"s10#3"}]},
              {"id":"s2","name":"АО «ГЛОНАСС»","role":"operator",
               "quote":"точка подключения к ГАИС","anchor":"s10#4",
               "needs":[
                 {"id":"n3","statement":"единое оперативное управление транспортом",
                  "quote":"Минтранс фиксирует фрагментарность цифровых инициатив","anchor":"s6#1"}]}]}
        """.trimIndent()

        val итог = читатель.read(ПРОЕКТ, материал, АВТОР)

        val нужды = итог.proposals.filter { it.concept == "need" }
        assertEquals(3, нужды.size, "две у первой стороны и одна у второй: ${нужды.map { it.localId }}")
        val общая = нужды.filter { it.payload["statement"] == "единое оперативное управление транспортом" }
        assertEquals(2, общая.size, "общая нужда повторена по сторонам, а не слита в одну")
        assertEquals(
            listOf("АО «ГЛОНАСС»", "Минтранс России / Ространснадзор"),
            общая.map { it.payload["stakeholder"].orEmpty() }.sorted(),
            "у каждой копии свой носитель — связь owns у нужды одна",
        )
    }

    @Test
    fun `ответ в обёртке вызова инструмента разворачивается, а не теряется`() {
        // Живое чтение 16.09: провайдер завернул вход инструмента в
        // {"parameters": {…}}, и ответ на девяносто пять пунктов прочитался
        // как «ни одного понятия» — тихая потеря всего, худший вид отказа.
        val материал = проект()
        транспорт.ответ = """{"parameters": $ОТВЕТ}"""

        val итог = читатель.read(ПРОЕКТ, материал, АВТОР)

        assertEquals(2, итог.proposals.size, "обёртка снята: сторона и её нужда на месте")
        assertTrue(итог.proposals.any { it.concept == "stakeholder" })
        assertTrue(итог.proposals.any { it.concept == "need" })
    }

    @Test
    fun `штрих приводится к канону истины, а диапазон остаётся диапазоном`() {
        // Истина 17.09 (`normalization`): канон — типографский штрих ′ (U+2032),
        // апостроф нормализуется; величины — объектом, диапазон через min/max.
        // Записка и эталон владельца пишут «A'», истина схем «A′» — без
        // приведения класс обслуживания не совпадает ни с чем.
        val материал = проект()
        транспорт.ответ = """
            {"stakeholders":[
              {"id":"s1","name":"Минтранс России / Ространснадзор","role":"customer",
               "quote":"Якорный заказчик, требования к транспортной телематике","anchor":"s10#3",
               "needs":[
                 {"id":"n4","statement":"резервный канал в белых пятнах","qos_class":"B'/C'",
                  "quote":"Минтранс фиксирует фрагментарность цифровых инициатив","anchor":"s6#1"}]}],
             "goals":[
              {"id":"g1","statement":"лётная демонстрация",
               "measure":{"min":"2","max":"4","unit":"КА"},"year":"2028",
               "quote":"Демонстрационные КА, наземные станции и терминалы","anchor":"s12#4"}]}
        """.trimIndent()

        val итог = читатель.read(ПРОЕКТ, материал, АВТОР)

        val нужда = итог.proposals.single { it.concept == "need" }
        assertEquals(
            "B\u2032/C\u2032",
            нужда.payload["qos_class"],
            "апостроф приведён к штриху канона: ${нужда.payload["qos_class"]}",
        )
        val цель = итог.proposals.single { it.concept == "goal" }
        assertEquals(
            """{"min":"2","max":"4","unit":"КА"}""",
            цель.payload["measure"],
            "диапазон остался диапазоном, а не превратился в одно значение",
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
               "anchor":"s10#3","confidence":0.9,
               "needs":[
                 {"id":"n5","statement":"единое оперативное управление транспортом",
                  "quote":"Минтранс фиксирует фрагментарность цифровых инициатив",
                  "anchor":"s6#1","confidence":0.85}]}]}
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
