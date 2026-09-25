// Ворота «устав один» на правке на месте (ответ владельца 25.09): тот же
// сторож, что на загрузке — второй `charter` в проекте → 409 с именем первого.
package orbita.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.api.internal.V2Router
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.ConflictException
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.Provenance
import orbita.knowledge.api.Authority
import orbita.knowledge.api.KnowledgeFactory
import orbita.library.api.LibraryFactory
import orbita.process.api.ProcessFactory
import orbita.readiness.api.ReadinessFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CharterGateTest {
    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val проект = "PJ-9870"
    private val область = Area.Project(проект)
    private val п = mapOf("project" to проект)
    private val шаблон = mapper.readTree(TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые/ШАБЛОН-ФАЗЫ-PRE-A-NASA.json").toFile())
    private val знания = KnowledgeFactory.intake(store, links, mapper)

    private val роутер: V2Router by lazy {
        val движок = ProcessFactory.engine(
            template = { шаблон },
            evaluator = ReadinessFactory.gateEvaluator(store, links, scenesDone = { emptySet() }, gatesPassed = { emptySet() }),
            passedGates = { mutableSetOf() }, gatePlan = { emptyMap() },
        )
        V2Router(store, links, движок, LibraryFactory.shelves(store) { шаблон }, знания, orbita.formulation.api.FormulationFactory.formulation(store, links), mapper)
    }

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        store.create(проект, "project", область, "1", mapper.createObjectNode().put("name", "Устав один"), Provenance(Channel.MANUAL, "Иванов И."))
    }

    @Test
    fun `правка роли на месте — второй устав отвергается конфликтом с именем первого, иная роль проходит`() {
        val устав = знания.putMaterial(проект, "Записка миссии", "mission_memo", "# Замысел\n\nМы строим систему связи.", "Иванов И.", rank = Authority.MANDATORY, role = "charter")
        val другой = знания.putMaterial(проект, "Аналитика рынка", "analysis", "# Рынок\n\nОбъём рынка растёт.", "Иванов И.", rank = Authority.REFERENCE)

        val отказ = assertFailsWith<ConflictException> {
            роутер.handle("PATCH", "/v2/entities/$другой", п, """{"fields":{"role":"charter"},"author":"Иванов И.","reason":"роль документа названа инженером"}""")
        }
        assertTrue("Записка миссии" in отказ.message!! && устав in отказ.message!!, отказ.message)
        assertEquals("", store.byCode(область, другой)!!.doc.path("role").asText(""), "роль не записана")

        val ок = роутер.handle("PATCH", "/v2/entities/$другой", п, """{"fields":{"role":"context"},"author":"Иванов И.","reason":"роль документа названа инженером"}""")!!
        assertEquals(200, ок.code, ок.body.toString())
        assertEquals("context", store.byCode(область, другой)!!.doc.path("role").asText())

        // Сам устав своей ролью правится: это не второй устав.
        val свой = роутер.handle("PATCH", "/v2/entities/$устав", п, """{"fields":{"role":"charter"},"author":"Иванов И.","reason":"повтор"}""")!!
        assertEquals(200, свой.code, свой.body.toString())
        // И загрузка второго устава — тот же конфликт, теми же словами.
        val приЗагрузке = assertFailsWith<ConflictException> {
            знания.putMaterial(проект, "Вторая записка", "mission_memo", "# Иное\n\nТекст.", "Иванов И.", rank = Authority.MANDATORY, role = "charter")
        }
        assertTrue("Записка миссии" in приЗагрузке.message!!)
    }
}
