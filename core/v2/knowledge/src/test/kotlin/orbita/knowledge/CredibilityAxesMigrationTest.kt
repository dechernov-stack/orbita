// Четыре оси достоверности (ЗАДАНИЕ-ШИП-4 §1.6): прежние имена полей сходят в
// mark · mark_no · rank, уверенность у факта снимается; записи по новой истине
// не трогаются.
package orbita.knowledge

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.Provenance
import orbita.knowledge.api.KnowledgeFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CredibilityAxesMigrationTest {
    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val проект = "PJ-9861"
    private val область = Area.Project(проект)
    private val п = Provenance(Channel.MANUAL, "Иванов И.")

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        store.create(проект, "project", область, "1", mapper.readTree("""{"name":"Оси достоверности","standard":"NASA-7120"}"""), п)
    }

    /** Запись прежней формы — так лежат записи стенда, заведённые до миграции. */
    private fun прежняя(id: String, документ: String) = TestDbV2.подложитьДокумент(id, документ)

    @Test
    fun `прежние имена сходят в новые, уверенность факта снимается, второй прогон пуст`() {
        val факт = store.create("F-0001", "fact", область, null, mapper.readTree(
            """{"kind":"framing","subject":"Минтранс","predicate":"не хватает","value":"связи","mark":"В",
               "rank":"reference","evidence":"claimed","disposition":"free","source":{"material":"M-1","anchor":"s1#1"},"quote":"…связи…","material":"M-1"}"""), п)
        прежняя(факт.id, """{"kind":"framing","subject":"Минтранс","predicate":"не хватает","value":"связи","source_mark":"В",
               "source_mark_no":"В3","authority":"reference","confidence":0.7,"evidence":"claimed","disposition":"free",
               "source":{"material":"M-1","anchor":"s1#1"},"quote":"…связи…","material":"M-1"}""")
        val материал = store.create("M-1", "material", область, null, mapper.readTree(
            """{"name":"Записка","rank":"mandatory","role":"charter","type":"tor"}"""), п)
        прежняя(материал.id, """{"name":"Записка","authority":"mandatory","role":"charter","type":"tor"}""")
        val норматив = store.create("NRM-1", "normative_document", Area.Library, null, mapper.readTree(
            """{"designation":"ПП РФ № 2216","title":"О связи","mark":"В"}"""), п)
        прежняя(норматив.id, """{"designation":"ПП РФ № 2216","title":"О связи","source_mark":"В"}""")
        val поИстине = store.create("F-0002", "fact", область, null, mapper.readTree(
            """{"kind":"framing","subject":"Росморпорт","predicate":"не хватает","value":"проводки","mark":"И",
               "rank":"mandatory","evidence":"claimed","disposition":"free","source":{"material":"M-1","anchor":"s1#2"},"quote":null,"material":"M-1"}"""), п)

        val итог = KnowledgeFactory.migrateCredibilityAxes(store, mapper)
        assertTrue("фактов 1" in итог && "материалов 1" in итог && "нормативов 1" in итог, итог)

        val ф = store.byId(факт.id)!!.doc
        assertEquals("В", ф.path("mark").asText()); assertEquals("В3", ф.path("mark_no").asText())
        assertEquals("reference", ф.path("rank").asText())
        assertFalse(ф.has("source_mark") || ф.has("source_mark_no") || ф.has("authority") || ф.has("confidence"), ф.toString())
        assertEquals("mandatory", store.byId(материал.id)!!.doc.path("rank").asText())
        assertFalse(store.byId(материал.id)!!.doc.has("authority"))
        assertEquals("В", store.byId(норматив.id)!!.doc.path("mark").asText())
        assertEquals(1, store.byId(поИстине.id)!!.version, "запись по новой истине не тронута")

        val снова = KnowledgeFactory.migrateCredibilityAxes(store, mapper)
        assertTrue("фактов 0" in снова && "материалов 0" in снова && "нормативов 0" in снова, снова)
    }
}
