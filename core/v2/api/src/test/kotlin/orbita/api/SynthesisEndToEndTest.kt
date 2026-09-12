// Сквозная проводка поля знаний v2 (шаг 20 плана «Знания v2»).
//
// Здесь не проверяется ни синтез, ни сверка, ни верификация по существу — у
// каждой свой тест. Здесь проверяется ПРОВОДКА: собранный границей роутер
// доводит запрос до нужного обработчика, а собранный БЕЗ новых маршрутов
// (стенд прохода ПМИ-5) отвечает на те же адреса ровно так же, как на
// выдуманный путь, — `null`, без единого падения.
//
// Разница между «выключено» и «не подключено» — суть этого файла:
//   · маршрут не передан      → `null`  (обвязка сделает 404);
//   · маршрут передан, флага у проекта нет → 409 со словами «что делать».
// Спутать их нельзя: первое означало бы, что стенд собран не полностью, и
// молчаливое 404 на новом проекте искали бы в коде маршрутов.
//
// Службы настоящие, кроме канала модели: подменён только транспорт — все
// адреса этого теста считаются по данным и живого вызова не делают.
package orbita.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.ai.api.AiFactory
import orbita.ai.api.Answer
import orbita.ai.api.Transport
import orbita.api.internal.DocRoutes
import orbita.api.internal.ReconcileRoutes
import orbita.api.internal.ResearchRoutes
import orbita.api.internal.SynthesisRoutes
import orbita.api.internal.V2Router
import orbita.api.internal.VerifyRoutes
import orbita.documents.api.DocumentsFactory
import orbita.formulation.api.FormulationFactory
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.KnowledgeFlag
import orbita.kernel.api.Provenance
import orbita.knowledge.api.KnowledgeFactory
import orbita.library.api.LibraryFactory
import orbita.process.api.ProcessFactory
import orbita.readiness.api.ReadinessFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SynthesisEndToEndTest {

    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)

    private val каталогПолок = TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые")
    private val шаблонФазы = mapper.readTree(каталогПолок.resolve("ШАБЛОН-ФАЗЫ-PRE-A-NASA.json").toFile())

    private val дом: java.io.File =
        java.nio.file.Files.createTempDirectory("orbita-baselines").toFile().also { it.deleteOnExit() }

    private val полки = LibraryFactory.shelves(store) { шаблонФазы }
    private val знания = KnowledgeFactory.intake(store, links, mapper)

    // Канал модели подменён пустым ответом: ни один адрес этого теста модели
    // не зовёт, и «пусто» — самый честный признак того, что не позвал.
    private val служба = AiFactory.service(store, Transport { _, _, _ -> Answer("[]", "тест", 0, 0) }, mapper)

    private val документы = DocumentsFactory.documents(
        store, links,
        template = { код ->
            val файл = каталогПолок.resolve("ШАБЛОН-" + код.uppercase() + ".json").toFile()
            if (файл.isFile) mapper.readTree(файл) else null
        },
        mapper = mapper,
        baselineRoot = дом,
    )
    private val проверка = DocumentsFactory.verification(store, links, документы, mapper)

    // Ступень 1 сверки собирается один раз и уходит семантике — ровно как в
    // Boundary: собери её служба заново, и кандидат-факты разошлись бы.
    private val сверка = AiFactory.reconcile(
        store,
        KnowledgeFactory.reconcile(store, links, mapper, полки, intake = знания),
        служба,
        mapper,
    )
    private val исследование = KnowledgeFactory.research(store, links, mapper, полки, intake = знания)

    private fun движок() = ProcessFactory.engine(
        template = { шаблонФазы },
        evaluator = ReadinessFactory.gateEvaluator(
            store, links, scenesDone = { emptySet() }, gatesPassed = { emptySet() },
        ),
        passedGates = { mutableSetOf() },
        gatePlan = { emptyMap() },
    )

    /** Сборка границы: все четыре маршрута поля знаний v2 на месте. */
    private val роутер: V2Router by lazy {
        V2Router(
            store, links, движок(), полки, знания,
            FormulationFactory.formulation(store, links), mapper,
            docRoutes = DocRoutes(store, документы, mapper) { шаблонФазы },
            synthesisRoutes = SynthesisRoutes(
                store, AiFactory.synthesisJobs(store, знания, служба, mapper), сверка, mapper,
            ),
            reconcileRoutes = ReconcileRoutes(store, сверка, знания, mapper),
            researchRoutes = ResearchRoutes(store, исследование, mapper),
            verifyRoutes = VerifyRoutes(store, документы, проверка, mapper),
        )
    }

    /** Сборка стенда прохода ПМИ-5: новых маршрутов не передано ни одного. */
    private val староеСобрание: V2Router by lazy {
        V2Router(
            store, links, движок(), полки, знания,
            FormulationFactory.formulation(store, links), mapper,
            docRoutes = DocRoutes(store, документы, mapper) { шаблонФазы },
        )
    }

    private val сПолем = "PJ-9801"
    private val безПоля = "PJ-9802"
    private val отчёт = "mcreport"

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        KnowledgeFlag.newProjectsDefault = false
        проект(сПолем, поле = true)
        проект(безПоля, поле = false)
    }

    private fun проект(код: String, поле: Boolean) {
        val документ = mapper.createObjectNode().put("name", "Проект $код")
        // Проект прохода ПМИ-5 поля не имеет ВОВСЕ: отсутствие и есть «выключено».
        if (поле) документ.put("knowledge_v2", true)
        store.create(код, "project", Area.Project(код), "1", документ, Provenance(Channel.MANUAL, "тест"))
    }

    private fun п(проект: String) = mapOf("project" to проект)

    /**
     * Документ с одним тезисом — заводится ПОРТОМ, а не сценами: ворота
     * сверки на проекте с флагом отбивают ручной ввод нужд без сверки, и
     * проводка тут ни при чём.
     */
    private fun сТезисом(проект: String) {
        документы.ensure(проект, отчёт, "Иванов И.")
        документы.addStatement(
            проект, отчёт, "§1",
            "Ожидаемый охват перевозок — 97 %.",
            emptyList(), "Иванов И.",
        )
    }

    /** Адреса, появившиеся в проводке шага 20. Метод, путь, ожидаемый код. */
    private fun новыеАдреса(): List<Triple<String, String, Int>> = listOf(
        Triple("GET", "/v2/ontology/formation", 200),
        Triple("GET", "/v2/synthesis/runs", 200),
        Triple("GET", "/v2/synthesis/diff", 200),
        Triple("GET", "/v2/synthesis/pending", 200),
        Triple("GET", "/v2/reconcile", 200),
        Triple("GET", "/v2/research", 200),
        Triple("GET", "/v2/documents/$отчёт/verification", 200),
    )

    // --- мера ---------------------------------------------------------------

    @Test
    fun `собранный роутер отвечает на все адреса поля знаний v2`() {
        сТезисом(сПолем)
        новыеАдреса().forEach { (метод, путь, код) ->
            val ответ = assertNotNull(
                роутер.handle(метод, путь, п(сПолем), null),
                "адрес «$путь» не доведён до обработчика — маршрут не проведён в роутере",
            )
            assertEquals(код, ответ.code, "«$путь» ответил не тем кодом: ${ответ.body}")
        }
    }

    @Test
    fun `онтология формирования приходит с версией и восемью понятиями`() {
        val ответ = роутер.handle("GET", "/v2/ontology/formation", п(сПолем), null)!!
        assertEquals(200, ответ.code)
        assertTrue(
            ответ.body.path("version").asText().isNotBlank(),
            "версия онтологии — отпечаток истины YAML: без неё запуск синтеза ссылается в пустоту",
        )
        assertEquals(8, ответ.body.path("concepts").size(), "понятий восемь: ${ответ.body.path("concepts")}")
    }

    @Test
    fun `верификация заводится через роутер и отчёт читается по своему коду`() {
        сТезисом(сПолем)
        val заведён = роутер.handle(
            "POST", "/v2/documents/$отчёт/verify", п(сПолем), """{"author":"Иванов И."}""",
        )!!
        assertEquals(201, заведён.code, заведён.body.toString())
        val код = заведён.body.path("code").asText()
        assertTrue(код.startsWith("VR-"), "отчёт получает свой код: $код")

        val один = assertNotNull(
            роутер.handle("GET", "/v2/verification/$код", п(сПолем), null),
            "адрес отчёта не проведён в роутере",
        )
        assertEquals(200, один.code)
        assertEquals(код, один.body.path("code").asText())
    }

    @Test
    fun `базирование на проекте с полем приносит сводку отчёта в своём теле`() {
        сТезисом(сПолем)
        val линия = роутер.handle(
            "POST", "/v2/documents/$отчёт/baseline", п(сПолем),
            """{"name":"внутренний обзор","author":"Иванов И."}""",
        )!!
        // Код и поля снимка прежние до буквы: проверка ДОБАВЛЯЕТ ключ, а не
        // подменяет ответ базирования.
        assertEquals(201, линия.code, линия.body.toString())
        assertEquals("внутренний обзор", линия.body.path("name").asText())
        assertTrue(линия.body.path("elements").asInt() > 0, "снимок непуст")

        val сводка = линия.body.path("verification")
        assertTrue(сводка.isObject, "после базирования отчёт заводится сам: ${линия.body}")
        assertTrue(сводка.path("code").asText().startsWith("VR-"), сводка.toString())
        assertTrue(сводка.path("summary").asText().isNotBlank(), "сводка говорит словами: $сводка")
        assertEquals(
            "внутренний обзор", сводка.path("baseline_name").asText(),
            "отчёт знает, против какой линии проверял",
        )
    }

    // --- отказные -----------------------------------------------------------

    @Test
    fun `старая сборка новых адресов не знает и не падает`() {
        сТезисом(сПолем)
        новыеАдреса().forEach { (метод, путь) ->
            // Проект С ПОЛЕМ: дело не во флаге, а в том, что маршрут не передан.
            assertNull(
                староеСобрание.handle(метод, путь, п(сПолем), null),
                "стенд без новых маршрутов обязан молчать на «$путь», как на выдуманном пути",
            )
        }
        assertNull(
            староеСобрание.handle("POST", "/v2/synthesis/runs", п(сПолем), """{"author":"Иванов И."}"""),
        )
        assertNull(
            староеСобрание.handle("POST", "/v2/reconcile", п(сПолем), """{"author":"Иванов И."}"""),
        )
    }

    @Test
    fun `старая сборка базирует документ без единой новой записи`() {
        сТезисом(сПолем)
        val линия = староеСобрание.handle(
            "POST", "/v2/documents/$отчёт/baseline", п(сПолем),
            """{"name":"внутренний обзор","author":"Иванов И."}""",
        )!!
        assertEquals(201, линия.code, линия.body.toString())
        assertFalse(
            линия.body.has("verification"),
            "проверки в старой сборке нет вовсе — базирование отвечает в точности как прежде",
        )
        assertTrue(
            store.list(Area.Project(сПолем), "verification_report").isEmpty(),
            "ни одного отчёта: автоотчёт заводит проводка, а её в старой сборке нет",
        )
    }

    @Test
    fun `на проекте без флага каждый новый адрес отказывает словами`() {
        сТезисом(безПоля)
        новыеАдреса().forEach { (метод, путь) ->
            val ответ = assertNotNull(
                роутер.handle(метод, путь, п(безПоля), null),
                "«$путь» обязан ОТВЕТИТЬ отказом, а не промолчать: молчание читается как 404",
            )
            assertEquals(409, ответ.code, "«$путь» на проекте прохода: ${ответ.body}")
            assertTrue(
                ответ.body.path("what_to_do").asText().isNotBlank(),
                "отказ обязан сказать, что делать: ${ответ.body}",
            )
        }
    }

    @Test
    fun `базирование на проекте прохода не заводит отчёта`() {
        сТезисом(безПоля)
        val линия = роутер.handle(
            "POST", "/v2/documents/$отчёт/baseline", п(безПоля),
            """{"name":"внутренний обзор","author":"Иванов И."}""",
        )!!
        assertEquals(201, линия.code, линия.body.toString())
        assertFalse(
            линия.body.has("verification"),
            "поле знаний v2 выключено — базирование идёт в точности как прежде: ${линия.body}",
        )
        assertTrue(
            store.list(Area.Project(безПоля), "verification_report").isEmpty(),
            "на проекте прохода базирование не прирастает ни одной записью",
        )
    }

    @Test
    fun `проводка не зовёт модель ни на одном из новых адресов`() {
        сТезисом(сПолем)
        новыеАдреса().forEach { (метод, путь) -> роутер.handle(метод, путь, п(сПолем), null) }
        роутер.handle("POST", "/v2/documents/$отчёт/verify", п(сПолем), """{"author":"Иванов И."}""")
        assertTrue(
            store.list(Area.Project(сПолем), "ai_call").isEmpty(),
            "журнал ИИ пуст: чтение дифа, онтологии, исследования и верификации токенов не стоит",
        )
    }
}
