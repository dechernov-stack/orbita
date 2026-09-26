// Правило противоречий 26.09 (истина `formation.normalization.contradictions`,
// ответ владельца — блокер ПМИ-8): противоречие — только у однозначного.
//
// Снимок `поле-знаний~полоса`: у Минтранса «фактов 25 · противоречий 17» —
// «нуждается в: единое оперативное управление» против «нуждается в:
// непрерывность телематики». Это перечень, а не спор. Однозначны величина
// одной размерности (после приведения к канону, с учётом оператора) и
// предикаты закрытого перечня истины; то же значение из другого документа —
// подтверждение `same_as`, счётчиком, а не противоречием.
//
// Тест на отказ (владелец): две нужды одной стороны из двух документов —
// противоречий 0; «≤ 180 мин» против «≤ 120 мин» — 1; «≤ 180 мин» против
// «≤ 3 ч» — 0.
package orbita.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.Provenance
import orbita.knowledge.api.Authority
import orbita.knowledge.api.KnowledgeFactory
import orbita.library.api.LibraryFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FactContradictionRuleTest {
    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val проект = "PJ-9626"
    private val область = Area.Project(проект)
    private val провенанс = Provenance(Channel.MANUAL, "Иванов И.")
    private val intake = KnowledgeFactory.intake(store, links, mapper, LibraryFactory.shelves(store) { mapper.createObjectNode() })

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        store.create(проект, "project", область, "1", mapper.createObjectNode().put("name", "Правило противоречий"), провенанс)
    }

    private fun документ(имя: String, текст: String, ранг: String) =
        intake.putMaterial(проект, имя, "reference", текст, "Иванов И.", rank = ранг)

    private fun якорь(материал: String) = intake.canon(проект, материал).first().anchor

    /** Один факт разбора: субъект · утверждение · значение (· единица). */
    private fun факт(материал: String, вид: String, субъект: String, предикат: String, значение: String, единица: String? = null) {
        val поле = if (единица != null) ""","unit":"$единица"""" else ""
        intake.putFacts(
            проект, материал,
            """{"topics":[],"actions":[],"facts":[{"kind":"$вид","subject":"$субъект","predicate":"$предикат",""" +
                """"value":"$значение"$поле,"source":{"anchor":"${якорь(материал)}"},"mark":"И"}]}""",
            "Иванов И.",
        )
    }

    private fun фактыДокумента(материал: String) = intake.facts(проект).filter { it.material == материал }

    private fun связи(тип: String) = store.list(область, "fact").flatMap { links.from(it.id, тип) }

    @Test
    fun `две нужды одной стороны из двух документов — перечень, противоречий 0`() {
        val записка = документ("Записка", "Минтранс нуждается в едином управлении.", Authority.MANDATORY)
        val тз = документ("ТЗ", "Минтранс нуждается в непрерывности телематики.", Authority.REFERENCE)
        факт(записка, "framing", "Минтранс России", "нуждается в", "единое оперативное управление")
        факт(тз, "framing", "Минтранс России", "нуждается в", "непрерывность регуляторной телематики")

        assertTrue(intake.facts(проект).all { it.conflicts.isEmpty() }, intake.facts(проект).map { it.conflicts }.toString())
        assertTrue(связи("contradicts").isEmpty(), "у перечня нет связи противоречия: ${связи("contradicts")}")
    }

    @Test
    fun `«≤ 180 мин» против «≤ 120 мин» — одно противоречие`() {
        val записка = документ("Записка", "P95 доставки не более 180 минут.", Authority.MANDATORY)
        val тз = документ("ТЗ", "P95 доставки не более 120 минут.", Authority.REFERENCE)
        факт(записка, "quantity", "сервис A′", "P95 доставки", "180", "мин")
        факт(тз, "quantity", "сервис A′", "P95 доставки", "120", "мин")

        val изЗаписки = фактыДокумента(записка).single()
        val изТз = фактыДокумента(тз).single()
        assertEquals(listOf(изТз.id), изЗаписки.conflicts, "спор назван у обоих концов")
        assertEquals(listOf(изЗаписки.id), изТз.conflicts)
        assertEquals(1, связи("contradicts").size, "у противоречия нет направления — связь одна")
    }

    @Test
    fun `«≤ 180 мин» против «≤ 3 ч» — одна величина после приведения, не спор, а подтверждение`() {
        // Справочник единиц — данными вида `unit`: второй таблицы коэффициентов в коде нет.
        listOf(
            Triple("UN-S", "с", 1.0), Triple("UN-MIN", "мин", 60.0), Triple("UN-H", "ч", 3600.0),
        ).forEach { (код, символ, множитель) ->
            store.create(
                код, "unit", Area.Library, null,
                mapper.createObjectNode().put("symbol", символ).put("dimension", "время").put("name", символ)
                    .put("canonical", символ == "с").put("factor", множитель).put("conversion_type", "linear"),
                провенанс,
            )
        }
        val записка = документ("Записка", "P95 доставки не более 180 минут.", Authority.MANDATORY)
        val тз = документ("ТЗ", "P95 доставки не более трёх часов.", Authority.REFERENCE)
        факт(записка, "quantity", "сервис A′", "P95 доставки", "180", "мин")
        факт(тз, "quantity", "сервис A′", "P95 доставки", "3", "ч")

        assertTrue(intake.facts(проект).all { it.conflicts.isEmpty() }, intake.facts(проект).map { it.conflicts }.toString())
        assertTrue(связи("contradicts").isEmpty())
        assertEquals(1, связи("same_as").size, "то же значение из другого документа — подтверждение связью")
    }

    @Test
    fun `роль стороны — предикат закрытого перечня истины, разные значения спорят`() {
        val записка = документ("Записка", "Минтранс — заказчик.", Authority.MANDATORY)
        val тз = документ("ТЗ", "Минтранс — регулятор.", Authority.REFERENCE)
        факт(записка, "relation", "Минтранс России", "роль", "заказчик")
        факт(тз, "relation", "Минтранс России", "роль", "регулятор")

        assertTrue(intake.facts(проект).all { it.conflicts.size == 1 }, intake.facts(проект).map { it.conflicts }.toString())
        assertEquals(1, связи("contradicts").size)
    }

    @Test
    fun `то же значение из другого документа — подтверждение same_as, счётчиком, не спор`() {
        val записка = документ("Записка", "Минтранс нуждается в едином управлении.", Authority.MANDATORY)
        val тз = документ("ТЗ", "Минтранс нуждается в едином управлении.", Authority.REFERENCE)
        факт(записка, "framing", "Минтранс России", "нуждается в", "единое оперативное управление")
        факт(тз, "framing", "Минтранс России", "нуждается в", "Единое оперативное управление")

        assertTrue(intake.facts(проект).all { it.conflicts.isEmpty() })
        val подтверждения = связи("same_as")
        assertEquals(1, подтверждения.size, "одна связь на пару: $подтверждения")
        assertTrue("подтверждение" in подтверждения.single().rationale.orEmpty(), подтверждения.single().rationale)
    }

    @Test
    fun `пересчёт при старте снимает пометы и связи прежнего правила, связь человека и смысловую связь разбора не трогает`() {
        val записка = документ("Записка", "Минтранс нуждается в едином управлении.", Authority.MANDATORY)
        val тз = документ("ТЗ", "Минтранс нуждается в непрерывности телематики.", Authority.REFERENCE)
        факт(записка, "framing", "Минтранс России", "нуждается в", "единое оперативное управление")
        факт(тз, "framing", "Минтранс России", "нуждается в", "непрерывность регуляторной телематики")
        // Так их оставило прежнее правило «разное значение — конфликт».
        val а = assertNotNull(store.byCode(область, фактыДокумента(записка).single().id))
        val б = assertNotNull(store.byCode(область, фактыДокумента(тз).single().id))
        listOf(а to б, б to а).forEach { (ф, другой) ->
            val д = (ф.doc.deepCopy() as ObjectNode)
            д.putArray("conflicts").add(другой.code)
            store.update(ф.id, д, Provenance(Channel.SERVICE, "служба"))
        }
        links.link("contradicts", а.id, б.id, Provenance(Channel.SERVICE, "служба"), rationale = "прежнее правило — показаны оба; ранг подсказывает, победителя выбирает человек")
        links.link("contradicts", б.id, а.id, Provenance(Channel.MANUAL, "Иванов И."), rationale = "человек так решил")
        // Смысловое противоречие из разбора — тем же каналом службы, но обоснованием модели: пересчёт его не снимает.
        links.link("contradicts", а.id, б.id, Provenance(Channel.SERVICE, "разбор"), rationale = "устав исключает то, чего требует ТЗ: цитаты обеих сторон")

        val итоги = KnowledgeFactory.recountContradictions(store, links, mapper)
        assertTrue(итоги.any { проект in it && "противоречий 0" in it }, итоги.toString())
        assertTrue(intake.facts(проект).all { it.conflicts.isEmpty() }, "пометы прежнего правила сняты")
        val остались = связи("contradicts")
        assertEquals(2, остались.size, "связь прежнего правила снята; связь человека и смысловая связь разбора остались: $остались")
        assertTrue(остались.none { "прежнее правило" in it.rationale.orEmpty() })
    }
}
