// Маршруты исследования полноты: дверь наружу и ворота флага (шаг 18 плана).
//
// Правила контура (пять классов, сцены входа, ранг результата, подтверждение
// источников) проверяет ResearchTaskTest модуля знаний — здесь проверяется
// ровно то, за что отвечает роутер: место входа приходит одним из двух,
// промпт уходит ФАЙЛОМ, автор не подставляется, отдельного разбора нет, а на
// проекте без поля знаний v2 дверь заперта словами.
//
// Порт подменён нарочно: настоящее исследование ничего не знает о HTTP, и
// подстановка показывает, что именно роутер передал вниз — и чего не выдумал.
package orbita.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.api.internal.ResearchRoutes
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.Provenance
import orbita.knowledge.api.AcceptedSummary
import orbita.knowledge.api.Research
import orbita.knowledge.api.ResearchTask
import orbita.knowledge.api.ResearchTrigger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResearchRoutesTest {

    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val исследование = ФальшивоеИсследование()
    private val маршруты = ResearchRoutes(store, исследование, mapper)

    private val сПолем = "PJ-9401"
    private val безПоля = "PJ-9402"

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        проект(сПолем, true)
        проект(безПоля, false)
        исследование.сброс()
    }

    private fun проект(код: String, поле: Boolean) {
        val документ = mapper.createObjectNode().put("name", "Проект $код")
        // Проект прохода ПМИ-5 поля не имеет ВОВСЕ — так он и заведён здесь.
        if (поле) документ.put("knowledge_v2", true)
        store.create(код, "project", Area.Project(код), "1", документ, Provenance(Channel.MANUAL, "тест"))
    }

    private fun п(проект: String) = mapOf("project" to проект)

    @Test
    fun `исследование заводится от сцены и возвращает карточку цикла`() {
        val ответ = маршруты.handle(
            "POST", "/v2/research", п(сПолем),
            """{"scene":"3","author":"Иванов И.","questions":["сторона: кто ещё решает"]}""",
        )!!
        assertEquals(201, ответ.code, ответ.body.toString())
        assertEquals(ResearchTrigger.Scene("3"), исследование.место, "место входа ушло вниз как сцена")
        assertEquals(listOf("сторона: кто ещё решает"), исследование.вопросы, "вопрос инженера не потерян")
        assertEquals("сцена 3", ответ.body.path("place").asText(), "место названо словами")
        assertEquals("3", ответ.body.path("trigger").path("scene").asText())
        assertEquals(2, ответ.body.path("questions").size())
        assertEquals(2, ответ.body.path("accepted").path("stakeholders").asInt())
        // Классы без принятого — ими идёт следующий цикл, и на экран они
        // выходят словами, а не кодами.
        assertTrue(
            ответ.body.path("open").any { it.asText() == "норма" },
            "остатки названы словами: ${ответ.body.path("open")}",
        )
    }

    @Test
    fun `промпт уходит файлом markdown с русским именем`() {
        // Адреса «/prompt» без расширения нет: промпт — файл, и адрес
        // обязан выглядеть файлом, иначе браузер покажет его строкой.
        assertNull(маршруты.handle("GET", "/v2/research/RT-0001/prompt", п(сПолем), null))
        val ответ = маршруты.handle("GET", "/v2/research/RT-0001/prompt.md", п(сПолем), null)!!
        assertEquals(200, ответ.code)
        assertEquals("ИССЛЕДОВАНИЕ-RT-0001.md", ответ.fileName, "имя файла — человеку, а не машине")
        assertTrue(ответ.contentType!!.startsWith("text/markdown"), ответ.contentType!!)
        assertEquals("## Контекст проекта", ответ.binary!!.decodeToString().lineSequence().first())
        // Адрес промпта не прочитан как код задачи: карточку никто не просил.
        assertEquals(0, исследование.карточек, "промпт не идёт через чтение задачи")
    }

    @Test
    fun `подтверждение источников передаёт названные факты как есть`() {
        val ответ = маршруты.handle(
            "POST", "/v2/research/RT-0001/sources", п(сПолем),
            """{"facts":["F-0001","","F-0002"],"author":"Иванов И."}""",
        )!!
        assertEquals(200, ответ.code)
        assertEquals(listOf("F-0001", "F-0002"), исследование.факты, "пустая строка кодом факта не считается")
        assertEquals("Иванов И.", исследование.автор)
    }

    // --- отказы ------------------------------------------------------------

    @Test
    fun `на проекте без поля знаний дверь заперта словами`() {
        listOf(
            Triple("POST", "/v2/research", """{"scene":"3","author":"Иванов И."}"""),
            Triple("GET", "/v2/research", null),
            Triple("GET", "/v2/research/RT-0001/prompt.md", null),
        ).forEach { (метод, путь, тело) ->
            val ответ = маршруты.handle(метод, путь, п(безПоля), тело)!!
            assertEquals(409, ответ.code, "$метод $путь")
            assertTrue("выключено" in ответ.body.path("error").asText(), ответ.body.toString())
            assertTrue(
                "POST /v2/projects" in ответ.body.path("what_to_do").asText(),
                "отказ обязан сказать, что делать: ${ответ.body}",
            )
        }
        assertEquals(0, исследование.обращений, "на проекте прохода порт не тронут ни разу")
    }

    @Test
    fun `место входа — ровно одно из двух`() {
        val оба = assertFailsWith<IllegalArgumentException> {
            маршруты.handle("POST", "/v2/research", п(сПолем), """{"scene":"3","gate":"MCR","author":"И"}""")
        }
        assertTrue("оставьте одно" in оба.message!!, оба.message!!)

        val ни = assertFailsWith<IllegalArgumentException> {
            маршруты.handle("POST", "/v2/research", п(сПолем), """{"author":"Иванов И."}""")
        }
        assertTrue("место входа не названо" in ни.message!!, ни.message!!)
        assertEquals(0, исследование.обращений, "разобранный кое-как запрос вниз не уходит")
    }

    @Test
    fun `автора маршрут не выдумывает`() {
        маршруты.handle("POST", "/v2/research/RT-0001/launch", п(сПолем), """{"note":"ушло в Deep Research"}""")
        // Умолчания «инженер» здесь нет намеренно: наружу ходит названный
        // человек, и отказать за него обязан порт, а не роутер молча подставить.
        assertEquals("", исследование.автор, "пустой автор ушёл вниз как пустой")
    }

    @Test
    fun `отдельного разбора у исследования нет`() {
        исследование.карточка = образец(status = "received", material = "MT-0007")
        val ответ = маршруты.handle("POST", "/v2/research/RT-0001/parse", п(сПолем), "{}")!!
        assertEquals(409, ответ.code)
        val чтоДелать = ответ.body.path("what_to_do").asText()
        assertTrue("/v2/intake/atomize" in чтоДелать, чтоДелать)
        assertTrue("MT-0007" in чтоДелать, "отказ называет материал результата: $чтоДелать")

        исследование.карточка = образец(status = "launched", material = null)
        val безРезультата = маршруты.handle("POST", "/v2/research/RT-0001/parse", п(сПолем), "{}")!!
        assertTrue(
            "/v2/research/RT-0001/result" in безРезультата.body.path("what_to_do").asText(),
            безРезультата.body.toString(),
        )
    }

    @Test
    fun `занятое место — конфликт, а не поломка стенда`() {
        исследование.бросить = IllegalStateException("по сцене 3 исследование уже заведено (RT-0001)")
        val ответ = маршруты.handle("POST", "/v2/research", п(сПолем), """{"scene":"3","author":"Иванов И."}""")!!
        assertEquals(409, ответ.code, "состояние цикла — 409, а не 500")
        assertTrue("уже заведено" in ответ.body.path("error").asText(), ответ.body.toString())
    }

    @Test
    fun `без кода проекта маршрут отказывает словами`() {
        val отказ = assertFailsWith<IllegalArgumentException> {
            маршруты.handle("GET", "/v2/research", emptyMap(), null)
        }
        assertTrue("project" in отказ.message!!, отказ.message!!)
    }

    @Test
    fun `чужой путь маршрут не перехватывает`() {
        assertNull(маршруты.handle("GET", "/v2/documents", emptyMap(), null))
        assertNull(маршруты.handle("GET", "/v2/topics", emptyMap(), null))
    }

    // --- подстановка порта -------------------------------------------------

    private fun образец(
        status: String = "formulated",
        material: String? = null,
    ): ResearchTask = ResearchTask(
        id = "RT-0001",
        trigger = ResearchTrigger.Scene("3"),
        questions = listOf("сторона: кто ещё решает", "норма: чем регулируется"),
        sliceFingerprint = "0f1e2d3c",
        promptVersion = "research/v1+ab12cd",
        iteration = 1,
        status = status,
        resultMaterial = material,
        accepted = AcceptedSummary(stakeholders = 2),
        note = "вопросов 2; вызова модели в контуре исследования нет",
    )

    private inner class ФальшивоеИсследование : Research {

        var карточка: ResearchTask = образец()
        var место: ResearchTrigger? = null
        var вопросы: List<String> = emptyList()
        var факты: List<String> = emptyList()
        var автор: String? = null
        var обращений: Int = 0
        var карточек: Int = 0
        var бросить: RuntimeException? = null

        fun сброс() {
            карточка = образец()
            место = null
            вопросы = emptyList()
            факты = emptyList()
            автор = null
            обращений = 0
            карточек = 0
            бросить = null
        }

        private fun отметить(): ResearchTask {
            обращений += 1
            бросить?.let { throw it }
            return карточка
        }

        override fun formulate(
            project: String,
            trigger: ResearchTrigger,
            author: String,
            questions: List<String>,
        ): ResearchTask {
            место = trigger
            вопросы = questions
            автор = author
            return отметить()
        }

        override fun task(project: String, task: String): ResearchTask {
            карточек += 1
            return отметить()
        }

        override fun list(project: String): List<ResearchTask> = listOf(отметить())

        override fun prompt(project: String, task: String): String {
            обращений += 1
            return "## Контекст проекта\n\n## Вопросы исследования\n\n## Требования к ответу\n"
        }

        override fun launch(project: String, task: String, author: String, note: String): ResearchTask {
            автор = author
            return отметить()
        }

        override fun result(
            project: String,
            task: String,
            name: String,
            text: String,
            author: String,
        ): ResearchTask {
            автор = author
            return отметить()
        }

        override fun confirmSources(
            project: String,
            task: String,
            facts: List<String>,
            author: String,
        ): ResearchTask {
            this.факты = facts
            автор = author
            return отметить()
        }

        override fun next(
            project: String,
            task: String,
            author: String,
            questions: List<String>,
        ): ResearchTask {
            вопросы = questions
            автор = author
            return отметить()
        }
    }
}
