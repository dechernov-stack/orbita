// Нужда — много носителей (ЗАДАНИЕ-ШИП-4 §1): копии одной формулировки
// сливаются в одну нужду с носителями, связи переезжают, история остаётся.
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

class NeedOwnersMigrationTest {
    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val проект = "PJ-9860"
    private val область = Area.Project(проект)
    private val п = Provenance(Channel.MANUAL, "Иванов И.")

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        store.create(проект, "project", область, "1", mapper.readTree("""{"name":"Миграция нужд","standard":"NASA-7120"}"""), п)
    }

    private fun сторона(код: String, имя: String) =
        store.create(код, "stakeholder", область, "3", mapper.readTree("""{"name":"$имя","role":"customer","interest":"—"}"""), п)

    @Test
    fun `копии одной формулировки сливаются в нужду с носителями, связи переезжают, копия снята с историей`() {
        val минтранс = сторона("SK-1", "Минтранс России")
        val глонасс = сторона("SK-2", "АО ГЛОНАСС")
        val росморпорт = сторона("SK-3", "Росморпорт")
        val первая = store.create("ND-0001", "need", область, "3", mapper.readTree("""{"statement":"суверенитет и безопасность данных","coverage_expected":"service"}"""), п)
        links.link("owns", минтранс.id, первая.id, п)
        val копия = store.create("ND-0002", "need", область, "3", mapper.readTree("""{"statement":"Суверенитет и безопасность данных.","coverage_expected":"service"}"""), п)
        links.link("owns", глонасс.id, копия.id, п)
        // третья копия — носитель прежним строковым полем, связи нет
        val третья = store.create("ND-0003", "need", область, "3", mapper.readTree("""{"statement":"суверенитет и безопасность данных","stakeholders":[]}"""), п)
        links.link("owns", росморпорт.id, третья.id, п)
        val другая = store.create("ND-0004", "need", область, "3", mapper.readTree("""{"statement":"единое оперативное управление транспортом"}"""), п)
        links.link("owns", минтранс.id, другая.id, п)
        // цель покрывает копию, а не первую: связь обязана переехать
        val цель = store.create("MG-0001", "goal", область, "4", mapper.readTree("""{"statement":"данные внутри страны","year":2030}"""), п)
        links.link("covers", цель.id, копия.id, п)
        val факт = store.create("F-0001", "fact", область, null, mapper.readTree("""{"kind":"framing","subject":"Минтранс","predicate":"нуждается в суверенитете данных","value":"","anchor":"b1","disposition":"adopted"}"""), п)
        links.link("derived_from_fact", копия.id, факт.id, п)

        val отчёт = KnowledgeFactory.migrateNeedOwners(store, links, mapper)
        assertTrue("слито копий 2" in отчёт, отчёт)

        val живые = store.list(область, "need").filter { it.status != "cancelled" }
        assertEquals(setOf("ND-0001", "ND-0004"), живые.map { it.code }.toSet(), "одна формулировка — одна нужда")
        val выжившая = store.byCode(область, "ND-0001")!!
        assertEquals(setOf(минтранс.id, глонасс.id, росморпорт.id), links.to(выжившая.id, "owns").map { it.from }.toSet(), "носители — объединение")
        assertEquals(links.to(выжившая.id, "owns").map { it.from }.toSet(), выжившая.doc.path("stakeholders").map { it.asText() }.toSet(), "поле зеркалит связи")
        assertEquals(listOf(цель.id), links.to(выжившая.id, "covers").map { it.from }, "покрытие переехало")
        assertEquals(listOf(факт.id), links.from(выжившая.id, "derived_from_fact").map { it.to }, "основание переехало")
        val снятая = store.byCode(область, "ND-0002")!!
        assertEquals("cancelled", снятая.status)
        assertTrue("слито в ND-0001" in снятая.doc.path("notes").asText(""), снятая.doc.toString())
        assertTrue(links.to(снятая.id).isEmpty() && links.from(снятая.id).isEmpty(), "у снятой копии связей не осталось")
        assertTrue(store.history(снятая.id).size >= 2, "история копии сохранена версиями")

        // Повтор — ничего не меняет.
        val второй = KnowledgeFactory.migrateNeedOwners(store, links, mapper)
        assertTrue("слито копий 0" in второй && "у 0 нужд" in второй, второй)
    }
}
