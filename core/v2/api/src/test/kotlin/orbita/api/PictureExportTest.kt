// Выгрузка картины (шип 4 §5): пять блоков с отпечатком среза, принятые
// объекты по кодам, цепочка задачи с пустыми звеньями, вопросы, права и
// единый формат ответа; пакет обратно проходит сверку по отпечатку.
package orbita.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.api.internal.ExchangeRoutes
import orbita.documents.api.DocumentsFactory
import orbita.exchange.api.ExchangeFactory
import orbita.exchange.api.KnowledgeFingerprint
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.Provenance
import orbita.knowledge.api.KnowledgeFactory
import orbita.library.api.LibraryFactory
import orbita.process.api.ProcessFactory
import orbita.readiness.api.ReadinessFactory
import java.util.zip.ZipInputStream
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PictureExportTest {
    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val проект = "PJ-9860"
    private val область = Area.Project(проект)
    private val провенанс = Provenance(Channel.MANUAL, "Иванов И.")
    private val п = mapOf("project" to проект)
    private val шаблон = mapper.readTree(TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые/ШАБЛОН-ФАЗЫ-PRE-A-NASA.json").toFile())
    private val полки = LibraryFactory.shelves(store) { шаблон }
    private val intake = KnowledgeFactory.intake(store, links, mapper, полки)
    private val обмен = ExchangeFactory.exchange(store, links, intake, mapper, strictDoc = null, reqif = null)

    private val маршруты: ExchangeRoutes by lazy {
        val движок = ProcessFactory.engine(
            template = { шаблон },
            evaluator = ReadinessFactory.gateEvaluator(store, links, scenesDone = { emptySet() }, gatesPassed = { emptySet() }),
            passedGates = { mutableSetOf() }, gatePlan = { emptyMap() },
        )
        val документы = DocumentsFactory.documents(store, links, template = { null }, mapper = mapper, baselineRoot = java.nio.file.Files.createTempDirectory("orbita-pic").toFile())
        ExchangeRoutes(store, обмен, движок, документы, mapper)
    }

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        store.create(проект, "project", область, "1", mapper.createObjectNode().put("name", "Картина").put("knowledge_v2", true), провенанс)
        store.create("SK-0001", "stakeholder", область, "3", mapper.createObjectNode().put("name", "Минтранс России").put("role", "customer"), провенанс, status = "accepted")
        store.create("ND-0001", "need", область, "3", mapper.createObjectNode().put("statement", "связь в высоких широтах"), провенанс, status = "accepted")
        store.create("GT-0001", "glossary_term", Area.Library, null,
            mapper.createObjectNode().put("term_ru", "Заинтересованная сторона").put("term_en", "Stakeholder").put("class", "se_concept").put("object_code", "stakeholder").put("source", "NASA SEH App. B").put("definition", "…"),
            провенанс, status = "accepted")
    }

    @Test
    fun `пять блоков с отпечатком, срез по задаче, принятые объекты кодами, пустые звенья названы`() {
        val пакет = обмен.picture(проект, "reading")
        assertEquals(listOf("01-срез-картины.md", "02-принятые-объекты.md", "03-цепочка-задачи.md", "04-вопросы.md", "05-права-и-формат.md"), пакет.files.keys.sorted())
        assertEquals(16, пакет.fingerprint.length)
        assertEquals(пакет.fingerprint, KnowledgeFingerprint.of(пакет.files), "шапка с отпечатком в хеш не входит")
        пакет.files.values.forEach { assertTrue(it.startsWith("<!-- отпечаток картины: ${пакет.fingerprint}"), "шапка в каждом файле") }

        val срез = пакет.files.getValue("01-срез-картины.md")
        assertTrue("## Область 1 · Мир" in срез && "## Область 2 · Постановка" in срез, срез.take(400))
        assertFalse("## Область 5" in срез, "срез чтения — области 1–2, без требований")
        assertTrue("`stakeholder`" in срез, "понятие среза названо кодом")
        assertTrue("| `qos_class` |" in срез && "справочник" in срез, "граница понятия словами картины: ${срез.lines().firstOrNull { "qos_class" in it }}")
        assertTrue(срез.lines().first { "| `stakeholder` |" in it }.split("|").size >= 6, "у стороны есть колонка «чем не является»")
        assertTrue("Stakeholder (NASA SEH App. B)" in срез, "стандартное имя из словаря библиотеки")
        assertTrue("`charter` — устав миссии" in срез, "роли документов в срезе постановки")

        val объекты = пакет.files.getValue("02-принятые-объекты.md")
        assertTrue("- `SK-0001` · v1 · accepted — Минтранс России" in объекты, объекты)
        assertTrue("- `ND-0001` · v1 · accepted — связь в высоких широтах" in объекты)
        assertTrue("(`goal`) — 0" in объекты && "_пусто_" in объекты)

        val цепочка = пакет.files.getValue("03-цепочка-задачи.md")
        assertTrue("## 6 · Трасса знания" in цепочка, цепочка.take(300))
        assertTrue("| стейкхолдер (`stakeholder`) | 1 | есть |" in цепочка.lowercase().replace("сторона", "стейкхолдер") || "(`stakeholder`) | 1 | есть |" in цепочка, цепочка)
        assertTrue("(`material`) | 0 | пусто — здесь нужны предложения |" in цепочка, цепочка)

        val вопросы = пакет.files.getValue("04-вопросы.md")
        assertTrue("какие стороны здесь НЕ из наших" in вопросы)
        val права = пакет.files.getValue("05-права-и-формат.md")
        assertTrue("\"picture_fingerprint\"" in права && "\"proposals\"" in права && "POST /v2/reconcile" in права, "единый пакет ответа описан")
        assertTrue("Срез: reading · области 1, 2 · цепочки 6" in права)
    }

    @Test
    fun `пакет обратно проходит сверку по отпечатку, устаревший — предупреждение, срезы — списком`() {
        val пакет = обмен.picture(проект, "applicability")
        assertTrue(обмен.verifyPicture(проект, "applicability", пакет.fingerprint).ok)
        val устарел = обмен.verifyPicture(проект, "applicability", "0000000000000000")
        assertFalse(устарел.ok); assertTrue("устарел" in устарел.warning!!)
        assertFalse(обмен.verifyPicture(проект, "reading", пакет.fingerprint).ok, "отпечаток среза другой задачи не подходит")
        // Картина меняется вместе с проектом: новая сторона — новый отпечаток.
        store.create("SK-0002", "stakeholder", область, "3", mapper.createObjectNode().put("name", "Росморпорт").put("role", "operator"), провенанс, status = "accepted")
        assertFalse(обмен.verifyPicture(проект, "applicability", пакет.fingerprint).ok)

        val ответ = маршруты.handle("GET", "/v2/export/picture", п + ("task" to "requirements"), null)!!
        assertEquals(200, ответ.code)
        assertEquals(5, ответ.body.path("files").size())
        assertTrue("## Область 5 · Требования" in ответ.body.path("files").path("01-срез-картины.md").asText())
        val zip = маршруты.handle("GET", "/v2/export/picture.zip", п + ("task" to "conops"), null)!!
        val имена = mutableListOf<String>()
        ZipInputStream(zip.binary!!.inputStream()).use { z -> generateSequence { z.nextEntry }.forEach { имена += it.name } }
        assertEquals(5, имена.size)
        val сверка = маршруты.handle("POST", "/v2/export/picture/verify", п, """{"task":"conops","fingerprint":"${zip.body.path("fingerprint").asText()}"}""")!!
        assertTrue(сверка.body.path("ok").asBoolean(), сверка.body.toString())
        val срезы = маршруты.handle("GET", "/v2/export/picture/tasks", emptyMap(), null)!!
        assertEquals(5, срезы.body.path("items").size())
        assertEquals("all", срезы.body.path("items")[4].path("key").asText())
    }
}
