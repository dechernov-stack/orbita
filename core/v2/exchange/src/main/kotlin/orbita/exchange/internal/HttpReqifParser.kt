// Служба обмена по HTTP (ops/exchange/reqif_service.py): POST /reqif/parse с
// файлом в теле → объекты с атрибутами по именам и стандартными полями.
package orbita.exchange.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.exchange.api.ExchangeUnavailable
import orbita.exchange.api.ReqifParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal class HttpReqifParser(
    private val url: String,
    private val mapper: ObjectMapper,
    private val client: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5)).build(),
) : ReqifParser {

    override fun parse(xml: String): JsonNode {
        val запрос = HttpRequest.newBuilder(URI.create(url.trimEnd('/') + "/reqif/parse"))
            .timeout(Duration.ofSeconds(120))
            .header("Content-Type", "application/xml; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(xml))
            .build()
        val ответ = try {
            client.send(запрос, HttpResponse.BodyHandlers.ofString())
        } catch (e: java.io.IOException) {
            throw ExchangeUnavailable("служба обмена ($url) не отвечает: ${e.message}")
        }
        if (ответ.statusCode() >= 400) {
            throw ExchangeUnavailable("служба обмена не разобрала файл (${ответ.statusCode()}): ${ответ.body().take(300)}")
        }
        return mapper.readTree(ответ.body())
    }
}
