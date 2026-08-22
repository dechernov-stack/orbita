// Связи выводятся из документа при каждой записи (ADR-027, шаг 16 §3.1) —
// один в один с spec/link_semantics.py, на настоящем хранилище.
package orbita.req

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.schema.SchemaRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LinkSyncTest {

    private val mapper = ObjectMapper()
    private val req = ReqService(TestDb.conn, SchemaRegistry(RepoPaths.schemasDir()))

    @BeforeEach
    fun reset() {
        TestDb.truncateAll()
        req.objects.create("ND-0801", "need", doc("ND-0801"))
        req.objects.create("SV-0801", "service", doc("SV-0801"))
        req.objects.create("CM-0801", "component", doc("CM-0801"))
        req.objects.create("RQ-0801", "requirement", doc("RQ-0801"))
        req.objects.create("RQ-0802", "requirement", doc("RQ-0802"))
    }

    private fun doc(id: String): ObjectNode = mapper.createObjectNode().put("id", id)

    @Test
    fun `правкой добавили derives_from — родитель виден, правкой убрали — связь исчезла`() {
        req.syncLinks("requirement", "RQ-0802", doc("RQ-0802").apply { putArray("derives_from").add("RQ-0801") })
        assertEquals(listOf("RQ-0801"), req.links.linksTo("RQ-0802", "derive").map { it.fromId })

        req.syncLinks("requirement", "RQ-0802", doc("RQ-0802"))
        assertTrue(req.links.linksTo("RQ-0802", "derive").isEmpty())
    }

    @Test
    fun `deriveAs переживает правку постороннего поля`() {
        val withParent = doc("RQ-0802").apply { putArray("derives_from").add("RQ-0801") }
        req.syncLinks("requirement", "RQ-0802", withParent)
        req.deriveAs("RQ-0801", "RQ-0802", "derived")

        req.syncLinks("requirement", "RQ-0802", (withParent.deepCopy() as ObjectNode).put("statement", "новая"))
        // удаление и вставка молча вернули бы 'allocated' — и производное
        // требование вошло бы в свёртку бюджета
        assertEquals("derived", req.links.linksTo("RQ-0802", "derive").single().derivationKind)
    }

    @Test
    fun `атрибуты распределения правятся на месте`() {
        fun allocated(kind: String, rationale: String?) = doc("RQ-0801").apply {
            putArray("allocated_to").addObject().apply {
                put("component", "CM-0801")
                put("kind", kind)
                rationale?.let { put("rationale", it) }
            }
        }
        req.syncLinks("requirement", "RQ-0801", allocated("full", null))
        req.syncLinks("requirement", "RQ-0801", allocated("partial", "делит массу"))
        val link = req.links.linksFrom("RQ-0801", "allocation").single()
        assertEquals("partial", link.allocationKind)
        assertEquals("делит массу", link.rationale)
    }

    @Test
    fun `двусторонний trace живёт, пока его объявляет хотя бы один документ`() {
        val nd = doc("ND-0801").apply { putArray("traces_down").add("SV-0801") }
        req.objects.change("ND-0801", nd)
        req.syncLinks("need", "ND-0801", nd)

        // сервис ссылку не объявляет — но нужда объявляет: связь жива
        req.syncLinks("service", "SV-0801", doc("SV-0801").apply { putArray("traces_up") })
        assertEquals(1, req.links.linksTo("SV-0801", "trace").size)

        // нужда убрала ссылку — не объявляет никто: связь удалена
        val ndEmpty = doc("ND-0801").apply { putArray("traces_down") }
        req.objects.change("ND-0801", ndEmpty)
        req.syncLinks("need", "ND-0801", ndEmpty)
        assertTrue(req.links.linksTo("SV-0801", "trace").isEmpty())
    }

    @Test
    fun `verification-связь пересчётом не затронута`() {
        req.objects.create("EV-0801", "evidence", doc("EV-0801"))
        req.links.add("RQ-0801", "EV-0801", "verification")
        req.syncLinks("requirement", "RQ-0801", doc("RQ-0801"))
        assertEquals(1, req.links.linksFrom("RQ-0801", "verification").size)
    }
}
