// Ф-07: замысел собирается ИЗ ДОКУМЕНТОВ, а не печатается в пустой табличке.
// Ф-08.1: постановочный документ входит в промпт разделами при загрузке.
// Ф-08.3: метка источника блока ([И]/[В]/[П]) доходит до карты разбора.
//
// Меры владельца: проект с запиской → «собрать из документов» → четыре поля
// с якорями, инженер правит и принимает, генерация О2 разблокирована;
// проект без документов — форма рукой, как в Ф-05.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.CoreType
import orbita.mod.schema.SchemaRegistry
import orbita.out.ParseLexicon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MissionIntentDraftTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)

    @TempDir
    lateinit var files: Path

    /** Записка по шаблону: метки источников проставлены автором. */
    private val note = """
        # Записка миссии

        [П] Предлагается национальная платформа коротких сообщений для перевозчиков.

        ## 1. Обоснование

        [И] Действующая нормативная база обязывает оснащать транспорт телеметрией.

        [В] Наземные сети не покрывают Арктику, поэтому нужен спутниковый канал.

        [П] Цель — 1 млн терминалов к 2033 году.
    """.trimIndent()

    private val lexicon = ParseLexicon(terms = listOf("Карта спроса"))

    @BeforeEach
    fun clean() {
        TestDb.truncateAll()
        System.setProperty("orbita.test.filesDir", files.toString())
        boundary.ingest(
            CoreType.Project,
            """{"id":"PJ-2201","name":"Замысел из документов","phase":"pre_phase_a",
                "milestones":[{"gate":"MCR"}],"lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2201",
        )
    }

    private fun addNote(id: String = "SD-2201") {
        boundary.ingest(
            CoreType.SourceDocument,
            """{"id":"$id","name":"Записка миссии","kind":"mission_note","org":"Минтранс",
                "rights":"внутренний документ проекта","lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2201",
        )
        DocumentParseStore.parseAndStore(files.toString(), id, "записка.md", note.toByteArray(), lexicon)
    }

    @Test
    fun `без документов сборка невозможна и честно это говорит`() {
        val readiness = MissionIntentDraft.readiness(boundary, files.toString(), "PJ-2201")
        assertEquals(false, readiness.path("can_compose").asBoolean())
        assertTrue("рукой" in readiness.path("why").asText()) { readiness.path("why").asText() }
    }

    @Test
    fun `с разобранной запиской сборка доступна и знает свой источник`() {
        addNote()
        val readiness = MissionIntentDraft.readiness(boundary, files.toString(), "PJ-2201")
        assertTrue(readiness.path("can_compose").asBoolean())
        assertEquals(1, readiness.path("parsed").asInt())
        assertEquals("SD-2201", readiness.path("sources")[0].path("document").asText())
    }

    @Test
    fun `вход операции — урожай и канон блоками, а не файл`() {
        addNote()
        DocumentHarvest.store(
            files.toString(), "SD-2201", "fp-1",
            mapper.readTree(
                """{"kind":"document_semantic_parse","source_document":"SD-2201","items":[
                     {"class":"stakeholder","name":"Перевозчики опасных грузов","block":["b1"]}]}""",
            ),
        )
        val statement = MissionIntentDraft.statementOf(boundary, files.toString(), "PJ-2201")
        assertTrue("УРОЖАЙ РАЗБОРА SD-2201" in statement)
        assertTrue("Перевозчики опасных грузов" in statement)
        assertTrue("{#b0}" in statement) { "канон блоками обязан войти во вход операции" }
    }

    @Test
    fun `принятое предложение ложится в паспорт с якорями происхождения`() {
        addNote()
        val draft = mapper.readTree(
            """{"kind":"mission_intent_from_docs","source_document":"SD-2201",
                "intent":{
                  "for_whom":{"text":"Перевозчики опасных грузов и операторы БПЛА","anchors":["b1","b2"]},
                  "what":{"text":"Короткие сообщения обязательной телеметрии","anchors":["b0"]},
                  "where":{"text":"Территория России с приоритетом Арктики","anchors":["b3"]},
                  "horizon":{"text":"1 млн терминалов к 2033 году","anchors":["b4"]}}}""",
        )
        assertTrue(MissionIntentDraft.problems(boundary, draft).isEmpty())

        val passport = boundary.objects.current("PJ-2201")!!
        val intent = MissionIntentDraft.applyTo(passport.doc, draft)
        val changes = mapper.createObjectNode()
        changes.set<ObjectNode>("mission_intent", intent)
        boundary.editing.update(CoreType.Project, "PJ-2201", changes, passport.version, "инженер")

        val saved = boundary.objects.current("PJ-2201")!!.doc.path("mission_intent")
        assertEquals("Перевозчики опасных грузов и операторы БПЛА", saved.path("for_whom").asText())
        assertEquals(listOf("b1", "b2"), saved.path("sources").path("for_whom").map { it.asText() })
        // и генерация постановки больше не заблокирована (Ф-05)
        assertNull(StatementSources(boundary).refusalFor("mission_to_goals", "PJ-2201"))
    }

    @Test
    fun `чужая форма предложения внутрь не проходит`() {
        val problems = MissionIntentDraft.problems(
            boundary,
            mapper.readTree("""{"kind":"mission_intent_from_docs","intent":{"for_whom":{"text":"кто-то"}}}"""),
        )
        assertTrue(problems.isNotEmpty()) { "неполный замысел обязан отклоняться схемой" }
    }

    @Test
    fun `метки источников доходят до карты разбора`() {
        addNote()
        val map = DocumentParseStore.mapOf(files.toString(), "SD-2201")!!
        val marks = map.path("source_marks").associate {
            it.path("block").asText() to it.path("mark").asText()
        }
        assertTrue(marks.values.contains("П")) { "предложение автора обязано быть помечено: $marks" }
        assertTrue(marks.values.contains("И") && marks.values.contains("В")) { "$marks" }
        assertEquals(marks.size, map.path("summary").path("source_marks").asInt())
        // текст блока метку сохраняет — канон ничего не теряет
        assertTrue("[П] Цель — 1 млн терминалов" in DocumentParseStore.canonOf(files.toString(), "SD-2201")!!)
    }
}
