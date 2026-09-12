// Знания v2, шаг C3: конфликт фактов живёт СВЯЗЬЮ между документами (поле
// `conflicts` остаётся вычисляемым представлением для экрана и экспорта),
// режим разбора выбирается ПРОФИЛЕМ содержимого, а не типом файла, и ранг
// доверия называется словами в тексте RFA/RID.
//
// Модели здесь нет: разбор приходит готовым JSON — так проверяется то, что
// система считает ПО ДАННЫМ. Тесты KnowledgeModesTest не трогаются: они
// стерегут прежнее поведение проекта без поля знаний v2.
package orbita.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.api.internal.KindJson
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.Provenance
import orbita.knowledge.api.Authority
import orbita.knowledge.api.FactIntake
import orbita.knowledge.api.KnowledgeFactory
import orbita.library.api.LibraryFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FieldConflictLinksTest {
    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val проект = "PJ-9613"
    private val область = Area.Project(проект)
    private val провенанс = Provenance(Channel.MANUAL, "Иванов И.")
    private val intake = KnowledgeFactory.intake(store, links, mapper, LibraryFactory.shelves(store) { mapper.createObjectNode() })

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        store.create(проект, "project", область, "1", mapper.createObjectNode().put("name", "Поле знаний"), провенанс)
    }

    private fun якоря(материал: String) = intake.canon(проект, материал).map { it.anchor }

    private fun масса(якорь: String, значение: String) = """
        {"topics":[],"actions":[],"facts":[
          {"kind":"quantity","subject":"платформа","predicate":"масса","value":"$значение","unit":"кг","source":{"anchor":"$якорь"},"source_mark":"В"}]}"""

    /** Факт по его коду: связи живут на идентификаторах сущностей, не на кодах. */
    private fun сущностьФакта(материал: String) =
        assertNotNull(store.byCode(область, intake.facts(проект).first { it.material == материал }.id))

    private fun связи(идентификатор: String) =
        links.from(идентификатор, "contradicts") + links.to(идентификатор, "contradicts")

    /** Материал, загруженный ДО перестройки: ранга доверия у него нет вовсе. */
    private fun снятьРанг(материал: String) {
        val карточка = assertNotNull(store.byCode(область, материал))
        val документ = (карточка.doc.deepCopy() as ObjectNode)
        документ.remove("authority")
        store.update(карточка.id, документ, провенанс)
    }

    @Test
    fun `конфликт двух документов — связь contradicts между фактами разных материалов, с рангами обоих`() {
        val записка = intake.putMaterial(
            проект, "Записка", "mission_memo", "Масса платформы 80 кг.", "Иванов И.",
            authority = Authority.MANDATORY,
        )
        val сайт = intake.putMaterial(
            проект, "Сайт поставщика", "reference", "Масса платформы 120 кг.", "Иванов И.",
            authority = Authority.DOUBTFUL,
        )
        intake.putFacts(проект, записка, масса(якоря(записка)[0], "80"), "Иванов И.")
        intake.putFacts(проект, сайт, масса(якоря(сайт)[0], "120"), "Иванов И.")

        val изЗаписки = сущностьФакта(записка)
        val изСети = сущностьФакта(сайт)
        val найденные = связи(изЗаписки.id)
        assertEquals(1, найденные.size, "у противоречия нет направления — связь одна, не зеркальная пара")
        val связь = найденные.single()
        assertEquals(
            setOf(изЗаписки.id, изСети.id), setOf(связь.from, связь.to),
            "концы связи — факты РАЗНЫХ материалов: противоречие переживает корпус",
        )
        val обоснование = связь.rationale.orEmpty()
        assertTrue("обязательного документа" in обоснование, "ранг первого конца словами: $обоснование")
        assertTrue("сомнительного источника" in обоснование, "ранг второго конца словами: $обоснование")
        assertTrue("80" in обоснование && "120" in обоснование, "показаны ОБА значения: $обоснование")

        // `conflicts` остаётся вычисляемым представлением: экран и экспорт
        // читают его тем же полем, что и до перестройки.
        val наЭкран = KindJson.факт(mapper, intake.facts(проект).first { it.material == записка })
        assertEquals(1, наЭкран.path("conflicts").size(), наЭкран.toString())
        assertEquals(изСети.code, наЭкран.path("conflicts").path(0).asText())
    }

    @Test
    fun `совпадающие значения связи не рождают, а повторный разбор её не удваивает`() {
        val записка = intake.putMaterial(
            проект, "Записка", "mission_memo", "Масса платформы 80 кг.", "Иванов И.",
            authority = Authority.MANDATORY,
        )
        val копия = intake.putMaterial(
            проект, "Аналитическая записка", "analysis", "Масса платформы 80 кг.", "Иванов И.",
            authority = Authority.REFERENCE,
        )
        intake.putFacts(проект, записка, масса(якоря(записка)[0], "80"), "Иванов И.")
        intake.putFacts(проект, копия, масса(якоря(копия)[0], "80"), "Иванов И.")
        assertTrue(связи(сущностьФакта(записка).id).isEmpty(), "то же значение — подтверждение, а не противоречие")
        assertTrue(intake.facts(проект).all { it.conflicts.isEmpty() }, intake.facts(проект).map { it.conflicts }.toString())

        val сайт = intake.putMaterial(
            проект, "Сайт поставщика", "reference", "Масса платформы 120 кг.", "Иванов И.",
            authority = Authority.DOUBTFUL,
        )
        val разбор = масса(якоря(сайт)[0], "120")
        intake.putFacts(проект, сайт, разбор, "Иванов И.")
        intake.putFacts(проект, сайт, разбор, "Иванов И.")
        val спорный = связи(сущностьФакта(сайт).id)
        assertEquals(
            2, спорный.size,
            "по одной связи на пару «80 ↔ 120»; повторный разбор той же версии связь не удваивает: $спорный",
        )
    }

    @Test
    fun `режим обязательств выбирается профилем норм, а не типом файла — класс сущности цел`() {
        store.create(
            "ND-0001", "need", область, "3",
            mapper.readTree("""{"statement":"подтверждаемая доставка сообщений"}"""), провенанс,
        )
        // Документ НЕ типа «ТЗ»: режим обязан прийти из профиля содержимого.
        val материал = аналитикаСТребованиями(Authority.MANDATORY)
        val задание = intake.task(проект, assertNotNull(разобрать(материал).task))

        val rfa = задание.plan.first { it.kind == "request_data" && it.targetKind == "finding" }
        assertTrue("ND-0001" in rfa.title, "непокрытая нужда — RFA заказчику: ${rfa.title}")
        assertTrue("из обязательного документа" in rfa.effect, "ранг документа словами в RFA: ${rfa.effect}")
        assertTrue("из обязательного документа" in rfa.payload["text"].orEmpty(), rfa.payload.toString())
        // Сирот две (требование и услуга) — берём услугу: её заголовок и
        // называет класс, который профиль обязан сохранить.
        val rid = задание.plan.first { it.kind == "flag_conflict" && "LEO-PNT" in it.title }
        assertTrue(rid.title.startsWith("услуга ТЗ"), "класс сущности ТЗ профилем не теряется: ${rid.title}")
        assertTrue("из обязательного документа" in rid.effect, "ранг требования словами в RID: ${rid.effect}")
        assertEquals(
            "service",
            store.list(область, "fact").first { "LEO-PNT" in it.doc.path("predicate").asText() }
                .doc.path("entity_class").asText(),
            "класс факта сохранён разбором",
        )
    }

    @Test
    fun `профиль одной постановки режима не включает — ни обязательств, ни параметров`() {
        store.create(
            "ND-0001", "need", область, "3",
            mapper.readTree("""{"statement":"подтверждаемая доставка сообщений"}"""), провенанс,
        )
        val материал = аналитикаСТребованиями(Authority.MANDATORY)
        val итог = разобрать(материал, профиль = """{"statement":1.0}""")
        val задание = intake.task(проект, assertNotNull(итог.task))
        assertTrue(
            задание.plan.none { it.kind == "request_data" || it.kind == "flag_conflict" },
            "блока норм в документе нет — режим обязательств не включается: ${задание.plan.map { it.title }}",
        )
        assertNotNull(задание.assessment, "оценка считается по данным всегда: режим решает только судьбу действий")
    }

    @Test
    fun `материал без ранга хвоста не печатает — «из неизвестного документа» не бывает`() {
        store.create(
            "ND-0001", "need", область, "3",
            mapper.readTree("""{"statement":"подтверждаемая доставка сообщений"}"""), провенанс,
        )
        val материал = аналитикаСТребованиями(Authority.MANDATORY)
        снятьРанг(материал)
        val задание = intake.task(проект, assertNotNull(разобрать(материал).task))
        val rfa = задание.plan.first { it.kind == "request_data" && it.targetKind == "finding" }
        assertFalse(
            listOf("обязательн", "справочн", "сомнительн", "эксперт").any { it in rfa.effect },
            "ранга у карточки нет — подсказки о ранге нет вовсе, «из неизвестного» не печатается: ${rfa.effect}",
        )
        assertTrue("ND-0001" in rfa.title, "само замечание при этом на месте: ${rfa.title}")
    }

    /** Аналитическая записка с разделом требований: тип — не «ТЗ», ранг — свой. */
    private fun аналитикаСТребованиями(ранг: String): String = intake.putMaterial(
        проект, "Аналитическая записка с разделом требований", "analysis",
        "п. 4.1 Система обязана обеспечивать приём телеметрии с интервалом не более 30 мин.\n\n" +
            "п. 3.2 Услуги: позиционирование и навигация (LEO-PNT).",
        "Иванов И.", authority = ранг,
    )

    private fun разобрать(материал: String, профиль: String = """{"statement":0.2,"norms":0.8}"""): FactIntake {
        val я = якоря(материал)
        val json = """{"profile":$профиль,"topics":[],"actions":[],"facts":[
            {"kind":"obligation","entity_class":"requirement","subject":"ТЗ п. 4.1","predicate":"приём телеметрии с интервалом не более 30 мин","value":"требование","source":{"anchor":"${я[0]}"},"source_mark":"И"},
            {"kind":"capability","entity_class":"service","subject":"ТЗ п. 3.2","predicate":"услуга позиционирования и навигации LEO-PNT","value":"услуга","source":{"anchor":"${я[1]}"},"source_mark":"И"}],
          "assessment":{"lines":[
            {"fact":0,"requirement":"п. 4.1","needs":[],"verdict":"none","note":"интервал есть, нужды проекта под ним нет"},
            {"fact":1,"requirement":"п. 3.2","needs":[],"verdict":"none","note":"навигации заказчик не просил"}],
            "needs":[{"need":"ND-0001","verdict":"uncovered","gap":"нет требования на подтверждение доставки"}]}}"""
        return intake.putFacts(проект, материал, json, "Иванов И.")
    }
}
