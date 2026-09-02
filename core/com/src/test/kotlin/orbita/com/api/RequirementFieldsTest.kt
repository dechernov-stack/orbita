// ADR-045 (шип 2 ночи): требование — полная структура. Поля видны в строке
// реестра и карточке, дерево по документам-основаниям ведёт от раздела к
// требованиям, связь без обоснования не принимается, противоречие без
// разрешения — разрыв, критерий приёмки — помета.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.CoreType
import orbita.mod.schema.SchemaRegistry
import orbita.mod.store.ModelViolationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RequirementFieldsTest {
    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val server = HttpApi(boundary).start(0)
    private val base = "http://127.0.0.1:${server.address.port}/api"
    private val client = HttpClient.newHttpClient()
    private val project = "PJ-2501"

    private fun requirement(id: String, extra: String) = """{"id":"$id","level":"system",
        "statement":"Система должна выполнять требование $id.","category":"functional",
        "traces_up":[{"ref":"ND-0001"}],"verification_events":[],"owner":"вед. СИ",
        "lifecycle":{"status":"Draft","version":"1"}$extra}"""

    @BeforeAll
    fun setup() {
        TestDb.truncateAll()
        boundary.ingest(
            CoreType.Project,
            """{"id":"$project","name":"Полное требование","phase":"pre_phase_a","milestones":[{"gate":"SRR"}],"lifecycle":{"status":"Draft","version":"1"}}""",
            "test", project,
        )
        boundary.ingest(
            CoreType.Need,
            """{"id":"ND-0001","statement":"Нужда в сборе телеметрии","stakeholder":{"name":"Оператор","role":"operator","priority":5},"lifecycle":{"status":"Draft","version":"1"}}""",
            "test", project,
        )
        boundary.ingest(
            CoreType.SourceDocument,
            """{"id":"SD-0001","name":"Техническая записка заказчика","kind":"mission_note","org":"Заказчик","rights":"внутреннее использование по договору","lifecycle":{"status":"Draft","version":"1"}}""",
            "test", project,
        )
        boundary.ingest(CoreType.Requirement, requirement("RQ-0001", ""","title":"Сбор телеметрии","priority":"high",
            "acceptance_criteria":"Телеметрия 10 датчиков принята за сутки без потерь",
            "source":{"doc":"SD-0001","anchor":"s2"},"tags":["телеметрия","IoT"],"comment":"уточнить у заказчика"""" ), "test", project)
        boundary.ingest(CoreType.Requirement, requirement("RQ-0002", ""","title":"Буфер борта","source":{"doc":"SD-0001","anchor":"s2"},
            "relations":[{"ref":"RQ-0001","kind":"refines","rationale":"уточняет объём буфера под сбор телеметрии"}]"""), "test", project)
        boundary.ingest(CoreType.Requirement, requirement("RQ-0003", ""","source":{"doc":"SD-0001","anchor":"s5"},
            "relations":[{"ref":"RQ-0001","kind":"conflicts_with","rationale":"требует отключать приём на витке, что рвёт сбор без потерь"}]"""), "test", project)
    }

    private fun get(path: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create("$base$path" + (if ("?" in path) "&" else "?") + "project=$project")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `строка и карточка несут заголовок, приоритет, критерий, источник с якорем, теги и комментарий`() {
        val row = boundary.screens.requirementTree(project).rows.first { it.id == "RQ-0001" }
        assertEquals("Сбор телеметрии", row.title)
        assertEquals("high", row.priority)
        assertEquals("Телеметрия 10 датчиков принята за сутки без потерь", row.acceptanceCriteria)
        assertEquals("SD-0001", row.sourceDoc?.doc)
        assertEquals("s2", row.sourceDoc?.anchor)
        assertEquals("Техническая записка заказчика", row.sourceDoc?.name)
        assertEquals(listOf("телеметрия", "IoT"), row.tags)
        assertEquals("уточнить у заказчика", row.comment)
        assertFalse(row.noAcceptanceGap)
        val card = mapper.readTree(get("/views/requirements/RQ-0001").body())
        assertEquals("Сбор телеметрии", card.path("row").path("title").asText())
        assertEquals("s2", card.path("row").path("sourceDoc").path("anchor").asText())
    }

    @Test
    fun `дерево по документам ведёт от раздела записки к требованиям`() {
        val tree = mapper.readTree(get("/views/requirement-tree").body())
        val docs = tree.path("documents")
        assertEquals(1, docs.size())
        val doc = docs[0]
        assertEquals("SD-0001", doc.path("doc").asText())
        assertEquals("Техническая записка заказчика", doc.path("name").asText())
        assertEquals(3, doc.path("count").asInt())
        val sections = doc.path("sections").associate { it.path("anchor").asText() to it.path("ids").map { x -> x.asText() } }
        assertEquals(listOf("RQ-0001", "RQ-0002"), sections["s2"])
        assertEquals(listOf("RQ-0003"), sections["s5"])
    }

    @Test
    fun `связь без обоснования не принимается, обоснованная ложится в таблицу связей`() {
        val e = assertThrows(ModelViolationException::class.java) {
            boundary.ingest(CoreType.Requirement, requirement("RQ-0009", ""","relations":[{"ref":"RQ-0001","kind":"derives","rationale":"   "}]"""), "test", project)
        }
        assertTrue(e.message!!.contains("без обоснования"), e.message)
        val self = assertThrows(ModelViolationException::class.java) {
            boundary.ingest(CoreType.Requirement, requirement("RQ-0008", ""","relations":[{"ref":"RQ-0008","kind":"conflicts_with","rationale":"на себя"}]"""), "test", project)
        }
        assertTrue(self.message!!.contains("само себя"), self.message)
        val derive = boundary.links.linksTo("RQ-0002", "derive").single()
        assertEquals("RQ-0001", derive.fromId)
        assertEquals("derived", derive.derivationKind)
        assertEquals("уточняет объём буфера под сбор телеметрии", derive.rationale)
        val conflict = boundary.links.linksFrom("RQ-0003", "conflict").single()
        assertEquals("RQ-0001", conflict.toId)
    }

    @Test
    fun `противоречие без разрешения — разрыв, без критерия приёмки — помета`() {
        val rows = boundary.screens.requirementTree(project).rows.associateBy { it.id }
        assertTrue(rows.getValue("RQ-0003").conflictOpen)
        assertFalse(rows.getValue("RQ-0002").conflictOpen)
        assertTrue(rows.getValue("RQ-0002").noAcceptanceGap)
        // готовность к SRR: противоречие держит ворота, критерий приёмки — помета
        val readiness = mapper.readTree(get("/views/gate-readiness?gate=SRR").body())
        val checks = readiness.path("groups").flatMap { g -> g.path("checks").toList() }
        fun check(id: String) = checks.first { it.path("id").asText() == id }
        assertEquals("open", check("conflicts").path("state").asText())
        assertTrue(check("conflicts").path("blocking").asBoolean())
        assertEquals("open", check("acceptance").path("state").asText())
        assertFalse(check("acceptance").path("blocking").asBoolean())
    }
}
