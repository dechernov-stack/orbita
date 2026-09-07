// Норматив, принятый из разбора, ложится в СВОЮ карточку на полке.
//
// Один и тот же акт приходит на полку двумя дорогами: поставкой из записки
// (`load_normatives.py`) и принятым фактом живого разбора. Цитируют его
// при этом по-разному — «№2216» и «№ 2216», с редакцией в скобках и без.
// Если полка узнаёт запись только по коду, у ограничения окажется два
// разных основания на один и тот же ПП РФ. Здесь это закрыто тестом.
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

class NormativeAcceptTest {

    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val шаблонФазы = mapper.readTree(
        TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые/ШАБЛОН-ФАЗЫ-PRE-A-NASA.json").toFile(),
    )
    private val полки = LibraryFactory.shelves(store) { шаблонФазы }
    private val знания = KnowledgeFactory.intake(store, links, mapper, полки)

    // Разбор цитирует акт так, как он написан в записке: без пробела после
    // номера и без редакции — то есть НЕ так, как его несёт поставка.
    private val ОТВЕТ = """
        {"topics":[{"label":"нормативная база"}],
         "actions":[
           {"kind":"create_entity","target_kind":"normative_document","scene":"polka",
            "title":"завести норматив ПП РФ №2216","preview":"на полке появится ПП РФ №2216",
            "payload":{"designation":"ПП РФ от 22.12.2020 №2216",
                       "title":"Передача данных мониторинга через ЭРА-ГЛОНАСС"},"facts":[0]}
         ],
         "facts":[
           {"kind":"obligation","topic":"нормативная база","subject":"собственник ТС",
            "predicate":"передача данных мониторинга через ЭРА-ГЛОНАСС","value":"обязательно",
            "source":{"anchor":"s1#1"},"source_mark":"В","confidence":0.9}
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

    private val проект = "PJ-9310"

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        router.handle("POST", "/v2/projects", emptyMap(), """{"name":"Нормативы","code":"$проект"}""")
    }

    private fun принять(): String {
        val материал = знания.putMaterial(
            проект, "Записка миссии", "mission_memo",
            "Нормативная база\nПередача данных мониторинга обязательна через ЭРА-ГЛОНАСС.",
            "Петрова М.",
        )
        router.handle("POST", "/v2/intake/atomize", mapOf("project" to проект),
            """{"material":"$материал","intent":"разбери по сущностям","author":"Петрова М."}""")
        val задание = store.list(Area.Project(проект), "intake_task")
            .first { it.doc.path("actions").size() > 0 }
        router.handle("POST", "/v2/intake/${задание.code}/accept", mapOf("project" to проект),
            """{"chosen":[0],"author":"Иванов И."}""")
        return материал
    }

    @Test
    fun `принятый норматив попадает в карточку поставки, а не заводит двойника`() {
        // Поставка положила акт заранее — со своим кодом и полными реквизитами.
        полки.put(
            "normative_document", "ПП-РФ-от-22122020-N-2216",
            mapper.createObjectNode()
                .put("designation", "ПП РФ от 22.12.2020 № 2216")
                .put("title", "О передаче данных мониторинга транспортных средств")
                .put("edition", "действует на 29.08.2026"),
            "поставка v2",
        )
        принять()

        val карточки = полки.of("normative_document")
        assertEquals(1, карточки.size, "на полке один акт, как его ни процитируй: $карточки")
        assertEquals("ПП-РФ-от-22122020-N-2216", карточки.first().code, "акт остался в своей карточке")
    }

    @Test
    fun `норматив живёт на полке, а не в проекте, и помнит свой факт`() {
        принять()
        assertEquals(
            0, store.list(Area.Project(проект), "normative_document").size,
            "один и тот же акт не заводится в каждом проекте заново",
        )
        val карточка = store.byCode(Area.Library, полки.of("normative_document").first().code)!!
        assertEquals(
            1, links.from(карточка.id, "derived_from_fact").size,
            "карточка помнит факт, из которого её завели",
        )
    }
}
