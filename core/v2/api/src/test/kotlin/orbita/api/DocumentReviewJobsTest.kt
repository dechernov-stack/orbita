// Документы живьём (шип 4 §4): связный текст и печать фоновыми заданиями,
// рецензия патчами с «принять как есть», печать выбранным движком.
//
// Модель подменена транспортом: проверяется поведение СИСТЕМЫ на её ответ.
// Сторож чисел стережёт и правку человека: число без опоры — отказ, как у
// модели. Typst компилируется, когда двоичный файл есть; иначе отказ словами.
package orbita.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.ai.api.AiFactory
import orbita.ai.api.Answer
import orbita.ai.api.Transport
import orbita.api.internal.DocRoutes
import orbita.api.internal.DocumentJobs
import orbita.api.internal.V2Router
import orbita.documents.api.DocumentsFactory
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.KernelFactory
import orbita.library.api.LibraryFactory
import orbita.process.api.ProcessFactory
import orbita.readiness.api.ReadinessFactory
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DocumentReviewJobsTest {

    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val полки = TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые")
    private val шаблонФазы = mapper.readTree(полки.resolve("ШАБЛОН-ФАЗЫ-PRE-A-NASA.json").toFile())
    private val дом: File = java.nio.file.Files.createTempDirectory("orbita-baselines").toFile().also { it.deleteOnExit() }

    /** Ответ подменённой модели: тест ставит его перед вызовом. */
    private var ответМодели = "Замысел обращён к перевозчикам. Стороны миссии — Сторона 1, Сторона 2 и Сторона 3."
    private var вызовов = 0
    private val служба = AiFactory.service(store, Transport { _, _, _, _ -> вызовов += 1; Answer(ответМодели, "модель-фона", 0, 0) }, mapper)

    private val документы = DocumentsFactory.documents(
        store, links,
        template = { код -> полки.resolve("ШАБЛОН-" + код.uppercase() + ".json").toFile().takeIf { it.isFile }?.let { mapper.readTree(it) } },
        mapper = mapper, baselineRoot = дом,
        writer = { проект, промпт -> служба.ask(проект, "document_render", промпт).let { it.text to it.model } },
    )
    private val задания = DocumentJobs(документы, служба)

    private val router: V2Router by lazy {
        val движок = ProcessFactory.engine(
            template = { шаблонФазы },
            evaluator = ReadinessFactory.gateEvaluator(store, links, scenesDone = { emptySet() }, gatesPassed = { emptySet() }),
            passedGates = { mutableSetOf() }, gatePlan = { emptyMap() },
        )
        V2Router(
            store, links, движок, LibraryFactory.shelves(store) { шаблонФазы },
            orbita.knowledge.api.KnowledgeFactory.intake(store, links, mapper),
            orbita.formulation.api.FormulationFactory.formulation(store, links), mapper,
            docRoutes = DocRoutes(store, документы, mapper, jobs = задания) { шаблонФазы },
        )
    }

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        вызовов = 0
    }

    private fun постановка(проект: String): Map<String, String> {
        val п = mapOf("project" to проект)
        router.handle("POST", "/v2/projects", emptyMap(), """{"name":"Документы живьём","code":"$проект"}""")
        router.handle("POST", "/v2/intent", п, """{"for_whom":"перевозчики","what":"телеметрия груза","where":"СМП","horizon":"2033","accepted":true}""")
        (1..3).forEach { n -> router.handle("POST", "/v2/stakeholders", п, """{"name":"Сторона $n","role":"customer"}""") }
        router.handle("GET", "/v2/documents", п, null)
        return п
    }

    private fun дождаться(п: Map<String, String>, job: String): com.fasterxml.jackson.databind.JsonNode {
        var з = router.handle("GET", "/v2/documents/mcreport/jobs/$job", п, null)!!.body
        var попыток = 0
        while (з.path("status").asText() == "running" && попыток < 100) { Thread.sleep(50); з = router.handle("GET", "/v2/documents/mcreport/jobs/$job", п, null)!!.body; попыток += 1 }
        return з
    }

    @Test
    fun `связный текст фоновым заданием - сеть в фоне, ответ применяется при опросе, повтор из журнала`() {
        val п = постановка("PJ-9840")
        val старт = router.handle("POST", "/v2/documents/mcreport/write", п, """{"section":"§1","author":"Иванов И.","background":true}""")!!
        assertEquals(202, старт.code, старт.body.toString())
        val job = старт.body.path("job").asText()
        assertTrue(job.startsWith("DJ-"))
        val готово = дождаться(п, job)
        assertEquals("done", готово.path("status").asText(), готово.toString())
        assertTrue(готово.path("result").path("accepted").asBoolean(), готово.toString())
        assertEquals("модель-фона", готово.path("result").path("model").asText())
        assertEquals(1, вызовов, "один вызов сети")
        assertEquals(1, store.list(Area.Project("PJ-9840"), "ai_call").size, "вызов записан в журнал на потоке запросов")
        assertEquals(1, router.handle("GET", "/v2/documents/mcreport/renderings", п, null)!!.body.path("items").size())

        // Тот же раздел снова — ответ из журнала: задание готово сразу, второго вызова нет.
        val повтор = router.handle("POST", "/v2/documents/mcreport/write", п, """{"section":"§1","author":"Иванов И.","background":true}""")!!
        assertEquals(201, повтор.code, повтор.body.toString())
        assertEquals("done", повтор.body.path("status").asText())
        assertEquals(1, вызовов, "второго вызова нет")
        assertEquals(null, задания.poll("PJ-чужой", job), "задание чужого проекта не видно")
    }

    @Test
    fun `рецензия патчами - число без опоры отказ, правка изложения хранится патчем, принять как есть`() {
        val п = постановка("PJ-9841")
        router.handle("POST", "/v2/documents/mcreport/write", п, """{"section":"§1","author":"Иванов И."}""")
        val было = router.handle("GET", "/v2/documents/mcreport/renderings", п, null)!!.body.path("items")[0]
        assertEquals("draft", было.path("status").asText())
        // Шип 5 §5: у текста раздела — кто и когда его написал.
        assertTrue(было.path("author").asText().isNotBlank(), было.toString())
        assertTrue(было.path("at").asText().startsWith("20"), было.toString())

        val отказ = router.handle("POST", "/v2/documents/mcreport/review", п,
            """{"section":"§1","author":"Петрова М.","text":"Замысел обращён к перевозчикам: охват 98 % территории. Стороны миссии — Сторона 1, Сторона 2 и Сторона 3."}""")!!
        assertEquals(422, отказ.code, "число без опоры в правке человека — тот же отказ, что у модели")
        assertTrue("98" in отказ.body.path("refusals").toString())
        assertEquals("draft", router.handle("GET", "/v2/documents/mcreport/renderings", п, null)!!.body.path("items")[0].path("status").asText(), "отклонённая правка не хранится")

        val правка = router.handle("POST", "/v2/documents/mcreport/review", п,
            """{"section":"§1","author":"Петрова М.","text":"Замысел обращён к перевозчикам грузов. Стороны миссии — Сторона 1, Сторона 2 и Сторона 3."}""")!!
        assertEquals(201, правка.code, правка.body.toString())
        assertEquals("reviewed", правка.body.path("status").asText())
        assertEquals("Петрова М.", правка.body.path("reviewer").asText())
        val патч = правка.body.path("patches").single()
        assertEquals("", патч.path("old").asText())
        assertEquals(" грузов", патч.path("new").asText())
        assertEquals(30, патч.path("from").asInt(), патч.toString())
        assertEquals("Петрова М.", патч.path("author").asText())
        assertTrue("грузов" in правка.body.path("text").asText())

        assertFailsWith<IllegalArgumentException> { router.handle("POST", "/v2/documents/mcreport/renderings/accept", п, """{"author":""}""") }
        val принято = router.handle("POST", "/v2/documents/mcreport/renderings/accept", п, """{"author":"Сидоров С.","section":"§1"}""")!!
        assertEquals(200, принято.code)
        val текст = принято.body.path("items").single()
        assertEquals("accepted", текст.path("status").asText())
        assertEquals("Сидоров С.", текст.path("reviewer").asText())
        assertTrue(текст.path("accepted_at").asText().startsWith("20"))
        assertEquals(1, текст.path("patches").size(), "патч остался в истории")

        // Печать берёт правленный текст, а не текст модели.
        val вид = документы.render("PJ-9841", "mcreport")
        assertEquals(listOf("Замысел обращён к перевозчикам грузов. Стороны миссии — Сторона 1, Сторона 2 и Сторона 3."), вид.sections.first { it.no == "§1" }.lines)

        // Новый текст модели — новый черновик: правки прежнего к нему не относятся.
        ответМодели = "Замысел обращён к перевозчикам. Стороны — Сторона 1, Сторона 2, Сторона 3."
        router.handle("POST", "/v2/documents/mcreport/write", п, """{"section":"§1","author":"Иванов И."}""")
        val заново = router.handle("GET", "/v2/documents/mcreport/renderings", п, null)!!.body.path("items")[0]
        assertEquals("draft", заново.path("status").asText())
    }

    @Test
    fun `печать движком - pdfbox всегда, typst когда есть, фоновое задание отдаёт файл`() {
        val п = постановка("PJ-9842")
        val pdfbox = router.handle("GET", "/v2/documents/mcreport/print", п + ("engine" to "pdfbox"), null)!!
        assertEquals("%PDF", pdfbox.binary!!.copyOfRange(0, 4).decodeToString())
        assertEquals("pdfbox", pdfbox.body.path("engine").asText())

        val typst = File(TestDbV2.repoRoot.toFile(), "build/tools/typst").canExecute() || System.getenv("ORBITA_TYPST") != null
        if (typst) {
            val ответ = router.handle("GET", "/v2/documents/mcreport/print", п + ("engine" to "typst"), null)!!
            assertEquals("typst", ответ.body.path("engine").asText())
            assertEquals("%PDF", ответ.binary!!.copyOfRange(0, 4).decodeToString())
            assertTrue(ответ.binary!!.size > 5000)
        } else {
            val е = assertFailsWith<IllegalStateException> { router.handle("GET", "/v2/documents/mcreport/print", п + ("engine" to "typst"), null) }
            assertTrue("Typst" in е.message!!, е.message)
            println("typst не найден: строгий режим отказал словами — компиляция пропущена")
        }
        assertFailsWith<IllegalArgumentException> { router.handle("GET", "/v2/documents/mcreport/print", п + ("engine" to "word"), null) }

        // Фоновая печать: вид собран сразу, байты — в фоне; файл забирается тем же /print с job=.
        val старт = router.handle("POST", "/v2/documents/mcreport/print/jobs", п, """{"engine":"auto"}""")!!
        assertEquals(202, старт.code)
        val job = старт.body.path("job").asText()
        val готово = дождаться(п, job)
        assertEquals("done", готово.path("status").asText(), готово.toString())
        assertTrue(готово.path("engine").asText() in setOf("typst", "pdfbox"), готово.toString())
        assertTrue(готово.path("size").asInt() > 1000)
        val файл = router.handle("GET", "/v2/documents/mcreport/print", п + ("job" to job), null)!!
        assertEquals("%PDF", файл.binary!!.copyOfRange(0, 4).decodeToString())
        assertEquals(готово.path("engine").asText(), файл.body.path("engine").asText())
    }
}
