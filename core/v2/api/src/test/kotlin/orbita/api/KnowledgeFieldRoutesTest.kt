// Три двери поля знаний v2, которые плану пришлось дописать (шаг 19).
//
// 1. Загрузка материала — ЕДИНСТВЕННАЯ дверь входного документа: она обязана
//    доносить до ядра ранг доверия с формы, иначе на проекте поля знаний
//    загрузка отвечает отказом «ранг доверия материала обязателен», и поле
//    знаний нечем наполнить.
// 2. Слияние тем — щелчок человека: маршрут возвращает ГОЛОВУ цепочки, тот
//    адрес, по которому теперь читаются факты обеих тем.
// 3. Импорт чужого файла ранг назвать некому: формы у канала обмена нет,
//    поэтому он называет его сам — справочный, как всякое свидетельство.
package orbita.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.api.internal.AcrossRoutes
import orbita.api.internal.KnowledgeRoutes
import orbita.ai.api.AiFactory
import orbita.ai.api.Answer
import orbita.ai.api.Transport
import orbita.exchange.api.ExchangeFactory
import orbita.exchange.api.SdocBundle
import orbita.exchange.api.StrictDocService
import orbita.formulation.api.FormulationFactory
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.Provenance
import orbita.knowledge.api.Authority
import orbita.knowledge.api.KnowledgeFactory
import orbita.library.api.LibraryFactory
import orbita.process.api.ProcessFactory
import orbita.readiness.api.ReadinessFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KnowledgeFieldRoutesTest {

    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val знания = KnowledgeFactory.intake(store, links, mapper)

    private val сПолем = "PJ-9250"
    private val безПоля = "PJ-9251"
    private val автор = "Иванов И."
    private val провенанс = Provenance(Channel.MANUAL, автор)

    private val шаблон = mapper.readTree(
        TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые/ШАБЛОН-ФАЗЫ-PRE-A-NASA.json").toFile(),
    )
    private val служба = AiFactory.service(store, Transport { _, _, _ -> Answer("{}", "тест", 0, 0) }, mapper)

    private val сквозные: AcrossRoutes by lazy {
        AcrossRoutes(
            store, links,
            ProcessFactory.engine(
                template = { шаблон },
                evaluator = ReadinessFactory.gateEvaluator(
                    store, links, scenesDone = { emptySet() }, gatesPassed = { emptySet() },
                ),
                passedGates = { mutableSetOf() }, gatePlan = { emptyMap() },
            ),
            LibraryFactory.shelves(store) { шаблон },
            знания,
            FormulationFactory.formulation(store, links),
            mapper,
        )
    }

    private val знанияМаршруты: KnowledgeRoutes by lazy {
        KnowledgeRoutes(знания, AiFactory.atomize(store, знания, служба, mapper), служба, mapper, store = store)
    }

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        проект(сПолем, "Новый проект", поле = true)
        проект(безПоля, "Проход ПМИ-5", поле = false)
    }

    private fun проект(код: String, имя: String, поле: Boolean) {
        val документ = mapper.createObjectNode().put("name", имя)
        if (поле) документ.put("knowledge_v2", true)
        store.create(код, "project", Area.Project(код), "1", документ, провенанс)
    }

    private fun материалов(проект: String) = store.list(Area.Project(проект), "material").size

    // --- дверь первая: ранг доверия доходит с формы до ядра ------------------

    @Test
    fun `загрузка материала доносит ранг доверия с формы и возвращает его человеку`() {
        val ответ = assertNotNull(
            сквозные.handle(
                "POST", "/v2/materials", mapOf("project" to сПолем),
                """{"name":"ТЗ заказчика","kind":"tor","text":"п. 4.1 Система обязана передавать телеметрию.",
                    "authority":"mandatory","author":"$автор"}""",
            ),
        )
        assertEquals(201, ответ.code, ответ.body.toString())
        assertEquals(Authority.MANDATORY, ответ.body.path("authority").asText(), "ответ несёт ранг, а не только код")

        val карточка = assertNotNull(store.byCode(Area.Project(сПолем), ответ.body.path("code").asText()))
        assertEquals(Authority.MANDATORY, карточка.doc.path("authority").asText())
        // Тип входного никуда не делся: он остаётся режимом разбора.
        assertEquals("tor", карточка.doc.path("kind").asText())

        val список = assertNotNull(сквозные.handle("GET", "/v2/materials", mapOf("project" to сПолем), null))
        assertEquals(
            Authority.MANDATORY,
            список.body.path("items").single().path("authority").asText(),
            "перечень материалов показывает ранг: без него колонке «Ранг» нечего показывать",
        )
    }

    @Test
    fun `на проекте поля знаний загрузка без ранга отказывает словами и материала не заводит`() {
        val отказ = assertFailsWith<IllegalArgumentException> {
            сквозные.handle(
                "POST", "/v2/materials", mapOf("project" to сПолем),
                """{"name":"ТЗ заказчика","kind":"tor","text":"п. 4.1 Текст требования.","author":"$автор"}""",
            )
        }
        assertTrue("ранг доверия материала обязателен" in отказ.message.orEmpty(), отказ.message.orEmpty())
        assertEquals(0, материалов(сПолем), "отказ пустого материала не оставил")
    }

    @Test
    fun `на проекте прохода ранг выводится по типу входного и загрузка не меняется`() {
        val ответ = assertNotNull(
            сквозные.handle(
                "POST", "/v2/materials", mapOf("project" to безПоля),
                """{"name":"ТЗ заказчика","kind":"tor","text":"п. 4.1 Текст требования.","author":"$автор"}""",
            ),
        )
        assertEquals(201, ответ.code, ответ.body.toString())
        assertEquals(Authority.MANDATORY, ответ.body.path("authority").asText(), "ТЗ заказчика — обязательный документ")
        assertEquals(1, материалов(безПоля))
    }

    // --- дверь вторая: слияние тем -------------------------------------------

    @Test
    fun `маршрут слияния возвращает голову цепочки и называет, что во что слито`() {
        val голова = знания.addTopic(безПоля, "телеметрия", автор)
        val слитая = знания.addTopic(безПоля, "сбор телеметрии", автор)

        val ответ = assertNotNull(
            знанияМаршруты.handle(
                "POST", "/v2/topics/${слитая.id}/merge", mapOf("project" to безПоля),
                """{"into":"${голова.id}","author":"$автор","reason":"один предмет"}""",
            ),
        )
        assertEquals(200, ответ.code, ответ.body.toString())
        assertEquals(голова.id, ответ.body.path("id").asText(), "в ответе — адрес среза, а не слитая тема")
        assertEquals("телеметрия", ответ.body.path("label").asText())
        assertEquals(слитая.id, ответ.body.path("merged").asText())
        assertEquals(голова.id, ответ.body.path("merged_into").asText())
        assertEquals(1, знания.topics(безПоля).size, "у предмета одна тема, не две")
    }

    @Test
    fun `слияние без автора не ставится, и ни одна тема от отказа не двигается`() {
        val голова = знания.addTopic(безПоля, "телеметрия", автор)
        val слитая = знания.addTopic(безПоля, "сбор телеметрии", автор)

        val ответ = assertNotNull(
            знанияМаршруты.handle(
                "POST", "/v2/topics/${слитая.id}/merge", mapOf("project" to безПоля),
                """{"into":"${голова.id}"}""",
            ),
        )
        assertEquals(400, ответ.code, ответ.body.toString())
        assertTrue(
            "слияние тем без автора не ставится" in ответ.body.path("error").asText(),
            ответ.body.path("error").asText(),
        )
        assertEquals(2, знания.topics(безПоля).size, "отказ маршрута тем не слил")
    }

    // --- дверь третья: импорт чужого файла ----------------------------------

    /** Подставная служба StrictDoc: разбирать нечего, одно требование. */
    private class ОдноТребование : StrictDocService {
        override fun build(payload: JsonNode): SdocBundle = SdocBundle("[GRAMMAR]", "[DOCUMENT]")

        override fun reqif(payload: JsonNode): String = "<REQ-IF/>"

        override fun parse(sdoc: String, sgra: String?): JsonNode {
            val mapper = ObjectMapper()
            val узел = mapper.createObjectNode()
            узел.putArray("requirements").addObject()
                .put("id", "RQ-0001").put("statement", "Система обязана передавать телеметрию")
            return узел
        }
    }

    @Test
    fun `импорт чужого файла называет ранг сам и кладёт материал справочным`() {
        val обмен = ExchangeFactory.exchange(
            store, links, знания, mapper,
            strictDoc = ОдноТребование(),
            baselineRoot = java.nio.file.Files.createTempDirectory("sdoc").toFile(),
            reqif = null,
        )
        val итог = обмен.importSdoc(сПолем, "[DOCUMENT]\nTITLE: ТЗ\n\n[REQUIREMENT]\nUID: RQ-0001\n", null, автор)

        val код = assertNotNull(итог.material, "импорт кладёт файл материалом: у требования будет источник и якорь")
        val карточка = assertNotNull(store.byCode(Area.Project(сПолем), код))
        assertEquals(
            Authority.REFERENCE, карточка.doc.path("authority").asText(),
            "чужой файл — свидетельство, а не обязательный документ заказчика",
        )
        assertEquals(1, итог.candidates.size, итог.note)
    }
}
