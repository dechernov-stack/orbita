// Ворота сохранения (план «Знания v2», шаг 19).
//
// Правило одно: на проекте поля знаний v2 введённое не попадает в модель
// напрямую — оно проходит сверку, и человек называет решение. Пока
// обязательная связь понятия не закрыта, сущности не будет. На проекте
// прохода ПМИ-5 ворот нет вовсе: тела прежних запросов не меняются ни на
// букву, и ни один существующий тест сцен не правится.
//
// Сверка здесь НАСТОЯЩАЯ (`KnowledgeFactory.reconcile`) и не тратит ни одного
// токена: ступень 1 работает на данных. Проверяется не она — её правила
// проверены в core/v2/knowledge, — а то, что ворота пропускают ровно
// сверенный ввод и отбивают остальной понятными человеку словами.
package orbita.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.ai.api.AiFactory
import orbita.ai.api.Answer
import orbita.ai.api.Transport
import orbita.api.internal.KnowledgeRoutes
import orbita.api.internal.V2Router
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.Provenance
import orbita.knowledge.api.Candidate
import orbita.knowledge.api.KnowledgeFactory
import orbita.library.api.LibraryFactory
import orbita.process.api.ProcessFactory
import orbita.readiness.api.ReadinessFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReconcileGateTest {

    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val знания = KnowledgeFactory.intake(store, links, mapper)
    private val сверка = KnowledgeFactory.reconcile(store, links, mapper, intake = знания)

    private val сПолем = "PJ-9240"
    private val безПоля = "PJ-9241"
    private val автор = "Иванов И."
    private val роль = "ведущий системный инженер"
    private val провенанс = Provenance(Channel.MANUAL, автор)

    private val шаблон = mapper.readTree(
        TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые/ШАБЛОН-ФАЗЫ-PRE-A-NASA.json").toFile(),
    )
    private val служба = AiFactory.service(store, Transport { _, _, _ -> Answer("{}", "тест", 0, 0) }, mapper)

    private val router: V2Router by lazy {
        V2Router(
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
            orbita.formulation.api.FormulationFactory.formulation(store, links),
            mapper,
            // Хранилище маршрутам знаний — ровно за флагом проекта: без него
            // ворот нет вовсе (прежняя сборка стенда прохода).
            knowledgeRoutes = KnowledgeRoutes(
                знания, AiFactory.atomize(store, знания, служба, mapper), служба, mapper,
                store = store,
            ),
        )
    }

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        проект(сПолем, "Новый проект", поле = true)
        проект(безПоля, "Проход ПМИ-5", поле = false)
    }

    private fun проект(код: String, имя: String, поле: Boolean) {
        val документ = mapper.createObjectNode().put("name", имя)
        // Проект прохода поля не имеет ВОВСЕ: отсутствие и есть «выключено».
        if (поле) документ.put("knowledge_v2", true)
        store.create(код, "project", Area.Project(код), "1", документ, провенанс)
    }

    private fun сторона(проект: String, код: String, имя: String) = store.create(
        код, "stakeholder", Area.Project(проект), "3",
        mapper.createObjectNode().put("name", имя).put("role", "заказчик"), провенанс,
    )

    /** Настоящий запуск сверки: его код и называет тело запроса — «SR-0001#c1». */
    private fun запуск(проект: String, формулировка: String, носитель: String? = null): String {
        val содержимое = mapper.createObjectNode().put("statement", формулировка)
        носитель?.let { содержимое.put("stakeholder", it) }
        return сверка.preview(проект, listOf(Candidate("c1", "need", содержимое)), автор, роль).id
    }

    private fun нужд(проект: String) = store.list(Area.Project(проект), "need").size

    // --- ввод мимо сверки ---------------------------------------------------

    @Test
    fun `на проекте поля знаний нужда без сверки отбивается словами «ввод не сверен»`() {
        сторона(сПолем, "SK-0001", "Минтранс России")
        val ответ = assertNotNull(
            router.handle(
                "POST", "/v2/needs", mapOf("project" to сПолем),
                """{"statement":"Непрерывный мониторинг вне наземного покрытия","owner":"SK-0001","author":"$автор"}""",
            ),
        )
        assertEquals(409, ответ.code, ответ.body.toString())
        assertEquals("ввод не сверен", ответ.body.path("error").asText())
        assertEquals("POST /v2/reconcile", ответ.body.path("what_to_do").asText())
        assertEquals(0, нужд(сПолем), "отказ ворот нужду не завёл")
    }

    @Test
    fun `сверка, названная в теле, обязана существовать в проекте`() {
        сторона(сПолем, "SK-0001", "Минтранс России")
        // Строка нужного вида, но запуска с таким кодом в проекте нет: иначе
        // «SR-0001#c1» в теле было бы обходом ворот, а не следом сверки.
        val ответ = assertNotNull(
            router.handle(
                "POST", "/v2/needs", mapOf("project" to сПолем),
                """{"statement":"Связь в Арктике","owner":"SK-0001","reconcile":"SR-0777#c1","decision":"принять новым"}""",
            ),
        )
        assertEquals(409, ответ.code, ответ.body.toString())
        assertEquals("ввод не сверен", ответ.body.path("error").asText())
        assertTrue("SR-0777" in ответ.body.path("note").asText(), ответ.body.path("note").asText())
        assertEquals(0, нужд(сПолем))
    }

    @Test
    fun `решение человека обязательно наравне со ссылкой на сверку`() {
        сторона(сПолем, "SK-0001", "Минтранс России")
        val код = запуск(сПолем, "Связь в Арктике", "SK-0001")
        val ответ = assertNotNull(
            router.handle(
                "POST", "/v2/needs", mapOf("project" to сПолем),
                """{"statement":"Связь в Арктике","owner":"SK-0001","reconcile":"$код#c1"}""",
            ),
        )
        assertEquals(409, ответ.code, "сверка без решения человека воротами не считается")
        assertEquals(0, нужд(сПолем))
    }

    // --- сверенный ввод проходит --------------------------------------------

    @Test
    fun `сверенная нужда с выбранной стороной сохраняется и помнит свою сверку`() {
        val сторона = сторона(сПолем, "SK-0001", "Минтранс России")
        val код = запуск(сПолем, "Непрерывный мониторинг вне наземного покрытия", "SK-0001")

        val ответ = assertNotNull(
            router.handle(
                "POST", "/v2/needs", mapOf("project" to сПолем),
                """{"statement":"Непрерывный мониторинг вне наземного покрытия","owner":"SK-0001",
                    "reconcile":"$код#c1","decision":"принять новым","author":"$автор"}""",
            ),
        )
        assertEquals(201, ответ.code, ответ.body.toString())
        val нужда = assertNotNull(store.byCode(Area.Project(сПолем), ответ.body.path("code").asText()))
        // Поля ворот — не содержание вида: в документе нужды их нет.
        assertTrue(нужда.doc.path("reconcile").isMissingNode, нужда.doc.toString())
        assertTrue(нужда.doc.path("decision").isMissingNode, нужда.doc.toString())
        assertEquals("$код#c1", нужда.provenance.source, "след сверки остался в провенансе записи")
        assertEquals(
            listOf(сторона.id), links.to(нужда.id, "owns").map { it.from },
            "носитель встал связью, как и прежде",
        )
    }

    // --- незакрытая обязательная связь --------------------------------------

    @Test
    fun `нужда без стороны отбивается 422 с именем обязательной связи`() {
        val код = запуск(сПолем, "Связь в Арктике")
        val ответ = assertNotNull(
            router.handle(
                "POST", "/v2/needs", mapOf("project" to сПолем),
                """{"statement":"Связь в Арктике","reconcile":"$код#c1","decision":"принять новым"}""",
            ),
        )
        assertEquals(422, ответ.code, ответ.body.toString())
        assertEquals("owns→stakeholder", ответ.body.path("missing").asText())
        assertTrue("owner" in ответ.body.path("what_to_do").asText(), ответ.body.path("what_to_do").asText())
        assertEquals(0, нужд(сПолем), "сущности без обязательной связи не будет")
    }

    @Test
    fun `цель без покрытых нужд и сервис без класса качества не заводятся`() {
        val код = запуск(сПолем, "Связь в Арктике")
        val цель = assertNotNull(
            router.handle(
                "POST", "/v2/goals", mapOf("project" to сПолем),
                """{"statement":"развернуть группировку","year":2032,"reconcile":"$код#c1","decision":"принять новым"}""",
            ),
        )
        assertEquals(422, цель.code, цель.body.toString())
        assertEquals("covers→need>=1", цель.body.path("missing").asText())

        // У сервиса связей две: покрытые нужды названы, класс качества — нет.
        store.create(
            "ND-0001", "need", Area.Project(сПолем), "3",
            mapper.createObjectNode().put("statement", "связь в Арктике"), провенанс,
        )
        val сервис = assertNotNull(
            router.handle(
                "POST", "/v2/services", mapOf("project" to сПолем),
                """{"name":"передача телеметрии","covers":["ND-0001"],"reconcile":"$код#c1","decision":"принять новым"}""",
            ),
        )
        assertEquals(422, сервис.code, сервис.body.toString())
        assertEquals("qos_class", сервис.body.path("missing").asText())
        assertTrue(store.list(Area.Project(сПолем), "goal").isEmpty())
        assertTrue(store.list(Area.Project(сПолем), "service").isEmpty())
    }

    // --- ручной факт --------------------------------------------------------

    @Test
    fun `ручной факт на проекте поля знаний идёт через сверку и несёт роль с рангом`() {
        val безСверки = assertNotNull(
            router.handle(
                "POST", "/v2/facts", mapOf("project" to сПолем),
                """{"subject":"система","predicate":"принимает телеметрию","value":"да","author":"$автор"}""",
            ),
        )
        assertEquals(409, безСверки.code, безСверки.body.toString())
        assertEquals("ввод не сверен", безСверки.body.path("error").asText())

        val код = запуск(сПолем, "Связь в Арктике")
        val ответ = assertNotNull(
            router.handle(
                "POST", "/v2/facts", mapOf("project" to сПолем),
                """{"subject":"система","predicate":"принимает телеметрию","value":"да",
                    "source":{"account":"$автор","role":"$роль"},
                    "reconcile":"$код#c1","decision":"кандидат-факт эксперта"}""",
            ),
        )
        assertEquals(201, ответ.code, ответ.body.toString())
        // Ранг руки — экспертный, и ставит его приём знаний, не маршрут.
        assertEquals("expert", ответ.body.path("authority").asText())
        // Источник союзом: у руки якоря нет, зато есть учётка · роль · дата.
        assertEquals(автор, ответ.body.path("source").path("account").asText())
        assertEquals(роль, ответ.body.path("source").path("role").asText())
        assertTrue(ответ.body.path("source").path("at").asText().isNotBlank(), ответ.body.toString())
        assertTrue(ответ.body.path("anchor").isNull, "у руки документа нет — якоря тоже")
        assertEquals("И", ответ.body.path("mark").asText(), "помета по умолчанию — наш материал")
    }

    // --- проект прохода: прежний порядок целиком ----------------------------

    @Test
    fun `на проекте без флага сцены и ручной факт сохраняются прежним порядком`() {
        val п = mapOf("project" to безПоля)
        val сторона = assertNotNull(
            router.handle("POST", "/v2/stakeholders", п, """{"name":"Минтранс России","role":"customer"}"""),
        )
        assertEquals(201, сторона.code, сторона.body.toString())

        // Ни слова о сверке в теле — и нужда без носителя тоже сохраняется:
        // на проекте прохода ворот нет, а разрыв показывает выход сцены 3.
        val нужда = assertNotNull(
            router.handle("POST", "/v2/needs", п, """{"statement":"Мониторинг вне покрытия"}"""),
        )
        assertEquals(201, нужда.code, нужда.body.toString())

        val цель = assertNotNull(
            router.handle("POST", "/v2/goals", п, """{"statement":"развернуть группировку","year":2032}"""),
        )
        assertEquals(201, цель.code, цель.body.toString())

        val факт = assertNotNull(
            router.handle(
                "POST", "/v2/facts", п,
                """{"subject":"система","predicate":"принимает телеметрию","value":"да"}""",
            ),
        )
        assertEquals(201, факт.code, факт.body.toString())
        // Роль на проекте прохода не спрашивают — источником остаётся «инженер, дата».
        assertNull(факт.body.path("source").path("role").textValue(), факт.body.toString())
        assertEquals("expert", факт.body.path("authority").asText(), "ранг руки экспертный и без поля знаний")
    }

    @Test
    fun `флаг соседнего проекта на проект прохода не переносится`() {
        сторона(сПолем, "SK-0001", "Минтранс России")
        val отбит = assertNotNull(
            router.handle("POST", "/v2/needs", mapOf("project" to сПолем), """{"statement":"а","owner":"SK-0001"}"""),
        )
        assertEquals(409, отбит.code)
        val прошёл = assertNotNull(
            router.handle("POST", "/v2/needs", mapOf("project" to безПоля), """{"statement":"а"}"""),
        )
        assertEquals(201, прошёл.code, "один стенд держит оба порядка сразу")
    }
}
