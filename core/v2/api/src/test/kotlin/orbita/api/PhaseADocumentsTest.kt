// Документы Phase A (шип G): SEMP §6 — 17 процессов с полки процессов СИ
// (запрос в область полки с развёрткой), ICD собирается из стыка, его
// параметров и требований с носителем-стыком (отбор по природе носителя).
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
import kotlin.test.assertTrue

class PhaseADocumentsTest {
    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val полки = TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые")
    private val проект = "PJ-9802"
    private val область = Area.Project(проект)
    private val провенанс = Provenance(Channel.MANUAL, "Чернов Д.")
    private val документы = DocumentsFactory.documents(
        store, links,
        template = { код -> полки.resolve("ШАБЛОН-" + код.uppercase() + ".json").toFile().takeIf { it.isFile }?.let { mapper.readTree(it) } },
        mapper = mapper,
        baselineRoot = java.nio.file.Files.createTempDirectory("orbita-baselines").toFile().also { it.deleteOnExit() },
    )

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        store.create(проект, "project", область, "1",
            mapper.createObjectNode().put("name", "Phase A документы").put("standard", "NASA-7120").put("phase", "Phase A"), провенанс)
        // полка процессов СИ — как её грузит load_shelves: одна запись с перечнем
        store.create("PRC-9001", "process_catalog", Area.Library, null, mapper.readTree(полки.resolve("ПОЛКА-ПРОЦЕССЫ-СИ.json").toFile()), провенанс)
    }

    private fun раздел(код: String, номер: String, ступень: String) =
        документы.document(проект, код, ступень).sections.first { it.no == номер }

    @Test
    fun `SEMP §6 — семнадцать строк процессов с полки, §2 читает нормативы полки`() {
        документы.ensure(проект, "semp", "Чернов Д.")
        val р6 = раздел("semp", "§6", "SRR")
        val строки = р6.elements.first().rows
        assertEquals(17, строки.size, "17 процессов NPR 7123.1 — мера шипа G")
        assertTrue(строки.any { it.any { я -> "Определение ожиданий стейкхолдеров" in я } }, строки.take(2).toString())
        assertTrue(строки.any { it.any { я -> "вне области Формулирования" in я } }, "отклонение названо, не умолчано")
        store.create("NR-0001", "normative_document", Area.Library, null,
            mapper.readTree("""{"designation":"ПП РФ № 2216","title":"О мониторинге транспортных средств","edition_date":"2020-12-22"}"""), провенанс)
        val р2 = раздел("semp", "§2", "SRR")
        assertEquals(1, р2.elements.first().rows.size, "норматив полки виден документу проекта")
        assertEquals(11, документы.document(проект, "semp", "SRR").sections.size)
    }

    @Test
    fun `ICD собирается из стыка — параметры и требования с носителем-стыком`() {
        val ка = store.create("EL-SC", "component", область, "7", mapper.readTree("""{"name":"КА","kind":"element","level":2,"nature":"node"}"""), провенанс)
        val терминал = store.create("EL-UT", "component", область, "7", mapper.readTree("""{"name":"Терминал","kind":"element","level":2,"nature":"node"}"""), провенанс)
        val стык = store.create("IF-S-USER", "interface", область, "7",
            mapper.readTree("""{"name":"КА — терминал","type":"rf","a":"${ка.id}","b":"${терминал.id}","direction":"both"}"""), провенанс)
        store.create("IF-S-USER.freq", "parameter", область, "A4",
            mapper.readTree("""{"target":"${стык.id}","key":"freq","measure":{"value":435.0,"unit":"МГц"},"origin":"design"}"""), провенанс)
        store.create("EL-SC.mass", "parameter", область, "A4",
            mapper.readTree("""{"target":"${ка.id}","key":"mass","measure":{"value":12.0,"unit":"кг"},"origin":"design"}"""), провенанс)
        store.create("RQ-S-0004", "requirement", область, "A4",
            mapper.readTree("""{"level":"system","title":"Пакет","statement":"Стык должен передавать пакет 32 байта за сеанс.","category":"interface","carrier":"${стык.id}"}"""), провенанс)
        store.create("RQ-S-0001", "requirement", область, "A4",
            mapper.readTree("""{"level":"system","title":"Кадр","statement":"КА должен передавать кадр раз за виток.","category":"performance","carrier":"${ка.id}"}"""), провенанс)
        документы.ensure(проект, "icd", "Чернов Д.")
        val реестр = раздел("icd", "§1", "SDR").elements.first().rows
        assertEquals(1, реестр.size)
        assertTrue(реестр.first().any { "IF-S-USER" in it }, реестр.toString())
        val параметры = раздел("icd", "§2", "SDR").elements.first().rows
        assertEquals(1, параметры.size, "только параметры стыков: масса КА в ICD не идёт")
        assertTrue(параметры.first().any { "freq" in it })
        val требования = раздел("icd", "§3", "SDR").elements.first().rows
        assertEquals(1, требования.size, "только требования с носителем-стыком")
        assertTrue(требования.first().any { "RQ-S-0004" in it })
    }
}
