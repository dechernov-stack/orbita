// Прямой транспорт к провайдеру: Messages API.
//
// Ключ и адрес приходят окружением. Не настроенный транспорт не
// притворяется работающим: он говорит, чего не хватает, и служба честно
// сообщает, что живой разбор недоступен, — вместо пустого урожая,
// который выглядит как «модель ничего не нашла».
package orbita.ai.internal

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.ai.api.Answer
import orbita.ai.api.ProviderUnavailable
import orbita.ai.api.Transport
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class HttpTransport(
    private val mapper: ObjectMapper = ObjectMapper(),
    private val key: String? = System.getenv("ORBITA_AI_KEY"),
    // В окружении стенда задан БАЗОВЫЙ адрес провайдера; путь дописывается
    // здесь — иначе запрос уходит в корень и возвращает 404 (поймано живым
    // прогоном).
    private val url: String = (System.getenv("ORBITA_AI_URL") ?: "https://api.anthropic.com")
        .trimEnd('/')
        .let { if (it.contains("/v1/")) it else "$it/v1/messages" },
    private val defaultModel: String = System.getenv("ORBITA_AI_MODEL") ?: "claude-sonnet-5",
    private val maxTokens: Int = (System.getenv("ORBITA_AI_MAX_TOKENS") ?: "16000").toInt(),
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20)).build(),
) : Transport {

    override fun ask(prompt: String, model: String?): Answer {
        val ключ = key?.takeIf { it.isNotBlank() }
            ?: throw ProviderUnavailable(
                "прямой канал не настроен: нет ORBITA_AI_KEY. Живой разбор недоступен — " +
                    "работайте пакетом либо задайте ключ",
            )
        val модель = model ?: defaultModel
        val тело = mapper.createObjectNode()
        тело.put("model", модель)
        тело.put("max_tokens", maxTokens)
        тело.putArray("messages").addObject()
            .put("role", "user").put("content", prompt)

        val запрос = HttpRequest.newBuilder(URI.create(url))
            .header("content-type", "application/json")
            .header("x-api-key", ключ)
            .header("anthropic-version", "2023-06-01")
            .timeout(Duration.ofMinutes(5))
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(тело)))
            .build()

        val ответ = try {
            client.send(запрос, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw ProviderUnavailable("провайдер прервал поток: ${e.message}")
        }
        if (ответ.statusCode() == 429 || ответ.statusCode() == 529 || ответ.statusCode() >= 500) {
            throw ProviderUnavailable("провайдер перегружен (${ответ.statusCode()})")
        }
        if (ответ.statusCode() != 200) {
            // Отказ по ключу или форме повторять нечего — он не «перегрузка»
            throw IllegalStateException(
                "провайдер отказал (${ответ.statusCode()}): " + ответ.body().take(400),
            )
        }
        val узел = mapper.readTree(ответ.body())
        val текст = узел.path("content").joinToString("") { it.path("text").asText("") }
        if (текст.isBlank()) throw ProviderUnavailable("провайдер вернул пустой ответ")
        return Answer(
            text = текст,
            model = узел.path("model").asText(модель),
            tokensIn = узел.path("usage").path("input_tokens").takeIf { it.isNumber }?.asInt(),
            tokensOut = узел.path("usage").path("output_tokens").takeIf { it.isNumber }?.asInt(),
        )
    }
}

/**
 * Перегрузка модели — состояние минуты, а не отказ работы: три попытки с
 * паузами 2 · 4 · 8 секунд. Вызов идемпотентен — в модель до акцепта
 * человеком ничего не пишется. Отказ по ключу или форме не повторяется.
 */
class RetryingTransport(
    private val inner: Transport,
    private val паузыМс: LongArray = longArrayOf(2000, 4000, 8000),
    private val спать: (Long) -> Unit = { Thread.sleep(it) },
) : Transport {

    override fun ask(prompt: String, model: String?): Answer {
        var последняя: ProviderUnavailable? = null
        for (попытка in паузыМс.indices) {
            try {
                return inner.ask(prompt, model)
            } catch (e: ProviderUnavailable) {
                последняя = e
                if (попытка < паузыМс.size - 1) спать(паузыМс[попытка])
            }
        }
        throw ProviderUnavailable("${последняя?.message} (попыток: ${паузыМс.size})")
    }
}
