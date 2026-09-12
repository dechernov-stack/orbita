// Реестр типов связи против истины схем.
//
// Пока типа нет в реестре, связи этого типа не существует: слияние тем по
// `same_as` упирается в отказ «произвольных связей не бывает». А перечень,
// разъехавшийся с enum `link.type`, — это тихая ложь модели: схема разрешает
// то, чего код не заводит. Поэтому сверка перечня здесь, а не в договорённости.
package orbita.kernel

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.kernel.api.Channel
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.Provenance
import orbita.kernel.internal.PgLinkRegistry
import orbita.kernel.schema.GeneratedKinds
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LinkTypesTest {

    private val mapper = ObjectMapper()
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val провенанс = Provenance(Channel.MANUAL, "Иванов И.")

    /** enum `type` вида `link` — из сгенерированной схемы, второго списка нет. */
    private fun типыИстины(): Set<String> {
        val схема = mapper.readTree(TestDbV2.repoRoot.resolve("schemas/v2/link.schema.json").toFile())
        val перечень = схема.path("properties").path("type").path("enum")
        assertTrue(перечень.isArray && перечень.size() > 0, "в схеме link нет enum type — сверять нечего")
        return перечень.map { it.asText() }.toSet()
    }

    @BeforeTest
    fun чисто() = TestDbV2.очистить()

    @Test
    fun `связь same_as заводится - без неё темы не сливаются`() {
        val связь = links.link("same_as", "F-1", "F-2", провенанс)
        assertEquals("same_as", связь.type)
        assertEquals("F-2", links.from("F-1", "same_as").single().to)
        assertEquals(связь.id, links.to("F-2", "same_as").single().id)
    }

    @Test
    fun `каждый тип связи из истины схем принимается реестром`() {
        // Обоснование даём всем — иначе отказ по обоснованию не отличить от
        // отказа по неизвестному типу, а проверяем мы именно перечень.
        val отвергнутые = типыИстины().filter { тип ->
            runCatching {
                links.link(тип, "F-1", "F-2", провенанс, rationale = "сверка перечня типов")
            }.isFailure
        }
        assertTrue(отвергнутые.isEmpty(), "схема разрешает, реестр отказывает: $отвергнутые")
    }

    @Test
    fun `перечень реестра сверен с enum link type`() {
        assertTrue("type" in GeneratedKinds.of("link").fields, "у вида link пропало поле type")

        val истина = типыИстины()
        assertEquals(
            истина,
            PgLinkRegistry.изСхемы,
            "перечень реестра разошёлся с истиной схем: " +
                "нет в реестре ${истина - PgLinkRegistry.изСхемы}, " +
                "нет в схеме ${PgLinkRegistry.изСхемы - истина}",
        )

        // Долг кода перед YAML именуется поимённо и не растёт молча: новый тип
        // заводится сначала в СХЕМЫ-ПОЛЕЙ-V2, а не в белом списке реестра.
        assertEquals(
            setOf("verified_by", "constrains", "instantiates"),
            PgLinkRegistry.внеСхемы,
            "типы вне истины схем перечислены поимённо — список изменился",
        )
        val закрытыйДолг = PgLinkRegistry.внеСхемы.filter { it in истина }
        assertTrue(
            закрытыйДолг.isEmpty(),
            "$закрытыйДолг уже описаны в enum link.type — убрать их из внеСхемы",
        )
        assertEquals(PgLinkRegistry.изСхемы + PgLinkRegistry.внеСхемы, PgLinkRegistry.известные)
    }

    @Test
    fun `связь вне реестра типов не заводится`() {
        val отказ = runCatching { links.link("похоже_на", "F-1", "F-2", провенанс) }.exceptionOrNull()
        assertNotNull(отказ, "связи «похоже_на» в реестре нет — заводить её нельзя")
        assertTrue("реестре связей" in (отказ.message ?: ""), отказ.message ?: "")
        assertTrue(links.from("F-1").isEmpty(), "отказ обязан ничего не записать")
    }

    @Test
    fun `вид уточнения вне перечня не заводится`() {
        val отказ = runCatching {
            links.link("same_as", "F-1", "F-2", провенанс, subtype = "на_глаз")
        }.exceptionOrNull()
        assertNotNull(отказ, "вид уточнения «на_глаз» неизвестен — связь заводить нельзя")
        assertTrue("вид уточнения" in (отказ.message ?: ""), отказ.message ?: "")
    }
}
