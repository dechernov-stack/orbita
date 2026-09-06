// Сквозной проход сцен 1–6 через маршруты v2 — ворота волн 1 и 2.
//
// Проверяется не «форма отправилась», а то, ради чего строится продукт:
// сцена 3 закрыта, пока замысел не принят; открывается сама; точка MCR
// держится, пока цели не связаны с нуждами; отказ приходит от движка.
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
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SceneFlowTest {

    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links: LinkRegistry = KernelFactory.linkRegistry(TestDbV2.conn)
    private val пройденные = mutableMapOf<String, MutableSet<String>>()

    private val шаблон = mapper.readTree(
        TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые/ШАБЛОН-ФАЗЫ-PRE-A-NASA.json").toFile(),
    )

    /** Полка процессов ЖЦ: входные потоки сцен приходят оттуда. */
    private val процессы = mapper.readTree(
        TestDbV2.repoRoot.resolve("docs/tz/v2/ПОЛКА-ПРОЦЕССЫ-РОМАНОВ.json").toFile(),
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
            gatePlan = { проект ->
                store.list(Area.Project(проект), "gate")
                    .associate { it.code to it.doc.path("planned_date").asText("") }
                    .filterValues { it.isNotBlank() }
            },
            processReference = { процессы },
            sceneWindows = { проект ->
                store.list(Area.Project(проект), "plan").lastOrNull()
                    ?.doc?.path("scene_windows")
                    ?.associate {
                        it.path("scene").asText() to (it.path("start").asText("") to it.path("end").asText(""))
                    }
                    ?.filterValues { it.first.isNotBlank() }
                    .orEmpty()
            },
        )
        V2Router(
            store, links, движок,
            LibraryFactory.shelves(store) { шаблон },
            orbita.knowledge.api.KnowledgeFactory.intake(store, mapper),
            orbita.formulation.api.FormulationFactory.formulation(store, links),
            mapper,
        )
    }

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        пройденные.clear()
    }

    private fun сцена(фаза: com.fasterxml.jackson.databind.JsonNode, ключ: String) =
        фаза.path("scenes").single { it.path("key").asText() == ключ }

    @Test
    fun `сцена 1 заводит проект и три точки с датами`() {
        val ответ = router.handle("POST", "/v2/projects", emptyMap(),
            """{"name":"Проверка волны 1","code":"PJ-9100","author":"Чернов Д."}""")!!
        assertEquals(201, ответ.code)
        assertEquals("NASA-7120", ответ.body.path("standard").asText())
        assertEquals(3, ответ.body.path("gates").size(), "фаза заводится с тремя точками")
        ответ.body.path("gates").forEach {
            assertTrue(it.path("planned_date").asText().isNotBlank(), "у точки обязана быть дата")
        }
        // сцена 1 прожита, сцена 2 открыта, сцена 3 закрыта
        assertEquals("done", сцена(ответ.body, "1").path("state").asText())
        assertEquals("open", сцена(ответ.body, "2").path("state").asText())
        assertEquals("locked", сцена(ответ.body, "3").path("state").asText())
    }

    @Test
    fun `сцена 3 открывается принятым замыслом, а не флагом`() {
        router.handle("POST", "/v2/projects", emptyMap(), """{"name":"П","code":"PJ-9101"}""")
        val параметры = mapOf("project" to "PJ-9101")

        // черновик замысла сцену не открывает
        router.handle("POST", "/v2/intent", параметры,
            """{"for_whom":"перевозчики","what":"телеметрия","where":"СМП","horizon":"2033","accepted":false}""")
        val до = router.handle("GET", "/v2/phase", параметры, null)!!.body
        assertEquals("locked", сцена(до, "3").path("state").asText())
        assertTrue(сцена(до, "3").path("blockers").toString().contains("не принят"))

        // принятый — открывает
        router.handle("POST", "/v2/intent", параметры,
            """{"for_whom":"перевозчики","what":"телеметрия","where":"СМП","horizon":"2033","accepted":true}""")
        val после = router.handle("GET", "/v2/phase", параметры, null)!!.body
        assertEquals("open", сцена(после, "3").path("state").asText())
    }

    @Test
    fun `сцены 5-6 закрываются ограничением и покрытием нужд сервисами`() {
        router.handle("POST", "/v2/projects", emptyMap(), """{"name":"Полный проход","code":"PJ-9104"}""")
        val параметры = mapOf("project" to "PJ-9104")
        router.handle("POST", "/v2/intent", параметры,
            """{"for_whom":"перевозчики","what":"телеметрия","where":"СМП","horizon":"2033","accepted":true}""")
        val коды = (1..3).map { n ->
            router.handle("POST", "/v2/stakeholders", параметры, """{"name":"Сторона $n","role":"customer"}""")!!
                .body.path("code").asText()
        }
        коды.forEachIndexed { i, код ->
            router.handle("POST", "/v2/needs", параметры,
                """{"statement":"нужда стороны ${i + 1} в связи","owner":"$код"}""")
        }
        val нужды = router.handle("GET", "/v2/entities", параметры + ("kind" to "need"), null)!!
            .body.path("items").map { it.path("code").asText() }
        router.handle("POST", "/v2/goals", параметры,
            """{"statement":"отслеживаемость","year":2033,"covers":${mapper.writeValueAsString(нужды)}}""")

        // сцена 5 закрыта, пока нет ни одного ограничения
        val доОграничений = router.handle("GET", "/v2/phase", параметры, null)!!.body
        assertEquals("open", сцена(доОграничений, "5").path("state").asText())
        assertTrue(сцена(доОграничений, "5").path("blockers").toString().contains("ограничений 0"))
        assertEquals("locked", сцена(доОграничений, "6").path("state").asText())

        val ограничение = router.handle("POST", "/v2/constraints", параметры,
            """{"text":"полезная нагрузка — только регенеративная","category":"техническое"}""")!!
        assertEquals("Р1", ограничение.body.path("code").asText(), "код Р-серии присваивает система")

        // сцена 6 открылась; без сервисов она не закроется
        val доСервисов = router.handle("GET", "/v2/phase", параметры, null)!!.body
        assertEquals("done", сцена(доСервисов, "5").path("state").asText())
        assertEquals("open", сцена(доСервисов, "6").path("state").asText())
        assertTrue(сцена(доСервисов, "6").path("blockers").toString().contains("без сервиса"))

        router.handle("POST", "/v2/services", параметры,
            """{"name":"передача коротких сообщений","qos_class":"B′","covers":${mapper.writeValueAsString(нужды)}}""")

        val фаза = router.handle("GET", "/v2/phase", параметры, null)!!.body
        assertEquals("done", сцена(фаза, "6").path("state").asText(), сцена(фаза, "6").path("blockers").toString())

        // Волна 3 добавила в фазу сцены 7 и 8: MCR теперь ждёт и их — и
        // говорит об этом словами. Полный проход до фиксации точки проверяет
        // SceneSevenEightTest: концепция, состав, требования, базирование.
        val mcr = фаза.path("gates").single { it.path("key").asText() == "MCR" }
        assertEquals(
            listOf("сцена 7 ещё не прожита", "сцена 8 ещё не прожита"),
            mcr.path("blocking").map { it.asText() },
            "после сцены 6 точка держится концепцией и требованиями",
        )
        assertEquals("open", сцена(фаза, "7").path("state").asText(), "сцена 7 открылась сама")
    }

    @Test
    fun `сцена несёт нить потока - вход, выход и кто её ждёт`() {
        router.handle("POST", "/v2/projects", emptyMap(), """{"name":"Нить","code":"PJ-9112"}""")
        val параметры = mapOf("project" to "PJ-9112")
        val фаза = router.handle("GET", "/v2/phase", параметры, null)!!.body
        val третья = сцена(фаза, "3")

        assertEquals("реестр стейкхолдеров и их нужд", третья.path("output").asText())
        assertTrue(
            третья.path("awaited_by").map { it.asText() }.any { it.contains("4 · Цели") },
            "сцена обязана знать, кто её ждёт: ${третья.path("awaited_by")}",
        )
        assertTrue(
            третья.path("awaited_by").map { it.asText() }.any { it.startsWith("◆") },
            "точки тоже ждут сцену: ${третья.path("awaited_by")}",
        )
        assertTrue(
            третья.path("input_flows").map { it.asText() }.any { it.contains("потребности") },
            "входные потоки приходят с полки процессов, а не из кода: ${третья.path("input_flows")}",
        )
        // Панель условий показывает ВСЕ условия — и выполненные, и нет.
        // «У каждого стейкхолдера есть нужда» в пустом проекте выполнено
        // честно: нарушителя нет, потому что нет и стейкхолдеров.
        val условия = третья.path("exit").map { it.path("title").asText() to it.path("passed").asBoolean() }
        assertTrue(условия.size >= 2, "условий выхода должно быть видно несколько: $условия")
        assertTrue(условия.any { !it.second }, "в пустом проекте есть невыполненное условие: $условия")
        assertTrue(
            третья.path("exit").any { !it.path("passed").asBoolean() && !it.path("why").isNull },
            "у невыполненного условия обязана быть причина словами",
        )
    }

    @Test
    fun `внутренний обзор держится планом работ фазы, а план ставит даты точкам`() {
        router.handle("POST", "/v2/projects", emptyMap(), """{"name":"План","code":"PJ-9113"}""")
        val параметры = mapOf("project" to "PJ-9113")

        val обзорДо = router.handle("GET", "/v2/phase", параметры, null)!!
            .body.path("gates").single { it.path("key").asText() == "internal_review" }
        assertTrue(
            обзорДо.path("blocking").map { it.asText() }.any { it.contains("план работ фазы не задан") },
            "без плана лента пуста, и точка это говорит: ${обзорДо.path("blocking")}",
        )

        router.handle("POST", "/v2/plan", параметры,
            """{"gate_dates":[{"gate":"internal_review","date":"2026-10-08"},{"gate":"MCR","date":"2026-11-25"}],
                "scene_windows":[{"scene":"1","start":"2026-09-14","end":"2026-09-15"},
                                 {"scene":"2","start":"2026-09-15","end":"2026-09-18"}],
                "author":"Чернов Д."}""")

        val фаза = router.handle("GET", "/v2/phase", параметры, null)!!.body
        assertTrue(
            фаза.path("gates").single { it.path("key").asText() == "internal_review" }
                .path("blocking").map { it.asText() }.none { it.contains("план работ фазы") },
            "порог владельца: план у первой доступной сцены снимает разрыв",
        )
        assertEquals(
            "2026-10-08",
            фаза.path("gates").single { it.path("key").asText() == "internal_review" }.path("planned_date").asText(),
            "план задаёт даты точкам, а не дублирует их",
        )
        assertEquals(
            "2026-09-14",
            сцена(фаза, "1").path("window").path("start").asText(),
            "окно сцены видно ленте",
        )
        assertTrue(
            сцена(фаза, "3").path("window").isMissingNode,
            "у сцены без окна плана его и нет — лента покажет «план не задан»",
        )
    }

    @Test
    fun `сцена показывает мероприятия метода с выходами-счётчиками`() {
        router.handle("POST", "/v2/projects", emptyMap(), """{"name":"Мероприятия","code":"PJ-9114"}""")
        val параметры = mapOf("project" to "PJ-9114")
        router.handle("POST", "/v2/intent", параметры,
            """{"for_whom":"перевозчики","what":"телеметрия","where":"СМП","horizon":"2033","accepted":true}""")

        val третья = сцена(router.handle("GET", "/v2/phase", параметры, null)!!.body, "3")
        val дела = третья.path("activities")
        assertEquals(2, дела.size(), "сцена 3 по методу — два мероприятия: 0.1 и 0.2")

        val первое = дела.single { it.path("code").asText() == "0.1" }
        assertEquals("Уточнение потребностей и задач пользователей", первое.path("name").asText())
        assertEquals("available", первое.path("state").asText(), "вход выполнен — мероприятие доступно")

        val пользователи = первое.path("outputs").single { it.path("kind").asText() == "stakeholder" }
        assertEquals(0, пользователи.path("count").asInt(), "выход считается ПО ДАННЫМ")
        assertEquals(3, пользователи.path("min").asInt())

        // Цели рождаются у нас в сцене 4 — выход помечен стрелкой, не пустотой.
        val цели = первое.path("outputs").single { it.path("kind").asText() == "goal" }
        assertEquals("4", цели.path("produced_in").asText(), "выход другой сцены назван стрелкой")
        assertTrue(цели.path("satisfied").asBoolean(), "чужой выход завершение мероприятия не держит")

        // Добавили стейкхолдера — счётчик вырос сам, состояние поехало.
        (1..3).forEach { n ->
            router.handle("POST", "/v2/stakeholders", параметры, """{"name":"Сторона $n","role":"customer"}""")
        }
        val после = сцена(router.handle("GET", "/v2/phase", параметры, null)!!.body, "3")
            .path("activities").single { it.path("code").asText() == "0.1" }
        assertEquals(3, после.path("outputs").single { it.path("kind").asText() == "stakeholder" }.path("count").asInt())
        assertEquals("in_progress", после.path("state").asText(), "выходы появляются — мероприятие в работе")
    }

    @Test
    fun `схема фазы идёт дорожками метода`() {
        router.handle("POST", "/v2/projects", emptyMap(), """{"name":"Дорожки","code":"PJ-9115"}""")
        val фаза = router.handle("GET", "/v2/phase", mapOf("project" to "PJ-9115"), null)!!.body
        val дорожки = фаза.path("lanes").associate { it.path("key").asText() to it }
        assertEquals(setOf("design", "modeling", "management"), дорожки.keys)
        assertTrue(
            дорожки.getValue("modeling").path("activities").size() >= 12,
            "дорожка моделирования показывает мероприятия метода: ${дорожки.getValue("modeling").path("activities").size()}",
        )
        assertEquals(
            "Техническое планирование и управление",
            дорожки.getValue("management").path("activities").first().path("name").asText(),
        )
        // Мероприятие метода, чьи выходы у нас ещё не заведены видами,
        // не объявляется выполненным: считать его нечем.
        assertTrue(
            дорожки.getValue("modeling").path("activities").all {
                it.path("state").asText() == "not_started"
            },
            "мероприятие без вида выхода остаётся «не начато», а не «выполнено»",
        )
    }

    @Test
    fun `портфель возвращает открытые проекты`() {
        router.handle("POST", "/v2/projects", emptyMap(), """{"name":"Первый","code":"PJ-9110"}""")
        router.handle("POST", "/v2/projects", emptyMap(), """{"name":"Второй","code":"PJ-9111"}""")

        val портфель = router.handle("GET", "/v2/projects", emptyMap(), null)!!.body.path("items")
        val коды = портфель.map { it.path("code").asText() }
        assertTrue(коды.containsAll(listOf("PJ-9110", "PJ-9111")), "портфель обязан видеть оба проекта: $коды")
        assertEquals(
            "Второй",
            портфель.single { it.path("code").asText() == "PJ-9111" }.path("name").asText(),
            "имя проекта нужно, чтобы выбрать его глазами, а не по коду",
        )
    }

    @Test
    fun `нужда без носителя не заводится`() {
        router.handle("POST", "/v2/projects", emptyMap(), """{"name":"П","code":"PJ-9102"}""")
        val параметры = mapOf("project" to "PJ-9102")
        val отказ = runCatching {
            router.handle("POST", "/v2/needs", параметры, """{"statement":"нужна связь","owner":"SK-9999"}""")
        }.exceptionOrNull()
        assertNotNull(отказ, "нужда с несуществующим носителем не заводится")
        assertTrue("не найден" in (отказ.message ?: ""), отказ.message ?: "")
    }

    @Test
    fun `сцены 1-4 прожиты, но MCR держится дальше - до покрытия нужд сервисами`() {
        router.handle("POST", "/v2/projects", emptyMap(), """{"name":"Полный проход","code":"PJ-9103"}""")
        val параметры = mapOf("project" to "PJ-9103")
        router.handle("POST", "/v2/intent", параметры,
            """{"for_whom":"перевозчики","what":"телеметрия","where":"СМП","horizon":"2033","accepted":true}""")

        // до сцен 3–4 точка держится
        val отказ = runCatching { router.handle("POST", "/v2/gates/MCR/pass", параметры, """{"author":"Чернов Д."}""") }
            .exceptionOrNull()
        assertNotNull(отказ, "MCR обязан держаться до сцен 3 и 4")

        // три стороны, у каждой нужда
        val коды = (1..3).map { n ->
            val ответ = router.handle("POST", "/v2/stakeholders", параметры,
                """{"name":"Сторона $n","role":"customer"}""")!!
            ответ.body.path("code").asText()
        }
        коды.forEachIndexed { i, код ->
            router.handle("POST", "/v2/needs", параметры,
                """{"statement":"нужда стороны ${i + 1} в телеметрии","owner":"$код"}""")
        }

        // цель, закрывающая все нужды
        val нужды = router.handle("GET", "/v2/entities", параметры + ("kind" to "need"), null)!!
            .body.path("items").map { it.path("code").asText() }
        router.handle("POST", "/v2/goals", параметры,
            """{"statement":"отслеживаемость перевозок","year":2033,"covers":${mapper.writeValueAsString(нужды)}}""")

        val фаза = router.handle("GET", "/v2/phase", параметры, null)!!.body
        assertEquals("done", сцена(фаза, "3").path("state").asText(), сцена(фаза, "3").path("blockers").toString())
        assertEquals("done", сцена(фаза, "4").path("state").asText(), сцена(фаза, "4").path("blockers").toString())

        // Сцены 3 и 4 прожиты, но MCR ждёт ещё и сервисов (сцена 6): точка
        // держится ровно до тех пор, пока держится хоть одно её условие.
        val mcr = фаза.path("gates").single { it.path("key").asText() == "MCR" }
        assertTrue(
            mcr.path("blocking").toString().contains("сцена 6"),
            "MCR обязан ждать покрытия нужд сервисами: ${mcr.path("blocking")}",
        )
        val всёЕщёОтказ = runCatching {
            router.handle("POST", "/v2/gates/MCR/pass", параметры, """{"author":"Чернов Д."}""")
        }.exceptionOrNull()
        assertNotNull(всёЕщёОтказ, "точку с невыполненным условием фиксировать нельзя")
    }
}
