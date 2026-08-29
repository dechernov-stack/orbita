// Замечание владельца к пачке-2: расшифровка меток источников была
// ПЕРЕВЁРНУТА — «[В] — вывод автора» вместо «внешний источник, проверенный
// на указанную дату». При живом разборе это исказило бы достоверность всей
// нормативной базы: проверенный факт стал бы мнением.
//
// Тест держит семантику дословно по записке владельца во всех местах, где
// она живёт: правило промпта, схема урожая, шаблон записки на полке.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class SourceMarkSemanticsTest {

    private val mapper = ObjectMapper()

    private val internal = "внутренний документ"
    private val external = "внешний источник, проверенный на указанную дату"
    private val assumption = "предлагаемая цель или инженерно-финансовое допущение"

    @Test
    fun `правило промпта несёт авторскую семантику меток`() {
        val kind = orbita.ai.PackageKinds.default().of(DocumentHarvest.KIND)
        val rule = kind.rules.first { "source_mark" in it }
        assertTrue(internal in rule) { rule }
        assertTrue(external in rule) { rule }
        assertTrue(assumption in rule) { rule }
        assertTrue("вывод автора" !in rule) { "перевёрнутая расшифровка вернулась: $rule" }
        assertTrue("не выдавай за факт" in rule) { "запрет [П]-допущения обязан остаться: $rule" }
    }

    @Test
    fun `редакция правил различает своды`() {
        val kind = orbita.ai.PackageKinds.default().of(DocumentHarvest.KIND)
        assertTrue(kind.rulesVersion >= 2) {
            "после правки семантики редакция обязана отличаться от первой: ${kind.rulesVersion}"
        }
    }

    @Test
    fun `схема урожая описывает метки так же`() {
        val schema = mapper.readTree(
            Files.readString(RepoPaths.schemasDir().resolve("core/document-harvest.schema.json")),
        )
        val text = schema.at("/properties/items/items/properties/source_mark/description").asText()
        assertTrue(internal in text && external in text && assumption in text) { text }
        assertTrue("вывод автора" !in text) { text }
        assertEquals(
            listOf("И", "В", "П"),
            schema.at("/properties/items/items/properties/source_mark/enum").map { it.asText() },
        )
    }

    @Test
    fun `шаблон записки на полке объясняет метки дословно`() {
        val seed = mapper.readTree(
            Files.readString(
                RepoPaths.repoRoot().resolve("docs/tz/manual-run/packets/10-шаблон-записки.json"),
            ),
        )
        val section = seed.path("objects")[0].path("sections")
            .first { it.path("title").asText().contains("Обозначения источников") }
        val expects = section.path("expects").asText()
        assertTrue(internal in expects) { expects }
        assertTrue(external in expects) { expects }
        assertTrue("вывод автора" !in expects) { expects }
    }
}
