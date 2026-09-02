// Мини-задача владельца (ШАБЛОН-SEMP, ред. 2 по NASA SEH App. J): SEMP
// наполовину — самоописание конфигурации проекта. Разделы обязаны приходить
// вставками, а не оставаться пустыми: организация (§5) — стейкхолдерами с
// поставками и ролями проекта, процессы (§6) — 17 строк NPR 7123.1 с
// tailoring у реализации, обзоры (§7) — вехами, отклонения (§9) — записями
// tailoring с обоснованием, среда (§10) — перечнем инструментов, показатели
// (§8) — запасами из свёрток.
package orbita.out

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
        val items = раздел(5).path("items").filter { it.path("kind").asText() != "role" }
        assertEquals(2, items.size) { "оба стейкхолдера обязаны дойти: $items" }
        val поставщик = items.first { it.path("role").asText() == "supplier" }
        assertTrue("CM-0001" in поставщик.path("supplies").asText()) {
            "ответственность без адреса пуста: поставщик показывается с узлом"
        }
    }

    @Test
    fun `процессы регламента приходят таблицей соответствия из конфигурации`() {
        val items = раздел(6).path("items")
        assertEquals(17, items.size()) { "канон — 17 процессов NPR 7123.1: ${items.size()}" }
        val реализация = items.filter { it.has("tailoring") }
        assertEquals(3, реализация.size) { "изготовление, интеграция, переход — вне области, пометкой tailoring" }
        assertTrue(реализация.all { "Phase C/D" in it.path("tailoring").asText() })
        val требования = items.first { "требовани" in it.path("process").asText().lowercase() }
        assertTrue(требования.path("mechanism").asText().isNotBlank()) { "чем сделан процесс" }
        assertTrue(требования.path("place").asText().isNotBlank()) { "и где он живёт" }
        // конфигурация — данные, не сочинение документа
        assertTrue(SempConfiguration.processes().size == items.size())
    }

    @Test
    fun `отклонения приходят записями tailoring с обоснованием и автором`() {
        val items = раздел(9).path("items").filter { it.has("gate") }
        assertEquals(1, items.size) { "запись неприменимости точки — одна" }
        val w = items[0]
        assertEquals("SRR", w.path("gate").asText())
        assertTrue("морской сегмент" in w.path("rationale").asText()) { "обоснование дословно" }
        assertEquals("Ведущий СИ", w.path("author").asText()) { "у отклонения есть автор" }
    }

    @Test
    fun `среда работ приходит перечнем инструментов`() {
        val items = раздел(10).path("items")
        assertTrue(items.size() >= 6) { "перечень среды: ${items.size()}" }
        val расчёты = items.first { it.path("area").asText() == "Расчёты" }
        assertTrue("в резерве" in расчёты.path("tool").asText()) { "Orekit — в резерве, не факт стенда" }
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
            .body.path("sections").first { it.path("number").asInt() == 9 }.path("items")
        assertEquals(0, items.count { it.has("gate") }) { "tailoring точек не выдумывается: его записи — решения инженера" }
        assertEquals(3, items.count { it.has("process") }) { "а отклонения процессов реализации — конфигурация, они есть всегда" }
    }

    @Test
    fun `показатели приходят числами из свёрток бюджетов и трендами замечаний`() {
        val сБюджетом = mapper.readTree(
            """{"requirements":[],"needs":[],"components":[],
                "project":{"id":"PJ-1909","name":"С бюджетом","phase":"phase_a"},
                "budgets":[{"kind":"mass","unit":"kg","nominal":48.5,"system_margin_pct":20,
                            "dry":58.2,"wet":60.0,"reserve":9.7,"within_platform_range":true},
                           {"kind":"power","unit":"Wh","generated":120.0,"consumed":90.0,"reserve":30.0,"ok":true}],
                "review_items":[{"id":"RI-0001","review_gate":"SRR","status":"open"},
                                {"id":"RI-0002","review_gate":"SRR","status":"closed"}]}""",
        )
        val items = generator.render(сБюджетом, SeedTemplates.of("semp"))
            .body.path("sections").first { it.path("number").asInt() == 8 }.path("items")
        val масса = items.first { it.path("name").asText() == "запас массы" }
        assertEquals(9.7, масса.path("reserve").asDouble(), 1e-9) { "запас массы — число из свёртки" }
        val мощность = items.first { it.path("name").asText() == "запас мощности" }
        assertEquals(30.0, мощность.path("reserve").asDouble(), 1e-9)
        val тренд = items.first { it.path("kind").asText() == "review_trend" }
        assertEquals(1, тренд.path("open").asInt()); assertEquals(1, тренд.path("closed").asInt())
    }

    @Test
    fun `режим раздела приходит из шаблона и печать связного раздела с текстом идёт прозой`() {
        val doc = generator.render(модель, SeedTemplates.of("semp"),
            texts = mapOf(3 to SectionAuthorText("Проект создаёт резервный канал телеметрии.", "")))
        val s3 = doc.body.path("sections").first { it.path("number").asInt() == 3 }
        assertEquals("prose", s3.path("mode").asText())
        assertEquals("table", doc.body.path("sections").first { it.path("number").asInt() == 2 }.path("mode").asText())
        val строки = PrintRenderer().lines(doc.body)
        assertTrue("Проект создаёт резервный канал телеметрии." in строки)
        assertFalse(строки.any { "замысел миссии" in it }) { "данные связного раздела с принятым текстом в печать не идут" }
        // без текста те же данные печатаются — документ не остаётся с дырой
        val безТекста = PrintRenderer().lines(generator.render(модель, SeedTemplates.of("semp")).body)
        assertTrue(безТекста.any { "замысел миссии" in it })
    }
}
