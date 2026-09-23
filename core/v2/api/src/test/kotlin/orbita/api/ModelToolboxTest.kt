// Инструменты модели (шип 4 §2): каждый отвечает объектом с источником; чего
// нет — словом missing, а не выдумкой.
package orbita.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.api.internal.ModelToolbox
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
import kotlin.test.assertTrue

class ModelToolboxTest {
    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val шаблон = mapper.readTree(TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые/ШАБЛОН-ФАЗЫ-PRE-A-NASA.json").toFile())
    private val полки = LibraryFactory.shelves(store) { шаблон }
    private val проект = "PJ-9815"
    private val область = Area.Project(проект)
    private val пров = Provenance(Channel.MANUAL, "Иванов И.")
    private val индекс = KnowledgeFactory.index(store, KernelFactory.textIndex(TestDbV2.conn, mapper), null, mapper)
    private val движок = ProcessFactory.engine(
        template = { шаблон },
        evaluator = ReadinessFactory.gateEvaluator(store, links, scenesDone = { emptySet() }, gatesPassed = { emptySet() }),
        passedGates = { mutableSetOf() }, gatePlan = { emptyMap() },
    )
    private val набор = ModelToolbox(store, KnowledgeFactory.glossary(store, mapper), индекс, движок, полки, mapper)

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        store.create(проект, "project", область, "1", mapper.readTree("""{"name":"Инструменты","standard":"NASA-7120"}"""), пров)
        store.create("GT-ST-001", "glossary_term", Area.Library, null, mapper.readTree(
            """{"term_ru":"Ространснадзор","class":"stakeholder","definition":"надзор на транспорте","synonyms":["ФСНСТ"]}"""), пров, status = "accepted")
        store.create("NR-2216", "normative_document", Area.Library, null, mapper.readTree(
            """{"designation":"ПП РФ от 22.12.2020 № 2216","title":"О передаче данных","clauses":[{"clause":"обязанность","text":"передавать координаты с интервалом не более 30 с","who":"собственники ТС"},{"clause":"срок","text":"вступает в силу с 1 марта 2021"}]}"""), пров)
        полки.put("pbs_template", "PBS-T", mapper.readTree("""{"code":"PBS-T","nodes":[{"code":"OBC","name":"Бортовой компьютер","kind":"subsystem","level":3,"parent":"BUS","params":[{"key":"mass","name":"масса","unit":"kg"}]}]}"""), "тест")
        store.create("SK-0001", "stakeholder", область, "3", mapper.readTree("""{"name":"Минтранс России","role":"customer"}"""), пров)
        store.create("ND-0001", "need", область, "3", mapper.readTree("""{"statement":"непрерывный мониторинг","stakeholders":[]}"""), пров)
        store.create("F-0001", "fact", область, "3", mapper.readTree(
            """{"kind":"quantity","subject":"телеметрия","predicate":"интервал передачи","value":"30","unit":"с","quote":"с интервалом передачи 30 секунд","material":"M-1","mark":"И","rank":"mandatory","evidence":"claimed","disposition":"free","source":{"material":"M-1","anchor":"s1#1"}}"""), пров)
        индекс.rebuild(проект)
    }

    private fun вызов(имя: String, vararg вход: Pair<String, String>) =
        набор.handler(проект).call(имя, mapper.createObjectNode().also { у -> вход.forEach { (к, з) -> у.put(к, з) } })

    @Test
    fun `семь инструментов отвечают источником, отсутствующее — словом missing`() {
        assertEquals(listOf("term", "node", "interface", "facts", "clauses", "scene", "similar"), набор.tools().map { it.name })
        val термин = вызов("term", "name" to "фснст")
        assertEquals("GT-ST-001", термин.path("code").asText()); assertEquals("Ространснадзор", термин.path("term").asText())
        assertTrue(вызов("term", "name" to "Пентагон").has("missing"))
        val узел = вызов("node", "code" to "obc")
        assertEquals("Бортовой компьютер", узел.path("name").asText()); assertEquals("mass", узел.path("params")[0].path("key").asText())
        assertTrue(вызов("interface", "code" to "IF-NONE").has("missing"))
        val факты = вызов("facts", "query" to "интервал передачи")
        assertEquals("F-0001", факты.path("facts")[0].path("code").asText())
        val пункты = вызов("clauses", "norm" to "ПП 2216", "topic" to "интервал координаты")
        assertEquals("NR-2216", пункты.path("code").asText())
        assertEquals(listOf(1), пункты.path("clauses").map { it.path("n").asInt() }, "по теме — только пункт про интервал")
        val сцена = вызов("scene", "key" to "3")
        assertEquals("3", сцена.path("key").asText())
        assertEquals("SK-0001", сцена.path("stakeholders")[0].path("code").asText())
        assertEquals("ND-0001", сцена.path("needs")[0].path("code").asText())
        assertTrue(сцена.path("exit").size() > 0, "условия выхода сцены названы")
        val похожее = вызов("similar", "text" to "интервал передачи", "class" to "fact")
        assertEquals("F-0001", похожее.path("items")[0].path("ref").asText())
        assertTrue(вызов("neizvestno").has("error"))
    }
}
