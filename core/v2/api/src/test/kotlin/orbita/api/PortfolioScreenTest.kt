// Экраны 2 и 3 шипа 2 («Проекты» и «Новый проект») со стороны сервера.
//
// Строка-карточка портфеля отвечает на один вопрос — «куда идти»: группа
// (рабочий проект или пример), ближайшая непройденная точка с датой и числом
// блокирующих, день последней правки записей проекта, руководитель. Всё это
// считает СЕРВЕР: на клиенте вердиктов из сравнения величин нет (сторож
// tools/validate_web_no_math.py), значит число блокирующих и день активности
// обязаны приходить готовыми.
//
// Форма «Новый проект» присылает даты трёх точек фазы. Даты ложатся в САМИ
// точки — оттуда их читает паспорт, и двум датам разойтись негде. Дата точки,
// которой в шаблоне фазы нет, — отказ словами, и проект от такого отказа
// заведённым не остаётся.
package orbita.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.api.internal.V2Router
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.LinkRegistry
import orbita.library.api.LibraryFactory
import orbita.process.api.ProcessFactory
import orbita.readiness.api.ReadinessFactory
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortfolioScreenTest {

    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links: LinkRegistry = KernelFactory.linkRegistry(TestDbV2.conn)
    private val пройденные = mutableMapOf<String, MutableSet<String>>()

    private val шаблон = mapper.readTree(
        TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые/ШАБЛОН-ФАЗЫ-PRE-A-NASA.json").toFile(),
    )

    private val router: V2Router by lazy {
        val движок = ProcessFactory.engine(
            template = { шаблон },
            evaluator = ReadinessFactory.gateEvaluator(
                store, links,
                scenesDone = { emptySet() },
                gatesPassed = { p -> пройденные.getOrPut(p) { mutableSetOf() } },
            ),
            passedGates = { p -> пройденные.getOrPut(p) { mutableSetOf() } },
            outputCounter = { проект, вид ->
                store.list(Area.Project(проект), вид)
                    .count { вид != "intent" || it.status == "accepted" }
            },
            // Даты точек живут в самих точках: план и форма их задают, движок
            // читает оттуда же — как на стенде (Boundary).
            gatePlan = { проект ->
                store.list(Area.Project(проект), "gate")
                    .associate { it.code to it.doc.path("planned_date").asText("") }
                    .filterValues { it.isNotBlank() }
            },
        )
        V2Router(
            store, links, движок,
            LibraryFactory.shelves(store) { шаблон },
            orbita.knowledge.api.KnowledgeFactory.intake(store, links, mapper),
            orbita.formulation.api.FormulationFactory.formulation(store, links),
            mapper,
        )
    }

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        пройденные.clear()
    }

    private val ДАТА = Regex("""\d{4}-\d{2}-\d{2}""")

    @Test
    fun `портфель отдаёт группу, ближайшую точку с блокирующими, день активности и руководителя`() {
        router.handle(
            "POST", "/v2/projects", emptyMap(),
            """{"name":"Национальная система IoT","code":"PJ-9200","manager":"Чернов Д.","author":"Чернов Д."}""",
        )
        router.handle(
            "POST", "/v2/projects", emptyMap(),
            """{"name":"Пример: система ДЗЗ","code":"PJ-9201","group":"example","manager":"Иванов И.","author":"Иванов И."}""",
        )
        val строки = router.handle("GET", "/v2/projects", emptyMap(), null)!!.body.path("items")
        assertEquals(2, строки.size())

        val рабочий = строки.single { it.path("code").asText() == "PJ-9200" }
        assertEquals("work", рабочий.path("group").asText(), "рабочая колонка — группа «work» истины схем")
        assertEquals("Национальная система IoT", рабочий.path("name").asText())
        assertEquals("Чернов Д.", рабочий.path("manager").asText(), "руководитель — именем истины")
        assertEquals("Чернов Д.", рабочий.path("lead").asText(), "прежнее имя поля живо: шапка и старые экраны его читают")
        assertTrue(рабочий.path("phase").asText().isNotBlank(), "фаза остаётся в строке")

        val примером = строки.single { it.path("code").asText() == "PJ-9201" }
        assertEquals("example", примером.path("group").asText(), "колонка примеров — та же группа, не догадка клиента")

        // Ближайшая точка — первая непройденная: с датой и числом блокирующих.
        val точка = рабочий.path("gate")
        assertEquals("internal_review", точка.path("key").asText())
        assertEquals("Внутренний обзор", точка.path("title").asText())
        assertTrue(ДАТА.matches(точка.path("planned_date").asText()), "дата точки — ГГГГ-ММ-ДД: ${точка.path("planned_date").asText()}")
        assertTrue(точка.path("blocking").asInt() > 0, "пустой проект держит точку: клиент считать это не вправе")

        assertTrue(
            ДАТА.matches(рабочий.path("last_activity").asText()),
            "день последней правки записей проекта: ${рабочий.path("last_activity").asText()}",
        )
    }

    @Test
    fun `заведение с датами точек кладёт их в точки, и паспорт показывает те же`() {
        val старт = LocalDate.of(2026, 9, 23)
        val даты = listOf(
            "internal_review" to старт.plusDays(24),
            "MCR" to старт.plusDays(72),
            "KDP-A" to старт.plusDays(88),
        )
        val тело = """{"name":"Проект с датами","code":"PJ-9202","manager":"Чернов Д.","group":"work","gate_dates":[""" +
            даты.joinToString(",") { (ключ, дата) -> """{"gate":"$ключ","date":"$дата"}""" } + "]}"
        val ответ = router.handle("POST", "/v2/projects", emptyMap(), тело)!!
        assertEquals(201, ответ.code)
        assertEquals(
            даты.map { it.second.toString() },
            ответ.body.path("gates").map { it.path("planned_date").asText() },
            "фаза отвечает теми датами, что пришли формой",
        )

        val записи = listOf("internal_review", "MCR", "KDP-A").map { ключ ->
            store.byCode(Area.Project("PJ-9202"), ключ)!!.doc.path("planned_date").asText()
        }
        assertEquals(даты.map { it.second.toString() }, записи, "даты лежат в самих точках, а не рядом с ними")

        val паспорт = router.handle("GET", "/v2/passport", mapOf("project" to "PJ-9202"), null)!!.body
        assertEquals(
            даты.map { it.second.toString() },
            паспорт.path("gates").map { it.path("planned_date").asText() },
            "паспорт показывает те же даты: читает он точки",
        )
        assertEquals("Чернов Д.", паспорт.path("manager").asText(), "руководитель формы доезжает до паспорта и печати")

        val строка = router.handle("GET", "/v2/projects", emptyMap(), null)!!.body
            .path("items").single { it.path("code").asText() == "PJ-9202" }
        assertEquals(даты[0].second.toString(), строка.path("gate").path("planned_date").asText(), "в портфеле — дата ближайшей точки")
    }

    @Test
    fun `точка вне шаблона фазы — отказ словами, и проект не заводится`() {
        val отказ = assertFailsWith<IllegalArgumentException> {
            router.handle(
                "POST", "/v2/projects", emptyMap(),
                """{"name":"Чужая точка","code":"PJ-9203","gate_dates":[{"gate":"SRR","date":"2026-12-01"}]}""",
            )
        }
        val слова = отказ.message.orEmpty()
        assertTrue("SRR" in слова, "отказ называет точку, которой нет: $слова")
        assertTrue("Внутренний обзор" in слова, "отказ называет точки фазы словами, а не ключами: $слова")
        assertNull(store.byCode(Area.Project("PJ-9203"), "PJ-9203"), "отказ не оставляет заведённого проекта")

        val кривая = assertFailsWith<IllegalArgumentException> {
            router.handle(
                "POST", "/v2/projects", emptyMap(),
                """{"name":"Кривая дата","code":"PJ-9204","gate_dates":[{"gate":"MCR","date":"25.11.2026"}]}""",
            )
        }
        assertTrue("MCR" in кривая.message.orEmpty(), "отказ по формату даты называет точку: ${кривая.message}")
        assertNull(store.byCode(Area.Project("PJ-9204"), "PJ-9204"), "кривая дата тоже не оставляет проекта")
    }
}
