// Ф-10: выгрузка знаний во внешний контур. Меры владельца: выгрузка отдаёт
// знания файлами; каждый файл несёт отпечаток; инструкция ГЕНЕРИРУЕТСЯ из
// реестра видов (двух редакций правил не существует); правка знаний на стенде
// меняет отпечаток, и вставка пакета по старой выгрузке предупреждена.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.CoreType
import orbita.mod.schema.SchemaRegistry
import orbita.mod.store.ObjectStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KnowledgeExportTest {

    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val mapper = ObjectMapper()
    private val allParts = KnowledgeExport.PARTS.map { it.key }.toSet()

    @BeforeEach
    fun clean() {
        TestDb.truncateAll()
        boundary.ingest(
            CoreType.NormativeDocument,
            """{"id":"NR-9001","name":"Об оснащении транспорта аппаратурой спутниковой навигации",
                "kind":"decree","number":"ПП №2216","org":"Правительство РФ","in_force":"in_force",
                "edition_date":"2021-12-01",
                "clauses":[{"clause":"п. 3","text":"Передача геопозиции — не реже одного раза в 30 секунд"}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", ObjectStore.LIBRARY_PROJECT,
        )
        boundary.ingest(
            CoreType.Project,
            """{"id":"PJ-1903","name":"Морская логистика","phase":"pre_phase_a",
                "mission_intent":{"for_whom":"перевозчики","what":"передача телеметрии",
                                  "where":"СМП","horizon":"до 2033 года"},
                "constraints":[{"code":"Р1","text":"Полезная нагрузка — только регенеративная."},
                               {"code":"Р2","text":"Снятое ограничение.","removed":true}],
                "milestones":[{"gate":"MCR"}],"lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-1903",
        )
    }

    @Test
    fun `выгрузка отдаёт знания файлами, каждый — с отпечатком в шапке`() {
        val bundle = KnowledgeExport.bundle(boundary, "files", "PJ-1903", allParts)
        assertEquals(KnowledgeExport.PARTS.size, bundle.files.size)
        assertTrue(bundle.fingerprint.length == 16) { "отпечаток: ${bundle.fingerprint}" }
        bundle.files.forEach { (name, body) ->
            assertTrue(bundle.fingerprint in body) { "$name без отпечатка в шапке" }
            assertTrue("knowledge_fingerprint" in body) { "$name не требует отпечатка в ответе" }
        }
        val constraints = bundle.files.getValue("02-ограничения.md")
        assertTrue("Р1" in constraints && "регенеративная" in constraints)
        assertTrue("~~Р2~~" in constraints) { "отменённое ограничение — след решения, а не пустота" }
        val normatives = bundle.files.getValue("05-нормативы.md")
        assertTrue("ПП №2216" in normatives && "30 секунд" in normatives) {
            "норматив обязан уйти пунктами, а не именем"
        }
        val intent = bundle.files.getValue("01-замысел.md")
        assertTrue("перевозчики" in intent && "2033" in intent)
    }

    @Test
    fun `инструкция генерируется из реестра видов, а не пишется рядом`() {
        val project = boundary.objects.current("PJ-1903")!!.doc
        val text = KnowledgeExport.instruction(project)
        val kinds = orbita.ai.PackageKinds.default()
        kinds.ids.forEach { id ->
            assertTrue("`$id`" in text) { "вид $id не попал в инструкцию — редакции разойдутся" }
        }
        // правила вида, заданные в реестре, приходят той же редакцией
        val parse = kinds.of("document_semantic_parse")
        assertTrue("редакция ${parse.rulesVersion}" in text) { "редакция правил обязана быть названа" }
        assertTrue("внешний источник, проверенный на указанную дату" in text) {
            "семантика меток источников — дословно авторская"
        }
        assertTrue("knowledge_fingerprint" in text) { "правило отпечатка — в инструкции" }
    }

    @Test
    fun `правка знаний меняет отпечаток`() {
        val before = KnowledgeExport.bundle(boundary, "files", "PJ-1903", allParts).fingerprint
        val passport = boundary.objects.current("PJ-1903")!!
        val changes = mapper.readTree(
            """{"constraints":[{"code":"Р1","text":"Полезная нагрузка — только регенеративная."},
                              {"code":"Р3","text":"Старт — только с российских космодромов."}]}""",
        )
        boundary.editing.update(
            CoreType.Project, "PJ-1903", changes as com.fasterxml.jackson.databind.node.ObjectNode,
            passport.version, "test", changeRef = "проверка отпечатка",
        )
        val after = KnowledgeExport.bundle(boundary, "files", "PJ-1903", allParts).fingerprint
        assertNotEquals(before, after) { "правка ограничения обязана менять отпечаток знаний" }
    }

    @Test
    fun `пакет по старой выгрузке принимается, но предупреждён`() {
        val current = KnowledgeExport.bundle(boundary, "files", "PJ-1903", allParts).fingerprint
        val fresh = mapper.readTree("""{"knowledge_fingerprint":"$current"}""")
        assertNull(KnowledgeExport.staleWarning(fresh, current))

        val stale = mapper.readTree("""{"knowledge_fingerprint":"0123456789abcdef"}""")
        val warning = KnowledgeExport.staleWarning(stale, current)
        assertTrue(warning != null && "устарели" in warning) { "$warning" }
        assertTrue(current in warning!!) { "предупреждение обязано назвать нынешний отпечаток: $warning" }

        val silent = mapper.readTree("""{"items":[]}""")
        assertTrue(KnowledgeExport.staleWarning(silent, current)!!.contains("не назвал отпечаток"))
    }

    @Test
    fun `состав выбирается — невыбранная часть в пакет не идёт`() {
        val bundle = KnowledgeExport.bundle(boundary, "files", "PJ-1903", setOf("instruction", "constraints"))
        assertEquals(setOf("00-инструкция.md", "02-ограничения.md"), bundle.files.keys)
        assertNotEquals(
            KnowledgeExport.bundle(boundary, "files", "PJ-1903", allParts).fingerprint,
            bundle.fingerprint,
        ) { "отпечаток считается по содержимому выгрузки — частичная и полная различаются" }
    }
}
