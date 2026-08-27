// Находка MVP-прохода (вставка пакета целей): id пакета глобально заняты
// старым проектом, а первая ошибка строки валила транзакцию пачки каскадом
// «current transaction is aborted». Меры: акцепт переназначает занятые id
// и перебивает внутренние ссылки; отказ строки точечен (SAVEPOINT) — отчёт
// называет настоящую причину каждой строки.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.CoreType
import orbita.mod.schema.SchemaRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BatchAcceptTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val importer = BatchImport(boundary)

    @BeforeEach
    fun clean() {
        TestDb.truncateAll()
        listOf("PJ-1401" to "Старый", "PJ-1402" to "Новый").forEach { (id, nm) ->
            boundary.ingest(
                CoreType.Project,
                """{"id":"$id","name":"$nm","phase":"pre_phase_a",
                    "milestones":[{"gate":"MCR"}],"lifecycle":{"status":"Draft","version":"1"}}""",
                "test", id,
            )
        }
        // цель старого проекта держит id — id глобальны (TZ-MOD-007)
        boundary.ingest(
            CoreType.MissionGoal,
            """{"id":"MG-0001","kind":"goal","statement":"Старая цель занята.",
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1401",
        )
    }

    @Test
    fun `занятые id пачки переназначаются, внутренние ссылки перебиваются`() {
        val items = mapper.readTree(
            """[
              {"id":"MG-0001","kind":"goal","statement":"Новая цель с занятым id.",
               "lifecycle":{"status":"Draft","version":"1"}},
              {"id":"MG-0002","kind":"objective","statement":"Задача конкретизирует цель пачки.",
               "parent":"MG-0001",
               "lifecycle":{"status":"Draft","version":"1"}}
            ]""",
        ) as ArrayNode
        val (remapped, idMap) = importer.remapBusyIds(items)
        assertEquals(setOf("MG-0001"), idMap.keys)
        val newId = idMap["MG-0001"]!!
        assertTrue(newId != "MG-0001" && newId.startsWith("MG-")) { newId }
        // свободный id пачки остаётся, ссылка внутри пачки перебита на новый
        assertEquals("MG-0002", remapped[1]["id"].asText())
        assertEquals(newId, remapped[1]["parent"].asText())

        val payload = mapper.createObjectNode()
        payload.put("author", "инженер")
        payload.set<ArrayNode>("objects", remapped)
        val report = importer.import(payload, "инженер", "PJ-1402")
        assertEquals(2, report.written)
        assertEquals("PJ-1402", boundary.objects.current(newId)!!.projectId)
        // старая цель старого проекта не тронута
        assertEquals("PJ-1401", boundary.objects.current("MG-0001")!!.projectId)
    }

    @Test
    fun `межпакетные ссылки чинятся картой проекта - комплект пакетов сквозной`() {
        // пакет 1: цель с занятым id — акцептный ремап оставляет след source_id
        val goals = mapper.readTree(
            """[{"id":"MG-0001","kind":"goal","statement":"Цель пакета 01.",
                 "provenance":{"source":"ai_proposed",
                   "ai":{"prompt_package_id":"пакет","accepted":true}},
                 "lifecycle":{"status":"Draft","version":"1"}}]""",
        ) as ArrayNode
        val (goalsRemapped, goalsMap) = importer.remapForAccept(goals, "PJ-1402")
        val newGoal = goalsMap["MG-0001"]!!
        assertEquals(
            "MG-0001",
            goalsRemapped[0]["provenance"]["ai"]["source_id"].asText(),
        )
        val p1 = mapper.createObjectNode()
        p1.put("author", "инженер")
        p1.set<ArrayNode>("objects", goalsRemapped)
        assertEquals(1, importer.import(p1, "инженер", "PJ-1402").written)

        // пакет 2 ссылается на ЧЕРНОВОЙ id пакета 1 (как 04-требования на
        // цели/нужды прежних пакетов): карта проекта чинит ссылку на
        // настоящий id, а не оставляет её чужому проекту
        val objectives = mapper.readTree(
            """[{"id":"MG-0002","kind":"objective",
                 "statement":"Задача пакета 2 конкретизирует цель пакета 1.",
                 "parent":"MG-0001",
                 "lifecycle":{"status":"Draft","version":"1"}}]""",
        ) as ArrayNode
        val (crossRemapped, _) = importer.remapForAccept(objectives, "PJ-1402")
        assertEquals(newGoal, crossRemapped[0]["parent"].asText()) {
            "ссылка должна уйти на $newGoal, а не в чужой проект: ${crossRemapped[0]}"
        }
        val p2 = mapper.createObjectNode()
        p2.put("author", "инженер")
        p2.set<ArrayNode>("objects", crossRemapped)
        assertEquals(1, importer.import(p2, "инженер", "PJ-1402").written)
    }

    @Test
    fun `отказ строки точечен - без каскада transaction is aborted`() {
        // без ремапа (путь импорта): занятый id — честный отказ строки,
        // остальным строкам причина не приписывается
        val payload = mapper.readTree(
            """{"author":"инженер","objects":[
              {"id":"MG-0001","kind":"goal","statement":"Дубль занятого id.",
               "lifecycle":{"status":"Draft","version":"1"}},
              {"id":"ND-9102","statement":"Живая строка той же пачки.",
               "stakeholder":{"name":"Оператор","role":"operator"},
               "lifecycle":{"status":"Draft","version":"1"}}
            ]}""",
        )
        val refusal = runCatching { importer.import(payload, "инженер", "PJ-1402") }
        val report = (refusal.exceptionOrNull() as BatchRejectedException).report
        assertEquals(0, report.written)
        // застрявшая строка — одна, и причина настоящая
        assertEquals(listOf("MG-0001"), report.problems.map { it.id })
        assertTrue("already in use" in report.problems[0].message) { report.problems[0].message }
        assertTrue(report.problems.none { "transaction is aborted" in it.message }) {
            report.problems.joinToString { it.message }
        }
        // всё или ничего: живая строка не записана
        assertEquals(null, boundary.objects.current("ND-9102"))
    }
}
