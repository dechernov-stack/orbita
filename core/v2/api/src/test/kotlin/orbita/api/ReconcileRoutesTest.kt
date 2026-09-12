// Маршруты сверки ввода (план «Знания v2», шаг 17).
//
// Здесь проверяется РОВНО то, за что отвечает маршрут: перевод HTTP в порт и
// обратно. Правила сверки — четыре вопроса, ключ идентичности, отличие
// словами — проверены там, где живут: core/v2/knowledge, ReconcileManualInputTest.
// Поэтому порт здесь двойник с записью вызовов: он и позволяет проверить то,
// ради чего маршрут заведён, — батч из десяти вводов уходит ОДНИМ вызовом
// (на этом стоит токенный бюджет), повторное чтение запуска не зовёт службу
// вовсе, а сам маршрут в модель не пишет ни строки.
//
// Хранилище настоящее: флаг проекта читается из него, и снимок принятых
// сущностей до и после сверки сверяется по нему же.
package orbita.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.api.internal.ReconcileRoutes
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.Provenance
import orbita.knowledge.api.Action
import orbita.knowledge.api.Applied
import orbita.knowledge.api.Authority
import orbita.knowledge.api.Candidate
import orbita.knowledge.api.CandidateOrigin
import orbita.knowledge.api.Comparison
import orbita.knowledge.api.FactSource
import orbita.knowledge.api.FieldDifference
import orbita.knowledge.api.Finding
import orbita.knowledge.api.KnowledgeFactory
import orbita.knowledge.api.Match
import orbita.knowledge.api.MustLinkMissing
import orbita.knowledge.api.Question
import orbita.knowledge.api.Reconcile
import orbita.knowledge.api.ReconcileItem
import orbita.knowledge.api.ReconcileRun
import orbita.knowledge.api.Verdict
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReconcileRoutesTest {

    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val intake = KnowledgeFactory.intake(store, links, mapper)

    private val сПолем = "PJ-8101"
    private val безПоля = "PJ-8102"
    private val автор = "Иванов И."
    private val роль = "ведущий СИ"
    private val провенанс = Provenance(Channel.MANUAL, автор)

    private val порт = ДвойникСверки()
    private val маршруты = ReconcileRoutes(store, порт, intake, mapper)

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        порт.очистить()
        проект(сПолем, "Новый проект", поле = true)
        проект(безПоля, "Проход ПМИ-5", поле = false)
    }

    private fun проект(код: String, имя: String, поле: Boolean) {
        val документ = mapper.createObjectNode().put("name", имя)
        // Проект прохода поля не имеет ВОВСЕ: отсутствие и есть «выключено».
        if (поле) документ.put("knowledge_v2", true)
        store.create(код, "project", Area.Project(код), null, документ, провенанс)
    }

    private fun сторона(проект: String, код: String, имя: String) = store.create(
        код, "stakeholder", Area.Project(проект), "3",
        mapper.createObjectNode().put("name", имя).put("role", "customer"), провенанс,
    )

    /** Снимок принятого: код, версия и документ — им и проверяется «не изменилось ничего». */
    private fun снимок(проект: String): List<String> =
        listOf("stakeholder", "need", "goal", "fact", "synthesis_run")
            .flatMap { store.list(Area.Project(проект), it) }
            .sortedBy { it.code }
            .map { "${it.code}|${it.version}|${it.doc}" }

    private fun тело(кандидатов: Int): String {
        val строки = (1..кандидатов).joinToString(",") { н ->
            """{"local_id":"c$н","concept":"need","payload":{"statement":"нужда номер $н"}}"""
        }
        return """{"author":"$автор","role":"$роль","semantic":false,"candidates":[$строки]}"""
    }

    // --- батч одним вызовом ------------------------------------------------

    @Test
    fun `десять кандидатов одним запросом дают один запуск и один вызов сверки`() {
        val ответ = маршруты.handle("POST", "/v2/reconcile", mapOf("project" to сПолем), тело(10))!!

        assertEquals(201, ответ.code, ответ.body.toString())
        assertEquals("SR-0001", ответ.body.path("run").asText())
        assertEquals(10, ответ.body.path("items").size(), "все десять строк ввода вернулись своими номерами")
        assertEquals(listOf("preview"), порт.вызовы, "батч не разбирается на десять вызовов: это и есть бюджет")
        assertEquals(
            (1..10).map { "c$it" }, порт.кандидаты.map { it.localId },
            "местный номер строки ввода доходит до порта: находке некуда вернуться без него",
        )
        assertEquals(автор to роль, порт.автор to порт.роль, "источник ручного ввода — учётка и роль")
        assertEquals(CandidateOrigin.MANUAL, порт.кандидаты.first().origin, "умолчание происхождения — рука")
        assertFalse(порт.семантика, "вторую ступень не просили — и маршрут её не включает сам")
    }

    @Test
    fun `находка доходит до человека с отличием словами и предложенными действиями`() {
        val ответ = маршруты.handle("POST", "/v2/reconcile", mapOf("project" to сПолем), тело(1))!!

        val предмет = ответ.body.path("items").first()
        assertEquals("c1", предмет.path("local_id").asText())
        assertEquals("F-0001", предмет.path("candidate_fact").asText(), "ручной ввод стал кандидатом-фактом")
        assertEquals(Authority.EXPERT, предмет.path("authority").asText())
        assertEquals(автор, предмет.path("source").path("account").asText(), "у руки якоря нет — учётка, роль, дата")
        assertTrue(предмет.path("decided").isNull, "решение ещё не принято: судьбу кандидата решает человек")

        val находка = предмет.path("findings").first()
        assertEquals("дубль", находка.path("question_word").asText())
        assertEquals("ND-0001", находка.path("target").asText())
        assertEquals("по ключу", находка.path("match").asText(), "ключ нашёл дубль без живого вызова")
        assertEquals("statement", находка.path("difference").path("field").asText())
        assertTrue(находка.path("difference").path("mine").asText().isNotBlank(), "«похоже» без «чем именно» — брак")
        assertTrue(
            находка.path("offers").map { it.asText() }.contains("merge_into"),
            "действия предложены, ни одно не выполнено: ${находка.path("offers")}",
        )
        assertFalse(ответ.body.path("ai_called").asBoolean(true), "ступень 1 журнал ИИ не прирастила")
    }

    // --- решение человека --------------------------------------------------

    @Test
    fun `решение слить доходит до порта целью и причиной, а маршрут в модель не пишет`() {
        val до = снимок(сПолем)
        порт.итог = Applied(
            created = emptyList(), updated = emptyList(),
            links = listOf("derived_from_fact"), facts = listOf("F-0001"),
            note = "кандидат привязан основанием к ND-0001: цель не переписана",
        )

        val ответ = маршруты.handle(
            "POST", "/v2/reconcile/SR-0001/apply", mapOf("project" to сПолем),
            """{"local_id":"c1","finding":0,"action":"merge_into","target":"ND-0001",""" +
                """"reason":"та же нужда иными словами","author":"$автор"}""",
        )!!

        assertEquals(201, ответ.code, ответ.body.toString())
        assertEquals(Action.MERGE_INTO to "ND-0001", порт.действие to порт.цель)
        assertEquals("та же нужда иными словами", порт.причина)
        assertEquals(0, ответ.body.path("created").size(), "слияние новой сущности не заводит")
        assertEquals("derived_from_fact", ответ.body.path("links").first().asText())
        assertTrue(ответ.body.path("coverage").isInt, "долю знаний считает сервер, не клиент")
        assertEquals(до, снимок(сПолем), "модель меняет порт, а не маршрут")
    }

    @Test
    fun `решение без причины не ставится`() {
        val беда = assertFailsWith<IllegalArgumentException> {
            маршруты.handle(
                "POST", "/v2/reconcile/SR-0001/apply", mapOf("project" to сПолем),
                """{"local_id":"c1","finding":0,"action":"merge_into","author":"$автор"}""",
            )
        }
        assertTrue("reason" in беда.message!!, беда.message)
        assertTrue(порт.вызовы.isEmpty(), "до порта решение без причины не доходит")
    }

    @Test
    fun `решение по нехватке без выбора стороны отвечает 422 и называет связь`() {
        порт.нехватка = MustLinkMissing("owns→stakeholder", "нужда без стороны не заводится: выберите носителя")

        val ответ = маршруты.handle(
            "POST", "/v2/reconcile/SR-0001/apply", mapOf("project" to сПолем),
            """{"local_id":"c1","finding":0,"action":"accept_new","reason":"новая нужда","author":"$автор"}""",
        )!!

        assertEquals(422, ответ.code, ответ.body.toString())
        assertEquals("owns→stakeholder", ответ.body.path("missing").asText())
        assertTrue(
            "target" in ответ.body.path("what_to_do").asText(),
            "отказ говорит, чем закрыть нехватку: ${ответ.body.path("what_to_do").asText()}",
        )
    }

    @Test
    fun `действие вне предложенного списка отвергается словами`() {
        val беда = assertFailsWith<IllegalArgumentException> {
            маршруты.handle(
                "POST", "/v2/reconcile/SR-0001/apply", mapOf("project" to сПолем),
                """{"local_id":"c1","finding":0,"action":"слить_всё","reason":"так быстрее","author":"$автор"}""",
            )
        }
        assertTrue("слить" in беда.message!!, "отказ перечисляет действия словами: ${беда.message}")
        assertTrue(порт.вызовы.isEmpty())
    }

    // --- что ждёт человека и повторное чтение ------------------------------

    @Test
    fun `чтение запуска по коду живого вызова не делает`() {
        val ответ = маршруты.handle("GET", "/v2/reconcile/SR-0001", mapOf("project" to сПолем), null)!!

        assertEquals(200, ответ.code)
        assertEquals("SR-0001", ответ.body.path("run").asText())
        assertEquals(listOf("run"), порт.вызовы, "повторный взгляд на находки токенов не стоит")
    }

    @Test
    fun `список открытых сверок спрашивается только про открытые`() {
        val ответ = маршруты.handle(
            "GET", "/v2/reconcile", mapOf("project" to сПолем, "status" to "open"), null,
        )!!
        assertEquals(200, ответ.code)
        assertEquals(1, ответ.body.path("count").asInt())
        assertEquals(listOf("open"), порт.вызовы)

        // Отказной: статус на слух клиент не придумывает
        val беда = assertFailsWith<IllegalArgumentException> {
            маршруты.handle("GET", "/v2/reconcile", mapOf("project" to сПолем, "status" to "closed"), null)
        }
        assertTrue("open" in беда.message!!, беда.message)
    }

    // --- отказы ------------------------------------------------------------

    @Test
    fun `на проекте без поля знаний сверка отказывает словами и порт не трогает`() {
        сторона(безПоля, "SK-0001", "Минтранс России")
        val до = снимок(безПоля)
        val п = mapOf("project" to безПоля)

        val адреса = listOf(
            Triple("POST", "/v2/reconcile", тело(1)),
            Triple(
                "POST", "/v2/reconcile/SR-0001/apply",
                """{"local_id":"c1","finding":0,"action":"dismiss","reason":"снято","author":"$автор"}""",
            ),
            Triple("GET", "/v2/reconcile", null),
            Triple("GET", "/v2/reconcile/SR-0001", null),
        )
        адреса.forEach { (метод, путь, запрос) ->
            val ответ = маршруты.handle(метод, путь, п, запрос)!!
            assertEquals(409, ответ.code, "$метод $путь: ${ответ.body}")
            assertTrue("выключен" in ответ.body.path("error").asText(), ответ.body.toString())
            assertTrue(
                "knowledge_v2" in ответ.body.path("what_to_do").asText(),
                "отказ говорит, что делать: ${ответ.body.path("what_to_do").asText()}",
            )
        }
        assertTrue(порт.вызовы.isEmpty(), "выключенная сверка не зовёт порт ни разу")
        assertEquals(до, снимок(безПоля), "проект прохода живёт прежним порядком")
    }

    @Test
    fun `понятие вне онтологии отвергается до сверки`() {
        val беда = assertFailsWith<IllegalArgumentException> {
            маршруты.handle(
                "POST", "/v2/reconcile", mapOf("project" to сПолем),
                """{"author":"$автор","role":"$роль","candidates":""" +
                    """[{"local_id":"c1","concept":"мечта","payload":{"statement":"хочется"}}]}""",
            )
        }
        assertTrue("онтологии" in беда.message!!, беда.message)
        assertTrue(порт.вызовы.isEmpty(), "понятие вне онтологии не образуется — и сверки не занимает")
    }

    @Test
    fun `сверка без кандидатов и без проекта отвергается`() {
        val пусто = assertFailsWith<IllegalArgumentException> {
            маршруты.handle("POST", "/v2/reconcile", mapOf("project" to сПолем), """{"author":"$автор","role":"$роль"}""")
        }
        assertTrue("candidates" in пусто.message!!, пусто.message)

        val безПроекта = assertFailsWith<IllegalArgumentException> {
            маршруты.handle("POST", "/v2/reconcile", emptyMap(), тело(1))
        }
        assertTrue("project" in безПроекта.message!!, безПроекта.message)
        assertTrue(порт.вызовы.isEmpty())
    }

    @Test
    fun `чужой путь маршрутам сверки не принадлежит`() {
        assertNull(маршруты.handle("GET", "/v2/facts", mapOf("project" to сПолем), null))
        assertNull(маршруты.handle("POST", "/v2/needs", mapOf("project" to сПолем), "{}"))
        assertNull(маршруты.handle("GET", "/v2/reconcile/SR-abc", mapOf("project" to сПолем), null))
    }

    /**
     * Двойник порта сверки с записью вызовов.
     *
     * Он не считает находок и не меняет модели: в нём проверяется договор
     * маршрута с портом — что пришло, сколько раз позвали и что вернулось
     * человеку. Правила самой сверки живут в модуле знаний.
     */
    private class ДвойникСверки : Reconcile {

        val вызовы = mutableListOf<String>()
        var кандидаты: List<Candidate> = emptyList()
        var автор: String = ""
        var роль: String = ""
        var семантика: Boolean = true
        var действие: Action? = null
        var цель: String? = null
        var причина: String = ""
        var нехватка: MustLinkMissing? = null
        var итог: Applied = Applied(emptyList(), emptyList(), emptyList(), emptyList(), "")

        fun очистить() {
            вызовы.clear()
            кандидаты = emptyList()
            автор = ""
            роль = ""
            семантика = true
            действие = null
            цель = null
            причина = ""
            нехватка = null
            итог = Applied(emptyList(), emptyList(), emptyList(), emptyList(), "")
        }

        override fun preview(
            project: String,
            candidates: List<Candidate>,
            author: String,
            role: String,
            semantic: Boolean,
        ): ReconcileRun {
            вызовы += "preview"
            кандидаты = candidates
            автор = author
            роль = role
            семантика = semantic
            return запуск(candidates.map { предмет(it.localId) })
        }

        override fun run(project: String, run: String): ReconcileRun {
            вызовы += "run"
            return запуск(listOf(предмет("c1")))
        }

        override fun open(project: String): List<ReconcileRun> {
            вызовы += "open"
            return listOf(запуск(listOf(предмет("c1"))))
        }

        override fun apply(
            project: String,
            run: String,
            localId: String,
            finding: Int,
            action: Action,
            target: String?,
            reason: String,
            author: String,
        ): Applied {
            вызовы += "apply"
            действие = action
            цель = target
            причина = reason
            нехватка?.let { throw it }
            return итог
        }

        private fun запуск(предметы: List<ReconcileItem>) = ReconcileRun(
            id = "SR-0001", status = "done", sliceFingerprint = "f1a2",
            ontologyVersion = "0ab9", aiCalled = false, open = true, items = предметы,
            note = "сверка ступени 1 без вызова службы",
        )

        private fun предмет(номер: String) = ReconcileItem(
            localId = номер, concept = "need", candidateFact = "F-0001", authority = Authority.EXPERT,
            source = FactSource.FromExpert(автор.ifBlank { "Иванов И." }, роль.ifBlank { "ведущий СИ" }, "2026-09-12"),
            verdict = Verdict.AUGMENT,
            findings = listOf(
                Finding(
                    question = Question.DUPLICATE, verdict = Verdict.AUGMENT, target = "ND-0001",
                    match = Match.KEY, comparedFields = listOf("stakeholder", "statement"),
                    difference = FieldDifference(
                        Comparison.DIFFERS, "statement",
                        mine = "в Арктике требуется связь", theirs = "необходимо обеспечить связь в Арктике",
                    ),
                    basis = listOf("F-0002"),
                    offers = listOf(Action.MERGE_INTO, Action.REFINE, Action.ACCEPT_NEW),
                ),
            ),
        )
    }
}
