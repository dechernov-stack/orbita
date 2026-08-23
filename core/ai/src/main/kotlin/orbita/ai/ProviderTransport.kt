// Прямой транспорт к провайдеру модели (П5: прямой API — основной транспорт).
//
// Транспорт живёт на границе ядра: адрес, ключ и модель приходят окружением,
// формат запроса — Messages API. Не настроенный транспорт не притворяется
// работающим: он честно сообщает, что прямой канал недоступен, и служба
// переходит в режим закрытого контура (пакет отдаётся владельцу, ответ
// возвращается файлом) — тем же форматом, той же схемой ответа.
package orbita.ai

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Ответ провайдера: текст и учёт — журналу нужно «сколько и почём». */
data class ProviderAnswer(
    val text: String,
    val model: String,
    val tokensIn: Int?,
    val tokensOut: Int?,
)

class ProviderUnavailableException(reason: String) : RuntimeException(reason)

/** Вызов модели. Реализация подменяется в тестах: сеть в тесте не нужна. */
fun interface ProviderTransport {
    fun ask(prompt: String, modelHint: String?): ProviderAnswer
}

/**
 * Messages API провайдера. Настраивается окружением:
 * ORBITA_AI_URL (по умолчанию api.anthropic.com), ORBITA_AI_KEY, ORBITA_AI_MODEL.
 * Без ключа канал недоступен — и это состояние, а не ошибка выполнения.
 */
class HttpProviderTransport(
    private val url: String? = System.getenv("ORBITA_AI_URL"),
    private val key: String? = System.getenv("ORBITA_AI_KEY"),
    private val defaultModel: String? = System.getenv("ORBITA_AI_MODEL"),
    private val mapper: ObjectMapper = ObjectMapper(),
) : ProviderTransport {

    val configured: Boolean get() = !key.isNullOrBlank()

    override fun ask(prompt: String, modelHint: String?): ProviderAnswer {
        val apiKey = key?.takeIf { it.isNotBlank() }
            ?: throw ProviderUnavailableException(
                "прямой канал не настроен: задайте ORBITA_AI_KEY (и при необходимости " +
                    "ORBITA_AI_URL, ORBITA_AI_MODEL) либо работайте режимом закрытого контура",
            )
        val model = modelHint ?: defaultModel ?: DEFAULT_MODEL
        val body = mapper.createObjectNode()
        body.put("model", model)
        body.put("max_tokens", MAX_TOKENS)
        val messages = body.putArray("messages")
        messages.addObject().put("role", "user").put("content", prompt)

        val endpoint = (url?.takeIf { it.isNotBlank() } ?: DEFAULT_URL).trimEnd('/') + "/v1/messages"
        val request = HttpRequest.newBuilder(URI.create(endpoint))
            .header("content-type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", API_VERSION)
            .timeout(Duration.ofMinutes(10))
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), Charsets.UTF_8))
            .build()

        val response = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build()
            .send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        if (response.statusCode() !in 200..299) {
            throw ProviderUnavailableException(
                "провайдер ответил ${response.statusCode()}: ${response.body().take(300)}",
            )
        }
        val n = mapper.readTree(response.body())
        val text = n.path("content").joinToString("") { it.path("text").asText("") }
        return ProviderAnswer(
            text = text,
            model = n.path("model").asText(model),
            tokensIn = n.path("usage").path("input_tokens").takeIf { it.isInt }?.asInt(),
            tokensOut = n.path("usage").path("output_tokens").takeIf { it.isInt }?.asInt(),
        )
    }

    private companion object {
        const val DEFAULT_URL = "https://api.anthropic.com"
        const val DEFAULT_MODEL = "claude-sonnet-4-5"
        const val API_VERSION = "2023-06-01"
        const val MAX_TOKENS = 16000
    }
}
