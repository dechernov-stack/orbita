// Три живых шаблона (ADR-050, ADR-051): каркас PBS ред. 2 разворачивается в
// дерево носителей и приносит анкету НА УЗЕЛ; запись модели спрашивает ответ,
// а не файл; покрытие требования считается по его категории.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.CoreType
import orbita.mod.schema.SchemaRegistry
import orbita.mod.store.ModelViolationException
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
class TemplatesTest {
    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val channel = LibraryChannel(boundary)
    private val project = "PJ-2901"
    private lateinit var fragment: String
    private lateinit var models: String

    @BeforeAll
    fun setup() {
        TestDb.truncateAll()
        boundary.ingest(
            CoreType.Project,
            """{"id":"$project","name":"Каркас","phase":"pre_phase_a","milestones":[{"gate":"MCR"},{"gate":"SRR"},{"gate":"SDR"}],"lifecycle":{"status":"Draft","version":"1"}}""",
            "test", project,
        )
        // каркас и набор моделей — те же пакеты полки, что везёт сид стенда
        val skeleton = mapper.readTree(
            Files.readString(RepoPaths.repoRoot().resolve("docs/tz/manual-run/packets/18-каркас-pbs.json")),
        ).path("objects")[0] as com.fasterxml.jackson.databind.node.ObjectNode
        skeleton.remove("id")
        fragment = boundary.editing.create(CoreType.LibraryFragment, skeleton, "test", ObjectStore.LIBRARY_PROJECT).id
        val modelSet = mapper.readTree(
            Files.readString(RepoPaths.repoRoot().resolve("docs/tz/manual-run/packets/17-модели-системы.json")),
        ).path("objects")[0] as com.fasterxml.jackson.databind.node.ObjectNode
        modelSet.remove("id")
        models = boundary.editing.create(CoreType.LibraryFragment, modelSet, "test", ObjectStore.LIBRARY_PROJECT).id
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    fun `каркас разворачивается в дерево - шесть сегментов, КА до агрегатов, анкета на узле`() {
        val outcome = channel.apply(fragment, project, "инженер", LibraryChannel.TakeOptions(depth = 4))
        assertTrue(outcome.created.size > 60, "взято узлов: ${outcome.created.size}")
        val nodes = boundary.objects.listCurrent(project).filter { it.type == "component" }
        assertEquals(6, nodes.count { it.doc.path("kind").asText() == "segment" }, "сегментов: " +
            nodes.filter { it.doc.path("kind").asText() == "segment" }.map { it.doc.path("code").asText() })
        // КА с платформой и полезной нагрузкой до уровня агрегатов
        val sc = nodes.first { it.doc.path("code").asText() == "SC" }
        val children = nodes.filter { it.doc.path("parent").asText() == sc.id }.map { it.doc.path("code").asText() }
        assertTrue(children.containsAll(listOf("SC-PLT", "SC-PL")), "дети КА: $children")
        val platform = nodes.first { it.doc.path("code").asText() == "SC-PLT" }
        val units = nodes.filter { it.doc.path("parent").asText() == platform.id }
        assertTrue(units.map { it.doc.path("code").asText() }.containsAll(listOf("EPS", "AOCS", "PROP")), "подсистемы платформы: " +
            units.map { it.doc.path("code").asText() })
        // анкета живёт НА УЗЛЕ: поля СЭП спрашиваются у СЭП
        val eps = units.first { it.doc.path("code").asText() == "EPS" }
        assertTrue(eps.doc.path("expects").size() > 0, "анкета узла СЭП пуста")
        val requests = DataRequests(boundary).ofNodes(project)
        val epsRequest = requests.first { it.target == eps.id }
        assertTrue(epsRequest.fields.isNotEmpty() && epsRequest.fields.none { it.filled },
            "поля анкеты узла: ${epsRequest.fields.map { it.key }}")
        // глубина: блоков (уровень 5) при depth=4 не берётся
        assertEquals(0, nodes.count { it.doc.path("level").asInt(0) > 4 }, "уровни глубже L4 при взятии до L4 не берутся")
        // необязательные узлы без подтверждения не заводятся
        assertFalse(nodes.any { it.doc.path("optional").asBoolean(false) })
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    fun `повторное взятие каркаса не плодит дубли - узел сопоставляется по коду`() {
        val matches = channel.matches(fragment, project)
        assertTrue(matches.any { it.first == "SC" }, "совпадений: ${matches.size}")
        val before = boundary.objects.listCurrent(project).count { it.type == "component" }
        val mapping = matches.associate { it.first to it.third }
        val outcome = channel.apply(fragment, project, "инженер", LibraryChannel.TakeOptions(depth = 4, mapping = mapping))
        assertTrue(outcome.created.isEmpty() || outcome.existing.isNotEmpty(), "повтор не должен плодить дубли")
        val after = boundary.objects.listCurrent(project).count { it.type == "component" }
        assertEquals(before, after, "число узлов после повторного взятия изменилось")
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    fun `модель отвечает выходом с датой, прокси помечен, вход ведёт в анкету`() {
        val node = boundary.objects.listCurrent(project).first { it.doc.path("code").asText() == "EPS" }
        boundary.ingest(
            CoreType.SystemModel,
            """{"id":"SM-0001","code":"М4","name":"Энергетика","question":"хватает ли энергии на витке",
                "status":"not_built","due_gate":"SRR",
                "inputs":[{"node":"${node.id}","param":"sa_power"}],
                "outputs":[],"lifecycle":{"status":"Draft","version":"1"}}""",
            "test", project,
        )
        val gaps = boundary.systemModels.gaps(project, "SRR")
        assertTrue(gaps.any { it.what.startsWith("модель не дала ответа") && it.place == "models" },
            "разрыв «нет ответа»: $gaps")
        assertTrue(gaps.any { it.what.startsWith("вход модели не задан") && it.place == "datarequests" },
            "разрыв «вход не задан» ведёт в анкету: $gaps")
        // прокси без пометки на выходе не принимается — это витрина
        val e = assertThrows(ModelViolationException::class.java) {
            boundary.ingest(
                CoreType.SystemModel,
                """{"id":"SM-0002","code":"М5","name":"Тепло","question":"не выйдем ли за диапазоны","status":"proxy",
                    "outputs":[{"name":"баланс","at":"2026-09-03"}],"lifecycle":{"status":"Draft","version":"1"}}""",
                "test", project,
            )
        }
        assertTrue(e.message!!.contains("витрина"), e.message)
        val view = boundary.systemModels.view(project, "SRR")
        assertEquals(1, view.path("models").size())
        assertFalse(view.path("models")[0].path("answered").asBoolean())
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    fun `покрытие считается по категории требования, иллюстрация покрытием не бывает`() {
        boundary.ingest(
            CoreType.Need,
            """{"id":"ND-0001","statement":"Сбор телеметрии в районах без наземной связи","stakeholder":{"name":"Оператор","role":"operator","priority":5},"lifecycle":{"status":"Draft","version":"1"}}""",
            "test", project,
        )
        val sc = boundary.objects.listCurrent(project).first { it.doc.path("code").asText() == "SC" }
        boundary.ingest(
            CoreType.Function,
            """{"id":"FN-0001","name":"сбор телеметрии","traces_up":[{"ref":"ND-0001"}],
                "allocated_to":[{"component":"${sc.id}"}],"lifecycle":{"status":"Draft","version":"1"}}""",
            "test", project,
        )
        boundary.ingest(
            CoreType.FunctionChain,
            """{"id":"FC-0001","name":"суточный сбор и сброс","steps":[{"function":"FN-0001"}],
                "traces_up":["ND-0001"],"lifecycle":{"status":"Draft","version":"1"}}""",
            "test", project,
        )
        boundary.ingest(
            CoreType.ModelElement,
            """{"id":"ME-0001","source_tool":"capella","model_id":"fixture","uuid":"fixture-diagram-lab","type":"Diagram","layer":"LA",
                "name":"Логическая архитектура (LAB)","fixture":true,"lifecycle":{"status":"Draft","version":"1"}}""",
            "test", project,
        )
        fun req(id: String, category: String, extra: String) =
            """{"id":"$id","level":"system","statement":"Система должна выполнять $id надёжно и в срок.","category":"$category",
                "traces_up":[{"ref":"ND-0001"}],"verification_events":[],"owner":"вед. СИ",
                "lifecycle":{"status":"Draft","version":"1"}$extra}"""
        boundary.ingest(CoreType.Requirement, req("RQ-0001", "functional", ""","satisfied_by":["FN-0001"]"""), "test", project)
        boundary.ingest(CoreType.Requirement, req("RQ-0002", "operational", ""","illustrated_by":["ME-0001"]"""), "test", project)
        boundary.ingest(CoreType.Requirement, req("RQ-0003", "operational", ""","realized_by":["FC-0001"]"""), "test", project)
        val m = boundary.matrices.coverageMatrix(project).rows.associateBy { it.requirementId }
        assertEquals("function", m.getValue("RQ-0001").kind)
        assertTrue(m.getValue("RQ-0001").covered)
        // сценарное требование с одной иллюстрацией не покрыто: диаграмма не доказательство
        assertEquals("chain", m.getValue("RQ-0002").kind)
        assertFalse(m.getValue("RQ-0002").covered, "иллюстрация покрытием не бывает")
        assertTrue(m.getValue("RQ-0003").covered)
        assertEquals(listOf("RQ-0002"), boundary.matrices.coverageMatrix(project).uncovered)
    }

    @Test
    @org.junit.jupiter.api.Order(5)
    fun `набор моделей берётся с полки в проект - четырнадцать записей, линки и потоки частями`() {
        val outcome = channel.apply(models, project, "инженер")
        assertEquals(14, outcome.created.size, "взято записей моделей: ${outcome.created.size}")
        val records = boundary.objects.listCurrent(project)
            .filter { it.type == "system_model" && it.doc.path("code").asText("").startsWith("М") }
            .associateBy { it.doc.path("code").asText() }
        assertTrue(records.keys.containsAll((1..14).map { "М$it" }), "коды набора: ${records.keys.sorted()}")
        // ответ дают четыре расчёта, остальные честно «не построена» или «прокси»
        assertEquals(4, records.values.count { it.doc.path("status").asText() == "computed" },
            "расчётов: " + records.values.filter { it.doc.path("status").asText() == "computed" }.map { it.doc.path("code").asText() })
        // линки М2а–г и потоки М3а–е живут частями записи, а не отдельными моделями
        assertEquals(4, records.getValue("М2").doc.path("parts").size(), "линки М2")
        assertEquals(6, records.getValue("М3").doc.path("parts").size(), "плечи М3")
        // взятие идёт в проект: полка ответов не даёт, а экран проекта их спрашивает
        val view = boundary.systemModels.view(project, "SRR")
        assertTrue(view.path("total").asInt() >= 14, "на экране моделей: ${view.path("total").asInt()}")
        // повторное взятие не плодит набор — идемпотентность связи «применяет»
        val again = channel.apply(models, project, "инженер")
        assertTrue(again.created.isEmpty() && again.existing.size == 14, "повтор: ${again.created.size}/${again.existing.size}")
    }
}
