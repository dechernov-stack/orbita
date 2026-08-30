// Ф-05: промпт постановки — из данных, не из общих слов. Меры владельца:
// без замысла миссии генерация заблокирована с причиной; с классом и взятой
// библиотекой промпт несёт конкретику полок (позиции считаются); пустой
// источник виден строкой «— пусто»; пакетный канал ПМИ не затронут.
package orbita.com.api

import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.CoreType
import orbita.mod.schema.SchemaRegistry
import orbita.mod.store.ObjectStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StatementSourcesTest {

    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val sources = StatementSources(boundary)

    private fun project(intent: String) = """
        {"id":"PJ-1901","name":"Постановка","phase":"pre_phase_a",
         "mission_class":"MC-9001",
         "constraints":[{"code":"Р1","text":"Полезная нагрузка — только регенеративная."},
                        {"code":"Р2","text":"Отменённое ограничение.","removed":true}],
         "start_path":{"status":"in_progress","step":3,"created_counts":{"requirement":34}},
         $intent
         "milestones":[{"gate":"MCR"}],"lifecycle":{"status":"Draft","version":"1"}}"""

    @BeforeEach
    fun clean() {
        TestDb.truncateAll()
        boundary.ingest(
            CoreType.MissionClass,
            """{"id":"MC-9001","name":"НОО · связь и IoT",
                "typical_constraints":[{"code":"Р1","text":"Регенеративная нагрузка"}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", ObjectStore.LIBRARY_PROJECT,
        )
        boundary.ingest(
            CoreType.StakeholderProfile,
            """{"id":"SH-9001","name":"Оператор связи","role":"operator",
                "mission_class_ref":"MC-9001","lifecycle":{"status":"Draft","version":"1"}}""",
            "test", ObjectStore.LIBRARY_PROJECT,
        )
        boundary.ingest(
            CoreType.TypicalRisk,
            """{"id":"TR-9001","statement":"Задержка поставки платформы — срыв срока запуска",
                "category":"supply","mission_class_ref":"MC-9001",
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", ObjectStore.LIBRARY_PROJECT,
        )
    }

    private fun ingestProject(intent: String) =
        boundary.ingest(CoreType.Project, project(intent), "test", "PJ-1901")

    @Test
    fun `без замысла генерация постановки заблокирована с причиной`() {
        ingestProject("")
        val refusal = sources.refusalFor("mission_to_goals", "PJ-1901")
        assertTrue(refusal != null && "замысл" in refusal) { "$refusal" }
        assertTrue("общие места" in refusal!!) { refusal }
        // виды вне постановки замысла не требуют
        assertNull(sources.refusalFor("requirement_quality", "PJ-1901"))
    }

    @Test
    fun `замысел четырьмя полями и одним абзацем — оба законны`() {
        ingestProject(
            """"mission_intent":{"for_whom":"перевозчики","what":"передача телеметрии",
                "where":"Арктика и СМП","horizon":"до 2033 года"},""",
        )
        assertNull(sources.refusalFor("mission_to_goals", "PJ-1901"))
        val byFields = sources.intentOf(boundary.objects.current("PJ-1901")!!.doc)!!
        assertTrue("перевозчики" in byFields && "Арктика" in byFields && "2033" in byFields)

        TestDb.truncateAll()
        clean()
        ingestProject(""""mission_intent":{"text":"Группировка для передачи телеметрии в Арктике."},""")
        assertNull(sources.refusalFor("mission_to_goals", "PJ-1901"))
    }

    @Test
    fun `неполный замысел замыслом не считается`() {
        ingestProject(""""mission_intent":{"for_whom":"перевозчики","what":"телеметрия"},""")
        assertTrue(sources.refusalFor("mission_to_goals", "PJ-1901") != null) {
            "три поля из четырёх — это не замысел"
        }
    }

    @Test
    fun `источники промпта — данными полок, с именами и счётчиками`() {
        ingestProject(""""mission_intent":{"text":"Группировка IoT для логистики."},""")
        // урожай Д2, принятый в проект, — контекст «уже принято»
        boundary.ingest(
            CoreType.MissionGoal,
            """{"id":"MG-9001","kind":"goal","statement":"Отслеживаемость грузов",
                "provenance":{"source":"imported","import":{"dataset":"SD-0003 «Записка»",
                  "dataset_version":"3","retrieved_at":"2026-08-29","terms":"внутренний документ"}},
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1901",
        )

        val list = sources.of("mission_to_goals", "PJ-1901")
        assertEquals(
            // Д3 добавил «материалы блоками» между библиотекой и взятым;
            // Ф-09 — «знание полки» сразу за перечнем позиций класса
            listOf("intent", "class_library", "library_facts", "materials", "taken", "accepted", "prohibitions"),
            list.map { it.key },
        ) { "порядок источников — он же порядок предпросмотра" }

        val library = list.first { it.key == "class_library" }
        assertTrue(library.count >= 3) { "полки класса не подтянулись: ${library.lines}" }
        assertTrue(library.lines.any { "НОО · связь и IoT" in it })
        assertTrue(library.lines.any { "SH-9001" in it })
        assertTrue(library.lines.any { "TR-9001" in it })

        val taken = list.first { it.key == "taken" }
        assertTrue(taken.lines.any { "34" in it && "не дублировать" in it }) { taken.lines.toString() }

        val accepted = list.first { it.key == "accepted" }
        assertEquals(1, accepted.count)
        assertTrue(accepted.lines.single().contains("SD-0003"))

        val prohibitions = list.first { it.key == "prohibitions" }
        assertEquals(1, prohibitions.count) { "отменённое ограничение в запреты не идёт" }
        assertTrue(prohibitions.lines.single().startsWith("Р1"))
    }

    @Test
    fun `пустой источник не исчезает - он виден счётчиком и пометкой`() {
        ingestProject(""""mission_intent":{"text":"Группировка IoT."},""")
        val list = sources.of("mission_to_goals", "PJ-1901")
        val accepted = list.first { it.key == "accepted" }
        assertTrue(accepted.empty)
        assertEquals(0, accepted.count)
        assertTrue(accepted.note != null && "не принимался" in accepted.note!!) { "${accepted.note}" }
    }
}
