// Замечание Б3-01: зависимость от НЕОБЯЗАТЕЛЬНОГО узла не блокирует полку.
// Каркас взят без подтверждения опциональных узлов — стыки, архитектура и WBS
// берутся с пропусками и пометой «не подтверждён ‹узел›»; подтверждение узла
// позже даёт добор: повторное взятие создаёт только теперь разрешимое,
// идемпотентно. Отказ остаётся для настоящей причины — «полка не взята».
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.CoreType
import orbita.mod.schema.SchemaRegistry
import orbita.mod.store.ObjectStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation::class)
class OptionalNodesTest {
    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val channel = LibraryChannel(boundary)
    private val project = "PJ-2903"
    private lateinit var pbs: String
    private lateinit var interfaces: String
    private lateinit var architecture: String
    private lateinit var wbs: String

    private fun полка(файл: String): String {
        val фрагмент = mapper.readTree(
            Files.readString(RepoPaths.repoRoot().resolve("docs/tz/manual-run/packets/$файл")),
        ).path("objects")[0] as ObjectNode
        фрагмент.remove("id")
        return boundary.editing.create(CoreType.LibraryFragment, фрагмент, "test", ObjectStore.LIBRARY_PROJECT).id
    }

    private fun коды(тип: String): Set<String> = boundary.objects.listCurrent(project)
        .filter { it.type == тип && it.status != orbita.mod.model.Lifecycle.Cancelled }
        .map { it.doc.path("code").asText("") }.toSet()

    @BeforeAll
    fun setup() {
        TestDb.truncateAll()
        boundary.ingest(
            CoreType.Project,
            """{"id":"$project","name":"Опциональные узлы","phase":"pre_phase_a",
                "milestones":[{"gate":"MCR"},{"gate":"SRR"},{"gate":"SDR"}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", project,
        )
        pbs = полка("18-каркас-pbs.json")
        interfaces = полка("19-интерфейсы.json")
        architecture = полка("20-архитектура-arcadia.json")
        wbs = полка("22-wbs.json")
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    fun `без каркаса причина отказа - полка не взята, а не узлы`() {
        val e = assertThrows(IllegalArgumentException::class.java) { channel.apply(interfaces, project, "инженер") }
        assertTrue(e.message!!.contains("полка не взята")) { e.message }
        assertTrue(e.message!!.contains("Каркас PBS") || e.message!!.contains("PBS")) { e.message }
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    fun `каркас без подтверждения - опциональные узлы в пометах, а не молча`() {
        val взятие = channel.apply(pbs, project, "инженер")
        assertEquals(130, взятие.created.size) { "135 узлов минус пять необязательных" }
        assertEquals(setOf("MCC-SIM", "PL-ISL", "PL-P", "PL-PNT", "SC-EXP"), взятие.skipped.map { it.code }.toSet())
        assertTrue("USR-APP" in коды("component")) { "USR-APP обязателен и заведён" }
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    fun `стыки берутся с пропуском двух, не подтверждены PL-ISL и PL-PNT`() {
        val взятие = channel.apply(interfaces, project, "инженер")
        assertEquals(24, взятие.created.size) { "стыков создано: ${взятие.created.size}" }
        assertEquals(listOf("IF-ISL", "IF-PNT-USER"), взятие.skipped.map { it.code }.sorted())
        assertTrue(взятие.skipped.all { it.reason.startsWith("пропущено: не подтверждён") }) { взятие.skipped.toString() }
        assertEquals(setOf("PL-ISL", "PL-PNT"), взятие.skipped.flatMap { it.on }.toSet())
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    fun `архитектура и WBS берутся с пропусками - каскад по optional_on, не по догадке`() {
        val арх = channel.apply(architecture, project, "инженер")
        assertEquals(51, арх.created.size) { "архитектура: ${арх.created.size}; пропущено ${арх.skipped.map { it.code }}" }
        assertEquals(setOf("F-22", "F-23", "FC-06", "LC-ISL"), арх.skipped.map { it.code }.toSet())
        val работы = channel.apply(wbs, project, "инженер")
        assertEquals(49, работы.created.size) { "WBS: ${работы.created.size}" }
        assertEquals(setOf("05.02", "05.05", "05.06", "07.05", "10.03"), работы.skipped.map { it.code }.toSet())
        // матрица «функции × узлы» живёт, функций без узла нет и без опциональных
        assertTrue(boundary.matrices.functionMatrix(project).unallocated.isEmpty())
    }

    @Test
    @org.junit.jupiter.api.Order(5)
    fun `подтверждение PL-ISL в дереве и добор - создаётся только разрешимое, повтор даёт ноль`() {
        // дерево называет, что подтверждать
        val ждут = boundary.carriers.tree(project).path("pending_optional").map { it.path("code").asText() }
        assertEquals(listOf("MCC-SIM", "PL-ISL", "PL-P", "PL-PNT", "SC-EXP"), ждут.sorted())
        val узел = channel.confirmOptional(pbs, project, setOf("PL-ISL"), "инженер")
        assertEquals(1, узел.created.size) { "подтверждение заводит ровно узел: ${узел.created}" }
        assertTrue("PL-ISL" in коды("component"))
        // добор: только записи, ставшие разрешимыми
        val добор = channel.topUp(project, "инженер")
        val создано = добор.sumOf { it.created }
        val чтоДобрано = добор.flatMap { it.createdIds }.map { id ->
            boundary.objects.current(id)?.let { "${it.type}:${it.doc.path("code").asText(it.doc.path("name").asText(""))}" } ?: id
        }
        assertEquals(5, создано) { "добор: $чтоДобрано" }
        assertTrue("IF-ISL" in коды("interface"))
        assertTrue("F-22" in коды("function"))
        assertTrue("FC-06" in коды("function_chain"))
        assertTrue("LC-ISL" in коды("logical_component"))
        assertTrue("05.05" in коды("wbs_element"))
        // обмен EX-ISL пришёл вместе с функцией F-22 и идёт по стыку проекта
        val f22 = boundary.objects.listCurrent(project).first { it.type == "function" && it.doc.path("code").asText() == "F-22" }
        val стыкISL = boundary.objects.listCurrent(project).first { it.type == "interface" && it.doc.path("code").asText() == "IF-ISL" }
        assertEquals(стыкISL.id, f22.doc.path("exchanges")[0].path("interface").asText())
        // повторный добор — ноль новых, взятое на месте
        val ещё = channel.topUp(project, "инженер")
        assertEquals(0, ещё.sumOf { it.created }) { "повтор: $ещё" }
        assertEquals(1, коды("interface").count { it == "IF-ISL" })
        // дерево больше не ждёт PL-ISL, остальные четыре — ждут
        val после = boundary.carriers.tree(project).path("pending_optional").map { it.path("code").asText() }
        assertEquals(listOf("MCC-SIM", "PL-P", "PL-PNT", "SC-EXP"), после.sorted())
    }
}
