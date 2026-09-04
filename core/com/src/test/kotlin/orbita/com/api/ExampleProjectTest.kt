// Проект-пример собирается СКРИПТОМ и только каналами (ЗАДАЧА-CODE-ПРОЕКТ-
// ПРИМЕР). Здесь — меры того, что делает сборку возможной и честной:
//
//  1. отменённый проект не числится в портфеле. Пересборка примера снимает
//     прежний отменой; пока счёт шёл по всем строкам, каждая пересборка
//     добавляла проект в отказ «в портфеле N проектов — укажите ?project»,
//     и на стенде с историей пересборок клиент вставал: единственный живой
//     проект переставал выбираться сам;
//  2. расчёты примера считаются, а не рисуются: у каждого выхода есть
//     расчёт-источник, числа сходятся с независимо известными величинами
//     (сторож tools/validate_example_models.py);
//  3. демо-значения примера ложатся в ключи анкет каркаса — таблица
//     значений и полка не разъезжаются (tools/validate_example_values.py).
package orbita.com.api

import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.CoreType
import orbita.mod.model.Lifecycle
import orbita.mod.schema.SchemaRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExampleProjectTest {

    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)

    private fun проект(id: String, имя: String) = boundary.ingest(
        CoreType.Project,
        """{"id":"$id","name":"$имя","phase":"pre_phase_a",
            "milestones":[{"gate":"MCR"}],
            "lifecycle":{"status":"Draft","version":"1"}}""",
        "test", id,
    )

    @BeforeEach
    fun clean() {
        TestDb.truncateAll()
    }

    @Test
    fun `отменённый проект в портфеле не числится`() {
        проект("PJ-2701", "Пример · сборка 1")
        проект("PJ-2702", "Пример · сборка 2")
        assertEquals(listOf("PJ-2701", "PJ-2702"), boundary.objects.projectIds())

        boundary.editing.cancel("PJ-2701", "test")
        assertEquals(
            listOf("PJ-2702"), boundary.objects.projectIds(),
            "снятый пересборкой пример обязан уйти из портфеля: иначе он остаётся " +
                "в отказе «укажите ?project» и живой проект перестаёт выбираться сам",
        )
        assertEquals(
            Lifecycle.Cancelled, boundary.objects.current("PJ-2701")!!.status,
            "из портфеля проект уходит ОТМЕНОЙ — история не стирается",
        )
    }

    @Test
    fun `единственный живой проект после пересборок выбирается сам`() {
        // три пересборки примера: две прежние сняты, живой остаётся один
        listOf("PJ-2711", "PJ-2712", "PJ-2713").forEach { проект(it, "Пример · $it") }
        boundary.editing.cancel("PJ-2711", "test")
        boundary.editing.cancel("PJ-2712", "test")
        val ids = boundary.objects.projectIds()
        assertEquals(1, ids.size, "живой проект один — ?project не нужен: $ids")
        assertEquals("PJ-2713", ids[0])
    }

    @Test
    fun `расчёты примера несут расчёт-источник и сходятся с эталонами`() {
        val (код, вывод) = запустить("tools/validate_example_models.py")
        assertEquals(0, код, "сторож расчётов примера не прошёл:\n$вывод")
        assertTrue(вывод.contains("расчёт-источник"), вывод)
    }

    @Test
    fun `демо-значения примера ложатся в ключи анкет каркаса`() {
        val (код, вывод) = запустить("tools/validate_example_values.py")
        assertEquals(0, код, "таблица демо-значений разошлась с полкой каркаса:\n$вывод")
        assertFalse(вывод.contains("НЕ в порядке"), вывод)
    }

    private fun запустить(скрипт: String): Pair<Int, String> {
        val процесс = ProcessBuilder("python3", скрипт)
            .directory(RepoPaths.repoRoot().toFile())
            .redirectErrorStream(true)
            .start()
        val вывод = процесс.inputStream.bufferedReader().readText()
        return процесс.waitFor() to вывод
    }
}
