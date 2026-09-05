// Ядро v2: сущность знает сцену рождения, правка не затирает прошлое,
// связь без обоснования там, где оно требуется, не заводится.
package orbita.kernel

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Provenance
import orbita.kernel.internal.GeneratedSchemaRegistry
import orbita.kernel.internal.PgEntityStore
import orbita.kernel.internal.PgLinkRegistry
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KernelTest {

    private val mapper = ObjectMapper()
    private val store = PgEntityStore(TestDbV2.conn, mapper)
    private val links = PgLinkRegistry(TestDbV2.conn)
    private val провенанс = Provenance(Channel.MANUAL, "Иванов И.")

    @BeforeTest
    fun чисто() = TestDbV2.очистить()

    @Test
    fun `сущность знает сцену рождения и область`() {
        val нужда = store.create(
            code = "ND-0001",
            kind = "need",
            area = Area.Project("PJ-0001"),
            bornIn = "3",
            doc = mapper.readTree("""{"statement":"перевозчику нужна телеметрия груза"}"""),
            provenance = провенанс,
        )
        assertEquals("3", нужда.bornIn, "сцена рождения обязана храниться, а не восстанавливаться")
        assertEquals(Area.Project("PJ-0001"), нужда.area)
        assertEquals(1, нужда.version)
        assertEquals(Channel.MANUAL, нужда.provenance.channel)
    }

    @Test
    fun `правка заводит новую версию, прежняя остаётся историей`() {
        val создана = store.create(
            "ND-0002", "need", Area.Project("PJ-0001"), "3",
            mapper.readTree("""{"statement":"первая формулировка"}"""), провенанс,
        )
        val правленая = store.update(
            создана.id,
            mapper.readTree("""{"statement":"уточнённая формулировка"}"""),
            Provenance(Channel.MANUAL, "Петрова М."),
        )
        assertEquals(2, правленая.version)
        val история = store.history(создана.id)
        assertEquals(2, история.size, "прошлая версия обязана остаться: ${история.map { it.version }}")
        assertEquals("первая формулировка", история[0].doc.path("statement").asText())
        assertEquals("Петрова М.", правленая.provenance.author, "автор правки — тот, кто правил")
    }

    @Test
    fun `связь без обоснования там, где оно требуется, не заводится`() {
        val требование = store.create(
            "RQ-0001", "requirement", Area.Project("PJ-0001"), "8",
            mapper.readTree("""{"statement":"система должна передавать телеметрию"}"""), провенанс,
        )
        val нужда = store.create(
            "ND-0003", "need", Area.Project("PJ-0001"), "3",
            mapper.readTree("""{"statement":"нужда"}"""), провенанс,
        )
        val отказ = runCatching {
            links.link("derives_from", требование.id, нужда.id, провенанс)
        }.exceptionOrNull()
        assertNotNull(отказ, "связь деривации без обоснования обязана отказать")
        assertTrue("обоснования" in (отказ.message ?: ""), отказ.message ?: "")

        val связь = links.link(
            "derives_from", требование.id, нужда.id, провенанс,
            rationale = "требование выведено из нужды перевозчика",
        )
        assertEquals(1, links.from(требование.id).size)
        assertEquals(связь.id, links.to(нужда.id).single().id)
    }

    @Test
    fun `связь вне реестра не заводится`() {
        val отказ = runCatching {
            links.link("выдуманная_связь", "a", "b", провенанс)
        }.exceptionOrNull()
        assertNotNull(отказ, "произвольных связей не бывает")
        assertTrue("реестре связей" in (отказ.message ?: ""), отказ.message ?: "")
    }

    @Test
    fun `схема вида проверяет документ, вид вне истины схем не существует`() {
        val реестр = GeneratedSchemaRegistry(TestDbV2.repoRoot.resolve("schemas/v2"))
        assertTrue(реестр.kinds().size >= 86, "видов в реестре: ${реестр.kinds().size}")
        assertTrue("requirement" in реестр.kinds())

        val отказ = runCatching { реестр.problems("выдуманный_вид", mapper.createObjectNode()) }.exceptionOrNull()
        assertNotNull(отказ, "вид вне истины схем не существует")
    }
}
