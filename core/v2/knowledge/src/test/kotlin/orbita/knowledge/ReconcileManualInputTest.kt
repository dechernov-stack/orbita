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
import orbita.kernel.api.QosClass
import orbita.knowledge.api.Action
import orbita.knowledge.api.Authority
import orbita.knowledge.api.Candidate
import orbita.knowledge.api.Disposition
import orbita.knowledge.api.FieldDifference
import orbita.knowledge.api.Influence
import orbita.knowledge.api.Comparison
import orbita.knowledge.api.Finding
import orbita.knowledge.api.Match
import orbita.knowledge.api.MustLinkMissing
import orbita.knowledge.api.Question
import orbita.knowledge.api.Verdict
import orbita.knowledge.internal.Reconciler
import orbita.knowledge.schema.GeneratedOntology
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
    fun `повтор нужды иными словами даёт вердикт дополнить — сторона в ключ не входит`() {
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
        // Ключ нужды — суть формулировки (истина 24.09: одна формулировка —
        // одна нужда, носителей много); сторона в ключ не входит.
        assertTrue("statement" in дубль.comparedFields, "находка обязана сказать, чем именно сравнивали: ${дубль.comparedFields}")
        assertTrue("stakeholder" !in дубль.comparedFields, "сторона — носитель, а не часть ключа: ${дубль.comparedFields}")
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

    /**
     * Журнал ПМИ-7, З-03: агрегат из вводных разделов («Владельцы трубопроводов,
     * ЛЭП и удалённых активов») ключом имени не сходится со стороной §3, хотя
     * дублирует её по смыслу. Вторая ступень — по смыслу имени, без модели.
     */
    @Test
    fun `сторона-агрегат дублирует принятую по смыслу имени, и это сказано с кодом`() {
        сторона("ST-0001", "Владельцы трубопроводов")
        сторона("ST-0002", "Минтранс России")
        val кандидат = mapper.createObjectNode().put("name", "Владельцы трубопроводов, ЛЭП и удалённых активов").put("role", "consumer")
        val запуск = сверка.preview(проект, listOf(Candidate("c1", "stakeholder", кандидат)), автор, роль)
        val предмет = запуск.items.single()
        val дубль = предмет.findings.single { it.question == Question.DUPLICATE }
        assertEquals(Verdict.AUGMENT, дубль.verdict, "агрегат — дополнение принятой, не новая сторона")
        assertEquals("ST-0001", дубль.target)
        assertEquals(Match.SEMANTIC, дубль.match)
        assertTrue(Action.MERGE_INTO in дубль.offers, "предложено слить")
        assertTrue("дублирует: ST-0001" in предмет.note, "кого дублирует — сказано кодом: ${предмет.note}")
        assertTrue(store.ofKind("ai_call").isEmpty(), "по смыслу имени — без записи в журнале ИИ")
    }

    /**
     * Журнал ПМИ-7, З-04: «Правительство Российской Федерации» — автор акта,
     * встречается в материале только в шапке и подписи; по истине not_from
     * это не сторона — предложено снять, а не заведено молча.
     */
    @Test
    fun `издатель акта, встречающийся только в шапке и подписи, предлагается к снятию`() {
        val тело = buildString {
            appendLine("ПРАВИТЕЛЬСТВО РОССИЙСКОЙ ФЕДЕРАЦИИ")
            appendLine("ПОСТАНОВЛЕНИЕ от 1 января 2026 г. № 1")
            repeat(40) { appendLine("Перевозчики опасных грузов обязаны передавать телематику оператору системы мониторинга в установленном порядке.") }
            appendLine("Председатель Правительства Российской Федерации")
        }
        store.create("SD-0002", "material", область, "2",
            mapper.createObjectNode().put("name", "Постановление").put("text", тело).put("authority", Authority.MANDATORY), провенанс)
        store.create("F-0002", "fact", область, null,
            mapper.createObjectNode().put("kind", "framing").put("subject", "Правительство Российской Федерации")
                .put("predicate", "издало").put("value", "постановление").put("anchor", "b1").put("material", "SD-0002")
                .put("authority", Authority.MANDATORY).put("disposition", "free"),
            провенанс)
        val кандидат = mapper.createObjectNode().put("name", "Правительство Российской Федерации").put("role", "regulator")
        val запуск = сверка.preview(проект, listOf(Candidate("c1", "stakeholder", кандидат, basis = "F-0002")), автор, роль)
        val предмет = запуск.items.single()
        assertTrue("издатель" in предмет.note, "помета называет причину: ${предмет.note}")
        val новая = предмет.findings.single { it.question == Question.DUPLICATE }
        assertEquals(Action.DISMISS, новая.offers.first(), "первое предложение — снять")
        assertTrue(новая.confidence < 0.5, "уверенность в стороне низкая — «требует внимания»")
        // Та же организация в теле документа — сторона: правило молчит.
        val вТеле = mapper.createObjectNode().put("name", "Перевозчики опасных грузов").put("role", "consumer")
        store.create("F-0003", "fact", область, null,
            mapper.createObjectNode().put("kind", "framing").put("subject", "Перевозчики опасных грузов").put("predicate", "обязаны")
                .put("value", "передавать телематику").put("anchor", "b2").put("material", "SD-0002")
                .put("authority", Authority.MANDATORY).put("disposition", "free"),
            провенанс)
        val второй = сверка.preview(проект, listOf(Candidate("c2", "stakeholder", вТеле, basis = "F-0003")), автор, роль).items.single()
        assertFalse("издатель" in второй.note, "сторона из тела документа не помечена издателем: ${второй.note}")
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
    fun `основание в документе не делает новую нужду узнанной`() {
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

        val предмет = запуск.items.single()
        assertEquals(
            Verdict.CONFIRM,
            предмет.findings.single { it.question == Question.CONNECTION }.verdict,
            "соединение с фактом документа — по-прежнему подтверждение",
        )
        assertEquals(
            Verdict.NEW,
            предмет.verdict,
            "вердикт о кандидате выносит тождество с ПРИНЯТЫМ понятием, а нужды такой в проекте нет",
        )
        assertTrue(
            Action.ACCEPT_NEW in предмет.findings.single { it.question == Question.DUPLICATE }.offers,
            "«завести новое» остаётся предложенным: иначе акцепт молча пройдёт мимо",
        )
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

    @Test
    fun `сторона пакета закрывает связь нужды того же пакета`() {
        val сторонаКандидат = Candidate(
            "c1", "stakeholder",
            mapper.createObjectNode().put("name", "АО «ГЛОНАСС»").put("role", "operator"),
        )
        val нуждаКандидат = Candidate(
            "c2", "need",
            mapper.createObjectNode()
                .put("statement", "телеметрия груза вне зоны покрытия")
                .put("stakeholder", "АО «ГЛОНАСС»"),
        )

        val запуск = сверка.preview(проект, listOf(сторонаКандидат, нуждаКандидат), автор, роль)

        // До заведения стороны связь нужды честно не закрыта — так и должно
        // быть: нехватка считается против проекта, каким он был до пакета.
        assertEquals(listOf("owns→stakeholder"), запуск.items.single { it.localId == "c2" }.blocking)

        сверка.apply(
            проект, запуск.id, "c1", finding = 0, action = Action.ACCEPT_NEW,
            reason = "принято из пакета", author = автор,
        )
        сверка.apply(
            проект, запуск.id, "c2", finding = 0, action = Action.ACCEPT_NEW,
            reason = "принято из пакета", author = автор,
        )

        val сторонаЗаведена = store.list(область, "stakeholder").single()
        val нуждаЗаведена = store.list(область, "need").single()
        assertEquals("АО «ГЛОНАСС»", сторонаЗаведена.doc.path("name").asText())
        assertTrue(
            links.from(сторонаЗаведена.id, "owns").any { it.to == нуждаЗаведена.id },
            "сторона, заведённая тем же пакетом, встала связью нужды",
        )
    }

    @Test
    fun `допущение ложится на факт пометой, а не заводит сущность`() {
        // Истина 15.09: «target_kind: fact (disposition=assumed) — отдельного
        // вида нет; реестр допущений — проекция фактов с диспозицией assumed».
        val кандидат = Candidate(
            "c1", "assumption",
            mapper.createObjectNode().put("statement", "предполагается доступность ракеты-носителя к 2029"),
        )

        val запуск = сверка.preview(проект, listOf(кандидат), автор, роль)
        val итог = сверка.apply(
            проект, запуск.id, "c1", finding = 0, action = Action.ACCEPT_NEW,
            reason = "принято инженером", author = автор,
        )

        assertTrue(итог.created.isEmpty(), "сущности допущение не заводит: ${итог.created}")
        val помеченный = store.byCode(область, итог.updated.single())!!
        assertEquals("fact", помеченный.kind, "помечен именно факт")
        assertEquals("assumed", помеченный.doc.path("disposition").asText())
        assertEquals(автор, помеченный.doc.path("disposition_decision").path("by").asText())
        assertTrue(
            "принято инженером" in помеченный.doc.path("disposition_decision").path("reason").asText(),
            "решение о диспозиции названо причиной: ${помеченный.doc.path("disposition_decision")}",
        )
        assertTrue("владельца и точку подтверждения ставит человек" in итог.note, итог.note)
    }

    @Test
    fun `сервис закрывает связь с нуждой её формулировкой, а не только именем`() {
        // Нужда названа `statement`, имени у неё нет; сервис и цель ссылаются
        // на неё формулировкой. Пока поиск шёл только по `name`, связь не
        // закрывалась никогда — «сервис без нужды» на каждом приёме (216, 17.09).
        сторона("SK-0001", "Минтранс России")
        нужда("ND-0001", "Единое оперативное управление транспортом", "Минтранс России")
        val кандидат = Candidate(
            "c1", "service",
            mapper.createObjectNode()
                .put("name", "Трекинг транспорта вне наземного покрытия")
                .put("needs", "единое оперативное управление транспортом")
                .put("qos_class", "B′"),
        )

        val запуск = сверка.preview(проект, listOf(кандидат), автор, роль)
        val строка = запуск.items.single()
        assertTrue(строка.findings.none { it.question == Question.GAP }, "связь закрыта: ${строка.findings}")
        val номер = строка.findings.indexOfFirst { Action.ACCEPT_NEW in it.offers }
        val итог = сверка.apply(проект, запуск.id, "c1", номер, Action.ACCEPT_NEW, reason = "принято", author = автор)

        assertEquals("service", store.byCode(область, итог.created.single())!!.kind)
    }

    @Test
    fun `замысел и риск из документа заводятся без полей, которые ставит человек или система`() {
        // Истина: «вероятность и последствия 1–5 — ставит человек; из документа
        // только формулировка и класс»; принятие замысла — само решение, штамп
        // ставит система. Схема вида требует эти поля у ПОЛНОЙ записи, и
        // онтология называет их поимённо — иначе сверка отбивала бы замысел и
        // все двенадцать рисков как «не задано обязательное поле».
        val замысел = Candidate(
            "c1", "intent",
            mapper.createObjectNode()
                .put("for_whom", "перевозчики и операторы БАС")
                .put("what", "передача коротких сообщений телеметрии вне зон наземного покрытия")
                .put("where", "территория РФ")
                .put("horizon", "2033"),
        )
        val риск = Candidate(
            "c2", "risk",
            mapper.createObjectNode()
                .put("statement", "Частоты не выделены к сроку развёртывания")
                .put("category", "regulatory")
                .put("measures", "заявка в ГКРЧ до внутреннего обзора"),
        )

        val запуск = сверка.preview(проект, listOf(замысел, риск), автор, роль)
        assertTrue(запуск.refused.isEmpty(), "кандидаты разобраны: ${запуск.refused}")
        val заведено = запуск.items.map { строка ->
            val номер = строка.findings.indexOfFirst { Action.ACCEPT_NEW in it.offers }
            сверка.apply(проект, запуск.id, строка.localId, номер, Action.ACCEPT_NEW, reason = "принято", author = автор)
                .created.single()
        }

        val виды = заведено.map { store.byCode(область, it)!!.kind }.sorted()
        assertEquals(listOf("intent", "risk"), виды)
        // Приём сверкой — и есть решение о замысле: запись рождается принятой,
        // со штампом; иначе сцена 2 остаётся открытой, а 3–12 — запертыми.
        val замыселЗапись = store.byCode(область, заведено.first())!!
        assertEquals("accepted", замыселЗапись.status, "замысел принят решением")
        assertEquals(автор, замыселЗапись.doc.path("accepted_by").asText())
        assertTrue(замыселЗапись.doc.path("accepted_at").asText().isNotBlank())
        val записьРиска = store.byCode(область, заведено.last())!!.doc
        assertTrue(записьРиска.path("probability").isMissingNode, "вероятность пуста — её ставит человек на сцене 11")
    }

    @Test
    fun `повтор нужды с носителем по имени узнаётся дублем принятой`() {
        // Принятая нужда хранит носителя именем; кандидат называет его именем;
        // ключ сравнивает коды — сторона кандидата кодом, сторона принятой нет.
        // Так повторный приём чтения заводил 33 нужды второй раз (216, 17.09).
        сторона("SK-0001", "Минтранс России")
        нужда("ND-0001", "Единое оперативное управление транспортом", "Минтранс России")
        val кандидат = кандидатНужды("Единое оперативное управление транспортом", "Минтранс России")

        val запуск = сверка.preview(проект, listOf(кандидат), автор, роль)

        val строка = запуск.items.single()
        assertTrue(строка.verdict != Verdict.NEW, "повтор узнан, а не назван новым: ${строка.verdict}")
        assertTrue(строка.findings.any { it.question == Question.DUPLICATE }, "вопрос дубля отвечен: ${строка.findings}")
    }

    @Test
    fun `сервис с целевой границей без точного значения принимается`() {
        // «не более 180 мин» — величина {max, unit} без `value`; кандидат-факт
        // получал пустое значение и отбивался («факт без значения», 216, 17.09).
        сторона("SK-0001", "МЧС России")
        нужда("ND-0001", "датчики паводков и пожаров", "МЧС России")
        val содержимое = mapper.createObjectNode()
            .put("name", "Датчики ЧС и гидрологии")
            .put("needs", "датчики паводков и пожаров")
            .put("qos_class", "B′")
        содержимое.putObject("target_measure").put("max", "180").put("unit", "мин")
        val кандидат = Candidate("c1", "service", содержимое)

        val запуск = сверка.preview(проект, listOf(кандидат), автор, роль)
        assertTrue(запуск.refused.isEmpty(), "кандидат разобран: ${запуск.refused}")
        val строка = запуск.items.single()
        val номер = строка.findings.indexOfFirst { Action.ACCEPT_NEW in it.offers }
        val итог = сверка.apply(проект, запуск.id, "c1", номер, Action.ACCEPT_NEW, reason = "принято", author = автор)

        assertEquals("service", store.byCode(область, итог.created.single())!!.kind)
    }

    @Test
    fun `повтор цели с единицей вне справочника узнаётся дублем`() {
        // Ключ цели — формулировка и показатель величины; у единицы «сценария»
        // размерности в справочнике нет, и ключ не складывался вовсе.
        val документ = mapper.createObjectNode().put("statement", "подтвердить реализуемость").put("year", 2027)
        документ.putObject("measure").put("value", 2).put("unit", "сценария")
        store.create("GL-0001", "goal", область, "4", документ, провенанс)
        val содержимое = mapper.createObjectNode().put("statement", "подтвердить реализуемость").put("year", "2027")
        содержимое.putObject("measure").put("value", "2").put("unit", "сценария")

        val запуск = сверка.preview(проект, listOf(Candidate("c1", "goal", содержимое)), автор, роль)

        assertTrue(запуск.items.single().verdict != Verdict.NEW, "повтор цели узнан: ${запуск.items.single().verdict}")
    }

    @Test
    fun `цель без показателя сверяется как утверждение, а не отбивается`() {
        // Цель без величины — не «величина без единицы»: кандидат-факт у неё
        // утверждение. Прежде такой ввод исчезал из сверки («кандидатов 0»).
        val кандидат = Candidate(
            "c1", "goal",
            mapper.createObjectNode().put("statement", "отслеживаемость перевозок доведена до перечня").put("year", "2033"),
        )

        val запуск = сверка.preview(проект, listOf(кандидат), автор, роль)

        assertTrue(запуск.refused.isEmpty(), "цель без показателя разобрана: ${запуск.refused}")
        assertEquals(1, запуск.items.size)
    }

    @Test
    fun `предложение с основанием сверяется фактом документа, второго факта не заводит`() {
        // Кандидат из чтения нёс кандидат-факт «эксперта» поверх факта документа:
        // два источника одного утверждения — и 27 ложных споров держали MCR.
        store.create("SD-0001", "material", область, "2",
            mapper.createObjectNode().put("name", "Записка").put("authority", "mandatory"), провенанс)
        val документ = mapper.createObjectNode()
            .put("kind", "framing").put("subject", "Минтранс России").put("predicate", "нуждается в")
            .put("value", "единое оперативное управление транспортом").put("material", "SD-0001")
            .put("anchor", "s3#1").put("disposition", "free").put("authority", "mandatory").put("source_mark", "И")
        документ.putObject("source").put("material", "SD-0001").put("anchor", "s3#1")
        store.create("F-0001", "fact", область, "2", документ, провенанс)
        сторона("SK-0001", "Минтранс России")
        val былоФактов = store.list(область, "fact").size

        val кандидат = Candidate(
            "c1", "need",
            mapper.createObjectNode().put("statement", "единое оперативное управление транспортом").put("stakeholder", "Минтранс России"),
            origin = orbita.knowledge.api.CandidateOrigin.SYNTHESIS, basis = "F-0001",
        )
        val запуск = сверка.preview(проект, listOf(кандидат), автор, роль)

        val строка = запуск.items.single()
        assertEquals("F-0001", строка.candidateFact, "кандидат-факт — сам факт документа")
        assertEquals(былоФактов, store.list(область, "fact").size, "второго экземпляра утверждения нет")
        val номер = строка.findings.indexOfFirst { Action.ACCEPT_NEW in it.offers }
        val итог = сверка.apply(проект, запуск.id, "c1", номер, Action.ACCEPT_NEW, reason = "принято", author = автор)
        assertEquals("need", store.byCode(область, итог.created.single())!!.kind)
        assertEquals("adopted", store.byCode(область, "F-0001")!!.doc.path("disposition").asText(), "принят сам факт документа")
    }

    @Test
    fun `связь носителя не уезжает на снятую с учёта сторону`() {
        // После «Отменить пакет» в поле лежат и снятая сторона, и заведённая
        // заново. Поиск по имени брал первую попавшуюся — связь `owns` уходила
        // на снятую, и сцена 3 видела восемнадцать сторон без нужд (216, 17.09).
        val снятая = сторона("SK-0001", "Минтранс России")
        store.update(снятая.id, снятая.doc, провенанс, status = "cancelled")
        val живая = сторона("SK-0002", "Минтранс России")

        val запуск = сверка.preview(проект, listOf(кандидатНужды("единое управление транспортом", "Минтранс России")), автор, роль)
        val строка = запуск.items.single()
        val номер = строка.findings.indexOfFirst { Action.ACCEPT_NEW in it.offers }
        val итог = сверка.apply(проект, запуск.id, "c1", номер, Action.ACCEPT_NEW, reason = "принято", author = автор)

        val нужда = store.byCode(область, итог.created.single())!!
        val носители = links.to(нужда.id, "owns").map { it.from }
        assertEquals(listOf(живая.id), носители, "нужда принадлежит ЖИВОЙ стороне, а не снятой")
    }

    @Test
    fun `второй замысел не заводится — он один на проект`() {
        // Истина: intent.identity.key = [project], «один на проект; правится
        // только решением». Ключ не складывался (поля `project` в содержимом
        // нет), и каждый приём предложения заводил ВТОРОЙ замысел (216, 17.09).
        val содержимое = {
            mapper.createObjectNode()
                .put("for_whom", "перевозчики и операторы БАС")
                .put("what", "короткие сообщения телеметрии вне наземного покрытия")
                .put("where", "территория РФ")
                .put("horizon", "2033")
        }
        val первый = сверка.preview(проект, listOf(Candidate("c1", "intent", содержимое())), автор, роль)
        val номер = первый.items.single().findings.indexOfFirst { Action.ACCEPT_NEW in it.offers }
        сверка.apply(проект, первый.id, "c1", номер, Action.ACCEPT_NEW, reason = "принято", author = автор)

        val второй = сверка.preview(проект, listOf(Candidate("c1", "intent", содержимое())), автор, роль)

        val строка = второй.items.single()
        assertTrue(строка.verdict != Verdict.NEW, "замысел узнан принятым, а не заведён вторым: ${строка.verdict}")
        assertEquals(1, store.list(область, "intent").count { it.status != "cancelled" }, "замысел на проекте один")
    }

    @Test
    fun `требование входит черновиком, а недостающее называется для своей сцены`() {
        // Проход владельца 18.09: двенадцать требований записки не входили в
        // проект НИКАК — приём отбивался «не задано обязательное поле «level» ·
        // «category» · «priority» · «verification_method» · «ears_pattern»».
        // Истина схем даёт виду ступень Draft, истина онтологии говорит, что
        // требование уровня проекта дозревает на сцене 8 решением инженера.
        val кандидат = Candidate(
            "c1", "requirement",
            mapper.createObjectNode()
                .put("title", "Отслеживаемость критических грузов")
                .put("statement", "Система должна обеспечивать отслеживаемость 100% объектов перечня к 2033 году"),
        )

        val запуск = сверка.preview(проект, listOf(кандидат), автор, роль)
        val номер = запуск.items.single().findings.indexOfFirst { Action.ACCEPT_NEW in it.offers }
        val итог = сверка.apply(проект, запуск.id, "c1", номер, Action.ACCEPT_NEW, reason = "принято", author = автор)

        val требование = store.byCode(область, итог.created.single())!!
        assertEquals("requirement", требование.kind)
        assertEquals("Draft", требование.status, "ступень записи — из модели состояний вида")
        // Истина схем 18.09 (`required_at`): на приёме спрашивается только
        // названное `accept` — заголовок и формулировка; остальное к
        // базированию и точкам, и названо ИМЕНАМИ полей по-русски.
        listOf("Носитель", "Категория", "Приоритет", "Метод верификации").forEach {
            assertTrue("«$it»" in итог.note, "к базированию названо по-русски: ${итог.note}")
        }
        listOf("carrier", "category", "ears_pattern").forEach {
            assertTrue("«$it»" !in итог.note, "кода поля человеку не показываем: ${итог.note}")
        }
    }

    @Test
    fun `русское значение перечисления ложится кодом, значение чужого поля переезжает`() {
        // Журнал ПМИ-7, З-11: категория пришла ПО-РУССКИ («характеристика»), а у
        // RE-0001 в категории оказалось значение УРОВНЯ — «проектное». Сторожа
        // перечислений на записи не было; истина 19.09 (`enum_labels`) даёт
        // карту русских значений — ею и узнаётся сказанное моделью.
        val перечнем = Candidate(
            "c1", "requirement",
            mapper.createObjectNode()
                .put("title", "Латентность доставки")
                .put("statement", "Система должна доставлять сообщение не более 180 мин")
                .put("category", "характеристика"),
        )
        val запуск = сверка.preview(проект, listOf(перечнем), автор, роль)
        val номер = запуск.items.single().findings.indexOfFirst { Action.ACCEPT_NEW in it.offers }
        val итог = сверка.apply(проект, запуск.id, "c1", номер, Action.ACCEPT_NEW, reason = "принято", author = автор)
        val первое = store.byCode(область, итог.created.single())!!
        assertEquals("performance", первое.doc.path("category").asText(), "русское имя ложится кодом истины")
        assertTrue("Категория" in итог.note, "правка названа словами: ${итог.note}")

        // «проектное» — значение УРОВНЯ: оно переезжает в своё поле, а категория
        // остаётся пустой до базирования («категория — из предложения или пусто»).
        val чужим = Candidate(
            "c2", "requirement",
            mapper.createObjectNode()
                .put("title", "Отслеживаемость грузов")
                .put("statement", "Система должна отслеживать перечень объектов")
                .put("category", "проектное"),
        )
        val второй = сверка.preview(проект, listOf(чужим), автор, роль)
        val н2 = второй.items.single().findings.indexOfFirst { Action.ACCEPT_NEW in it.offers }
        val итог2 = сверка.apply(проект, второй.id, "c2", н2, Action.ACCEPT_NEW, reason = "принято", author = автор)
        val второе = store.byCode(область, итог2.created.single())!!
        assertEquals("project", второе.doc.path("level").asText(), "значение уровня уехало в уровень: ${второе.doc}")
        assertTrue(второе.doc.path("category").asText("").isBlank(), "категория пуста до базирования: ${второе.doc}")
        assertTrue("Уровень" in итог2.note && "Категория" in итог2.note, "перенос назван словами: ${итог2.note}")
    }

    @Test
    fun `понятие на факте не попадает в перечень принятых сущностей`() {
        // Сторож удвоения среза: пока допущение считалось видом «fact», каждый
        // факт поля приезжал в синтез ещё и «принятым понятием».
        val наФакте = GeneratedOntology.concepts.filter { it.marksFact }
        assertEquals(listOf("assumption"), наФакте.map { it.code }, "на факт ложится только допущение")
        assertEquals("fact", наФакте.single().targetKindCode)
    }

    @Test
    fun `влияние стороны считает система по карте истины, а не модель`() {
        // Истина 15.09: «influence вычисляется системой из role по карте …
        // модель поле не заполняет, инженер правит на месте». Карта живёт в
        // ОНТОЛОГИЯ-ФОРМИРОВАНИЯ.influence_map, второй копии в коде нет.
        val кандидат = Candidate(
            "c1", "stakeholder",
            mapper.createObjectNode()
                .put("name", "ГКРЧ")
                .put("role", "regulator")
                // Модель назвала своё значение — и оно обязано быть снято.
                .put("influence", "informed")
                .put("power", 5),
        )

        val запуск = сверка.preview(проект, listOf(кандидат), автор, роль)
        сверка.apply(
            проект, запуск.id, "c1", finding = 0, action = Action.ACCEPT_NEW,
            reason = "принято инженером", author = автор,
        )

        val сторона = store.list(область, "stakeholder").single()
        assertEquals(
            "decides",
            сторона.doc.path("influence").asText(),
            "регулятор решает — по карте истины, а не по слову модели",
        )
        assertTrue(
            сторона.doc.path("power").isMissingNode,
            "силу ставит человек на сцене 3: значение модели снято",
        )
    }

    @Test
    fun `карта влияния покрывает все роли истины схем`() {
        // Сторож расхождения двух истин: роль, которую схемы знают, а карта
        // влияния — нет, оставила бы сторону без вычисляемого поля молча.
        val роли = orbita.kernel.schema.GeneratedKinds.byCode["stakeholder"]
            ?.enums?.get("role").orEmpty()
        val влияния = orbita.kernel.schema.GeneratedKinds.byCode["stakeholder"]
            ?.enums?.get("influence").orEmpty()

        assertTrue(роли.isNotEmpty(), "перечень ролей в истине схем есть")
        val безВлияния = роли.filterNot { it in Influence.byRole }
        assertTrue(безВлияния.isEmpty(), "роли без влияния в карте истины: $безВлияния")
        val чужие = Influence.byRole.values.filterNot { it in влияния }
        assertTrue(чужие.isEmpty(), "карта называет влияние вне перечня истины схем: $чужие")
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

    /**
     * Решение владельца 12.09 §1: класс обслуживания у нужды обязателен, но
     * до сцены сервисов допустимо значение TBR. Нужда заводится и без класса —
     * и уносит с собой ответственного и ворота, где класс обязан появиться.
     */
    @Test
    fun `нужда без класса обслуживания заводится с TBR — ответственный и ворота названы`() {
        val минтранс = сторона("SK-0001", "Минтранс России")
        val запуск = сверка.preview(проект, listOf(кандидатНужды("В Арктике требуется связь")), автор, роль)

        сверка.apply(
            проект, запуск.id, "c1", finding = 0, action = Action.ACCEPT_NEW,
            target = минтранс.code, reason = "сторона выбрана инженером", author = автор,
        )

        val класс = store.list(область, "need").single().doc.path("qos_class")
        assertFalse(QosClass.assigned(класс), "класса ещё нет — значение TBR")
        assertEquals(автор, класс.path("owner").asText(), "TBR без ответственного был бы вечным")
        assertEquals(QosClass.GATE, класс.path("gate").asText(), "ворота TBR — сцена сервисов")
    }

    /**
     * Решение владельца 12.09 §2: у вехи свой вид. Понятие «этап» перестало
     * ложиться на факт и заводит сущность — с основанием, которое система
     * знает сама.
     */
    @Test
    fun `этап заводится видом вехи, и основанием ему встаёт кандидат-факт`() {
        val содержимое = mapper.createObjectNode()
            .put("name", "этап 1: до 50 КА")
            .put("kind", "program_stage")
            .put("date", "2028-12-31")
        val запуск = сверка.preview(проект, listOf(Candidate("c1", "milestone", содержимое)), автор, роль)

        сверка.apply(
            проект, запуск.id, "c1", finding = 0, action = Action.ACCEPT_NEW,
            target = null, reason = "этап записки", author = автор,
        )

        val веха = store.list(область, "milestone").single()
        assertEquals("этап 1: до 50 КА", веха.doc.path("name").asText())
        val кандидатФакт = store.byCode(область, запуск.items.single().candidateFact)!!
        assertEquals(
            кандидатФакт.code, веха.doc.path("source").asText(),
            "основание вехи — тот факт, из которого она выведена: спрашивать его у человека незачем",
        )
    }

    /**
     * Род вехи — то единственное, ради чего вид отделён от точки. Онтология его
     * не называет, взять системе неоткуда: запись без него не отвечала бы
     * собственной схеме, и молча её быть не должно.
     */
    @Test
    fun `этап без рода не заводится — отказ называет поле`() {
        val содержимое = mapper.createObjectNode().put("name", "этап 1: до 50 КА")
        val запуск = сверка.preview(проект, listOf(Candidate("c1", "milestone", содержимое)), автор, роль)

        val отказ = assertFailsWith<IllegalArgumentException> {
            сверка.apply(
                проект, запуск.id, "c1", finding = 0, action = Action.ACCEPT_NEW,
                target = null, reason = "этап записки", author = автор,
            )
        }
        assertTrue("kind" in отказ.message.orEmpty(), "отказ обязан назвать поле: ${отказ.message}")
        assertTrue(store.list(область, "milestone").isEmpty(), "неполной записи быть не должно")
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
    fun `копии одной нужды у двух сторон в одном запуске дают одну нужду с двумя носителями`() {
        // Одна формулировка — одна нужда, носителей много (истина 24.09):
        // в предпросмотре обе копии «новые» — принятой первой ещё нет, — а
        // принятая второй прибавляет свою сторону носителем, копии не заводит.
        сторона("SK-0001", "Минтранс России")
        сторона("SK-0002", "Росморпорт")
        val вторая = Candidate(
            "c2", "need",
            mapper.createObjectNode().put("statement", "Непрерывный мониторинг судов на СМП").put("stakeholder", "Росморпорт"),
        )
        val запуск = сверка.preview(
            проект, listOf(кандидатНужды("Непрерывный мониторинг судов на СМП", "Минтранс России"), вторая), автор, роль,
        )
        assertEquals(listOf(Verdict.NEW, Verdict.NEW), запуск.items.map { it.verdict }, "до принятия обе копии — новые")

        val первое = сверка.apply(
            проект, запуск.id, "c1",
            запуск.items.first { it.localId == "c1" }.findings.indexOfFirst { Action.ACCEPT_NEW in it.offers },
            Action.ACCEPT_NEW, reason = "принято", author = автор,
        )
        val второе = сверка.apply(
            проект, запуск.id, "c2",
            запуск.items.first { it.localId == "c2" }.findings.indexOfFirst { Action.ACCEPT_NEW in it.offers },
            Action.ACCEPT_NEW, reason = "принято", author = автор,
        )

        val нужды = store.list(область, "need").filter { it.status != "cancelled" }
        assertEquals(1, нужды.size, "копии нет: ${нужды.map { it.code }}")
        val нужда = нужды.single()
        assertEquals(первое.created, listOf(нужда.code))
        assertTrue(второе.created.isEmpty(), "вторая копия сущности не завела: ${второе.created}")
        assertEquals(listOf(нужда.code), второе.updated, "вторая копия дополнила принятую")
        assertTrue("уже принята" in второе.note, второе.note)
        assertEquals(
            setOf("SK-0001", "SK-0002"), links.to(нужда.id, "owns").map { store.byId(it.from)!!.code }.toSet(),
            "обе стороны — носители",
        )
        assertEquals(
            setOf("SK-0001", "SK-0002"), нужда.doc.path("stakeholders").map { store.byId(it.asText())!!.code }.toSet(),
            "поле stakeholders зеркалит связи",
        )
        assertEquals(2, links.from(нужда.id, "derived_from_fact").size, "оба кандидата-факта — основания одной нужды")
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
