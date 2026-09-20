// Раздача требований по целям — один вызов, приём обратимый (20.09).
//
// Проверяется устройство, а не модель: промпт несёт полные перечни целей и
// требований; ответ ложится запуском с предложениями; код вне проекта отбит
// поимённо; стоящий источник не пишется второй раз; приём ДОПИСЫВАЕТ цель
// источником требования, не трогая прежние основания; отмена снимает ровно
// дописанное.
package orbita.ai

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.ai.api.AiFactory
import orbita.ai.internal.GoalCoverageDistributor
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.EntityStore
import orbita.kernel.api.Provenance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoalCoverageTest {

    private val mapper = ObjectMapper()
    private val store: EntityStore = ПамятьПоля()
    private val канал = КаналЦелей()
    private val служба = AiFactory.service(store, канал, mapper)
    private val раздача = GoalCoverageDistributor(store, служба, mapper)
    private val область = Area.Project(ПРОЕКТ)
    private val провенанс = Provenance(Channel.MANUAL, АВТОР)

    private fun поле() {
        store.create(
            ПРОЕКТ, "project", область, "1",
            mapper.createObjectNode().put("name", "ПМИ-7").put("knowledge_v2", true), провенанс,
        )
        store.create(
            "MG-0041", "goal", область, "4",
            mapper.createObjectNode().put("statement", "Подтвердить реализуемость: верификация баллистики и покрытия"),
            провенанс,
        )
        store.create(
            "MG-0042", "goal", область, "4",
            mapper.createObjectNode().put("statement", "Провести лётную демонстрацию"), провенанс,
        )
        val материал = store.create(
            "SD-0001", "material", область, "2", mapper.createObjectNode().put("name", "Записка миссии"), провенанс,
        )
        listOf(
            "RE-0001" to "Система должна обеспечивать отслеживаемость 100 % объектов перечня",
            "RE-0002" to "Система должна доставлять сообщение класса B′ не более 180 мин",
        ).forEach { (код, текст) ->
            val док = mapper.createObjectNode().put("title", код).put("statement", текст).put("level", "project")
            док.putArray("source").addObject().put("kind", "material").put("ref", материал.id)
            store.create(код, "requirement", область, "8", док, провенанс, status = "Draft")
        }
    }

    @Test
    fun `промпт несёт полные перечни целей и требований`() {
        поле()
        канал.ответ = ОТВЕТ

        раздача.distribute(ПРОЕКТ, АВТОР)

        val промпт = канал.промпты.single()
        listOf("MG-0041", "MG-0042", "RE-0001", "RE-0002").forEach {
            assertTrue(it in промпт, "в промпте есть $it")
        }
        assertTrue("источником" in промпт, "сказано, что цель становится источником требования")
        assertTrue("выдумывать связь нельзя" in промпт, "модель не выдумывает связь там, где её нет")
    }

    @Test
    fun `код вне проекта отбит поимённо, а цель без требования названа`() {
        поле()
        канал.ответ = ОТВЕТ

        val итог = раздача.distribute(ПРОЕКТ, АВТОР)

        assertTrue(итог.refused.any { "MG-9999" in it }, "цели вне проекта нет: ${итог.refused}")
        assertTrue(итог.refused.any { "RE-0099" in it }, "требования вне проекта нет: ${итог.refused}")
        assertEquals(listOf("MG-0042"), итог.unassigned, "цель, которой не нашлось требования, названа: ${итог.unassigned}")
        assertEquals(1, итог.links.size, "осталась одна годная связь: ${итог.links.map { it.need + "→" + it.target }}")
        assertEquals("RE-0001", итог.links.single().need)
        assertEquals("MG-0041", итог.links.single().target)
    }

    @Test
    fun `приём дописывает цель источником, прежнее основание цело, отмена снимает ровно дописанное`() {
        поле()
        канал.ответ = ОТВЕТ
        val запуск = раздача.distribute(ПРОЕКТ, АВТОР)

        val принято = раздача.accept(ПРОЕКТ, запуск.id, listOf(запуск.links.single().id), АВТОР, "сцена 8")
        assertEquals(1, принято.linked, принято.note)
        val требование = store.byCode(область, "RE-0001")!!
        val источники = требование.doc.path("source").map { it.path("kind").asText() to it.path("ref").asText() }
        assertEquals(2, источники.size, "источник ДОПИСАН, прежний цел: $источники")
        assertTrue(источники.any { it.first == "material" }, "материал записки на месте: $источники")
        assertTrue(
            источники.any { it.first == "goal" && it.second == store.byCode(область, "MG-0041")!!.id },
            "цель встала источником: $источники",
        )

        // Второй приём той же связи ничего не удваивает.
        val повтор = раздача.accept(ПРОЕКТ, запуск.id, listOf(запуск.links.single().id), АВТОР, "ещё раз")
        assertEquals(0, повтор.linked, повтор.note)

        val отмена = раздача.undo(ПРОЕКТ, запуск.id, АВТОР)
        assertEquals(1, отмена.unlinked, отмена.note)
        val после = store.byCode(область, "RE-0001")!!.doc.path("source")
            .map { it.path("kind").asText() }
        assertEquals(listOf("material"), после, "снято ровно дописанное: $после")
    }

    @Test
    fun `без целей или без требований раздача отказывает словами`() {
        store.create(
            ПРОЕКТ, "project", область, "1",
            mapper.createObjectNode().put("name", "пустой").put("knowledge_v2", true), провенанс,
        )
        val пусто = assertFailsWith<IllegalArgumentException> { раздача.distribute(ПРОЕКТ, АВТОР) }
        assertTrue("целей в проекте нет" in пусто.message.orEmpty(), пусто.message.orEmpty())

        store.create("MG-0041", "goal", область, "4", mapper.createObjectNode().put("statement", "цель"), провенанс)
        val безТребований = assertFailsWith<IllegalArgumentException> { раздача.distribute(ПРОЕКТ, АВТОР) }
        assertTrue("требований в проекте нет" in безТребований.message.orEmpty(), безТребований.message.orEmpty())
    }

    private companion object {
        const val ПРОЕКТ = "PJ-7200"
        const val АВТОР = "Иванов И."
        val ОТВЕТ: String = """{"requirements":[
            {"requirement":"RE-0001","goals":["MG-0041","MG-9999"],"reason":"отслеживаемость подтверждает реализуемость покрытия"},
            {"requirement":"RE-0099","goals":["MG-0042"],"reason":"такого требования в проекте нет"},
            {"requirement":"RE-0002","goals":[],"reason":"не к чему"}
        ]}"""
    }
}

/** Канал-подмена: без сети; промпты видны тесту. */
private class КаналЦелей : orbita.ai.api.Transport {
    var ответ: String = """{"requirements":[]}"""
    val промпты = mutableListOf<String>()
    override fun ask(
        prompt: String,
        model: String?,
        maxTokens: Int?,
        schema: com.fasterxml.jackson.databind.JsonNode?,
    ): orbita.ai.api.Answer {
        промпты += prompt
        return orbita.ai.api.Answer(text = ответ, model = "модель-теста", tokensIn = 10, tokensOut = 20)
    }
}
