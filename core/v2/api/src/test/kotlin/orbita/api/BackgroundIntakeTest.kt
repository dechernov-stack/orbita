// Разбор фоновой задачей (ADR-069, предусловие ПМИ-5): сеть в фоне, база —
// на потоке запросов; ответ из журнала применяется сразу; отказ провайдера
// не теряется, а называется.
package orbita.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.ai.api.AiFactory
import orbita.ai.api.Answer
import orbita.ai.api.ProviderUnavailable
import orbita.ai.api.Transport
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.Provenance
import orbita.knowledge.api.KnowledgeFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BackgroundIntakeTest {
    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val проект = "PJ-9804"
    private val область = Area.Project(проект)
    private val п = Provenance(Channel.MANUAL, "Чернов Д.")
    private val intake = KnowledgeFactory.intake(store, links, mapper)

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        store.create(проект, "project", область, "1", mapper.readTree("""{"name":"Фоновый разбор","standard":"NASA-7120","phase":"Pre-Phase A"}"""), п)
    }

    private fun ответ(якорь: String) = """{"topics":[{"label":"телеметрия"}],"actions":[
        {"kind":"create_entity","target_kind":"requirement","scene":"8","title":"требование из п. 4.1","preview":"…","payload":{"statement":"приём телеметрии не реже 30 мин","level":"project","category":"performance"},"facts":[0]}],"facts":[
        {"kind":"obligation","topic":"телеметрия","subject":"ТЗ п. 4.1","predicate":"приём телеметрии не реже 30 мин","value":"требование","source":{"anchor":"$якорь"},"source_mark":"И"}]}"""

    @Test
    fun `фоновый разбор — задание идёт, стенд свободен, ответ применяется при опросе`() {
        val материал = intake.putMaterial(проект, "ТЗ", "tor", "п. 4.1 Система обязана обеспечивать приём телеметрии с интервалом не более 30 мин.", "Иванов И.")
        val якорь = intake.canon(проект, материал).first().anchor
        val отпустить = CountDownLatch(1)
        val дошло = CountDownLatch(1)
        val транспорт = Transport { _, _, _ ->
            дошло.countDown()
            отпустить.await(10, TimeUnit.SECONDS)
            Answer(ответ(якорь), "тест", 10, 20)
        }
        val служба = AiFactory.service(store, транспорт, mapper)
        val задания = AiFactory.atomizeJobs(store, intake, служба, mapper)

        val старт = задания.start(проект, материал, "это ТЗ — оцени против нужд", "Иванов И.")
        assertEquals("running", старт.status, "ответ не ждём: задание идёт")
        assertTrue(дошло.await(5, TimeUnit.SECONDS), "сеть звонит в фоне")
        assertEquals("running", задания.poll(проект, старт.id)!!.status, "пока модель думает — идёт")
        assertTrue(store.list(область, "fact").isEmpty(), "до ответа в базе ничего")

        отпустить.countDown()
        var готово = задания.poll(проект, старт.id)!!
        var попыток = 0
        while (готово.status == "running" && попыток < 50) { Thread.sleep(50); готово = задания.poll(проект, старт.id)!!; попыток += 1 }
        assertEquals("done", готово.status, готово.error ?: "")
        assertEquals(1, готово.accepted)
        assertNotNull(готово.task, "задание с планом заведено при опросе")
        assertEquals(1, store.list(область, "ai_call").size, "вызов записан в журнал на потоке запросов")
        assertEquals(1, store.list(область, "fact").size)
        assertEquals(1, задания.list(проект).size)

        // Тот же материал повторно — ответ из журнала, задание готово сразу, второго вызова нет.
        val повтор = задания.start(проект, материал, "это ТЗ — оцени против нужд", "Иванов И.")
        assertEquals("done", повтор.status, "ответ из журнала применяется без фона")
        assertEquals(1, store.list(область, "ai_call").size, "второго вызова нет")
    }

    @Test
    fun `отказ провайдера — задание не удалось, причина названа, база не тронута`() {
        val материал = intake.putMaterial(проект, "ТЗ", "tor", "п. 4.1 Текст.", "Иванов И.")
        val транспорт = Transport { _, _, _ -> throw ProviderUnavailable("нет ключа") }
        val задания = AiFactory.atomizeJobs(store, intake, AiFactory.service(store, транспорт, mapper), mapper)
        val старт = задания.start(проект, материал, "разбери", "Иванов И.")
        var з = задания.poll(проект, старт.id)!!
        var попыток = 0
        while (з.status == "running" && попыток < 50) { Thread.sleep(50); з = задания.poll(проект, старт.id)!!; попыток += 1 }
        assertEquals("failed", з.status)
        assertTrue(з.error!!.contains("нет ключа"), з.error)
        assertTrue(store.list(область, "ai_call").isEmpty())
        assertEquals(null, задания.poll("PJ-другой", старт.id), "задание чужого проекта не видно")
    }
}
