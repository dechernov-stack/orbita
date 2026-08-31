// Сид правил требований (NASA SEH App. C, поставка владельца): линт L-C1…L-C6.
// Пометы МЯГКИЕ: они советуют, а не запрещают, и базирование ими не держится —
// его держит check. Словарь слов — данные: правится без пересборки ядра.
package orbita.req

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RequirementLintTest {

    private val mapper = ObjectMapper()
    private val quality = QualityControl()

    private fun требование(текст: String, mop: String = "") = mapper.readTree(
        """{"id":"RQ-9001","level":"system","category":"functional","owner":"инженер",
            "statement":"$текст","verification_events":[],"traces_up":[{"ref":"ND-0001"}]
            ${if (mop.isBlank()) "" else ", \"mop\": $mop"},
            "lifecycle":{"status":"Draft","version":"1"}}""",
    )

    private fun коды(текст: String, mop: String = "") =
        quality.lint(требование(текст, mop)).map { it.id }

    @Test
    fun `L-C2 — неопределённое слово названо по имени`() {
        val ноты = quality.lint(требование("Система должна при необходимости передавать телеметрию."))
        val нота = ноты.single { it.id == "L-C2" }
        assertTrue("при необходимости" in нота.text) {
            "помета обязана назвать само слово, а не отослать к правилу: ${нота.text}"
        }
    }

    @Test
    fun `L-C1 — два глагола под одним «должен»`() {
        assertTrue("L-C1" in коды("Система должна принимать сообщения и должна их подтверждать."))
    }

    @Test
    fun `L-C3 — пассив и безличность`() {
        assertTrue("L-C3" in коды("Должно быть обеспечено резервирование канала."))
        assertTrue("L-C3" !in коды("Аппарат должен резервировать канал управления."))
    }

    @Test
    fun `L-C4 — негативная форма`() {
        assertTrue("L-C4" in коды("Терминал не должен превышать мощность 2 Вт."))
    }

    @Test
    fun `L-C5 — TBD без владельца и срока`() {
        val открытый = """{"name":"задержка","operator":"le","tbd":true,
            "value":{"value":30,"unit":"s","provenance":{"source":"manual"}}}"""
        val нота = quality.lint(требование("Система должна доставлять сообщение.", открытый))
            .single { it.id == "L-C5" }
        assertTrue("срок" in нота.text) { "канон TBR требует срок: ${нота.text}" }

        val закрытый = """{"name":"задержка","operator":"le","tbd":true,
            "tbd_owner":"вед. СИ","tbd_due":"к SRR","tbd_action":"замер на демонстраторе",
            "value":{"value":30,"unit":"s","provenance":{"source":"manual"}}}"""
        assertTrue("L-C5" !in коды("Система должна доставлять сообщение.", закрытый)) {
            "TBD с владельцем, сроком и действием — это план, а не помета"
        }
    }

    @Test
    fun `L-C6 — цель среди требований`() {
        assertTrue("L-C6" in коды("Системе желательно поддерживать роуминг."))
    }

    @Test
    fun `чистая формулировка помет не собирает`() {
        assertEquals(emptyList<String>(), коды("Аппарат должен передавать телеметрию каждые 30 секунд."))
    }

    @Test
    fun `линт не держит базирование — это разные своды`() {
        val req = требование("Система должна при необходимости передавать телеметрию.")
        assertTrue(quality.lint(req).isNotEmpty()) { "помета есть" }
        // check — свой свод: он и решает вопрос пригодности к базированию
        assertTrue(quality.check(req).isNotEmpty()) { "неизмеримое слово ловится и жёстким сводом" }
    }
}
