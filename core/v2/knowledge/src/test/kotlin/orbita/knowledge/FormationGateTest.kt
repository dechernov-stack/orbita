// Ворота приёма: что проверяется — и чего ворота не делают никогда.
//
// РЕШЕНИЕ-ЧИТАТЬ-СМЫСЛ (владелец, 15.09) сняло лексические ворота: «предикаты
// задумывались подсказкой, а записаны воротами», и очевидное отбивалось —
// «Минтранс России — заказчик» уходило в отказ, потому что факт оказался вида
// `assessment`, а правило ждало `relation`. Истина теперь называет ворота
// поимённо (`gates.machine_checkable_only`): цитата в каноне · якорь · число с
// единицей · разрешимость ссылок · обязательные поля · дубль по ключу. И так
// же поимённо — чего ворота не делают (`gates.never`): не судят о виде факта,
// не требуют предиката из списка, не отбивают содержательное предложение по
// форме.
//
// Тесты, стоявшие здесь до 15.09 и пришивавшие лексический отказ, переписаны
// в свою противоположность: они сторожат, что отказа БОЛЬШЕ НЕТ. Прочие ворота
// (ранг материала, обязательные поля и связи, вид факта из истины схем) стоят
// как стояли, и отказ по-прежнему обязан называть, чего не хватило.
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
import orbita.knowledge.schema.GeneratedOntology
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
    fun `вид факта приёму не судья — понятие образуется из любого вида`() {
        // Ровно тот случай, на котором встал прогон: факт вида relation, а
        // заводится нужда. До 15.09 приём отбивал это «не подходит под правило
        // образования»; теперь вид факта воротами не служит, и решает человек.
        val задание = план(
            """
            {"facts":[
              {"kind":"relation","subject":"Минвостокразвития","predicate":"является заказчиком",
               "value":"заказчик/регулятор макрорегиона","source":{"anchor":"ЯКОРЬ"},
               "mark":"И","entity_class":"stakeholder"}],
             "actions":[
              {"kind":"create_entity","target_kind":"need","scene":"3",
               "title":"нужда из строки роли","preview":"появится нужда",
               "payload":{"statement":"заказчик/регулятор макрорегиона",
                          "stakeholder":"Минтранс России","owner":"Минтранс России"},
               "facts":[0]}]}
            """.trimIndent(),
        )

        val принято = знания.accept(проект, задание, listOf(0), автор)

        assertTrue(
            принято.notes.none { it.contains("не подходит под правило образования") },
            "лексического отказа больше нет (gates.never): ${принято.notes}",
        )
        assertEquals(1, store.list(область, "need").size, "понятие заведено: решает человек, а не словарь")
    }

    @Test
    fun `предикат вне подсказки приёму не судья`() {
        // «вынужден» есть в predicate_hints нужды, но НЕ в from_facts —
        // владелец оставил расхождение намеренно, объявив подсказки не
        // фильтром. Слово вообще чужое («испытывает дефицит») — и это тоже не
        // повод отбить: предложение судится по содержанию, не по словарю.
        val задание = план(
            """
            {"facts":[
              {"kind":"assessment","subject":"Минтранс России","predicate":"испытывает дефицит",
               "value":"непрерывной телеметрии вне зоны покрытия","source":{"anchor":"ЯКОРЬ"},
               "mark":"П"}],
             "actions":[
              {"kind":"create_entity","target_kind":"need","scene":"3",
               "title":"нужда из оценки","preview":"появится нужда",
               "payload":{"statement":"непрерывная телеметрия вне зоны покрытия",
                          "stakeholder":"Минтранс России","owner":"Минтранс России"},
               "facts":[0]}]}
            """.trimIndent(),
        )

        val принято = знания.accept(проект, задание, listOf(0), автор)

        assertTrue(принято.notes.isEmpty(), "отказывать не в чем: ${принято.notes}")
        assertEquals(1, store.list(область, "need").size, "нужда заведена предикатом вне всякого перечня")
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
               "mark":"И"}],
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
    fun `издатель акта — анти-пример инструкции, а не отказ ворот`() {
        // «Президент Российской Федерации» попал в стороны потому, что
        // подписал Указ 645. Это по-прежнему неверно — но чинится это
        // ИНСТРУКЦИЕЙ модели, а не отказом ворот: истина держит анти-пример в
        // `not_from`, и он идёт в промпт. Ворота о виде факта не судят.
        val задание = план(
            """
            {"facts":[
              {"kind":"obligation","subject":"Президент Российской Федерации",
               "predicate":"утверждает Стратегию развития Арктики","value":"Указ № 645",
               "source":{"anchor":"ЯКОРЬ"},"mark":"И"},
              {"kind":"relation","subject":"ГКРЧ","predicate":"регулирует",
               "value":"радиочастотный спектр","source":{"anchor":"ЯКОРЬ"},
               "mark":"И","entity_class":"stakeholder"}],
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

        assertEquals(2, принято.codes.size, "обе строки прошли: ворота о виде факта не судят")
        assertTrue(принято.notes.isEmpty(), "лексического отказа не осталось: ${принято.notes}")

        // А вот инструкция модели обязана остаться — и она в истине, не в коде.
        val анти = GeneratedOntology.of("stakeholder").notFrom
        assertTrue(
            анти.any { "издател" in it.lowercase() } && анти.any { "подписант" in it.lowercase() },
            "анти-пример «издатель акта — не сторона» назван истиной онтологии: $анти",
        )
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
               "source":{"anchor":"ЯКОРЬ"},"mark":"В","entity_class":"stakeholder"},
              {"kind":"framing","subject":"Рыбопромысловый флот Приморья","predicate":"нуждается в",
               "value":"спутниковом канале","source":{"anchor":"ЯКОРЬ"},"mark":"В"}],
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
               "value":"180 МКА","source":{"anchor":"ЯКОРЬ"},"mark":"И"}],
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
    fun `факт без вида отклоняется поимённо и отказ перечисляет виды истины`() {
        // Молчаливый framing ставил фактам ровно тот вид, из которого
        // онтология образует нужду, — и текст роли приезжал нуждой. Вид
        // факта стал обязательным; перечень ведёт истина схем.
        val итог = разобрать(
            """
            {"facts":[
              {"subject":"Приморье","predicate":"нуждается в","value":"спутниковом канале",
               "source":{"anchor":"ЯКОРЬ"},"mark":"В"}],
             "actions":[]}
            """.trimIndent(),
        )

        assertTrue(итог.accepted.isEmpty(), "факт без вида не принят: ${итог.accepted}")
        assertTrue(store.list(область, "fact").isEmpty(), "факта в проекте не завелось")
        val отказ = итог.refused.single()
        assertTrue(отказ.contains("нуждается в"), "отклонённое названо поимённо: $отказ")
        assertTrue(отказ.contains("вид не назван"), "сказано, чего не хватило: $отказ")
        // Перечень ведёт ИСТИНА СХЕМ, и второй копии в тесте быть не должно:
        // 15.09 владелец добавил к восьми видам ещё два (чужая цель и чужая
        // нужда — для применимости), и список, переписанный сюда руками,
        // сделал бы тест сторожем вчерашней истины.
        assertTrue(
            ВИДЫ_ФАКТА.containsAll(
                listOf("quantity", "capability", "obligation", "framing", "event", "relation", "assessment"),
            ),
            "истина схем несёт основные виды факта: $ВИДЫ_ФАКТА",
        )
        ВИДЫ_ФАКТА.forEach { вид ->
            assertTrue(отказ.contains(вид), "в отказе назван вид «$вид»: $отказ")
        }
    }

    // --- оснастка ------------------------------------------------------------

    private fun разобрать(текстРазбора: String, ранг: String = Authority.MANDATORY): FactIntake {
        val материал = знания.putMaterial(
            проект, "Записка о миссии", "mission_memo", ТЕКСТ, автор, rank = ранг,
        )
        val якорь = знания.canon(проект, материал).first { it.kind == "para" }.anchor
        return знания.putFacts(проект, материал, текстРазбора.replace("ЯКОРЬ", якорь), автор)
    }

    /** Задание с планом разбора; факты обязаны пройти правила честности §6.1. */
    @Test
    fun `та же нужда у двух сторон плана — одна нужда с двумя носителями, копии нет`() {
        // Одна формулировка — одна нужда, носителей много (истина 24.09):
        // план разбора называет общую нужду у каждой стороны, а в проекте
        // она одна — вторая строка прибавляет носителя и основание.
        store.create(
            "SK-0002", "stakeholder", область, "3",
            mapper.createObjectNode().put("name", "Росморпорт").put("role", "оператор"),
            провенанс,
        )
        val задание = план(
            """
            {"facts":[
              {"kind":"framing","subject":"Минтранс России","predicate":"не хватает",
               "value":"непрерывного мониторинга судов на СМП","source":{"anchor":"ЯКОРЬ"},"mark":"И"},
              {"kind":"framing","subject":"Росморпорт","predicate":"не хватает",
               "value":"непрерывного мониторинга судов на СМП","source":{"anchor":"ЯКОРЬ"},"mark":"И"}],
             "actions":[
              {"kind":"create_entity","target_kind":"need","scene":"3",
               "title":"нужда Минтранса","preview":"появится нужда",
               "payload":{"statement":"Непрерывный мониторинг судов на СМП","owner":"Минтранс России"},"facts":[0]},
              {"kind":"create_entity","target_kind":"need","scene":"3",
               "title":"нужда Росморпорта","preview":"появится нужда",
               "payload":{"statement":"Непрерывный мониторинг судов на СМП","owner":"Росморпорт"},"facts":[1]}]}
            """.trimIndent(),
        )

        val принято = знания.accept(проект, задание, listOf(0, 1), автор)

        val нужды = store.list(область, "need").filter { it.status != "cancelled" }
        assertEquals(1, нужды.size, "копии нет: ${нужды.map { it.code }}")
        val нужда = нужды.single()
        assertEquals(
            setOf("SK-0001", "SK-0002"), links.to(нужда.id, "owns").map { store.byId(it.from)!!.code }.toSet(),
            "обе стороны — носители одной нужды",
        )
        assertEquals(
            setOf("SK-0001", "SK-0002"), нужда.doc.path("stakeholders").map { store.byId(it.asText())!!.code }.toSet(),
            "поле stakeholders зеркалит связи",
        )
        assertTrue(принято.notes.any { "носителем" in it }, "приём обязан сказать, что копия стала носителем: ${принято.notes}")
        assertEquals(2, links.from(нужда.id, "derived_from_fact").size, "оба факта — основания одной нужды")
    }

    private fun план(текстРазбора: String, ранг: String = Authority.MANDATORY): String {
        val итог = разобрать(текстРазбора, ранг)
        assertTrue(итог.refused.isEmpty(), "разбор отклонён ещё до ворот плана: ${итог.refused}")
        return assertNotNull(итог.task, "план собран разбором — задание обязано быть названо")
    }

    /** Факт узлом: только те поля, по которым правило образования и судит. */

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
