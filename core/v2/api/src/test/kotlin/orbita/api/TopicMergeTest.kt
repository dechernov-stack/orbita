// Знания v2, шаг D1: корпусные темы и связи фактов.
//
// Тема — АДРЕС среза для синтеза. Пока «телеметрия» из записки и «сбор
// телеметрии» из ТЗ живут двумя темами, срез рвётся по документам, и синтез
// перестаёт идти «по всему полю». Здесь проверяется, что слияние чинит адрес
// и НИЧЕГО не переписывает: факты остаются при своих темах, а читаются по
// цепочке до головы.
//
// Модели тут нет: разбор приходит готовым JSON — проверяется то, что система
// считает ПО ДАННЫМ, а не то, как отвечает модель.
//
// Две строки ниже ждут соседних шагов волны и до них красные — это НЕ
// украшение теста, а договор, который им надо выполнить:
//   · `Intake.mergeTopic` в knowledge/api/Ports.kt (владелец — шаг портов);
//   · маршрут POST /v2/topics/{код}/merge в KnowledgeRoutes.kt.
// Пока порта нет, слияние зовётся у реализации (см. `слить`); когда он
// появится, вызов станет `знания.mergeTopic(...)` и строка приведения уйдёт.
package orbita.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.ai.api.AiFactory
import orbita.ai.api.Answer
import orbita.ai.api.Transport
import orbita.api.internal.KnowledgeRoutes
import orbita.api.internal.V2Router
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.Provenance
import orbita.knowledge.api.Authority
import orbita.knowledge.api.KnowledgeFactory
import orbita.knowledge.api.Topic
import orbita.library.api.LibraryFactory
import orbita.process.api.ProcessFactory
import orbita.readiness.api.ReadinessFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TopicMergeTest {

    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val проект = "PJ-9621"
    private val область = Area.Project(проект)
    private val знания = KnowledgeFactory.intake(store, links, mapper)

    private val шаблонФазы = mapper.readTree(
        TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые/ШАБЛОН-ФАЗЫ-PRE-A-NASA.json").toFile(),
    )

    private val служба = AiFactory.service(store, Transport { _, _, _ -> Answer("{}", "тест", 0, 0) }, mapper)

    private val router: V2Router by lazy {
        V2Router(
            store, links,
            ProcessFactory.engine(
                template = { шаблонФазы },
                evaluator = ReadinessFactory.gateEvaluator(
                    store, links, scenesDone = { emptySet() }, gatesPassed = { mutableSetOf() },
                ),
                passedGates = { mutableSetOf() },
                gatePlan = { emptyMap() },
            ),
            LibraryFactory.shelves(store) { шаблонФазы },
            знания,
            orbita.formulation.api.FormulationFactory.formulation(store, links),
            mapper,
            knowledgeRoutes = KnowledgeRoutes(
                знания, AiFactory.atomize(store, знания, служба, mapper), служба, mapper,
            ),
        )
    }

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        store.create(
            проект, "project", область, "1",
            mapper.createObjectNode().put("name", "Корпус тем"),
            Provenance(Channel.MANUAL, "Иванов И."),
        )
    }

    /**
     * Слияние тем.
     *
     * Пока `Intake.mergeTopic` не объявлен портом, метод зовётся у
     * реализации: логика слияния — предмет этого шага, а порт и маршрут
     * приходят соседними. Подмена реализации от этого не ломается: тест
     * просит ровно тот метод, который порт и объявит.
     */
    private fun слить(тема: String, вГолову: String, author: String = "Иванов И.", reason: String = "один предмет"): Topic =
        (знания as orbita.knowledge.internal.EntityIntake).mergeTopic(проект, тема, вГолову, author, reason)

    private fun материал(имя: String, вид: String, ранг: String, текст: String): String =
        знания.putMaterial(проект, имя, вид, текст, "Иванов И.", authority = ранг)

    private fun якорь(материал: String): String = знания.canon(проект, материал).first { it.kind == "para" }.anchor

    /** Разбор с одной темой и одним фактом: тема живёт своим именем в своём документе. */
    private fun разбор(материал: String, тема: String, утверждение: String): String = """
        {"topics":[{"label":"$тема"}],"actions":[],"facts":[
          {"kind":"framing","topic":"$тема","subject":"система","predicate":"$утверждение",
           "value":"да","source":{"anchor":"${якорь(материал)}"},"source_mark":"И"}]}
    """.trimIndent()

    /** Записка и ТЗ говорят об одном предмете разными словами — так и родятся две темы. */
    private fun дваДокумента(): Pair<String, String> {
        val записка = материал(
            "Записка миссии", "mission_memo", Authority.MANDATORY,
            "Записка миссии\nТелеметрия принимается не реже 30 мин.",
        )
        val тз = материал(
            "ТЗ", "tor", Authority.MANDATORY,
            "Техническое задание\nСбор телеметрии ведётся с интервалом не более 30 мин.",
        )
        знания.putFacts(проект, записка, разбор(записка, "телеметрия", "приём телеметрии"), "Иванов И.")
        знания.putFacts(проект, тз, разбор(тз, "сбор телеметрии", "сбор телеметрии"), "Иванов И.")
        return записка to тз
    }

    private fun тема(метка: String): String =
        assertNotNull(
            store.list(область, "topic").firstOrNull { it.doc.path("label").asText() == метка },
            "темы «$метка» в проекте нет",
        ).code

    @Test
    fun `темы двух документов, слитые в одну, дают одну голову и общий счёт фактов`() {
        дваДокумента()
        assertEquals(2, знания.topics(проект).size, "до слияния предмет разорван по документам")

        val голова = тема("телеметрия")
        val слитая = тема("сбор телеметрии")
        assertEquals(голова, слить(слитая, голова).id, "слияние возвращает голову — адрес среза")

        val темы = знания.topics(проект)
        assertEquals(1, темы.size, "слитая тема с экрана уходит: у предмета одна тема, не две")
        assertEquals(голова, темы.single().id)
        assertEquals(2, темы.single().facts, "счёт фактов складывается по цепочке merged_into: ${темы.single()}")

        // Факты НЕ переписаны: слияние чинит адрес, а не трогает принятое.
        val адреса = store.list(область, "fact").map { it.doc.path("topic").asText() }.toSet()
        assertEquals(setOf(голова, слитая), адреса, "факты остались при своих темах: $адреса")

        // Тождество тем видно связью — тем же способом, каким его предлагали.
        val связь = links.from(assertNotNull(store.byCode(область, слитая)).id, "same_as")
        assertEquals(1, связь.size, "слияние записано связью same_as: $связь")
        assertTrue("Иванов И." in связь.single().rationale.orEmpty(), связь.single().rationale.orEmpty())
    }

    @Test
    fun `разрешение ведёт на голову цепочки, а метка слитой темы её не воскрешает`() {
        дваДокумента()
        val голова = тема("телеметрия")
        val слитая = тема("сбор телеметрии")
        слить(слитая, голова)

        store.create(
            "ND-0001", "need", область, "3",
            mapper.createObjectNode().put("statement", "приём телеметрии не реже 30 мин"),
            Provenance(Channel.MANUAL, "Иванов И."),
        )
        // Разрешается СЛИТАЯ тема — адрес обязан лечь на голову: иначе факты
        // читались бы по голове, а сущность нашлась бы у темы, которой на
        // экране уже нет.
        assertEquals(голова, знания.resolveTopic(проект, слитая, "ND-0001", "Иванов И.").id)
        assertEquals("ND-0001", знания.topics(проект).single().resolvedTo)

        // Повтор метки слитой темы — тот же предмет, а не вторая тема.
        assertEquals(голова, знания.addTopic(проект, "сбор телеметрии", "Иванов И.").id)
        assertEquals(1, знания.topics(проект).size, "повтор метки тем не плодит")
    }

    @Test
    fun `связи между фактами приходят разбором и повтором не удваиваются`() {
        val записка = материал(
            "Записка миссии", "mission_memo", Authority.MANDATORY,
            "Записка миссии\nТелеметрия принимается не реже 30 мин.\nПриём ведёт наземный комплекс.",
        )
        val блоки = знания.canon(проект, записка).filter { it.kind == "para" }
        val ответ = """
            {"topics":[],"actions":[],"facts":[
              {"kind":"framing","subject":"система","predicate":"приём телеметрии не реже 30 мин",
               "value":"да","source":{"anchor":"${блоки[0].anchor}"},"source_mark":"И"},
              {"kind":"framing","subject":"наземный комплекс","predicate":"ведёт приём телеметрии",
               "value":"да","source":{"anchor":"${блоки[1].anchor}"},"source_mark":"И"}],
             "links":[{"from":1,"to":0,"type":"supports","rationale":"кто ведёт приём — основание срока приёма"}]}
        """.trimIndent()
        val принято = знания.putFacts(проект, записка, ответ, "Иванов И.")
        assertEquals(2, принято.accepted.size, принято.refused.toString())
        assertTrue("связей между фактами 1" in принято.note, принято.note)

        val первый = assertNotNull(store.byCode(область, принято.accepted[0].id))
        val второй = assertNotNull(store.byCode(область, принято.accepted[1].id))
        assertEquals(
            setOf(второй.id to первый.id),
            links.from(второй.id, "supports").map { it.from to it.to }.toSet(),
            "связь стоит между самими фактами, а не полем в документе",
        )

        знания.putFacts(проект, записка, ответ, "Иванов И.")
        assertEquals(1, links.from(второй.id, "supports").size, "повторный разбор той же версии связь не удваивает")
    }

    @Test
    fun `связь неизвестного типа и связь без причины отвергаются внятным отказом`() {
        val записка = материал(
            "Записка миссии", "mission_memo", Authority.MANDATORY,
            "Записка миссии\nТелеметрия принимается не реже 30 мин.\nПриём ведёт наземный комплекс.",
        )
        val блоки = знания.canon(проект, записка).filter { it.kind == "para" }
        val ответ = """
            {"topics":[],"actions":[],"facts":[
              {"kind":"framing","subject":"система","predicate":"приём телеметрии",
               "value":"да","source":{"anchor":"${блоки[0].anchor}"},"source_mark":"И"},
              {"kind":"framing","subject":"наземный комплекс","predicate":"ведёт приём",
               "value":"да","source":{"anchor":"${блоки[1].anchor}"},"source_mark":"И"}],
             "links":[
               {"from":0,"to":1,"type":"похоже","rationale":"на глаз"},
               {"from":0,"to":1,"type":"supports","rationale":""},
               {"from":0,"to":0,"type":"same_as","rationale":"сам с собой"}]}
        """.trimIndent()
        val принято = знания.putFacts(проект, записка, ответ, "Иванов И.")
        assertEquals(2, принято.accepted.size, "брак связей фактов не отменяет: отказ называется поимённо")
        assertTrue(принято.refused.any { "«похоже»" in it }, принято.refused.toString())
        assertTrue(принято.refused.any { "без обоснования" in it }, принято.refused.toString())
        assertTrue(принято.refused.any { "сам с собой" in it }, принято.refused.toString())
        assertTrue(
            links.from(assertNotNull(store.byCode(область, принято.accepted[0].id)).id).isEmpty(),
            "ни одна отклонённая связь в модель не попала",
        )
    }

    @Test
    fun `слияние без автора и слияние в самоё себя не ставятся`() {
        дваДокумента()
        val голова = тема("телеметрия")
        val слитая = тема("сбор телеметрии")

        val безАвтора = assertFailsWith<IllegalArgumentException> { слить(слитая, голова, author = " ") }
        assertTrue("сливает человек" in безАвтора.message.orEmpty(), безАвтора.message.orEmpty())

        val сСобой = assertFailsWith<IllegalArgumentException> { слить(голова, голова) }
        assertTrue("закольцевалось" in сСобой.message.orEmpty(), сСобой.message.orEmpty())

        слить(слитая, голова)
        val обратно = assertFailsWith<IllegalArgumentException> { слить(голова, слитая) }
        assertTrue("закольцевалось" in обратно.message.orEmpty(), обратно.message.orEmpty())
        assertEquals(1, знания.topics(проект).size, "ни один отказ слияния тем не изменил")
    }

    @Test
    fun `две темы, разрешённые в разные сущности, слиянием не сводятся`() {
        дваДокумента()
        listOf("ND-0001", "ND-0002").forEach { код ->
            store.create(
                код, "need", область, "3",
                mapper.createObjectNode().put("statement", "нужда $код"),
                Provenance(Channel.MANUAL, "Иванов И."),
            )
        }
        val голова = тема("телеметрия")
        val вторая = тема("сбор телеметрии")
        знания.resolveTopic(проект, голова, "ND-0001", "Иванов И.")
        знания.resolveTopic(проект, вторая, "ND-0002", "Иванов И.")

        val отказ = assertFailsWith<IllegalArgumentException> { слить(вторая, голова) }
        assertTrue("за человека" in отказ.message.orEmpty(), отказ.message.orEmpty())
        assertEquals(2, знания.topics(проект).size, "обе темы на месте: слияние ничего не выбрало само")
    }

    @Test
    fun `разбор тем не сливает - снимок тем до и после совпадает`() {
        val (записка, тз) = дваДокумента()
        val до = знания.topics(проект).map { it.id to it.facts }.toSet()
        assertEquals(2, до.size, "две темы об одном предмете — это и есть повод предложить слияние")
        // Тот же разбор ещё раз: служба предлагает, но не сливает — слияние
        // остаётся щелчком человека (ИИ не сливает, правило прав онтологии).
        знания.putFacts(проект, записка, разбор(записка, "телеметрия", "приём телеметрии"), "Иванов И.")
        знания.putFacts(проект, тз, разбор(тз, "сбор телеметрии", "сбор телеметрии"), "Иванов И.")
        assertEquals(до, знания.topics(проект).map { it.id to it.facts }.toSet(), "разбор тем не сливает")
    }

    @Test
    fun `маршрут слияния требует автора`() {
        дваДокумента()
        val голова = тема("телеметрия")
        val слитая = тема("сбор телеметрии")
        val параметры = mapOf("project" to проект)

        val безАвтора = router.handle("POST", "/v2/topics/$слитая/merge", параметры, """{"into":"$голова"}""")
        assertEquals(400, безАвтора?.code, "слияние без автора не ставится: сливает человек, ИИ предлагает")
        assertEquals(2, знания.topics(проект).size, "отказ маршрута тем не слил")

        val сАвтором = assertNotNull(
            router.handle(
                "POST", "/v2/topics/$слитая/merge", параметры,
                """{"into":"$голова","author":"Иванов И.","reason":"один предмет"}""",
            ),
            "маршрут слияния тем не отвечает",
        )
        assertTrue(сАвтором.code == 200 || сАвтором.code == 201, "слияние с автором проходит: ${сАвтором.code}")
        assertEquals(1, знания.topics(проект).size, "после слияния у предмета одна тема")
    }
}
