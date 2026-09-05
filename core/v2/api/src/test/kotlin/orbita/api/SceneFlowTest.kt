// Сквозной проход сцен 1–4 через маршруты v2 — ворота волны 1.
//
// Проверяется не «форма отправилась», а то, ради чего строится продукт:
// сцена 3 закрыта, пока замысел не принят; открывается сама; точка MCR
// держится, пока цели не связаны с нуждами; отказ приходит от движка.
package orbita.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.api.internal.V2Router
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.internal.PgEntityStore
import orbita.kernel.internal.PgLinkRegistry
import orbita.process.internal.TemplateProcessEngine
import orbita.readiness.internal.DomainGateEvaluator
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SceneFlowTest {

    private val mapper = ObjectMapper()
    private val store = PgEntityStore(TestDbV2.conn, mapper)
    private val links = PgLinkRegistry(TestDbV2.conn)
    private val пройденные = mutableMapOf<String, MutableSet<String>>()

    private val шаблон = mapper.readTree(
        TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые/ШАБЛОН-ФАЗЫ-PRE-A-NASA.json").toFile(),
    )

    private val router: V2Router by lazy {
        val движок = TemplateProcessEngine(
            шаблон = { шаблон },
            оценщик = DomainGateEvaluator(
                store, links,
                сценыПройдены = { emptySet() },
                воротаПройдены = { p -> пройденные.getOrPut(p) { mutableSetOf() } },
            ),
            пройденныеТочки = { p -> пройденные.getOrPut(p) { mutableSetOf() } },
            планТочек = { проект ->
                store.list(Area.Project(проект), "gate")
                    .associate { it.code to it.doc.path("planned_date").asText("") }
                    .filterValues { it.isNotBlank() }
            },
        )
        V2Router(store, links, движок, mapper)
    }

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        пройденные.clear()
    }

    private fun сцена(фаза: com.fasterxml.jackson.databind.JsonNode, ключ: String) =
        фаза.path("scenes").single { it.path("key").asText() == ключ }

    @Test
    fun `сцена 1 заводит проект и три точки с датами`() {
        val ответ = router.handle("POST", "/v2/projects", emptyMap(),
            """{"name":"Проверка волны 1","code":"PJ-9100","author":"Чернов Д."}""")!!
        assertEquals(201, ответ.code)
        assertEquals("NASA-7120", ответ.body.path("standard").asText())
        assertEquals(3, ответ.body.path("gates").size(), "фаза заводится с тремя точками")
        ответ.body.path("gates").forEach {
            assertTrue(it.path("planned_date").asText().isNotBlank(), "у точки обязана быть дата")
        }
        // сцена 1 прожита, сцена 2 открыта, сцена 3 закрыта
        assertEquals("done", сцена(ответ.body, "1").path("state").asText())
        assertEquals("open", сцена(ответ.body, "2").path("state").asText())
        assertEquals("locked", сцена(ответ.body, "3").path("state").asText())
    }

    @Test
    fun `сцена 3 открывается принятым замыслом, а не флагом`() {
        router.handle("POST", "/v2/projects", emptyMap(), """{"name":"П","code":"PJ-9101"}""")
        val параметры = mapOf("project" to "PJ-9101")

        // черновик замысла сцену не открывает
        router.handle("POST", "/v2/intent", параметры,
            """{"for_whom":"перевозчики","what":"телеметрия","where":"СМП","horizon":"2033","accepted":false}""")
        val до = router.handle("GET", "/v2/phase", параметры, null)!!.body
        assertEquals("locked", сцена(до, "3").path("state").asText())
        assertTrue(сцена(до, "3").path("blockers").toString().contains("не принят"))

        // принятый — открывает
        router.handle("POST", "/v2/intent", параметры,
            """{"for_whom":"перевозчики","what":"телеметрия","where":"СМП","horizon":"2033","accepted":true}""")
        val после = router.handle("GET", "/v2/phase", параметры, null)!!.body
        assertEquals("open", сцена(после, "3").path("state").asText())
    }

    @Test
    fun `нужда без носителя не заводится`() {
        router.handle("POST", "/v2/projects", emptyMap(), """{"name":"П","code":"PJ-9102"}""")
        val параметры = mapOf("project" to "PJ-9102")
        val отказ = runCatching {
            router.handle("POST", "/v2/needs", параметры, """{"statement":"нужна связь","owner":"SK-9999"}""")
        }.exceptionOrNull()
        assertNotNull(отказ, "нужда с несуществующим носителем не заводится")
        assertTrue("не найден" in (отказ.message ?: ""), отказ.message ?: "")
    }

    @Test
    fun `проход сцен 1-4 закрывает MCR, и точка фиксируется только после этого`() {
        router.handle("POST", "/v2/projects", emptyMap(), """{"name":"Полный проход","code":"PJ-9103"}""")
        val параметры = mapOf("project" to "PJ-9103")
        router.handle("POST", "/v2/intent", параметры,
            """{"for_whom":"перевозчики","what":"телеметрия","where":"СМП","horizon":"2033","accepted":true}""")

        // до сцен 3–4 точка держится
        val отказ = runCatching { router.handle("POST", "/v2/gates/MCR/pass", параметры, """{"author":"Чернов Д."}""") }
            .exceptionOrNull()
        assertNotNull(отказ, "MCR обязан держаться до сцен 3 и 4")

        // три стороны, у каждой нужда
        val коды = (1..3).map { n ->
            val ответ = router.handle("POST", "/v2/stakeholders", параметры,
                """{"name":"Сторона $n","role":"customer"}""")!!
            ответ.body.path("code").asText()
        }
        коды.forEachIndexed { i, код ->
            router.handle("POST", "/v2/needs", параметры,
                """{"statement":"нужда стороны ${i + 1} в телеметрии","owner":"$код"}""")
        }

        // цель, закрывающая все нужды
        val нужды = router.handle("GET", "/v2/entities", параметры + ("kind" to "need"), null)!!
            .body.path("items").map { it.path("code").asText() }
        router.handle("POST", "/v2/goals", параметры,
            """{"statement":"отслеживаемость перевозок","year":2033,"covers":${mapper.writeValueAsString(нужды)}}""")

        val фаза = router.handle("GET", "/v2/phase", параметры, null)!!.body
        assertEquals("done", сцена(фаза, "3").path("state").asText(), сцена(фаза, "3").path("blockers").toString())
        assertEquals("done", сцена(фаза, "4").path("state").asText(), сцена(фаза, "4").path("blockers").toString())

        val mcr = фаза.path("gates").single { it.path("key").asText() == "MCR" }
        assertEquals(0, mcr.path("blocking").size(), "условия MCR выполнены: ${mcr.path("blocking")}")

        val пройдена = router.handle("POST", "/v2/gates/MCR/pass", параметры, """{"author":"Чернов Д."}""")!!
        assertTrue(
            пройдена.body.path("gates").single { it.path("key").asText() == "MCR" }.path("passed").asBoolean(),
            "после выполнения условий точка фиксируется",
        )
    }
}
