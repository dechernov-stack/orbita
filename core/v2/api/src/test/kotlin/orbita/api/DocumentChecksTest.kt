// Условия про документы обязаны УМЕТЬ ОТКАЗАТЬ (правило 08.09): документа
// нет · документ пуст · документ неполон к ступени · не базирован — каждое
// с причиной словами, и ни одно не пишет в реестр.
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentChecksTest {
    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val полки = TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые")
    private val проект = "PJ-9301"
    private val область = Area.Project(проект)
    private val провенанс = Provenance(Channel.MANUAL, "Иванов И.")
    private val документы = DocumentsFactory.documents(
        store, links,
        template = { код ->
            полки.resolve("ШАБЛОН-" + код.uppercase() + ".json").toFile().takeIf { it.isFile }?.let { mapper.readTree(it) }
        },
        mapper = mapper,
        baselineRoot = java.nio.file.Files.createTempDirectory("orbita-baselines").toFile().also { it.deleteOnExit() },
    )
    private val проверки = DocumentsFactory.gateChecks(документы)

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        store.create(проект, "project", область, "1", mapper.createObjectNode().put("name", "Проверка"), провенанс)
    }

    private fun почему(условие: String): String {
        val ответ = assertNotNull(проверки.of(проект, условие), "условие «$условие» — про документы, ответ обязан быть")
        assertFalse(ответ.passed, "условие «$условие» обязано отказать")
        return assertNotNull(ответ.why)
    }

    @Test
    fun `документа нет — отказ, пустой документ — отказ, чужое условие — не наше`() {
        assertTrue("нет" in почему("document_started:mcreport"))
        документы.ensure(проект, "mcreport", "Иванов И.")
        assertTrue("пуст" in почему("document_started:mcreport"), "документ без полных разделов — пуст")
        assertNull(проверки.of(проект, "risks_min"), "условие не про документы — не отвечаем за него")
    }

    @Test
    fun `неполный к ступени — отказ числами, не базирован — отказ словами`() {
        документы.ensure(проект, "mcreport", "Иванов И.")
        val к = почему("document_complete:mcreport:MCR")
        assertTrue(Regex("полны \\d+ из \\d+").containsMatchIn(к), к)
        assertTrue("не базирован" in почему("document_baselined:mcreport"))
    }

    @Test
    fun `условие без шаблона — отказ, а не пропуск`() {
        val ответ = assertNotNull(проверки.of(проект, "document_complete"))
        assertFalse(ответ.passed)
        assertEquals(true, ответ.why?.contains("шаблон"))
    }
}
