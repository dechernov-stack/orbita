// Каждый критерий готовности обязан УМЕТЬ ОТКАЗАТЬ.
//
// Правило владельца 08.09 после двух дефектов одного класса — «проверка,
// которая молча проходит»: срок хранился идентификатором, а условие искало
// по коду, и точка объявляла себя готовой, ничего не проверив; порог
// «Б1: увод не более 5 лет» читался как число 1 из обозначения норматива.
// Оба поймались живьём, потому что тесты проверяли, что критерий
// ПРОПУСКАЕТ, и молчали о том, что он не умеет держать.
//
// Здесь для каждого условия оценщика стоит фикстура, при которой оно
// ОБЯЗАНО вернуть причину. Пустой результат проверки ≠ «выполнено».
package orbita.readiness

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Provenance
import orbita.kernel.internal.PgEntityStore
import orbita.kernel.internal.PgLinkRegistry
import orbita.readiness.api.ReadinessFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GateRefusalTest {

    private val mapper = ObjectMapper()
    private val store = PgEntityStore(TestDbV2.conn, mapper)
    private val links = PgLinkRegistry(TestDbV2.conn)
    private val провенанс = Provenance(Channel.MANUAL, "Иванов И.")
    private val проект = "PJ-9002"
    private val область = Area.Project(проект)

    private val оценщик = ReadinessFactory.gateEvaluator(
        store, links, scenesDone = { emptySet() }, gatesPassed = { emptySet() },
    )

    @BeforeTest
    fun чисто() = TestDbV2.очистить()

    /** Причина отказа: null означало бы «выполнено». */
    private fun причина(условие: String): String =
        assertNotNull(оценщик.why(проект, условие), "условие «$условие» обязано отказать на пустом проекте")

    private fun проект() = store.create(
        проект, "project", область, "1", mapper.createObjectNode(), провенанс,
    )

    @Test
    fun `нет проекта — отказ`() {
        assertTrue("проект" in причина("project_exists").lowercase())
    }

    @Test
    fun `нет точек фазы — отказ, и три точки его снимают`() {
        проект()
        assertNotNull(оценщик.why(проект, "points_planned"), "без точек фазы условие обязано держать")
        listOf("internal_review", "MCR", "KDP-A").forEach { ключ ->
            store.create(
                ключ, "gate", область, "1",
                mapper.readTree("""{"title":"$ключ","kind":"phase","planned_date":"2026-11-06"}"""),
                провенанс,
            )
        }
        assertNull(оценщик.why(проект, "points_planned"), "три точки с датами условие снимают")
    }

    @Test
    fun `открытое замечание точки — отказ с текстом и сценой возврата`() {
        проект()
        store.create(
            "RFA-0001", "finding", область, "16",
            mapper.readTree("""{"gate":"MCR","text":"нет владельца у нужды N-3","returns_to_scene":"3","author":"Чернов Д."}"""),
            провенанс, status = "open",
        )
        val почему = причина("findings_closed:MCR")
        assertTrue("нет владельца" in почему && "сцена 3" in почему, почему)
        assertNull(оценщик.why(проект, "findings_closed:internal_review"), "у другой точки замечаний нет")
    }

    @Test
    fun `нет записей вида — отказ по-русски, запись снимает`() {
        проект()
        val почему = причина("exists:goal")
        assertTrue("нет ни одной записи" in почему, почему)
        store.create("G-1", "goal", область, "4", mapper.createObjectNode(), провенанс)
        assertNull(оценщик.why(проект, "exists:goal"))
    }

    @Test
    fun `допущение без подтверждения к точке — отказ с владельцем`() {
        проект()
        store.create("F-0001", "fact", область, null,
            mapper.readTree("""{"subject":"терминал","predicate":"сеанс 12 с","value":"12","unit":"с","disposition":"assumed","assumption":{"owner":"Иванов И.","confirm_by":"MCR","validation":"замер на стенде","impact_if_wrong":"бюджет мощности"}}"""),
            провенанс)
        val почему = причина("assumptions_confirmed:MCR")
        assertTrue("Иванов И." in почему && "F-0001" in почему, почему)
        assertNull(оценщик.why(проект, "assumptions_confirmed:KDP-A"), "к другой точке допущений нет")
    }

    @Test
    fun `тема с принятыми фактами без сущности — отказ, разрешённая — нет`() {
        проект()
        store.create("TP-0001", "topic", область, null, mapper.readTree("""{"label":"Платформа Спутникс"}"""), провенанс)
        store.create("F-0002", "fact", область, null,
            mapper.readTree("""{"subject":"платформа","predicate":"масса","value":"96","unit":"кг","disposition":"adopted","topic":"TP-0001"}"""), провенанс)
        val почему = причина("topics_resolved")
        assertTrue("TP-0001" in почему, почему)
        val тема = store.byCode(область, "TP-0001")!!
        store.update(тема.id, (тема.doc.deepCopy() as com.fasterxml.jackson.databind.node.ObjectNode).put("resolved_to", "SC-PLT"), провенанс)
        assertNull(оценщик.why(проект, "topics_resolved"))
    }

    @Test
    fun `принятый факт с живым противоречием — отказ, отклонённый спорщик снимает`() {
        проект()
        store.create("F-0003", "fact", область, null,
            mapper.readTree("""{"subject":"платформа","predicate":"масса","value":"96","unit":"кг","disposition":"adopted","conflicts":["F-0004"]}"""), провенанс)
        store.create("F-0004", "fact", область, null,
            mapper.readTree("""{"subject":"платформа","predicate":"масса","value":"120","unit":"кг","disposition":"free","conflicts":["F-0003"]}"""), провенанс)
        val почему = причина("facts_consistent")
        assertTrue("F-0003" in почему && "F-0004" in почему, почему)
        val спорщик = store.byCode(область, "F-0004")!!
        store.update(спорщик.id, (спорщик.doc.deepCopy() as com.fasterxml.jackson.databind.node.ObjectNode).put("disposition", "rejected"), провенанс)
        assertNull(оценщик.why(проект, "facts_consistent"))
    }

    @Test
    fun `непринятый замысел — отказ`() {
        проект()
        store.create("IN-0001", "intent", область, "2", mapper.createObjectNode(), провенанс, status = "draft")
        assertTrue("замысел" in причина("intent_accepted").lowercase())
    }

    @Test
    fun `стейкхолдеров меньше нужного — отказ числом`() {
        проект()
        store.create("SK-0001", "stakeholder", область, "3",
            mapper.readTree("""{"name":"Одна сторона","role":"customer"}"""), провенанс)
        val ответ = причина("stakeholders_min:3")
        assertTrue("1 из 3" in ответ, "названо, сколько есть и сколько нужно: $ответ")
    }

    @Test
    fun `сторона без нужды — отказ с именем`() {
        проект()
        store.create("SK-0001", "stakeholder", область, "3",
            mapper.readTree("""{"name":"Минтранс России","role":"regulator"}"""), провенанс)
        assertTrue("Минтранс России" in причина("each_stakeholder_has_need"))
    }

    @Test
    fun `нужда без цели — отказ`() {
        проект()
        store.create("ND-0001", "need", область, "3",
            mapper.readTree("""{"statement":"нужда без цели"}"""), провенанс)
        assertTrue("цел" in причина("each_need_has_goal").lowercase())
    }

    @Test
    fun `нужда без сервиса — отказ`() {
        проект()
        store.create("ND-0001", "need", область, "3",
            mapper.readTree("""{"statement":"нужда без сервиса"}"""), провенанс)
        assertNotNull(оценщик.why(проект, "each_need_has_service"))
    }

    @Test
    fun `ограничений меньше нужного — отказ`() {
        проект()
        assertNotNull(оценщик.why(проект, "constraints_min:1"), "рамки проекта задаются в сцене 5")
    }

    @Test
    fun `плана работ нет — отказ`() {
        проект()
        assertNotNull(оценщик.why(проект, "phase_plan_started"))
    }

    @Test
    fun `даты точек не заданы — отказ`() {
        проект()
        store.create("MCR", "gate", область, "1",
            mapper.readTree("""{"title":"MCR","kind":"phase","planned_date":""}"""), провенанс)
        assertNotNull(оценщик.why(проект, "gate_dates_planned"))
    }

    @Test
    fun `неизвестное условие не выдаётся за выполненное`() {
        проект()
        val ответ = причина("условие_которого_нет")
        assertTrue(
            "не реализовано" in ответ,
            "оценщик обязан сказать, что правила не знает, а не пропустить: $ответ",
        )
    }
}
