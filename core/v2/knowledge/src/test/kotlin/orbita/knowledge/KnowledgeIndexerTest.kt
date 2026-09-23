// База знаний (шип 4 §2, мера задания): поиск «интервал передачи» находит пункт
// ПП 2216 и факт записки; термины словаря и блоки канона тоже в индексе;
// перестроение идемпотентно, исчезнувший факт снимается.
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
import kotlin.test.assertTrue

class KnowledgeIndexerTest {
    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val индекс = KnowledgeFactory.index(store, KernelFactory.textIndex(TestDbV2.conn, mapper), embeddings = null, mapper = mapper)
    private val проект = "PJ-9862"
    private val область = Area.Project(проект)
    private val п = Provenance(Channel.MANUAL, "Иванов И.")

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        store.create(проект, "project", область, "1", mapper.readTree("""{"name":"База знаний","standard":"NASA-7120"}"""), п)
        store.create("NR-2216", "normative_document", Area.Library, null, mapper.readTree(
            """{"designation":"ПП РФ от 22.12.2020 № 2216","title":"О передаче данных в Ространснадзор",
               "clauses":[{"clause":"обязанность","text":"Передавать координаты, курс, скорость, дату и время с интервалом не более 30 с через ГАИС «ЭРА-ГЛОНАСС» в Ространснадзор","who":"Собственники ТС категорий M2, M3 и N при перевозке опасных грузов"}]}"""), п)
        store.create("GT-ST-001", "glossary_term", Area.Library, null, mapper.readTree(
            """{"term_ru":"Ространснадзор","class":"stakeholder","definition":"Федеральная служба по надзору в сфере транспорта","synonyms":["ФСНСТ"]}"""), п, status = "accepted")
        store.create("M-0001", "material", область, null, mapper.readTree(
            """{"name":"Записка о миссии","role":"charter","rank":"mandatory","type":"tor",
               "text":"# Записка о миссии\n\nСистема передаёт телеметрию с максимальным интервалом передачи 30 секунд.\n\nМасса платформы не более 100 кг."}"""), п)
        store.create("F-0001", "fact", область, "3", mapper.readTree(
            """{"kind":"quantity","subject":"телеметрия","predicate":"максимальный интервал передачи","value":"30","unit":"с",
               "quote":"с максимальным интервалом передачи 30 секунд","material":"M-0001","mark":"И","rank":"mandatory","evidence":"claimed","disposition":"free",
               "source":{"material":"M-0001","anchor":"s1#1"}}"""), п)
    }

    @Test
    fun `«интервал передачи» находит пункт ПП 2216 и факт записки, а перестроение идемпотентно`() {
        val первый = индекс.rebuild(проект)
        assertTrue(первый.blocks >= 4, "канон, термин, пункт, факт: ${первый.note}")
        assertEquals(первый.blocks, первый.written)
        val второй = индекс.rebuild(проект)
        assertEquals(0, второй.written, "отпечатки не изменились: ${второй.note}")

        val находки = индекс.search(проект, "интервал передачи")
        val виды = находки.associate { it.kind to it.ref }
        assertEquals("NR-2216", виды["clause"], "пункт ПП 2216: $находки")
        assertEquals("F-0001", виды["fact"], "факт записки: $находки")
        assertEquals("M-0001", виды["canon_block"], "блок канона записки: $находки")

        assertEquals(listOf("GT-ST-001"), индекс.search(проект, "ФСНСТ", kinds = listOf("term")).map { it.ref }, "термин по синониму")
        assertEquals(listOf("NR-2216"), индекс.search(проект, "интервал", filters = mapOf("rank" to "mandatory"), kinds = listOf("clause")).map { it.ref })

        store.update(store.byCode(область, "F-0001")!!.id, store.byCode(область, "F-0001")!!.doc, п, status = "cancelled")
        val третий = индекс.rebuild(проект)
        assertEquals(1, третий.removed, "снятый факт ушёл из индекса: ${третий.note}")
        assertTrue(индекс.search(проект, "интервал передачи", kinds = listOf("fact")).isEmpty())
    }
}
