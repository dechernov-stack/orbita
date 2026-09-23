// Библиотека (экран 10 задания шипа 2): каталог полок и окно взятия.
//
// До шипа 2 экран «Библиотека» умел одно — перечислить виды полок и число
// записей; взять с полки было нечем, и владелец назвал это прямо: «с любой
// полки можно взять». Здесь проверено ровно это: каталог считает содержимое,
// версию и уже взятое; окно взятия говорит, что будет взято и что станет
// доступно; плоская полка кладётся копией с истоком, а повтор не удваивает;
// полка без механизма взятия отказывает СЛОВАМИ, а не молчит.
package orbita.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.api.internal.AcrossRoutes
import orbita.formulation.api.FormulationFactory
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.Provenance
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

class LibraryTakeTest {
    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val проект = "PJ-9810"
    private val область = Area.Project(проект)
    private val провенанс = Provenance(Channel.MANUAL, "Чернов Д.")
    private val q = mapOf("project" to проект)

    private val маршруты: AcrossRoutes by lazy {
        val шаблон = mapper.readTree(
            TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые/ШАБЛОН-ФАЗЫ-PRE-A-NASA.json").toFile(),
        )
        val движок = ProcessFactory.engine(
            template = { шаблон },
            evaluator = ReadinessFactory.gateEvaluator(store, links, scenesDone = { emptySet() }, gatesPassed = { emptySet() }),
            passedGates = { mutableSetOf() }, gatePlan = { emptyMap() },
        )
        AcrossRoutes(
            store, links, движок, LibraryFactory.shelves(store) { шаблон },
            KnowledgeFactory.intake(store, links, mapper),
            FormulationFactory.formulation(store, links), mapper,
        )
    }

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        store.create(
            проект, "project", область, "1",
            mapper.readTree("""{"name":"Библиотека","standard":"NASA-7120","phase_current":"Pre-Phase A"}"""),
            провенанс,
        )
    }

    /** Каркас состава: полка-контейнер со своим правилом взятия. */
    private fun каркас() = store.create(
        "PBS-9810", "pbs_template", Area.Library, null,
        mapper.readTree(
            """{"title":"Каркас состава НОО-IoT","version":3,
            "rules":["взятие создаёт уровни 0–2 всегда, 3–5 — по классу миссии"],
            "nodes":[
              {"code":"SYS","parent":null,"name":"Система","kind":"system","level":0},
              {"code":"SEG-SP","parent":"SYS","name":"Космический сегмент","kind":"segment","level":1},
              {"code":"SC","parent":"SEG-SP","name":"Космический аппарат","kind":"element","level":2},
              {"code":"OBC","parent":"SC","name":"Бортовая машина","kind":"unit","level":3}]}""",
        ),
        провенанс,
    )

    /** Типовые стыки: их стороны — коды узлов каркаса. */
    private fun стыки() = store.create(
        "IFT-9810", "interface_template", Area.Library, null,
        mapper.readTree(
            """{"title":"Типовые стыки","version":1,"interfaces":[
              {"code":"IF-A","name":"Радиолиния","type":"RF","a":"SC","b":"SEG-SP","direction":"двусторонняя","requirement_classes":["интерфейсные"]},
              {"code":"IF-B","name":"Питание машины","type":"power","a":"OBC","b":"SC","direction":"односторонняя","requirement_classes":["интерфейсные"]}]}""",
        ),
        провенанс,
    )

    /** Рамки Р — плоская полка: каждая запись отдельной карточкой. */
    private fun рамки(vararg коды: String) = коды.forEach { код ->
        store.create(
            код, "constraint", Area.Library, null,
            mapper.readTree("""{"code":"$код","type":"technical","statement":"ограничение $код класса миссии"}"""),
            провенанс,
        )
    }

    private fun каталог() = assertNotNull(маршруты.handle("GET", "/v2/library/catalog", q, null)).body.path("items")

    private fun полка(вид: String) = каталог().first { it.path("kind").asText() == вид }

    @Test
    fun `каталог полок считает записи, версию и уже взятое`() {
        каркас()
        рамки("Р1", "Р2", "Р3")
        // Одна рамка уже в проекте: строка каталога обязана это показать —
        // иначе «взять» предлагается там, где брать нечего.
        store.create(
            "Р2", "constraint", область, "5",
            mapper.readTree("""{"code":"Р2","type":"technical","statement":"ограничение Р2 класса миссии"}"""),
            Provenance(Channel.SHELF, "Чернов Д.", source = "constraint"),
        )

        val каркасСтрока = полка("pbs_template")
        assertEquals("Каркас состава НОО-IoT", каркасСтрока.path("title").asText(), каркасСтрока.toString())
        assertEquals(4, каркасСтрока.path("count").asInt(), "число записей — размер содержимого полки-контейнера")
        assertEquals(3, каркасСтрока.path("version").asInt(), "версия — полки, а не записи")
        assertEquals("узлы", каркасСтрока.path("what").asText(), "чем полка наполнена — словом истины схем")
        assertEquals(0, каркасСтрока.path("taken").asInt())
        assertTrue(каркасСтрока.path("takeable").asBoolean(), каркасСтрока.toString())

        val рамкиСтрока = полка("constraint")
        assertEquals(3, рамкиСтрока.path("count").asInt(), "плоская полка: записей столько, сколько карточек")
        assertEquals(1, рамкиСтрока.path("taken").asInt(), "взятое считается по коду и по истоку: $рамкиСтрока")
        assertTrue(рамкиСтрока.path("takeable").asBoolean(), рамкиСтрока.toString())
    }

    @Test
    fun `пустая полка не строка каталога, а одна строка с причиной`() {
        каркас()
        val ответ = assertNotNull(маршруты.handle("GET", "/v2/library/catalog", q, null))
        assertEquals(1, ответ.body.path("items").size(), "полки без записей строками не занимают экран")
        val пустые = ответ.body.path("empty")
        assertTrue(пустые.size() > 1, "пустые полки названы одной строкой: $пустые")
        assertTrue(
            пустые.any { "Рамки" in it.asText() },
            "пустая полка называется по-русски, а не кодом вида: $пустые",
        )
        assertTrue("поставк" in ответ.body.path("empty_note").asText(), ответ.body.path("empty_note").asText())
    }

    @Test
    fun `окно взятия отмечает рекомендованное правилом полки, а взятое показывает без чекбокса`() {
        каркас()
        store.create(
            "SYS", "component", область, "7",
            mapper.readTree("""{"name":"Система","level":0,"nature":"node","kind":"system"}"""),
            Provenance(Channel.SHELF, "инженер", source = "PBS-9810"),
        )

        val окно = assertNotNull(
            маршруты.handle("GET", "/v2/library/take/preview", q + mapOf("shelf" to "PBS-9810"), null),
        ).body
        val записи = окно.path("items").associateBy { it.path("code").asText() }
        assertEquals(4, записи.size, окно.toString())
        assertTrue(записи.getValue("SYS").path("taken").asBoolean(), "узел уже в проекте — «есть в проекте», без чекбокса")
        assertTrue(записи.getValue("SC").path("recommended").asBoolean(), "уровни 0–2 — рекомендованный набор полки")
        assertEquals(false, записи.getValue("OBC").path("recommended").asBoolean(), "уровень 3 берётся по выбору инженера")
        assertEquals("SEG-SP", записи.getValue("SC").path("parent").asText(), "дерево строится родителем")
        assertEquals(2, окно.path("take").asInt(), "возьмём двоих: третий уже есть, четвёртый не рекомендован")
        assertEquals(1, окно.path("already").asInt())
    }

    @Test
    fun `запись без родителя в наборе — серая строка со словами «взять родителя»`() {
        каркас()
        // Инженер выбрал только глубокий узел: его родителя в проекте нет, и
        // окно обязано сказать, что взять сначала, а не молча создать сироту.
        val сухой = assertNotNull(
            маршруты.handle(
                "POST", "/v2/library/take", q,
                """{"shelf":"PBS-9810","items":["OBC"],"dry_run":true}""",
            ),
        ).body
        val узел = сухой.path("items").first { it.path("code").asText() == "OBC" }
        assertEquals(1, узел.path("needs").size(), узел.toString())
        assertEquals("SC", узел.path("needs").first().path("code").asText())
        assertEquals("Космический аппарат", узел.path("needs").first().path("title").asText(), "родитель назван именем, не кодом")
        assertEquals(0, сухой.path("take").asInt(), "запертая запись в счёт взятия не идёт")
        assertEquals(1, сухой.path("blocked").asInt())
    }

    @Test
    fun `предпросмотр считает, что станет доступно после взятия`() {
        каркас()
        стыки()
        val окно = assertNotNull(
            маршруты.handle("GET", "/v2/library/take/preview", q + mapOf("shelf" to "PBS-9810"), null),
        ).body
        // Уровни 0–2 рекомендованы: со взятием SC и SEG-SP становится доступен
        // типовой стык IF-A (обе стороны в проекте), а IF-B ждёт узла OBC.
        val станут = окно.path("unlocks").associate { it.path("kind").asText() to it.path("count").asInt() }
        assertEquals(1, станут["interface"], "считает сервер, а не экран: $станут")
    }

    @Test
    fun `взятие плоской полки копирует записи с истоком, а повтор не удваивает`() {
        рамки("Р1", "Р2")
        val взято = assertNotNull(
            маршруты.handle(
                "POST", "/v2/library/take", q,
                """{"shelf":"constraint","items":["Р1","Р2"],"author":"Иванов И."}""",
            ),
        )
        assertEquals(201, взято.code, взято.body.toString())
        assertEquals(2, взято.body.path("created").asInt(), взято.body.toString())

        val вПроекте = store.list(область, "constraint").associateBy { it.code }
        assertEquals(setOf("Р1", "Р2"), вПроекте.keys)
        assertEquals("constraint", вПроекте.getValue("Р1").provenance.source, "исток записи — код полки")
        assertEquals(Channel.SHELF, вПроекте.getValue("Р1").provenance.channel)
        assertEquals("Иванов И.", вПроекте.getValue("Р1").provenance.author, "автор — человек, канал — полка")
        assertEquals("ограничение Р1 класса миссии", вПроекте.getValue("Р1").doc.path("statement").asText())

        val повтор = assertNotNull(
            маршруты.handle(
                "POST", "/v2/library/take", q,
                """{"shelf":"constraint","items":["Р1","Р2"],"author":"Иванов И."}""",
            ),
        )
        assertEquals(0, повтор.body.path("created").asInt(), "повтор ничего не удваивает: ${повтор.body}")
        assertEquals(2, повтор.body.path("already").asInt())
        assertEquals(2, store.list(область, "constraint").size, "в проекте по-прежнему две рамки")
    }

    @Test
    fun `полка с готовым механизмом и полка без взятия отказывают словами`() {
        каркас()
        стыки()
        // Каркас берётся своим механизмом (сцена 7): маршрут библиотеки не
        // делает его работу второй раз, а называет, кто её делает.
        val каркасОтказ = assertFailsWith<IllegalArgumentException> {
            маршруты.handle("POST", "/v2/library/take", q, """{"shelf":"PBS-9810","items":["SYS"]}""")
        }
        assertTrue("каркас" in каркасОтказ.message.orEmpty(), каркасОтказ.message.orEmpty())

        // У стыков механизма нет вовсе — и строка каталога обязана нести
        // причину словами, чтобы запертая кнопка не молчала.
        val стыкиСтрока = полка("interface_template")
        assertEquals(false, стыкиСтрока.path("takeable").asBoolean(), стыкиСтрока.toString())
        assertTrue(стыкиСтрока.path("why").asText().length > 20, "причина — фразой: ${стыкиСтрока.path("why").asText()}")
        val стыкиОтказ = assertFailsWith<IllegalArgumentException> {
            маршруты.handle("POST", "/v2/library/take", q, """{"shelf":"IFT-9810","items":["IF-A"]}""")
        }
        assertEquals(стыкиСтрока.path("why").asText(), стыкиОтказ.message, "отказ и подсказка кнопки — одни слова")

        // Предпросмотр у запертой полки работает: содержимое видно, взятия нет.
        val окно = assertNotNull(
            маршруты.handle("GET", "/v2/library/take/preview", q + mapOf("shelf" to "IFT-9810"), null),
        ).body
        assertEquals(2, окно.path("items").size(), окно.toString())
        assertEquals(false, окно.path("takeable").asBoolean())
    }

    @Test
    fun `полки, которой нет, маршрут не придумывает`() {
        каркас()
        val отказ = assertFailsWith<NoSuchElementException> {
            маршруты.handle("GET", "/v2/library/take/preview", q + mapOf("shelf" to "НЕТ-ТАКОЙ"), null)
        }
        assertTrue("полки" in отказ.message.orEmpty(), отказ.message.orEmpty())
    }
}
