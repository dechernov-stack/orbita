// Материал двоичным файлом (ПМИ-5, 12.09 «в поле знаний только md»): docx ·
// pdf · xlsx · pptx приходят base64, текст извлекает подставленный
// извлекатель; формат не читается — отказ словами, пустого материала нет;
// без извлекателя стенд честно говорит «вставьте текст».
package orbita.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.api.internal.AcrossRoutes
import orbita.formulation.api.FormulationFactory
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.Provenance
import orbita.knowledge.api.KnowledgeFactory
import orbita.library.api.LibraryFactory
import orbita.process.api.ProcessFactory
import orbita.readiness.api.ReadinessFactory
import java.util.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MaterialUploadTest {
    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val полки = TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые")
    private val проект = "PJ-9808"
    private val область = Area.Project(проект)
    private val провенанс = Provenance(Channel.MANUAL, "Чернов Д.")
    private val intake = KnowledgeFactory.intake(store, links, mapper)
    private val q = mapOf("project" to проект)

    private fun маршруты(extract: ((String, ByteArray) -> String?)?): AcrossRoutes {
        val шаблон = mapper.readTree(полки.resolve("ШАБЛОН-ФАЗЫ-PRE-A-NASA.json").toFile())
        val движок = ProcessFactory.engine(
            template = { шаблон },
            evaluator = ReadinessFactory.gateEvaluator(store, links, scenesDone = { emptySet() }, gatesPassed = { emptySet() }),
            passedGates = { mutableSetOf() }, gatePlan = { emptyMap() },
        )
        return AcrossRoutes(store, links, движок, LibraryFactory.shelves(store) { шаблон }, intake,
            FormulationFactory.formulation(store, links), mapper, extract = extract)
    }

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        store.create(проект, "project", область, "1", mapper.readTree("""{"name":"Файлы","standard":"NASA-7120","phase":"Pre-Phase A"}"""), провенанс)
    }

    private fun base64(текст: String) = Base64.getEncoder().encodeToString(текст.toByteArray())

    @Test
    fun `docx приходит base64, текст извлекает сервер, шапка называет источник`() {
        val извлекатель = { имя: String, байты: ByteArray -> if (имя.endsWith(".docx")) "п. 4.1 " + String(байты) else null }
        val ответ = маршруты(извлекатель).handle("POST", "/v2/materials", q,
            """{"kind":"tor","filename":"ТЗ.docx","file_base64":"${base64("Система обязана передавать телеметрию.")}","author":"Иванов И."}""")!!
        assertEquals(201, ответ.code, ответ.body.toString())
        assertEquals("ТЗ.docx", ответ.body.path("extracted_from").asText())
        assertTrue(ответ.body.path("chars").asInt() > 30)
        val код = ответ.body.path("code").asText()
        val блоки = intake.canon(проект, код)
        assertTrue(блоки.any { "телеметрию" in it.text }, блоки.map { it.text }.toString())
        assertEquals("ТЗ", store.byCode(область, код)!!.doc.path("name").asText(), "имя — из имени файла без расширения")
    }

    @Test
    fun `формат не читается или извлекателя нет — отказ словами, материала нет`() {
        val е = assertFailsWith<IllegalArgumentException> {
            маршруты { _, _ -> null }.handle("POST", "/v2/materials", q,
                """{"kind":"reference","filename":"скан.pdf","file_base64":"${base64("x")}","author":"Иванов И."}""")
        }
        assertTrue("не прочитался" in е.message!!, е.message)
        val е2 = assertFailsWith<IllegalArgumentException> {
            маршруты(null).handle("POST", "/v2/materials", q,
                """{"kind":"reference","filename":"ТЗ.docx","file_base64":"${base64("x")}","author":"Иванов И."}""")
        }
        assertTrue("вставьте текст" in е2.message!!, е2.message)
        assertTrue(store.list(область, "material").isEmpty(), "пустой материал не заведён")
        // текст по-прежнему принимается как раньше
        val ок = маршруты(null).handle("POST", "/v2/materials", q, """{"name":"Записка","kind":"note","text":"Текст записки.","author":"Иванов И."}""")!!
        assertEquals(201, ок.code)
    }
}
