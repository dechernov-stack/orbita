// Находка живого прохода ПМИ-3: инженер скопировал ответ службы и вставил в
// ближайшее поле — «либо одним связным абзацем». Форма послушно сохранила
// строку, и в паспорт лёг весь JSON пакета целиком: замысел формально
// «задан», но состоит из фигурных скобок, а SEMP печатает его как есть.
//
// Здесь: абзац замысла — текст, а не пакет; ворота паспорта это держат.
package orbita.com.api

import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.schema.SchemaRegistry
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IntentPacketGuardTest {

    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)

    private fun паспорт(intent: String) = com.fasterxml.jackson.databind.ObjectMapper().readTree(
        """{"id":"PJ-1909","name":"Проверка замысла","phase":"pre_phase_a",
            "mission_intent":$intent,
            "milestones":[{"gate":"MCR"}],"lifecycle":{"status":"Draft","version":"1"}}""",
    )

    @Test
    fun `пакет службы в поле абзаца не принимается`() {
        val пакет = """{"text":"{\"kind\": \"mission_intent_from_docs\", \"intent\": {}}"}"""
        val problems = boundary.schemaProblems("core/project", паспорт(пакет))
        assertTrue(problems.isNotEmpty()) {
            "строка, начинающаяся с фигурной скобки, — это пакет, а не замысел словами"
        }
    }

    @Test
    fun `связный абзац словами принимается`() {
        val абзац = """{"text":"Группировка передаёт телеметрию перевозчикам в Арктике; горизонт — 2033 год."}"""
        val problems = boundary.schemaProblems("core/project", паспорт(абзац))
        assertTrue(problems.isEmpty()) { "нормальный текст обязан проходить: $problems" }
    }

    @Test
    fun `четыре поля принимаются по-прежнему`() {
        val поля = """{"for_whom":"перевозчики","what":"телеметрия","where":"Арктика","horizon":"2033"}"""
        val problems = boundary.schemaProblems("core/project", паспорт(поля))
        assertTrue(problems.isEmpty()) { "путь четырьмя полями не тронут: $problems" }
    }
}
