// Решение Б3-01 ред. 2: данные полки полны, ВЫБОР — у инженера при взятии.
// Окно взятия показывает рекомендованный набор, взятое, «зачем» и зависимости
// по ссылкам; зависимость с другой полки — предложение довзять тем же
// подтверждением, не отказ. Повторное открытие полки — добор, идемпотентно;
// снятие отметки — отмена с историей. Отказ — только для настоящей ошибки.
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
class SelectionTest {
    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val channel = LibraryChannel(boundary)
    private val project = "PJ-2904"
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

    private fun коды(тип: String): List<String> = boundary.objects.listCurrent(project)
        .filter { it.type == тип && it.status != orbita.mod.model.Lifecycle.Cancelled }
        .map { it.doc.path("code").asText("") }

    private fun элемент(окно: com.fasterxml.jackson.databind.JsonNode, код: String) =
        окно.path("elements").first { it.path("code").asText() == код }

    private fun идПоКоду(fragment: String, коды: Set<String>): Set<String> =
        boundary.objects.current(fragment)!!.doc.path("payload").path("objects")
            .filter { it.path("code").asText("") in коды }.map { it.path("id").asText() }.toSet()

    @BeforeAll
    fun setup() {
        TestDb.truncateAll()
        boundary.ingest(
            CoreType.Project,
            """{"id":"$project","name":"Выбор элементов","phase":"pre_phase_a",
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
    fun `окно взятия каркаса - рекомендованный набор класса, вне него пять узлов, взятого нет`() {
        val окно = channel.takeWindow(pbs, project)
        assertEquals(135, окно.path("summary").path("total").asInt())
        assertEquals(130, окно.path("summary").path("recommended").asInt())
        assertEquals(0, окно.path("summary").path("taken").asInt())
        val вне = окно.path("elements").filter { !it.path("default_take").asBoolean() }.map { it.path("code").asText() }.toSet()
        assertEquals(setOf("PL-ISL", "PL-PNT", "PL-P", "MCC-SIM", "SC-EXP"), вне)
        // «от него зависит» — по ссылкам других полок: стык, функция, компонент, пакет
        val isl = элемент(окно, "PL-ISL")
        val зависимые = isl.path("needed_by").map { it.path("code").asText() }.toSet()
        assertTrue(зависимые.containsAll(setOf("IF-ISL", "F-22", "LC-ISL", "05.05"))) { "от PL-ISL зависит: $зависимые" }
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    fun `каркас рекомендованным набором - 130 узлов, USR-APP среди них`() {
        val взятие = channel.apply(pbs, project, "инженер")
        assertEquals(130, взятие.created.size)
        assertTrue("USR-APP" in коды("component"))
        assertFalse("PL-ISL" in коды("component"))
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    fun `стыки - IF-ISL серый с предложением взять узел PL-ISL из каркаса, рекомендованный набор берётся без отказа`() {
        val окно = channel.takeWindow(interfaces, project)
        val isl = элемент(окно, "IF-ISL")
        assertFalse(isl.path("default_take").asBoolean())
        val нужда = isl.path("needs").first { it.path("code").asText() == "PL-ISL" }
        assertFalse(нужда.path("in_project").asBoolean())
        assertEquals(pbs, нужда.path("shelf").asText()) { "зависимость лежит на полке каркаса — предложение, не ошибка" }
        // рекомендованный набор: 24 стыка, ни одного отказа
        val взятие = channel.apply(interfaces, project, "инженер")
        assertEquals(24, взятие.created.size)
        assertFalse("IF-ISL" in коды("interface"))
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    fun `выбрать IF-ISL без узла - отказ называет элемент и полку - с довзятием - стык и узел берутся вместе`() {
        val id = идПоКоду(interfaces, setOf("IF-ISL"))
        val e = assertThrows(IllegalArgumentException::class.java) {
            channel.apply(interfaces, project, "инженер", LibraryChannel.TakeOptions(select = id))
        }
        assertTrue(e.message!!.contains("«IF-ISL» требует «PL-ISL»") && e.message!!.contains("тем же подтверждением")) { e.message }
        assertFalse("IF-ISL" in коды("interface")) { "отказ идёт до первой записи" }
        val взятие = channel.apply(interfaces, project, "инженер",
            LibraryChannel.TakeOptions(select = id, extras = mapOf(pbs to setOf("PL-ISL"))))
        assertEquals(2, взятие.created.size) { "узел и стык одним подтверждением: ${взятие.created}" }
        assertTrue("PL-ISL" in коды("component") && "IF-ISL" in коды("interface"))
    }

    @Test
    @org.junit.jupiter.api.Order(5)
    fun `архитектура и WBS - рекомендованным набором без отказов, записи ISL ждут выбора`() {
        assertEquals(51, channel.apply(architecture, project, "инженер").created.size)
        assertFalse("F-22" in коды("function"))
        assertEquals(49, channel.apply(wbs, project, "инженер").created.size)
        assertFalse("05.05" in коды("wbs_element"))
        assertTrue(boundary.matrices.functionMatrix(project).unallocated.isEmpty())
        // в окне архитектуры F-22 требует IF-ISL — теперь он в проекте
        val окно = channel.takeWindow(architecture, project)
        assertEquals(51, окно.path("summary").path("taken").asInt())
        val f22 = элемент(окно, "F-22")
        assertTrue(f22.path("needs").all { it.path("in_project").asBoolean() }) { "зависимости F-22: ${f22.path("needs")}" }
    }

    @Test
    @org.junit.jupiter.api.Order(6)
    fun `открыть полку снова - взятое отмечено, невзятое выбрать одним подтверждением - повтор не плодит`() {
        // FC-06 требует F-22 из этой же полки: одна без другой не берётся
        val толькоЦепочка = идПоКоду(architecture, setOf("FC-06"))
        val e = assertThrows(IllegalArgumentException::class.java) {
            channel.apply(architecture, project, "инженер", LibraryChannel.TakeOptions(select = толькоЦепочка))
        }
        assertTrue(e.message!!.contains("«FC-06» требует «F-22»")) { e.message }
        val взятие = channel.apply(architecture, project, "инженер",
            LibraryChannel.TakeOptions(select = идПоКоду(architecture, setOf("F-22", "FC-06", "LC-ISL"))))
        assertEquals(3, взятие.created.size) { взятие.created.toString() }
        val f22 = boundary.objects.listCurrent(project).first { it.type == "function" && it.doc.path("code").asText() == "F-22" }
        val стыкISL = boundary.objects.listCurrent(project).first { it.type == "interface" && it.doc.path("code").asText() == "IF-ISL" }
        assertEquals(стыкISL.id, f22.doc.path("exchanges")[0].path("interface").asText()) { "обмен EX-ISL идёт по стыку проекта" }
        // WBS: пакет 05.05 на PL-ISL — теперь берётся; повтор рекомендованного — ноль
        assertEquals(1, channel.apply(wbs, project, "инженер", LibraryChannel.TakeOptions(select = идПоКоду(wbs, setOf("05.05")))).created.size)
        val повтор = channel.apply(wbs, project, "инженер")
        assertEquals(0, повтор.created.size)
        assertEquals(50, повтор.existing.size)
        assertEquals(1, коды("interface").count { it == "IF-ISL" })
    }

    @Test
    @org.junit.jupiter.api.Order(7)
    fun `снять отметку - отмена с историей - настоящая ошибка - код, которого нет нигде`() {
        val снятие = channel.apply(wbs, project, "инженер", LibraryChannel.TakeOptions(select = emptySet(), unselect = идПоКоду(wbs, setOf("05.05"))))
        assertEquals(1, снятие.removed.size)
        assertFalse("05.05" in коды("wbs_element"))
        val окно = channel.takeWindow(wbs, project)
        assertEquals("", элемент(окно, "05.05").path("taken").asText())
        // сломанная пачка: ссылка на код, которого нет ни в проекте, ни на полках
        val порченая = mapper.readTree(Files.readString(RepoPaths.repoRoot().resolve("docs/tz/manual-run/packets/19-интерфейсы.json")))
            .path("objects")[0] as ObjectNode
        порченая.remove("id")
        val стык = порченая.path("payload").path("objects")[0] as ObjectNode
        стык.putArray("owners").add("@НЕТ-ТАКОГО").add("@UT")
        val битая = boundary.editing.create(CoreType.LibraryFragment, порченая, "test", ObjectStore.LIBRARY_PROJECT).id
        val e = assertThrows(IllegalArgumentException::class.java) {
            channel.apply(битая, project, "инженер", LibraryChannel.TakeOptions(select = setOf(стык.path("id").asText())))
        }
        assertTrue(e.message!!.contains("нет ни в проекте, ни на полках")) { e.message }
    }
}
