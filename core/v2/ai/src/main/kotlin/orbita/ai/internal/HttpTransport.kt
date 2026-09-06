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

    override fun ask(prompt: String, model: String?, maxTokens: Int?): Answer {
        val ключ = key?.takeIf { it.isNotBlank() }
            ?: throw ProviderUnavailable(
                "прямой канал не настроен: нет ORBITA_AI_KEY. Живой разбор недоступен — " +
                    "работайте пакетом либо задайте ключ",
            )
        val модель = model ?: defaultModel
        val тело = mapper.createObjectNode()
        тело.put("model", модель)
        тело.put("max_tokens", maxTokens ?: this.maxTokens)
        // Разбор большого документа считается минутами, а тихое соединение
        // рвётся на шестидесятой секунде: ответ читается ПОТОКОМ — куски
        // идут непрерывно, и рвать нечего (поймано на записке в 36 тыс.
        // знаков: три попытки по 62 с, «EOF reached while reading»).
        тело.put("stream", true)
        тело.putArray("messages").addObject()
            .put("role", "user").put("content", prompt)

        val запрос = HttpRequest.newBuilder(URI.create(url))
            .header("content-type", "application/json")
            .header("x-api-key", ключ)
            .header("anthropic-version", "2023-06-01")
            .timeout(Duration.ofMinutes(15))
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
        return собрать(ответ.body(), модель)
    }

    /**
     * Сборка потокового ответа: события SSE идут строками `data: {…}`.
     * Берём приращения текста и учёт токенов; событие `error` — отказ
     * провайдера, а не пустой ответ.
     */
    internal fun собрать(поток: String, модель: String): Answer {
        val текст = StringBuilder()
        var вход: Int? = null
        var выход: Int? = null
        var имяМодели = модель
        var причинаОстановки = ""
        поток.lineSequence().forEach { строка ->
            val данные = строка.removePrefix("data:").trim()
            if (!строка.startsWith("data:") || данные.isEmpty() || данные == "[DONE]") return@forEach
            val узел = runCatching { mapper.readTree(данные) }.getOrNull() ?: return@forEach
            when (узел.path("type").asText()) {
                "message_start" -> {
                    имяМодели = узел.path("message").path("model").asText(модель)
                    вход = узел.path("message").path("usage").path("input_tokens")
                        .takeIf { it.isNumber }?.asInt()
                }
                "content_block_delta" -> текст.append(узел.path("delta").path("text").asText(""))
                "message_delta" -> {
                    выход = узел.path("usage").path("output_tokens")
                        .takeIf { it.isNumber }?.asInt() ?: выход
                    причинаОстановки = узел.path("delta").path("stop_reason").asText(причинаОстановки)
                }
                "error" -> throw ProviderUnavailable(
                    "провайдер прервал поток: " + узел.path("error").path("message").asText(""),
                )
            }
        }
        if (текст.isBlank()) throw ProviderUnavailable("провайдер вернул пустой ответ")
        // Обрыв по бюджету — НЕ ответ: половина JSON выглядит как поломка
        // разбора, а причина другая. Повторять бессмысленно, поэтому это
        // не «канал недоступен», а названная ошибка настройки.
        if (причинаОстановки == "max_tokens") {
            throw IllegalStateException(
                "ответ оборван бюджетом: модель дошла до потолка в ${выход ?: "?"} токенов. " +
                    "Поднимите ORBITA_AI_MAX_TOKENS либо разбирайте документ по частям",
            )
        }
        return Answer(текст.toString(), имяМодели, вход, выход)
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

    override fun ask(prompt: String, model: String?, maxTokens: Int?): Answer {
        var последняя: ProviderUnavailable? = null
        for (попытка in паузыМс.indices) {
            try {
                return inner.ask(prompt, model, maxTokens)
            } catch (e: ProviderUnavailable) {
                последняя = e
                if (попытка < паузыМс.size - 1) спать(паузыМс[попытка])
            }
        }
        throw ProviderUnavailable("${последняя?.message} (попыток: ${паузыМс.size})")
    }
}
