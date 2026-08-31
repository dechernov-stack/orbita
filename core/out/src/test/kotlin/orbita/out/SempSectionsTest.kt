// Мини-задача владельца (ШАБЛОН-SEMP): SEMP наполовину — самоописание
// конфигурации проекта. Разделы 3–7 обязаны приходить вставками, а не
// оставаться пустыми: организация — стейкхолдерами с поставками, процессы —
// таблицей соответствия, обзоры — вехами, отклонения — записями tailoring
// с обоснованием, среда — перечнем инструментов.
package orbita.out

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SempSectionsTest {

    private val mapper = ObjectMapper()
    private val generator = DocumentGenerator(mapper)

    private val модель = mapper.readTree(
        """{"requirements":[],"needs":[],"components":[],
            "project":{"id":"PJ-1907","name":"Морская логистика","phase":"phase_a",
              "mission_intent":{"for_whom":"перевозчики","what":"передача телеметрии",
                                "where":"СМП","horizon":"до 2033 года"},
              "milestones":[{"gate":"SRR","planned":"2026-09-02"}],
              "gate_tailoring":[{"gate":"SRR","check":"geo_masks",
                                 "rationale":"морской сегмент вне зоны первой очереди",
                                 "author":"Ведущий СИ","at":"2026-08-31"}]},
            "stakeholders":[{"id":"SK-0001","name":"Минтранс России","role":"customer",
                             "interest":"единый транспорт данных мониторинга"},
                            {"id":"SK-0002","name":"Поставщик платформы","role":"supplier",
                             "supplies":["CM-0001"]}]}""",
    )

    private fun раздел(n: Int) = generator.render(модель, SeedTemplates.of("semp"))
        .body.path("sections").first { it.path("number").asInt() == n }

    @Test
    fun `организация приходит стейкхолдерами, поставщик — вместе с узлом`() {
        val items = раздел(3).path("items")
        assertEquals(2, items.size()) { "оба стейкхолдера обязаны дойти: $items" }
        val поставщик = items.first { it.path("role").asText() == "supplier" }
        assertTrue("CM-0001" in поставщик.path("supplies").asText()) {
            "ответственность без адреса пуста: поставщик показывается с узлом"
        }
    }

    @Test
    fun `процессы регламента приходят таблицей соответствия из конфигурации`() {
        val items = раздел(4).path("items")
        assertTrue(items.size() >= 8) { "таблица процессов обязана быть полной: ${items.size()}" }
        val требования = items.first { "требовани" in it.path("process").asText().lowercase() }
        assertTrue(требования.path("mechanism").asText().isNotBlank()) { "чем сделан процесс" }
        assertTrue(требования.path("place").asText().isNotBlank()) { "и где он живёт" }
        // конфигурация — данные, не сочинение документа
        assertTrue(SempConfiguration.processes().size == items.size())
    }

    @Test
    fun `отклонения приходят записями tailoring с обоснованием и автором`() {
        val items = раздел(6).path("items")
        assertEquals(1, items.size())
        val w = items[0]
        assertEquals("SRR", w.path("gate").asText())
        assertTrue("морской сегмент" in w.path("rationale").asText()) { "обоснование дословно" }
        assertEquals("Ведущий СИ", w.path("author").asText()) { "у отклонения есть автор" }
    }

    @Test
    fun `среда работ приходит перечнем инструментов`() {
        val items = раздел(7).path("items")
        assertTrue(items.size() >= 6) { "перечень среды: ${items.size()}" }
        val mbse = items.first { "MBSE" in it.path("area").asText() }
        assertTrue("сама система" in mbse.path("tool").asText()) {
            "MBSE-модель — сама система, а не отдельный файл: это обязано быть сказано"
        }
    }

    @Test
    fun `без отклонений раздел пуст, а не выдуман`() {
        val чистая = mapper.readTree(
            """{"requirements":[],"needs":[],"components":[],
                "project":{"id":"PJ-1908","name":"Без отклонений","phase":"phase_a"}}""",
        )
        val items = generator.render(чистая, SeedTemplates.of("semp"))
            .body.path("sections").first { it.path("number").asInt() == 6 }.path("items")
        assertEquals(0, items.size()) { "tailoring не выдумывается: его записи — решения инженера" }
    }
}
