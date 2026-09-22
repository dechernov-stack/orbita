// Сценарии сцены 9 предложением из сервисов и цепочек Arcadia (шип 1, п. 1.7).
//
// Проверяется устройство, а не модель: промпт несёт сервисы, узлы, стороны и
// цепочки полки; ответ ложится запуском с предложениями; участники разрешаются
// в узлы (кодом и именем), стороны и внешние системы, неразрешённый назван;
// приём заводит цепочки сцены 9 с шагами «участник — что происходит», повтор
// ничего не удваивает, откат снимает ровно заведённое. Мера: 8 сервисов →
// не меньше 3 сценариев.
package orbita.ai

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.ai.api.AiFactory
import orbita.ai.internal.ScenarioProposer
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.EntityStore
import orbita.kernel.api.Provenance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScenarioProposalTest {

    private val mapper = ObjectMapper()
    private val store: EntityStore = ПамятьПоля()
    private val канал = КаналСценариев()
    private val служба = AiFactory.service(store, канал, mapper)
    private val предложение = ScenarioProposer(store, служба, mapper)
    private val область = Area.Project(ПРОЕКТ)
    private val провенанс = Provenance(Channel.MANUAL, АВТОР)

    /** Восемь сервисов, три узла, две стороны, полка Arcadia с одной цепочкой. */
    private fun поле() {
        store.create(ПРОЕКТ, "project", область, "1", mapper.createObjectNode().put("name", "ПМИ-7"), провенанс)
        (1..8).forEach { n ->
            store.create("SVC-000$n", "service", область, "6",
                mapper.createObjectNode().put("name", "сервис $n").put("qos_class", if (n == 8) "A′" else "B′"), провенанс)
        }
        listOf("TERM" to "Терминал абонентский", "SAT" to "КА", "DC" to "ЦОД").forEach { (код, имя) ->
            store.create(код, "component", область, "7", mapper.createObjectNode().put("name", имя).put("kind", "node"), провенанс)
        }
        listOf("ST-0001" to "Заказчик", "ST-0002" to "МЧС").forEach { (код, имя) ->
            store.create(код, "stakeholder", область, "3", mapper.createObjectNode().put("name", имя).put("role", "customer"), провенанс)
        }
        val полка = mapper.createObjectNode().put("title", "Arcadia")
        val sa = полка.putObject("SA")
        sa.putArray("functions").also { ф ->
            ф.addObject().put("code", "F-01").put("name", "Принять сообщение терминала").put("allocated_to", "SAT")
            ф.addObject().put("code", "F-02").put("name", "Доставить в ЦОД").put("allocated_to", "DC")
        }
        sa.putArray("functional_chains").addObject().put("code", "FC-01").put("name", "Доставка тревоги")
            .also { it.putArray("steps").add("F-01").add("F-02") }
        store.create("ARC-9002", "architecture_template", Area.Library, null, полка, провенанс)
    }

    @Test
    fun `промпт несёт сервисы, узлы, стороны и цепочки полки`() {
        поле()
        канал.ответ = ОТВЕТ
        предложение.propose(ПРОЕКТ, АВТОР)
        val промпт = канал.промпты.single()
        listOf("SVC-0001", "SVC-0008", "TERM", "ST-0002", "Доставка тревоги", "Принять сообщение терминала").forEach {
            assertTrue(it in промпт, "в промпте есть $it")
        }
        assertTrue("Выдуманных участников не бывает" in промпт)
    }

    @Test
    fun `восемь сервисов дают не меньше трёх сценариев, участники разрешены в узлы, стороны и внешние системы`() {
        поле()
        канал.ответ = ОТВЕТ
        val итог = предложение.propose(ПРОЕКТ, АВТОР)
        assertTrue(итог.scenarios.size >= 3, "мера 1.7: 8 сервисов → ≥ 3 сценария, а тут ${итог.scenarios.size}")
        val штатный = итог.scenarios.first { it.mode == "nominal" }
        val виды = штатный.steps.map { it.participant to it.participantKind }
        assertEquals("node", штатный.steps[0].participantKind, "код узла разрешён: $виды")
        assertEquals("SAT", штатный.steps[0].ref)
        assertEquals("node", штатный.steps[1].participantKind, "узел по имени разрешён: $виды")
        assertEquals("DC", штатный.steps[1].ref)
        assertEquals("external", штатный.steps[2].participantKind, "внешняя система названа словами: $виды")
        assertEquals("side", штатный.steps[3].participantKind, "сторона по имени с пометой: $виды")
        assertEquals("ST-0001", штатный.steps[3].ref)
        val тревога = итог.scenarios.first { it.mode == "alarm" }
        assertEquals(listOf("Диспетчер порта"), тревога.unresolved, "неразрешённый участник назван, а не подставлен")
        assertEquals(listOf("SVC-0008"), тревога.services)
        assertTrue(итог.refused.any { "SVC-0099" in it }, "сервис вне проекта отбит поимённо: ${итог.refused}")
        assertTrue(итог.refused.any { "без шагов" in it }, "сценарий без шагов отбит: ${итог.refused}")
    }

    @Test
    fun `приём заводит цепочки сцены 9 с шагами, повтор не удваивает, откат снимает заведённое`() {
        поле()
        канал.ответ = ОТВЕТ
        val запуск = предложение.propose(ПРОЕКТ, АВТОР)
        val выбранные = запуск.scenarios.take(2).map { it.id }
        val принято = предложение.accept(ПРОЕКТ, запуск.id, выбранные, АВТОР)
        assertEquals(2, принято.created.size, принято.note)
        val цепочки = store.list(область, "functional_chain")
        assertEquals(2, цепочки.size)
        val первая = цепочки.first { it.doc.path("name").asText() == "Штатная доставка телеметрии" }
        assertEquals("9", первая.bornIn)
        val шаги = первая.doc.path("steps")
        assertEquals(4, шаги.size())
        assertEquals("SAT", шаги[0].path("actor").asText())
        assertEquals("SAT", шаги[0].path("component").asText(), "участник-узел записан кодом узла")
        assertEquals("ЦОД", шаги[1].path("actor").asText())
        assertEquals("DC", шаги[1].path("component").asText(), "узел по имени тоже держится состава")
        assertTrue(шаги[2].path("component").isMissingNode, "внешняя система — не узел")
        assertTrue("штатный" in первая.doc.path("notes").asText(), "режим — заметкой: ${первая.doc.path("notes")}")

        val повтор = предложение.accept(ПРОЕКТ, запуск.id, выбранные, АВТОР)
        assertEquals(0, повтор.created.size, повтор.note)
        assertEquals(2, повтор.skipped.size)
        assertTrue(предложение.view(ПРОЕКТ, запуск.id).scenarios.count { it.accepted } == 2)

        val отмена = предложение.undo(ПРОЕКТ, запуск.id, АВТОР)
        assertEquals(2, отмена.cancelled, отмена.note)
        assertTrue(store.list(область, "functional_chain").all { it.status == "cancelled" })
    }

    @Test
    fun `без сервисов предлагать не из чего — отказ словами`() {
        store.create(ПРОЕКТ, "project", область, "1", mapper.createObjectNode().put("name", "пустой"), провенанс)
        val отказ = assertFailsWith<IllegalArgumentException> { предложение.propose(ПРОЕКТ, АВТОР) }
        assertTrue("сервисов в проекте нет" in отказ.message.orEmpty(), отказ.message.orEmpty())
    }

    private companion object {
        const val ПРОЕКТ = "PJ-7300"
        const val АВТОР = "Иванов И."
        val ОТВЕТ: String = """{"scenarios":[
            {"name":"Штатная доставка телеметрии","mode":"nominal","services":["SVC-0001","SVC-0002"],"reason":"путь сервиса по цепочке FC-01",
             "steps":[{"participant":"SAT","what":"принимает пакеты в зоне видимости"},
                      {"participant":"ЦОД","what":"принимает, маршрутизирует, ведёт SLA"},
                      {"participant":"ГАИС «ЭРА-ГЛОНАСС» (внешняя система)","what":"получает регуляторную телематику"},
                      {"participant":"Заказчик (сторона)","what":"видит объекты на своей платформе"}]},
            {"name":"Потеря наземного покрытия","mode":"off_nominal","services":["SVC-0003"],"reason":"резервный канал",
             "steps":[{"participant":"TERM","what":"переключается на спутниковый канал"},{"participant":"SAT","what":"принимает первым пролётом"}]},
            {"name":"Тревога класса A′","mode":"alarm","services":["SVC-0008","SVC-0099"],"reason":"приоритет",
             "steps":[{"participant":"TERM","what":"формирует тревожный пакет"},{"participant":"Диспетчер порта","what":"подтверждает приём"},{"participant":"ST-0002","what":"получает тревогу"}]},
            {"name":"Пустой","mode":"nominal","services":[],"reason":"","steps":[]}
        ]}"""
    }
}

/** Канал-подмена: без сети; промпты видны тесту. */
private class КаналСценариев : orbita.ai.api.Transport {
    var ответ: String = """{"scenarios":[]}"""
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
