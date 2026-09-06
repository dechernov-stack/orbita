// Волна 3, требования: природа носителя, базирование с условными
// элементами, подозрительные связи.
//
// Проверяется не «код работает», а четыре обещания ТЗ:
//   · требование уровня «интерфейсное» нельзя посадить на узел;
//   · снимок не берёт набор с помехами и называет их поимённо;
//   · незрелая технология делает элемент УСЛОВНЫМ, а не запрещает снимок;
//   · правка после снимка делает связь подозрительной, и снимает подозрение
//     человек — а новая правка возвращает его.
package orbita.requirements

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.Provenance
import orbita.requirements.api.BaselineKind
import orbita.requirements.api.BaselineRefused
import orbita.requirements.api.Ears
import orbita.requirements.api.Firmness
import orbita.requirements.api.RequirementsFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RequirementsTest {

    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val требования = RequirementsFactory.requirements(store, links, mapper)
    private val снимки = RequirementsFactory.baselines(store, links, mapper)
    private val провенанс = Provenance(Channel.MANUAL, "Ведущий СИ")
    private val проект = "PJ-R01"
    private val область = Area.Project(проект)

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        store.create(проект, "project", область, "1", mapper.createObjectNode(), провенанс)
    }

    private fun узел(код: String, род: String = "node") = store.create(
        код, "component", область, "8",
        mapper.readTree("""{"name":"$код","level":3,"nature":"$род","kind":"subsystem"}"""),
        провенанс,
    )

    private fun стык(код: String, a: String, b: String) = store.create(
        код, "interface", область, "8",
        mapper.readTree("""{"name":"$код","type":"data","a":"$a","b":"$b","direction":"bi","requirement_classes":["data"]}"""),
        провенанс,
    )

    /** Требование, годное к базированию: с носителем, источником, методом, критерием. */
    private fun требование(
        код: String,
        уровень: String,
        носитель: String,
        формулировка: String = "КА должен хранить принятые сообщения не менее 24 ч.",
        категория: String = "functional",
        показатель: String? = null,
    ) = store.create(
        код, "requirement", область, "8",
        (mapper.createObjectNode()).apply {
            put("level", уровень)
            put("title", "требование $код")
            put("statement", формулировка)
            put("category", категория)
            put("carrier", носитель)
            put("verification_method", "test")
            put("acceptance_criteria", "в журнале борта есть сообщение через 24 ч")
            put("ears_pattern", "ubiquitous")
            показатель?.let { set<com.fasterxml.jackson.databind.JsonNode>("measure", mapper.readTree(it)) }
            putArray("source").addObject().put("kind", "need").put("ref", "ND-0001")
        },
        провенанс,
    )

    @Test
    fun `линт видит форму шаблона и запрещённые слова`() {
        assertTrue(
            требования.lint("КА должен хранить сообщения не менее 24 ч.", Ears.ALWAYS).isEmpty(),
            "формулировка по шаблону «всегда» замечаний не даёт",
        )
        val событие = требования.lint("КА должен перейти в безопасный режим.", Ears.EVENT)
        assertTrue(
            событие.any { it.rule == "L-E1" },
            "формулировка без «Когда …,» не является требованием по событию: $событие",
        )
        val цель = требования.lint("Система должна обеспечить надёжно приём.", Ears.ALWAYS)
        assertTrue(цель.any { it.rule == "L-C6" }, "слово цели «обеспечить» обязано быть замечено: $цель")
        assertTrue(цель.any { it.rule == "L-C5" }, "расплывчатое «надёжно» обязано быть замечено: $цель")
    }

    @Test
    fun `интерфейсное требование на узле — помеха базированию с именем правила`() {
        val бцвм = узел("OBC")
        val по = узел("OBC-SW", "behaviour")
        val шина = стык("IF-DATA-BUS", бцвм.id, по.id)

        требование("RQ-S-01", "system", бцвм.id)
        // Природа носителя нарушена НАРОЧНО: интерфейсное требование посажено на узел.
        требование("RQ-I-01", "interface", бцвм.id)
        требование("RQ-I-02", "interface", шина.id)

        val помехи = снимки.blockers(проект, BaselineKind.ALLOCATED, "SRR")
        val про01 = помехи.filter { it.code == "RQ-I-01" }
        assertTrue(про01.any { it.rule == "И2" }, "нарушение природы носителя обязано быть названо: $помехи")
        assertTrue(
            про01.single { it.rule == "И2" }.what.contains("стык"),
            "помеха обязана сказать, ЧТО ожидается носителем: ${про01.map { it.what }}",
        )
        assertTrue(помехи.none { it.code == "RQ-I-02" }, "правильный носитель помехой не считается: $помехи")
    }

    @Test
    fun `снимок не берёт набор с помехами и называет их списком`() {
        val ка = узел("SC")
        требование("RQ-P-01", "project", ка.id)
        // Ничьё требование: носителя нет вовсе.
        store.create(
            "RQ-P-02", "requirement", область, "8",
            mapper.readTree(
                """{"level":"project","title":"ничьё","statement":"Необходимо обеспечить связь.",
                    "category":"functional","verification_method":"","ears_pattern":"ubiquitous"}""",
            ),
            провенанс,
        )

        val отказ = assertFailsWith<BaselineRefused> {
            снимки.baseline(проект, "SRR", BaselineKind.FUNCTIONAL, "SRR", "Ведущий СИ")
        }
        val про02 = отказ.blockers.filter { it.code == "RQ-P-02" }
        assertTrue(про02.any { it.what.contains("нет носителя") }, "$про02")
        assertTrue(про02.any { it.what.contains("нет источника") }, "$про02")
        assertTrue(про02.any { it.what.contains("нет метода") }, "$про02")
        assertTrue(про02.any { it.what.contains("критерия приёмки") }, "$про02")
        assertTrue(про02.any { it.rule == "И7" }, "формулировка вне шаблона обязана быть помехой: $про02")
        assertTrue(отказ.blockers.none { it.code == "RQ-P-01" }, "годное требование помехой не считается")
        assertEquals(emptyList(), снимки.list(проект), "отказ не оставляет полуснимков")
    }

    @Test
    fun `незрелая технология делает элемент условным, а не запрещает снимок`() {
        val бцвм = узел("OBC")
        val ка = узел("SC")
        требование("RQ-S-02", "system", бцвм.id)
        требование("RQ-S-03", "system", ка.id)
        store.create(
            "TECH-FDIR", "technology", область, "8",
            mapper.readTree(
                """{"name":"алгоритмы FDIR","component":"${бцвм.id}","trl_current":5,
                    "trl_required":6,"required_by":"PDR"}""",
            ),
            провенанс,
        )

        val снимок = снимки.baseline(проект, "SRR", BaselineKind.ALLOCATED, "SRR", "Ведущий СИ")
        val условные = снимок.conditional.map { it.code }
        assertTrue("RQ-S-02" in условные, "требование на узле незрелой технологии условно: ${снимок.items}")
        assertTrue("RQ-S-03" !in условные, "требование на зрелом узле остаётся твёрдым: ${снимок.items}")
        val условный = снимок.conditional.first { it.code == "RQ-S-02" }
        assertEquals("TECH-FDIR", условный.conditionalOn)
        assertTrue(
            условный.why!!.contains("TRL 5") && условный.why!!.contains("6"),
            "условие обязано называть разрыв TRL словами: ${условный.why}",
        )
        assertEquals(Firmness.CONDITIONAL, условный.firmness)
    }

    @Test
    fun `правка после снимка делает связь подозрительной, подтверждение снимает подозрение`() {
        val ка = узел("SC")
        val бцвм = узел("OBC")
        val верхнее = требование("RQ-P-03", "project", ка.id)
        val нижнее = требование("RQ-S-04", "system", бцвм.id)
        val связь = links.link(
            "derives_from", нижнее.id, верхнее.id, провенанс,
            rationale = "системное требование выведено из проектного",
        )

        снимки.baseline(проект, "SRR", BaselineKind.ALLOCATED, "SRR", "Ведущий СИ")
        assertEquals(emptyList(), снимки.suspects(проект), "сразу после снимка подозрений нет")

        store.update(
            нижнее.id,
            нижнее.doc.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
                .put("statement", "КА должен хранить принятые сообщения не менее 48 ч."),
            провенанс,
        )
        val подозрения = снимки.suspects(проект)
        assertEquals(1, подозрения.size, "правка конца делает связь подозрительной: $подозрения")
        assertEquals(связь.id, подозрения.single().linkId)
        assertTrue(
            подозрения.single().why.contains("RQ-S-04") && подозрения.single().why.contains("версия 2"),
            "подозрение обязано назвать, какой конец уехал: ${подозрения.single().why}",
        )
        assertTrue(
            снимки.blockers(проект, BaselineKind.ALLOCATED, "SDR").any { it.rule == "И5" },
            "подозрительная связь — разрыв к следующей точке",
        )

        снимки.confirm(проект, связь.id, "Ведущий СИ")
        assertEquals(emptyList(), снимки.suspects(проект), "подтверждение человека снимает подозрение")

        store.update(
            нижнее.id,
            нижнее.doc.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
                .put("statement", "КА должен хранить принятые сообщения не менее 72 ч."),
            провенанс,
        )
        assertEquals(
            1, снимки.suspects(проект).size,
            "новая правка возвращает подозрение: подтверждение относилось к тому, что было видно тогда",
        )
    }

    @Test
    fun `экземпляр типового помнит шаблон, а отклонение без обоснования не заводится`() {
        val бцвм = узел("OBC")
        store.create(
            "TR-SEC-01", "typical_requirement", Area.Library, null,
            mapper.readTree(
                """{"title":"защита команд","statement":"‹Носитель› должен проверять подпись команды.",
                    "category":"safety","verification_method":"inspection",
                    "acceptance_criteria":"команда без подписи отвергнута","ears_pattern":"ubiquitous"}""",
            ),
            Provenance(Channel.PACKAGE, "поставка"),
        )

        val взято = требования.instantiate(
            проект, "TR-SEC-01", "RQ-S-08", "OBC",
            orbita.requirements.api.Applicability.APPLIED, "Ведущий СИ",
        )
        assertEquals("OBC", взято.carrier)
        assertNotNull(взято.templateRef, "экземпляр обязан помнить шаблон")
        assertEquals(orbita.requirements.api.Applicability.APPLIED, взято.applicability)
        assertTrue(
            links.from(взято.id, "instantiates").isNotEmpty(),
            "связь с типовым нужна, чтобы новая редакция полки нашла экземпляры",
        )

        val отказ = assertFailsWith<IllegalArgumentException> {
            требования.instantiate(
                проект, "TR-SEC-01", "RQ-S-09", "OBC",
                orbita.requirements.api.Applicability.NOT_APPLICABLE, "Ведущий СИ",
            )
        }
        assertTrue(
            отказ.message!!.contains("обоснования"),
            "отклонение от типового без причины неотличимо от забывчивости: ${отказ.message}",
        )
    }
}
