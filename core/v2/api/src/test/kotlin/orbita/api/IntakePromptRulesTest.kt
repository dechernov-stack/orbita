// Правило промпта разбора (поставка 12.09, ЗАМЕЧАНИЯ-ПМИ5-СЦЕНА-3): интерес
// стороны → нужда [П]; общая нужда → стороны по смыслу; у каждой стороны
// ≥ 1 нужда; влияние — из формулировки. Промпт обязан нести правило
// словами — иначе разбор снова выделит стороны без нужд.
package orbita.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.ai.api.AiFactory
import orbita.ai.api.Answer
import orbita.ai.api.Transport
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.Provenance
import orbita.knowledge.api.KnowledgeFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class IntakePromptRulesTest {
    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val проект = "PJ-9810"
    private val intake = KnowledgeFactory.intake(store, links, mapper)

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        store.create(проект, "project", Area.Project(проект), "1", mapper.readTree("""{"name":"Промпт","standard":"NASA-7120","phase":"Pre-Phase A"}"""), Provenance(Channel.MANUAL, "Чернов Д."))
    }

    @Test
    fun `промпт разбора несёт правило нужд из интереса стороны и общих нужд по смыслу`() {
        val материал = intake.putMaterial(проект, "Записка", "note", "§3 ГКРЧ — роль/интерес: частотные присвоения до запуска.", "Иванов И.")
        var промпт = ""
        val служба = AiFactory.service(store, Transport { p, _, _ -> промпт = p; Answer("""{"topics":[],"actions":[],"facts":[]}""", "тест", 1, 1) }, mapper)
        AiFactory.atomize(store, intake, служба, mapper).atomize(проект, материал, "разбери по сущностям", "Иванов И.")
        listOf("интерес стороны", "ЭТО НУЖДА с меткой [П]", "ПО СМЫСЛУ", "хотя бы одна нужда", "influence", "decides", "informed").forEach {
            assertTrue(it in промпт, "в промпте нет «$it»")
        }
    }
}
