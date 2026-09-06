// Сквозной проход сцен 7–8 через маршруты v2 — ворота волны 3.
//
// Проверяется то, ради чего волна делалась:
//   · состав и развёртывание закрывают сцену 7, а незакрытое называется словами;
//   · требование живёт на носителе СВОЕЙ природы — иначе сцена 8 не закроется;
//   · базирование берёт снимок и делает элемент условным по разрыву TRL;
//   · после сцен 7–8 точка MCR открывается и фиксируется.
package orbita.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.api.internal.ReqArchRoutes
import orbita.api.internal.V2Router
import orbita.architecture.api.ArchitectureFactory
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.LinkRegistry
import orbita.library.api.LibraryFactory
import orbita.process.api.ProcessFactory
import orbita.readiness.api.ReadinessFactory
import orbita.requirements.api.RequirementsFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SceneSevenEightTest {

    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links: LinkRegistry = KernelFactory.linkRegistry(TestDbV2.conn)
    private val пройденные = mutableMapOf<String, MutableSet<String>>()

    private val шаблон = mapper.readTree(
        TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые/ШАБЛОН-ФАЗЫ-PRE-A-NASA.json").toFile(),
    )

    private val router: V2Router by lazy {
        val требования = RequirementsFactory.requirements(store, links, mapper)
        val снимки = RequirementsFactory.baselines(store, links, mapper)
        val архитектура = ArchitectureFactory.architecture(store, links, mapper)
        val проверкиТ = RequirementsFactory.gateChecks(store, снимки)
        val проверкиА = ArchitectureFactory.gateChecks(store, архитектура)
        val движок = ProcessFactory.engine(
            template = { шаблон },
            evaluator = ReadinessFactory.gateEvaluator(
                store, links,
                scenesDone = { emptySet() },
                gatesPassed = { p -> пройденные.getOrPut(p) { mutableSetOf() } },
                extra = { проект, условие -> проверкиТ.of(проект, условие) ?: проверкиА.of(проект, условие) },
            ),
            passedGates = { p -> пройденные.getOrPut(p) { mutableSetOf() } },
            gatePlan = { проект ->
                store.list(Area.Project(проект), "gate")
                    .associate { it.code to it.doc.path("planned_date").asText("") }
                    .filterValues { it.isNotBlank() }
            },
        )
        V2Router(
            store, links, движок,
            LibraryFactory.shelves(store) { шаблон },
            orbita.knowledge.api.KnowledgeFactory.intake(store, mapper),
            orbita.formulation.api.FormulationFactory.formulation(store, links),
            mapper,
            ReqArchRoutes(store, требования, снимки, архитектура, mapper),
        )
    }

    private val проект = "PJ-9301"
    private val параметры = mapOf("project" to проект)

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        пройденные.clear()
        // Полка шаблонов компонентов: лестница зрелости — данные поставки.
        store.create(
            "TCS-9002", "component_template_shelf", Area.Library, null,
            mapper.readTree(TestDbV2.repoRoot.resolve("docs/tz/v2/ПОЛКА-ШАБЛОНЫ-КОМПОНЕНТОВ.json").toFile()),
            orbita.kernel.api.Provenance(orbita.kernel.api.Channel.PACKAGE, "поставка"),
        )
        сцены1до6()
    }

    /** Волны 1–2 в одну строку: до сцены 7 доходим уже проверенным путём. */
    private fun сцены1до6() {
        router.handle("POST", "/v2/projects", emptyMap(), """{"name":"Волна 3","code":"$проект"}""")
        router.handle("POST", "/v2/intent", параметры,
            """{"for_whom":"перевозчики","what":"телеметрия груза","where":"СМП","horizon":"2033","accepted":true}""")
        val стороны = (1..3).map { n ->
            router.handle("POST", "/v2/stakeholders", параметры, """{"name":"Сторона $n","role":"customer"}""")!!
                .body.path("code").asText()
        }
        стороны.forEachIndexed { i, код ->
            router.handle("POST", "/v2/needs", параметры,
                """{"statement":"нужда стороны ${i + 1} в связи","owner":"$код"}""")
        }
        val нужды = router.handle("GET", "/v2/entities", параметры + ("kind" to "need"), null)!!
            .body.path("items").map { it.path("code").asText() }
        router.handle("POST", "/v2/goals", параметры,
            """{"statement":"отслеживаемость перевозок","year":2033,"covers":${mapper.writeValueAsString(нужды)}}""")
        router.handle("POST", "/v2/constraints", параметры,
            """{"text":"полезная нагрузка — только регенеративная","category":"техническое"}""")
        router.handle("POST", "/v2/services", параметры,
            """{"name":"передача коротких сообщений","qos_class":"B′","covers":${mapper.writeValueAsString(нужды)}}""")
    }

    private fun фаза(): JsonNode = router.handle("GET", "/v2/phase", параметры, null)!!.body

    private fun сцена(ключ: String): JsonNode =
        фаза().path("scenes").single { it.path("key").asText() == ключ }

    private fun узел(код: String, имя: String, род: String, уровень: Int, вид: String) =
        router.handle("POST", "/v2/components", параметры,
            """{"code":"$код","name":"$имя","nature":"$род","level":$уровень,"kind":"$вид"}""")

    @Test
    fun `сцена 7 держится составом и развёртыванием, и говорит чем именно`() {
        assertEquals("open", сцена("7").path("state").asText(), "после сервисов сцена 7 открывается сама")
        assertTrue(
            сцена("7").path("blockers").toString().contains("узлов состава 0 из 3"),
            "пустой состав называется числом: ${сцена("7").path("blockers")}",
        )

        узел("SC", "Космический аппарат", "node", 1, "system")
        узел("OBC-CPU", "БЦВМ", "node", 4, "assembly")
        узел("OBC-SW", "ПО управления КА", "behaviour", 4, "assembly")

        assertTrue(
            сцена("7").path("blockers").toString().contains("OBC-SW"),
            "поведение без носителя держит сцену поимённо: ${сцена("7").path("blockers")}",
        )

        router.handle("POST", "/v2/components/deploy", параметры,
            """{"behaviour":"OBC-SW","node":"OBC-CPU","rationale":"поведение исполняется на БЦВМ"}""")
        assertTrue(
            сцена("7").path("blockers").toString().contains("базовый вариант не назван"),
            "остаётся последнее: выбор варианта с обоснованием — ${сцена("7").path("blockers")}",
        )

        концепция()
        assertEquals("done", сцена("7").path("state").asText(), сцена("7").path("blockers").toString())
    }

    /** Базовая концепция: вариант с обоснованием и отказами от объёма. */
    private fun концепция() {
        router.handle("POST", "/v2/concept", параметры,
            """{"variant":"V1","rationale":"единственный вариант, закрывающий все нужды в рамках Р2",
                "rejected":[{"variant":"V2","reason":"масса вне 12U…100 кг"}],
                "descopes":[{"text":"межспутниковая связь","impact":"задержка до 6 ч"}],
                "author":"Чернов Д."}""")
    }

    @Test
    fun `базовый вариант без обоснования не записывается`() {
        составБезКонцепции()
        val отказ = runCatching {
            router.handle("POST", "/v2/concept", параметры, """{"variant":"V1","author":"Чернов Д."}""")
        }.exceptionOrNull()
        assertTrue(
            отказ?.message?.contains("обоснования") == true,
            "выбор без причины — не решение: ${отказ?.message}",
        )
        assertTrue(
            сцена("7").path("blockers").toString().contains("базовый вариант не назван"),
            "сцена остаётся открытой: ${сцена("7").path("blockers")}",
        )
    }

    @Test
    fun `сцена 8 не закрывается требованием на носителе чужой природы`() {
        составИКонцепция()
        val цель = store.list(Area.Project(проект), "goal").single()

        // Требование уровня «интерфейсное», посаженное на узел, — типовая ошибка.
        router.handle("POST", "/v2/requirements", параметры,
            """{"code":"RQ-I-01","level":"interface","title":"обмен кадрами",
                "statement":"Стык должен передавать кадры TM/TC.","category":"interface",
                "carrier":"OBC-CPU","verification_method":"test","ears_pattern":"ubiquitous",
                "acceptance_criteria":"кадр принят","source":[{"kind":"goal","ref":"${цель.id}"}]}""")

        val помеха = сцена("8").path("blockers").toString()
        assertTrue(помеха.contains("не той природы"), "сцена 8 держится природой носителя: $помеха")
        assertTrue(помеха.contains("RQ-I-01"), "разрыв называет требование: $помеха")
    }

    @Test
    fun `сцены 7 и 8 прожиты — MCR открывается и фиксируется`() {
        составИКонцепция()
        val цель = store.list(Area.Project(проект), "goal").single()

        router.handle("POST", "/v2/components", параметры,
            """{"code":"IF-TTC","name":"кадры TM/TC","kind":"assembly","level":4,"nature":"node"}""")
        store.create(
            "IF-DATA-BUS", "interface", Area.Project(проект), "8",
            mapper.readTree(
                """{"name":"шина данных","type":"data",
                    "a":"${store.byCode(Area.Project(проект), "OBC-CPU")!!.id}",
                    "b":"${store.byCode(Area.Project(проект), "OBC-SW")!!.id}",
                    "direction":"bi","requirement_classes":["data"]}""",
            ),
            orbita.kernel.api.Provenance(orbita.kernel.api.Channel.MANUAL, "Чернов Д."),
        )

        fun требование(код: String, уровень: String, носитель: String, формулировка: String) =
            router.handle("POST", "/v2/requirements", параметры,
                """{"code":"$код","level":"$уровень","title":"требование $код",
                    "statement":"$формулировка","category":"functional","carrier":"$носитель",
                    "verification_method":"test","ears_pattern":"ubiquitous",
                    "acceptance_criteria":"проверено на испытании",
                    "source":[{"kind":"goal","ref":"${цель.id}"}]}""")!!

        требование("RQ-P-01", "project", "SC", "КА должен передавать сообщения перевозчиков не реже раза в сутки.")
        требование("RQ-S-01", "system", "OBC-CPU", "БЦВМ должна хранить принятые сообщения не менее 24 ч.")
        val последнее = требование(
            "RQ-I-01", "interface", "IF-DATA-BUS", "Шина должна передавать кадры TM/TC без потерь.",
        )
        assertEquals("interface", последнее.body.path("carrier_kind").asText(), "носитель развёрнут видом")
        assertTrue(последнее.body.path("notes").isEmpty, "формулировка по шаблону замечаний не даёт")

        assertEquals("done", сцена("8").path("state").asText(), сцена("8").path("blockers").toString())

        val mcr = фаза().path("gates").single { it.path("key").asText() == "MCR" }
        assertEquals(0, mcr.path("blocking").size(), "сцены прожиты — MCR открыт: ${mcr.path("blocking")}")
        val пройдена = router.handle("POST", "/v2/gates/MCR/pass", параметры, """{"author":"Чернов Д."}""")!!
        assertTrue(
            пройдена.body.path("gates").single { it.path("key").asText() == "MCR" }.path("passed").asBoolean(),
            "после выполнения всех условий точка фиксируется",
        )
    }

    @Test
    fun `базирование через маршрут отказывает списком, а незрелая технология даёт условный элемент`() {
        составИКонцепция()
        val цель = store.list(Area.Project(проект), "goal").single()

        // Требование с пометой линта: базирование его не переживёт.
        router.handle("POST", "/v2/requirements", параметры,
            """{"code":"RQ-P-09","level":"project","title":"плохое","statement":"Необходимо обеспечить связь.",
                "category":"functional","carrier":"SC","verification_method":"test","ears_pattern":"ubiquitous",
                "acceptance_criteria":"—","source":[{"kind":"goal","ref":"${цель.id}"}]}""")

        val отказ = router.handle("POST", "/v2/baselines", параметры,
            """{"name":"SRR","kind":"functional","gate":"SRR","author":"Чернов Д."}""")!!
        assertEquals(409, отказ.code, "набор с помехами не базируется")
        val причины = отказ.body.path("blockers").toString()
        assertTrue(причины.contains("RQ-P-09"), "отказ называет объект: $причины")
        assertTrue(причины.contains("слово цели") || причины.contains("не по шаблону"), причины)

        // Правим формулировку и добавляем незрелую технологию на узел.
        val плохое = store.byCode(Area.Project(проект), "RQ-P-09")!!
        store.update(
            плохое.id,
            плохое.doc.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
                .put("statement", "КА должен передавать сообщения не реже раза в сутки."),
            orbita.kernel.api.Provenance(orbita.kernel.api.Channel.MANUAL, "Чернов Д."),
        )
        store.create(
            "TECH-ISL", "technology", Area.Project(проект), "10",
            mapper.readTree(
                """{"name":"межспутниковая линия","component":"${store.byCode(Area.Project(проект), "SC")!!.id}",
                    "trl_current":4,"trl_required":6,"required_by":"PDR"}""",
            ),
            orbita.kernel.api.Provenance(orbita.kernel.api.Channel.MANUAL, "Чернов Д."),
        )

        val снимок = router.handle("POST", "/v2/baselines", параметры,
            """{"name":"SRR","kind":"functional","gate":"SRR","author":"Чернов Д."}""")!!
        assertEquals(201, снимок.code, снимок.body.toString())
        val условные = снимок.body.path("items").filter { it.path("firmness").asText() == "conditional" }
        assertTrue(
            условные.any { it.path("code").asText() == "RQ-P-09" && it.path("conditional_on").asText() == "TECH-ISL" },
            "требование на узле незрелой технологии входит условным: ${снимок.body.path("items")}",
        )
        assertTrue(
            условные.first().path("why").asText().contains("TRL 4"),
            "условие названо словами: ${условные.first().path("why")}",
        )
    }

    /** Сцена 7 целиком: состав, развёртывание, базовая концепция. */
    private fun составИКонцепция() {
        составБезКонцепции()
        концепция()
    }

    private fun составБезКонцепции() {
        узел("SC", "Космический аппарат", "node", 1, "system")
        узел("OBC-CPU", "БЦВМ", "node", 4, "assembly")
        узел("OBC-SW", "ПО управления КА", "behaviour", 4, "assembly")
        router.handle("POST", "/v2/components/deploy", параметры,
            """{"behaviour":"OBC-SW","node":"OBC-CPU","rationale":"поведение исполняется на БЦВМ"}""")
    }
}
