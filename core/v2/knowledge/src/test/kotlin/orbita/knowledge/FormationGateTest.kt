// Ворота образования: почему постановка больше не набирается мусором.
//
// Остановка ПМИ-6 (14.09): на экране «Постановка» строка РОЛИ стороны стала
// нуждой, автор указа — стороной, а строки материала ранга «сомнительный» —
// принятыми сущностями. Сущности создал не синтез, а приём ПЛАНА РАЗБОРА:
// тот путь не спрашивал онтологию, не проверял обязательных связей и не
// смотрел на ранг материала. Здесь проверяется, что теперь спрашивает.
//
// Мера читается так же, как её сформулировал владелец: каждое ожидание из
// диагноза — отдельным тестом, и отказ обязан НАЗЫВАТЬ, чего не хватило.
// Отказ без имени правила человек чинить не может.
//
// Модели тут нет и базы тут нет: разбор приходит готовым JSON, хранилище и
// реестр связей — общая оснастка поля (ПамятьПоля, СвязиПоля). Проверяется
// то, что система считает ПО ДАННЫМ, а не то, как отвечает модель.
package orbita.knowledge

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.kernel.api.Provenance
import orbita.knowledge.api.Authority
import orbita.knowledge.api.FactIntake
import orbita.knowledge.api.FormationRules
import orbita.knowledge.internal.EntityIntake
import orbita.knowledge.internal.ВИДЫ_ФАКТА
import orbita.knowledge.schema.Concept
import orbita.knowledge.schema.ConceptIdentity
import orbita.knowledge.schema.FactRule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FormationGateTest {

    private val mapper = ObjectMapper()
    private val store: EntityStore = ПамятьПоля()
    private val links: LinkRegistry = СвязиПоля()
    private val знания = EntityIntake(store, links, mapper)

    private val проект = "PJ-9003"
    private val область = Area.Project(проект)
    private val автор = "Иванов И."
    private val провенанс = Provenance(Channel.MANUAL, автор)

    /**
     * Поле знаний v2 включено полем проекта: ворота живут только здесь.
     * На проекте прохода ПМИ-5 приём плана работает как работал, и ни один
     * тест этого файла к нему не относится.
     */
    @BeforeTest
    fun поле() {
        store.create(
            проект, "project", область, "1",
            mapper.createObjectNode().put("name", "Спутниковый IoT").put("knowledge_v2", true),
            провенанс,
        )
        store.create(
            "SK-0001", "stakeholder", область, "3",
            mapper.createObjectNode().put("name", "Минтранс России").put("role", "заказчик"),
            провенанс,
        )
    }

    // --- ожидания владельца из диагноза -------------------------------------

    @Test
    fun `строка роли стороны нуждой не становится и отказ называет правило`() {
        // Ровно ND-0002 из снимка: «Заказчик/регулятор макрорегиона» — это
        // колонка «роль» стороны, факт вида relation. Нужда из онтологии
        // рождается из framing «нуждается в · требует · не хватает».
        val задание = план(
            """
            {"facts":[
              {"kind":"relation","subject":"Минвостокразвития","predicate":"является заказчиком",
               "value":"заказчик/регулятор макрорегиона","source":{"anchor":"ЯКОРЬ"},
               "source_mark":"И","entity_class":"stakeholder"}],
             "actions":[
              {"kind":"create_entity","target_kind":"need","scene":"3",
               "title":"нужда из строки роли","preview":"появится нужда",
               "payload":{"statement":"заказчик/регулятор макрорегиона",
                          "stakeholder":"Минтранс России","owner":"Минтранс России"},
               "facts":[0]}]}
            """.trimIndent(),
        )

        val принято = знания.accept(проект, задание, listOf(0), автор)

        assertTrue(принято.codes.isEmpty(), "из строки роли нужда не образуется: ${принято.codes}")
        assertTrue(store.list(область, "need").isEmpty(), "ни одной нужды в проекте не завелось")
        val отказ = принято.notes.single()
        assertTrue(
            отказ.contains("не подходит под правило образования"),
            "отказ называет причину, а не молчит: $отказ",
        )
        assertTrue(отказ.contains("вид [framing]"), "названо, ИЗ ЧЕГО нужда образуется: $отказ")
        assertTrue(
            отказ.contains("нуждается в · требует · не хватает"),
            "названы предикаты правила — человеку есть что чинить: $отказ",
        )
    }

    @Test
    fun `нужда из факта framing с предикатом нужды принимается`() {
        // Обратная сторона той же меры: ворота отсекают не всё подряд. Если
        // этот тест красный, ворота глухие — постановка не наберётся вовсе.
        val задание = план(
            """
            {"facts":[
              {"kind":"framing","subject":"Минтранс России","predicate":"нуждается в",
               "value":"связь в Арктике на всём протяжении СМП","source":{"anchor":"ЯКОРЬ"},
               "source_mark":"И"}],
             "actions":[
              {"kind":"create_entity","target_kind":"need","scene":"3",
               "title":"нужда Минтранса","preview":"появится нужда",
               "payload":{"statement":"связь в Арктике на всём протяжении СМП",
                          "stakeholder":"Минтранс России","owner":"Минтранс России"},
               "facts":[0]}]}
            """.trimIndent(),
        )

        val принято = знания.accept(проект, задание, listOf(0), автор)

        assertTrue(принято.notes.isEmpty(), "отказывать тут не в чем: ${принято.notes}")
        val код = принято.codes.single()
        val нужда = assertNotNull(store.byCode(область, код), "нужда «$код» обязана быть в проекте")
        assertEquals("need", нужда.kind)
        assertEquals("связь в Арктике на всём протяжении СМП", нужда.doc.path("statement").asText())
        val носитель = assertNotNull(store.byCode(область, "SK-0001"))
        assertTrue(
            links.from(носитель.id, "owns").any { it.to == нужда.id },
            "носитель ушёл в связь, а не остался строкой в документе",
        )
    }

    @Test
    fun `автор акта стороной не становится а носитель роли становится`() {
        // «Президент Российской Федерации» попал в стороны потому, что
        // подписал Указ 645. Автор акта — не сторона; сторона образуется из
        // relation с предикатом роли К ПРОЕКТУ. Оба действия в одном плане:
        // видно, что ворота разделяют, а не запрещают.
        val задание = план(
            """
            {"facts":[
              {"kind":"obligation","subject":"Президент Российской Федерации",
               "predicate":"утверждает Стратегию развития Арктики","value":"Указ № 645",
               "source":{"anchor":"ЯКОРЬ"},"source_mark":"И"},
              {"kind":"relation","subject":"ГКРЧ","predicate":"регулирует",
               "value":"радиочастотный спектр","source":{"anchor":"ЯКОРЬ"},
               "source_mark":"И","entity_class":"stakeholder"}],
             "actions":[
              {"kind":"create_entity","target_kind":"stakeholder","scene":"3",
               "title":"Президент Российской Федерации","preview":"появится сторона миссии",
               "payload":{"name":"Президент Российской Федерации","role":"подписал указ"},"facts":[0]},
              {"kind":"create_entity","target_kind":"stakeholder","scene":"3",
               "title":"ГКРЧ","preview":"появится сторона миссии",
               "payload":{"name":"ГКРЧ","role":"регулятор"},"facts":[1]}]}
            """.trimIndent(),
        )

        val принято = знания.accept(проект, задание, listOf(0, 1), автор)

        val код = принято.codes.single()
        assertEquals(
            "ГКРЧ", assertNotNull(store.byCode(область, код)).doc.path("name").asText(),
            "прошла сторона, названная предикатом роли",
        )
        assertTrue(
            store.list(область, "stakeholder").none {
                it.doc.path("name").asText() == "Президент Российской Федерации"
            },
            "автор акта в сторонах проекта не появился",
        )
        val отказ = принято.notes.single()
        assertTrue(отказ.contains("Президент Российской Федерации"), "отказ назван поимённо: $отказ")
        assertTrue(отказ.contains("вид [relation]"), "названо, из чего сторона образуется: $отказ")
        assertTrue(отказ.contains("является заказчиком"), "названы предикаты роли: $отказ")
    }

    @Test
    fun `материал ранга сомнительный пакетом не принимается ни одним действием`() {
        // Оба действия ниже прошли бы по онтологии: relation с предикатом
        // роли и framing «нуждается в». Останавливает их ТОЛЬКО ранг — иначе
        // проверка сводилась бы к правилам образования и ничего о ранге не
        // говорила.
        val задание = план(
            """
            {"facts":[
              {"kind":"relation","subject":"Корпорация развития Дальнего Востока",
               "predicate":"является оператором","value":"цифровых коридоров",
               "source":{"anchor":"ЯКОРЬ"},"source_mark":"В","entity_class":"stakeholder"},
              {"kind":"framing","subject":"Рыбопромысловый флот Приморья","predicate":"нуждается в",
               "value":"спутниковом канале","source":{"anchor":"ЯКОРЬ"},"source_mark":"В"}],
             "actions":[
              {"kind":"create_entity","target_kind":"stakeholder","scene":"3",
               "title":"Корпорация развития Дальнего Востока","preview":"появится сторона миссии",
               "payload":{"name":"Корпорация развития Дальнего Востока","role":"оператор"},"facts":[0]},
              {"kind":"create_entity","target_kind":"need","scene":"3",
               "title":"нужда рыбопромыслового флота","preview":"появится нужда",
               "payload":{"statement":"спутниковый канал на промысле",
                          "stakeholder":"Минтранс России","owner":"Минтранс России"},
               "facts":[1]}]}
            """.trimIndent(),
            ранг = Authority.DOUBTFUL,
        )

        val принято = знания.accept(проект, задание, listOf(0, 1), автор)

        assertTrue(принято.codes.isEmpty(), "из непроверенного пакетом не принимается ничего: ${принято.codes}")
        assertTrue(store.list(область, "need").isEmpty(), "нужд не завелось")
        assertEquals(
            listOf("SK-0001"), store.list(область, "stakeholder").map { it.code },
            "сторон прибавилось ноль: в проекте осталась только заведённая до разбора",
        )
        assertEquals(2, принято.notes.size, "о каждом снятом действии сказано отдельно: ${принято.notes}")
        принято.notes.forEach { отказ ->
            assertTrue(отказ.contains("источник не подтверждён"), "помета ранга словами: $отказ")
        }
    }

    @Test
    fun `цель без нужд не принимается и отказ называет поле`() {
        // «Покрытие 0 из 9» на снимке — это цели, заведённые без covers→need.
        // Цель, которая ничего не покрывает, и есть нулевое покрытие.
        val задание = план(
            """
            {"facts":[
              {"kind":"framing","subject":"группировка","predicate":"развернуть к 2032 году",
               "value":"180 МКА","source":{"anchor":"ЯКОРЬ"},"source_mark":"И"}],
             "actions":[
              {"kind":"create_entity","target_kind":"goal","scene":"4",
               "title":"цель: развернуть группировку","preview":"появится цель",
               "payload":{"statement":"развернуть группировку","year":"2032"},"facts":[0]}]}
            """.trimIndent(),
        )

        val принято = знания.accept(проект, задание, listOf(0), автор)

        assertTrue(принято.codes.isEmpty(), "цель без нужд не заводится: ${принято.codes}")
        assertTrue(store.list(область, "goal").isEmpty(), "ни одной цели в проекте не завелось")
        val отказ = принято.notes.single()
        assertTrue(отказ.contains("«needs»"), "отказ называет ПОЛЕ, которого не хватило: $отказ")
        assertTrue(отказ.contains("covers→need"), "названа связь онтологии, а не общее «не принято»: $отказ")
    }

    @Test
    fun `факт без вида отклоняется поимённо и отказ перечисляет восемь видов`() {
        // Молчаливый framing ставил фактам ровно тот вид, из которого
        // онтология образует нужду, — и текст роли приезжал нуждой. Вид
        // факта стал обязательным; перечень ведёт истина схем.
        val итог = разобрать(
            """
            {"facts":[
              {"subject":"Приморье","predicate":"нуждается в","value":"спутниковом канале",
               "source":{"anchor":"ЯКОРЬ"},"source_mark":"В"}],
             "actions":[]}
            """.trimIndent(),
        )

        assertTrue(итог.accepted.isEmpty(), "факт без вида не принят: ${итог.accepted}")
        assertTrue(store.list(область, "fact").isEmpty(), "факта в проекте не завелось")
        val отказ = итог.refused.single()
        assertTrue(отказ.contains("нуждается в"), "отклонённое названо поимённо: $отказ")
        assertTrue(отказ.contains("вид не назван"), "сказано, чего не хватило: $отказ")
        val виды = listOf(
            "quantity", "capability", "obligation", "framing",
            "event", "relation", "assessment", "assumption",
        )
        assertEquals(виды, ВИДЫ_ФАКТА, "перечень видов ведёт истина схем, второй копии в коде нет")
        виды.forEach { вид ->
            assertTrue(отказ.contains(вид), "в отказе назван вид «$вид»: $отказ")
        }
    }

    // --- правило образования само по себе -----------------------------------

    /** Случай таблицы: правило, факт и ожидание — по одному признаку за раз. */
    private data class Случай(
        val про: String,
        val правило: FactRule,
        val факт: JsonNode,
        val подходит: Boolean,
    )

    @Test
    fun `правило образования проверяет каждый признак онтологии`() {
        val случаи = listOf(
            Случай(
                "вид совпал", FactRule(kind = "framing"), фактУзла(kind = "framing"), true,
            ),
            Случай(
                "вид не тот", FactRule(kind = "framing"), фактУзла(kind = "relation"), false,
            ),
            Случай(
                "предикат из перечня",
                FactRule(kind = "relation", predicateIn = listOf("является заказчиком", "регулирует")),
                фактУзла(kind = "relation", predicate = "ГКРЧ регулирует спектр"), true,
            ),
            Случай(
                "предикат вне перечня",
                FactRule(kind = "relation", predicateIn = listOf("является заказчиком", "регулирует")),
                фактУзла(kind = "relation", predicate = "подписал указ"), false,
            ),
            Случай(
                "субъект совпал",
                FactRule(kind = "capability", subject = "организация"),
                фактУзла(kind = "capability", subject = "организация связи"), true,
            ),
            Случай(
                "субъект не тот",
                FactRule(kind = "capability", subject = "организация"),
                фактУзла(kind = "capability", subject = "система связи"), false,
            ),
            Случай(
                "класс субъекта совпал",
                FactRule(kind = "assessment", subjectIs = "stakeholder"),
                фактУзла(kind = "assessment", entityClass = "stakeholder"), true,
            ),
            Случай(
                "класс субъекта не тот",
                FactRule(kind = "assessment", subjectIs = "stakeholder"),
                фактУзла(kind = "assessment", entityClass = "normative_ref"), false,
            ),
            Случай(
                "метка совпала",
                FactRule(kind = "assessment", mark = "П"),
                фактУзла(kind = "assessment", mark = "П"), true,
            ),
            Случай(
                "метка не та",
                FactRule(kind = "assessment", mark = "П"),
                фактУзла(kind = "assessment", mark = "И"), false,
            ),
            Случай(
                "признак горизонта есть",
                FactRule(kind = "quantity", with = "horizon/year"),
                фактУзла(kind = "quantity", value = "2032"), true,
            ),
            Случай(
                "признака горизонта нет",
                FactRule(kind = "quantity", with = "horizon/year"),
                фактУзла(kind = "quantity", value = "180"), false,
            ),
            Случай(
                "признак обозначения есть",
                FactRule(kind = "obligation", with = "designation"),
                фактУзла(kind = "obligation", subject = "ПП РФ № 2216"), true,
            ),
            Случай(
                "признака обозначения нет",
                FactRule(kind = "obligation", with = "designation"),
                фактУзла(kind = "obligation", subject = "Стратегия развития"), false,
            ),
        )

        случаи.forEach { случай ->
            assertEquals(
                случай.подходит,
                FormationRules.подходит(понятиеИз(случай.правило), случай.факт),
                "${случай.про}: правило «${FormationRules.словами(случай.правило)}» против факта ${случай.факт}",
            )
        }
    }

    @Test
    fun `правило-подсказка воротами не служит`() {
        // `section_hint` адресован разбору, а не проверке. Считать его
        // совпадением значило бы пускать под видом правила что угодно.
        val подсказка = понятиеИз(FactRule(sectionHint = "§2.1 общие нужды → привязка к сторонам"))

        assertFalse(FormationRules.проверяемо(подсказка), "проверять подсказкой нечего")
        assertFalse(
            FormationRules.подходит(подсказка, фактУзла(kind = "framing", predicate = "нуждается в")),
            "подсказка не пускает даже подходящий по смыслу факт: ворот у неё нет",
        )
        assertTrue(
            FormationRules.проверяемо(понятиеИз(FactRule(kind = "framing"))),
            "у правила с признаком ворота есть",
        )
    }

    @Test
    fun `правило читается словами теми же признаками какими записано`() {
        assertEquals(
            "вид [framing] с предикатом из «нуждается в · требует»",
            FormationRules.словами(FactRule(kind = "framing", predicateIn = listOf("нуждается в", "требует"))),
        )
        assertEquals(
            "вид [assessment], субъект — stakeholder, метка [П]",
            FormationRules.словами(FactRule(kind = "assessment", subjectIs = "stakeholder", mark = "П")),
        )
        assertEquals(
            "правило без признаков", FormationRules.словами(FactRule()),
            "правило, которое ничего не требует, так и называется — иначе отказ читался бы как требование",
        )
    }

    // --- оснастка -----------------------------------------------------------

    /**
     * Материал названного ранга и разбор к нему: план приходит ТЕМ ЖЕ
     * ответом, что и факты, — как на живом пути. Якорь подставляется в
     * текст разбора вместо «ЯКОРЬ»: без якоря факта не существует.
     */
    private fun разобрать(текстРазбора: String, ранг: String = Authority.MANDATORY): FactIntake {
        val материал = знания.putMaterial(
            проект, "Записка о миссии", "mission_memo", ТЕКСТ, автор, authority = ранг,
        )
        val якорь = знания.canon(проект, материал).first { it.kind == "para" }.anchor
        return знания.putFacts(проект, материал, текстРазбора.replace("ЯКОРЬ", якорь), автор)
    }

    /** Задание с планом разбора; факты обязаны пройти правила честности §6.1. */
    private fun план(текстРазбора: String, ранг: String = Authority.MANDATORY): String {
        val итог = разобрать(текстРазбора, ранг)
        assertTrue(итог.refused.isEmpty(), "разбор отклонён ещё до ворот плана: ${итог.refused}")
        return assertNotNull(итог.task, "план собран разбором — задание обязано быть названо")
    }

    /** Факт узлом: только те поля, по которым правило образования и судит. */
    private fun фактУзла(
        kind: String,
        subject: String = "",
        predicate: String = "",
        value: String = "",
        mark: String = "И",
        entityClass: String = "",
    ): JsonNode = mapper.createObjectNode()
        .put("kind", kind).put("subject", subject).put("predicate", predicate)
        .put("value", value).put("source_mark", mark).put("entity_class", entityClass)

    /** Понятие с одним правилом: проверяется правило, а не онтология целиком. */
    private fun понятиеИз(vararg правила: FactRule): Concept = Concept(
        code = "проверка",
        fromFacts = правила.toList(),
        fields = emptyMap(),
        mustLink = emptyList(),
        conflictOn = emptyList(),
        identity = ConceptIdentity(key = emptyList(), semantic = "—", threshold = 1.0),
    )

    private companion object {

        /**
         * Текст материала: абзацы кончаются точкой — иначе канон считает
         * строку заголовком, и якоря «para» у материала не окажется.
         */
        val ТЕКСТ = """
            # Записка о миссии

            Заказчиком системы выступает Минтранс России, оператором — АО ГЛОНАСС.

            Использование радиочастотного спектра регулирует ГКРЧ.
        """.trimIndent()
    }
}
