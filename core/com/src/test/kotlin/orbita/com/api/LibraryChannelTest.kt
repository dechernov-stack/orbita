// Канал библиотеки (§2–§3): предпросмотр показывает резы поимённо; без
// подтверждения фрагмент не пишется; сохранённое с узла поддерево
// применяется в ДРУГОЙ проект и даёт работающий состав со связями
// «применяет» и происхождением (приёмка §6).
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.CoreType
import orbita.mod.schema.SchemaRegistry
import orbita.mod.store.ObjectStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.assertThrows

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class LibraryChannelTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val channel = LibraryChannel(boundary)
    private var fragmentId = ""

    @BeforeAll
    fun setup() {
        TestDb.truncateAll()
        listOf("PJ-2101" to "Донор", "PJ-2102" to "Приёмник").forEach { (id, nm) ->
            boundary.ingest(
                CoreType.Project,
                """{"id":"$id","name":"$nm","phase":"pre_phase_a",
                    "milestones":[{"gate":"MCR"}],"lifecycle":{"status":"Draft","version":"1"}}""",
                "test", id,
            )
        }
        // норматив в библиотеке — основание, которое резаться не должно
        boundary.ingest(
            CoreType.NormativeDocument,
            """{"id":"NR-0001","name":"Увод КА с орбиты","kind":"standard",
                "number":"NASA-STD-8719.14","edition_date":"2021-01-01","in_force":"in_force",
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", ObjectStore.LIBRARY_PROJECT,
        )
        // дерево донора: сегмент → КА → БЦВМ; сосед вне поддерева; интерфейс внутри
        boundary.ingest(
            CoreType.Component,
            """{"id":"CM-0001","name":"Космический сегмент","kind":"segment",
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2101",
        )
        boundary.ingest(
            CoreType.Component,
            """{"id":"CM-0002","name":"КА-ретранслятор","kind":"system","parent":"CM-0001",
                "parameters":[{"name":"mass","quantity":{"value":80,"unit":"kg",
                    "provenance":{"source":"manual","author":"test"}}}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2101",
        )
        boundary.ingest(
            CoreType.Component,
            """{"id":"CM-0003","name":"БЦВМ","kind":"subsystem","parent":"CM-0002",
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2101",
        )
        boundary.ingest(
            CoreType.Component,
            """{"id":"CM-0004","name":"Наземный сегмент","kind":"segment",
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2101",
        )
        boundary.ingest(
            CoreType.Interface,
            """{"id":"IF-0001","name":"КА ↔ БЦВМ шина","owners":["CM-0002","CM-0003"],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2101",
        )
        boundary.ingest(
            CoreType.Interface,
            """{"id":"IF-0002","name":"КА ↔ наземный","owners":["CM-0002","CM-0004"],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2101",
        )
        // нужда и требование: основание на норматив (не режется) + нужда (режется)
        boundary.ingest(
            CoreType.Need,
            """{"id":"ND-0001","statement":"Нужда донора в связи для мониторинга грузов",
                "stakeholder":{"name":"Минтранс","role":"regulator"},"lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2101",
        )
        boundary.ingest(
            CoreType.Requirement,
            """{"id":"RQ-0001","level":"system","category":"functional",
                "statement":"КА должен сводиться с орбиты не позднее 25 лет после завершения миссии",
                "traces_up":[{"ref":"ND-0001"},{"ref":"NR-0001"}],
                "allocated_to":[{"component":"CM-0002"}],
                "verification_events":[],"owner":"test",
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2101",
        )
    }

    @Test
    @Order(1)
    fun `предпросмотр называет резы поимённо, основания на нормативы не режутся`() {
        val c = channel.closure("PJ-2101", null, emptyList(), "CM-0002")
        // поддерево: КА + БЦВМ + внутренний интерфейс + требование на КА
        assertEquals(
            listOf("CM-0002", "CM-0003", "IF-0001", "RQ-0001"),
            c.objects.map { it.id },
        ) { c.objects.map { it.id }.toString() }
        val cuts = c.cuts.map { "${it.from} → ${it.to} (${it.what})" }
        assertTrue("CM-0002 → CM-0001 (parent)" in cuts) { cuts.toString() }
        assertTrue("RQ-0001 → ND-0001 (traces_up)" in cuts) { cuts.toString() }
        // основание на NR-0001 (область LIB) НЕ в резах
        assertTrue(cuts.none { "NR-0001" in it }) { cuts.toString() }
        // величина массы — кандидат обезличивания
        assertTrue(c.valueCandidates.any { it.first == "CM-0002" && "80" in it.third })
    }

    @Test
    @Order(2)
    fun `без подтверждения резов фрагмент не пишется`() {
        val e = assertThrows<IllegalArgumentException> {
            channel.save(
                "PJ-2101", null, emptyList(), "CM-0002",
                name = "Типовой КА", shelf = "B5", missionClassRef = null,
                acknowledgedCuts = emptySet(), replacements = emptyMap(), author = "test",
            )
        }
        assertTrue("не подтверждено" in (e.message ?: "")) { e.message ?: "" }
    }

    @Test
    @Order(3)
    fun `сохранение с подтверждением и применение в другой проект`() {
        val c = channel.closure("PJ-2101", null, emptyList(), "CM-0002")
        val acks = c.cuts.map { "${it.from} → ${it.to} (${it.what})" }.toSet()
        val frag = channel.save(
            "PJ-2101", null, emptyList(), "CM-0002",
            name = "Типовой КА-ретранслятор IoT", shelf = "B5", missionClassRef = null,
            acknowledgedCuts = acks,
            replacements = mapOf("CM-0002" to listOf("parameters/0")),
            author = "test",
        )
        fragmentId = frag.id
        assertEquals(ObjectStore.LIBRARY_PROJECT, frag.projectId)
        assertTrue(frag.doc.path("anonymized").asBoolean())
        assertEquals(2, frag.doc.path("counters").path("component").asInt())
        assertEquals("PJ-2101", frag.doc.path("origin").path("project").asText())

        val outcome = channel.apply(frag.id, "PJ-2102", "приёмник")
        val created = outcome.created
        assertEquals(4, created.size)
        val newByOld = created.toMap()
        val ka = boundary.objects.current(newByOld["CM-0002"]!!)!!
        assertEquals("PJ-2102", ka.projectId)
        // родословная и «применяет»
        assertEquals("imported", ka.doc.path("provenance").path("source").asText())
        assertTrue(frag.id in ka.doc.path("provenance").path("import").path("dataset").asText())
        assertEquals(frag.id, ka.doc.path("applies").path("ref").asText())
        val links = boundary.links.linksFrom(ka.id, "applies")
        assertEquals(frag.id, links.single().toId)
        // внутренние ссылки ремапнуты: parent БЦВМ — новый id КА
        val bcvm = boundary.objects.current(newByOld["CM-0003"]!!)!!
        assertEquals(ka.id, bcvm.doc.path("parent").asText())
        val itf = boundary.objects.current(newByOld["IF-0001"]!!)!!
        assertEquals(
            setOf(ka.id, bcvm.id),
            itf.doc.path("owners").map { it.asText() }.toSet(),
        )
        // требование: основание на NR-0001 сохранено, нужда донора отрезана
        val rq = boundary.objects.current(newByOld["RQ-0001"]!!)!!
        assertEquals(
            listOf("NR-0001"),
            rq.doc.path("traces_up").map { it.path("ref").asText() },
        )
        // обезличенная масса не приехала
        assertTrue(ka.doc.path("parameters").isEmpty || ka.doc.path("parameters").isMissingNode)
    }

    @Test
    @Order(4)
    fun `взятие идемпотентно, отмена гасит созданное, тронутое руками не отменяется`() {
        // повторное нажатие не плодит второй набор — по связям «применяет»
        val again = channel.apply(fragmentId, "PJ-2102", "приёмник")
        assertTrue(again.created.isEmpty()) { "второй набор не создан: ${'$'}{again.created}" }
        assertEquals(4, again.existing.size)

        // отмена гасит созданное именно этим взятием
        val removed = channel.revert(fragmentId, "PJ-2102", "приёмник")
        assertEquals(4, removed.size)
        removed.forEach {
            assertEquals(orbita.mod.model.Lifecycle.Cancelled, boundary.objects.current(it)!!.status)
        }
        // после отмены путь свободен: взятие создаёт набор заново
        val fresh = channel.apply(fragmentId, "PJ-2102", "приёмник")
        assertEquals(4, fresh.created.size)

        // тронутое руками — отказ с перечнем, не молчаливое удаление
        val touchedId = fresh.created.first { it.first == "CM-0002" }.second
        val cur = boundary.objects.current(touchedId)!!
        val doc = cur.doc.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        doc.put("name", "Тронуто рукой инженера")
        boundary.editing.update(
            orbita.mod.model.CoreType.Component, touchedId, doc, cur.version, "инженер",
        )
        val blocked = org.junit.jupiter.api.Assertions.assertThrows(
            LibraryChannel.RevertBlockedException::class.java,
        ) { channel.revert(fragmentId, "PJ-2102", "приёмник") }
        assertTrue(touchedId in blocked.touched) { blocked.touched.toString() }
    }

    @Test
    @Order(5)
    fun `полка Б6 - WBS-пачка берётся с кодами, без кодов - отказ схемой`() {
        // триаж ПМИ-2 А3: «кнопка взять не нажимается» — пачка полки без
        // обязательного code падала схемой на первом же элементе
        fun frag(objects: String): String {
            val doc = mapper.readTree(
                """{"name":"WBS-пачка теста","shelf":"B6",
                    "payload":{"objects":$objects}}""",
            ) as com.fasterxml.jackson.databind.node.ObjectNode
            return boundary.editing.create(
                CoreType.LibraryFragment, doc, "test", ObjectStore.LIBRARY_PROJECT,
            ).id
        }

        val good = frag(
            """[
                {"id":"WB-9001","code":"1","name":"Программа"},
                {"id":"WB-9002","code":"1.1","name":"Изготовление КА","parent":"WB-9001"},
                {"id":"WB-9003","code":"1.2","name":"Испытания","parent":"WB-9001"}
            ]""",
        )
        val outcome = channel.apply(good, "PJ-2102", "приёмник")
        assertEquals(3, outcome.created.size)
        val byOld = outcome.created.toMap()
        val child = boundary.objects.current(byOld["WB-9002"]!!)!!
        assertEquals("wbs_element", child.type)
        assertEquals(byOld["WB-9001"]!!, child.doc.path("parent").asText())
        assertEquals("1.1", child.doc.path("code").asText())

        val broken = frag("""[{"id":"WB-9101","name":"Без кода"}]""")
        val refusal = org.junit.jupiter.api.Assertions.assertThrows(Exception::class.java) {
            channel.apply(broken, "PJ-2102", "приёмник")
        }
        assertTrue("code" in (refusal.message ?: "")) { "отказ называет поле: ${'$'}{refusal.message}" }
    }

    @Test
    @Order(6)
    fun `каркас PBS ложится в то же дерево - родители переписаны, узел КА виден сборке`() {
        // ADR-044 п.4: сегменты и элементы каркаса становятся узлами одного
        // дерева состава, а не списком рядом с ним — profile и parent едут с пачкой
        val doc = mapper.readTree(
            """{"name":"Каркас PBS теста","shelf":"B5",
                "payload":{"objects":[
                  {"id":"CM-9101","name":"Космический сегмент","kind":"segment","segment":"space"},
                  {"id":"CM-9102","name":"Космический аппарат","kind":"element","parent":"CM-9101",
                   "profile":{"role":"spacecraft","preset":"cubesat_16u"}},
                  {"id":"CM-9103","name":"Платформа","kind":"subsystem","parent":"CM-9102","profile":{"role":"platform"}}
                ]}}""",
        ) as com.fasterxml.jackson.databind.node.ObjectNode
        val frag = boundary.editing.create(CoreType.LibraryFragment, doc, "test", ObjectStore.LIBRARY_PROJECT).id
        val outcome = channel.apply(frag, "PJ-2102", "приёмник")
        val byOld = outcome.created.toMap()
        val ka = boundary.objects.current(byOld["CM-9102"]!!)!!
        assertEquals(byOld["CM-9101"]!!, ka.doc.path("parent").asText())
        assertEquals("spacecraft", ka.doc.path("profile").path("role").asText())
        val platform = boundary.objects.current(byOld["CM-9103"]!!)!!
        assertEquals(ka.id, platform.doc.path("parent").asText())
        assertTrue(boundary.carriers.nodes("PJ-2102").any { it.id == ka.id }) { "узел КА из каркаса не виден сборке" }
    }
}
