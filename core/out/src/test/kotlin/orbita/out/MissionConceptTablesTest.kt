// Шип 5 (хвост моделирования): в отчёте-статусе позиция «вставка таблицы
// сравнения в раздел AoA» была помечена «под вопросом» — механизм в коде
// есть, но тестом не закрыт, а значит мог тихо отвалиться.
//
// Здесь мера закрывается: раздел AoA документа Mission Concept обязан нести
// живую таблицу сравнения построений со снимком времени расчёта, а без
// расчёта — не выдумывать её.
package orbita.out

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MissionConceptTablesTest {

    private val mapper = ObjectMapper()
    private val generator = DocumentGenerator(mapper)

    private fun модель(сравнение: String) = mapper.readTree(
        """{"requirements":[],"needs":[],"components":[],
            "alternatives":[{"id":"AL-0001","kind":"option","name":"Вариант A",
                             "summary":"12 КА, три плоскости","criteria":[]}]
            $сравнение}""",
    )

    private fun разделAoA(body: com.fasterxml.jackson.databind.JsonNode) =
        body.path("sections").first { it.path("number").asInt() == 2 }

    @Test
    fun `таблица сравнения построений попадает в раздел AoA со снимком расчёта`() {
        val doc = generator.render(
            модель(
                ""","constellation_compare":{"computed_at":"2026-08-31T10:00:00Z",
                    "scenario_ref":"SC-0001",
                    "variants":[{"id":"CN-0001","name":"12 КА","coverage_a_prime":0.91},
                                {"id":"CN-0002","name":"24 КА","coverage_a_prime":0.97}]}""",
            ),
            SeedTemplates.of("mission_concept"),
        )
        val раздел = разделAoA(doc.body)
        val таблица = раздел.path("items").firstOrNull {
            it.path("kind").asText() == "constellation_compare_table"
        }
        assertTrue(таблица != null) {
            "раздел AoA обязан нести таблицу сравнения: ${раздел.path("items")}"
        }
        assertEquals(2, таблица!!.path("variants").size()) { "оба варианта обязаны дойти" }
        assertEquals("2026-08-31T10:00:00Z", таблица.path("computed_at").asText()) {
            "снимок времени расчёта — часть таблицы: по нему видно, не устарела ли она"
        }
        assertEquals("SC-0001", таблица.path("scenario_ref").asText()) {
            "таблица привязана к сценарию: результат без сценария — не результат"
        }
    }

    @Test
    fun `без расчёта таблица не выдумывается`() {
        val doc = generator.render(модель(""), SeedTemplates.of("mission_concept"))
        val items = разделAoA(doc.body).path("items")
        assertFalse(items.any { it.path("kind").asText() == "constellation_compare_table" }) {
            "сравнения не считали — таблице взяться неоткуда"
        }
        // альтернативы при этом на месте: раздел не пуст по другой причине
        assertTrue(items.any { it.path("id").asText() == "AL-0001" })
    }
}
