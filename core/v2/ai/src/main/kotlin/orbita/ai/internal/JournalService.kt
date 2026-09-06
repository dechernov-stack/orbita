// Служба вызовов: журнал и кэш по отпечатку.
//
// Правило владельца: один живой вызов на версию документа. Отсюда кэш не
// «оптимизация», а закон: повтор того же промпта в том же проекте
// возвращает записанный ответ и не звонит. Запись вызова живёт сущностью
// проекта — по журналу видно, за что заплачено и что пришло из кэша.
package orbita.ai.internal

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.ai.api.Answer
import orbita.ai.api.AiService
import orbita.ai.api.CallRecord
import orbita.ai.api.Transport
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.EntityStore
import orbita.kernel.api.Provenance
import java.security.MessageDigest
import java.time.OffsetDateTime

class JournalService(
    private val store: EntityStore,
    private val transport: Transport,
    private val mapper: ObjectMapper = ObjectMapper(),
) : AiService {

    override fun ask(project: String, kind: String, prompt: String, model: String?): Answer {
        val отпечаток = отпечатокПромпта(prompt)
        val записанный = store.list(Area.Project(project), "ai_call")
            .firstOrNull { it.doc.path("fingerprint").asText() == отпечаток }
        if (записанный != null) {
            return Answer(
                text = записанный.doc.path("response").asText(""),
                model = записанный.doc.path("model").asText(""),
                tokensIn = записанный.doc.path("tokens_in").takeIf { it.isNumber }?.asInt(),
                tokensOut = записанный.doc.path("tokens_out").takeIf { it.isNumber }?.asInt(),
                cached = true,
            )
        }
        val ответ = transport.ask(prompt, model)
        val документ = mapper.createObjectNode()
        документ.put("kind", kind)
        документ.put("model", ответ.model)
        документ.put("fingerprint", отпечаток)
        документ.put("prompt_chars", prompt.length)
        документ.put("response", ответ.text)
        ответ.tokensIn?.let { документ.put("tokens_in", it) }
        ответ.tokensOut?.let { документ.put("tokens_out", it) }
        документ.put("at", OffsetDateTime.now().toString())
        val занято = store.list(Area.Project(project), "ai_call").size
        store.create(
            "AI-%04d".format(занято + 1), "ai_call", Area.Project(project), null,
            документ, Provenance(Channel.SERVICE, "служба", fingerprint = отпечаток),
        )
        return ответ
    }

    override fun journal(project: String): List<CallRecord> =
        store.list(Area.Project(project), "ai_call").map {
            CallRecord(
                kind = it.doc.path("kind").asText(""),
                model = it.doc.path("model").asText(""),
                fingerprint = it.doc.path("fingerprint").asText(""),
                tokensIn = it.doc.path("tokens_in").takeIf { т -> т.isNumber }?.asInt(),
                tokensOut = it.doc.path("tokens_out").takeIf { т -> т.isNumber }?.asInt(),
                at = it.doc.path("at").asText(""),
                cached = false,
            )
        }

    private fun отпечатокПромпта(prompt: String): String =
        MessageDigest.getInstance("SHA-256").digest(prompt.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
}
