// Живое поле знаний: канон → факты с якорями → диспозиции.
//
// Проверяется то, ради чего конвейер поднят: факт без якоря не существует,
// величина без единицы — не факт, повтор той же версии документа вызова не
// делает, диспозицию ставит человек и обязан объяснить. Сеть в тесте не
// нужна: транспорт подменён — проверяется поведение системы, а не модели.
package orbita.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.ai.api.AiFactory
import orbita.ai.api.Answer
import orbita.ai.api.Transport
import orbita.api.internal.KnowledgeRoutes
import orbita.api.internal.V2Router
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.LinkRegistry
import orbita.knowledge.api.KnowledgeFactory
import orbita.library.api.LibraryFactory
import orbita.process.api.ProcessFactory
import orbita.readiness.api.ReadinessFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KnowledgeIntakeTest {

    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links: LinkRegistry = KernelFactory.linkRegistry(TestDbV2.conn)
    private val знания = KnowledgeFactory.intake(store, links, mapper)
    private val шаблонФазы = mapper.readTree(
        TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые/ШАБЛОН-ФАЗЫ-PRE-A-NASA.json").toFile(),
    )

    /** Звонков наружу столько, сколько записано: кэш проверяется счётчиком. */
    private var звонков = 0

    private val ОТВЕТ = """
        {"topics":[{"label":"масса платформы"},{"label":"частотный режим"}],
         "actions":[
           {"kind":"create_entity","target_kind":"stakeholder","scene":"3",
            "title":"завести сторону «Оператор»","preview":"появится сторона «Оператор», роль «оператор»",
            "payload":{"name":"Оператор","role":"operator"},"facts":[1]},
           {"kind":"create_entity","target_kind":"constraint","scene":"5",
            "title":"ограничение по частотам","preview":"появится ограничение: частоты только после решения ГКРЧ",
            "payload":{"text":"частоты только после решения ГКРЧ","category":"регуляторное"},"facts":[1]},
           {"kind":"create_entity","target_kind":"goal","scene":"4",
            "title":"цель по массе","preview":"появится цель с показателем массы",
            "payload":{"statement":"уложиться в массу платформы","metric":"78 кг"},"facts":[0]}
         ],
         "facts":[
           {"kind":"quantity","topic":"масса платформы","subject":"платформа","predicate":"масса сухая",
            "value":"78","unit":"кг","source":{"anchor":"s1#1"},"source_mark":"В","confidence":0.8},
           {"kind":"obligation","topic":"частотный режим","subject":"оператор",
            "predicate":"использование частот только после решения ГКРЧ","value":"обязательно",
            "source":{"anchor":"s1#2"},"source_mark":"И","confidence":0.9},
           {"kind":"quantity","topic":"масса платформы","subject":"платформа","predicate":"масса стартовая",
            "value":"84","source":{"anchor":"s1#1"},"source_mark":"В","confidence":0.7},
           {"kind":"framing","subject":"миссия","predicate":"без якоря придумано",
            "value":"нечто","source":{"anchor":"s99#9"},"source_mark":"П","confidence":0.3}
         ]}
    """.trimIndent()

    private val транспорт = Transport { _, _, _ ->
        звонков += 1
        Answer(ОТВЕТ, "модель-проверки", 100, 200)
    }

    private val служба = AiFactory.service(store, транспорт, mapper)

    private val router: V2Router by lazy {
        val движок = ProcessFactory.engine(
            template = { шаблонФазы },
            evaluator = ReadinessFactory.gateEvaluator(
                store, links, scenesDone = { emptySet() }, gatesPassed = { mutableSetOf() },
            ),
            passedGates = { mutableSetOf() },
            outputCounter = { проект, вид -> store.list(Area.Project(проект), вид).size },
            gatePlan = { emptyMap() },
        )
        V2Router(
            store, links, движок,
            LibraryFactory.shelves(store) { шаблонФазы },
            знания,
            orbita.formulation.api.FormulationFactory.formulation(store, links),
            mapper,
            knowledgeRoutes = KnowledgeRoutes(
                знания, AiFactory.atomize(store, знания, служба, mapper), служба, mapper,
            ),
        )
    }

    private val ТЕКСТ = """
        Записка миссии
        Платформа несёт сухую массу 78 кг и стартовую 84 кг.
        Использование частот допускается только после решения ГКРЧ.
        Целевой горизонт
        К 2033 году отслеживаемость перевозок доведена до утверждённого перечня.
    """.trimIndent()

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        звонков = 0
    }

    private fun материал(проект: String): String {
        router.handle("POST", "/v2/projects", emptyMap(), """{"name":"Знания","code":"$проект"}""")
        return знания.putMaterial(проект, "Записка миссии", "mission_memo", ТЕКСТ, "Петрова М.")
    }

    @Test
    fun `канон Д1 даёт блоки с якорями и повторяется отпечатком`() {
        val проект = "PJ-9300"
        val код = материал(проект)
        val канон = router.handle("GET", "/v2/intake/canon",
            mapOf("project" to проект, "material" to код), null)!!.body
        val якоря = канон.path("blocks").map { it.path("anchor").asText() }
        assertTrue(якоря.contains("s1"), "заголовок стал разделом: $якоря")
        assertTrue(якоря.any { it.startsWith("s1#") }, "абзацы получили якоря: $якоря")
        assertEquals(
            знания.fingerprint(проект, код), канон.path("fingerprint").asText(),
            "отпечаток канона — та же версия документа, тот же разбор",
        )
    }

    @Test
    fun `факт без якоря и величина без единицы не принимаются`() {
        val проект = "PJ-9301"
        val код = материал(проект)
        val ответ = router.handle("POST", "/v2/intake/atomize", mapOf("project" to проект),
            """{"material":"$код","intent":"разбери по сущностям","author":"Петрова М."}""")!!
        assertEquals(201, ответ.code)
        assertEquals(2, ответ.body.path("accepted").asInt(), "приняты два чистых факта")
        val отказы = ответ.body.path("refusals").joinToString("; ") { it.asText() }
        assertTrue("без единицы" in отказы, "величина без единицы отклонена: $отказы")
        assertTrue("не найден в каноне" in отказы, "выдуманный якорь отклонён: $отказы")
        assertEquals(2, ответ.body.path("topics").size(), "темы заведены разбором")
    }

    @Test
    fun `повтор той же версии документа вызова не делает`() {
        val проект = "PJ-9302"
        val код = материал(проект)
        val тело = """{"material":"$код","intent":"разбери по сущностям","author":"Петрова М."}"""
        router.handle("POST", "/v2/intake/atomize", mapOf("project" to проект), тело)
        assertEquals(1, звонков, "первый разбор — живой вызов")
        val второй = router.handle("POST", "/v2/intake/atomize", mapOf("project" to проект), тело)!!
        assertEquals(1, звонков, "повтор той же версии берёт ответ из журнала")
        assertTrue(
            "из журнала" in второй.body.path("note").asText(),
            "повтор говорит, что вызова не было: ${второй.body.path("note").asText()}",
        )
        // Кэша ответа мало: повторный приём тех же фактов не должен их
        // удваивать — факт узнаётся по месту в документе (живой прогон).
        assertEquals(0, второй.body.path("accepted").asInt(), "повтор новых фактов не создал")
        assertEquals(2, знания.facts(проект).size, "фактов в проекте столько же, сколько было")
        // План при повторе не схлопывается: действия ссылаются на факты
        // НОМЕРАМИ ответа, и номер обязан вести к уже существующему факту
        val задание = store.list(Area.Project(проект), "intake_task")
            .first { it.doc.path("actions").size() > 0 }
        assertEquals(3, задание.doc.path("actions").size(), "план тот же, что и в первый раз")
    }

    @Test
    fun `диспозицию ставит человек и обязан объяснить`() {
        val проект = "PJ-9303"
        val код = материал(проект)
        router.handle("POST", "/v2/intake/atomize", mapOf("project" to проект),
            """{"material":"$код","intent":"разбери по сущностям","author":"Петрова М."}""")
        val факт = знания.facts(проект).first()
        assertEquals("FREE", факт.disposition.name, "свежий факт свободен")

        val ошибка = runCatching {
            router.handle("POST", "/v2/facts/${факт.id}/disposition", mapOf("project" to проект),
                """{"disposition":"adopted","author":"Иванов И."}""")
        }.exceptionOrNull()
        assertTrue(
            ошибка?.message?.contains("причины") == true,
            "диспозиция без причины не ставится: ${ошибка?.message}",
        )

        val ответ = router.handle("POST", "/v2/facts/${факт.id}/disposition", mapOf("project" to проект),
            """{"disposition":"adopted","reason":"величина подтверждена паспортом платформы","author":"Иванов И."}""")!!
        assertEquals("adopted", ответ.body.path("disposition").asText())
    }

    @Test
    fun `повторная загрузка того же материала не спотыкается о код задания`() {
        val проект = "PJ-9305"
        val код = материал(проект)
        val первое = знания.plan(проект, код, "разбери по сущностям", "Петрова М.")
        // Тот же материал, то же задание — план пересчитывается, а не падает
        val второе = знания.plan(проект, код, "разбери по сущностям", "Петрова М.")
        assertEquals(первое.id, второе.id, "то же задание — та же карточка задания")

        // Другое намерение по тому же материалу — новое задание своим кодом
        val третье = знания.plan(проект, код, "это норматив — заведи", "Петрова М.")
        assertTrue(третье.id != первое.id, "другое задание — другой код: ${третье.id}")

        // И второй материал не сталкивается с первым
        val второйМатериал = знания.putMaterial(проект, "Анализ", "analysis", ТЕКСТ, "Петрова М.")
        val четвёртое = знания.plan(проект, второйМатериал, "разбери по сущностям", "Петрова М.")
        assertTrue(
            setOf(первое.id, третье.id, четвёртое.id).size == 3,
            "коды заданий не сталкиваются: ${первое.id}, ${третье.id}, ${четвёртое.id}",
        )
    }

    @Test
    fun `акцепт плана заводит сущности со связью к фактам и считает долю знаний`() {
        val проект = "PJ-9306"
        val код = материал(проект)
        router.handle("POST", "/v2/intake/atomize", mapOf("project" to проект),
            """{"material":"$код","intent":"разбери по сущностям","author":"Петрова М."}""")

        val задание = store.list(Area.Project(проект), "intake_task")
            .first { it.doc.path("actions").size() > 0 }
        val план = router.handle("GET", "/v2/intake/${задание.code}", mapOf("project" to проект), null)!!.body
        assertEquals(3, план.path("actions").size(), "план собран разбором")
        val первое = план.path("actions")[0]
        assertTrue(
            первое.path("preview").asText().isNotBlank() && первое.path("payload").size() > 0,
            "действие показывает, ЧТО появится, до нажатия: ${первое.path("preview").asText()}",
        )

        // Принимаем два действия из трёх: третье снято человеком
        val итог = router.handle("POST", "/v2/intake/${задание.code}/accept", mapOf("project" to проект),
            """{"chosen":[0,1],"author":"Иванов И."}""")!!
        assertEquals(201, итог.code)
        assertEquals(2, итог.body.path("created").asInt(), "создано ровно принятое")
        assertEquals(1, store.list(Area.Project(проект), "stakeholder").size)
        assertEquals(1, store.list(Area.Project(проект), "constraint").size)
        assertEquals(0, store.list(Area.Project(проект), "goal").size, "снятое действие не выполняется")

        // Связь к факту стоит — по ней и считается доля знаний
        val сторона = store.list(Area.Project(проект), "stakeholder").first()
        assertTrue(
            links.from(сторона.id, "derived_from_fact").isNotEmpty(),
            "сущность из знаний обязана помнить свой факт",
        )
        val покрытие = router.handle("GET", "/v2/knowledge/coverage", mapOf("project" to проект), null)!!.body
        assertEquals(2, покрытие.path("total").asInt())
        assertEquals(2, покрытие.path("from_facts").asInt())
        assertEquals(100, покрытие.path("share_percent").asInt(), "всё заведённое пришло из фактов")

        // Диспозиции: принятое — adopted, снятое — noted, а не пусто
        val факты = знания.facts(проект).associateBy { it.id }
        val принятые = факты.values.filter { it.disposition.name == "ADOPTED" }
        val рассмотренные = факты.values.filter { it.disposition.name == "NOTED" }
        assertTrue(принятые.isNotEmpty(), "принятый факт помечен adopted")
        assertTrue(рассмотренные.isNotEmpty(), "снятый факт остаётся рассмотренным, а не исчезает")
    }

    @Test
    fun `журнал вызовов знает, за что заплачено`() {
        val проект = "PJ-9304"
        val код = материал(проект)
        router.handle("POST", "/v2/intake/atomize", mapOf("project" to проект),
            """{"material":"$код","intent":"разбери по сущностям","author":"Петрова М."}""")
        val журнал = router.handle("GET", "/v2/ai/journal", mapOf("project" to проект), null)!!.body
        assertEquals(1, журнал.path("items").size())
        val запись = журнал.path("items")[0]
        assertEquals("intake_atomize", запись.path("kind").asText())
        assertEquals(100, запись.path("tokens_in").asInt())
    }
}
