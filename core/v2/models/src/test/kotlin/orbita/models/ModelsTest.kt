// Волна 4, модели: ответ вместо файла, снимок входов, два резерва, физика.
//
// Проверяется то, ради чего модуль существует:
//   · «модель дала ответ» — это прогон, а не заведённая запись;
//   · вход модели не задан → отказ с адресом, а не расчёт по пустому месту;
//   · правка входа после прогона делает ответ устаревшим;
//   · свёртка печатает ОБА резерва — по классу зрелости и системный;
//   · линк-бюджет считает физику, а не возвращает правдоподобное число.
package orbita.models

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.Provenance
import orbita.models.api.ModelsFactory
import orbita.models.api.Tool
import orbita.models.internal.LinkBudget
import kotlin.math.abs
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelsTest {

    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val модели = ModelsFactory.models(store, mapper)
    private val варианты = ModelsFactory.variants(store)
    private val провенанс = Provenance(Channel.MANUAL, "Ведущий СИ")
    private val проект = "PJ-M01"
    private val область = Area.Project(проект)

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        store.create(проект, "project", область, "1", mapper.createObjectNode(), провенанс)
        listOf(
            "docs/tz/v2/полки-порождённые/ПОЛКА-МОДЕЛИ-СИСТЕМЫ.json" to "model_template",
            "docs/tz/v2/ПОЛКА-ШАБЛОНЫ-КОМПОНЕНТОВ.json" to "component_template_shelf",
        ).forEach { (путь, вид) ->
            val файл = TestDbV2.repoRoot.resolve(путь).toFile()
            val док = mapper.readTree(файл)
            store.create(
                док.path("code").asText(вид), вид, Area.Library, null, док,
                Provenance(Channel.PACKAGE, "поставка"),
            )
        }
    }

    private fun узел(код: String) = store.create(
        код, "component", область, "8",
        mapper.readTree("""{"name":"$код","level":3,"nature":"node","kind":"subsystem"}"""),
        провенанс,
    )

    private fun параметр(узел: String, ключ: String, значение: Double, класс: String) = store.create(
        "$узел.$ключ", "parameter", область, "8",
        mapper.readTree(
            """{"target":"${store.byCode(область, узел)!!.id}","key":"$ключ",
                "measure":{"value":$значение,"unit":"кг"},"origin":"shelf",
                "required_to":"MCR","maturity_class":"$класс","uncertainty":20}""",
        ),
        провенанс,
    )

    @Test
    fun `взятие набора заводит записи со статусом «не построена»`() {
        val взято = модели.take(проект, "Ведущий СИ")
        assertTrue(взято.size >= 20, "полка поставки даёт весь набор моделей: ${взято.size}")
        val м2а = взято.single { it.code == "М2а" }
        assertEquals("MCR", м2а.requiredTo, "срок модели — данные поставки, а не догадка")
        assertEquals(Tool.BUILD, м2а.tool, "инструмент назван честно: модель ещё не построена")
        assertTrue(
            м2а.gaps.any { it.contains("не дала ответ") },
            "заведённая запись — ещё не ответ: ${м2а.gaps}",
        )
        assertTrue(
            м2а.gaps.any { it.contains("не привязана к стыку") },
            "модель линка живёт на ребре состава: ${м2а.gaps}",
        )
        assertEquals(взято.size, модели.take(проект, "Ведущий СИ").size, "повторное взятие не плодит записей")
    }

    @Test
    fun `вход модели не задан — расчёт отказывает с адресом`() {
        модели.take(проект, "Ведущий СИ")
        val м6 = store.byCode(область, "М6")!!
        store.update(
            м6.id,
            м6.doc.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().apply {
                putArray("inputs").addObject().put("param_ref", "parameter-нет-такого")
            },
            провенанс,
        )
        val отказ = assertFailsWith<IllegalArgumentException> { модели.run(проект, "М6", "Ведущий СИ") }
        assertTrue(
            отказ.message!!.contains("вход модели не задан"),
            "расчёт по пустому месту не делается: ${отказ.message}",
        )
    }

    @Test
    fun `прогон помнит снимок входов, а правка входа делает ответ устаревшим`() {
        модели.take(проект, "Ведущий СИ")
        узел("SC")
        val масса = параметр("SC", "mass_dry", 78.0, "estimated")
        val м6 = store.byCode(область, "М6")!!
        store.update(
            м6.id,
            м6.doc.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().apply {
                putArray("inputs").addObject().put("param_ref", масса.id)
            },
            провенанс,
        )

        val прогон = модели.run(проект, "М6", "Ведущий СИ", mapOf("mass_total" to "78 кг"))
        assertEquals(1, прогон.inputsSnapshot["SC.mass_dry"], "снимок входов помнит ВЕРСИЮ, а не значение")
        assertTrue(прогон.staleInputs.isEmpty(), "сразу после прогона входы свежие")
        assertEquals("answered", store.byCode(область, "М6")!!.status, "прогон — это и есть «модель дала ответ»")

        store.update(
            масса.id,
            масса.doc.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().apply {
                putObject("measure").put("value", 84.0).put("unit", "кг")
            },
            провенанс,
        )
        val после = модели.list(проект).single { it.code == "М6" }.lastRun!!
        assertTrue(
            после.staleInputs.any { it.contains("SC.mass_dry") },
            "вход уехал — ответ устарел, и это видно: ${после.staleInputs}",
        )
    }

    @Test
    fun `свёртка печатает оба резерва - по классу зрелости и системный`() {
        узел("SC"); узел("EPS")
        параметр("SC", "mass_dry", 78.0, "estimated")
        параметр("EPS", "mass_dry", 12.0, "off_the_shelf")

        val бюджет = модели.budget(проект, "mass", "MCR")
        assertEquals(90.0, бюджет.sum, "сумма — это сумма, без резервов")
        // 78 × 1,20 + 12 × 1,05 = 93,6 + 12,6 = 106,2
        assertTrue(abs(бюджет.withClassReserve - 106.2) < 0.01, "резерв по классам: ${бюджет.withClassReserve}")
        assertEquals(20, бюджет.systemMarginPercent, "системный резерв к MCR — 20 % с полки")
        assertTrue(
            abs(бюджет.withSystemMargin - 127.44) < 0.05,
            "системный резерв ложится поверх классового: ${бюджет.withSystemMargin}",
        )
        assertTrue(бюджет.note.contains("раздельно"), "оба резерва печатаются раздельно: ${бюджет.note}")
        assertEquals("estimated", бюджет.lines.first().maturity, "у строки виден класс зрелости")
    }

    @Test
    fun `сравнение вариантов - без итогового балла, отсеянный назван порогом`() {
        listOf(
            """{"key":"p95_latency","title":"P95 задержки","threshold":180,"worse_if":"greater","group":"служба"}""",
            """{"key":"coverage_a","title":"Покрытие A′","threshold":0.8,"worse_if":"less","group":"служба"}""",
        ).forEachIndexed { i, док ->
            store.create("CR-${i + 1}", "criterion", область, "7", mapper.readTree(док), провенанс)
        }
        fun вариант(код: String, имя: String, задержка: Double, покрытие: Double) = store.create(
            код, "constellation_variant", область, "7",
            mapper.readTree(
                """{"name":"$имя","metrics":[
                    {"key":"p95_latency","value":$задержка,"unit":"мин","group":"служба"},
                    {"key":"coverage_a","value":$покрытие,"unit":"доля","group":"служба"}]}""",
            ),
            провенанс,
        )
        вариант("V1", "Walker 4×6 550 км", 142.0, 0.86)
        вариант("V2", "Walker 6×8 550 км", 96.0, 0.91)
        вариант("V3", "ССО 3×8 600 км", 210.0, 0.64)

        val сравнение = варианты.compare(проект)
        val третий = сравнение.variants.single { it.code == "V3" }
        assertNotNull(третий.rejectedBy, "отсеянный вариант обязан назвать порог")
        assertTrue(
            третий.rejectedBy!!.contains("P95") && третий.rejectedBy!!.contains("180"),
            "порог называется по имени и значению: ${третий.rejectedBy}",
        )
        assertTrue(сравнение.variants.single { it.code == "V2" }.pareto, "V2 не хуже V1 ни в чём — он на фронте")
        assertTrue(!сравнение.variants.single { it.code == "V1" }.pareto, "V1 доминируется V2")
        assertTrue(
            сравнение.note.contains("итогового балла нет"),
            "свёртка в балл прячет решение в коэффициенты: ${сравнение.note}",
        )
        assertTrue(
            сравнение.variants.none { it.metrics.isEmpty() },
            "у каждого варианта метрики своими строками",
        )
    }

    @Test
    fun `impact требования называет узел, событие и документ`() {
        val links = KernelFactory.linkRegistry(TestDbV2.conn)
        val влияние = ModelsFactory.impact(store, links)
        val бцвм = узел("OBC")
        val требование = store.create(
            "RQ-S-08", "requirement", область, "8",
            mapper.readTree(
                """{"level":"subsystem","title":"защита команд","carrier":"${бцвм.id}",
                    "statement":"ПО должно проверять подпись команды.","category":"safety"}""",
            ),
            провенанс,
        )
        store.create(
            "VE-01", "verification_event", область, "9",
            mapper.readTree("""{"requirement":"${требование.id}","method":"inspection"}"""), провенанс,
        )
        val документ = store.create(
            "DOC-SRS", "document", область, "9",
            mapper.readTree("""{"code":"SRS","title":"спецификация ПО"}"""), провенанс,
        )
        links.link("snapshot_of", документ.id, требование.id, провенанс)

        val граф = влияние.of(проект, "RQ-S-08")
        val виды = граф.nodes.filter { it.depth > 0 }.map { it.kind }.toSet()
        assertTrue("component" in виды, "impact называет носителя: $виды")
        assertTrue("verification_event" in виды, "и событие верификации: $виды")
        assertTrue("document" in виды, "и документ, где это напечатано: $виды")
        assertTrue(граф.summary.contains("заденет"), граф.summary)
        assertTrue(граф.edges.any { it.type == "carrier" }, "ребро носителя названо полем: ${граф.edges}")
    }

    @Test
    fun `линк-бюджет считает физику, а не правдоподобное число`() {
        // Контрольный случай: 868 МГц, 550 км, угол места 30°, ЭИИМ 14 дБм.
        val дальность = LinkBudget.slantRangeM(550_000.0, 30.0)
        assertTrue(дальность > 550_000 && дальность < 1_200_000, "наклонная дальность: $дальность")
        // FSPL на 868 МГц и ~1000 км ≈ 151 дБ (проверка по формуле Фрииса)
        val потери = LinkBudget.fspl(1_000_000.0, 868e6)
        assertTrue(abs(потери - 151.2) < 0.5, "потери в свободном пространстве: $потери")

        val ответ = LinkBudget.рассчитать(
            LinkBudget.Вход(
                txPowerW = 0.025, txGainDbi = 2.0, rxGainDbi = 6.0, frequencyHz = 868e6,
                rangeM = дальность, noiseTempK = 500.0, bitrateBps = 300.0,
            ),
        )
        assertTrue(ответ.closes, "терминал 25 мВт на 300 бит/с должен замыкаться: ${ответ.words}")
        assertTrue(ответ.words.contains("запасом"), ответ.words)

        val быстрый = LinkBudget.рассчитать(
            LinkBudget.Вход(
                txPowerW = 0.025, txGainDbi = 2.0, rxGainDbi = 6.0, frequencyHz = 868e6,
                rangeM = дальность, noiseTempK = 500.0, bitrateBps = 250_000.0,
            ),
        )
        assertTrue(!быстрый.closes, "на 250 кбит/с та же линия не замыкается: ${быстрый.words}")
        assertTrue(быстрый.words.contains("не хватает"), быстрый.words)
    }
}
