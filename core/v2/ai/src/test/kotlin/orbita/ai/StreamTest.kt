// Сборка потокового ответа провайдера.
//
// Разбор большого документа считается минутами, и тихое соединение рвётся
// на шестидесятой секунде — ответ читается потоком. Здесь проверяется
// сборка: приращения текста склеиваются по порядку, учёт токенов берётся
// из событий, а `error` в потоке — отказ, а не пустой ответ.
package orbita.ai

import orbita.ai.api.ProviderUnavailable
import orbita.ai.internal.HttpTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamTest {

    private val транспорт = HttpTransport(key = "ключ-для-теста")

    @Test
    fun `второй блок-инструмент не склеивается с первым`() {
        // Живое чтение записки 15.09: провайдер прислал ДВА блока-инструмента
        // подряд — полный и пустой. Склеенные в одну строку, они дают «Extra
        // data» при разборе, и исправный ответ на 16 сторон читался как пустой.
        val поток = """
            event: message_start
            data: {"type":"message_start","message":{"model":"claude-sonnet-5","usage":{"input_tokens":10}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"stakeholders\":"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"[{\"name\":\"Минтранс\"}]}"}}

            event: content_block_start
            data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"stakeholders\": []}"}}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":42}}
        """.trimIndent()

        val ответ = транспорт.собрать(поток, "claude-sonnet-5")

        assertEquals(
            "{\"stakeholders\":[{\"name\":\"Минтранс\"}]}",
            ответ.text,
            "берётся содержательный блок, а не склейка двух",
        )
    }

    @Test
    fun `приращения склеиваются, токены считаны`() {
        val поток = """
            event: message_start
            data: {"type":"message_start","message":{"model":"claude-sonnet-5","usage":{"input_tokens":2251}}}

            event: content_block_delta
            data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"{\"facts\":"}}

            event: content_block_delta
            data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"[]}"}}

            event: message_delta
            data: {"type":"message_delta","usage":{"output_tokens":6512}}

            data: [DONE]
        """.trimIndent()
        val ответ = транспорт.собрать(поток, "модель-по-умолчанию")
        assertEquals("{\"facts\":[]}", ответ.text)
        assertEquals("claude-sonnet-5", ответ.model)
        assertEquals(2251, ответ.tokensIn)
        assertEquals(6512, ответ.tokensOut)
    }

    @Test
    fun `ошибка в потоке — отказ, а не пустой ответ`() {
        val поток = """
            data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}
        """.trimIndent()
        val беда = runCatching { транспорт.собрать(поток, "модель") }.exceptionOrNull()
        assertTrue(беда is ProviderUnavailable, "перегрузка обязана быть отказом канала: $беда")
        assertTrue("Overloaded" in (беда?.message ?: ""), беда?.message ?: "")
    }

    @Test
    fun `обрыв по бюджету называется прямо, а не половиной JSON`() {
        val поток = """
            data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"{\"facts\":[{\"kind\":\"quant"}}

            data: {"type":"message_delta","delta":{"stop_reason":"max_tokens"},"usage":{"output_tokens":32000}}
        """.trimIndent()
        val беда = runCatching { транспорт.собрать(поток, "модель") }.exceptionOrNull()
        assertTrue(
            беда?.message?.contains("оборван бюджетом") == true,
            "обрыв обязан быть назван причиной, а не сломанным разбором: ${беда?.message}",
        )
        assertTrue("32000" in (беда?.message ?: ""), "названо, на чём остановились: ${беда?.message}")
    }

    @Test
    fun `пустой поток не выдаётся за ответ`() {
        val беда = runCatching { транспорт.собрать("data: [DONE]", "модель") }.exceptionOrNull()
        assertTrue(беда is ProviderUnavailable, "пустой ответ — состояние канала, а не результат")
    }
}
