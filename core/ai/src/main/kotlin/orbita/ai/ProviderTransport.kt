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
 * Ж-01 (прогон 04.09): перегрузка модели — состояние минуты, а не отказ
 * работы. Живой вызов упал с «провайдер прервал поток: Overloaded», и человек
 * остался ни с чем. Здесь запрос повторяется трижды с паузами 2 · 4 · 8
 * секунд: он идемпотентен — в модель ничего не пишется до акцепта человеком,
 * частичных записей нет по закону. Отказ по ключу, форме или запрету не
 * повторяется: ждать там нечего. Число попыток названо в причине, чтобы по
 * журналу было видно, что канал пробовали.
 */
class RetryingTransport(
    private val inner: ProviderTransport,
    private val паузыМс: LongArray = longArrayOf(2000, 4000, 8000),
    private val спать: (Long) -> Unit = { Thread.sleep(it) },
) : ProviderTransport {

    override fun ask(prompt: String, modelHint: String?): ProviderAnswer {
        val попыток = паузыМс.size
        var последняя: ProviderUnavailableException? = null
        for (попытка in 0 until попыток) {
            try {
                return inner.ask(prompt, modelHint)
            } catch (e: ProviderUnavailableException) {
                последняя = e
                if (!стоитПовторить(e)) throw e
                if (попытка < попыток - 1) спать(паузыМс[попытка])
            }
        }
        val e = последняя!!
        throw ProviderUnavailableException("${e.message} (попыток: $попыток)")
    }

    private fun стоитПовторить(e: ProviderUnavailableException): Boolean {
        val текст = (e.message ?: "").lowercase()
        return "overloaded" in текст || "529" in текст || "прервал поток" in текст ||
            "обрыв связи" in текст || "поток провайдера не разбирается" in текст ||
            Regex("ответил 5\\d\\d").containsMatchIn(текст)
    }
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
    /** Потолок ответа: длинные ответы рвутся на VPN, пачку дробит инженер. */
    private val maxTokens: Int = System.getenv("ORBITA_AI_MAX_TOKENS")?.toIntOrNull() ?: MAX_TOKENS,
    private val mapper: ObjectMapper = ObjectMapper(),
) : ProviderTransport {

    val configured: Boolean get() = !key.isNullOrBlank()

    override fun ask(prompt: String, modelHint: String?): ProviderAnswer = askOnce(prompt, modelHint)

    private fun askOnce(prompt: String, modelHint: String?): ProviderAnswer {
        val apiKey = key?.takeIf { it.isNotBlank() }
            ?: throw ProviderUnavailableException(
                "прямой канал не настроен: задайте ORBITA_AI_KEY (и при необходимости " +
                    "ORBITA_AI_URL, ORBITA_AI_MODEL) либо работайте режимом закрытого контура",
            )
        val model = modelHint ?: defaultModel ?: DEFAULT_MODEL
        val body = mapper.createObjectNode()
        body.put("model", model)
        body.put("max_tokens", maxTokens)
        // Потоковый режим намеренно: длинный ответ модель отдаёт десятками
        // секунд, и молчащее соединение рвётся посредником (на VPN — ровно
        // на минуте). При потоке байты идут непрерывно, обрыва по простою нет.
        body.put("stream", true)
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

        // Любой отказ транспорта — состояние канала, а не сбой службы: он
        // обязан дойти до журнала причиной, а не всплыть пятисоткой. Длинный
        // ответ через VPN рвётся посередине, и «EOF при чтении» — ровно такой
        // отказ: его видно в журнале, вызов повторяется меньшей пачкой.
        // HTTP/1.1 намеренно: по умолчанию клиент JDK идёт по HTTP/2, и на
        // длинном ответе через VPN поток обрывался ровно на минуте («EOF
        // reached while reading»), тогда как curl из того же контейнера тем же
        // ключом отвечал за 17 секунд. Разница — только версия протокола.
        val client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(30))
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw ProviderUnavailableException(
                "обрыв связи с провайдером: ${e.message ?: e::class.simpleName}",
            )
        }
        if (response.statusCode() !in 200..299) {
            throw ProviderUnavailableException(
                "провайдер ответил ${response.statusCode()}: ${response.body().take(300)}",
            )
        }
        return try {
            parseStream(response.body(), model)
        } catch (e: Exception) {
            throw ProviderUnavailableException(
                "поток провайдера не разбирается: ${e.message ?: e::class.simpleName}",
            )
        }
    }

    /**
     * Разбор потока событий (SSE). Берутся дельты ТЕКСТА: у моделей с
     * рассуждением поток несёт и блоки thinking — они к ответу не относятся
     * и в разбор не идут. Учёт токенов приходит событиями message_start
     * и message_delta.
     */
    private fun parseStream(raw: String, fallbackModel: String): ProviderAnswer {
        val text = StringBuilder()
        var model = fallbackModel
        var tokensIn: Int? = null
        var tokensOut: Int? = null
        var currentIsText = false

        raw.lineSequence().forEach { line ->
            if (!line.startsWith("data:")) return@forEach
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") return@forEach
            val n = mapper.readTree(payload)
            when (n.path("type").asText()) {
                "message_start" -> {
                    val m = n.path("message")
                    model = m.path("model").asText(model)
                    tokensIn = m.path("usage").path("input_tokens").takeIf { it.isInt }?.asInt()
                }
                "content_block_start" ->
                    currentIsText = n.path("content_block").path("type").asText() == "text"
                "content_block_delta" ->
                    if (currentIsText) text.append(n.path("delta").path("text").asText(""))
                "message_delta" ->
                    tokensOut = n.path("usage").path("output_tokens").takeIf { it.isInt }?.asInt()
                "error" -> throw ProviderUnavailableException(
                    "провайдер прервал поток: ${n.path("error").path("message").asText()}",
                )
            }
        }
        return ProviderAnswer(text.toString(), model, tokensIn, tokensOut)
    }

    private companion object {
        const val DEFAULT_URL = "https://api.anthropic.com"
        const val DEFAULT_MODEL = "claude-sonnet-4-5"
        const val API_VERSION = "2023-06-01"
        const val MAX_TOKENS = 8000
    }
}
