// Раздача нужд по целям и сервисам — один вызов, приём обратимый (17.09).
//
// Проверяется устройство, а не модель: промпт несёт полные перечни и истину
// о связях; ответ ложится запуском с предложениями; коды вне проекта отбиты
// поимённо; уже существующая связь не заводится второй раз; приём заводит
// `covers` и класс покрывшего сервиса; отмена снимает связи и возвращает класс.
package orbita.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.ai.api.AiFactory
import orbita.ai.api.Answer
import orbita.ai.api.Transport
import orbita.ai.internal.NeedDistributor
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.Link
import orbita.kernel.api.LinkRegistry
import orbita.kernel.api.Provenance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NeedDistributionTest {

    private val mapper = ObjectMapper()
    private val store: EntityStore = ПамятьПоля()
    private val links = ПамятьСвязейРаздачи()
    private val канал = КаналРаздачи()
    private val служба = AiFactory.service(store, канал, mapper)
    private val раздача = NeedDistributor(store, links, служба, mapper)
    private val область = Area.Project(ПРОЕКТ)
    private val провенанс = Provenance(Channel.MANUAL, АВТОР)

    private fun поле() {
        store.create(ПРОЕКТ, "project", область, "1", mapper.createObjectNode().put("name", "ПМИ-7").put("knowledge_v2", true), провенанс)
        val сторона = store.create("SK-0001", "stakeholder", область, "3",
            mapper.createObjectNode().put("name", "Минтранс России").put("role", "customer"), провенанс)
        listOf("ND-0001" to "единое оперативное управление транспортом", "ND-0002" to "резервный канал в белых пятнах", "ND-0003" to "суверенитет данных", "ND-0004" to "серийность космического сегмента")
            .forEach { (код, текст) ->
                val нужда = store.create(код, "need", область, "3", mapper.createObjectNode().put("statement", текст), провенанс)
                links.link("owns", сторона.id, нужда.id, провенанс)
            }
        val цель = mapper.createObjectNode().put("statement", "развернуть базовый национальный контур").put("year", "2030")
        цель.putObject("measure").put("value", "50").put("unit", "КА")
        store.create("GL-0001", "goal", область, "4", цель, провенанс)
        store.create("GL-0002", "goal", область, "4", mapper.createObjectNode().put("statement", "суверенный контур данных"), провенанс)
        store.create("SV-0001", "service", область, "6",
            mapper.createObjectNode().put("name", "резервный канал мониторинга").put("qos_class", "B′"), провенанс)
        store.create("SV-0002", "service", область, "6",
            mapper.createObjectNode().put("name", "платформа связности и API").put("qos_class", "A′/B′/C′"), провенанс)
        // Одна связь уже есть: цель GL-0001 закрывает ND-0001. И сервис уже
        // покрывает ND-0003, но класса у нужды нет — TBR ждёт сцены 6.
        links.link("covers", store.byCode(область, "GL-0001")!!.id, store.byCode(область, "ND-0001")!!.id, провенанс)
        links.link("covers", store.byCode(область, "SV-0002")!!.id, store.byCode(область, "ND-0003")!!.id, провенанс)
    }

    @Test
    fun `промпт несёт полные перечни и истину о связях, а не правила из кода`() {
        поле()
        канал.ответ = ОТВЕТ

        раздача.distribute(ПРОЕКТ, АВТОР)

        val промпт = канал.промпты.single()
        listOf("ND-0001", "ND-0002", "ND-0003", "GL-0001", "GL-0002", "SV-0001", "SV-0002").forEach {
            assertTrue(it in промпт, "в промпте есть $it")
        }
        assertTrue("сторона: Минтранс России" in промпт, "у нужды назван носитель")
        assertTrue("уже ведёт к: GL-0001" in промпт, "существующая связь названа — модель её не выдумывает заново")
        assertTrue("класс: B′" in промпт, "класс сервиса в перечне")
        assertTrue("Истина (ОНТОЛОГИЯ-ФОРМИРОВАНИЯ)" in промпт && "цель · `needs`" in промпт, "правила связи — из истины")
    }

    @Test
    fun `карта связей ложится запуском, чужие коды отбиты, существующая связь помечена`() {
        поле()
        канал.ответ = ОТВЕТ

        val запуск = раздача.distribute(ПРОЕКТ, АВТОР)

        assertEquals("done", запуск.status)
        val поНужде = запуск.links.groupBy { it.need }
        assertEquals(setOf("ND-0001", "ND-0002", "ND-0003"), поНужде.keys, "связи по трём нуждам")
        assertEquals(listOf("ND-0004"), запуск.unassigned, "нужда без связи названа поимённо")
        assertTrue(запуск.refused.any { "GL-0099" in it }, "код вне проекта отбит поимённо: ${запуск.refused}")
        val существующая = запуск.links.single { it.need == "ND-0001" && it.target == "GL-0001" }
        assertTrue(существующая.exists, "уже лежащая связь помечена «уже есть»")
        assertTrue(!существующая.classPending, "у связи с целью класса нет")
        val покрытаяБезКласса = запуск.links.single { it.need == "ND-0003" && it.target == "SV-0002" }
        assertTrue(покрытаяБезКласса.exists && покрытаяБезКласса.classPending, "покрытая сервисом нужда без класса: класс ждёт приёма")
        assertEquals("B′", запуск.links.single { it.target == "SV-0001" }.qosClass, "класс сервиса едет со связью")
        assertTrue(запуск.links.all { it.reason.isNotBlank() }, "у каждой связи есть причина")
        // Запуск лежит своей меткой и в перечень синтеза не попадает.
        val запись = store.byCode(область, запуск.id)!!
        assertEquals("synthesis_run", запись.kind)
        assertTrue(запись.doc.path("tags").any { it.asText() == "distribution" })
        assertTrue(запись.doc.path("tags").none { it.asText() == "synthesis" })
        assertEquals(запуск.id, раздача.latest(ПРОЕКТ)!!.id)
    }

    @Test
    fun `приём заводит связи и класс покрывшего сервиса, отмена возвращает как было`() {
        поле()
        канал.ответ = ОТВЕТ
        val запуск = раздача.distribute(ПРОЕКТ, АВТОР)
        val новые = запуск.links.filter { !it.exists }.map { it.id }
        val существующие = запуск.links.filter { it.exists }.map { it.id }

        val итог = раздача.accept(ПРОЕКТ, запуск.id, новые + существующие, АВТОР, "по смыслу")

        assertEquals(новые.size, итог.linked, "заведены только новые связи: ${итог.skipped}")
        assertTrue(итог.skipped.any { "уже есть" in it }, "существующая связь с целью названа пропущенной")
        assertEquals("A′/B′/C′", store.byCode(область, "ND-0003")!!.doc.path("qos_class").asText(), "покрытой нужде без класса класс дан и без новой связи")
        val нужда2 = store.byCode(область, "ND-0002")!!
        assertTrue(links.to(нужда2.id, "covers").any { store.byId(it.from)!!.code == "GL-0001" }, "цель закрывает нужду связью covers")
        assertTrue(links.to(нужда2.id, "covers").any { store.byId(it.from)!!.code == "SV-0001" }, "сервис покрывает нужду")
        assertEquals("B′", нужда2.doc.path("qos_class").asText(), "класс нужды — от покрывшего сервиса")
        // ND-0001 тоже получила класс — от платформы (SV-0002): связи с ней не было; ND-0003 — по лежащей связи.
        assertEquals(3, итог.classes)
        assertEquals("A′/B′/C′", store.byCode(область, "ND-0001")!!.doc.path("qos_class").asText())
        // Принятыми числятся новые связи и лежащая связь сервиса, по которой дан класс.
        assertEquals(новые.size + 1, раздача.view(ПРОЕКТ, запуск.id).links.count { it.accepted })

        val отмена = раздача.undo(ПРОЕКТ, запуск.id, АВТОР)

        assertEquals(новые.size, отмена.unlinked)
        val после = store.byCode(область, "ND-0002")!!
        assertTrue(links.to(после.id, "covers").isEmpty(), "связи сняты")
        assertTrue(после.doc.path("qos_class").isMissingNode, "класс возвращён как был — его не было")
        assertTrue(раздача.view(ПРОЕКТ, запуск.id).links.none { it.accepted })
        assertTrue(links.to(store.byCode(область, "ND-0001")!!.id, "covers").size == 1, "чужая, прежняя связь не тронута")
        assertTrue(links.to(store.byCode(область, "ND-0003")!!.id, "covers").size == 1, "лежащая связь сервиса не тронута")
        assertTrue(store.byCode(область, "ND-0003")!!.doc.path("qos_class").isMissingNode, "класс, данный по лежащей связи, возвращён")
    }

    @Test
    fun `без нужд раздавать нечего — отказ словами, а не пустой запуск`() {
        store.create(ПРОЕКТ, "project", область, "1", mapper.createObjectNode().put("name", "пусто"), провенанс)
        val беда = assertFailsWith<IllegalArgumentException> { раздача.distribute(ПРОЕКТ, АВТОР) }
        assertTrue("нужд в проекте нет" in беда.message.orEmpty())
        assertTrue(канал.промпты.isEmpty(), "вызова не было")
    }

    private companion object {
        const val ПРОЕКТ = "PJ-7100"
        const val АВТОР = "Иванов И."
        val ОТВЕТ: String = """{"needs":[
            {"need":"ND-0001","goals":["GL-0001","GL-0099"],"services":["SV-0002"],"reason":"единый поток данных — это платформа и базовый контур"},
            {"need":"ND-0002","goals":["GL-0001"],"services":["SV-0001"],"reason":"резервный канал — сервис резервного канала, контур покрытия"},
            {"need":"ND-0003","goals":["GL-0002"],"services":["SV-0002"],"reason":"суверенитет данных — контур и платформа"},
            {"need":"ND-0004","goals":[],"services":[],"reason":"не к чему"}
        ]}"""
    }
}

/** Канал-подмена: без сети; промпты видны тесту. */
private class КаналРаздачи : Transport {
    var ответ: String = """{"needs":[]}"""
    val промпты = mutableListOf<String>()
    override fun ask(prompt: String, model: String?, maxTokens: Int?, schema: JsonNode?): Answer {
        промпты += prompt
        return Answer(text = ответ, model = "модель-теста", tokensIn = 10, tokensOut = 20)
    }
}

/** Реестр связей в памяти: ровно то, что нужно раздаче. */
private class ПамятьСвязейРаздачи : LinkRegistry {
    private val связи = linkedMapOf<String, Link>()
    private var счётчик = 0
    override fun link(type: String, from: String, to: String, provenance: Provenance, rationale: String?, subtype: String?): Link {
        счётчик += 1
        val связь = Link("LN-$счётчик", type, from, to, rationale, provenance, subtype)
        связи[связь.id] = связь
        return связь
    }
    override fun unlink(id: String, provenance: Provenance) { связи.remove(id) }
    override fun byId(id: String): Link? = связи[id]
    override fun from(id: String, type: String?): List<Link> = связи.values.filter { it.from == id && (type == null || it.type == type) }
    override fun to(id: String, type: String?): List<Link> = связи.values.filter { it.to == id && (type == null || it.type == type) }
    override fun confirm(id: String, by: String): Link = связи.getValue(id)
}
