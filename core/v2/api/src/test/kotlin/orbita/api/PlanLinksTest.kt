// Имя соседа в плане разбора — это СВЯЗЬ, а не строка в документе.
//
// Разбор называет носителя нужды по-человечески («Минтранс России»).
// Если приём положит это имя в документ нужды, связи не будет: нужда
// повиснет на выходе сцены 3 и в матрице покрытия, а человек узнает об
// этом на воротах — когда чинить дороже. Здесь проверено, что имя
// становится связью, а ненайденное имя названо вслух при приёме.
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
import orbita.knowledge.api.KnowledgeFactory
import orbita.library.api.LibraryFactory
import orbita.process.api.ProcessFactory
import orbita.readiness.api.ReadinessFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlanLinksTest {

    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val шаблонФазы = mapper.readTree(
        TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые/ШАБЛОН-ФАЗЫ-PRE-A-NASA.json").toFile(),
    )
    private val полки = LibraryFactory.shelves(store) { шаблонФазы }
    private val знания = KnowledgeFactory.intake(store, links, mapper, полки)

    // Действие 0 заводит сторону, действие 1 — нужду ЭТОЙ стороны по имени,
    // действие 2 — нужду стороны, которой в проекте нет.
    private val ОТВЕТ = """
        {"topics":[{"label":"потребности"}],
         "actions":[
           {"kind":"create_entity","target_kind":"stakeholder","scene":"3",
            "title":"завести сторону «Минтранс России»","preview":"появится сторона «Минтранс России»",
            "payload":{"name":"Минтранс России","role":"regulator"},"facts":[0]},
           {"kind":"create_entity","target_kind":"need","scene":"3",
            "title":"завести потребность в телеметрии","preview":"появится потребность",
            "payload":{"owner":"Минтранс России","statement":"непрерывная телеметрия перевозок"},"facts":[1]},
           {"kind":"create_entity","target_kind":"need","scene":"3",
            "title":"завести потребность неизвестной стороны","preview":"появится потребность",
            "payload":{"owner":"Росатом","statement":"связь в удалённых районах"},"facts":[1]}
         ],
         "facts":[
           {"kind":"relation","topic":"потребности","subject":"Минтранс России",
            "predicate":"регулирует перевозки","value":"да",
            "source":{"anchor":"s1#1"},"source_mark":"И","confidence":0.9},
           {"kind":"capability","topic":"потребности","subject":"перевозчик",
            "predicate":"нуждается в непрерывной телеметрии","value":"да",
            "source":{"anchor":"s1#2"},"source_mark":"И","confidence":0.8}
         ]}
    """.trimIndent()

    private val служба = AiFactory.service(store, Transport { _, _, _ ->
        Answer(ОТВЕТ, "модель-проверки", 100, 200)
    }, mapper)

    private val router: V2Router by lazy {
        V2Router(
            store, links,
            ProcessFactory.engine(
                template = { шаблонФазы },
                evaluator = ReadinessFactory.gateEvaluator(
                    store, links, scenesDone = { emptySet() }, gatesPassed = { mutableSetOf() },
                ),
                passedGates = { mutableSetOf() },
                outputCounter = { проект, вид -> store.list(Area.Project(проект), вид).size },
                gatePlan = { emptyMap() },
            ),
            полки, знания,
            orbita.formulation.api.FormulationFactory.formulation(store, links),
            mapper,
            knowledgeRoutes = KnowledgeRoutes(
                знания, AiFactory.atomize(store, знания, служба, mapper), служба, mapper,
            ),
        )
    }

    private val проект = "PJ-9320"

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        router.handle("POST", "/v2/projects", emptyMap(), """{"name":"Связи плана","code":"$проект"}""")
    }

    private fun принять(): com.fasterxml.jackson.databind.JsonNode {
        val материал = знания.putMaterial(
            проект, "Записка миссии", "mission_memo",
            "Потребности\nМинтранс России регулирует перевозки.\nПеревозчику нужна телеметрия.",
            "Петрова М.",
        )
        router.handle("POST", "/v2/intake/atomize", mapOf("project" to проект),
            """{"material":"$материал","intent":"разбери по сущностям","author":"Петрова М."}""")
        val задание = store.list(Area.Project(проект), "intake_task")
            .first { it.doc.path("actions").size() > 0 }
        return router.handle("POST", "/v2/intake/${задание.code}/accept", mapOf("project" to проект),
            """{"chosen":[0,1,2],"author":"Иванов И."}""")!!.body
    }

    @Test
    fun `названный носитель становится связью, а не полем документа`() {
        принять()
        val нужда = store.list(Area.Project(проект), "need")
            .first { it.doc.path("statement").asText().startsWith("непрерывная") }
        assertFalse(
            нужда.doc.has("owner"),
            "имя носителя не остаётся в документе: там оно связью не работает",
        )
        val носители = links.to(нужда.id, "owns").map { store.byId(it.from)!!.code }
        assertEquals(1, носители.size, "у нужды ровно один носитель: $носители")
        assertEquals(
            "Минтранс России",
            store.byCode(Area.Project(проект), носители.first())!!.doc.path("name").asText(),
            "носитель — та самая сторона, которую назвал разбор",
        )
    }

    @Test
    fun `ненайденный носитель назван вслух при приёме, а не молчком`() {
        val итог = принять()
        val заметки = итог.path("notes").map { it.asText() }
        assertEquals(1, заметки.size, "сказано ровно об одном незакрытом: $заметки")
        assertTrue("Росатом" in заметки.first(), "названо имя, которого нет: ${заметки.first()}")
        assertTrue("сцене 3" in заметки.first(), "сказано, где чинить: ${заметки.first()}")

        // Нужда всё равно заведена: терять разбор из-за одного имени нельзя.
        assertEquals(2, store.list(Area.Project(проект), "need").size)
        val сирота = store.list(Area.Project(проект), "need")
            .first { it.doc.path("statement").asText().startsWith("связь") }
        assertTrue(links.to(сирота.id, "owns").isEmpty(), "связи нет — и это видно, а не спрятано")
    }
}
