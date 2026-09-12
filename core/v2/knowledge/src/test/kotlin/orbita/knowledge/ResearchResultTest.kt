// Результат исследования: материал «сомнительный», подтверждение источников,
// следующий цикл по остаткам.
//
// Проверяется главное правило контура (research_rule): найденное снаружи не
// попадает в модель мимо общего пути и не зачитывает само себя. До того как
// человек посмотрел источники, разбор результата не предлагается сцене ни
// одной строкой, а ранг материала не растёт.
//
// Модели тут нет: разбор приходит готовым JSON — проверяется то, что система
// считает ПО ДАННЫМ, а не то, как отвечает модель. Хранилище и реестр связей —
// общая оснастка из ResearchTaskTest.
package orbita.knowledge

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.kernel.api.Provenance
import orbita.knowledge.api.Authority
import orbita.knowledge.api.ResearchTrigger
import orbita.knowledge.internal.EntityIntake
import orbita.knowledge.internal.ResearchTasks
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ResearchResultTest {

    private val mapper = ObjectMapper()
    private val store: EntityStore = ПамятьПоля()
    private val links: LinkRegistry = СвязиПоля()
    private val знания = EntityIntake(store, links, mapper)
    private val исследование = ResearchTasks(store, links, mapper, intake = знания)

    private val проект = "PJ-9002"
    private val область = Area.Project(проект)
    private val автор = "Иванов И."
    private val провенанс = Provenance(Channel.MANUAL, автор)

    @BeforeTest
    fun поле() {
        store.create(
            проект, "project", область, "1",
            mapper.createObjectNode().put("name", "Спутниковый IoT").put("knowledge_v2", true),
            провенанс,
        )
        store.create(
            "IN-0001", "intent", область, "2",
            mapper.createObjectNode().put("what", "национальная спутниковая платформа IoT")
                .put("where", "в Арктике и на СМП").put("horizon", "2033"),
            провенанс,
        )
        store.create(
            "SK-0001", "stakeholder", область, "3",
            mapper.createObjectNode().put("name", "Минтранс России").put("role", "заказчик"), провенанс,
        )
    }

    // --- приём результата --------------------------------------------------

    @Test
    fun `результат ложится материалом сомнительного ранга со связью с задачей`() {
        val задача = запущенная()

        val принята = исследование.result(проект, задача, "ИССЛЕДОВАНИЕ-RT-0001.md", ОТВЕТ, автор)

        assertEquals("received", принята.status)
        val код = assertNotNull(принята.resultMaterial, "материал результата обязан быть назван")
        val материал = assertNotNull(store.byCode(область, код))
        assertEquals(
            Authority.DOUBTFUL, материал.doc.path("authority").asText(),
            "внешний контур непроверен по построению: ранг поднимет человек",
        )
        val задачаЗапись = assertNotNull(store.byCode(область, задача))
        assertTrue(
            links.from(материал.id, "output_of").any { it.to == задачаЗапись.id },
            "материал — выход задачи: связь читается с обоих концов",
        )
    }

    @Test
    fun `разбор сомнительного результата сцене не предлагается пока источники не подтверждены`() {
        val задача = запущенная()
        val материал = assertNotNull(исследование.result(проект, задача, "ответ.md", ОТВЕТ, автор).resultMaterial)
        разобрать(материал)

        assertTrue(
            знания.suggestions(проект, "3").actions.isEmpty(),
            "непроверенное в сцену не предлагается: сперва источники, потом предложения",
        )
        assertEquals(
            "parsed", исследование.task(проект, задача).status,
            "разбор результата виден задаче по фактам её материала",
        )

        исследование.confirmSources(проект, задача, фактыРезультата(материал), автор)

        assertTrue(
            знания.suggestions(проект, "3").actions.isNotEmpty(),
            "после подтверждения источников предложения приходят обычным путём",
        )
    }

    @Test
    fun `подтверждение источников поднимает материал и отмечает свидетельство`() {
        val задача = запущенная()
        val материал = assertNotNull(исследование.result(проект, задача, "ответ.md", ОТВЕТ, автор).resultMaterial)
        разобрать(материал)
        val факты = фактыРезультата(материал)

        val итог = исследование.confirmSources(проект, задача, факты, автор)

        assertEquals(
            Authority.REFERENCE, assertNotNull(store.byCode(область, материал)).doc.path("authority").asText(),
            "источники подтверждены человеком — материал стал свидетельством",
        )
        факты.forEach { код ->
            assertEquals(
                "corroborated", assertNotNull(store.byCode(область, код)).doc.path("evidence").asText(),
                "подтверждённый источником факт — corroborated",
            )
        }
        assertEquals(2, итог.accepted.stakeholders, "принятое разложено по классам вопросов")
        assertEquals(1, итог.accepted.norms)
        assertEquals(0, итог.accepted.applications, "по этому классу ответа не было — он и остаток")
    }

    // --- цикл --------------------------------------------------------------

    @Test
    fun `следующий цикл спрашивает только то на что ответа нет`() {
        val задача = запущенная()
        val материал = assertNotNull(исследование.result(проект, задача, "ответ.md", ОТВЕТ, автор).resultMaterial)
        разобрать(материал)
        исследование.confirmSources(проект, задача, фактыРезультата(материал), автор)

        val второй = исследование.next(проект, задача, автор)

        assertEquals(2, второй.iteration, "цикл по тому же месту — второй")
        val классы = второй.questions.mapNotNull { orbita.knowledge.api.ResearchClass.of(it.substringBefore(":")) }
        assertTrue(
            классы.none { it == orbita.knowledge.api.ResearchClass.STAKEHOLDER },
            "по сторонам ответ есть — второй раз о них не спрашивают: $классы",
        )
        assertTrue(
            классы.contains(orbita.knowledge.api.ResearchClass.APPLICATION),
            "по применению ответа не было — это и есть остаток: $классы",
        )
    }

    // --- отказные ----------------------------------------------------------

    @Test
    fun `факт чужого материала этой задачей не подтверждается`() {
        val задача = запущенная()
        val материал = assertNotNull(исследование.result(проект, задача, "ответ.md", ОТВЕТ, автор).resultMaterial)
        разобрать(материал)
        val чужой = чужойФакт()

        val отказ = assertFailsWith<IllegalArgumentException> {
            исследование.confirmSources(проект, задача, listOf(чужой), автор)
        }
        assertTrue(
            отказ.message.orEmpty().contains("не из материала результата"),
            "подтверждение поднимает ранг ЕЁ результата: ${отказ.message}",
        )
    }

    @Test
    fun `подтверждать источники нечему пока результата нет`() {
        val задача = запущенная()

        val отказ = assertFailsWith<IllegalStateException> {
            исследование.confirmSources(проект, задача, listOf("F-0001"), автор)
        }
        assertTrue(отказ.message.orEmpty().contains("результата ещё нет"), отказ.message.orEmpty())
    }

    @Test
    fun `следующий цикл не заводится пока результата нет`() {
        val задача = запущенная()

        val отказ = assertFailsWith<IllegalArgumentException> { исследование.next(проект, задача, автор) }
        assertTrue(
            отказ.message.orEmpty().contains("ПО ОСТАТКАМ"),
            "остатки видны только по принятому из результата: ${отказ.message}",
        )
    }

    @Test
    fun `когда принято по всем пяти классам следующего цикла не заводится`() {
        val задача = запущенная()
        val материал = assertNotNull(
            исследование.result(проект, задача, "ответ.md", ПОЛНЫЙ_ОТВЕТ, автор).resultMaterial,
        )
        разобрать(материал, ПОЛНЫЙ_РАЗБОР)
        исследование.confirmSources(проект, задача, фактыРезультата(материал), автор)

        val отказ = assertFailsWith<IllegalArgumentException> { исследование.next(проект, задача, автор) }
        assertTrue(
            отказ.message.orEmpty().contains("исследование окончено"),
            "останавливает цикл человек, и выглядит это так: спрашивать нечего — ${отказ.message}",
        )
    }

    // --- оснастка ----------------------------------------------------------

    private fun запущенная(): String {
        val задача = исследование.formulate(проект, ResearchTrigger.Scene("3"), автор)
        исследование.launch(проект, задача.id, автор, "внешний контур с поиском")
        return задача.id
    }

    /** Разбор результата — общий путь загрузки: готовый JSON, без вызова модели. */
    private fun разобрать(материал: String, разбор: String = РАЗБОР) {
        val якорь = знания.canon(проект, материал).first { it.kind == "para" }.anchor
        val итог = знания.putFacts(проект, материал, разбор.replace("ЯКОРЬ", якорь), автор)
        assertTrue(итог.refused.isEmpty(), "разбор результата отклонён: ${итог.refused}")
    }

    private fun фактыРезультата(материал: String): List<String> =
        знания.facts(проект).filter { it.material == материал }.map { it.id }

    private fun чужойФакт(): String {
        val другой = знания.putMaterial(
            проект, "Записка о миссии", "mission_memo",
            "# Записка\n\nЗаказчик — Минтранс России.\n", автор, authority = Authority.MANDATORY,
        )
        val якорь = знания.canon(проект, другой).first { it.kind == "para" }.anchor
        знания.putFacts(
            проект, другой,
            """
            {"topics":[{"label":"сторона"}],"facts":[
              {"kind":"relation","topic":"сторона","subject":"Минтранс России",
               "predicate":"является заказчиком","value":"да",
               "source":{"anchor":"$якорь"},"source_mark":"И"}]}
            """.trimIndent(),
            автор,
        )
        return знания.facts(проект).first { it.material == другой }.id
    }

    private companion object {

        val ОТВЕТ = """
            # Результат исследования по сценам 3–5

            Минвостокразвития заинтересовано в спутниковом IoT для ДФО — government.ru, 12.09.2026.

            Корпорация развития Дальнего Востока ведёт цифровые коридоры — erdc.ru, 12.09.2026.

            ПП РФ №2216 применимо к передаче данных телематики — pravo.gov.ru, 12.09.2026.
        """.trimIndent()

        /**
         * Разбор ответа: тема строки — её КЛАСС, как того требует промпт. По
         * теме принятое и раскладывается по классам.
         */
        val РАЗБОР = """
            {"topics":[{"label":"сторона"},{"label":"норма"}],
             "facts":[
               {"kind":"relation","topic":"сторона","subject":"Минвостокразвития",
                "predicate":"заинтересовано в спутниковом IoT","value":"да",
                "source":{"anchor":"ЯКОРЬ"},"source_mark":"В","entity_class":"stakeholder"},
               {"kind":"relation","topic":"сторона","subject":"Корпорация развития Дальнего Востока",
                "predicate":"ведёт цифровые коридоры","value":"да",
                "source":{"anchor":"ЯКОРЬ"},"source_mark":"В","entity_class":"stakeholder"},
               {"kind":"obligation","topic":"норма","subject":"ПП РФ №2216",
                "predicate":"применимо к передаче телематики","value":"да",
                "source":{"anchor":"ЯКОРЬ"},"source_mark":"В","entity_class":"normative_ref"}],
             "actions":[
               {"kind":"create_entity","target_kind":"stakeholder","scene":"3",
                "title":"Минвостокразвития","preview":"появится сторона миссии",
                "payload":{"name":"Минвостокразвития"},"facts":[0]}]}
        """.trimIndent()

        val ПОЛНЫЙ_ОТВЕТ = """
            # Результат исследования по всем классам

            Минвостокразвития заинтересовано в спутниковом IoT — government.ru, 12.09.2026.

            Госпрограмма развития ДФО задаёт показатели связанности — government.ru, 12.09.2026.

            ПП РФ №2216 применимо к передаче телематики — pravo.gov.ru, 12.09.2026.

            Рыбопромысловый флот Приморья требует спутникового канала — rosrybolovstvo.ru, 12.09.2026.

            Проект Astrocast решает ту же задачу на НОО — astrocast.com, 12.09.2026.
        """.trimIndent()

        val ПОЛНЫЙ_РАЗБОР = """
            {"topics":[{"label":"сторона"},{"label":"программа"},{"label":"норма"},
                       {"label":"применение"},{"label":"аналог"}],
             "facts":[
               {"kind":"relation","topic":"сторона","subject":"Минвостокразвития",
                "predicate":"заинтересовано в спутниковом IoT","value":"да",
                "source":{"anchor":"ЯКОРЬ"},"source_mark":"В"},
               {"kind":"framing","topic":"программа","subject":"Госпрограмма развития ДФО",
                "predicate":"задаёт показатели связанности","value":"да",
                "source":{"anchor":"ЯКОРЬ"},"source_mark":"В"},
               {"kind":"obligation","topic":"норма","subject":"ПП РФ №2216",
                "predicate":"применимо к передаче телематики","value":"да",
                "source":{"anchor":"ЯКОРЬ"},"source_mark":"В"},
               {"kind":"framing","topic":"применение","subject":"Рыбопромысловый флот Приморья",
                "predicate":"требует спутникового канала","value":"да",
                "source":{"anchor":"ЯКОРЬ"},"source_mark":"В"},
               {"kind":"framing","topic":"аналог","subject":"Astrocast",
                "predicate":"решает ту же задачу на НОО","value":"да",
                "source":{"anchor":"ЯКОРЬ"},"source_mark":"В"}],
             "actions":[]}
        """.trimIndent()
    }
}
