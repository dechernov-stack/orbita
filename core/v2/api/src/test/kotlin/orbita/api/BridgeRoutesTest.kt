// Мостик ведущего и рабочий лист специалиста (шип 2, экран 6) через маршруты.
//
// Проверяется то, ради чего экран делался: ведущий НЕ открывает реестры для
// обзора — маршрут, очередь решений, блокирующие ближайшей точки, команда и
// сигналы приходят одним ответом И ИЗ ДАННЫХ проекта; поручение заводится
// видом «задание» по истине схем (четыре поля, не больше) и закрывается;
// специалист видит свои поручения по сроку, просроченные первыми.
package orbita.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.api.api.Actor
import orbita.api.internal.BridgeRoutes
import orbita.api.internal.GateRecords
import orbita.api.internal.ModelRoutes
import orbita.api.internal.PointRoutes
import orbita.api.internal.V2Router
import orbita.architecture.api.ArchitectureFactory
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.LinkRegistry
import orbita.kernel.api.Provenance
import orbita.library.api.LibraryFactory
import orbita.models.api.ModelsFactory
import orbita.process.api.ProcessFactory
import orbita.programmatics.api.ProgrammaticsFactory
import orbita.readiness.api.ReadinessFactory
import orbita.requirements.api.RequirementsFactory
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BridgeRoutesTest {

    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links: LinkRegistry = KernelFactory.linkRegistry(TestDbV2.conn)
    private val шаблон = mapper.readTree(
        TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые/ШАБЛОН-ФАЗЫ-PRE-A-NASA.json").toFile(),
    )

    private val проект = "PJ-9406"
    private val п = mapOf("project" to проект)
    private val область = Area.Project(проект)
    private val провенанс = Provenance(Channel.MANUAL, "Чернов Д.")

    /** Сегодня — фиксировано: сроки и просрочку считает сервер, и тест не зависит от календаря. */
    private val сегодня = LocalDate.parse("2026-09-23")

    private val рп = Actor("chernov", "Чернов Д.", setOf("lead", "da_review"))
    private val инженер = Actor("petrova", "Петрова М.", setOf("specialist"))

    private val router: V2Router by lazy {
        val записи = GateRecords(store, mapper)
        val требования = RequirementsFactory.requirements(store, links, mapper)
        val снимки = RequirementsFactory.baselines(
            store, links, mapper,
            hardGates = orbita.knowledge.schema.GeneratedOntology.hardGatesOnly,
        )
        val архитектура = ArchitectureFactory.architecture(store, links, mapper)
        val программатика = ProgrammaticsFactory.programmatics(store, mapper)
        val проверкиТ = RequirementsFactory.gateChecks(store, снимки)
        val проверкиА = ArchitectureFactory.gateChecks(store, архитектура)
        val проверкиП = ProgrammaticsFactory.gateChecks(store, программатика)
        val движок = ProcessFactory.engine(
            template = { шаблон },
            evaluator = ReadinessFactory.gateEvaluator(
                store, links,
                scenesDone = { emptySet() },
                gatesPassed = { записи.passed(it) },
                extra = { проект, условие ->
                    проверкиТ.of(проект, условие) ?: проверкиА.of(проект, условие) ?: проверкиП.of(проект, условие)
                },
            ),
            passedGates = { записи.passed(it) },
            gatePlan = { п ->
                store.list(Area.Project(п), "gate")
                    .associate { it.code to it.doc.path("planned_date").asText("") }
                    .filterValues { it.isNotBlank() }
            },
            findings = { записи.findings(it) },
            decisions = { записи.decisions(it) },
            phaseOf = { записи.phaseOf(it) },
            onDecision = { p, т, кем, исход, помета, фаза -> записи.record(p, т, кем, исход, помета, фаза) },
        )
        V2Router(
            store, links, движок,
            LibraryFactory.shelves(store) { шаблон },
            orbita.knowledge.api.KnowledgeFactory.intake(store, links, mapper),
            orbita.formulation.api.FormulationFactory.formulation(store, links),
            mapper,
            modelRoutes = ModelRoutes(
                store, ModelsFactory.models(store, mapper), ModelsFactory.variants(store),
                ModelsFactory.impact(store, links), программатика, mapper,
            ),
            pointRoutes = PointRoutes(движок, записи, mapper),
            bridgeRoutes = BridgeRoutes(
                store, движок, mapper,
                formulation = orbita.formulation.api.FormulationFactory.formulation(store, links),
                programmatics = программатика,
                models = ModelsFactory.models(store, mapper),
                today = { сегодня },
            ),
        )
    }

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        router.handle("POST", "/v2/projects", emptyMap(), """{"name":"Мостик","code":"$проект","author":"Чернов Д."}""")
        router.handle(
            "POST", "/v2/intent", п,
            """{"for_whom":"перевозчики","what":"телеметрия","where":"СМП","horizon":"2033","accepted":true}""",
        )
        // Даты точек — планом работ фазы, как на стенде: ближайшая точка и её
        // срок берутся из данных, а не из «сегодня плюс что-нибудь».
        router.handle(
            "POST", "/v2/plan", п,
            """{"phase":"Pre-Phase A","gate_dates":[
                 {"gate":"internal_review","date":"2026-10-06"},
                 {"gate":"MCR","date":"2026-11-24"},
                 {"gate":"KDP-A","date":"2026-12-10"}],
               "author":"Чернов Д."}""",
        )
    }

    /**
     * Сторона с нуждой: без нужд матрица покрытия пуста, и сигнала «нужды с
     * покрытием» нет по правилу самого мостика — выдуманного нуля он не рисует.
     * Сигнал проверяется на данных, а не на пустоте.
     */
    private fun нуждаСтороны() {
        router.handle("POST", "/v2/stakeholders", п, """{"name":"Минтранс России","role":"customer"}""")
        val сторона = store.list(область, "stakeholder").first()
        router.handle(
            "POST", "/v2/needs", п,
            """{"statement":"единое оперативное управление транспортом","owner":"${сторона.code}"}""",
        )
    }

    private fun мостик(): JsonNode = router.handle("GET", "/v2/bridge", п, null)!!.body

    /** Открытый риск критичностью 15 (3 × 5): сигнал «риски ≥ 12» берётся из реестра. */
    private fun рискКритичный() {
        store.create(
            "RI-9001", "risk", область, "11",
            mapper.createObjectNode()
                .put("statement", "масса превышает рамку Р2")
                .put("category", "technical").put("probability", 3).put("impact", 5)
                .put("strategy", "mitigate").put("measures", "облегчение конструкции")
                .put("owner", "petrova").put("due_point", "MCR")
                .also { it.putObject("cec").put("condition", "рост состава").put("event", "перебор массы").put("consequence", "перенос запуска") },
            провенанс, status = "open",
        )
    }

    // ——— мостик ————————————————————————————————————————————————————————

    @Test
    fun `мостик отдаёт маршрут, очередь до семи строк, блокеры, команду и сигналы из данных`() {
        рискКритичный()
        нуждаСтороны()
        // Замечание обзора — строка очереди «замечание» с возвратом в сцену 2.
        router.handle(
            "POST", "/v2/points/internal_review/findings", п,
            """{"text":"замысел не называет горизонт","returns_to_scene":"2"}""", рп,
        )
        val ответ = мостик()

        val маршрут = ответ.path("route")
        assertEquals("Pre-Phase A", маршрут.path("phase").asText(), "фаза — одной строкой маршрута")
        assertTrue(маршрут.path("scene").path("key").asText().isNotBlank(), "сцена маршрута названа")
        assertTrue(маршрут.path("scene").path("title").asText().isNotBlank(), "у сцены — имя, а не код")
        assertEquals("internal_review", маршрут.path("gate").path("key").asText(), "ближайшая точка — по дате плана")
        assertEquals("2026-10-06", маршрут.path("gate").path("planned_date").asText())
        assertEquals(13, маршрут.path("days_to_gate").asInt(), "дней до точки считает сервер")
        assertTrue(маршрут.path("gate").path("blocking").asInt() > 0, "блокирующих у точки больше нуля")

        val очередь = ответ.path("decide")
        assertTrue(очередь.size() in 1..7, "очередь решений — не больше семи строк, а не весь реестр")
        очередь.forEach { строка ->
            assertTrue(строка.path("kind").asText().isNotBlank(), "у строки очереди назван род решения")
            assertTrue(строка.path("text").asText().isNotBlank(), "строка очереди говорит словами")
            assertTrue(строка.path("action").asText().isNotBlank(), "у строки очереди одно действие")
            assertTrue(строка.path("where").path("section").asText().isNotBlank(), "действие ведёт в место решения")
        }
        val замечание = очередь.firstOrNull { it.path("kind").asText() == "замечание" }
        assertNotNull(замечание, "открытое замечание обзора стоит в очереди решений")
        assertEquals("2", замечание.path("where").path("scene").asText(), "замечание ведёт в сцену возврата")
        assertTrue(
            очередь.any { it.path("kind").asText() == "точка" },
            "держащаяся точка — строка очереди «что блокирует»",
        )

        val блокеры = ответ.path("blockers")
        assertTrue(!блокеры.isEmpty, "блокирующие ближайшей точки названы словами")
        блокеры.forEach { б ->
            assertTrue(б.path("scene").asText().isNotBlank(), "у блокера названа сцена")
            assertTrue(б.path("why").asText().isNotBlank(), "у блокера названа причина")
        }

        assertTrue(ответ.path("team").isArray, "команда — перечень, пусть и пустой")

        val сигналы = ответ.path("signals")
        assertTrue(сигналы.size() in 1..4, "сигналов три-четыре, а не двадцать")
        val риски = сигналы.firstOrNull { "риски" in it.path("name").asText() }
        assertNotNull(риски, "открытый риск критичностью 15 даёт сигнал «риски ≥ 12»")
        assertEquals("1", риски.path("value").asText())
        assertTrue(риски.path("over").asBoolean(), "за рамкой — решает сервер, не экран")
        assertTrue(риски.path("where").path("section").asText().isNotBlank(), "сигнал ведёт туда, где он считается")
        val покрытие = сигналы.firstOrNull { "покрыт" in it.path("name").asText() }
        assertNotNull(покрытие, "доля нужд с покрытием — из матрицы покрытия, а не из головы")
    }

    @Test
    fun `сигналов без данных нет — пустой проект не рисует нулей`() {
        val сигналы = мостик().path("signals")
        assertTrue(
            сигналы.none { "TRL" in it.path("name").asText() },
            "технологий в проекте нет — сигнала разрывов TRL тоже нет",
        )
        assertTrue(
            сигналы.none { "масса" in it.path("name").asText() },
            "свёртки массы с рамкой нет — числа не выдумываются",
        )
    }

    // ——— поручение ————————————————————————————————————————————————————

    @Test
    fun `поручение указывает на мероприятие фазы кодом — чужой код отбивается словами`() {
        // Истина 24.09 (ОНТОЛОГИЯ-АУДИТ часть 5): «шаг» ушёл — целью поручения
        // бывает разрыв сцены либо мероприятие фазы; мероприятие 0.1 — сцены 3.
        val создано = router.handle(
            "POST", "/v2/assignments", п,
            """{"target":{"kind":"activity","ref":"0.1"},"assignee":"petrova","due_point":"internal_review",
                "what":"уточнить пользователей миссии","author":"chernov"}""", рп,
        )!!
        assertEquals(201, создано.code)
        val запись = store.byCode(область, создано.body.path("code").asText())!!
        assertEquals("activity", запись.doc.path("target").path("kind").asText())
        assertEquals("0.1", запись.doc.path("target").path("ref").asText())

        val чужое = runCatching {
            router.handle(
                "POST", "/v2/assignments", п,
                """{"target":{"kind":"activity","ref":"9.9"},"assignee":"petrova","due_point":"internal_review","author":"chernov"}""", рп,
            )
        }.exceptionOrNull()
        assertTrue(чужое is IllegalArgumentException && "мероприятия «9.9» в фазе нет" in (чужое.message ?: ""), "${чужое?.message}")
        val шаг = runCatching {
            router.handle(
                "POST", "/v2/assignments", п,
                """{"target":{"kind":"step","ref":"3"},"assignee":"petrova","due_point":"internal_review","author":"chernov"}""", рп,
            )
        }.exceptionOrNull()
        assertTrue(шаг is IllegalArgumentException, "«шаг» целью поручения не бывает: ${шаг?.message}")
    }

    @Test
    fun `поручение заводится по истине схем и закрывается`() {
        // Слова поручения берутся у САМОГО блокера мостика: выдуманная строка
        // никогда не сойдётся с тем, что держит точку на самом деле.
        val разрыв = мостик().path("blockers").first { it.path("scene").asText() == "3" }
        val причина = разрыв.path("why").asText()
        val создано = router.handle(
            "POST", "/v2/assignments", п,
            """{"target":{"kind":"gap","ref":"3"},"assignee":"petrova","due_point":"internal_review",
                "what":"$причина","author":"chernov"}""", рп,
        )!!
        assertEquals(201, создано.code)
        val код = создано.body.path("code").asText()
        assertTrue(код.isNotBlank(), "у поручения есть код")

        // Истина схем: target · assignee · due_point · assigned_by. Слова
        // поручения живут в `notes` — это поле ЯДРА, а не выдуманное поле вида.
        val запись = store.byCode(область, код)!!
        assertEquals("assignment", запись.kind)
        assertEquals(
            setOf("target", "assignee", "due_point", "assigned_by", "notes"),
            запись.doc.fieldNames().asSequence().toSet(),
            "полей вне истины у записи нет — иначе сторож записи отбил бы её",
        )
        assertEquals("gap", запись.doc.path("target").path("kind").asText())
        assertEquals("3", запись.doc.path("target").path("ref").asText())
        assertEquals("petrova", запись.doc.path("assignee").asText())
        assertEquals("internal_review", запись.doc.path("due_point").asText())
        assertEquals("open", запись.status)

        val перечень = router.handle("GET", "/v2/assignments", п, null)!!.body.path("items")
        assertEquals(1, перечень.size())
        assertEquals("2026-10-06", перечень.first().path("due_date").asText(), "срок — дата точки, а не сама точка")
        assertTrue(!перечень.first().path("overdue").asBoolean(), "срок ещё не прошёл")
        assertEquals(причина, перечень.first().path("what").asText())

        // Блокер на мостике знает о своём поручении: «Поручить» второй раз не предлагается.
        val блокер = мостик().path("blockers").firstOrNull {
            it.path("scene").asText() == "3" && it.path("why").asText() == причина
        }
        assertNotNull(блокер, "блокер, по которому дано поручение, остался на мостике")
        assertEquals(код, блокер.path("assignment").path("code").asText(), "у блокера видно поручение")

        val команда = мостик().path("team")
        assertEquals(1, команда.size())
        assertEquals("petrova", команда.first().path("assignee").asText())
        assertEquals(причина, команда.first().path("what").asText())
        assertTrue(!команда.first().path("done").asBoolean())

        val закрыто = router.handle("POST", "/v2/assignments/$код/done", п, """{"author":"petrova"}""", инженер)!!
        assertEquals(200, закрыто.code)
        assertEquals("done", закрыто.body.path("status").asText())
        assertEquals("done", store.byCode(область, код)!!.status)
        assertTrue(мостик().path("team").first().path("done").asBoolean(), "закрытое поручение видно ведущему")
    }

    @Test
    fun `поручение без исполнителя, без срока-точки и не в ту точку отбивается словами`() {
        assertFailsWith<IllegalArgumentException> {
            router.handle(
                "POST", "/v2/assignments", п,
                """{"target":{"kind":"gap","ref":"3"},"due_point":"internal_review","author":"chernov"}""", рп,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            router.handle(
                "POST", "/v2/assignments", п,
                """{"target":{"kind":"gap","ref":"3"},"assignee":"petrova","author":"chernov"}""", рп,
            )
        }
        // Срок — точка ФАЗЫ: выдуманная точка не становится сроком молча.
        val беда = assertFailsWith<IllegalArgumentException> {
            router.handle(
                "POST", "/v2/assignments", п,
                """{"target":{"kind":"gap","ref":"3"},"assignee":"petrova","due_point":"PDR","author":"chernov"}""", рп,
            )
        }
        assertTrue("PDR" in (беда.message ?: ""), "отказ называет точку, которой в фазе нет")
        // Цель поручения — из перечня истины (gap · step), третьего вида нет.
        assertFailsWith<IllegalArgumentException> {
            router.handle(
                "POST", "/v2/assignments", п,
                """{"target":{"kind":"сцена","ref":"3"},"assignee":"petrova","due_point":"MCR","author":"chernov"}""", рп,
            )
        }
    }

    // ——— моя работа ————————————————————————————————————————————————————

    @Test
    fun `моя работа сортирует поручения по сроку и ставит просроченные первыми`() {
        // Просроченная точка-веха: дата в прошлом относительно «сегодня» теста.
        store.create(
            "TRL-9001", "gate", область, "10",
            mapper.createObjectNode().put("phase", "Pre-Phase A").put("key", "TRL-9001")
                .put("title", "TRL 5 достигнут").put("kind", "technology").put("planned_date", "2026-09-10"),
            провенанс, status = "planned",
        )
        router.handle(
            "POST", "/v2/assignments", п,
            """{"target":{"kind":"gap","ref":"5"},"assignee":"petrova","due_point":"MCR",
                "what":"Р2 без машинной границы","author":"chernov"}""", рп,
        )
        router.handle(
            "POST", "/v2/assignments", п,
            """{"target":{"kind":"gap","ref":"3"},"assignee":"petrova","due_point":"TRL-9001",
                "what":"у 4 сторон нет нужд","author":"chernov"}""", рп,
        )
        router.handle(
            "POST", "/v2/assignments", п,
            """{"target":{"kind":"gap","ref":"4"},"assignee":"ivanov","due_point":"internal_review",
                "what":"показателей у целей нет","author":"chernov"}""", рп,
        )

        val моя = router.handle("GET", "/v2/mywork", п + mapOf("login" to "petrova"), null)!!.body
        val поручения = моя.path("assignments")
        assertEquals(2, поручения.size(), "чужие поручения специалист не видит")
        assertEquals("2026-09-10", поручения[0].path("due_date").asText(), "просроченное — первым")
        assertTrue(поручения[0].path("overdue").asBoolean(), "просрочку считает сервер")
        assertEquals("2026-11-24", поручения[1].path("due_date").asText())
        assertTrue(!поручения[1].path("overdue").asBoolean())
        assertTrue(
            поручения[0].path("what_blocks").isArray,
            "«что мешает» — живые разрывы сцены поручения, а не запись годовой давности",
        )
        assertEquals("3", моя.path("open_activity").path("scene").asText(), "открыто мероприятие сцены поручения")
        assertTrue(моя.path("open_activity").path("activity").path("code").asText().isNotBlank())

        // Закрытое поручение из рабочего листа уходит: специалист не пишет отчётов.
        val код = поручения[0].path("code").asText()
        router.handle("POST", "/v2/assignments/$код/done", п, null, инженер)
        val после = router.handle("GET", "/v2/mywork", п + mapOf("login" to "petrova"), null)!!.body
        assertEquals(1, после.path("assignments").size())
        assertEquals("2026-11-24", после.path("assignments")[0].path("due_date").asText())
    }

    @Test
    fun `рабочий лист без поручений не открывает мероприятия и говорит словами`() {
        val моя = router.handle("GET", "/v2/mywork", п + mapOf("login" to "petrova"), null)!!.body
        assertEquals(0, моя.path("assignments").size())
        assertTrue(моя.path("open_activity").isNull, "нет поручения — нет и открытого мероприятия")
        assertTrue(моя.path("note").asText().isNotBlank(), "пустой лист объясняет себя словами")
    }

    @Test
    fun `мостик и поручения требуют проект`() {
        assertFailsWith<IllegalArgumentException> { router.handle("GET", "/v2/bridge", emptyMap(), null) }
        assertNull(router.handle("GET", "/v2/bridge/что-нибудь", п, null), "выдуманного пути у мостика нет")
    }
}
