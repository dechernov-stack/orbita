// Окна сцен по умолчанию (карта фазы, 27.09): план не утверждён — окна считает
// сервер из шаблона фазы и дат точек. Проверяется правило, а не числа наизусть:
// сцена кончается не позже своей точки, связи FS · SS · FF двигают окно, у каждой
// окно непустое, экземпляры наследуют окно сцены (их в расчёте нет).
package orbita.api

import orbita.api.internal.PlanDefaults
import orbita.process.api.ConditionView
import orbita.process.api.GateView
import orbita.process.api.PhaseView
import orbita.process.api.SceneLinkView
import orbita.process.api.SceneState
import orbita.process.api.SceneView
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanDefaultsTest {

    private fun сцена(ключ: String, точка: String?, вид: String? = null, vararg связи: Pair<String, String>) = SceneView(
        key = ключ, title = ключ, order = 0, role = "lead", question = "", state = SceneState.OPEN, blockers = emptyList(),
        links = связи.map { (на, тип) -> SceneLinkView(на, тип, "по шаблону") }, instanceOf = вид, gate = точка,
    )

    private fun точка(ключ: String, дата: String, vararg сцены: String) = GateView(
        key = ключ, title = ключ, order = 0, plannedDate = дата, passed = false, blocking = emptyList(),
        criteria = сцены.map { ConditionView("сцена $it прожита", "scene_done:$it", false, null) },
    )

    private val фаза = PhaseView(
        project = "PJ-9927", standard = "NASA-7120", phase = "Phase A", currentScene = "A1",
        scenes = listOf(
            сцена("A1", "SRR"),
            сцена("A2", "SRR", null, "A1" to "SS"),
            сцена("A3", "SRR", null, "A1" to "SS", "A2" to "FF"),
            сцена("A4", "SDR", null, "A5" to "INPUT", "A5" to "SS"),
            сцена("A4:EL-SC", "SDR", "A4"),
            сцена("A5", "SDR", null, "A3" to "SS", "A4" to "FF"),
            сцена("A10", "KDP-B", null, "A7" to "INPUT"),
            сцена("A11", "KDP-B", null, "A10" to "FS"),
        ),
        gates = listOf(
            точка("SRR", "2027-01-24", "A1", "A2", "A3"),
            точка("SDR", "2027-04-14", "A4", "A5"),
            точка("KDP-B", "2027-05-24", "A10", "A11"),
        ),
    )
    private val начало = LocalDate.parse("2026-10-02")
    private val окна = PlanDefaults.окна(фаза, начало).associateBy { it.scene }
    private fun д(т: String) = LocalDate.parse(т)

    @Test
    fun `сцена кончается не позже своей точки, окно непустое и не раньше начала фазы`() {
        val сроки = mapOf("A1" to "2027-01-24", "A2" to "2027-01-24", "A3" to "2027-01-24", "A4" to "2027-04-14", "A5" to "2027-04-14", "A10" to "2027-05-24", "A11" to "2027-05-24")
        сроки.forEach { (к, срок) ->
            val о = окна.getValue(к)
            assertTrue(!о.end.isAfter(д(срок)), "$к кончается к своей точке: $о")
            assertTrue(о.start.isBefore(о.end), "$к — окно непустое: $о")
            assertTrue(!о.start.isBefore(начало), "$к начинается не раньше фазы: $о")
        }
    }

    @Test
    fun `FS — после конца предшественника, SS — не раньше его начала, FF — вместе с ним`() {
        assertTrue(!окна.getValue("A11").start.isBefore(окна.getValue("A10").end), "A11 FS A10: ${окна["A11"]} после ${окна["A10"]}")
        assertTrue(!окна.getValue("A2").start.isBefore(окна.getValue("A1").start), "A2 SS A1")
        assertEquals(окна.getValue("A2").end, окна.getValue("A3").end, "A3 FF A2 — кончаются вместе")
    }

    @Test
    fun `экземпляры в расчёте не участвуют — наследуют окно сцены у движка`() {
        assertTrue("A4:EL-SC" !in окна, окна.keys.toString())
        assertTrue("A4" in окна)
    }

    @Test
    fun `только экземпляры без сцены шаблона — окно получает сцена шаблона`() {
        val безШаблона = фаза.copy(scenes = фаза.scenes.filter { it.key != "A4" })
        val окна = PlanDefaults.окна(безШаблона, начало).associateBy { it.scene }
        assertTrue("A4" in окна, "у экземпляров A4:… окно — окном сцены A4: ${окна.keys}")
        assertTrue(!окна.getValue("A4").end.isAfter(д("2027-04-14")))
    }

    @Test
    fun `у точек нет дат — окон по умолчанию нет, а не выдуманные`() {
        val безДат = фаза.copy(gates = фаза.gates.map { it.copy(plannedDate = null) })
        assertTrue(PlanDefaults.окна(безДат, начало).isEmpty())
    }
}
