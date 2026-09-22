// Функции → узлы состава — четвёртая раздача на общем порядке (шип 1).
//
// Проверяется устройство: промпт несёт функции и узлы с подсказкой полки;
// код вне проекта отбит; лежащее распределение помечено «уже есть»; приём
// дописывает узел в `allocated_to` функции, повтор не удваивает; откат
// снимает ровно дописанный узел, прежний цел.
package orbita.ai

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.ai.api.AiFactory
import orbita.ai.internal.FunctionAllocationDistributor
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.EntityStore
import orbita.kernel.api.Provenance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FunctionAllocationTest {

    private val mapper = ObjectMapper()
    private val store: EntityStore = ПамятьПоля()
    private val канал = КаналФункций()
    private val служба = AiFactory.service(store, канал, mapper)
    private val раздача = FunctionAllocationDistributor(store, служба, mapper)
    private val область = Area.Project(ПРОЕКТ)
    private val провенанс = Provenance(Channel.MANUAL, АВТОР)

    private fun поле() {
        store.create(ПРОЕКТ, "project", область, "1", mapper.createObjectNode().put("name", "ПМИ-7"), провенанс)
        val sat = store.create("SAT", "component", область, "7", mapper.createObjectNode().put("name", "КА").put("kind", "node").put("level", 1), провенанс)
        store.create("DC", "component", область, "7", mapper.createObjectNode().put("name", "ЦОД").put("kind", "node").put("level", 1), провенанс)
        store.create("FN-0001", "function", область, "A5",
            mapper.createObjectNode().put("name", "Принять сообщение терминала").put("layer", "SA").also { it.putArray("allocated_to") }, провенанс)
        store.create("FN-0002", "function", область, "A5",
            mapper.createObjectNode().put("name", "Доставить в ЦОД").put("layer", "SA").also { it.putArray("allocated_to").add(sat.id) }, провенанс)
        val полка = mapper.createObjectNode().put("title", "Arcadia")
        полка.putObject("SA").putArray("functions").addObject().put("code", "F-01").put("name", "Принять сообщение терминала").put("allocated_to", "PL-S")
        store.create("ARC-9002", "architecture_template", Area.Library, null, полка, провенанс)
    }

    @Test
    fun `промпт несёт функции, узлы и подсказку полки`() {
        поле()
        канал.ответ = ОТВЕТ
        раздача.distribute(ПРОЕКТ, АВТОР)
        val промпт = канал.промпты.single()
        listOf("FN-0001", "FN-0002", "SAT", "DC", "уже распределена на: SAT", "полка Arcadia относит к узлу PBS PL-S").forEach {
            assertTrue(it in промпт, "в промпте есть $it")
        }
        assertTrue("выдумывать узел нельзя" in промпт)
    }

    @Test
    fun `код вне проекта отбит, лежащее распределение помечено, приём и откат — ровно дописанное`() {
        поле()
        канал.ответ = ОТВЕТ
        val запуск = раздача.distribute(ПРОЕКТ, АВТОР)
        assertTrue(запуск.refused.any { "GW" in it }, "узла GW в проекте нет: ${запуск.refused}")
        assertTrue(запуск.refused.any { "FN-0099" in it }, "функции FN-0099 нет: ${запуск.refused}")
        val лежащая = запуск.links.single { it.need == "FN-0002" && it.target == "SAT" }
        assertTrue(лежащая.exists, "лежащее распределение помечено «уже есть»")
        val новая = запуск.links.single { it.need == "FN-0001" && it.target == "SAT" }
        assertTrue(!новая.exists)

        val итог = раздача.accept(ПРОЕКТ, запуск.id, listOf(новая.id, лежащая.id, запуск.links.single { it.target == "DC" }.id), АВТОР, "сцена 7")
        assertEquals(2, итог.linked, итог.note)
        assertTrue(итог.skipped.any { "уже распределена" in it }, "лежащее пропущено словами: ${итог.skipped}")
        val функция = store.byCode(область, "FN-0001")!!
        val узлы = функция.doc.path("allocated_to").map { store.byId(it.asText())?.code ?: it.asText() }
        assertEquals(listOf("SAT"), узлы, "узел дописан кодом id: $узлы")
        val вторая = store.byCode(область, "FN-0002")!!.doc.path("allocated_to").map { store.byId(it.asText())?.code }
        assertEquals(listOf("SAT", "DC"), вторая, "второй узел дописан, прежний цел")

        val повтор = раздача.accept(ПРОЕКТ, запуск.id, listOf(новая.id), АВТОР, "ещё раз")
        assertEquals(0, повтор.linked, повтор.note)

        val отмена = раздача.undo(ПРОЕКТ, запуск.id, АВТОР)
        assertEquals(2, отмена.unlinked, отмена.note)
        assertTrue(store.byCode(область, "FN-0001")!!.doc.path("allocated_to").isEmpty, "дописанное снято")
        assertEquals(listOf("SAT"), store.byCode(область, "FN-0002")!!.doc.path("allocated_to").map { store.byId(it.asText())?.code }, "прежний узел цел")
    }

    @Test
    fun `без функций или без узлов раздача отказывает словами`() {
        store.create(ПРОЕКТ, "project", область, "1", mapper.createObjectNode().put("name", "пустой"), провенанс)
        val пусто = assertFailsWith<IllegalArgumentException> { раздача.distribute(ПРОЕКТ, АВТОР) }
        assertTrue("функций в проекте нет" in пусто.message.orEmpty(), пусто.message.orEmpty())
        store.create("FN-0001", "function", область, "A5", mapper.createObjectNode().put("name", "функция").put("layer", "SA"), провенанс)
        val безУзлов = assertFailsWith<IllegalArgumentException> { раздача.distribute(ПРОЕКТ, АВТОР) }
        assertTrue("узлов состава в проекте нет" in безУзлов.message.orEmpty(), безУзлов.message.orEmpty())
    }

    private companion object {
        const val ПРОЕКТ = "PJ-7400"
        const val АВТОР = "Иванов И."
        val ОТВЕТ: String = """{"functions":[
            {"function":"FN-0001","nodes":["SAT","GW"],"reason":"приём сообщений — борт"},
            {"function":"FN-0002","nodes":["SAT","DC"],"reason":"доставка — борт и центр"},
            {"function":"FN-0099","nodes":["DC"],"reason":"такой функции нет"}
        ]}"""
    }
}

/** Канал-подмена: без сети; промпты видны тесту. */
private class КаналФункций : orbita.ai.api.Transport {
    var ответ: String = """{"functions":[]}"""
    val промпты = mutableListOf<String>()
    override fun ask(
        prompt: String,
        model: String?,
        maxTokens: Int?,
        schema: com.fasterxml.jackson.databind.JsonNode?,
    ): orbita.ai.api.Answer {
        промпты += prompt
        return orbita.ai.api.Answer(ответ, "модель-теста", 10, 20)
    }
}
