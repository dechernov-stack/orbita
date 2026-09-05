// Ворота волны 1 — главная мера: сцена 3 закрыта без замысла ДВИЖКОМ.
//
// Не интерфейсом, не флагом в базе: условие входа сцены 3 спрашивает данные
// домена, и пока замысел не принят, сцена не открывается. Приняли — сцена
// открылась сама, без единого клика по «статусу».
package orbita.readiness

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Provenance
import orbita.kernel.internal.PgEntityStore
import orbita.kernel.internal.PgLinkRegistry
import orbita.process.api.SceneState
import orbita.process.api.ProcessFactory
import orbita.readiness.api.ReadinessFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SceneGateTest {

    private val mapper = ObjectMapper()
    private val store = PgEntityStore(TestDbV2.conn, mapper)
    private val links = PgLinkRegistry(TestDbV2.conn)
    private val провенанс = Provenance(Channel.MANUAL, "Иванов И.")
    private val проект = "PJ-9001"
    private val область = Area.Project(проект)

    private val пройденные = mutableMapOf<String, MutableSet<String>>()

    private val шаблон = mapper.readTree(
        TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые/ШАБЛОН-ФАЗЫ-PRE-A-NASA.json").toFile(),
    )

    private val движок by lazy {
        val прожитые = { p: String -> emptySet<String>() }
        ProcessFactory.engine(
            template = { шаблон },
            evaluator = ReadinessFactory.gateEvaluator(
                store, links,
                scenesDone = прожитые,
                gatesPassed = { p -> пройденные.getOrPut(p) { mutableSetOf() } },
            ),
            passedGates = { p -> пройденные.getOrPut(p) { mutableSetOf() } },
            gatePlan = { emptyMap() },
        )
    }

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        пройденные.clear()
        // сцена 1: проект и точки заведены
        store.create("PJ-9001", "project", область, "1",
            mapper.readTree("""{"name":"Проверка ворот","standard":"NASA-7120"}"""), провенанс)
        listOf("internal_review" to "2026-10-05", "MCR" to "2026-11-05", "KDP-A" to "2026-12-05")
            .forEach { (ключ, дата) ->
                store.create(ключ, "gate", область, "1",
                    mapper.readTree("""{"title":"$ключ","planned_date":"$дата"}"""), провенанс)
            }
    }

    /** Проект опознаётся КОДОМ — он же стоит в области его сущностей. */
    private fun идПроекта(): String = проект

    @Test
    fun `сцена 3 закрыта без принятого замысла - и говорит почему`() {
        val вид = движок.view(идПроекта())
        val сцена3 = вид.scenes.single { it.key == "3" }
        assertEquals(SceneState.LOCKED, сцена3.state, "без замысла сцена 3 обязана быть закрыта")
        assertTrue(
            сцена3.blockers.any { "замысел" in it },
            "закрытая сцена обязана назвать причину: ${сцена3.blockers}",
        )
        // и сцена 2 при этом открыта — работать есть где
        assertEquals(SceneState.OPEN, вид.scenes.single { it.key == "2" }.state)
        assertEquals("2", вид.currentScene)
    }

    @Test
    fun `принятый замысел открывает сцену 3 сам`() {
        val id = идПроекта()
        store.create("INT-0001", "intent", область, "2",
            mapper.readTree("""{"for_whom":"перевозчики","what":"телеметрия","where":"СМП","horizon":"2033"}"""),
            провенанс, status = "accepted")

        val сцена3 = движок.view(id).scenes.single { it.key == "3" }
        assertEquals(SceneState.OPEN, сцена3.state, "замысел принят — сцена 3 открывается сама: ${сцена3.blockers}")
    }

    @Test
    fun `непринятый замысел сцену не открывает`() {
        val id = идПроекта()
        store.create("INT-0002", "intent", область, "2",
            mapper.readTree("""{"for_whom":"перевозчики"}"""), провенанс, status = "draft")

        val сцена3 = движок.view(id).scenes.single { it.key == "3" }
        assertEquals(SceneState.LOCKED, сцена3.state)
        assertTrue(сцена3.blockers.any { "не принят" in it }, "${сцена3.blockers}")
    }

    @Test
    fun `точка MCR держится, пока сцены 3 и 4 не прожиты, и отказ приходит от движка`() {
        val id = идПроекта()
        val mcr = движок.view(id).gates.single { it.key == "MCR" }
        assertTrue(mcr.blocking.isNotEmpty(), "MCR обязан держаться до сцен 3 и 4")

        val отказ = runCatching { движок.passGate(id, "MCR", "Чернов Д.") }.exceptionOrNull()
        assertNotNull(отказ, "фиксация точки с невыполненными условиями обязана отказать")
        assertTrue("держится" in (отказ.message ?: ""), отказ.message ?: "")
    }

    @Test
    fun `стейкхолдер без нужды держит сцену 3 закрытой на выходе`() {
        val id = идПроекта()
        store.create("INT-0003", "intent", область, "2",
            mapper.readTree("""{"for_whom":"перевозчики"}"""), провенанс, status = "accepted")
        (1..3).forEach { n ->
            store.create("SK-000$n", "stakeholder", область, "3",
                mapper.readTree("""{"name":"Сторона $n","role":"customer"}"""), провенанс)
        }
        // нужда есть только у первого
        val первый = store.byCode(область, "SK-0001")!!
        val нужда = store.create("ND-0001", "need", область, "3",
            mapper.readTree("""{"statement":"нужна телеметрия груза в пути"}"""), провенанс)
        links.link("owns", первый.id, нужда.id, провенанс)

        val сцена3 = движок.view(id).scenes.single { it.key == "3" }
        assertEquals(SceneState.OPEN, сцена3.state, "вход выполнен — сцена открыта")
        assertTrue(
            сцена3.blockers.any { "без нужд" in it && "Сторона 2" in it },
            "выход обязан назвать поимённо, у кого нет нужд: ${сцена3.blockers}",
        )
    }
}
