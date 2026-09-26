// Документ как источник — целиком (шип 4 §3): маршруты досье, оснований,
// отката, приёма режимом, партии каталога и библиотеки проекта.
//
// Меры решения §8 на маршрутах: каталог из шести файлов — одна очередь и одна
// сводка; норматив взят в новый проект вместе с фактами, вызовов модели ноль
// (журнал ИИ не прирастает — службы ИИ у маршрутов нет вовсе); карта пробелов
// устава приходит ответом загрузки.
package orbita.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.ai.api.AtomizeJob
import orbita.ai.api.AtomizeJobs
import orbita.api.internal.AcrossRoutes
import orbita.api.internal.DossierRoutes
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
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DossierRoutesTest {
    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val intake = KnowledgeFactory.intake(store, links, mapper)
    private val проект = "PJ-9830"
    private val другой = "PJ-9831"
    private val область = Area.Project(проект)
    private val провенанс = Provenance(Channel.MANUAL, "Чернов Д.")
    private val q = mapOf("project" to проект)

    private val сквозные: AcrossRoutes by lazy {
        val шаблон = mapper.readTree(TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые/ШАБЛОН-ФАЗЫ-PRE-A-NASA.json").toFile())
        val движок = ProcessFactory.engine(
            template = { шаблон },
            evaluator = ReadinessFactory.gateEvaluator(store, links, scenesDone = { emptySet() }, gatesPassed = { emptySet() }),
            passedGates = { mutableSetOf() }, gatePlan = { emptyMap() },
        )
        AcrossRoutes(store, links, движок, LibraryFactory.shelves(store) { шаблон }, intake, FormulationFactory.formulation(store, links), mapper)
    }

    /** Очередь разбора без сети: «ответ модели» — один факт по первому якорю документа, применяется сразу. */
    private val очередь = object : AtomizeJobs {
        private val задания = mutableListOf<AtomizeJob>()
        override fun start(project: String, material: String, intent: String, author: String): AtomizeJob {
            val якорь = intake.canon(project, material).first { it.kind != "heading" }.anchor
            val итог = intake.putFacts(
                project, material,
                """{"summary":"документ о связи","facts":[{"kind":"quantity","subject":"платформа","predicate":"масса","value":"${80 + задания.size * 10}","unit":"кг","quote":"масса","source":{"anchor":"$якорь"},"mark":"И"}]}""",
                author, promptVersion = "тест-v1",
            )
            val з = AtomizeJob("JOB-${задания.size + 1}", project, material, "done", "2026-09-24T00:00:00Z", 0, итог.task, итог.note, итог.accepted.size, итог.refused.size, итог.refused)
            задания += з
            return з
        }
        override fun poll(project: String, id: String): AtomizeJob? = задания.firstOrNull { it.id == id && it.project == project }
        override fun list(project: String): List<AtomizeJob> = задания.filter { it.project == project }
    }

    private val досье: DossierRoutes by lazy {
        DossierRoutes(store, intake, mapper, jobs = очередь) { проект, тело ->
            сквозные.handle("POST", "/v2/materials", mapOf("project" to проект), тело)!!
        }
    }

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        store.create(проект, "project", область, "1", mapper.readTree("""{"name":"Досье","standard":"NASA-7120","phase_current":"Pre-Phase A"}"""), провенанс)
        store.create(другой, "project", Area.Project(другой), "1", mapper.readTree("""{"name":"Второй","standard":"NASA-7120","phase_current":"Pre-Phase A"}"""), провенанс)
    }

    private fun документ(имя: String, текст: String, роль: String = "context", ранг: String = "reference"): String =
        """{"name":"$имя","text":"${текст.replace("\n", "\\n")}","rank":"$ранг","role":"$роль","author":"Иванов И."}"""

    @Test
    fun `партия из шести файлов — одна очередь и одна сводка`() {
        val тела = (1..6).joinToString(",") { """{"name":"Файл $it","text":"# Документ $it\n\nПлатформа: масса ${80 + it} кг.","rank":"reference","role":"context"}""" }
        val ответ = досье.handle("POST", "/v2/materials/batch", q, """{"author":"Иванов И.","items":[$тела]}""")!!
        assertEquals(201, ответ.code, ответ.body.toString())
        assertEquals(6, ответ.body.path("documents").asInt())
        assertEquals(6, ответ.body.path("codes").size())
        assertTrue(ответ.body.path("items").all { it.path("job_status").asText() == "done" }, ответ.body.toString())
        val коды = ответ.body.path("codes").joinToString(",") { it.asText() }
        val сводка = досье.handle("GET", "/v2/materials/batch", q + ("codes" to коды), null)!!
        assertEquals(200, сводка.code)
        assertEquals(6, сводка.body.path("documents").asInt())
        assertEquals(6, сводка.body.path("facts").asInt())
        assertEquals(6, сводка.body.path("done").asInt())
        assertTrue(сводка.body.path("note").asText().startsWith("6 документов, 6 фактов"), сводка.body.path("note").asText())
        assertEquals(0, сводка.body.path("duplicate_sources").asInt(), "тексты разные — дублей нет")
        // Шесть одинаковых утверждений с разными величинами из разных документов — противоречия названы.
        assertTrue(сводка.body.path("contradicts").asInt() >= 6, сводка.body.toString())
        assertTrue(сводка.body.path("items").all { it.path("summary").asText() == "документ о связи" })

        // Тот же текст вторым файлом — дубль источника в сводке.
        val дубль = досье.handle("POST", "/v2/materials/batch", q, """{"items":[{"name":"Копия","text":"# Документ 1\n\nПлатформа: масса 81 кг.","rank":"reference","role":"context"}]}""")!!
        val кодДубля = дубль.body.path("codes")[0].asText()
        val сводка2 = досье.handle("GET", "/v2/materials/batch", q + ("codes" to "$коды,$кодДубля"), null)!!
        assertEquals(2, сводка2.body.path("duplicate_sources").asInt(), "оба одинаковых документа названы дублями")
    }

    @Test
    fun `досье, основания, полезность и откат — маршрутами`() {
        val положен = сквозные.handle("POST", "/v2/materials", q, документ("Исследование", "# Обстановка\n\nРосморпорт развивает порты."))!!
        val код = положен.body.path("code").asText()
        val якорь = intake.canon(проект, код).first { it.kind != "heading" }.anchor
        val итог = intake.putFacts(
            проект, код,
            """{"summary":"об обстановке портов","facts":[{"kind":"capability","subject":"Росморпорт","predicate":"развивает","value":"порты","quote":"развивает порты","source":{"anchor":"$якорь"},"mark":"И"}],
               "actions":[{"kind":"create_entity","target_kind":"stakeholder","scene":"3","title":"завести сторону","preview":"","payload":{"name":"Росморпорт","role":"operator","interest":"порты"},"facts":[0]}]}""",
            "Иванов И.", promptVersion = "v1",
        )
        val принят = досье.handle("POST", "/v2/materials/$код/accept", q, """{"mode":"whole","author":"Иванов И."}""")!!
        assertEquals(201, принят.code, принят.body.toString())
        assertEquals(1, принят.body.path("created").size())
        val сторона = принят.body.path("created")[0].asText()

        val д = досье.handle("GET", "/v2/materials/$код/dossier", q, null)!!
        assertEquals(200, д.code)
        assertEquals("об обстановке портов", д.body.path("summary").asText())
        assertEquals("whole", д.body.path("accept_mode").asText())
        assertEquals(1, д.body.path("facts_by_kind").path("capability").asInt())
        assertEquals(1, д.body.path("by_disposition").path("adopted").asInt())
        assertEquals(1, д.body.path("runs").size())
        assertEquals("v1", д.body.path("runs")[0].path("prompt_version").asText())
        assertEquals(сторона, д.body.path("entities")[0].path("code").asText())
        assertTrue(д.body.path("entities")[0].path("only_basis").asBoolean())
        assertEquals(1, д.body.path("entities_only_basis").asInt())

        val оценка = досье.handle("POST", "/v2/materials/$код/usefulness", q, """{"note":"полезно для сцены 3","author":"Иванов И."}""")!!
        assertEquals("полезно для сцены 3", оценка.body.path("usefulness_note").asText())

        val основания = досье.handle("GET", "/v2/entities/$сторона/basis", q, null)!!
        assertEquals(200, основания.code)
        assertEquals(1, основания.body.path("facts").size())
        assertEquals("context", основания.body.path("facts")[0].path("role").asText())
        assertEquals(якорь, основания.body.path("facts")[0].path("anchor").asText())

        assertFailsWith<IllegalArgumentException> { досье.handle("POST", "/v2/materials/$код/rollback", q, """{"author":"Иванов И."}""") }
        val откат = досье.handle("POST", "/v2/materials/$код/rollback", q, """{"author":"Иванов И.","reason":"не о нашей миссии"}""")!!
        assertEquals(200, откат.code)
        assertEquals(1, откат.body.path("facts").asInt())
        assertEquals(сторона, откат.body.path("entities_cancelled")[0].asText())
        assertEquals("cancelled", store.byCode(область, сторона)!!.status)
        assertTrue(store.list(область, "fact").all { it.status == "cancelled" })
        assertEquals("rolled_back", досье.handle("GET", "/v2/materials/$код/dossier", q, null)!!.body.path("runs")[0].path("status").asText())
        assertTrue(итог.task != null)
    }

    @Test
    fun `норматив из библиотеки проекта берётся в новый проект с фактами — вызовов модели ноль`() {
        val положен = сквозные.handle("POST", "/v2/materials", q, документ("ПП РФ 2216", "# Нормы\n\nОператор обязан хранить данные 3 года.", роль = "regulatory", ранг = "mandatory"))!!
        val код = положен.body.path("code").asText()
        val якорь = intake.canon(проект, код).first { it.kind != "heading" }.anchor
        intake.putFacts(проект, код, """{"facts":[{"kind":"obligation","subject":"оператор","predicate":"обязан хранить данные","value":"3","unit":"года","quote":"хранить данные 3 года","source":{"anchor":"$якорь"},"mark":"И"}]}""", "Иванов И.", promptVersion = "v1")

        val взятие = досье.handle("POST", "/v2/materials/take", mapOf("project" to другой), """{"from_project":"$проект","material":"$код","author":"Иванов И."}""")!!
        assertEquals(201, взятие.code, взятие.body.toString())
        assertEquals(0, взятие.body.path("model_calls").asInt())
        assertEquals(1, взятие.body.path("facts").asInt())
        val новый = взятие.body.path("material").asText()
        assertEquals(1, intake.facts(другой).size)
        assertEquals(новый, intake.facts(другой).single().material)
        // Шип 5 §3.2: перечень фактов несёт цитату блока — «откуда» раскрывает её на месте.
        val перечень = сквозные.handle("GET", "/v2/facts", mapOf("project" to другой), null)!!
        assertEquals("хранить данные 3 года", перечень.body.path("items")[0].path("quote").asText())
        assertTrue(store.list(Area.Project(другой), "ai_call").isEmpty(), "журнал ИИ пуст: модель не звалась")
        assertTrue(store.list(область, "ai_call").isEmpty())
        val список = сквозные.handle("GET", "/v2/materials", mapOf("project" to другой), null)!!
        assertEquals(1, список.body.path("items").size())
        assertEquals("regulatory", список.body.path("items")[0].path("role").asText())
    }

    @Test
    fun `устав при загрузке получает карту пробелов, второй устав — отказ с именем первого`() {
        val записка = TestDbV2.repoRoot.resolve("docs/tz/v2/поставка-09-15/ЗАПИСКА-МИССИИ-IoT.md").toFile().readText()
        val положен = сквозные.handle("POST", "/v2/materials", q, mapper.writeValueAsString(
            mapper.createObjectNode().put("name", "Записка миссии").put("text", записка).put("rank", "mandatory").put("role", "charter").put("author", "Иванов И."),
        ))!!
        assertEquals(201, положен.code, положен.body.toString())
        assertTrue(положен.body.path("gaps").path("present").asInt() >= 10, положен.body.path("gaps").toString())
        val код = положен.body.path("code").asText()
        val карта = досье.handle("GET", "/v2/materials/$код/gaps", q, null)!!
        assertEquals(12, карта.body.path("items").size())
        val отказ = assertFailsWith<orbita.kernel.api.ConflictException> {
            сквозные.handle("POST", "/v2/materials", q, документ("Вторая записка", "# Иное\n\nТекст.", роль = "charter", ранг = "mandatory"))
        }
        assertTrue("Записка миссии" in отказ.message!!, отказ.message)
        // Роль лежащего документа — своим маршрутом с тем же правилом; у устава — карта пробелов.
        val второй = сквозные.handle("POST", "/v2/materials", q, документ("Аналитика", "# Обстановка\n\nТекст.", роль = "context"))!!.body.path("code").asText()
        val отказРоли = assertFailsWith<orbita.kernel.api.ConflictException> {
            досье.handle("POST", "/v2/materials/$второй/role", q, """{"role":"charter","author":"Иванов И."}""")
        }
        assertTrue("Записка миссии" in отказРоли.message!!, отказРоли.message)
        val роль = досье.handle("POST", "/v2/materials/$второй/role", q, """{"role":"tor","author":"Иванов И."}""")!!
        assertEquals("tor", роль.body.path("role").asText())
        val рольУстава = досье.handle("POST", "/v2/materials/$код/role", q, """{"role":"charter","author":"Иванов И."}""")!!
        assertEquals(12, рольУстава.body.path("gaps").path("items").size(), "у устава ответ несёт карту пробелов")
    }
}
