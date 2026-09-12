// Маршруты верификации документа против поля знаний (шаг 18 плана).
//
// Роды находок и их тексты проверяет VerificationTest модуля документов —
// здесь проверяется то, за что отвечает роутер: отчёт заводится и приходит
// целиком, находка переносится в замечание обзора со сценой РАЗДЕЛА, кнопки
// «исправить» нет ни одной, а на проекте без поля знаний v2 дверь заперта
// словами и ни одной записи в проекте не появляется.
//
// Службы настоящие: подменять проверку подстановкой значило бы проверять
// подстановку. Подменена только полка шаблонов — она лежит файлами в репо.
package orbita.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.api.internal.VerifyRoutes
import orbita.documents.api.DocumentsFactory
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.LinkRegistry
import orbita.kernel.api.Provenance
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerifyRoutesTest {

    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links: LinkRegistry = KernelFactory.linkRegistry(TestDbV2.conn)
    private val полки = TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые")

    private val дом: java.io.File =
        java.nio.file.Files.createTempDirectory("orbita-baselines").toFile().also { it.deleteOnExit() }

    private val документы = DocumentsFactory.documents(
        store, links,
        template = { код ->
            val файл = полки.resolve("ШАБЛОН-" + код.uppercase() + ".json").toFile()
            if (файл.isFile) mapper.readTree(файл) else null
        },
        mapper = mapper,
        baselineRoot = дом,
    )
    private val проверка = DocumentsFactory.verification(store, links, документы, mapper)
    private val маршруты = VerifyRoutes(store, документы, проверка, mapper)

    private val сПолем = "PJ-9421"
    private val безПоля = "PJ-9422"
    private val отчётДок = "mcreport"

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        проект(сПолем, true)
        проект(безПоля, false)
    }

    private fun проект(код: String, поле: Boolean) {
        val документ = mapper.createObjectNode().put("name", "Проект $код")
        // Проект прохода ПМИ-5 поля не имеет ВОВСЕ — так он и заведён здесь.
        if (поле) документ.put("knowledge_v2", true)
        store.create(код, "project", Area.Project(код), "1", документ, Provenance(Channel.MANUAL, "тест"))
    }

    private fun п(проект: String) = mapOf("project" to проект)

    /** Документ с тезисом без опоры: находка «без основания» получается наверняка. */
    private fun сТезисом(проект: String): String {
        документы.ensure(проект, отчётДок, "Иванов И.")
        val раздел = документы.addStatement(
            проект, отчётДок, "§1",
            "Ожидаемый охват перевозок — 97 %.",
            emptyList(), "Иванов И.",
        )
        return раздел.elements.last { it.text != null }.code
    }

    private fun отчёты(проект: String) = store.list(Area.Project(проект), "verification_report")

    @Test
    fun `проверка заводит отчёт и не правит ни документа, ни поля`() {
        val тезис = сТезисом(сПолем)
        val до = документы.document(сПолем, отчётДок)

        val ответ = маршруты.handle("POST", "/v2/documents/$отчётДок/verify", п(сПолем), """{"author":"Иванов И."}""")!!
        assertEquals(201, ответ.code, ответ.body.toString())
        assertTrue(ответ.body.path("code").asText().startsWith("VR-"), ответ.body.toString())
        assertEquals(отчётДок, ответ.body.path("document").asText())
        assertTrue(ответ.body.path("open").asBoolean(), "новый отчёт открыт — его ещё не разбирали")

        val находка = ответ.body.path("items").single { it.path("issue").asText() == "no_basis" }
        assertEquals(тезис, находка.path("element").asText(), "находка ведёт в тезис, а не в документ вообще")
        assertEquals("без основания", находка.path("issue_word").asText(), "род назван словами")
        assertTrue(находка.path("n").asInt() >= 1, "у находки есть номер — им она переносится")
        assertTrue(находка.path("proposal").asText().isNotBlank(), "находка предлагает, а не молчит")

        // «Правок молча — 0»: версия документа не выросла ни на единицу.
        assertEquals(до.version, документы.document(сПолем, отчётДок).version)
    }

    @Test
    fun `отчёты документа приходят списком`() {
        сТезисом(сПолем)
        маршруты.handle("POST", "/v2/documents/$отчётДок/verify", п(сПолем), """{"author":"Иванов И."}""")
        маршруты.handle("POST", "/v2/documents/$отчётДок/verify", п(сПолем), """{"author":"Иванов И."}""")

        val ответ = маршруты.handle("GET", "/v2/documents/$отчётДок/verification", п(сПолем), null)!!
        assertEquals(200, ответ.code)
        assertEquals(отчётДок, ответ.body.path("document").asText())
        assertEquals(2, ответ.body.path("items").size(), "каждая проверка заводит свой отчёт, а не правит прежний")

        val код = ответ.body.path("items").first().path("code").asText()
        val один = маршруты.handle("GET", "/v2/verification/$код", п(сПолем), null)!!
        assertEquals(200, один.code)
        assertEquals(код, один.body.path("code").asText())
    }

    @Test
    fun `находка переносится в замечание обзора со сценой раздела`() {
        val тезис = сТезисом(сПолем)
        val отчёт = маршруты.handle(
            "POST", "/v2/documents/$отчётДок/verify", п(сПолем), """{"author":"Иванов И."}""",
        )!!.body
        val код = отчёт.path("code").asText()
        val номер = отчёт.path("items").single { it.path("issue").asText() == "no_basis" }.path("n").asInt()
        val сцена = документы.document(сПолем, отчётДок).sections
            .single { раздел -> раздел.elements.any { it.code == тезис } }
            .scenes.first()

        val ответ = маршруты.handle(
            "POST", "/v2/verification/$код/items/$номер/finding", п(сПолем), """{"author":"Иванов И."}""",
        )!!
        assertEquals(201, ответ.code, ответ.body.toString())
        assertEquals(сцена, ответ.body.path("scene").asText(), "замечание возвращает в сцену РАЗДЕЛА")
        assertEquals(код, ответ.body.path("verification").asText(), "видно, из какого отчёта замечание")
        assertTrue("без основания" in ответ.body.path("text").asText(), ответ.body.path("text").asText())
        assertTrue(тезис in ответ.body.path("text").asText(), "замечание называет место в документе")
        assertEquals(1, store.list(Area.Project(сПолем), "finding").size)
    }

    @Test
    fun `сводка базирования собирается для проекта с полем знаний`() {
        сТезисом(сПолем)
        val линия = документы.baseline(сПолем, отчётДок, "внутренний обзор", "Иванов И.")
        val сводка = маршруты.послеБазирования(сПолем, отчётДок, "Иванов И.", линия.name)!!
        assertEquals("внутренний обзор", сводка.path("baseline_name").asText())
        assertTrue(сводка.path("summary").asText().isNotBlank(), "сводка говорит словами, а не числом")
        assertEquals(1, отчёты(сПолем).size, "автоотчёт — один на базирование")
    }

    // --- отказы ------------------------------------------------------------

    @Test
    fun `на проекте без поля знаний дверь заперта словами`() {
        документы.ensure(безПоля, отчётДок, "Иванов И.")
        val ответ = маршруты.handle(
            "POST", "/v2/documents/$отчётДок/verify", п(безПоля), """{"author":"Иванов И."}""",
        )!!
        assertEquals(409, ответ.code)
        assertTrue("выключено" in ответ.body.path("error").asText(), ответ.body.toString())
        assertTrue("POST /v2/projects" in ответ.body.path("what_to_do").asText(), ответ.body.toString())
        assertTrue(отчёты(безПоля).isEmpty(), "на проекте прохода не завелось ни одной записи")
        // Автоотчёт после базирования там тоже не появляется: базирование
        // идёт в точности как прежде.
        assertNull(маршруты.послеБазирования(безПоля, отчётДок, "Иванов И."))
        assertTrue(отчёты(безПоля).isEmpty())
    }

    @Test
    fun `отчёт закрывается только с причиной`() {
        сТезисом(сПолем)
        val код = маршруты.handle(
            "POST", "/v2/documents/$отчётДок/verify", п(сПолем), """{"author":"Иванов И."}""",
        )!!.body.path("code").asText()

        val отказ = assertFailsWith<IllegalArgumentException> {
            маршруты.handle("POST", "/v2/verification/$код/close", п(сПолем), """{"author":"Иванов И."}""")
        }
        assertTrue("причин" in отказ.message!!, отказ.message!!)
        val после = маршруты.handle("GET", "/v2/verification/$код", п(сПолем), null)!!
        assertTrue(после.body.path("open").asBoolean(), "отказ не закрыл отчёт наполовину")

        val закрыт = маршруты.handle(
            "POST", "/v2/verification/$код/close", п(сПолем),
            """{"author":"Иванов И.","reason":"находки разобраны на обзоре"}""",
        )!!
        assertEquals(200, закрыт.code)
        assertTrue(!закрыт.body.path("open").asBoolean())
        assertTrue(закрыт.body.path("items").size() > 0, "закрытие не стирает след расхождения")
    }

    @Test
    fun `находки с таким номером в отчёте нет`() {
        сТезисом(сПолем)
        val код = маршруты.handle(
            "POST", "/v2/documents/$отчётДок/verify", п(сПолем), """{"author":"Иванов И."}""",
        )!!.body.path("code").asText()

        assertFailsWith<NoSuchElementException> {
            маршруты.handle("POST", "/v2/verification/$код/items/99/finding", п(сПолем), """{"author":"Иванов И."}""")
        }
        // Замечание заводит человек: без автора его некому предъявить.
        assertFailsWith<IllegalArgumentException> {
            маршруты.handle("POST", "/v2/verification/$код/items/1/finding", п(сПолем), "{}")
        }
        assertTrue(store.list(Area.Project(сПолем), "finding").isEmpty(), "отказ не завёл замечания")
    }

    @Test
    fun `проверять нечего — документ без тезисов говорит это словами`() {
        документы.ensure(сПолем, отчётДок, "Иванов И.")
        val ответ = маршруты.handle(
            "POST", "/v2/documents/$отчётДок/verify", п(сПолем), """{"author":"Иванов И."}""",
        )!!
        assertEquals(0, ответ.body.path("items").size(), "пустых находок не выдумано")
        assertTrue("проверять нечего" in ответ.body.path("summary").asText(), ответ.body.path("summary").asText())
    }

    @Test
    fun `кнопки «исправить» у верификации нет`() {
        сТезисом(сПолем)
        val код = маршруты.handle(
            "POST", "/v2/documents/$отчётДок/verify", п(сПолем), """{"author":"Иванов И."}""",
        )!!.body.path("code").asText()
        // Ни «исправить», ни «исправить всё»: адреса не существует, и
        // отсутствие кнопки на экране — следствие, а не договорённость.
        assertNull(маршруты.handle("POST", "/v2/verification/$код/fix", п(сПолем), """{"author":"Иванов И."}"""))
        assertNull(маршруты.handle("POST", "/v2/verification/$код/fix-all", п(сПолем), "{}"))
    }

    @Test
    fun `чужой путь маршрут не перехватывает`() {
        assertNull(маршруты.handle("GET", "/v2/documents/$отчётДок", emptyMap(), null))
        assertNull(маршруты.handle("POST", "/v2/documents/$отчётДок/baseline", emptyMap(), null))
        assertNull(маршруты.handle("GET", "/v2/research", emptyMap(), null))
    }

    @Test
    fun `без кода проекта маршрут отказывает словами`() {
        val отказ = assertFailsWith<IllegalArgumentException> {
            маршруты.handle("POST", "/v2/documents/$отчётДок/verify", emptyMap(), """{"author":"Иванов И."}""")
        }
        assertTrue("project" in отказ.message!!, отказ.message!!)
    }
}
