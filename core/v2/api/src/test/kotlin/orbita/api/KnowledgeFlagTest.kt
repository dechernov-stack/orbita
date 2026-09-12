// Флаг поля знаний v2 — признак ПРОЕКТА, а не стенда (план «Знания v2», шаг 3).
//
// Один стенд держит проект прохода ПМИ-5 и новый проект одновременно, поэтому
// проверяется не «переменная прочиталась», а то, ради чего флаг заведён:
// проект без поля живёт по-прежнему, умолчание стенда не переписывает явный
// выбор в теле, флаг соседнего проекта не протекает, руками на месте флаг не
// переключается, и на проекте без флага ввод сохраняется как до перестройки.
package orbita.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.api.internal.V2Router
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.KnowledgeFlag
import orbita.kernel.api.Provenance
import orbita.library.api.LibraryFactory
import orbita.process.api.ProcessFactory
import orbita.readiness.api.ReadinessFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KnowledgeFlagTest {

    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val полки = TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые")
    private val шаблон = mapper.readTree(полки.resolve("ШАБЛОН-ФАЗЫ-PRE-A-NASA.json").toFile())

    private fun router(): V2Router {
        val движок = ProcessFactory.engine(
            template = { шаблон },
            evaluator = ReadinessFactory.gateEvaluator(
                store, links, scenesDone = { emptySet() }, gatesPassed = { emptySet() },
            ),
            passedGates = { mutableSetOf() }, gatePlan = { emptyMap() },
        )
        return V2Router(
            store, links, движок, LibraryFactory.shelves(store) { шаблон },
            orbita.knowledge.api.KnowledgeFactory.intake(store, links, mapper),
            orbita.formulation.api.FormulationFactory.formulation(store, links), mapper,
        )
    }

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        KnowledgeFlag.newProjectsDefault = false
    }

    // Умолчание стенда — состояние процесса: оставить его включённым значит
    // молча переключить поведение соседним тестам в той же сборке.
    @AfterTest
    fun вернутьУмолчание() {
        KnowledgeFlag.newProjectsDefault = false
    }

    @Test
    fun `проект прохода живёт без поля знаний v2 — флага нет`() {
        val ответ = router().handle(
            "POST", "/v2/projects", emptyMap(),
            """{"name":"Проход ПМИ-5","code":"PJ-9330","author":"Чернов Д."}""",
        )!!
        assertEquals(201, ответ.code, ответ.body.toString())
        assertFalse(ответ.body.path("knowledge_v2").asBoolean(false), "умолчание стенда выключено")
        assertFalse(KnowledgeFlag.on(store, "PJ-9330"))

        // Проект, заведённый ДО перестройки, поля не имеет вовсе — и это тоже «выключено»
        store.create(
            "PJ-9331", "project", Area.Project("PJ-9331"), "1",
            mapper.createObjectNode().put("name", "Прежний проект"),
            Provenance(Channel.MANUAL, "Чернов Д."),
        )
        assertFalse(KnowledgeFlag.on(store, "PJ-9331"), "поля нет — развилки не срабатывают")
        assertFalse(KnowledgeFlag.on(store, "PJ-0000"), "проекта нет вовсе — флаг не падает, а молчит")
    }

    @Test
    fun `умолчание стенда включает поле знаний новому проекту`() {
        KnowledgeFlag.newProjectsDefault = true
        val ответ = router().handle(
            "POST", "/v2/projects", emptyMap(),
            """{"name":"Новый проект","code":"PJ-9332","author":"Чернов Д."}""",
        )!!
        assertTrue(ответ.body.path("knowledge_v2").asBoolean(false), "ответ несёт признак сразу")
        assertTrue(KnowledgeFlag.on(store, "PJ-9332"))

        val карточка = router().handle("GET", "/v2/projects", emptyMap(), null)!!
            .body.path("items").single { it.path("code").asText() == "PJ-9332" }
        assertTrue(
            карточка.path("knowledge_v2").asBoolean(false),
            "экран узнаёт о поле знаний из карточки проекта, а не из стенда",
        )
    }

    @Test
    fun `явный выбор в теле сильнее умолчания стенда`() {
        KnowledgeFlag.newProjectsDefault = true
        // Отказ умолчанию: на стенде с включённым умолчанием проект заводится
        // выключенным — иначе прежнее поведение нечем воспроизвести.
        router().handle(
            "POST", "/v2/projects", emptyMap(),
            """{"name":"Повтор прохода","code":"PJ-9333","knowledge_v2":false}""",
        )
        assertFalse(KnowledgeFlag.on(store, "PJ-9333"), "тело выключило поле, умолчание его не вернуло")

        KnowledgeFlag.newProjectsDefault = false
        router().handle(
            "POST", "/v2/projects", emptyMap(),
            """{"name":"Проверка меры","code":"PJ-9334","knowledge_v2":true}""",
        )
        assertTrue(KnowledgeFlag.on(store, "PJ-9334"), "новый порядок включается на отдельном проекте")
    }

    @Test
    fun `флаг соседнего проекта на проект прохода не переносится`() {
        val r = router()
        r.handle("POST", "/v2/projects", emptyMap(), """{"name":"С полем","code":"PJ-9335","knowledge_v2":true}""")
        r.handle("POST", "/v2/projects", emptyMap(), """{"name":"Без поля","code":"PJ-9336"}""")
        assertTrue(KnowledgeFlag.on(store, "PJ-9335"))
        assertFalse(KnowledgeFlag.on(store, "PJ-9336"), "один стенд держит оба порядка сразу")
    }

    @Test
    fun `флаг не переключается правкой на месте`() {
        val r = router()
        r.handle("POST", "/v2/projects", emptyMap(), """{"name":"Проход ПМИ-5","code":"PJ-9337"}""")
        val ошибка = assertFailsWith<IllegalArgumentException> {
            r.handle(
                "PATCH", "/v2/entities/PJ-9337", mapOf("project" to "PJ-9337"),
                """{"fields":{"knowledge_v2":true},"author":"Иванов И."}""",
            )
        }
        assertTrue("на месте не правятся" in ошибка.message!!, ошибка.message)
        val паспорт = store.byCode(Area.Project("PJ-9337"), "PJ-9337")!!
        assertEquals(1, паспорт.version, "отказ не плодит версию паспорта")
        assertFalse(KnowledgeFlag.on(store, "PJ-9337"), "поведение проекта прохода руками не меняется")
    }

    @Test
    fun `на проекте без флага нужда сохраняется по-прежнему`() {
        val r = router()
        r.handle("POST", "/v2/projects", emptyMap(), """{"name":"Проход ПМИ-5","code":"PJ-9338"}""")
        val п = mapOf("project" to "PJ-9338")
        val сторона = r.handle("POST", "/v2/stakeholders", п, """{"name":"Минтранс России","role":"customer"}""")!!
        assertEquals(201, сторона.code)
        // Ни слова о сверке в теле: так ввод приходит на проекте прохода
        val нужда = r.handle(
            "POST", "/v2/needs", п,
            """{"statement":"Непрерывный мониторинг вне наземного покрытия","owner":"${сторона.body.path("code").asText()}"}""",
        )!!
        assertEquals(201, нужда.code, нужда.body.toString())
        val запись = store.byCode(Area.Project("PJ-9338"), нужда.body.path("code").asText())
        assertTrue(запись != null, "нужда сохранена без сверки — прежний порядок цел")
    }

    @Test
    fun `умолчание стенда включается только явной единицей`() {
        assertTrue(KnowledgeFlag.defaultFrom("1"))
        assertTrue(KnowledgeFlag.defaultFrom(" 1 "), "пробелы вокруг значения не меняют смысла")
        // Отказ всему остальному: забытая или полупустая переменная не имеет
        // права включить новое поведение на стенде владельца молча.
        assertFalse(KnowledgeFlag.defaultFrom(null))
        assertFalse(KnowledgeFlag.defaultFrom(""))
        assertFalse(KnowledgeFlag.defaultFrom("0"))
        assertFalse(KnowledgeFlag.defaultFrom("true"))
        assertFalse(KnowledgeFlag.defaultFrom("да"))
    }
}
