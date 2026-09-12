// Проверка документа против поля знаний: четыре рода находки и ни одной правки.
//
// Ни один тест здесь не поднимает базу и не зовёт модель: верификация по
// построению работает на документе, фактах поля и реестре связей. Понадобься
// ей однажды живой вызов — мера ПМИ-6 п. 2.10 («правок молча — 0», отчёт
// строится по данным) была бы потеряна, и сломался бы именно этот файл.
//
// Хранилище и реестр связей — память: у модуля документов нет оснастки базы,
// а проверять надо правила находки, а не запись в Postgres. Обвязка объявлена
// `internal`, потому что её же берёт VerificationReportTest.
package orbita.documents

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.documents.api.DocumentsFactory
import orbita.documents.api.Issue
import orbita.documents.api.VerificationReport
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.Link
import orbita.kernel.api.LinkRegistry
import orbita.kernel.api.Provenance
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VerificationTest {

    private val стенд = СтендПоля()

    /**
     * Поле с четырьмя болезнями сразу: цель со спорным годом, цель без
     * основания, цель на устаревшем источнике и тезис с числом из ниоткуда.
     */
    private fun расставить() {
        стенд.материал("MM-0001", "Записка о миссии", "mandatory")
        стенд.материал("AN-0002", "Анализ идей", "reference")
        стенд.материал("SD-0003", "Даташит платформы", "reference")

        // Спор: один год в обязательной записке, другой — в справочном анализе.
        val обязательный = стенд.факт("F-0001", "группировка", "год развёртывания", "2030", material = "MM-0001")
        стенд.факт("F-0009", "группировка", "год развёртывания", "2032", material = "AN-0002")
        стенд.спор("F-0001", "F-0009")

        // Число поля, с которым разойдётся тезис.
        стенд.факт("F-0007", "сервис", "охват абонентов", "95", единица = "%", material = "AN-0002")

        // Устаревшее основание: источник пришёл новой версией.
        val устаревший = стенд.факт(
            "F-0005", "наземный сегмент", "готовность", "обеспечено", material = "SD-0003",
            правки = { узел ->
                узел.put("source_updated", "SD-0004")
                узел.put("source_note", "основание F-0005: источник обновлён (SD-0004, блок §2.1)")
            },
        )

        val спорная = стенд.цель("G-0001", "развернуть группировку", 2030)
        стенд.цель("G-0002", "обеспечить связность приполярных районов", 2031)
        val устарелая = стенд.цель("G-0003", "довести наземный сегмент до готовности", null)
        стенд.основание(спорная, обязательный)
        стенд.основание(устарелая, устаревший)
        // У G-0002 основания НЕТ намеренно: это и есть находка «без основания».

        стенд.документы.ensure(СтендПоля.ПРОЕКТ, СтендПоля.ОТЧЁТ, "Иванов И.")
        стенд.документы.addStatement(
            СтендПоля.ПРОЕКТ, СтендПоля.ОТЧЁТ, "2",
            "Ожидаемый охват абонентов — 97 %.",
            listOf("Э-ЦЕЛИ"), "Иванов И.",
        )
    }

    @Test
    fun `отчёт содержит по одной находке каждого из четырёх родов`() {
        расставить()
        val линия = стенд.документы.baseline(СтендПоля.ПРОЕКТ, СтендПоля.ОТЧЁТ, "внутренний обзор", "Иванов И.")
        val отчёт = стенд.проверка.verify(СтендПоля.ПРОЕКТ, СтендПоля.ОТЧЁТ, "Иванов И.", линия.name)

        Issue.entries.forEach { род ->
            assertEquals(
                1, отчёт.items.count { it.issue == род },
                "род «${род.word}» ожидался ровно один раз: ${отчёт.items.map { it.issue.code to it.element }}",
            )
        }
        assertTrue(отчёт.code.startsWith("VR-"), "код отчёта выдаёт система: ${отчёт.code}")
        assertEquals(VerificationReport.OPEN, отчёт.status)
        assertEquals("внутренний обзор", отчёт.baselineName)
    }

    @Test
    fun `противоречие называет оба значения и оба ранга`() {
        расставить()
        val отчёт = стенд.проверка.verify(СтендПоля.ПРОЕКТ, СтендПоля.ОТЧЁТ, "Иванов И.")
        val спор = отчёт.items.first { it.issue == Issue.CONTRADICTION }

        listOf("2030", "2032", "обязательный", "справочный", "F-0001", "F-0009").forEach { слово ->
            assertTrue(слово in спор.detail, "в отличии не названо «$слово»: ${спор.detail}")
        }
        // Победителя служба не выбирает и говорит об этом прямо.
        assertTrue("победителя выбирает человек" in спор.detail, "в отличии не сказано, кто решает: ${спор.detail}")
        assertNotNull(спор.proposal, "у находки обязано быть предложение человеку")
    }

    @Test
    fun `число тезиса против факта показано обоими значениями с рангом`() {
        расставить()
        val отчёт = стенд.проверка.verify(СтендПоля.ПРОЕКТ, СтендПоля.ОТЧЁТ, "Иванов И.")
        val число = отчёт.items.first { it.issue == Issue.NUMBER_VS_FACT }

        assertTrue("97 %" in число.detail, "не названо число документа: ${число.detail}")
        assertTrue("95 %" in число.detail, "не названо число поля: ${число.detail}")
        assertTrue("справочный" in число.detail, "не назван ранг основания: ${число.detail}")
        assertTrue(число.element.startsWith(СтендПоля.ОТЧЁТ), "находка обязана вести в тезис: ${число.element}")
    }

    @Test
    fun `находка ведёт в место документа, а не в документ вообще`() {
        расставить()
        val отчёт = стенд.проверка.verify(СтендПоля.ПРОЕКТ, СтендПоля.ОТЧЁТ, "Иванов И.")
        val безОснования = отчёт.items.first { it.issue == Issue.NO_BASIS }

        assertEquals("Э-ЦЕЛИ#2", безОснования.element, "строка без основания — вторая в таблице целей")
        assertTrue("G-0002" in безОснования.detail, "не названа сущность строки: ${безОснования.detail}")
    }

    @Test
    fun `сводка считает находки по родам`() {
        расставить()
        val отчёт = стенд.проверка.verify(СтендПоля.ПРОЕКТ, СтендПоля.ОТЧЁТ, "Иванов И.")

        assertTrue("проверено 4 места" in отчёт.summary, "сводка не назвала проверенного: ${отчёт.summary}")
        Issue.entries.forEach { род ->
            assertTrue("${род.word} — 1" in отчёт.summary, "в сводке нет рода «${род.word}»: ${отчёт.summary}")
        }
    }

    @Test
    fun `отчёт читается по коду и попадает в перечень проекта`() {
        расставить()
        val отчёт = стенд.проверка.verify(СтендПоля.ПРОЕКТ, СтендПоля.ОТЧЁТ, "Иванов И.")
        val прочитанный = стенд.проверка.report(СтендПоля.ПРОЕКТ, отчёт.code)

        assertEquals(отчёт.items.map { it.issue to it.element }, прочитанный.items.map { it.issue to it.element })
        assertEquals(отчёт.summary, прочитанный.summary)
        assertEquals(listOf(отчёт.code), стенд.проверка.reports(СтендПоля.ПРОЕКТ, СтендПоля.ОТЧЁТ).map { it.code })
    }

    // --- отказные ----------------------------------------------------------

    @Test
    fun `документ без тезисов и строк говорит словами «проверять нечего»`() {
        стенд.документы.ensure(СтендПоля.ПРОЕКТ, СтендПоля.ПУСТОЙ, "Иванов И.")
        val отчёт = стенд.проверка.verify(СтендПоля.ПРОЕКТ, СтендПоля.ПУСТОЙ, "Иванов И.")

        assertTrue(отчёт.items.isEmpty(), "проверять было нечего, а находки появились: ${отчёт.items}")
        assertTrue("проверять нечего" in отчёт.summary, "отказ обязан звучать словами: ${отчёт.summary}")
    }

    @Test
    fun `полный документ без расхождений не выдаёт «проверять нечего»`() {
        стенд.материал("MM-0001", "Записка о миссии", "mandatory")
        val факт = стенд.факт("F-0001", "группировка", "год развёртывания", "2030", material = "MM-0001")
        стенд.основание(стенд.цель("G-0001", "развернуть группировку", 2030), факт)
        стенд.документы.ensure(СтендПоля.ПРОЕКТ, СтендПоля.ОТЧЁТ, "Иванов И.")

        val отчёт = стенд.проверка.verify(СтендПоля.ПРОЕКТ, СтендПоля.ОТЧЁТ, "Иванов И.")

        assertTrue(отчёт.items.isEmpty(), "расхождений не было, а находки появились: ${отчёт.items}")
        assertTrue("расхождений нет" in отчёт.summary, "сошлось — так и надо сказать: ${отчёт.summary}")
    }

    @Test
    fun `повторная проверка после ручной правки исчезнувшую находку не показывает`() {
        расставить()
        val первый = стенд.проверка.verify(СтендПоля.ПРОЕКТ, СтендПоля.ОТЧЁТ, "Иванов И.")
        assertTrue(первый.items.any { it.issue == Issue.NO_BASIS && it.element == "Э-ЦЕЛИ#2" })

        // Правит ЧЕЛОВЕК: привязывает основание. Служба этого не делает.
        val основание = стенд.факт("F-0011", "приполярные районы", "нуждаются в связности", "да", material = "MM-0001")
        стенд.основание(стенд.store.byCode(Area.Project(СтендПоля.ПРОЕКТ), "G-0002")!!, основание)

        val второй = стенд.проверка.verify(СтендПоля.ПРОЕКТ, СтендПоля.ОТЧЁТ, "Иванов И.")
        assertFalse(
            второй.items.any { it.issue == Issue.NO_BASIS && it.element == "Э-ЦЕЛИ#2" },
            "находка исчезла в поле, а отчёт её всё ещё показывает: ${второй.items}",
        )
        assertTrue(первый.code != второй.code, "каждая проверка заводит НОВЫЙ отчёт, а не переписывает прежний")
    }

    @Test
    fun `проверка против несуществующей базовой линии отказывает`() {
        расставить()
        val беда = assertFailsWith<NoSuchElementException> {
            стенд.проверка.verify(СтендПоля.ПРОЕКТ, СтендПоля.ОТЧЁТ, "Иванов И.", "предварительный обзор")
        }
        assertTrue("предварительный обзор" in беда.message.orEmpty())
    }

    @Test
    fun `проверка без автора отказывает`() {
        расставить()
        assertFailsWith<IllegalArgumentException> {
            стенд.проверка.verify(СтендПоля.ПРОЕКТ, СтендПоля.ОТЧЁТ, " ")
        }
    }

    @Test
    fun `отчёта с чужим кодом в проекте нет`() {
        assertFailsWith<NoSuchElementException> { стенд.проверка.report(СтендПоля.ПРОЕКТ, "VR-9999") }
    }

    @Test
    fun `род находки вне истины схем не существует`() {
        val беда = assertFailsWith<IllegalStateException> { Issue.of("почти_противоречие") }
        assertTrue("истине схем" in беда.message.orEmpty() || "нет" in беда.message.orEmpty())
    }

    @Test
    fun `находка без элемента документа не заводится`() {
        assertFailsWith<IllegalArgumentException> {
            orbita.documents.api.VerificationItem(" ", Issue.NO_BASIS, "что-то не сошлось")
        }
    }

    @Test
    fun `находка без объяснения словами не заводится`() {
        assertFailsWith<IllegalArgumentException> {
            orbita.documents.api.VerificationItem("Э-ЦЕЛИ#1", Issue.CONTRADICTION, "  ")
        }
    }
}

/**
 * Стенд поля знаний для проверки документов: хранилище, реестр связей, порт
 * документов и порт проверки — всё в памяти, с шаблоном отчёта на две
 * страницы.
 */
internal class СтендПоля {

    val mapper = ObjectMapper()
    val store: EntityStore = ПамятьСущностейДок()
    val links: LinkRegistry = ПамятьСвязейДок()

    private val область = Area.Project(ПРОЕКТ)
    private val провенанс = Provenance(Channel.MANUAL, "Иванов И.")

    val документы = DocumentsFactory.documents(
        store, links,
        template = { код -> ШАБЛОНЫ[код]?.let { mapper.readTree(it) } },
        mapper = mapper,
        baselineRoot = java.nio.file.Files.createTempDirectory("basirovaniya").toFile(),
    )

    val проверка = DocumentsFactory.verification(store, links, документы, mapper)

    fun материал(код: String, имя: String, ранг: String): Entity = store.create(
        код, "material", область, "2",
        mapper.createObjectNode().put("name", имя).put("authority", ранг), провенанс,
    )

    fun факт(
        код: String,
        субъект: String,
        предикат: String,
        значение: String,
        единица: String? = null,
        material: String,
        правки: (ObjectNode) -> Unit = {},
    ): Entity {
        val документ = mapper.createObjectNode()
            .put("subject", субъект).put("predicate", предикат).put("value", значение)
            .put("material", material).put("anchor", "§1").put("mark", "И")
            .put("disposition", "adopted")
        единица?.let { документ.put("unit", it) }
        правки(документ)
        return store.create(код, "fact", область, null, документ, провенанс)
    }

    fun цель(код: String, формулировка: String, год: Int?): Entity {
        val документ = mapper.createObjectNode().put("statement", формулировка)
        год?.let { документ.put("year", it) }
        return store.create(код, "goal", область, "4", документ, провенанс)
    }

    /** Нить от сущности к её факту: без неё основания у строки нет. */
    fun основание(сущность: Entity, факт: Entity) {
        links.link("derived_from_fact", сущность.id, факт.id, провенанс)
    }

    /** Спор двух фактов — так его записывает поле знаний (IntakeModes.markConflicts). */
    fun спор(один: String, другой: String) {
        listOf(один to другой, другой to один).forEach { (свой, чужой) ->
            val факт = store.byCode(область, свой)!!
            val документ = (факт.doc.deepCopy() as ObjectNode)
            документ.putArray("conflicts").add(чужой)
            store.update(факт.id, документ, провенанс)
        }
    }

    companion object {
        const val ПРОЕКТ: String = "PJ-7001"
        const val ОТЧЁТ: String = "MCReport"
        const val ПУСТОЙ: String = "EmptyReport"

        private val ШАБЛОНЫ: Map<String, String> = mapOf(
            ОТЧЁТ to """
            {
              "title": "Отчёт к обзору миссии",
              "standard": "NPR 7120.5",
              "sections": [
                {
                  "no": "2",
                  "title": "Цели миссии",
                  "scenes": ["4"],
                  "elements": [
                    {
                      "code": "Э-ЦЕЛИ",
                      "title": "Цели миссии",
                      "select": "goal",
                      "min_rows": 1,
                      "columns": [
                        {"title": "Код", "field": "code"},
                        {"title": "Цель", "field": "statement"},
                        {"title": "Год", "field": "year"}
                      ]
                    }
                  ]
                }
              ]
            }
            """,
            ПУСТОЙ to """
            {
              "title": "Отчёт без содержания",
              "standard": "NPR 7120.5",
              "sections": [
                {"no": "1", "title": "Введение", "scenes": ["1"], "elements": []}
              ]
            }
            """,
        )
    }
}

/** Хранилище сущностей в памяти: проверяются правила находки, а не оснастка базы. */
internal class ПамятьСущностейДок : EntityStore {

    private val записи = linkedMapOf<String, Entity>()
    private val история = mutableMapOf<String, MutableList<Entity>>()
    private var счётчик = 0

    override fun create(
        code: String,
        kind: String,
        area: Area,
        bornIn: String?,
        doc: JsonNode,
        provenance: Provenance,
        status: String,
    ): Entity {
        счётчик += 1
        val сейчас = OffsetDateTime.now()
        val сущность = Entity("id-$счётчик", code, kind, area, bornIn, status, 1, provenance, doc, сейчас, сейчас)
        записи[сущность.id] = сущность
        история.getOrPut(сущность.id) { mutableListOf() }.add(сущность)
        return сущность
    }

    override fun update(id: String, doc: JsonNode, provenance: Provenance, status: String?): Entity {
        val было = записи.getValue(id)
        val стало = было.copy(
            doc = doc, provenance = provenance, status = status ?: было.status,
            version = было.version + 1, updatedAt = OffsetDateTime.now(),
        )
        записи[id] = стало
        история.getOrPut(id) { mutableListOf() }.add(стало)
        return стало
    }

    override fun byId(id: String): Entity? = записи[id]

    override fun byCode(area: Area, code: String): Entity? =
        записи.values.firstOrNull { it.area == area && it.code == code }

    override fun list(area: Area, kind: String?): List<Entity> =
        записи.values.filter { it.area == area && (kind == null || it.kind == kind) }.sortedBy { it.code }

    override fun ofKind(kind: String): List<Entity> = записи.values.filter { it.kind == kind }

    override fun history(id: String): List<Entity> = история[id].orEmpty()
}

/** Реестр связей в памяти: типы стережёт реестр ядра, здесь проверяются сами связи. */
internal class ПамятьСвязейДок : LinkRegistry {

    private val связи = mutableListOf<Link>()
    private var счётчик = 0

    override fun link(
        type: String,
        from: String,
        to: String,
        provenance: Provenance,
        rationale: String?,
        subtype: String?,
    ): Link {
        счётчик += 1
        val связь = Link("link-$счётчик", type, from, to, rationale, provenance, subtype)
        связи += связь
        return связь
    }

    override fun unlink(id: String, provenance: Provenance) {
        связи.removeAll { it.id == id }
    }

    override fun byId(id: String): Link? = связи.firstOrNull { it.id == id }

    override fun from(id: String, type: String?): List<Link> =
        связи.filter { it.from == id && (type == null || it.type == type) }

    override fun to(id: String, type: String?): List<Link> =
        связи.filter { it.to == id && (type == null || it.type == type) }

    override fun confirm(id: String, by: String): Link {
        val связь = связи.first { it.id == id }
        val подтверждённая = связь.copy(confirmedBy = by, confirmedAt = OffsetDateTime.now().toString())
        связи[связи.indexOf(связь)] = подтверждённая
        return подтверждённая
    }
}
