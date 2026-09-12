// Сверка ручного ввода: четыре вопроса — и ни одного вызова службы.
//
// Ни один тест здесь не поднимает базу и не зовёт модель: ступень 1 по
// построению работает на данных кандидата, принятых сущностях и фактах поля.
// Если для дубля по ключу однажды понадобится живой вызов, мера «дубль по
// ключу найден БЕЗ вызова, журнал ИИ не прирастает» будет потеряна — и
// сломается именно этот файл.
//
// Хранилище и реестр связей здесь — память: у модуля знаний нет оснастки базы,
// а проверять надо правила сверки, а не запись в Postgres.
package orbita.knowledge

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.Link
import orbita.kernel.api.LinkRegistry
import orbita.kernel.api.Provenance
import orbita.knowledge.api.Action
import orbita.knowledge.api.Authority
import orbita.knowledge.api.Candidate
import orbita.knowledge.api.Disposition
import orbita.knowledge.api.FieldDifference
import orbita.knowledge.api.Comparison
import orbita.knowledge.api.Finding
import orbita.knowledge.api.Match
import orbita.knowledge.api.MustLinkMissing
import orbita.knowledge.api.Question
import orbita.knowledge.api.Verdict
import orbita.knowledge.internal.Reconciler
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReconcileManualInputTest {

    private val mapper = ObjectMapper()
    // Типы — портами ядра: тест проверяет правила сверки, а не оснастку.
    private val store: EntityStore = ПамятьСущностей()
    private val links: LinkRegistry = ПамятьСвязей()
    private val сверка = Reconciler(store, links, mapper)

    private val проект = "PJ-7001"
    private val область = Area.Project(проект)
    private val автор = "Иванов И."
    private val роль = "ведущий СИ"
    private val провенанс = Provenance(Channel.MANUAL, автор)

    private fun сторона(код: String, имя: String): Entity = store.create(
        код, "stakeholder", область, "3",
        mapper.createObjectNode().put("name", имя).put("role", "заказчик"), провенанс,
    )

    private fun нужда(код: String, формулировка: String, сторона: String): Entity = store.create(
        код, "need", область, "3",
        mapper.createObjectNode().put("statement", формулировка).put("stakeholder", сторона), провенанс,
    )

    private fun цель(код: String, формулировка: String, год: Int): Entity {
        val документ = mapper.createObjectNode().put("statement", формулировка).put("year", год)
        документ.putObject("measure").put("key", "fleet").put("value", 180).put("unit", "МКА")
        return store.create(код, "goal", область, "4", документ, провенанс)
    }

    /** Материал с рангом и его факт: у принятой сущности должно быть основание. */
    private fun основание(цель: Entity, ранг: String): Entity {
        store.create(
            "SD-0001", "material", область, "2",
            mapper.createObjectNode().put("name", "Записка о миссии").put("authority", ранг), провенанс,
        )
        val факт = store.create(
            "F-0001", "fact", область, null,
            mapper.createObjectNode()
                .put("kind", "quantity").put("subject", "группировка").put("predicate", "развёрнута к году")
                .put("value", "2032").put("anchor", "b1").put("material", "SD-0001")
                .put("authority", ранг).put("disposition", "adopted"),
            провенанс,
        )
        links.link("derived_from_fact", цель.id, факт.id, провенанс)
        return факт
    }

    private fun кандидатНужды(формулировка: String, носитель: String? = null): Candidate {
        val содержимое = mapper.createObjectNode().put("statement", формулировка)
        носитель?.let { содержимое.put("stakeholder", it) }
        return Candidate("c1", "need", содержимое)
    }

    /** Снимок принятого: код, версия и документ — им и проверяется «ничего не изменилось». */
    private fun снимок(): List<String> = listOf("stakeholder", "need", "goal")
        .flatMap { store.list(область, it) }
        .sortedBy { it.code }
        .map { "${it.code}|${it.version}|${it.doc}" }

    // --- четыре вопроса ----------------------------------------------------

    @Test
    fun `повтор нужды той же стороны иными словами даёт вердикт дополнить`() {
        val минтранс = сторона("SK-0001", "Минтранс России")
        нужда("ND-0001", "Необходимо обеспечить связь в Арктике", минтранс.code)

        val запуск = сверка.preview(
            проект, listOf(кандидатНужды("В Арктике требуется связь", "Минтранс России")), автор, роль,
        )

        val предмет = запуск.items.single()
        assertEquals(Verdict.AUGMENT, предмет.verdict, "тот же ключ — это дополнение, а не вторая нужда")
        val дубль = предмет.findings.single { it.question == Question.DUPLICATE }
        assertEquals("ND-0001", дубль.target)
        assertEquals(Match.KEY, дубль.match, "дубль найден ключом идентичности, а не смыслом")
        assertTrue(
            дубль.comparedFields.containsAll(listOf("stakeholder", "statement")),
            "находка обязана сказать, чем именно сравнивали: ${дубль.comparedFields}",
        )
    }

    @Test
    fun `дубль по ключу найден без единого вызова службы`() {
        val минтранс = сторона("SK-0001", "Минтранс России")
        нужда("ND-0001", "Необходимо обеспечить связь в Арктике", минтранс.code)

        val запуск = сверка.preview(
            проект, listOf(кандидатНужды("В Арктике требуется связь", "Минтранс России")), автор, роль,
        )

        assertFalse(запуск.aiCalled, "ступень 1 бесплатна: живого вызова здесь нет")
        assertTrue(store.ofKind("ai_call").isEmpty(), "журнал ИИ не прирос ни одной записью")
    }

    @Test
    fun `разные годы у одной цели дают противоречие с рангами обоих значений`() {
        val принятая = цель("MG-0001", "развернуть группировку", 2032)
        основание(принятая, Authority.MANDATORY)

        val кандидат = mapper.createObjectNode().put("statement", "развернуть группировку").put("year", 2030)
        кандидат.putObject("measure").put("key", "fleet").put("value", 180).put("unit", "МКА")
        val запуск = сверка.preview(проект, listOf(Candidate("c1", "goal", кандидат)), автор, роль)

        val предмет = запуск.items.single()
        assertEquals(Verdict.CONTRADICT, предмет.verdict, "разные годы — спор, а не дополнение")
        val спор = предмет.findings.single { it.question == Question.CONTRADICTION }
        val отличие = assertNotNull(спор.difference, "противоречие без отличия — «похоже» без «чем именно»")
        assertEquals("year", отличие.field)
        assertEquals("2030", отличие.mine)
        assertEquals("2032", отличие.theirs)
        assertTrue(отличие.reason.contains(Authority.word(Authority.EXPERT)), "ранг кандидата назван словами")
        assertTrue(отличие.reason.contains(Authority.word(Authority.MANDATORY)), "ранг принятого назван словами")
        assertEquals(2032, store.byCode(область, "MG-0001")!!.doc.path("year").asInt(), "значение цели не изменилось")
    }

    @Test
    fun `факт независимого документа становится предложением привязать основание`() {
        val минтранс = сторона("SK-0001", "Минтранс России")
        store.create(
            "SD-0001", "material", область, "2",
            mapper.createObjectNode().put("name", "Записка о миссии").put("authority", Authority.MANDATORY),
            провенанс,
        )
        store.create(
            "F-0001", "fact", область, null,
            mapper.createObjectNode()
                .put("kind", "framing").put("subject", минтранс.code).put("predicate", "нуждается в")
                .put("value", "Необходимо обеспечить связь в Арктике").put("anchor", "b2")
                .put("material", "SD-0001").put("authority", Authority.MANDATORY),
            провенанс,
        )

        val запуск = сверка.preview(
            проект, listOf(кандидатНужды("В Арктике требуется связь", "Минтранс России")), автор, роль,
        )

        val соединение = запуск.items.single().findings.single { it.question == Question.CONNECTION }
        assertEquals(Verdict.CONFIRM, соединение.verdict, "независимый документ подтверждает, а не спорит")
        assertEquals(listOf("F-0001"), соединение.basis)
        assertTrue(Action.LINK_BASIS in соединение.offers, "человеку предложено привязать основание")
    }

    @Test
    fun `нужда без стороны даёт блокирующую нехватку с именем связи`() {
        val запуск = сверка.preview(проект, listOf(кандидатНужды("В Арктике требуется связь")), автор, роль)

        val предмет = запуск.items.single()
        val нехватка = предмет.findings.single { it.question == Question.GAP }
        assertTrue(нехватка.blocking, "незакрытая обязательная связь останавливает принятие")
        assertEquals("owns→stakeholder", нехватка.missing)
        assertEquals(listOf("owns→stakeholder"), предмет.blocking)
    }

    // --- решения человека --------------------------------------------------

    @Test
    fun `принятие нового заводит сущность со связью носителя и с основанием`() {
        val минтранс = сторона("SK-0001", "Минтранс России")
        val запуск = сверка.preview(проект, listOf(кандидатНужды("В Арктике требуется связь")), автор, роль)

        val итог = сверка.apply(
            проект, запуск.id, "c1", finding = 0, action = Action.ACCEPT_NEW,
            target = минтранс.code, reason = "сторона выбрана инженером", author = автор,
        )

        val заведённая = store.list(область, "need").single()
        assertEquals(listOf(заведённая.code), итог.created)
        assertTrue(links.from(минтранс.id, "owns").any { it.to == заведённая.id }, "носитель встал связью")
        val кандидатФакт = store.byCode(область, запуск.items.single().candidateFact)!!
        assertTrue(
            links.from(заведённая.id, "derived_from_fact").any { it.to == кандидатФакт.id },
            "у заведённой нужды есть факт-основание",
        )
        assertEquals(
            Disposition.ADOPTED.name.lowercase(), кандидатФакт.doc.path("disposition").asText(),
            "принятый кандидат-факт получает диспозицию решения",
        )
    }

    @Test
    fun `слияние не переписывает принятое а привязывает кандидата основанием`() {
        val минтранс = сторона("SK-0001", "Минтранс России")
        val принятая = нужда("ND-0001", "Необходимо обеспечить связь в Арктике", минтранс.code)
        val запуск = сверка.preview(
            проект, listOf(кандидатНужды("В Арктике требуется связь", "Минтранс России")), автор, роль,
        )
        val номер = запуск.items.single().findings.indexOfFirst { Action.MERGE_INTO in it.offers }

        сверка.apply(
            проект, запуск.id, "c1", finding = номер, action = Action.MERGE_INTO,
            target = принятая.code, reason = "та же нужда иными словами", author = автор,
        )

        val после = store.byCode(область, "ND-0001")!!
        assertEquals(принятая.version, после.version, "поля принятого не переписаны")
        assertEquals(принятая.doc, после.doc, "документ принятого не тронут")
        assertTrue(
            links.from(принятая.id, "derived_from_fact").isNotEmpty(),
            "кандидат-факт привязан к принятой нужде основанием",
        )
    }

    @Test
    fun `повторное чтение запуска отдаёт те же находки без нового вызова`() {
        val минтранс = сторона("SK-0001", "Минтранс России")
        нужда("ND-0001", "Необходимо обеспечить связь в Арктике", минтранс.code)
        val запуск = сверка.preview(
            проект, listOf(кандидатНужды("В Арктике требуется связь", "Минтранс России")), автор, роль,
        )

        val снова = сверка.run(проект, запуск.id)

        assertEquals(запуск.items.map { it.verdict }, снова.items.map { it.verdict })
        assertEquals(запуск.sliceFingerprint, снова.sliceFingerprint, "тот же срез — тот же отпечаток")
        assertFalse(снова.aiCalled, "чтение готового запуска службу не зовёт")
        assertEquals(1, store.ofKind("synthesis_run").size, "второго запуска чтение не заводит")
    }

    @Test
    fun `десять вводов подряд дают один запуск сверки`() {
        сторона("SK-0001", "Минтранс России")
        val кандидаты = (1..10).map { номер ->
            Candidate(
                "c$номер", "need",
                mapper.createObjectNode()
                    .put("statement", "нужда номер $номер")
                    .put("stakeholder", "SK-0001"),
            )
        }

        val запуск = сверка.preview(проект, кандидаты, автор, роль)

        assertEquals(10, запуск.items.size)
        assertEquals(1, store.ofKind("synthesis_run").size, "батч — один запуск, а не десять")
    }

    // --- отказные: служба не сливает, не переписывает, не выбирает ----------

    @Test
    fun `предпросмотр не меняет ни одной принятой сущности`() {
        val минтранс = сторона("SK-0001", "Минтранс России")
        нужда("ND-0001", "Необходимо обеспечить связь в Арктике", минтранс.code)
        val было = снимок()

        сверка.preview(проект, listOf(кандидатНужды("В Арктике требуется связь", "Минтранс России")), автор, роль)

        assertEquals(было, снимок(), "ни одного слияния и ни одной правки без клика")
    }

    @Test
    fun `решение без причины не ставится`() {
        сторона("SK-0001", "Минтранс России")
        val запуск = сверка.preview(проект, listOf(кандидатНужды("В Арктике требуется связь")), автор, роль)

        assertFailsWith<IllegalArgumentException>("через год «снять» без объяснения читается как случайность") {
            сверка.apply(
                проект, запуск.id, "c1", finding = 0, action = Action.DISMISS,
                target = null, reason = "  ", author = автор,
            )
        }
    }

    @Test
    fun `решение без автора не ставится`() {
        сторона("SK-0001", "Минтранс России")
        val запуск = сверка.preview(проект, listOf(кандидатНужды("В Арктике требуется связь")), автор, роль)

        assertFailsWith<IllegalArgumentException>("кто решил — часть решения") {
            сверка.apply(
                проект, запуск.id, "c1", finding = 0, action = Action.DISMISS,
                target = null, reason = "не то", author = "",
            )
        }
    }

    @Test
    fun `акцепт с незакрытой обязательной связью сущности не заводит`() {
        val запуск = сверка.preview(проект, listOf(кандидатНужды("В Арктике требуется связь")), автор, роль)

        val отказ = assertFailsWith<MustLinkMissing> {
            сверка.apply(
                проект, запуск.id, "c1", finding = 0, action = Action.ACCEPT_NEW,
                target = null, reason = "принимаем как есть", author = автор,
            )
        }

        assertEquals("owns→stakeholder", отказ.missing)
        assertTrue(store.list(область, "need").isEmpty(), "сущности в сторе нет")
    }

    @Test
    fun `действие вне предложенных находкой отвергается`() {
        сторона("SK-0001", "Минтранс России")
        val запуск = сверка.preview(
            проект, listOf(кандидатНужды("В Арктике требуется связь", "SK-0001")), автор, роль,
        )

        assertFailsWith<IllegalArgumentException>("слить не с чем: дубля находка не нашла") {
            сверка.apply(
                проект, запуск.id, "c1", finding = 0, action = Action.MERGE_INTO,
                target = "ND-0001", reason = "хочу слить", author = автор,
            )
        }
    }

    @Test
    fun `находка без названных полей сравнения не создаётся`() {
        assertFailsWith<IllegalArgumentException>("«похоже» без «чем именно» — брак") {
            Finding(
                question = Question.DUPLICATE,
                verdict = Verdict.AUGMENT,
                target = "ND-0001",
                comparedFields = emptyList(),
                difference = FieldDifference(Comparison.EQUAL, "statement", "одно", "другое"),
                offers = listOf(Action.MERGE_INTO),
            )
        }
    }

    @Test
    fun `находка без отличия не создаётся`() {
        assertFailsWith<IllegalArgumentException>("сказать «похоже» и не сказать «чем» нельзя") {
            Finding(
                question = Question.DUPLICATE,
                verdict = Verdict.AUGMENT,
                target = "ND-0001",
                comparedFields = listOf("statement"),
                difference = null,
                offers = listOf(Action.MERGE_INTO),
            )
        }
    }

    @Test
    fun `у находки нет поля победителя`() {
        val поля = Finding::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(
            поля.none { it.contains("winner") || it.contains("winning") },
            "ранг подсказывает, победителя выбирает человек: $поля",
        )
    }

    @Test
    fun `понятие вне онтологии кандидатом не становится`() {
        assertFailsWith<IllegalStateException>("понятие вне онтологии не образуется") {
            Candidate("c1", "веха_придуманная_в_коде", mapper.createObjectNode().put("statement", "что-то"))
        }
    }

    @Test
    fun `ввод без роли автора не сверяется`() {
        assertFailsWith<IllegalArgumentException>("источник ручного ввода — учётка, роль и дата") {
            сверка.preview(проект, listOf(кандидатНужды("В Арктике требуется связь")), автор, "")
        }
    }

    @Test
    fun `повторные местные номера кандидатов отвергаются`() {
        val два = listOf(
            кандидатНужды("первая нужда"),
            кандидатНужды("вторая нужда"),
        )

        assertFailsWith<IllegalArgumentException>("находка вернулась бы не в ту строку ввода") {
            сверка.preview(проект, два, автор, роль)
        }
    }

    @Test
    fun `сверка без кандидатов отвергается`() {
        assertFailsWith<IllegalArgumentException>("сверять нечего") {
            сверка.preview(проект, emptyList(), автор, роль)
        }
    }

    @Test
    fun `решённый кандидат второй раз не решается`() {
        сторона("SK-0001", "Минтранс России")
        val запуск = сверка.preview(проект, listOf(кандидатНужды("В Арктике требуется связь")), автор, роль)
        сверка.apply(
            проект, запуск.id, "c1", finding = 0, action = Action.DISMISS,
            target = null, reason = "введено по ошибке", author = автор,
        )

        assertFailsWith<IllegalArgumentException>("по этой находке решение уже принято") {
            сверка.apply(
                проект, запуск.id, "c1", finding = 0, action = Action.DISMISS,
                target = null, reason = "и ещё раз", author = автор,
            )
        }
    }

    @Test
    fun `запуск с нерешённым кандидатом остаётся открытым`() {
        val минтранс = сторона("SK-0001", "Минтранс России")
        val запуск = сверка.preview(
            проект, listOf(кандидатНужды("В Арктике требуется связь", минтранс.code)), автор, роль,
        )

        assertEquals(listOf(запуск.id), сверка.open(проект).map { it.id })

        сверка.apply(
            проект, запуск.id, "c1", finding = 0, action = Action.ACCEPT_NEW,
            target = минтранс.code, reason = "новая нужда стороны", author = автор,
        )

        assertTrue(сверка.open(проект).isEmpty(), "решённый кандидат закрывает запуск")
        assertEquals(
            Action.ACCEPT_NEW, сверка.run(проект, запуск.id).items.single().decided,
            "решение человека записано в сам запуск",
        )
    }
}

// --- оснастка: хранилище и реестр связей в памяти ---------------------------

/**
 * Хранилище в памяти: правка заводит НОВУЮ версию, прежняя остаётся историей —
 * ровно как у хранилища ядра, иначе «версия не выросла» проверять нечем.
 */
private class ПамятьСущностей : EntityStore {

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
private class ПамятьСвязей : LinkRegistry {

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
