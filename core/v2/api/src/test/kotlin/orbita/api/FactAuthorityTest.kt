// Ранг доверия и источник факта в ядре знаний (план «Знания v2», шаг 6).
//
// Проверяется не «поле записалось», а то, ради чего ранг заведён: доверие
// живёт ОТДЕЛЬНО от типа документа (тип остаётся режимом разбора), факт
// наследует ранг материала, а рука эксперта даёт свой ранг и свой источник —
// учётку, роль и дату вместо якоря.
//
// Сторож якоря при этом не ослаблен, а РАЗВЕТВЛЁН: документальный факт без
// якоря по-прежнему не существует, и это самое опасное место пакета —
// поэтому отказные проверки здесь на каждую ветку союза `fact.source`.
package orbita.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.Provenance
import orbita.knowledge.api.ContentProfile
import orbita.knowledge.api.FactSource
import orbita.knowledge.api.KnowledgeFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FactAuthorityTest {

    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val схемы = KernelFactory.schemaRegistry(TestDbV2.repoRoot.resolve("schemas/v2"))
    private val intake = KnowledgeFactory.intake(store, links, mapper)
    private val провенанс = Provenance(Channel.MANUAL, "Иванов И.")

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
    }

    /** Проект прохода — без поля знаний; проект меры — с полем. */
    private fun проект(код: String, полеЗнаний: Boolean = false): String {
        val документ = mapper.createObjectNode().put("name", "Знания v2")
        if (полеЗнаний) документ.put("knowledge_v2", true)
        store.create(код, "project", Area.Project(код), "1", документ, провенанс)
        return код
    }

    private fun якоря(проект: String, материал: String) = intake.canon(проект, материал).map { it.anchor }

    private fun документ(проект: String, код: String) =
        store.byCode(Area.Project(проект), код)!!.doc

    @Test
    fun `факт из обязательного материала наследует ранг, ручной факт эксперта — свой`() {
        val п = проект("PJ-9610")
        val материал = intake.putMaterial(п, "ТЗ заказчика", "tor", "п. 4.1 Срок службы 5 лет.", "Иванов И.")
        val карточка = документ(п, материал)
        assertEquals("mandatory", карточка.path("authority").asText(), "ТЗ заказчика — обязательный документ")
        // Тип входного никуда не делся: по нему разбор выбирает режим ТЗ, и
        // ранг его не заменяет — это разные вопросы к одному документу.
        assertEquals("tor", карточка.path("kind").asText())
        assertEquals("tor", карточка.path("type").asText())

        val я = якоря(п, материал)
        val итог = intake.putFacts(
            п, материал,
            """{"topics":[],"actions":[],"facts":[
              {"kind":"quantity","subject":"система","predicate":"срок службы","value":"5","unit":"год",
               "source":{"anchor":"${я[0]}"},"source_mark":"И"}]}""",
            "Иванов И.",
        )
        assertEquals(1, итог.accepted.size, итог.refused.toString())
        val изДокумента = итог.accepted.single()
        assertEquals("mandatory", изДокумента.authority, "ранг наследуется от материала, а не от мнения разбора")
        assertEquals(FactSource.FromMaterial(материал, я[0]), изДокумента.source)

        // Рука эксперта: ранг свой, источник — учётка, роль и дата, якоря нет.
        val ручной = intake.addFact(
            п, "Гонец-Д1М", "срок активного существования по ЛИ", "7", "лет", "quantity",
            null, null, "Иванов И.", role = "ведущий системный инженер",
        )
        assertEquals("expert", ручной.authority)
        val источник = ручной.source
        assertTrue(источник is FactSource.FromExpert, "источник ручного факта — эксперт: $источник")
        assertEquals("Иванов И.", (источник as FactSource.FromExpert).account)
        assertEquals("ведущий системный инженер", источник.role)
        assertTrue(источник.at.isNotBlank(), "дата у экспертного источника обязательна")
        assertNull(ручной.anchor, "у руки документа нет — якоря тоже")

        // По истине схем: ранг и союзный источник замечаний не дают. Прочие
        // поля факта (плоские subject/value) — долг DTO из YAML, не этого шага.
        val замечания = схемы.problems("fact", документ(п, ручной.id))
        assertTrue(замечания.none { "authority" in it }, "ранг по истине схем: $замечания")
        assertTrue(
            замечания.none { it.substringBefore(":").trim().endsWith("source") },
            "источник по истине схем: $замечания",
        )
    }

    @Test
    fun `ранг выводится по типу входного, неизвестный тип даёт сомнительный с пометой`() {
        val п = проект("PJ-9611")
        val анализ = intake.putMaterial(п, "Анализ идей", "analysis", "Идея миссии.", "Иванов И.")
        assertEquals("reference", документ(п, анализ).path("authority").asText(), "аналитика — свидетельство")

        // Тип вне перечня истины схем: правдоподобный ранг не выдумывается.
        val записка = intake.putMaterial(п, "Заметка", "note", "Текст заметки.", "Иванов И.")
        val карточка = документ(п, записка)
        assertEquals("doubtful", карточка.path("authority").asText())
        assertTrue(
            "сомнительный" in карточка.path("notes").asText(),
            "вывод ранга назван пометой: ${карточка.path("notes").asText()}",
        )

        // Ранг с формы сильнее вывода по типу: доверие называет человек.
        val сайт = intake.putMaterial(п, "Сайт поставщика", "reference", "Масса 120 кг.", "Иванов И.", authority = "doubtful")
        assertEquals("doubtful", документ(п, сайт).path("authority").asText())
    }

    @Test
    fun `факт из материала без якоря не принимается, экспертный — без учётки, роли или даты`() {
        val п = проект("PJ-9612")
        val м = intake.putMaterial(п, "Записка", "mission_memo", "Заказчик — Минтранс.\n\nСрок службы 5 лет.", "Иванов И.")
        val я = якоря(п, м)
        val итог = intake.putFacts(
            п, м,
            """{"topics":[],"actions":[],"facts":[
              {"kind":"framing","subject":"миссия","predicate":"без якоря придумано","value":"нечто",
               "source":{},"source_mark":"П"},
              {"kind":"framing","subject":"миссия","predicate":"эксперт без роли","value":"нечто",
               "source":{"account":"Иванов И."},"source_mark":"И"},
              {"kind":"framing","subject":"миссия","predicate":"и якорь и эксперт разом","value":"нечто",
               "source":{"anchor":"${я[0]}","account":"Иванов И.","role":"ведущий","at":"2026-09-12"},"source_mark":"И"},
              {"kind":"relation","subject":"заказчик","predicate":"кто заказчик","value":"Минтранс",
               "source":{"account":"Петрова М.","role":"главный конструктор","at":"2026-09-12"},"source_mark":"И"}]}""",
            "Иванов И.",
        )
        val отказы = итог.refused.joinToString("; ")
        assertTrue("без якоря — факта не существует" in отказы, отказы)
        assertTrue("без учётки, роли или даты" in отказы, отказы)
        assertTrue("разом документом и экспертом" in отказы, отказы)

        assertEquals(1, итог.accepted.size, "прошла только целая экспертная ветка: $отказы")
        val экспертный = итог.accepted.single()
        assertEquals("expert", экспертный.authority, "ранг руки — экспертный, не ранг материала")
        assertNull(экспертный.anchor)
        val источник = документ(п, экспертный.id).path("source")
        assertEquals("Петрова М.", источник.path("account").asText())
        assertEquals("главный конструктор", источник.path("role").asText())
        assertEquals("2026-09-12", источник.path("at").asText())
        assertFalse(источник.has("anchor"), "две ветки союза разом не пишутся")
    }

    @Test
    fun `повтор того же разбора экспертный факт не удваивает`() {
        val п = проект("PJ-9615")
        val м = intake.putMaterial(п, "Записка", "mission_memo", "Заказчик — Минтранс.", "Иванов И.")
        val разбор = """{"topics":[],"actions":[],"facts":[
              {"kind":"relation","subject":"заказчик","predicate":"кто заказчик","value":"Минтранс",
               "source":{"account":"Петрова М.","role":"главный конструктор","at":"2026-09-12"},"source_mark":"И"}]}"""
        assertEquals(1, intake.putFacts(п, м, разбор, "Иванов И.").accepted.size)
        // Место факта в источнике у руки — учётка автора: без неё ключ повтора
        // схлопнулся бы к «|утверждение», и второй приём завёл бы второй факт.
        assertEquals(0, intake.putFacts(п, м, разбор, "Иванов И.").accepted.size, "повтор не заводит второй факт")
        assertEquals(1, intake.facts(п).size)
    }

    @Test
    fun `на проекте поля знаний ранг материала и роль автора обязательны`() {
        val п = проект("PJ-9613", полеЗнаний = true)
        val безРанга = assertFailsWith<IllegalArgumentException> {
            intake.putMaterial(п, "ТЗ", "tor", "п. 4.1 Текст требования.", "Иванов И.")
        }
        assertTrue("ранг доверия материала обязателен" in безРанга.message!!, безРанга.message!!)
        assertTrue(
            "обязательный · экспертный · справочный · сомнительный" in безРанга.message!!,
            "выбор назван словами, а не кодами: ${безРанга.message}",
        )
        val м = intake.putMaterial(п, "ТЗ", "tor", "п. 4.1 Текст требования.", "Иванов И.", authority = "mandatory")
        assertEquals("mandatory", документ(п, м).path("authority").asText())

        val безРоли = assertFailsWith<IllegalArgumentException> {
            intake.addFact(п, "система", "срок службы", "5", "год", "quantity", null, null, "Иванов И.")
        }
        assertTrue("роль автора обязательна" in безРоли.message!!, безРоли.message!!)

        val чужойРанг = assertFailsWith<IllegalArgumentException> {
            intake.addFact(
                п, "система", "срок службы", "5", "год", "quantity", null, null, "Иванов И.",
                role = "ведущий системный инженер", authority = "важный",
            )
        }
        assertTrue("неизвестен" in чужойРанг.message!!, чужойРанг.message!!)

        // Правило честности прежнее: величина без единицы — не факт, кто бы её ни заводил.
        val безЕдиницы = assertFailsWith<IllegalArgumentException> {
            intake.addFact(
                п, "система", "масса", "280", null, "quantity", null, null, "Иванов И.",
                role = "ведущий системный инженер",
            )
        }
        assertTrue("без единицы" in безЕдиницы.message!!, безЕдиницы.message!!)
    }

    @Test
    fun `на проекте прохода ручной факт сохраняется прежним источником`() {
        val п = проект("PJ-9616")
        // Роли никто не спрашивает — и ввод идёт как до перестройки.
        val ф = intake.addFact(п, "терминал", "длительность сеанса", "12", "с", "quantity", null, null, "Иванов И.")
        assertTrue(ф.material.startsWith("инженер, "), ф.material)
        assertTrue(ф.source is FactSource.FromMaterial, "прежний источник цел: ${ф.source}")
        assertEquals("expert", ф.authority, "ранг ручного факта — экспертный и без поля знаний")
    }

    @Test
    fun `профиль содержимого ставит разбор, доли вне единицы не принимаются`() {
        val п = проект("PJ-9614")
        val м = intake.putMaterial(п, "ТЗ", "tor", "п. 4.1 Срок службы 5 лет.", "Иванов И.")
        val я = якоря(п, м)
        val сБраком = intake.putFacts(
            п, м,
            """{"profile":{"statement":0.6,"params":0.6},"topics":[],"actions":[],"facts":[
              {"kind":"quantity","subject":"система","predicate":"срок службы","value":"5","unit":"год",
               "source":{"anchor":"${я[0]}"},"source_mark":"И"}]}""",
            "Иванов И.",
        )
        assertEquals(1, сБраком.accepted.size, "брак профиля не отменяет фактов")
        assertTrue(
            сБраком.refused.any { "профиль содержимого не принят" in it },
            "брак назван поимённо: ${сБраком.refused}",
        )
        assertFalse(документ(п, м).path("profile").isObject, "бракованный профиль на материал не садится")

        intake.putFacts(
            п, м,
            """{"profile":{"statement":0.5,"params":0.2,"norms":0.2,"assessments":0.1},
                "topics":[],"actions":[],"facts":[]}""",
            "Иванов И.",
        )
        val профиль = документ(п, м).path("profile")
        assertEquals(0.5, профиль.path("statement").asDouble(), 1e-9)
        assertEquals(0.1, профиль.path("assessments").asDouble(), 1e-9)

        // Доли стережёт сам вид: по профилю с суммой больше единицы режим
        // атомизации блока выбирался бы наугад.
        assertFailsWith<IllegalArgumentException> { ContentProfile(statement = 1.2) }
        assertFailsWith<IllegalArgumentException> { ContentProfile(statement = 0.6, params = 0.6) }
    }
}
