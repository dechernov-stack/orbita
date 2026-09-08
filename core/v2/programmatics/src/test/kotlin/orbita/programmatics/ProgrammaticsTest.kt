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

    @Test
    fun `точка считает сроки по дате, а не по виду вехи`() {
        val проверки = ProgrammaticsFactory.gateChecks(store, программатика)
        точка("MCR", "phase", "2026-11-06")
        // Веха технологии — законный срок риска (решение владельца 08.09),
        // и её дата раньше даты MCR.
        точка("TRL-TECH-0001", "technology", "2026-10-15")
        риск("RSK-0001", "TRL-TECH-0001")

        // Срок хранится ИДЕНТИФИКАТОРОМ: так его кладёт маршрут заведения
        // риска, и искать только по коду значило не найти ни одного срока.
        val поId = store.create(
            "RSK-0003", "risk", область, "11",
            mapper.readTree(
                """{"statement":"риск по id","category":"техническое","probability":2,"impact":2,
                    "strategy":"accept","owner":"вед. СИ",
                    "due_point":"${store.byCode(область, "TRL-TECH-0001")!!.id}"}""",
            ),
            провенанс,
        )
        assertTrue(поId.code == "RSK-0003")

        val до = проверки.of(проект, "risks_due_closed:MCR")!!
        assertTrue(!до.passed, "открытый риск со сроком раньше точки её держит")
        assertTrue("RSK-0001" in (до.why ?: ""), "риск назван кодом: ${до.why}")
        assertTrue("RSK-0003" in (до.why ?: ""), "срок идентификатором тоже найден: ${до.why}")

        // Тот же риск со сроком ПОЗЖЕ точки её не держит: сравниваются даты.
        точка("TRL-TECH-0002", "technology", "2027-03-30")
        риск("RSK-0002", "TRL-TECH-0002")
        val после = проверки.of(проект, "risks_due_closed:MCR")!!
        assertTrue("RSK-0002" !in (после.why ?: ""), "срок позже точки её не держит: ${после.why}")
    }

    @Test
    fun `веха без даты названа, а не пропущена молча`() {
        val проверки = ProgrammaticsFactory.gateChecks(store, программатика)
        точка("MCR", "phase", "")
        val ответ = проверки.of(проект, "risks_due_closed:MCR")!!
        assertTrue(!ответ.passed, "без даты сравнивать нечего — это состояние, а не «всё хорошо»")
        assertTrue("нет даты" in (ответ.why ?: ""), ответ.why ?: "")
    }

    private fun точка(ключ: String, вид: String, дата: String) = store.create(
        ключ, "gate", область, "1",
        mapper.readTree("""{"title":"$ключ","kind":"$вид","planned_date":"$дата"}"""),
        провенанс,
    )

    private fun риск(код: String, срок: String) = store.create(
        код, "risk", область, "11",
        mapper.readTree(
            """{"statement":"риск $код","category":"техническое","probability":3,"impact":4,
                "strategy":"mitigate","owner":"вед. СИ","due_point":"$срок"}""",
        ),
        провенанс,
    )

    // --- каждый критерий обязан уметь отказать (правило владельца 08.09) ---

    @Test
    fun `критерии программатики отказывают на пустом проекте`() {
        val проверки = ProgrammaticsFactory.gateChecks(store, программатика)
        fun причина(условие: String): String {
            val ответ = проверки.of(проект, условие)
            assertTrue(ответ != null && !ответ.passed, "«$условие» обязано отказать на пустом проекте")
            return ответ!!.why ?: ""
        }
        assertTrue("рисков 0 из 3" in причина("risks_min:3"), причина("risks_min:3"))
        assertTrue("WBS не взят" in причина("wbs_paired"), причина("wbs_paired"))
        assertTrue("засорения" in причина("oda_started"), причина("oda_started"))
        assertTrue("технолог" in причина("technologies_named:1").lowercase())
        assertTrue(причина("estimate_ranged").isNotBlank())
    }

    @Test
    fun `разрыв TRL без пакета и стоимость без созревания — отказ`() {
        val проверки = ProgrammaticsFactory.gateChecks(store, программатика)
        val узел = узел("OBC")
        store.create(
            "TECH-0001", "technology", область, "10",
            mapper.readTree(
                """{"name":"алгоритмы FDIR","component":"${узел.id}",
                    "trl_current":4,"trl_required":6,"required_by":"PDR"}""",
            ),
            провенанс,
        )
        // Пакет созревания ещё не порождён: план не составлен.
        val планОтвет = проверки.of(проект, "maturation_planned")!!
        assertTrue(!планОтвет.passed, "разрыв TRL без пакета обязан держать сцену")

        программатика.maturation(проект, "Иванов И.")
        val стоимость = проверки.of(проект, "estimate_includes_maturation")!!
        assertTrue(
            !стоимость.passed && "созревание" in (стоимость.why ?: ""),
            "пакет созревания без оценки обязан держать сцену 12: ${стоимость.why}",
        )
    }

    @Test
    fun `риск без срока — отказ с кодом риска`() {
        val проверки = ProgrammaticsFactory.gateChecks(store, программатика)
        store.create(
            "RSK-0009", "risk", область, "11",
            mapper.readTree("""{"statement":"риск без срока","probability":3,"impact":3}"""),
            провенанс,
        )
        val ответ = проверки.of(проект, "each_risk_has_due_point")!!
        assertTrue(!ответ.passed && "RSK-0009" in (ответ.why ?: ""), ответ.why ?: "")
    }

    @Test
    fun `план пакета созревания ведёт дату вехи`() {
        val узел = узел("PL-RX")
        store.create(
            "TECH-0001", "technology", область, "10",
            mapper.readTree(
                """{"name":"приёмный тракт","component":"${узел.id}",
                    "trl_current":4,"trl_required":6,"required_by":"PDR"}""",
            ),
            провенанс,
        )
        программатика.maturation(проект, "Иванов И.")
        val веха = store.byCode(область, "TRL-TECH-0001")!!
        assertTrue(веха.doc.path("planned_date").asText("").isBlank(), "веха рождается без даты")

        val тронута = программатика.planPackage(
            проект, "04.TECH-0001", "2026-10-01", "2027-03-30", "Иванов И.",
        )
        assertEquals("TRL-TECH-0001", тронута, "план пакета ведёт свою веху")
        val после = store.byCode(область, "TRL-TECH-0001")!!
        assertEquals("2027-03-30", после.doc.path("planned_date").asText(), "дата вехи — конец работ")
        assertTrue(
            "04.TECH-0001" in после.doc.path("date_source").asText(),
            "источник даты назван: ${после.doc.path("date_source").asText()}",
        )
    }

    @Test
    fun `конец работ раньше начала не принимается`() {
        val узел = узел("PL-RX")
        store.create(
            "TECH-0001", "technology", область, "10",
            mapper.readTree(
                """{"name":"тракт","component":"${узел.id}",
                    "trl_current":4,"trl_required":6,"required_by":"PDR"}""",
            ),
            провенанс,
        )
        программатика.maturation(проект, "Иванов И.")
        val ошибка = runCatching {
            программатика.planPackage(проект, "04.TECH-0001", "2027-03-30", "2026-10-01", "Иванов И.")
        }.exceptionOrNull()
        assertTrue(
            ошибка?.message?.contains("раньше начала") == true,
            "план задом наперёд отклоняется: ${ошибка?.message}",
        )
    }
}
