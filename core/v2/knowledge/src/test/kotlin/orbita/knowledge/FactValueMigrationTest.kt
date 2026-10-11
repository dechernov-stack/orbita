// Величина факта объектом measure (§0.2 шипа 6): живая запись прежней формы
// переносится, отменённая — нет. Отменённые держат поля вне истины (оси
// достоверности 24.09 их не сняли — фильтр cancelled), и update такой записи
// ронял миграцию на страже посреди списка: на стенде 216 миграция понятий
// падала при каждом старте api, факты после отказавшей записи не переносились.
package orbita.knowledge

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.Provenance
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FactValueMigrationTest {
    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val проект = "PJ-9862"
    private val область = Area.Project(проект)
    private val п = Provenance(Channel.MANUAL, "Иванов И.")

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        store.create(проект, "project", область, "1", mapper.readTree("""{"name":"Величина факта","standard":"NASA-7120"}"""), п)
    }

    @Test
    fun `живой факт прежней формы переносится, отменённый не трогается`() {
        val живой = store.create("F-0001", "fact", область, null, mapper.readTree(
            """{"kind":"величина","subject":"Полезная нагрузка","predicate":"масса","value":{"value":180.25,"unit":"кг"},
               "mark":"И","rank":"mandatory","evidence":"measured","disposition":"free","source":{"material":"M-1","anchor":"s1#1"},"quote":null,"material":"M-1"}"""), п)
        TestDbV2.подложитьДокумент(живой.id, """{"kind":"величина","subject":"Полезная нагрузка","predicate":"масса","value":"≤180","unit":"кг",
               "mark":"И","rank":"mandatory","evidence":"measured","disposition":"free","source":{"material":"M-1","anchor":"s1#1"},"quote":null,"material":"M-1"}""")
        // Отменённый факт стенда: актуальная версия держит поля вне истины,
        // оси достоверности 24.09 сняты только у живых.
        val снятый = store.create("F-0002", "fact", область, null, mapper.readTree(
            """{"kind":"величина","subject":"Срок","predicate":"не позднее","value":{"value":2030,"unit":"г"},
               "mark":"И","rank":"reference","evidence":"claimed","disposition":"free","source":{"material":"M-1","anchor":"s1#2"},"quote":null,"material":"M-1"}"""), п)
        store.update(снятый.id, store.byId(снятый.id)!!.doc, п, status = "cancelled")
        TestDbV2.подложитьДокумент(снятый.id, """{"kind":"величина","subject":"Срок","predicate":"не позднее","value":"2030","unit":"г",
               "source_mark":"И","source_mark_no":"И2","authority":"reference","confidence":0.7,
               "evidence":"claimed","disposition":"free","source":{"material":"M-1","anchor":"s1#2"},"quote":null,"material":"M-1"}""")

        val итог = orbita.knowledge.internal.FactValueMigration(store, mapper).run()
        assertTrue("перенесено 1" in итог, итог)

        val после = store.byId(живой.id)!!.doc
        assertTrue(после.path("value").isObject, после.toString())
        assertEquals("<=", после.path("value").path("op").asText())
        assertEquals(180.0, после.path("value").path("value").asDouble())
        assertFalse(после.has("unit"), "top-level unit снят")
        // Отменённый — как лежал: страж его бы отклонил, а миграция не падает.
        val снятыйПосле = store.byId(снятый.id)!!.doc
        assertEquals("2030", снятыйПосле.path("value").asText())
        assertEquals("г", снятыйПосле.path("unit").asText())
        assertTrue(снятыйПосле.has("authority"), "отменённый не тронут")
    }
}
