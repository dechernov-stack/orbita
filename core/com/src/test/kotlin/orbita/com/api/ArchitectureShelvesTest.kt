// Три полки в проекте (ADR-052): каркас разворачивается деревом, стыки ложатся
// рёбрами МЕЖДУ ЕГО УЗЛАМИ, архитектура садится поверх — функции с
// распределением, цепочки, логические компоненты. Порядок взятия обязателен:
// полка, чьи адреса ещё не заведены, отказывает целиком, а не ложится наполовину.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.CoreType
import orbita.mod.schema.SchemaRegistry
import orbita.mod.store.ObjectStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation::class)
class ArchitectureShelvesTest {
    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val channel = LibraryChannel(boundary)
    private val project = "PJ-2902"
    private lateinit var pbs: String
    private lateinit var interfaces: String
    private lateinit var architecture: String

    private fun полка(файл: String): String {
        val фрагмент = mapper.readTree(
            Files.readString(RepoPaths.repoRoot().resolve("docs/tz/manual-run/packets/$файл")),
        ).path("objects")[0] as ObjectNode
        фрагмент.remove("id")
        return boundary.editing.create(CoreType.LibraryFragment, фрагмент, "test", ObjectStore.LIBRARY_PROJECT).id
    }

    @BeforeAll
    fun setup() {
        TestDb.truncateAll()
        boundary.ingest(
            CoreType.Project,
            """{"id":"$project","name":"Арcadia","phase":"pre_phase_a",
                "milestones":[{"gate":"MCR"},{"gate":"SRR"},{"gate":"SDR"}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", project,
        )
        pbs = полка("18-каркас-pbs.json")
        interfaces = полка("19-интерфейсы.json")
        architecture = полка("20-архитектура-arcadia.json")
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    fun `полка стыков без каркаса отказывает целиком и называет недостающие коды`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            channel.apply(interfaces, project, "инженер")
        }
        assertTrue(e.message!!.contains("которых в проекте нет"), e.message)
        assertTrue(e.message!!.contains("каркас PBS"), "отказ обязан сказать, что брать раньше: ${e.message}")
        assertEquals(0, boundary.objects.listCurrent(project).count { it.type == "interface" },
            "отказ не должен оставить в проекте половину стыков")
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    fun `каркас, стыки и архитектура ложатся друг на друга - мера задания`() {
        assertEquals(135, channel.apply(pbs, project, "инженер", LibraryChannel.TakeOptions(withOptional = true)).created.size)
        assertEquals(26, channel.apply(interfaces, project, "инженер").created.size)
        assertEquals(55, channel.apply(architecture, project, "инженер").created.size)

        val объекты = boundary.objects.listCurrent(project).groupBy { it.type }
        assertEquals(135, объекты.getValue("component").size)
        assertEquals(26, объекты.getValue("interface").size)
        assertEquals(25, объекты.getValue("function").size)
        assertEquals(6, объекты.getValue("function_chain").size)
        assertEquals(9, объекты.getValue("logical_component").size)
        assertEquals(6, объекты.getValue("capability").size)
        assertEquals(9, объекты.getValue("stakeholder").size, "акторы приходят предложением в стейкхолдеры")

        // стык — ребро МЕЖДУ УЗЛАМИ проекта: коды полки разрешены в живые id
        val узлы = объекты.getValue("component").associateBy { it.doc.path("code").asText("") }
        val абонентский = объекты.getValue("interface").first { it.doc.path("code").asText() == "IF-S-USER" }
        assertEquals(
            listOf(узлы.getValue("PL-S").id, узлы.getValue("UT").id),
            абонентский.doc.path("owners").map { it.asText() },
            "стороны стыка обязаны указывать на узлы этого проекта",
        )
        assertTrue(абонентский.doc.path("expects").size() >= 8, "анкета стыка пуста")
        assertEquals("RF", абонентский.doc.path("type").asText())

        // функция распределена на узел, обмен идёт по стыку проекта
        val приём = объекты.getValue("function").first { it.doc.path("code").asText() == "F-01" }
        assertEquals(узлы.getValue("PL-S").id, приём.doc.path("allocated_to")[0].path("component").asText())
        val подтверждение = объекты.getValue("function").first { it.doc.path("code").asText() == "F-02" }
        val обмен = подтверждение.doc.path("exchanges").first()
        assertEquals(абонентский.id, обмен.path("interface").asText(), "обмен обязан идти по стыку проекта")

        // логический компонент развёрнут на узлы, цепочка собрана из функций
        val ПН = объекты.getValue("logical_component").first { it.doc.path("code").asText() == "LC-PAYLOAD" }
        assertTrue(ПН.doc.path("deployed_to").map { it.asText() }.contains(узлы.getValue("PL-S").id))
        val тревога = объекты.getValue("function_chain").first { it.doc.path("code").asText() == "FC-01" }
        assertEquals(7, тревога.doc.path("steps").size())
        assertTrue(тревога.doc.path("capability").asText("").startsWith("OC-"))
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    fun `матрица «функции × узлы» оживает - функций без узла не остаётся`() {
        val матрица = boundary.matrices.functionMatrix(project)
        assertEquals(25, матрица.rows.size)
        assertTrue(матрица.unallocated.isEmpty(), "функции без узла: ${матрица.unallocated}")
        assertTrue(матрица.columns.size >= 161, "столбцы — узлы и стыки: ${матрица.columns.size}")
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    fun `интерфейсное требование висит на стыке, сценарное — на цепочке`() {
        val стык = boundary.objects.listCurrent(project)
            .first { it.type == "interface" && it.doc.path("code").asText() == "IF-S-USER" }
        val цепочка = boundary.objects.listCurrent(project)
            .first { it.type == "function_chain" && it.doc.path("code").asText() == "FC-01" }
        boundary.ingest(
            CoreType.Need,
            """{"id":"ND-0001","statement":"Тревога опасного груза доходит вовремя",
                "stakeholder":{"name":"Перевозчик","role":"end_user","priority":5},
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", project,
        )
        fun требование(id: String, category: String, хвост: String) =
            """{"id":"$id","level":"system","statement":"Система должна выполнять $id надёжно и в срок.",
                "category":"$category","traces_up":[{"ref":"ND-0001"}],"verification_events":[],"owner":"вед. СИ",
                "lifecycle":{"status":"Draft","version":"1"}$хвост}"""
        boundary.ingest(
            CoreType.Requirement,
            требование("RQ-0001", "interface", ""","allocated_to":[{"component":"${стык.id}"}]"""
                .replace("\"component\"", "\"interface\"")),
            "test", project,
        )
        boundary.ingest(CoreType.Requirement, требование("RQ-0002", "operational", ""","realized_by":["${цепочка.id}"]"""), "test", project)

        val покрытие = boundary.matrices.coverageMatrix(project).rows.associateBy { it.requirementId }
        assertEquals("carrier", покрытие.getValue("RQ-0001").kind)
        assertTrue(покрытие.getValue("RQ-0001").covered, "интерфейсное требование покрыто стыком")
        assertTrue(покрытие.getValue("RQ-0001").carriers.contains(стык.id))
        assertTrue(покрытие.getValue("RQ-0002").covered, "сценарное требование покрыто цепочкой")
        assertFalse(boundary.matrices.coverageMatrix(project).uncovered.contains("RQ-0001"),
            "стык — носитель: в «Без носителя» интерфейсное требование не висит")
    }

    @Test
    @org.junit.jupiter.api.Order(5)
    fun `анкета стыка спрашивается у ребра и попадает в общий список запросов`() {
        val анкеты = DataRequests(boundary).ofInterfaces(project)
        assertEquals(26, анкеты.size, "анкет стыков: ${анкеты.size}")
        val абонентская = анкеты.first { it.note!!.startsWith("IF-S-USER") }
        assertTrue(абонентская.note!!.contains("↔"), "в подписи анкеты названы обе стороны: ${абонентская.note}")
        assertTrue(абонентская.fields.none { it.filled }, "поля стыка приходят незаполненными")
        assertTrue(абонентская.fields.any { it.name.contains("запас линка") }, "поля: ${абонентская.fields.map { it.name }}")
    }

    @Test
    @org.junit.jupiter.api.Order(6)
    fun `готовность к SDR спрашивает архитектуру - функции на узлах, требования на цепочках`() {
        val проверки = boundary.gatePassing.readiness("SDR", project).associateBy { it.id }
        val функции = проверки.getValue("functions_allocated")
        assertTrue(функции.blocking, "функция без узла ничего не объясняет — это ворота")
        assertEquals("closed", функции.state, "все 25 функций сели на узлы: ${функции.note}")
        val цепочки = проверки.getValue("chains_requirements")
        assertTrue(цепочки.blocking)
        assertEquals("open", цепочки.state, "требование есть только у FC-01: ${цепочки.note}")
        assertTrue(цепочки.note.startsWith("5 цепочек без требования"), цепочки.note)
    }

    @Test
    @org.junit.jupiter.api.Order(7)
    fun `ICD собирается из анкет и требований стыков, а незаполненное называет незаполненным`() {
        val шаблон = mapper.readTree(
            Files.readString(RepoPaths.repoRoot().resolve("docs/tz/manual-run/packets/21-шаблон-icd.json")),
        ).path("objects")[0] as ObjectNode
        шаблон.remove("id")
        boundary.editing.create(CoreType.DocumentTemplate, шаблон, "test", ObjectStore.LIBRARY_PROJECT)
        val данные = orbita.out.TemplateData.of(шаблон)
        val модель = DocumentModel.model(boundary, project)
        val документ = orbita.out.DocumentGenerator(mapper).render(модель, данные, emptyMap())
        val разделы = документ.body.path("sections").associateBy { it.path("number").asInt() }

        val перечень = разделы.getValue(2).path("items")
        assertEquals(26, перечень.size(), "в перечне стыков: ${перечень.size()}")
        assertTrue(перечень.any { it.path("code").asText() == "IF-S-USER" && it.path("type").asText() == "RF" })

        val параметры = разделы.getValue(3).path("items")
        assertEquals(71, параметры.size(), "полей анкет стыков: ${параметры.size()}")
        assertTrue(параметры.all { it.path("value_missing").asBoolean(false) },
            "значений ещё нет — ICD обязан сказать это, а не промолчать")

        val требования = разделы.getValue(4).path("items")
        assertEquals(1, требования.size(), "требований на стыках: ${требования.size()}")
        assertEquals("RQ-0001", требования[0].path("requirement").asText())

        val вопросы = разделы.getValue(5).path("items")
        assertTrue(вопросы.any { it.path("issue").asText().startsWith("не заполнено полей") })
        assertTrue(вопросы.any { it.path("issue").asText().startsWith("на стык не распределено") })
    }
}
