// Прямой транспорт к провайдеру: Messages API.
//
// Ключ и адрес приходят окружением. Не настроенный транспорт не
// притворяется работающим: он говорит, чего не хватает, и служба честно
// сообщает, что живой разбор недоступен, — вместо пустого урожая,
// который выглядит как «модель ничего не нашла».
package orbita.ai.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.ai.api.Answer
import orbita.ai.api.ProviderUnavailable
import orbita.ai.api.Transport
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Переменная окружения, заданная ПУСТОЙ, — то же, что не заданная: compose
 * стенда передаёт `${ORBITA_AI_MAX_TOKENS:-}`, и на сервере без ключа модели
 * пустая строка роняла весь v2 на первом же запросе («For input string: ""»,
 * выкат 216 09.09).
 */
private fun env(имя: String): String? = System.getenv(имя)?.takeIf { it.isNotBlank() }

class HttpTransport(
    private val mapper: ObjectMapper = ObjectMapper(),
    private val key: String? = env("ORBITA_AI_KEY"),
    // В окружении стенда задан БАЗОВЫЙ адрес провайдера; путь дописывается
    // здесь — иначе запрос уходит в корень и возвращает 404 (поймано живым
    // прогоном).
    private val url: String = (env("ORBITA_AI_URL") ?: "https://api.anthropic.com")
        .trimEnd('/')
        .let { if (it.contains("/v1/")) it else "$it/v1/messages" },
    private val defaultModel: String = env("ORBITA_AI_MODEL") ?: "claude-sonnet-5",
    private val maxTokens: Int = env("ORBITA_AI_MAX_TOKENS")?.toIntOrNull() ?: 16000,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20)).build(),
    /** Отправка тела и получение потока SSE — подменяется в тестах цикла инструментов. */
    private val отправка: ((String) -> String)? = null,
) : orbita.ai.api.ToolTransport {

    private companion object {
        /** Имя инструмента ответа: одно на все контуры, им же держится формат. */
        const val ИНСТРУМЕНТ = "orbita_result"

        /** Потолок ходов с инструментами: разбор документа укладывается в десяток запросов знания. */
        const val ПОТОЛОК_ХОДОВ = 16
    }

    override fun ask(prompt: String, model: String?, maxTokens: Int?, schema: JsonNode?): Answer {
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
        // Формат ответа держит ПРОВАЙДЕР, а не уговор в тексте промпта:
        // объявляем инструмент со схемой и обязываем им воспользоваться.
        // Пока формат держался словами, любая правка инструкций уводила
        // модель с него, и разбор возвращал пустой список фактов (15.09).
        if (schema != null) {
            val инструмент = тело.putArray("tools").addObject()
            инструмент.put("name", ИНСТРУМЕНТ)
            инструмент.put("description", "Вернуть результат строго по схеме")
            инструмент.set<JsonNode>("input_schema", schema)
            тело.putObject("tool_choice").put("type", "tool").put("name", ИНСТРУМЕНТ)
        }

        return собрать(отправить(ключ, тело), модель)
    }

    /** Отправка тела провайдеру: поток SSE строкой; перегрузка и отказ — названы. */
    private fun отправить(ключ: String, тело: JsonNode): String {
        отправка?.let { return it(mapper.writeValueAsString(тело)) }
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
        return ответ.body()
    }

    /**
     * Цикл с инструментами (шип 4 §2): ответ по схеме — тем же инструментом
     * `orbita_result`; знание модель берёт инструментами, каждый вызов
     * исполняется здесь и возвращается ей результатом. Ходов не больше
     * ПОТОЛОК_ХОДОВ: модель, не отдавшая ответ, — отказ, а не вечный разговор.
     */
    override fun askWithTools(
        prompt: String,
        model: String?,
        maxTokens: Int?,
        schema: JsonNode?,
        tools: List<orbita.ai.api.Tool>,
        handler: orbita.ai.api.ToolHandler,
    ): Answer {
        val ключ = key?.takeIf { it.isNotBlank() }
            ?: throw ProviderUnavailable("прямой канал не настроен: нет ORBITA_AI_KEY")
        val модель = model ?: defaultModel
        val сообщения = mapper.createArrayNode()
        сообщения.addObject().put("role", "user").put("content", prompt)
        var входВсего = 0
        var выходВсего = 0
        var имяМодели = модель
        repeat(ПОТОЛОК_ХОДОВ) {
            val тело = mapper.createObjectNode()
            тело.put("model", модель).put("max_tokens", maxTokens ?: this.maxTokens).put("stream", true)
            тело.set<JsonNode>("messages", сообщения.deepCopy())
            val набор = тело.putArray("tools")
            if (schema != null) {
                набор.addObject().put("name", ИНСТРУМЕНТ).put("description", "Вернуть ИТОГОВЫЙ результат строго по схеме — один раз, когда всё собрано")
                    .set<JsonNode>("input_schema", schema)
            }
            tools.forEach { и -> набор.addObject().put("name", и.name).put("description", и.description).set<JsonNode>("input_schema", и.inputSchema) }
            // Каждый ход модель обязана позвать инструмент: знание либо итог.
            тело.putObject("tool_choice").put("type", "any")
            val сообщение = собратьСообщение(отправить(ключ, тело), модель)
            имяМодели = сообщение.model
            входВсего += сообщение.tokensIn ?: 0
            выходВсего += сообщение.tokensOut ?: 0
            if (сообщение.stopReason == "max_tokens") {
                throw IllegalStateException("ответ оборван бюджетом на ходу с инструментами (${выходВсего} токенов): поднимите ORBITA_AI_MAX_TOKENS")
            }
            сообщение.blocks.firstOrNull { it.type == "tool_use" && it.name == ИНСТРУМЕНТ }?.let { итог ->
                return Answer(итог.json, имяМодели, входВсего, выходВсего)
            }
            val вызовы = сообщение.blocks.filter { it.type == "tool_use" }
            if (вызовы.isEmpty()) {
                val текст = сообщение.blocks.filter { it.type == "text" }.joinToString("") { it.json }
                if (текст.isBlank()) throw ProviderUnavailable("провайдер вернул пустой ответ")
                return Answer(текст, имяМодели, входВсего, выходВсего)
            }
            // Ход модели — в историю как есть; результаты — следующим ходом пользователя.
            val ассистент = сообщения.addObject().put("role", "assistant")
            val содержимое = ассистент.putArray("content")
            сообщение.blocks.forEach { б ->
                when (б.type) {
                    "text" -> if (б.json.isNotBlank()) содержимое.addObject().put("type", "text").put("text", б.json)
                    "tool_use" -> содержимое.addObject().put("type", "tool_use").put("id", б.id).put("name", б.name)
                        .set<JsonNode>("input", runCatching { mapper.readTree(б.json.ifBlank { "{}" }) }.getOrDefault(mapper.createObjectNode()))
                }
            }
            val пользователь = сообщения.addObject().put("role", "user")
            val результаты = пользователь.putArray("content")
            вызовы.forEach { в ->
                val вход = runCatching { mapper.readTree(в.json.ifBlank { "{}" }) }.getOrDefault(mapper.createObjectNode())
                val результат = runCatching { handler.call(в.name, вход) }
                    .getOrElse { mapper.createObjectNode().put("error", "инструмент «${в.name}» отказал: ${it.message}") }
                результаты.addObject().put("type", "tool_result").put("tool_use_id", в.id)
                    .put("content", mapper.writeValueAsString(результат))
            }
        }
        throw IllegalStateException("модель не отдала ответ за $ПОТОЛОК_ХОДОВ ходов с инструментами")
    }

    /**
     * Сборка потокового ответа: события SSE идут строками `data: {…}`.
     * Берём приращения текста и учёт токенов; событие `error` — отказ
     * провайдера, а не пустой ответ.
     */
    /** Чем блок содержательнее: длина без пробелов — пустой `{}` весит два знака. */
    private fun содержательность(блок: StringBuilder): Int = блок.count { !it.isWhitespace() }

    /** Блок содержимого ответа: текст либо вызов инструмента (json — вход инструмента). */
    internal data class Блок(val type: String, val id: String, val name: String, val json: String)

    internal data class Сообщение(
        val blocks: List<Блок>,
        val model: String,
        val tokensIn: Int?,
        val tokensOut: Int?,
        val stopReason: String,
    )

    /** Поток SSE → сообщение блоками: текст и вызовы инструментов, учёт токенов, причина остановки. */
    internal fun собратьСообщение(поток: String, модель: String): Сообщение {
        val блоки = linkedMapOf<Int, StringBuilder>()
        val виды = linkedMapOf<Int, Triple<String, String, String>>()
        var текущий = 0
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
                "content_block_start" -> {
                    текущий = узел.path("index").asInt(текущий)
                    val блок = узел.path("content_block")
                    виды[текущий] = Triple(блок.path("type").asText("text"), блок.path("id").asText(""), блок.path("name").asText(""))
                    блоки.getOrPut(текущий) { StringBuilder() }
                }
                // Ответ по схеме приходит не текстом, а входом инструмента:
                // куски идут отдельным видом дельты. Сборщик один на оба вида.
                "content_block_delta" -> {
                    val номер = узел.path("index").asInt(текущий)
                    блоки.getOrPut(номер) { StringBuilder() }.append(
                        when (узел.path("delta").path("type").asText()) {
                            "input_json_delta" -> узел.path("delta").path("partial_json").asText("")
                            else -> узел.path("delta").path("text").asText("")
                        },
                    )
                }
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
        return Сообщение(
            блоки.map { (номер, текст) ->
                val (тип, id, имя) = виды[номер] ?: Triple("text", "", "")
                Блок(тип, id, имя, текст.toString())
            },
            имяМодели, вход, выход, причинаОстановки,
        )
    }

    internal fun собрать(поток: String, модель: String): Answer {
        // Блоки содержимого собираются ПООТДЕЛЬНОСТИ. Провайдер вправе прислать
        // их несколько — и присылает: живое чтение записки 15.09 вернуло два
        // блока-инструмента подряд, полный и пустой. Склеенные в одну строку,
        // они дают «Extra data» при разборе, а с ним — пустой ответ на исправном
        // вызове: худший вид отказа, тихий.
        val сообщение = собратьСообщение(поток, модель)
        val текст = StringBuilder()
        val вход = сообщение.tokensIn
        val выход = сообщение.tokensOut
        val имяМодели = сообщение.model
        val причинаОстановки = сообщение.stopReason
        // Из нескольких блоков берётся САМЫЙ БОЛЬШОЙ: пустой хвостовой блок
        // (второй вызов инструмента без содержимого) ответа не отменяет.
        сообщение.blocks.map { StringBuilder(it.json) }.maxByOrNull { содержательность(it) }?.let { текст.append(it) }
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
) : orbita.ai.api.ToolTransport {

    override fun askWithTools(
        prompt: String, model: String?, maxTokens: Int?, schema: JsonNode?,
        tools: List<orbita.ai.api.Tool>, handler: orbita.ai.api.ToolHandler,
    ): Answer {
        val инструментальный = inner as? orbita.ai.api.ToolTransport ?: return ask(prompt, model, maxTokens, schema)
        var последняя: ProviderUnavailable? = null
        for (попытка in паузыМс.indices) {
            try {
                return инструментальный.askWithTools(prompt, model, maxTokens, schema, tools, handler)
            } catch (e: ProviderUnavailable) {
                последняя = e
                if (попытка < паузыМс.size - 1) спать(паузыМс[попытка])
            }
        }
        throw ProviderUnavailable("${последняя?.message} (попыток: ${паузыМс.size})")
    }

    override fun ask(prompt: String, model: String?, maxTokens: Int?, schema: JsonNode?): Answer {
        var последняя: ProviderUnavailable? = null
        for (попытка in паузыМс.indices) {
            try {
                return inner.ask(prompt, model, maxTokens, schema)
            } catch (e: ProviderUnavailable) {
                последняя = e
                if (попытка < паузыМс.size - 1) спать(паузыМс[попытка])
            }
        }
        throw ProviderUnavailable("${последняя?.message} (попыток: ${паузыМс.size})")
    }
}
