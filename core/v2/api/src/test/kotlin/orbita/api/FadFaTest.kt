// FAD и FA (Прил. 1–2 БП-PPA, поставка 09.09): разделы-запросы наполняются
// реестром, раздел с тезисом ждёт слова руководителя, отбор пакетов по
// префиксу кода и рисков по критичности — и всё это к ступени KDP-A.
package orbita.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.documents.api.DocumentsFactory
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.Provenance
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FadFaTest {
    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val полки = TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые")
    private val проект = "PJ-9501"
    private val область = Area.Project(проект)
    private val провенанс = Provenance(Channel.MANUAL, "Чернов Д.")
    private val документы = DocumentsFactory.documents(
        store, links,
        template = { код ->
            полки.resolve("ШАБЛОН-" + код.uppercase() + ".json").toFile().takeIf { it.isFile }?.let { mapper.readTree(it) }
        },
        mapper = mapper,
        baselineRoot = java.nio.file.Files.createTempDirectory("orbita-baselines").toFile().also { it.deleteOnExit() },
    )

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        store.create(проект, "project", область, "1",
            mapper.createObjectNode().put("name", "Проверка FAD/FA").put("lead", "Чернов Д.").put("standard", "NASA-7120"), провенанс)
    }

    private fun раздел(код: String, номер: String, ступень: String = "KDP-A") =
        документы.document(проект, код, ступень).sections.first { it.no == номер }

    @Test
    fun `раздел с тезисом ждёт слова человека, запрос — реестра`() {
        документы.ensure(проект, "fad", "Чернов Д.")
        val до = раздел("fad", "§1")
        assertFalse(до.complete)
        assertTrue(до.waiting.any { it.startsWith("тезис: 0 из 1") && "основание" in it }, до.waiting.toString())
        assertTrue(до.waiting.any { "Замысел миссии" in it }, "запрос без замысла ждёт сцены 2: ${до.waiting}")
        store.create("INT-0001", "intent", область, "2",
            mapper.readTree("""{"for_whom":"перевозчики","what":"телеметрия","where":"СМП","horizon":"2033"}"""),
            провенанс, status = "accepted")
        документы.addStatement(проект, "fad", "§1", "Проект инициирован по поручению Минтранса.", emptyList(), "Чернов Д.")
        assertTrue(раздел("fad", "§1").complete, "замысел есть и тезис написан — раздел полон: ${раздел("fad", "§1").waiting}")
    }

    @Test
    fun `KDP-A в лестнице ступеней, разделы решения ждут только к ней`() {
        документы.ensure(проект, "fad", "Чернов Д.")
        val кMCR = документы.document(проект, "fad", "MCR")
        assertTrue(кMCR.sections.first { it.no == "§6" }.dueNow.not(), "§6 «согласование» к MCR ещё не ждут")
        assertEquals(1, кMCR.notDueYet)
        val кKDP = документы.document(проект, "fad", "KDP-A")
        assertEquals(0, кKDP.notDueYet, "к KDP-A ждут все разделы")
    }

    @Test
    fun `FA отбирает пакеты созревания по префиксу и ключевые риски по критичности`() {
        документы.ensure(проект, "fa", "Чернов Д.")
        store.create("04.TECH-0001", "wbs_package", область, "10", mapper.createObjectNode().put("name", "Созревание приёмника"), провенанс)
        store.create("01.SYS-0001", "wbs_package", область, "12", mapper.createObjectNode().put("name", "Системный анализ"), провенанс)
        store.create("RSK-0001", "risk", область, "11",
            mapper.readTree("""{"statement":"масса за рамкой","probability":4,"impact":5}"""), провенанс)
        store.create("RSK-0002", "risk", область, "11",
            mapper.readTree("""{"statement":"мелочь","probability":2,"impact":2}"""), провенанс)
        val второй = раздел("fa", "§2")
        val пакеты = второй.elements.first { it.code == "2.1" }
        assertEquals(listOf("04.TECH-0001"), пакеты.rows.map { it.first() }, "только группа 04")
        val риски = второй.elements.first { it.code == "2.2" }
        assertEquals(1, риски.rows.size, "критичность 20 проходит, 4 — нет")
        assertTrue(риски.rows.first().any { "масса за рамкой" in it })
    }
}
