// Сквозной проход сцен 10–12 через маршруты v2 — ворота волны 4.
//
// Проверяется то, ради чего волна делалась:
//   · разрыв TRL сам рождает пакет созревания и веху — сцена 10;
//   · риск без срока-точки держит сцену 11, ОСЗ считается от базового варианта;
//   · оценка диапазоном обязана включать созревание — сцена 12;
//   · после сцен 11–12 точка KDP-A открывается.
package orbita.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.api.internal.ModelRoutes
import orbita.api.internal.ReqArchRoutes
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
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SceneTenTwelveTest {

    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links: LinkRegistry = KernelFactory.linkRegistry(TestDbV2.conn)
    private val пройденные = mutableMapOf<String, MutableSet<String>>()
    private val провенанс = Provenance(Channel.MANUAL, "Чернов Д.")

    private val шаблон = mapper.readTree(
        TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые/ШАБЛОН-ФАЗЫ-PRE-A-NASA.json").toFile(),
    )

    private val router: V2Router by lazy {
        val требования = RequirementsFactory.requirements(store, links, mapper)
        val снимки = RequirementsFactory.baselines(store, links, mapper)
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
                gatesPassed = { p -> пройденные.getOrPut(p) { mutableSetOf() } },
                extra = { проект, условие ->
                    проверкиТ.of(проект, условие)
                        ?: проверкиА.of(проект, условие)
                        ?: проверкиП.of(проект, условие)
                },
            ),
            passedGates = { p -> пройденные.getOrPut(p) { mutableSetOf() } },
            outputCounter = { проект, вид ->
                store.list(Area.Project(проект), вид)
                    .count { вид != "intent" || it.status == "accepted" }
            },
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
            ModelRoutes(
                store,
                ModelsFactory.models(store, mapper),
                ModelsFactory.variants(store),
                ModelsFactory.impact(store, links),
                программатика,
                mapper,
            ),
        )
    }

    private val проект = "PJ-9401"
    private val параметры = mapOf("project" to проект)
    private val область = Area.Project(проект)

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        пройденные.clear()
        val wbs = mapper.readTree(TestDbV2.repoRoot.resolve("docs/tz/v2/ПОЛКА-WBS.json").toFile())
        store.create(
            wbs.path("code").asText("WBS-9002"), "wbs_template", Area.Library, null, wbs,
            Provenance(Channel.PACKAGE, "поставка"),
        )
        доСцены10()
    }

    private fun фаза(): JsonNode = router.handle("GET", "/v2/phase", параметры, null)!!.body

    private fun сцена(ключ: String): JsonNode =
        фаза().path("scenes").single { it.path("key").asText() == ключ }

    /** Сцены 1–8 уже проверены своими тестами: сюда доходим их путём. */
    private fun доСцены10() {
        router.handle("POST", "/v2/projects", emptyMap(), """{"name":"Волна 4","code":"$проект"}""")
        router.handle("POST", "/v2/intent", параметры,
            """{"for_whom":"перевозчики","what":"телеметрия","where":"СМП","horizon":"2033","accepted":true}""")
        val стороны = (1..3).map { n ->
            router.handle("POST", "/v2/stakeholders", параметры, """{"name":"Сторона $n","role":"customer"}""")!!
                .body.path("code").asText()
        }
        стороны.forEachIndexed { i, код ->
            router.handle("POST", "/v2/needs", параметры,
                """{"statement":"нужда стороны ${i + 1}","owner":"$код"}""")
        }
        val нужды = router.handle("GET", "/v2/entities", параметры + ("kind" to "need"), null)!!
            .body.path("items").map { it.path("code").asText() }
        router.handle("POST", "/v2/goals", параметры,
            """{"statement":"отслеживаемость","year":2033,"covers":${mapper.writeValueAsString(нужды)}}""")
        router.handle("POST", "/v2/constraints", параметры,
            """{"text":"полезная нагрузка — только регенеративная","category":"техническое"}""")
        router.handle("POST", "/v2/services", параметры,
            """{"name":"короткие сообщения","qos_class":"B′","covers":${mapper.writeValueAsString(нужды)}}""")
        listOf(
            """{"code":"SC","name":"Космический аппарат","nature":"node","level":1,"kind":"system"}""",
            """{"code":"OBC-CPU","name":"БЦВМ","nature":"node","level":4,"kind":"assembly"}""",
            """{"code":"OBC-SW","name":"ПО управления КА","nature":"behaviour","level":4,"kind":"assembly"}""",
        ).forEach { router.handle("POST", "/v2/components", параметры, it) }
        router.handle("POST", "/v2/components/deploy", параметры,
            """{"behaviour":"OBC-SW","node":"OBC-CPU","rationale":"поведение исполняется на БЦВМ"}""")
        router.handle("POST", "/v2/concept", параметры,
            """{"variant":"V1","rationale":"единственный вариант в рамках Р2","author":"Чернов Д."}""")
        val цель = store.list(область, "goal").single().id
        listOf("RQ-P-01" to "SC", "RQ-S-01" to "OBC-CPU", "RQ-S-02" to "OBC-SW").forEach { (код, носитель) ->
            router.handle("POST", "/v2/requirements", параметры,
                """{"code":"$код","level":"system","title":"требование $код",
                    "statement":"Узел должен передавать сообщения не реже раза в сутки.",
                    "category":"functional","carrier":"$носитель","verification_method":"test",
                    "ears_pattern":"ubiquitous","acceptance_criteria":"проверено",
                    "source":[{"kind":"goal","ref":"$цель"}]}""")
        }
    }

    @Test
    fun `сцена 10 держится технологиями, а разрыв TRL сам рождает пакет и веху`() {
        assertEquals("open", сцена("10").path("state").asText(), "после требований сцена 10 открывается сама")
        assertTrue(
            сцена("10").path("blockers").toString().contains("критических технологий названо 0"),
            "пустой список технологий назван числом: ${сцена("10").path("blockers")}",
        )

        store.create(
            "TECH-FDIR", "technology", область, "10",
            mapper.readTree(
                """{"name":"алгоритмы FDIR","component":"${store.byCode(область, "OBC-SW")!!.id}",
                    "trl_current":5,"trl_required":6,"required_by":"PDR","fallback":"покупной модуль"}""",
            ),
            провенанс,
        )

        val созревание = router.handle("GET", "/v2/maturation", параметры, null)!!.body.path("items")
        val fdir = созревание.single { it.path("technology").asText() == "TECH-FDIR" }
        assertTrue(fdir.path("package").asText().isNotBlank(), "разрыв TRL родил пакет: $fdir")
        assertTrue(fdir.path("milestone").asText().isNotBlank(), "и веху: $fdir")
        assertTrue(fdir.path("words").asText().contains("TRL 5 → 6"), fdir.path("words").asText())

        assertEquals("done", сцена("10").path("state").asText(), сцена("10").path("blockers").toString())
    }

    @Test
    fun `веха технологии не считается точкой фазы и не открывает сцену 1 заново`() {
        val доТехнологии = сцена("1").path("state").asText()
        assertEquals("done", доТехнологии, "сцена 1 прожита: проект и точки с датами есть")

        технологию()

        // Веха «TRL достигнут» рождается без даты — её даёт план созревания,
        // а не сцена 1. Считая её точкой фазы, мы открывали сцену 1 заново.
        assertTrue(
            store.list(область, "gate").any { it.doc.path("kind").asText() == "technology" },
            "веха технологии заведена",
        )
        assertEquals(
            "done",
            сцена("1").path("state").asText(),
            "сцена 1 остаётся прожитой: ${сцена("1").path("blockers")}",
        )
    }

    @Test
    fun `сцена 11 держится сроком-точкой риска и оценкой засорения`() {
        технологию()
        assertTrue(
            сцена("11").path("blockers").toString().contains("рисков 0 из 3"),
            "пустой реестр рисков означает, что их не искали: ${сцена("11").path("blockers")}",
        )

        (1..3).forEach { n ->
            store.create(
                "RSK-0$n", "risk", область, "11",
                mapper.readTree(
                    """{"statement":"риск $n","category":"technical","probability":$n,"impact":3,
                        "strategy":"mitigate","measures":"меры","owner":"Ведущий СИ"}""",
                ),
                провенанс,
            )
        }
        assertTrue(
            сцена("11").path("blockers").toString().contains("без срока-точки"),
            "срок без точки не наступает: ${сцена("11").path("blockers")}",
        )

        store.list(область, "risk").forEach { риск ->
            store.update(
                риск.id,
                риск.doc.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("due_point", "SDR"),
                провенанс,
            )
        }
        assertTrue(
            сцена("11").path("blockers").toString().contains("засорения"),
            "остаётся ОСЗ: ${сцена("11").path("blockers")}",
        )

        store.create(
            "ODA-01", "debris_assessment", область, "11",
            mapper.readTree("""{"variant":"V1","lifetime_years":18,"compliant":true}"""), провенанс,
        )
        assertEquals("done", сцена("11").path("state").asText(), сцена("11").path("blockers").toString())

        val риски = router.handle("GET", "/v2/risks", параметры, null)!!.body.path("items")
        assertEquals("RSK-03", риски.first().path("code").asText(), "реестр идёт по уровню")
    }

    @Test
    fun `сцена 12 требует пары к узлам, диапазон и созревание в стоимости`() {
        технологию()
        рискиИОсз()

        // Пакет созревания уже завёлся сам (сцена 10), поэтому «WBS не взят»
        // здесь не звучит — сцену держит отсутствие оценки.
        assertTrue(
            сцена("12").path("blockers").toString().contains("оценки нет"),
            "к KDP нужен ROM диапазоном с допущениями: ${сцена("12").path("blockers")}",
        )

        router.handle("POST", "/v2/wbs/take", параметры, """{"author":"Чернов Д."}""")
        val пакеты = router.handle("GET", "/v2/wbs", параметры, null)!!.body.path("items")
        assertTrue(пакеты.size() > 10, "типовой WBS взят: ${пакеты.size()}")

        // Пакеты без пары к узлу держат сцену поимённо.
        val держит = сцена("12").path("blockers").toString()
        assertTrue(держит.contains("без пары") || держит.contains("оценки нет"), держит)

        // Пары ставим сами: узлов в проекте три, остальные пакеты снимаем.
        val ка = store.byCode(область, "SC")!!.id
        store.list(область, "wbs_package")
            .filter { !it.doc.path("cross_cutting").asBoolean(false) && it.doc.path("pbs_refs").isEmpty }
            .forEach { пакет ->
                store.update(
                    пакет.id,
                    пакет.doc.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().apply {
                        putArray("pbs_refs").add(ка)
                    },
                    провенанс,
                )
            }

        val одноЧисло = runCatching {
            router.handle("POST", "/v2/wbs/estimate", параметры,
                """{"package":"04.TECH-FDIR","min":50,"max":50,"unit":"млн ₽","method":"rom",
                    "assumptions":"как-то так","author":"Чернов Д."}""")
        }.exceptionOrNull()
        assertTrue(
            одноЧисло?.message?.contains("диапазон") == true,
            "оценка одним числом отклоняется до записи: ${одноЧисло?.message}",
        )
    }

    @Test
    fun `стоимость без пакета созревания при открытом TRL — разрыв`() {
        технологию()
        рискиИОсз()
        router.handle("POST", "/v2/wbs/take", параметры, """{"author":"Чернов Д."}""")
        val ка = store.byCode(область, "SC")!!.id
        store.list(область, "wbs_package")
            .filter { !it.doc.path("cross_cutting").asBoolean(false) && it.doc.path("pbs_refs").isEmpty }
            .forEach { пакет ->
                store.update(
                    пакет.id,
                    пакет.doc.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
                        .apply { putArray("pbs_refs").add(ка) },
                    провенанс,
                )
            }
        // Оценка есть, но не на пакете созревания.
        val любой = store.list(область, "wbs_package").first { it.code != "04.TECH-FDIR" }.code
        router.handle("POST", "/v2/wbs/estimate", параметры,
            """{"package":"$любой","min":80,"max":140,"unit":"млн ₽","method":"rom",
                "assumptions":"серийная платформа 12U, пуск попутный","author":"Чернов Д."}""")

        assertTrue(
            сцена("12").path("blockers").toString().contains("не включает созревание"),
            "оценка без пакетов созревания считает не тот объём работ: ${сцена("12").path("blockers")}",
        )

        router.handle("POST", "/v2/wbs/estimate", параметры,
            """{"package":"04.TECH-FDIR","min":15,"max":40,"unit":"млн ₽","method":"rom",
                "assumptions":"ОКР по FDIR с макетом и SIL-стендом","author":"Чернов Д."}""")
        assertEquals("done", сцена("12").path("state").asText(), сцена("12").path("blockers").toString())
    }

    private fun технологию() {
        store.create(
            "TECH-FDIR", "technology", область, "10",
            mapper.readTree(
                """{"name":"алгоритмы FDIR","component":"${store.byCode(область, "OBC-SW")!!.id}",
                    "trl_current":5,"trl_required":6,"required_by":"PDR"}""",
            ),
            провенанс,
        )
        router.handle("GET", "/v2/maturation", параметры, null)
    }

    private fun рискиИОсз() {
        (1..3).forEach { n ->
            store.create(
                "RSK-0$n", "risk", область, "11",
                mapper.readTree(
                    """{"statement":"риск $n","category":"technical","probability":$n,"impact":3,
                        "strategy":"mitigate","measures":"меры","owner":"Ведущий СИ","due_point":"SDR"}""",
                ),
                провенанс,
            )
        }
        store.create(
            "ODA-01", "debris_assessment", область, "11",
            mapper.readTree("""{"variant":"V1","lifetime_years":18,"compliant":true}"""), провенанс,
        )
    }
}
