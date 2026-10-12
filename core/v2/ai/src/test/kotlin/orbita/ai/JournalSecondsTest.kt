// Журнал ИИ: у каждого вызова — токены и секунды (ответ владельца 25.09).
// Секунды меряет служба вокруг живого вызова; ответ из журнала несёт секунды
// исходного вызова. Кэш-попадание — тоже событие журнала: повтор пишет вторую
// запись с cached=true, чтобы рядом с прогоном, взявшим ответ из кэша,
// в журнале не было пусто.
package orbita.ai

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.ai.api.AiFactory
import orbita.ai.api.Answer
import orbita.ai.api.Transport
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Provenance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JournalSecondsTest {
    private val mapper = ObjectMapper()
    private val store = ПамятьПоля()
    private val проект = "PJ-8040"

    @Test
    fun `секунды вызова ложатся в журнал рядом с токенами, повтор берёт их из журнала`() {
        store.create(проект, "project", Area.Project(проект), "1", mapper.createObjectNode().put("name", "Журнал"), Provenance(Channel.MANUAL, "тест"))
        val служба = AiFactory.service(store, Transport { _, _, _, _ -> Thread.sleep(120); Answer("ответ", "модель-теста", 11, 7) }, mapper)
        val ответ = служба.ask(проект, "document_render", "промпт")
        assertNotNull(ответ.seconds, "секунды измерены службой")
        assertTrue(ответ.seconds!! >= 0.1, "не меньше времени ответа: ${ответ.seconds}")
        val запись = служба.journal(проект).single()
        assertEquals(11, запись.tokensIn); assertEquals(7, запись.tokensOut)
        assertEquals(ответ.seconds, запись.seconds, "в журнале те же секунды")
        assertTrue(store.list(Area.Project(проект), "ai_call").single().doc.path("seconds").isNumber, "поле истины `ai_call.seconds`")

        val повтор = служба.ask(проект, "document_render", "промпт")
        assertTrue(повтор.cached)
        assertEquals(ответ.seconds, повтор.seconds, "ответ из журнала несёт секунды исходного вызова")
        val записи = служба.journal(проект)
        assertEquals(2, записи.size, "кэш-попадание — тоже событие журнала")
        assertEquals(1, записи.count { it.cached }, "одна запись — из кэша")
        assertEquals(1, записи.count { !it.cached }, "одна запись — живой вызов")
        val документы = store.list(Area.Project(проект), "ai_call").map { it.doc.path("cached").asBoolean() }
        assertEquals(setOf(false, true), документы.toSet(), "поле истины `ai_call.cached` у обеих записей")
    }
}
