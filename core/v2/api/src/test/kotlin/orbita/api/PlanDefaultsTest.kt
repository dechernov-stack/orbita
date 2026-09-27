// Окна сцен по умолчанию (карта фазы, 27.09): план не утверждён — окна считает
// сервер из шаблона фазы и дат точек лесенкой по связям шаблона (владелец 27.09:
// «а не все от сегодня до своей точки»). Проверяется правило, а не числа
// наизусть: сцена кончается не позже своей точки; FS — с конца предшественника,
// цепочка FS делит время поровну и идёт ступенями без зазоров; SS — через неделю
// после начала, INPUT — через две; условия входа — те же связи; окно непустое;
// экземпляры наследуют окно сцены (их в расчёте нет).
package orbita.api

import orbita.api.internal.PlanDefaults
import orbita.process.api.ConditionView
import orbita.process.api.GateView
import orbita.process.api.PhaseView
import orbita.process.api.SceneLinkView
import orbita.process.api.SceneState
import orbita.process.api.SceneView
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanDefaultsTest {

    private fun сцена(
        ключ: String, точка: String?, связи: List<Pair<String, String>> = emptyList(), вид: String? = null,
        вход: List<String> = emptyList(), выход: List<String> = emptyList(),
    ) = SceneView(
        key = ключ, title = ключ, order = 0, role = "lead", question = "", state = SceneState.OPEN, blockers = emptyList(),
        entry = вход.map { ConditionView(it, it, false, null) }, exit = выход.map { ConditionView(it, it, false, null) },
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
            сцена("A2", "SRR", listOf("A1" to "SS")),
            сцена("A3", "SRR", listOf("A1" to "SS", "A2" to "FF")),
            сцена("A4", "SDR", listOf("A5" to "INPUT", "A5" to "SS")),
            сцена("A4:EL-SC", "SDR", вид = "A4"),
            сцена("A5", "SDR", listOf("A3" to "SS", "A4" to "FF")),
            // связь только входом: «аванпроект начат» — SS
            сцена("A6", "SDR", listOf("A4" to "FF"), вход = listOf("scene_started:A4")),
            сцена("A10", "KDP-B", listOf("A7" to "INPUT")),
            сцена("A11", "KDP-B", listOf("A10" to "FS"), вход = listOf("scene_done:A10")),
        ),
        gates = listOf(
            точка("SRR", "2027-01-24", "A1", "A2", "A3"),
            точка("SDR", "2027-04-14", "A4", "A5", "A6"),
            точка("KDP-B", "2027-05-24", "A10", "A11"),
        ),
    )
    private val начало = LocalDate.parse("2026-10-02")
    private val окна = PlanDefaults.окна(фаза, начало).associateBy { it.scene }
    private fun д(т: String) = LocalDate.parse(т)
    private fun дней(о: PlanDefaults.Окно) = ChronoUnit.DAYS.between(о.start, о.end)

    @Test
    fun `сцена кончается не позже своей точки, окно непустое и не раньше начала фазы`() {
        val сроки = mapOf(
            "A1" to "2027-01-24", "A2" to "2027-01-24", "A3" to "2027-01-24", "A4" to "2027-04-14", "A5" to "2027-04-14",
            "A6" to "2027-04-14", "A10" to "2027-05-24", "A11" to "2027-05-24",
        )
        сроки.forEach { (к, срок) ->
            val о = окна.getValue(к)
            assertTrue(!о.end.isAfter(д(срок)), "$к кончается к своей точке: $о")
            assertTrue(о.start.isBefore(о.end), "$к — окно непустое: $о")
            assertTrue(!о.start.isBefore(начало), "$к начинается не раньше фазы: $о")
        }
    }

    @Test
    fun `лесенка — SS через неделю после начала предшественника, INPUT через две, а не все с начала фазы`() {
        val н = { к: String -> окна.getValue(к).start }
        assertEquals(начало, н("A1"), "первая сцена — с начала фазы")
        assertEquals(н("A1").plusDays(7), н("A2"), "A2 SS A1")
        assertEquals(н("A3").plusDays(7), н("A5"), "A5 SS A3")
        assertEquals(н("A5").plusDays(14), н("A4"), "A4 INPUT A5: вход должен появиться")
        assertEquals(н("A4").plusDays(7), н("A6"), "A6 — вход «аванпроект начат»: SS")
        assertTrue(окна.values.map { it.start }.toSet().size >= 5, "начала лесенкой: ${окна.values}")
    }

    @Test
    fun `FS — с конца предшественника без зазора, FF — кончаются вместе`() {
        assertEquals(окна.getValue("A10").end, окна.getValue("A11").start, "A11 FS A10 — ступень без зазора: ${окна["A10"]} → ${окна["A11"]}")
        assertEquals(окна.getValue("A2").end, окна.getValue("A3").end, "A3 FF A2 — кончаются вместе")
        assertEquals(д("2027-05-24"), окна.getValue("A11").end, "последнее звено — до своей точки")
    }

    @Test
    fun `цепочка из условий входа (Pre-A) — ступени поровну до сроков, вход-выход другой сцены тоже FS`() {
        // Pre-A: связей depends нет, цепочку задают входы; «замысел принят» — выход сцены 2.
        val преА = PhaseView(
            project = "PJ-9928", standard = "NASA-7120", phase = "Pre-Phase A", currentScene = "1",
            scenes = listOf(
                сцена("1", null, выход = listOf("project_exists")),
                сцена("2", null, вход = listOf("scene_done:1"), выход = listOf("intent_accepted")),
                сцена("3", null, вход = listOf("intent_accepted")),
                сцена("4", null, вход = listOf("scene_done:3")),
            ),
            gates = listOf(точка("internal_review", "2026-11-01", "2"), точка("MCR", "2026-12-01", "3", "4")),
        )
        val о = PlanDefaults.окна(преА, начало).associateBy { it.scene }
        val ступени = listOf("1", "2", "3", "4").map { о.getValue(it) }
        ступени.zipWithNext().forEach { (было, стало) -> assertEquals(было.end, стало.start, "ступень без зазора: $было → $стало") }
        assertEquals(1, ступени.map { дней(it) }.toSet().size, "время до сроков — поровну между звеньями: $ступени")
        assertEquals(начало, ступени.first().start)
        assertTrue(!о.getValue("2").end.isAfter(д("2026-11-01")), "замысел — к внутреннему обзору")
        assertEquals(д("2026-12-01"), ступени.last().end, "последнее звено — до MCR")
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
    fun `встречные FS в шаблоне — расчёт не виснет, окна непустые`() {
        val кольцо = фаза.copy(
            scenes = listOf(сцена("X", "SRR", вход = listOf("scene_done:Y")), сцена("Y", "SRR", вход = listOf("scene_done:X"))),
        )
        val о = PlanDefaults.окна(кольцо, начало)
        assertEquals(setOf("X", "Y"), о.map { it.scene }.toSet())
        о.forEach { assertTrue(it.start.isBefore(it.end), "окно непустое: $it") }
    }

    @Test
    fun `у точек нет дат — окон по умолчанию нет, а не выдуманные`() {
        val безДат = фаза.copy(gates = фаза.gates.map { it.copy(plannedDate = null) })
        assertTrue(PlanDefaults.окна(безДат, начало).isEmpty())
    }
}
