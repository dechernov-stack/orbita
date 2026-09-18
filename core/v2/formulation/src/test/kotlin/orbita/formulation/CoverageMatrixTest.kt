// Матрица покрытия говорит о живом и по истине (проход владельца, 17.09).
//
// Два отказа одного прохода: матрица считала снятое с учёта («покрыто 29 из
// 143» при тридцати трёх живых нуждах) и спрашивала сервис у всякой нужды —
// а по истине 17.09 (`need.coverage_expected`) сервиса ждёт не всякая.
package orbita.formulation

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.formulation.api.FormulationFactory
import orbita.formulation.api.NeedCoverage
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Entity
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.Provenance
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoverageMatrixTest {

    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val провенанс = Provenance(Channel.MANUAL, "Иванов И.")
    private val проект = "PJ-F01"
    private val область = Area.Project(проект)

    /** Чем закрывается нужда: в жизни правило приходит из истины онтологии. */
    private val покрытие = mutableMapOf<String, String>()
    private val постановка by lazy {
        FormulationFactory.formulation(store, links) { нужда -> покрытие[нужда.code] ?: NeedCoverage.SERVICE }
    }

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        покрытие.clear()
        store.create(проект, "project", область, "1", mapper.readTree("""{"name":"Покрытие"}"""), провенанс)
    }

    private fun сторона(код: String, имя: String, роль: String = "customer"): Entity = store.create(
        код, "stakeholder", область, "3", mapper.readTree("""{"name":"$имя","role":"$роль"}"""), провенанс,
    )

    private fun нужда(код: String, текст: String, носитель: Entity): Entity {
        val запись = store.create(код, "need", область, "3", mapper.readTree("""{"statement":"$текст"}"""), провенанс)
        links.link("owns", носитель.id, запись.id, провенанс)
        return запись
    }

    private fun снять(запись: Entity) {
        store.update(запись.id, запись.doc, провенанс, status = "cancelled")
    }

    @Test
    fun `снятое с учёта в матрицу не попадает, а носителем считается живая сторона`() {
        val живая = сторона("SK-0002", "Минтранс")
        val снятаяСторона = сторона("SK-0001", "Минтранс (снятая)")
        val живаяНужда = нужда("ND-0002", "связь в Арктике", живая)
        // Прежняя нужда того же смысла и её носитель сняты пакетом.
        val снятаяНужда = нужда("ND-0001", "связь в Арктике", снятаяСторона)
        снять(снятаяНужда)
        // И у живой нужды осталась связь на снятую сторону — так бывает после отмены.
        links.link("owns", снятаяСторона.id, живаяНужда.id, провенанс)
        снять(снятаяСторона)

        val матрица = постановка.coverage(проект)

        assertEquals(1, матрица.total, "в матрице только живая нужда: ${матрица.needs.map { it.code }}")
        assertEquals("ND-0002", матрица.needs.single().code)
        assertEquals("Минтранс", матрица.needs.single().ownerName, "носитель — живая сторона, нужда не ничья")
        assertTrue(матрица.stakeholdersWithoutNeeds.isEmpty(), "снятая сторона в краях матрицы не числится")
    }

    @Test
    fun `сервис спрашивается только у той нужды, что его ждёт`() {
        val заказчик = сторона("SK-0001", "Минтранс")
        val регулятор = сторона("SK-0002", "ГКРЧ", роль = "regulator")
        val своя = нужда("ND-0001", "связь в Арктике", заказчик)
        val частоты = нужда("ND-0002", "частотные присвоения", регулятор)
        покрытие["ND-0002"] = "constraint"
        val цель = store.create("MG-0001", "goal", область, "4",
            mapper.readTree("""{"statement":"развернуть контур","year":2030}"""), провенанс)
        listOf(своя, частоты).forEach { links.link("covers", цель.id, it.id, провенанс) }

        val матрица = постановка.coverage(проект)

        val регуляторская = матрица.needs.single { it.code == "ND-0002" }
        assertTrue(регуляторская.covered, "нужда регулятора закрыта целью: сервиса она не ждёт")
        assertTrue(регуляторская.gap == null && регуляторская.note!!.contains("constraint"), "чем закрывается — сказано: ${регуляторская.note}")
        val заказчикова = матрица.needs.single { it.code == "ND-0001" }
        assertTrue(!заказчикова.covered && заказчикова.gap!!.contains("нет сервиса"), "с той, что ждёт сервиса, он спрашивается")
        assertEquals(mapOf("constraint" to 1), матрица.otherCoverage)
        assertTrue("сервиса не ждут: constraint 1" in матрица.summary, матрица.summary)
    }
}
