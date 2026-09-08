// Точки как экспертизы (шип D): замечание — событие движка, решение — с
// ролью, отказ — с именами, переход фазы — только решением KDP-A.
//
// Домена здесь нет: оценщик подменён таблицей условий, замечания и
// решения — списками в памяти. Проверяется ровно движок.
package orbita.process

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.process.api.DecisionView
import orbita.process.api.FindingView
import orbita.process.api.GateEvaluator
import orbita.process.api.GateHeldException
import orbita.process.api.ProcessFactory
import orbita.process.api.RoleRefusedException
import orbita.process.api.SceneState
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PointsEngineTest {
    private val mapper = ObjectMapper()
    private val корень: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { it.resolve("docs/tz/v2").toFile().isDirectory }
    private val шаблон = mapper.readTree(
        корень.resolve("docs/tz/v2/полки-порождённые/ШАБЛОН-ФАЗЫ-PRE-A-NASA.json").toFile(),
    )

    /** Условия, которые «не выполнены»; остальные считаются выполненными. */
    private val держат = mutableSetOf<String>()
    private val замечания = mutableListOf<FindingView>()
    private val решения = mutableMapOf<String, DecisionView>()
    private val записано = mutableListOf<String>()
    private var фаза: String? = null
    private val пройдены = mutableSetOf<String>()

    private val оценщик = object : GateEvaluator {
        override fun why(project: String, check: String): String? =
            if (check in держат) "условие $check не выполнено" else null
    }

    private fun движок(сЗаписью: Boolean = true) = ProcessFactory.engine(
        template = { шаблон },
        evaluator = оценщик,
        passedGates = { пройдены },
        gatePlan = { emptyMap() },
        findings = { замечания.toList() },
        decisions = { решения.toMap() },
        phaseOf = { фаза },
        onDecision = if (!сЗаписью) null else { _, точка, кем, исход, _, открывает ->
            записано += "$точка:$исход:$кем"
            if (исход == "approve") { пройдены += точка; открывает?.let { фаза = it } }
            решения[точка] = DecisionView(кем, "сейчас", исход, null)
        },
    )

    @Test
    fun `открытое замечание возвращает прожитую сцену в работу, закрытое — отпускает`() {
        val движок = движок()
        assertEquals(SceneState.DONE, движок.view("P").scenes.first { it.key == "2" }.state, "без помех сцена 2 прожита")
        замечания += FindingView("FND-0001", "замысел не называет горизонт", "2", "internal_review", "open", "Чернов Д.")
        val сцена = движок.view("P").scenes.first { it.key == "2" }
        assertEquals(SceneState.OPEN, сцена.state, "замечание с возвратом — событие: сцена снова в работе")
        assertTrue(сцена.exit.any { it.check == "finding_closed:FND-0001" && !it.passed }, "условие выхода названо замечанием")
        assertTrue(сцена.blockers.any { "замысел не называет горизонт" in it })
        замечания[0] = замечания[0].copy(status = "closed")
        assertEquals(SceneState.DONE, движок.view("P").scenes.first { it.key == "2" }.state, "закрыли — прожита вновь")
    }

    @Test
    fun `точка несёт критерии целиком, экспертизу с позициями и матрицу`() {
        val точки = движок().view("P").gates
        val mcr = точки.first { it.key == "MCR" }
        assertEquals("da_review", mcr.role)
        assertTrue(mcr.criteria.size >= 6 && mcr.criteria.all { it.passed }, "критерии — все, с состоянием")
        val экспертиза = assertNotNull(mcr.expertise)
        assertEquals("EXP-0A", экспертиза.source)
        assertTrue(экспертиза.questions.size >= 3)
        assertTrue(экспертиза.positions.size >= 10, "позиции полки Романова пришли данными")
        assertTrue(экспертиза.positions.any { it.passed == null && it.ourRef == null }, "несопоставленная позиция видна, а не спрятана")
        val kdp = точки.first { it.key == "KDP-A" }
        assertEquals("Phase A", kdp.opensPhase)
        assertEquals(9, kdp.matrix.size, "матрица Д1–Д9")
        assertTrue(kdp.matrix.any { it.artifact.startsWith("Д1") && it.passed == null }, "FAD без шаблона — не проверяется, и это видно")
        assertEquals("MCR", точки.first { it.key == "internal_review" }.checklistOf)
        assertNotNull(mcr.legendNote, "легенда кодов — предположительно, и это сказано")
    }

    @Test
    fun `позиция по реестру — помета со состоянием, точку держит только документ`() {
        держат += "exists:goal"
        val mcr = движок().view("P").gates.first { it.key == "MCR" }
        val цели = mcr.expertise!!.positions.first { it.artifact == "цели задания и задачи" }
        assertEquals(false, цели.passed, "состояние позиции по реестру видно")
        assertTrue(mcr.blocking.none { "цели задания и задачи" in it }, "но точку она не держит: ${mcr.blocking}")
        держат -= "exists:goal"
        // Документ — мера есть: строка матрицы Д2 к KDP-A держит точку с именем
        держат += "document_complete:mcreport:KDP-A"
        пройдены += "MCR"
        val kdp = движок().view("P").gates.first { it.key == "KDP-A" }
        assertTrue(kdp.blocking.any { "Д2" in it }, "документ держит: ${kdp.blocking}")
        держат.clear()
        assertTrue(движок().view("P").gates.first { it.key == "KDP-A" }.blocking.isEmpty())
    }

    @Test
    fun `решение чужой ролью — отказ с именами, а не тихий пропуск`() {
        val отказ = assertFailsWith<RoleRefusedException> {
            движок().decide("P", "MCR", "Петрова М.", setOf("specialist"), "approve", null)
        }
        assertTrue("Петрова М." in отказ.message!! && "инженер" in отказ.message!!, отказ.message)
        assertTrue(записано.isEmpty())
    }

    @Test
    fun `approve при блокирующем — отказ движка с критерием, без помех — запись решения`() {
        держат += "risks_due_closed:MCR"
        val держит = assertFailsWith<GateHeldException> {
            движок().decide("P", "MCR", "Чернов Д.", setOf("lead", "da_review"), "approve", null)
        }
        assertTrue("risks_due_closed:MCR" in держит.message!!, держит.message)
        держат.clear()
        val вид = движок().decide("P", "MCR", "Чернов Д.", setOf("da_review"), "approve", "без замечаний")
        assertTrue(вид.gates.first { it.key == "MCR" }.passed)
        assertEquals(listOf("MCR:approve:Чернов Д."), записано)
        assertEquals("approve", вид.gates.first { it.key == "MCR" }.decision?.outcome)
    }

    @Test
    fun `return без открытых замечаний — отказ, с замечанием — записывается`() {
        assertFailsWith<IllegalArgumentException> {
            движок().decide("P", "internal_review", "Чернов Д.", setOf("lead"), "return", null)
        }
        замечания += FindingView("FND-0001", "нет владельца нужды", "3", "internal_review", "open", "Чернов Д.")
        движок().decide("P", "internal_review", "Чернов Д.", setOf("lead"), "return", "вернуть в сцену 3")
        assertEquals(listOf("internal_review:return:Чернов Д."), записано)
        assertNull(фаза)
    }

    @Test
    fun `KDP-A открывает Phase A — только решением, и фаза читается из данных`() {
        val движок = движок()
        assertEquals("Pre-Phase A", движок.view("P").phase)
        пройдены += "MCR"
        движок.decide("P", "KDP-A", "Чернов Д.", setOf("da_review"), "approve", null)
        assertEquals("Phase A", фаза)
        assertEquals("Phase A", движок.view("P").phase)
        assertTrue(движок.view("P").gates.all { it.key != "KDP-A" || it.passed })
    }

    @Test
    fun `passGate без записи решения живёт в памяти — как раньше у тестов волны 1`() {
        val движок = движок(сЗаписью = false)
        движок.passGate("P", "internal_review", "Чернов Д.")
        assertTrue("internal_review" in пройдены)
    }
}
