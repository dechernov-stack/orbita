// Волна 4, программатика: пара к узлу, диапазон с допущениями, созревание.
//
// Три обещания, которые проверяются:
//   · пакет работ без пары к узлу состава — разрыв (работу без предмета
//     на обзоре защитить нечем);
//   · оценка одним числом не принимается: на Pre-A это ложная точность;
//   · технология с разрывом TRL САМА рождает пакет созревания и веху.
package orbita.programmatics

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.Provenance
import orbita.programmatics.api.EstimateMethod
import orbita.programmatics.api.ProgrammaticsFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProgrammaticsTest {

    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val программатика = ProgrammaticsFactory.programmatics(store, mapper)
    private val провенанс = Provenance(Channel.MANUAL, "Ведущий СИ")
    private val проект = "PJ-P01"
    private val область = Area.Project(проект)

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        store.create(проект, "project", область, "1", mapper.createObjectNode(), провенанс)
        val полка = mapper.readTree(TestDbV2.repoRoot.resolve("docs/tz/v2/ПОЛКА-WBS.json").toFile())
        store.create(
            полка.path("code").asText("WBS-9002"), "wbs_template", Area.Library, null, полка,
            Provenance(Channel.PACKAGE, "поставка"),
        )
    }

    private fun узел(код: String) = store.create(
        код, "component", область, "8",
        mapper.readTree("""{"name":"$код","level":2,"nature":"node","kind":"element"}"""),
        провенанс,
    )

    private fun технология(код: String, узел: String, текущий: Int, нужный: Int, резерв: String? = null) =
        store.create(
            код, "technology", область, "10",
            mapper.readTree(
                """{"name":"технология $код","component":"${store.byCode(область, узел)!!.id}",
                    "trl_current":$текущий,"trl_required":$нужный,"required_by":"PDR"
                    ${резерв?.let { ""","fallback":"$it"""" } ?: ""}}""",
            ),
            провенанс,
        )

    @Test
    fun `WBS берётся с полки и знает пары к узлам состава`() {
        узел("SC")
        val пакеты = программатика.takeWbs(проект, "Ведущий СИ")
        assertTrue(пакеты.size > 10, "типовой WBS поставки даёт дерево пакетов: ${пакеты.size}")
        assertTrue(
            пакеты.any { it.crossCutting },
            "сквозные пакеты (управление, СИ) пар к узлам не имеют — и это норма",
        )
        val безПары = пакеты.filter { !it.crossCutting && it.pbsRefs.isEmpty() }
        assertTrue(
            безПары.all { п -> п.gaps.any { it.contains("без пары") } },
            "несквозной пакет без узла — разрыв: работа без предмета не защищается",
        )
        assertEquals(пакеты.size, программатика.takeWbs(проект, "Ведущий СИ").size, "повторное взятие не плодит")
    }

    @Test
    fun `оценка одним числом не принимается, диапазон требует допущений`() {
        узел("SC")
        программатика.takeWbs(проект, "Ведущий СИ")
        val пакет = программатика.packages(проект).first().code

        val одноЧисло = assertFailsWith<IllegalArgumentException> {
            программатика.estimate(проект, пакет, 100.0, 100.0, "млн ₽", EstimateMethod.ROM, "как-то так", "СИ")
        }
        assertTrue(
            одноЧисло.message!!.contains("диапазон"),
            "точка вместо диапазона на Pre-A — ложная точность: ${одноЧисло.message}",
        )

        val безДопущений = assertFailsWith<IllegalArgumentException> {
            программатика.estimate(проект, пакет, 80.0, 140.0, "млн ₽", EstimateMethod.ROM, "  ", "СИ")
        }
        assertTrue(безДопущений.message!!.contains("допущени"), безДопущений.message!!)

        val оценка = программатика.estimate(
            проект, пакет, 80.0, 140.0, "млн ₽", EstimateMethod.ROM,
            "серийная платформа 12U, пуск попутный, курс на дату оценки", "СИ",
        )
        assertEquals(80.0, оценка.min)
        assertEquals(140.0, оценка.max)
        assertEquals(EstimateMethod.ROM, оценка.method)
        assertTrue(
            программатика.packages(проект).single { it.code == пакет }.gaps.none { it.contains("оценки нет") },
            "после оценки разрыв снимается",
        )
    }

    @Test
    fun `технология с разрывом TRL рождает пакет созревания и веху`() {
        узел("OBC")
        технология("TECH-FDIR", "OBC", 5, 6, резерв = "покупной модуль FDIR")
        технология("TECH-RTOS", "OBC", 9, 6)

        val созревание = программатика.maturation(проект, "Ведущий СИ")
        val fdir = созревание.single { it.technology == "TECH-FDIR" }
        assertNotNull(fdir.packageCode, "разрыв TRL обязан родить пакет работ")
        assertNotNull(fdir.milestoneGate, "и веху «TRL достигнут»")
        assertTrue(fdir.words.contains("TRL 5 → 6"), "разрыв назван числами: ${fdir.words}")
        assertTrue(fdir.words.contains("резервное решение"), fdir.words)

        val веха = store.byCode(область, fdir.milestoneGate!!)!!
        assertEquals("technology", веха.doc.path("kind").asText(), "веха технологии — не точка фазы")
        assertEquals("OBC", fdir.component, "созревание держится на узле, а не в воздухе")

        val rtos = созревание.single { it.technology == "TECH-RTOS" }
        assertEquals(null, rtos.packageCode, "без разрыва пакет не заводится: TRL 9 выше требуемого")
        assertTrue(rtos.words.contains("разрыва нет"), rtos.words)

        assertEquals(
            созревание.count { it.packageCode != null },
            программатика.maturation(проект, "Ведущий СИ").count { it.packageCode != null },
            "повтор не плодит пакетов",
        )
    }

    @Test
    fun `риск без срока-точки — разрыв, реестр идёт по уровню`() {
        узел("SC")
        store.create(
            "RSK-01", "risk", область, "11",
            mapper.readTree(
                """{"statement":"SEU в памяти без ECC → зависание","category":"technical",
                    "probability":3,"impact":4,"strategy":"mitigate","measures":"ECC и watchdog",
                    "owner":"Ведущий СИ"}""",
            ),
            провенанс,
        )
        store.create(
            "RSK-02", "risk", область, "11",
            mapper.readTree(
                """{"statement":"недооценка CPU в целевом режиме","category":"technical",
                    "probability":2,"impact":2,"strategy":"mitigate","measures":"бюджет compute к SDR",
                    "owner":"Ведущий СИ","due_point":"SDR"}""",
            ),
            провенанс,
        )

        val риски = программатика.risks(проект)
        assertEquals("RSK-01", риски.first().code, "реестр идёт по уровню: 3×4 выше 2×2")
        assertEquals(12, риски.first().level)
        assertTrue(
            программатика.gaps(проект, "MCR").any { it.contains("RSK-01") && it.contains("срока-точки") },
            "срок без точки не наступает: ${программатика.gaps(проект, "MCR")}",
        )
    }
}
