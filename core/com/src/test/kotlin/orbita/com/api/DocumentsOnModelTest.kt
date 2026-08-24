// Документы по приложениям регламентов на РЕАЛЬНОЙ базе (TZ-OUT-001, шаг 11.1).
//
// Проверяется не форма документа — она закрыта в core/out, — а то, что срез
// модели действительно доносит до генератора всё, что в модели есть.
//
// Различие, ради которого этот тест существует: раздел «Режимы и состояния»
// пуст либо потому, что режимов нет, либо потому, что их не передали. По самому
// документу это неразличимо. На заполненной базе режимы ЕСТЬ — значит, пустой
// раздел здесь означал бы дефект сборки среза, а не находку проекта.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.schema.SchemaRegistry
import orbita.out.DocumentGenerator
import orbita.out.DocumentTemplate
import orbita.out.ModelSnapshot
import orbita.out.SpacecraftConditions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentsOnModelTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val generator = DocumentGenerator(mapper)

    @BeforeAll
    fun seed() {
        TestDb.truncateAll()
        DemoProject.seed(boundary)
    }

    /** Срез модели ровно тот же, что пойдёт в пакет передачи. */
    private fun snapshot() = ModelSnapshot.of(
        boundary.objects,
        mapper,
        options = boundary.results.activeForScenario(DEMO_SCENARIO, "kpi").map { it.payload },
        budgets = ModelSnapshot.budgetsOf(
            boundary.spacecraft.build(
                boundary.objects.current(DemoProject.DEMO_SPACECRAFT)!!.doc,
                SpacecraftConditions(altKm = 550.0),
            ),
            mapper,
        ),
    )

    @Test
    @DisplayName("§11.1: срез модели несёт входы моделирования, а не только требования")
    fun `срез несёт входы моделирования`() {
        val m = snapshot()
        assertEquals(DemoProject.DEMO_CONSTELLATION, m.path("constellation").path("id").asText())
        assertEquals(DemoProject.DEMO_SPACECRAFT, m.path("spacecraft").path("id").asText())
        assertEquals(DemoProject.DEMO_GROUND_STATIONS, m.path("ground_stations").path("id").asText())
        assertEquals(9, m.path("requirements").size())
        assertEquals(10, m.path("components").size())
    }

    /** Статус берётся из ХРАНИЛИЩА, а не из текста документа. */
    @Test
    @DisplayName("§11.1: запись требования показывает статус хранимого объекта")
    fun `статус в срезе совпадает с хранилищем`() {
        val m = snapshot()
        val stored = boundary.objects.current("RQ-0100")!!
        val inSnapshot = m.path("requirements").first { it.path("id").asText() == "RQ-0100" }
        assertEquals(stored.status.name, inSnapshot.path("lifecycle").path("status").asText())
        assertEquals(stored.version, inSnapshot.path("lifecycle").path("version").asText())
    }

    @Test
    @DisplayName("§11.1: разделы ConOps о режимах и среде заполнены из модели")
    fun `ConOps заполняется входами моделирования`() {
        val doc = generator.render(snapshot(), DocumentTemplate.ConOps)
        val modes = doc.body.path("sections").first { it.path("number").asInt() == 3 }
        assertEquals(3, modes.path("items").size(), "режимы КА не дошли до документа")
        assertTrue(modes.path("items").any { it.path("name").asText() == "downlink" })

        val environment = doc.body.path("sections").first { it.path("number").asInt() == 5 }
        assertTrue(environment.path("items").any { it.path("kind").asText() == "orbit" })
        assertTrue(environment.path("items").any { it.path("kind").asText() == "ground_station" })
        assertTrue(doc.gaps.none { it.section == 3 || it.section == 5 }) { doc.gaps.toString() }
    }

    @Test
    @DisplayName("§11.1: раздел бюджетов описания архитектуры собран из расчёта")
    fun `бюджеты доходят до описания архитектуры`() {
        val doc = generator.render(snapshot(), DocumentTemplate.ArchitectureDescription)
        val budgets = doc.body.path("sections").first { it.path("number").asInt() == 7 }
        val kinds = budgets.path("items").map { it.path("kind").asText() }
        assertTrue(kinds.containsAll(listOf("mass", "power", "tpm"))) { kinds.toString() }
        // резерв показан отдельной величиной, а не растворён в итоге
        val mass = budgets.path("items").first { it.path("kind").asText() == "mass" }
        assertTrue(mass.path("reserve").asDouble() > 0.0) { mass.toString() }
        assertTrue(doc.gaps.none { it.section == 7 }) { doc.gaps.toString() }
    }

    /**
     * Разрывы, которые ОСТАЛИСЬ на полной модели, — находки проекта, а не
     * дефект сборки. Их наличие и есть смысл шага 11.4.
     */
    @Test
    @DisplayName("§11.1: оставшиеся разрывы названы и относятся к модели")
    fun `оставшиеся разрывы относятся к модели`() {
        val spec = generator.render(snapshot(), DocumentTemplate.RequirementSpecification)
        // Введение в демо-проекте не заполнено. Разрыв называет НЕЗАПОЛНЕННОЕ
        // ПОЛЕ, а не «раздел пуст»: проект в модели есть всегда (он контейнер),
        // и инженеру нужно знать, чего именно не хватает, чтобы дописать.
        assertEquals(
            listOf("назначение", "область", "применимые документы"),
            spec.gaps.filter { it.section == 1 }
                .map { it.what.substringAfter('«').substringBefore('»') },
        )
        // обоснование не задано ни у одного требования — это находка к SRR
        assertEquals(9, spec.gaps.count { it.what.contains("обоснование") })

        val conops = generator.render(snapshot(), DocumentTemplate.ConOps)
        // Шаг 17 C1: раздел 4 «Операционные сценарии» больше не вечно пуст —
        // он наполняется хранимыми объектами conops; пустым остался только
        // раздел 6 «Персонал и обеспечение», которого в модели ещё нет
        assertEquals(
            listOf(6),
            conops.gaps.filter { it.what == "раздел пуст" }.map { it.section }.sorted(),
        )
    }

    @Test
    @DisplayName("§11.1: генерация на полной модели воспроизводима и модель не меняет")
    fun `генерация воспроизводима на полной модели`() {
        val model = snapshot()
        val before = model.toString()
        val first = generator.render(model, DocumentTemplate.RequirementSpecification)
        val second = generator.render(model, DocumentTemplate.RequirementSpecification)
        assertEquals(first.digest, second.digest)
        assertEquals(before, model.toString())
    }
}
