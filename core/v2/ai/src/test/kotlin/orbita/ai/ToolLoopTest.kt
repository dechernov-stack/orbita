// Цикл с инструментами (шип 4 §2): модель зовёт инструмент — результат уходит
// ей следующим ходом, итог приходит инструментом ответа; токены суммируются
// по ходам; потолок ходов — отказ словами, а не вечный разговор.
package orbita.ai

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.ai.api.Tool
import orbita.ai.api.ToolHandler
import orbita.ai.internal.HttpTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToolLoopTest {
    private val mapper = ObjectMapper()
    private val схема = mapper.readTree("""{"type":"object","properties":{"facts":{"type":"array"}}}""")
    private val инструменты = listOf(Tool("term", "термин словаря", mapper.readTree("""{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""")))

    private fun поток(vararg блоки: Triple<String, String, String>, вход: Int = 10, выход: Int = 5, остановка: String = "tool_use"): String {
        val строки = mutableListOf("data: {\"type\":\"message_start\",\"message\":{\"model\":\"тест\",\"usage\":{\"input_tokens\":$вход}}}")
        блоки.forEachIndexed { i, (тип, имя, содержимое) ->
            val старт = if (тип == "tool_use") "{\"type\":\"tool_use\",\"id\":\"tu_$i\",\"name\":\"$имя\"}" else "{\"type\":\"text\"}"
            строки += "data: {\"type\":\"content_block_start\",\"index\":$i,\"content_block\":$старт}"
            val дельта = if (тип == "tool_use") "{\"type\":\"input_json_delta\",\"partial_json\":${mapper.writeValueAsString(содержимое)}}"
            else "{\"type\":\"text_delta\",\"text\":${mapper.writeValueAsString(содержимое)}}"
            строки += "data: {\"type\":\"content_block_delta\",\"index\":$i,\"delta\":$дельта}"
        }
        строки += "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"$остановка\"},\"usage\":{\"output_tokens\":$выход}}"
        return строки.joinToString("\n")
    }

    @Test
    fun `вызов инструмента исполняется, результат уходит следующим ходом, итог — инструментом ответа`() {
        val тела = mutableListOf<String>()
        val ответы = ArrayDeque(listOf(
            поток(Triple("text", "", "посмотрю термин"), Triple("tool_use", "term", """{"name":"Ространснадзор"}""")),
            поток(Triple("tool_use", "orbita_result", """{"facts":[{"subject":"Ространснадзор"}]}"""), вход = 20, выход = 7, остановка = "tool_use"),
        ))
        val транспорт = HttpTransport(mapper, key = "ключ", отправка = { тело -> тела += тело; ответы.removeFirst() })
        val вызовы = mutableListOf<String>()
        val исполнитель = ToolHandler { имя, вход ->
            вызовы += "$имя:${вход.path("name").asText()}"
            mapper.createObjectNode().put("code", "GT-ST-001").put("term", "Ространснадзор")
        }

        val ответ = транспорт.askWithTools("разбери", null, null, схема, инструменты, исполнитель)

        assertEquals(listOf("term:Ространснадзор"), вызовы)
        assertEquals("""{"facts":[{"subject":"Ространснадзор"}]}""", ответ.text)
        assertEquals(30, ответ.tokensIn); assertEquals(12, ответ.tokensOut)
        assertEquals(2, тела.size, "два хода: вызов инструмента и итог")
        val второе = mapper.readTree(тела[1])
        val сообщения = второе.path("messages")
        assertEquals(3, сообщения.size(), "промпт, ход модели, результаты инструментов")
        assertEquals("assistant", сообщения[1].path("role").asText())
        assertEquals("tool_use", сообщения[1].path("content")[1].path("type").asText())
        assertEquals("tool_result", сообщения[2].path("content")[0].path("type").asText())
        assertEquals("tu_1", сообщения[2].path("content")[0].path("tool_use_id").asText())
        assertTrue("GT-ST-001" in сообщения[2].path("content")[0].path("content").asText())
        assertEquals("any", второе.path("tool_choice").path("type").asText())
        assertEquals(listOf("orbita_result", "term"), второе.path("tools").map { it.path("name").asText() })
    }

    @Test
    fun `отказ инструмента уходит модели словами, а модель без итога упирается в потолок ходов`() {
        val бесконечный = HttpTransport(mapper, key = "ключ", отправка = { поток(Triple("tool_use", "term", """{"name":"x"}""")) })
        val исполнитель = ToolHandler { _, _ -> throw IllegalStateException("словарь недоступен") }
        val беда = assertFailsWith<IllegalStateException> {
            бесконечный.askWithTools("разбери", null, null, схема, инструменты, исполнитель)
        }
        assertTrue("ходов" in (беда.message ?: ""), беда.message)
    }
}
