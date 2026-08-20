// Мастер Ш1–Ш7 на РЕАЛЬНОЙ базе (STEP-7-9 §9.1, готовность шага 9).
//
// Проверяется не форма ответа, а свойство, ради которого мастер существует:
// «пусто» и «замечаний нет» — разные состояния. Шаг без объектов не закрыт,
// сколько бы замечаний в нём ни отсутствовало; иначе пустой проект выглядел бы
// готовым к контрольной точке.
package orbita.com.api

import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.schema.SchemaRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WizardTest {

    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)

    @BeforeAll
    fun seed() {
        TestDb.truncateAll()
        DemoProject.seed(boundary)
    }

    @Test
    fun `мастер состоит из семи шагов подряд`() {
        val steps = boundary.wizard.wizard(boundary.screens)
        assertEquals((1..7).toList(), steps.map { it.number })
        assertTrue(steps.all { it.title.isNotBlank() })
    }

    @Test
    fun `шаг закрыт только при наличии объектов и отсутствии замечаний`() {
        boundary.wizard.wizard(boundary.screens).forEach { step ->
            assertEquals(
                step.objects > 0 && step.issues.isEmpty(),
                step.complete,
                "шаг ${step.number} «${step.title}»: признак закрытия не совпал с составом",
            )
        }
    }

    /**
     * Демо-проект намеренно неидеален: витрина, где все шаги зелёные,
     * не показывает, как система ловит проблемы (STEP-7-9, ловушка 5).
     */
    @Test
    fun `на демо-проекте закрыты не все шаги`() {
        val steps = boundary.wizard.wizard(boundary.screens)
        assertTrue(steps.any { !it.complete }, "все шаги закрыты — демо-проект стал витриной")
        assertTrue(steps.any { it.complete }, "ни один шаг не закрыт — проверять нечего")
    }

    @Test
    fun `счётчик шага моделирования считает результаты, а не устаревшие`() {
        val step = boundary.wizard.wizard(boundary.screens).first { it.number == 5 }
        val results = boundary.results.activeForScenario(DEMO_SCENARIO, "kpi")
        assertEquals(results.size, step.objects)
        assertTrue(step.objects > 1, "результатов сравнения меньше двух: сравнивать не с чем")
    }

    @Test
    fun `нужда без сервисов попадает в замечания первого шага`() {
        val step = boundary.wizard.wizard(boundary.screens).first { it.number == 1 }
        assertEquals(boundary.wizard.needs().size, step.objects)
        val orphans = boundary.wizard.needs().filter { it.services.isEmpty() }
        assertEquals(orphans.size, step.issues.size)
        orphans.forEach { need -> assertTrue(step.issues.any { it.startsWith(need.id) }) }
    }

    @Test
    fun `непокрытый класс потребителей попадает в замечания второго шага`() {
        val step = boundary.wizard.wizard(boundary.screens).first { it.number == 2 }
        val uncovered = boundary.wizard.services().sumOf { it.uncoveredClasses.size }
        assertEquals(uncovered, step.issues.size)
        assertTrue(uncovered > 0, "в демо-проекте все классы покрыты — правило Р9 не проверяется")
    }

    /** Планка контрольной точки растёт: к SDR требования строже, чем к SRR. */
    @Test
    fun `разрывов к SDR не меньше, чем к SRR`() {
        val srr = boundary.wizard.readiness("SRR")
        val sdr = boundary.wizard.readiness("SDR")
        assertEquals(srr.totalObjects, sdr.totalObjects)
        assertTrue(
            sdr.gaps.size >= srr.gaps.size,
            "к SDR разрывов меньше, чем к SRR: планка не может смягчаться",
        )
    }

    @Test
    fun `готовность считает готовыми объекты без разрывов`() {
        val view = boundary.wizard.readiness("SRR")
        assertEquals(view.totalObjects - view.gaps.size, view.readyObjects)
        assertEquals(view.gaps.isEmpty(), view.ready)
    }
}
