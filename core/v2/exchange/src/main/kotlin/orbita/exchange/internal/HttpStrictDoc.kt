// Служба StrictDoc по HTTP (ops/strictdoc/strictdoc_service.py, ADR-049):
// POST /sdoc/build · /sdoc/export · /sdoc/import. Ответ не 2xx — отказ
// словами с началом текста службы: пустой файл выглядел бы рабочим экспортом.
package orbita.exchange.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.exchange.api.ExchangeUnavailable
import orbita.exchange.api.SdocBundle
import orbita.exchange.api.StrictDocService
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal class HttpStrictDoc(
    private val url: String,
    private val mapper: ObjectMapper,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) : StrictDocService {

    override fun build(payload: JsonNode): SdocBundle {
        val ответ = post("/sdoc/build", payload.toString())
        return SdocBundle(ответ.path("sgra").asText(""), ответ.path("sdoc").asText(""))
    }

    override fun reqif(payload: JsonNode): String {
        val запрос = mapper.createObjectNode()
        запрос.set<JsonNode>("payload", payload)
        запрос.putArray("formats").add("reqif-sdoc")
        val ответ = post("/sdoc/export", запрос.toString())
        val xml = ответ.path("files").properties().firstOrNull { it.key.endsWith(".reqif") }?.value?.asText("")
        if (!ответ.path("ok").asBoolean(false) || xml.isNullOrBlank()) {
            throw ExchangeUnavailable("StrictDoc не выдал ReqIF: " + ответ.path("stderr").asText("").take(400))
        }
        return xml
    }

    override fun parse(sdoc: String, sgra: String?): JsonNode {
        val запрос = mapper.createObjectNode().put("sdoc", sdoc)
        sgra?.let { запрос.put("sgra", it) }
        return post("/sdoc/import", запрос.toString())
    }

    private fun post(путь: String, тело: String): JsonNode {
        val запрос = HttpRequest.newBuilder(URI.create(url.trimEnd('/') + путь))
            .timeout(Duration.ofSeconds(120))
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(тело))
            .build()
        val ответ = try {
            client.send(запрос, HttpResponse.BodyHandlers.ofString())
        } catch (e: java.io.IOException) {
            throw ExchangeUnavailable("служба StrictDoc ($url) не отвечает: ${e.message}")
        }
        if (ответ.statusCode() >= 400) {
            throw ExchangeUnavailable("служба StrictDoc ответила ${ответ.statusCode()}: ${ответ.body().take(300)}")
        }
        return mapper.readTree(ответ.body())
    }
}
