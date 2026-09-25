// Применимость (шип 4 §5): «Приморье» → ≥ 4 применимости, из них ≥ 1 «не наш
// профиль» со ссылкой на границу устава; ни одной цели из обстановки.
//
// Чтение документа подменено транспортом: ответ модели собирается в тесте из
// строк самого исследования (цитаты дословно, якоря — из канона), поэтому
// ворота цитат стоят по-настоящему. Дальше всё считается по данным: акцепт
// предложений, матрица, границы устава, решение человека.
package orbita.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.ai.api.AiFactory
import orbita.ai.api.Answer
import orbita.ai.api.Transport
import orbita.api.internal.KnowledgeRoutes
import orbita.api.internal.SynthesisRoutes
import orbita.api.internal.V2Router
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.Provenance
import orbita.knowledge.api.Authority
import orbita.knowledge.api.KnowledgeFactory
import orbita.library.api.LibraryFactory
import orbita.process.api.ProcessFactory
import orbita.readiness.api.ReadinessFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ApplicabilityTest {

    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val проект = "PJ-9850"
    private val область = Area.Project(проект)
    private val п = mapOf("project" to проект)
    private val шаблон = mapper.readTree(TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые/ШАБЛОН-ФАЗЫ-PRE-A-NASA.json").toFile())
    private val полки = LibraryFactory.shelves(store) { шаблон }
    private val знания = KnowledgeFactory.intake(store, links, mapper, полки)

    /** Ответ модели — из теста: чтение «Приморья» собирается по строкам исследования. */
    private var ответ = "{}"
    private val служба = AiFactory.service(store, Transport { _, _, _, _ -> Answer(ответ, "тест", 0, 0) }, mapper)
    private val сверка = AiFactory.reconcile(store, KnowledgeFactory.reconcile(store, links, mapper, полки, intake = знания), служба, mapper)

    private val роутер: V2Router by lazy {
        val движок = ProcessFactory.engine(
            template = { шаблон },
            evaluator = ReadinessFactory.gateEvaluator(store, links, scenesDone = { emptySet() }, gatesPassed = { emptySet() }),
            passedGates = { mutableSetOf() }, gatePlan = { emptyMap() },
        )
        V2Router(
            store, links, движок, полки, знания, orbita.formulation.api.FormulationFactory.formulation(store, links), mapper,
            knowledgeRoutes = KnowledgeRoutes(
                знания, AiFactory.atomize(store, знания, служба, mapper), служба, mapper,
                read = AiFactory.readDocument(store, знания, служба, mapper), store = store,
            ),
            synthesisRoutes = SynthesisRoutes(store, AiFactory.synthesisJobs(store, знания, служба, mapper), сверка, mapper, links),
        )
    }

    private val записка = TestDbV2.repoRoot.resolve("docs/tz/v2/поставка-09-15/ЗАПИСКА-МИССИИ-IoT.md").toFile()
    private val приморье = TestDbV2.repoRoot.resolve("docs/tz/v2/поставка-09-14/ИССЛЕДОВАНИЕ-ПРИМОРЬЕ-ЦИКЛ1.md").toFile()

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        store.create(проект, "project", область, "1", mapper.createObjectNode().put("name", "Применимость").put("knowledge_v2", true), Provenance(Channel.MANUAL, "Иванов И."))
        // Наши сервисы — из записки (сцена 6 прожита руками): им и приписывается применимость.
        listOf("Телеметрия морского и речного флота", "Трекинг вагонов, контейнеров и пломб", "Датчики ЧС и ледовой обстановки").forEach { имя ->
            store.create("SV-%04d".format(store.list(область, "service").size + 1), "service", область, "6", mapper.createObjectNode().put("name", имя).put("qos_class", "B′"), Provenance(Channel.MANUAL, "Иванов И."), status = "accepted")
        }
    }

    /** Якорь блока канона, где дословно стоит цитата: ворота чтения требуют именно его. */
    private fun якорь(материал: String, цитата: String): String =
        знания.canon(проект, материал).firstOrNull { цитата in it.text }?.anchor
            ?: error("цитаты «${цитата.take(60)}» в каноне нет")

    private fun пункт(материал: String, цитата: String, поля: Map<String, Any>): String {
        val узел = mapper.createObjectNode().put("quote", цитата).put("anchor", якорь(материал, цитата)).put("confidence", 0.8)
        поля.forEach { (к, в) -> if (в is List<*>) узел.putArray(к).also { а -> в.forEach { а.add(it.toString()) } } else узел.put(к, в.toString()) }
        return mapper.writeValueAsString(узел)
    }

    /** Ответ чтения «Приморья»: стороны, чужие цели, применимости — и цели, которых обстановка порождать не вправе. */
    private fun ответЧтения(м: String): String {
        val стороны = listOf(
            "Минвостокразвития России" to "владелец Нацпрограммы ДВ и куратор Плана СМП",
            "Росрыболовство / ФГБУ ЦСМС" to "ОСМ обязательна для каждого судна",
            "МЧС России в Арктике" to "аварийная связь и датчики на СМП",
            "Правительство Приморского края" to "портовая и коридорная повестка региона",
        ).joinToString(",") { (имя, цитата) -> пункт(м, цитата, mapOf("name" to имя, "role" to "consumer", "interest" to цитата)) }
        val чужие = пункт(м, "грузопоток 80 млн т (2024) → 160 млн т (2035)", mapOf("statement" to "грузопоток СМП 160 млн т к 2035", "owner" to "План развития Северного морского пути до 2035", "value" to "160", "unit" to "млн т", "year" to "2035"))
        val применимости = listOf(
            пункт(м, "флот обязан передавать данные в ОСМ — телеметрия вне береговых сетей", mapOf("external_item" to "телеметрия рыбопромыслового флота вне береговых сетей", "owner" to "Росрыболовство / ФГБУ ЦСМС", "verdict" to "covered", "our_services" to listOf("SV-0001"), "rationale" to "телеметрия судов вне береговых сетей — наш сервис флота", "proposal" to "extend_scope")),
            пункт(м, "суда и арктические центры МЧС с вертолётами — датчики и аварийная связь", mapOf("external_item" to "аварийная связь и датчики МЧС на СМП", "owner" to "МЧС России в Арктике", "verdict" to "partial", "our_services" to listOf("Датчики ЧС и ледовой обстановки"), "missing" to "голосовая аварийная связь — не короткое сообщение", "rationale" to "датчики закрываем, голос — нет", "proposal" to "add_service")),
            пункт(м, "объекты — контейнеры, пломбы, транспорт портов", mapOf("external_item" to "трекинг контейнеров и пломб в портах Приморья", "owner" to "Порты Приморского края", "verdict" to "covered", "our_services" to listOf("SV-0002"), "rationale" to "трекинг вагонов, контейнеров и пломб — наш сервис")),
            пункт(м, "недостаточная пропускная способность — программная задача Нацпрограммы ДВ", mapOf("external_item" to "пропускная способность Транссиба и БАМа", "owner" to "Нацпрограмма развития ДВ", "verdict" to "not_our_profile", "rationale" to "мы не строим широкополосный интернет и не расшиваем железнодорожную инфраструктуру — это вне границ устава", "proposal" to "reject")),
            пункт(м, "нормативы транзита через пункты пропуска", mapOf("external_item" to "контроль нормативов транзита по МТК Приморье-1/2", "owner" to "МТК «Приморье-1» и «Приморье-2»", "verdict" to "needs_work", "missing" to "геозоны пунктов пропуска и интеграция с таможней", "rationale" to "трекинг есть, контроль нормативов — доработка", "proposal" to "add_service")),
        ).joinToString(",")
        // Цель, которую модель выписала из обстановки, — в проект не идёт: роль context целей не порождает.
        val цели = пункт(м, "грузопоток 80 млн т (2024) → 160 млн т (2035)", mapOf("statement" to "достичь грузопотока 160 млн т", "measure" to "160 млн т", "year" to "2035"))
        return """{"stakeholders":[$стороны],"external_targets":[$чужие],"applicabilities":[$применимости],"goals":[$цели]}"""
    }

    @Test
    fun `Приморье даёт применимости с вердиктами, «не наш профиль» — со ссылкой на границу устава, целей — ни одной`() {
        val устав = знания.putMaterial(проект, "Записка миссии IoT", "mission_memo", записка.readText(), "Иванов И.", rank = Authority.MANDATORY, role = "charter")
        val м = знания.putMaterial(проект, "Исследование Приморья", "analysis", приморье.readText(), "Иванов И.", rank = Authority.REFERENCE, role = "context")
        ответ = ответЧтения(м)

        val чтение = роутер.handle("POST", "/v2/intake/read", п, """{"material":"$м","author":"Иванов И."}""")!!
        assertEquals(201, чтение.code, чтение.body.toString())
        assertTrue("goals: роль документа «context» этого понятия не порождает" in чтение.body.path("note").asText() || store.list(область, "goal").isEmpty(), чтение.body.toString())
        val запуск = чтение.body.path("run").asText()
        val диф = роутер.handle("GET", "/v2/synthesis/runs/$запуск", п, null)!!.body
        val предложения = диф.path("diff").path("new").map { it.path("proposal").asText() }
        assertTrue(предложения.size >= 9, "стороны, чужая цель следом, применимости: $предложения ${диф.path("note")}")
        val принято = роутер.handle("POST", "/v2/synthesis/runs/$запуск/accept", п, mapper.writeValueAsString(mapper.createObjectNode().put("author", "Иванов И.").also { it.putArray("chosen").also { а -> предложения.forEach { к -> а.add(к) } } }))!!
        assertEquals(201, принято.code, принято.body.toString())

        assertTrue(store.list(область, "goal").isEmpty(), "обстановка целей не порождает: ${store.list(область, "goal").map { it.code }}")
        val возможности = store.list(область, "opportunity")
        assertTrue(возможности.size >= 4, "применимостей ${возможности.size}: ${принято.body}")

        val матрица = роутер.handle("GET", "/v2/opportunities", п, null)!!.body
        val строки = матрица.path("rows")
        assertTrue(строки.size() >= 4, матрица.toString())
        assertEquals(1, матрица.path("by_verdict").path("not_our_profile").asInt(), матрица.path("by_verdict").toString())
        val неНаш = строки.first { it.path("verdict").asText() == "not_our_profile" }
        assertEquals("не наш профиль", неНаш.path("verdict_word").asText())
        assertEquals(устав, неНаш.path("charter_boundary").path("material").asText(), "«не наш профиль» опирается на устав: $неНаш")
        assertTrue(неНаш.path("charter_boundary").path("anchor").asText().startsWith("s"), неНаш.path("charter_boundary").toString())
        assertTrue("не" in неНаш.path("charter_boundary").path("text").asText().lowercase(), "граница — блок «чего мы не строим»: ${неНаш.path("charter_boundary")}")
        assertEquals("отклонить", неНаш.path("proposal_word").asText())
        val покрыта = строки.first { it.path("external_item").asText().startsWith("телеметрия рыбопромыслового") }
        assertEquals("covered", покрыта.path("verdict").asText())
        assertTrue(покрыта.path("our_service_names")[0].asText().startsWith("SV-0001 · Телеметрия"), покрыта.path("our_service_names").toString())
        assertTrue(покрыта.path("external").path("anchor").asText().isNotBlank(), "основание — след-факт с якорем: ${покрыта.path("external")}")
        assertEquals("context", покрыта.path("external").path("role").asText())
        val чужие = матрица.path("external_targets")
        assertEquals(1, чужие.size(), "чужая цель — фактом, не целью: $чужие")
        assertTrue("План развития" in чужие[0].path("owner").asText())

        // Решение человека: отклонение — с причиной; принятие — состояние по истине.
        assertFailsWith<IllegalArgumentException> { роутер.handle("POST", "/v2/opportunities/${неНаш.path("code").asText()}/decide", п, """{"status":"rejected","author":"Иванов И."}""") }
        val решено = роутер.handle("POST", "/v2/opportunities/${покрыта.path("code").asText()}/decide", п, """{"status":"accepted","author":"Иванов И."}""")!!
        assertEquals("accepted", решено.body.path("status").asText())
        assertEquals("accepted", store.byCode(область, покрыта.path("code").asText())!!.status)
    }

    @Test
    fun `без устава матрица говорит, что «не наш профиль» не на что сослаться`() {
        val матрица = роутер.handle("GET", "/v2/opportunities", п, null)!!.body
        assertEquals(0, матрица.path("rows").size())
        assertTrue("границ устава не найдено" in матрица.path("note").asText(), матрица.path("note").asText())
    }
}
