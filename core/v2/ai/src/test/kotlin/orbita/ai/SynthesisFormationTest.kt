// Синтез постановки из поля: срез, диф и цена вопроса в токенах.
//
// Проверяется не «служба ответила», а то, чего служба НЕ вправе сделать:
// съесть поле целиком мимо потолка среза, позвать модель дважды на один и тот
// же срез, завести запуск на каждое событие поля, пустить в постановку
// непроверенный источник, выдать «похоже» без названного поля отличия и
// тронуть хоть одну принятую сущность.
//
// Канал к модели подменён: сеть в тесте не нужна, а поведение службы — кэш, журнал,
// окно накопления — проверяется без неё.
package orbita.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.ai.api.AiFactory
import orbita.ai.api.Answer
import orbita.ai.api.ProviderUnavailable
import orbita.ai.api.SynthesisRun
import orbita.ai.api.Transport
import orbita.ai.internal.BackgroundSynthesizer
import orbita.ai.internal.Synthesizer
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.Provenance
import orbita.knowledge.api.KnowledgeFactory
import orbita.knowledge.api.SourceMark
import java.time.Duration
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SynthesisFormationTest {

    private val mapper = ObjectMapper()
    private val store: EntityStore = ПамятьПоля()
    private val знания = KnowledgeFactory.intake(store, null, mapper)
    private val транспорт = КаналСинтеза()
    private val служба = AiFactory.service(store, транспорт, mapper)
    private val синтез = Synthesizer(store, знания, служба, mapper)

    // --- срез поля ----------------------------------------------------------

    @Test
    fun `срез не превышает потолка и называет урезание словами`() {
        паспорт(ПРОЕКТ, полеЗнаний = true)
        repeat(60) { номер -> факт("F-%04d".format(номер + 1), "узел ${номер + 1}", "описан") }

        val запуск = синтез.synthesize(ПРОЕКТ, "manual", "инженер")

        assertEquals(Synthesizer.ПОТОЛОК, запуск.sliceSize, "срез обязан укладываться в потолок онтологии")
        assertTrue(
            "срез урезан: ${Synthesizer.ПОТОЛОК} из 60" in (запуск.note ?: ""),
            "урезание называется словами: ${запуск.note}",
        )
    }

    @Test
    fun `урезание идёт по рангу — обязательное вытесняет справочное`() {
        паспорт(ПРОЕКТ, полеЗнаний = true)
        repeat(60) { номер -> факт("F-%04d".format(номер + 1), "узел ${номер + 1}", "описан", authority = "reference") }
        факт("F-0100", "Минтранс России", "нуждается в", authority = "mandatory")

        синтез.synthesize(ПРОЕКТ, "manual", "инженер")

        val промпт = транспорт.промпты.single()
        assertTrue("F-0100" in промпт, "обязательный факт вытесняет справочные, а не наоборот")
    }

    @Test
    fun `сомнительный факт в срез не попадает, пока человек его не принял`() {
        паспорт(ПРОЕКТ, полеЗнаний = true)
        факт("F-0001", "внешний обзор", "утверждает", authority = "doubtful", disposition = "noted")
        факт("F-0002", "записка", "утверждает", authority = "mandatory", disposition = "noted")

        синтез.synthesize(ПРОЕКТ, "manual", "инженер")
        val первый = транспорт.промпты.last()
        assertFalse("F-0001" in первый, "результат исследования до подтверждения источников в постановку не идёт")
        assertTrue("F-0002" in первый)

        принять("F-0001")
        синтез.synthesize(ПРОЕКТ, "manual", "инженер")
        assertTrue(
            "F-0001" in транспорт.промпты.last(),
            "принятый рукой факт входит в срез: явное включение — решение человека",
        )
    }

    // --- диф «поле → постановка» -------------------------------------------

    @Test
    fun `три документа разного ранга дают одну постановку с происхождением из трёх`() {
        паспорт(ПРОЕКТ, полеЗнаний = true)
        факт("F-0001", "Минтранс России", "нуждается в", material = "M-0001", authority = "mandatory")
        факт("F-0002", "Минтранс России", "нуждается в", material = "M-0002", authority = "expert")
        факт("F-0003", "Минтранс России", "нуждается в", material = "M-0003", authority = "reference")
        транспорт.ответ = ответ(
            """
            {"concept":"need",
             "payload":{"statement":"связь в Арктике без наземной инфраструктуры","stakeholder":"Минтранс России"},
             "basis":["F-0001","F-0002","F-0003"],"verdict":"new"}
            """.trimIndent()
        )

        val запуск = синтез.synthesize(ПРОЕКТ, "manual", "инженер")

        val предложение = запуск.diff.new.single()
        assertEquals(1, запуск.diff.size, "три факта об одном — одна постановка, а не три")
        assertEquals(listOf("M-0001", "M-0002", "M-0003"), предложение.basis.map { it.material })
        assertEquals(
            listOf("mandatory", "expert", "reference"),
            предложение.basis.map { it.authority },
            "ранг основания берётся у факта, а не у ответа службы",
        )
        assertTrue(предложение.missing.isEmpty(), "сторона названа — обязательная связь закрыта")
        assertEquals(1, запуск.proposals.size, "у предложения есть карточка: решение принимается по ней")
    }

    @Test
    fun `цель с разными годами — противоречие с рангами обоих и без выбора победителя`() {
        паспорт(ПРОЕКТ, полеЗнаний = true)
        понятие("MG-0001", "goal", mapOf("statement" to "развернуть группировку", "year" to "2030"))
        факт("F-0001", "группировка", "развёрнута к году", "2030", material = "M-0001", authority = "mandatory")
        факт("F-0002", "группировка", "развёрнута к году", "2032", material = "M-0002", authority = "reference")
        транспорт.ответ = ответ(
            """
            {"concept":"goal","payload":{"statement":"развернуть группировку","year":"2032"},
             "basis":["F-0001","F-0002"],"verdict":"contradict","target":"MG-0001","diff_field":"year"}
            """.trimIndent()
        )

        val предложение = синтез.synthesize(ПРОЕКТ, "manual", "инженер").diff.contradict.single()

        assertEquals("MG-0001", предложение.targetRef)
        assertEquals("year", предложение.diffField, "различие названо полем, а не процентом близости")
        assertTrue("обязательный" in предложение.rankHint, предложение.rankHint)
        assertTrue("справочный" in предложение.rankHint, предложение.rankHint)
        assertTrue("2032" in предложение.payload.values.joinToString(" "), "второе значение показывается")
        assertTrue(
            "covers→need>=1" in предложение.missing,
            "цель без нужды остаётся предложением с пометой, а не сущностью",
        )
    }

    @Test
    fun `подтверждение из другого материала — свидетельство, а не дубль`() {
        паспорт(ПРОЕКТ, полеЗнаний = true)
        понятие("ND-0001", "need", mapOf("statement" to "связь в Арктике"))
        факт("F-0001", "Минтранс России", "нуждается в", material = "M-0007", authority = "expert")
        транспорт.ответ = ответ(
            """
            {"concept":"need","payload":{"statement":"связь в Арктике","stakeholder":"Минтранс России"},
             "basis":["F-0001"],"verdict":"confirm","target":"ND-0001","diff_field":"statement"}
            """.trimIndent()
        )

        val запуск = синтез.synthesize(ПРОЕКТ, "manual", "инженер")

        val предложение = запуск.diff.confirm.single()
        assertEquals("ND-0001", предложение.targetRef)
        assertEquals("M-0007", предложение.basis.single().material, "подтверждение идёт из ДРУГОГО материала")
        val карточка = store.byCode(Area.Project(ПРОЕКТ), запуск.proposals.single())
        assertNotNull(карточка, "карточка предложения заведена")
        val предмет = карточка.doc.path("items").first()
        assertEquals("pending", предмет.path("decision").asText(), "решения нет, пока его не принял человек")
        assertTrue("подтверждает" in предмет.path("reason").asText(), предмет.path("reason").asText())
    }

    @Test
    fun `метка достоверности берётся у слабейшего основания`() {
        паспорт(ПРОЕКТ, полеЗнаний = true)
        факт("F-0001", "группировка", "развёрнута к году", "2030", mark = "И")
        факт("F-0002", "группировка", "развёрнута к году", "2032", mark = "П")
        транспорт.ответ = ответ(
            """
            {"concept":"goal","payload":{"statement":"развернуть группировку","year":"2032"},
             "basis":["F-0001","F-0002"],"verdict":"new"}
            """.trimIndent()
        )

        val предложение = синтез.synthesize(ПРОЕКТ, "manual", "инженер").diff.new.single()

        assertEquals(SourceMark.П, предложение.sourceMark, "утверждение не крепче своего слабейшего основания")
    }

    @Test
    fun `синтез не трогает ни одной принятой сущности`() {
        паспорт(ПРОЕКТ, полеЗнаний = true)
        понятие("ND-0001", "need", mapOf("statement" to "связь в Арктике"))
        факт("F-0001", "Минтранс России", "нуждается в")
        транспорт.ответ = ответ(
            """
            {"concept":"need","payload":{"statement":"связь в Арктике круглосуточно","stakeholder":"Минтранс России"},
             "basis":["F-0001"],"verdict":"augment","target":"ND-0001","diff_field":"statement"}
            """.trimIndent()
        )
        val доСинтеза = снимок("ND-0001")

        val запуск = синтез.synthesize(ПРОЕКТ, "manual", "инженер")

        assertEquals(1, запуск.diff.augment.size)
        assertEquals(доСинтеза, снимок("ND-0001"), "принятое меняет только сверка — синтез предлагает")
    }

    // --- цена вопроса в токенах ---------------------------------------------

    @Test
    fun `два запуска без изменения поля — один живой вызов и одна запись журнала`() {
        паспорт(ПРОЕКТ, полеЗнаний = true)
        факт("F-0001", "Минтранс России", "нуждается в")
        транспорт.ответ = ответ(
            """
            {"concept":"need","payload":{"statement":"связь в Арктике","stakeholder":"Минтранс России"},
             "basis":["F-0001"],"verdict":"new"}
            """.trimIndent()
        )

        val первый = синтез.synthesize(ПРОЕКТ, "manual", "инженер")
        val второй = синтез.synthesize(ПРОЕКТ, "manual", "инженер")

        assertEquals(первый.sliceFingerprint, второй.sliceFingerprint, "поле не менялось — отпечаток тот же")
        assertFalse(первый.cached)
        assertTrue(второй.cached, "повтор того же среза берёт ответ из журнала")
        assertEquals(1, транспорт.промпты.size, "живой вызов один на срез")
        assertEquals(1, служба.journal(ПРОЕКТ).size, "журнал ИИ не прирастает на повторе")
    }

    @Test
    fun `поле изменилось — счёт делает сервер`() {
        паспорт(ПРОЕКТ, полеЗнаний = true)
        факт("F-0001", "Минтранс России", "нуждается в")

        val доСинтеза = синтез.drift(ПРОЕКТ)
        assertEquals(1, доСинтеза.changed, "синтеза ещё не было — изменилось всё поле")
        assertEquals(null, доСинтеза.since)

        val запуск = синтез.synthesize(ПРОЕКТ, "manual", "инженер")
        val послеСинтеза = синтез.drift(ПРОЕКТ)
        assertEquals(0, послеСинтеза.changed, "сразу после синтеза дрейфа нет")
        assertEquals(запуск.id, послеСинтеза.since)

        факт("F-0002", "ГКРЧ", "требует")
        assertTrue(синтез.drift(ПРОЕКТ).changed > 0, "новый факт поля виден счётчиком")
    }

    // --- окно накопления (ADR-069) ------------------------------------------

    @Test
    fun `три события внутри окна дают один запуск и ни одного вызова`() {
        паспорт(ПРОЕКТ, полеЗнаний = true)
        факт("F-0001", "Минтранс России", "нуждается в")
        val фон = фоновый()

        фон.start(ПРОЕКТ, "new_material", "инженер")
        факт("F-0002", "ГКРЧ", "требует")
        фон.start(ПРОЕКТ, "new_facts", "инженер")
        фон.start(ПРОЕКТ, "manual_edit", "инженер")

        assertEquals(1, фон.list(ПРОЕКТ).size, "события копятся в окно: один запуск на пачку")
        assertEquals(0, транспорт.промпты.size, "окно ещё копит — живого вызова нет")
    }

    @Test
    fun `рука закрывает открытое окно, а второго запуска не заводит`() {
        паспорт(ПРОЕКТ, полеЗнаний = true)
        факт("F-0001", "Минтранс России", "нуждается в")
        транспорт.ответ = ответ(
            """
            {"concept":"need","payload":{"statement":"связь в Арктике","stakeholder":"Минтранс России"},
             "basis":["F-0001"],"verdict":"new"}
            """.trimIndent()
        )
        val фон = фоновый()

        фон.start(ПРОЕКТ, "new_facts", "инженер")
        val запуск = фон.start(ПРОЕКТ, "manual", "инженер")
        val готовый = дождаться(фон, запуск.id)

        assertEquals(1, фон.list(ПРОЕКТ).size, "рука закрывает окно, а не заводит второй запуск на тот же срез")
        assertEquals("done", готовый.status)
        assertEquals(1, готовый.diff.size)
        assertEquals(1, транспорт.промпты.size, "живой вызов один на срез")
    }

    @Test
    fun `окно закрывается набором потолка, не дожидаясь срока`() {
        паспорт(ПРОЕКТ, полеЗнаний = true)
        факт("F-0001", "Минтранс России", "нуждается в")
        val фон = фоновый()
        фон.start(ПРОЕКТ, "new_material", "инженер")

        repeat(Synthesizer.ПОТОЛОК) { номер -> факт("F-%04d".format(номер + 100), "узел $номер", "описан") }
        val запуск = фон.start(ПРОЕКТ, "new_facts", "инженер")
        val готовый = дождаться(фон, запуск.id)

        assertEquals(1, фон.list(ПРОЕКТ).size, "накопление закрыло окно, а не завело второй запуск")
        assertEquals("done", готовый.status, "потолок набран — вызов ушёл, не дожидаясь срока окна")
    }

    @Test
    fun `отказ канала — запуск с названной причиной, а не потерянный вызов`() {
        паспорт(ПРОЕКТ, полеЗнаний = true)
        факт("F-0001", "Минтранс России", "нуждается в")
        транспорт.беда = "ключа канала нет"

        val запуск = синтез.synthesize(ПРОЕКТ, "manual", "инженер")

        assertEquals(SynthesisRun.ERROR, запуск.status)
        assertTrue("недоступен" in (запуск.error ?: ""), запуск.error ?: "")
        assertEquals(0, запуск.diff.size)
    }

    // --- отказные: брак ответа и выключенное поле ---------------------------

    @Test
    fun `брак предложения отклоняется поимённо, а годные остаются`() {
        паспорт(ПРОЕКТ, полеЗнаний = true)
        понятие("ND-0001", "need", mapOf("statement" to "связь в Арктике"))
        факт("F-0001", "Минтранс России", "нуждается в")
        транспорт.ответ = ответ(
            """
            {"concept":"need","payload":{"statement":"без оснований"},"basis":[],"verdict":"new"}
            """.trimIndent(),
            """
            {"concept":"need","payload":{"statement":"придуманный код"},"basis":["F-9999"],"verdict":"new"}
            """.trimIndent(),
            """
            {"concept":"архитектура","payload":{"statement":"понятия такого нет"},"basis":["F-0001"],"verdict":"new"}
            """.trimIndent(),
            """
            {"concept":"need","payload":{"statement":"похоже, но чем — не сказано"},
             "basis":["F-0001"],"verdict":"augment","target":"ND-0001"}
            """.trimIndent(),
            """
            {"concept":"need","payload":{"statement":"связь в Арктике круглосуточно","stakeholder":"Минтранс России"},
             "basis":["F-0001"],"verdict":"new"}
            """.trimIndent(),
        )

        val запуск = синтез.synthesize(ПРОЕКТ, "manual", "инженер")

        assertEquals(1, запуск.diff.size, "годное предложение остаётся, брак не уносит весь запуск")
        val примечание = запуск.note ?: ""
        assertTrue("отклонено 4" in примечание, примечание)
        assertTrue("без факта-основания" in примечание, примечание)
        assertTrue("вне среза" in примечание, примечание)
        assertTrue("вне правил образования" in примечание, примечание)
        assertTrue("не назвал поле отличия" in примечание, примечание)
    }

    @Test
    fun `вердикт о принятом называет существующее понятие, а не выдуманное`() {
        паспорт(ПРОЕКТ, полеЗнаний = true)
        факт("F-0001", "Минтранс России", "нуждается в")
        транспорт.ответ = ответ(
            """
            {"concept":"need","payload":{"statement":"связь в Арктике"},"basis":["F-0001"],
             "verdict":"augment","target":"ND-0404","diff_field":"statement"}
            """.trimIndent()
        )

        val запуск = синтез.synthesize(ПРОЕКТ, "manual", "инженер")

        assertEquals(0, запуск.diff.size)
        assertTrue("такого принятого понятия в срезе нет" in (запуск.note ?: ""), запуск.note ?: "")
    }

    @Test
    fun `ответ не деревом — отказ запуска с причиной, а не пустой диф`() {
        паспорт(ПРОЕКТ, полеЗнаний = true)
        факт("F-0001", "Минтранс России", "нуждается в")
        транспорт.ответ = "предложений не будет, извините"

        val запуск = синтез.synthesize(ПРОЕКТ, "manual", "инженер")

        assertEquals(SynthesisRun.ERROR, запуск.status)
        assertTrue("не разобран" in (запуск.error ?: ""), запуск.error ?: "")
    }

    @Test
    fun `на проекте без поля знаний v2 синтеза нет вовсе`() {
        паспорт(ПРОЕКТ, полеЗнаний = true)
        паспорт(СТАРЫЙ, полеЗнаний = false)
        факт("F-0001", "Минтранс России", "нуждается в")

        val беда = assertFailsWith<IllegalArgumentException> { синтез.synthesize(СТАРЫЙ, "manual", "инженер") }

        assertEquals(Synthesizer.ВЫКЛЮЧЕН, беда.message)
        assertEquals(0, транспорт.промпты.size, "на проекте прохода фоновых вызовов не появляется вовсе")
    }

    @Test
    fun `запуск без автора не заводится`() {
        паспорт(ПРОЕКТ, полеЗнаний = true)
        факт("F-0001", "Минтранс России", "нуждается в")
        val срез = синтез.prepare(ПРОЕКТ)

        val беда = assertFailsWith<IllegalArgumentException> { синтез.open(ПРОЕКТ, "manual", "", срез) }

        assertTrue("автора" in (беда.message ?: ""), беда.message ?: "")
    }

    @Test
    fun `сверка ручного ввода в список запусков синтеза не попадает`() {
        паспорт(ПРОЕКТ, полеЗнаний = true)
        факт("F-0001", "Минтранс России", "нуждается в")
        синтез.synthesize(ПРОЕКТ, "manual", "инженер")
        // Вид synthesis_run общий у синтеза и сверки — различает их метка.
        store.create(
            "SR-0900", SynthesisRun.KIND, Area.Project(ПРОЕКТ), null,
            mapper.createObjectNode().apply {
                put("trigger", "manual")
                put("slice_fingerprint", "чужой")
                put("ontology_version", "чужая")
                putArray("proposals")
                putObject("diff")
                putArray("tags").add("reconcile")
            },
            Provenance(Channel.MANUAL, "инженер"), status = "done",
        )

        assertEquals(listOf("SR-0001"), синтез.list(ПРОЕКТ).map { it.id })
        assertEquals(null, синтез.view(ПРОЕКТ, "SR-0900"), "чужой запуск синтезу не принадлежит")
    }

    // --- оснастка ------------------------------------------------------------

    private fun фоновый(): BackgroundSynthesizer =
        BackgroundSynthesizer(синтез, служба, debounce = ОКНО, clock = { OffsetDateTime.now() })

    /** Опрос задания до готовности: сеть в фоне, приём — на потоке запросов. */
    private fun дождаться(фон: BackgroundSynthesizer, код: String): SynthesisRun {
        val срок = System.currentTimeMillis() + 5_000
        var запуск = фон.poll(ПРОЕКТ, код)
        while (System.currentTimeMillis() < срок && запуск?.status in ИДЁТ) {
            Thread.sleep(20)
            запуск = фон.poll(ПРОЕКТ, код)
        }
        return assertNotNull(запуск, "задание синтеза «$код» потерялось")
    }

    private fun паспорт(project: String, полеЗнаний: Boolean) {
        val документ = mapper.createObjectNode().put("name", "Космическая система IoT")
        if (полеЗнаний) документ.put("knowledge_v2", true)
        store.create(
            project, "project", Area.Project(project), null, документ,
            Provenance(Channel.MANUAL, "инженер"),
        )
    }

    @Suppress("LongParameterList")
    private fun факт(
        код: String,
        subject: String,
        predicate: String,
        value: String = "",
        unit: String? = null,
        material: String = "M-0001",
        anchor: String = "s1#1",
        authority: String = "mandatory",
        mark: String = "И",
        disposition: String = "adopted",
        kind: String = "framing",
    ) {
        val документ = mapper.createObjectNode()
        документ.put("kind", kind).put("subject", subject).put("predicate", predicate)
        if (value.isNotBlank()) документ.put("value", value)
        unit?.let { документ.put("unit", it) }
        документ.put("anchor", anchor).put("material", material)
        документ.put("mark", mark).put("source_mark", mark)
        документ.put("authority", authority).put("disposition", disposition)
        документ.putObject("source").put("material", material).put("anchor", anchor)
        store.create(
            код, "fact", Area.Project(ПРОЕКТ), null, документ,
            Provenance(Channel.SERVICE, "разбор", source = material, anchor = anchor),
        )
    }

    private fun понятие(код: String, вид: String, поля: Map<String, String>) {
        val документ = mapper.createObjectNode()
        поля.forEach { (имя, значение) -> документ.put(имя, значение) }
        store.create(код, вид, Area.Project(ПРОЕКТ), null, документ, Provenance(Channel.MANUAL, "инженер"))
    }

    /** Диспозицию «принят» ставит только человек — здесь его рука. */
    private fun принять(код: String) {
        val факт = store.byCode(Area.Project(ПРОЕКТ), код) ?: error("факта «$код» в проекте нет")
        val документ = факт.doc.deepCopy<JsonNode>() as ObjectNode
        документ.put("disposition", "adopted")
        store.update(факт.id, документ, Provenance(Channel.MANUAL, "инженер"))
    }

    private fun снимок(код: String): String {
        val сущность = store.byCode(Area.Project(ПРОЕКТ), код) ?: error("сущности «$код» в проекте нет")
        return "${сущность.version}|${сущность.status}|${сущность.doc}"
    }

    private fun ответ(vararg предложения: String): String =
        """{"proposals":[${предложения.joinToString(",")}]}"""

    private companion object {
        const val ПРОЕКТ = "PRJ"
        const val СТАРЫЙ = "OLD"

        /** Окно накопления в тесте: события копятся, а ждать их нечего. */
        val ОКНО: Duration = Duration.ofSeconds(30)

        /** Состояния, из которых запуск ещё выйдет: опрос продолжается. */
        val ИДЁТ: Set<String?> = setOf("queued", "running")
    }
}

/** Канал-подмена: сеть не нужна, а промпты и число вызовов видны. */
private class КаналСинтеза : Transport {

    var ответ: String = """{"proposals":[]}"""
    var беда: String? = null
    val промпты = mutableListOf<String>()

    override fun ask(prompt: String, model: String?, maxTokens: Int?): Answer {
        промпты += prompt
        беда?.let { throw ProviderUnavailable(it) }
        return Answer(text = ответ, model = "модель-теста", tokensIn = 100, tokensOut = 200)
    }
}

/**
 * Хранилище в памяти: правка заводит НОВУЮ версию, прежняя остаётся историей —
 * ровно как у хранилища ядра, иначе «принятое не тронуто» проверять нечем.
 */
private class ПамятьПоля : EntityStore {

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
