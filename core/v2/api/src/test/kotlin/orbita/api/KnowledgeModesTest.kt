// Знания 2 (шип E): три типа входных дают факты своим режимом, ТЗ оценено
// матрицей, даташит заполнил анкету и столкнулся с рамкой, новая версия
// источника помечает зависимое, конфликт показывает оба, допущение без
// владельца не ставится, тема разрешается в сущность.
//
// Модели здесь нет: разбор приходит готовым JSON — так проверяется всё,
// что система считает ПО ДАННЫМ, а не то, что придумает модель.
package orbita.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.api.internal.UrlSnapshot
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.Provenance
import orbita.knowledge.api.Assumption
import orbita.knowledge.api.Disposition
import orbita.knowledge.api.KnowledgeFactory
import orbita.library.api.LibraryFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KnowledgeModesTest {
    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val проект = "PJ-9601"
    private val область = Area.Project(проект)
    private val провенанс = Provenance(Channel.MANUAL, "Иванов И.")
    private val полкаКомпонентов = mapper.readTree(TestDbV2.repoRoot.resolve("docs/tz/v2/ПОЛКА-ШАБЛОНЫ-КОМПОНЕНТОВ.json").toFile())
    private val intake = KnowledgeFactory.intake(store, links, mapper, LibraryFactory.shelves(store) { mapper.createObjectNode() })

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        store.create(проект, "project", область, "1", mapper.createObjectNode().put("name", "Знания 2"), провенанс)
        store.create("CTS-9001", "component_template_shelf", Area.Library, null, полкаКомпонентов, провенанс)
    }

    private fun якоря(материал: String) = intake.canon(проект, материал).map { it.anchor }

    @Test
    fun `даташит — параметры в анкету узла, конфликт с рамкой Р2 риском, недостающее запросом`() {
        store.create("Р2", "constraint", область, "5",
            mapper.readTree("""{"text":"платформа в диапазоне 12U…100 кг","category":"техническое","bound":{"key":"mass","op":"le","value":100,"unit":"кг"}}"""), провенанс)
        store.create("Р4", "constraint", область, "5",
            mapper.readTree("""{"text":"средняя мощность ПН не более 40 Вт","category":"техническое","bound":{"key":"power","op":"le","value":40,"unit":"Вт"}}"""), провенанс)
        store.create("SC-PLT", "component", область, "7",
            mapper.readTree("""{"name":"Платформа","kind":"subsystem","level":2,"nature":"node","template_ref":"TC-SC"}"""), провенанс)
        val материал = intake.putMaterial(проект, "Даташит платформы", "datasheet",
            "Платформа микрокласса.\n\nМасса сухая 120 кг.\n\nСредняя мощность на ПН 60 Вт.", "Иванов И.")
        val я = якоря(материал)
        val json = """{"topics":[{"label":"Платформа"}],"actions":[],"facts":[
            {"kind":"quantity","topic":"Платформа","subject":"платформа","predicate":"масса сухая","value":"120","unit":"кг","source":{"anchor":"${я[1]}"},"source_mark":"В","param_key":"mass_dry"},
            {"kind":"quantity","topic":"Платформа","subject":"платформа","predicate":"пиковая мощность ПН","value":"60","unit":"Вт","source":{"anchor":"${я[2]}"},"source_mark":"В","param_key":"power_peak"}]}"""
        val итог = intake.putFacts(проект, материал, json, "Иванов И.", intent = "обнови параметры SC-PLT")
        assertEquals(2, итог.accepted.size, итог.refused.toString())
        val задание = intake.task(проект, assertNotNull(итог.task))
        val виды = задание.plan.map { it.kind to it.targetKind }
        assertEquals(2, виды.count { it == "update_params" to "parameter" }, виды.toString())
        assertEquals(1, виды.count { it == "create_risk" to "risk" }, "120 кг против Р2 ≤ 100: риск, не молчание; пик 60 Вт против средней — не риск")
        assertEquals(1, виды.count { it == "request_data" to "data_request" }, "недостающие поля анкеты — запрос поставщику")
        val принято = intake.accept(проект, задание.id, задание.plan.indices.toList(), "Иванов И.")
        val параметр = assertNotNull(store.byCode(область, "SC-PLT.mass_dry"), принято.notes.toString())
        assertEquals("datasheet", параметр.doc.path("origin").asText())
        assertEquals(120.0, параметр.doc.path("measure").path("value").asDouble())
        assertEquals(я[1], параметр.doc.path("source").path("anchor").asText(), "параметр помнит якорь даташита")
        assertTrue(store.list(область, "risk").any { "Р2" in it.doc.path("statement").asText() })
        assertNotNull(store.byCode(область, "DR-SC-PLT"))
    }

    @Test
    fun `ТЗ — оценка против нужд матрицей, непокрытая нужда — RFA заказчику`() {
        store.create("ND-0001", "need", область, "3", mapper.readTree("""{"statement":"непрерывное поступление телеметрии"}"""), провенанс)
        store.create("ND-0002", "need", область, "3", mapper.readTree("""{"statement":"подтверждаемая доставка сообщений"}"""), провенанс)
        val материал = intake.putMaterial(проект, "ТЗ заказчика", "tor",
            "п. 4.1 Система обязана обеспечивать приём телеметрии с интервалом не более 30 мин.\n\nп. 4.2 Срок службы не менее 5 лет.", "Иванов И.")
        val я = якоря(материал)
        val json = """{"topics":[],"actions":[
            {"kind":"create_entity","target_kind":"requirement","scene":"8","title":"требование из п. 4.1","preview":"…","payload":{"statement":"приём телеметрии не реже 30 мин","level":"project","category":"performance"},"facts":[0]}],
          "facts":[
            {"kind":"obligation","subject":"ТЗ п. 4.1","predicate":"приём телеметрии с интервалом не более 30 мин","value":"требование","source":{"anchor":"${я[0]}"},"source_mark":"И"},
            {"kind":"obligation","subject":"ТЗ п. 4.2","predicate":"срок службы не менее 5 лет","value":"требование","source":{"anchor":"${я[1]}"},"source_mark":"И"}],
          "assessment":{"lines":[{"fact":0,"requirement":"п. 4.1","needs":["ND-0001"],"verdict":"covers"},{"fact":1,"requirement":"п. 4.2","needs":[],"verdict":"none"}]}}"""
        val итог = intake.putFacts(проект, материал, json, "Иванов И.", intent = "это ТЗ — оцени против нужд")
        val задание = intake.task(проект, assertNotNull(итог.task))
        val оценка = assertNotNull(задание.assessment)
        assertEquals(listOf("ND-0002"), оценка.uncoveredNeeds, "непокрытое считается по реестру нужд")
        assertEquals(1, оценка.orphanRequirements.size, "п. 4.2 — требование без нужды")
        assertTrue(задание.plan.any { it.kind == "request_data" && it.targetKind == "finding" && "ND-0002" in it.title }, задание.plan.map { it.title }.toString())
        intake.accept(проект, задание.id, задание.plan.indices.toList(), "Чернов Д.")
        val rfa = store.list(область, "finding").firstOrNull { it.doc.path("kind").asText() == "rfa" }
        assertNotNull(rfa, "RFA заказчику записан замечанием")
        assertEquals("open", rfa.status); assertEquals("3", rfa.doc.path("returns_to_scene").asText())
        assertTrue(store.list(область, "requirement").isNotEmpty(), "требование ТЗ заведено планом")
    }

    @Test
    fun `новая версия источника помечает факты изменённых блоков и их сущности`() {
        val старый = intake.putMaterial(проект, "Записка v1", "mission_memo", "Заказчик — Минтранс.\n\nСрок службы 5 лет.", "Иванов И.")
        val я = якоря(старый)
        intake.putFacts(проект, старый, """{"topics":[],"actions":[
            {"kind":"create_entity","target_kind":"stakeholder","scene":"3","title":"сторона","preview":"…","payload":{"name":"Минтранс","role":"customer"},"facts":[0]}],
          "facts":[
            {"kind":"relation","subject":"заказчик","predicate":"кто","value":"Минтранс","source":{"anchor":"${я[0]}"},"source_mark":"И"},
            {"kind":"quantity","subject":"система","predicate":"срок службы","value":"5","unit":"год","source":{"anchor":"${я[1]}"},"source_mark":"И"}]}""", "Иванов И.")
        val задание = store.list(область, "intake_task").first()
        intake.accept(проект, задание.code, listOf(0), "Иванов И.")
        val новый = intake.putMaterial(проект, "Записка v2", "mission_memo", "Заказчик — Минтранс.\n\nСрок службы 7 лет.", "Иванов И.", supersedes = старый)
        val факты = intake.facts(проект).filter { it.material == старый }
        val срок = факты.first { it.predicate == "срок службы" }
        assertTrue(срок.sourceUpdated?.contains(новый) == true, "факт изменённого блока помечен: ${срок.sourceUpdated}")
        assertTrue(факты.first { it.predicate == "кто" }.sourceUpdated == null, "неизменённый блок не трогается")
    }

    @Test
    fun `конфликт фактов — оба со ссылкой друг на друга, ИИ не выбирает`() {
        val а = intake.putMaterial(проект, "Даташит А", "datasheet", "Масса платформы 80 кг.", "Иванов И.")
        val б = intake.putMaterial(проект, "Сайт", "reference", "Масса платформы 120 кг.", "Иванов И.")
        intake.putFacts(проект, а, """{"topics":[],"actions":[],"facts":[{"kind":"quantity","subject":"платформа","predicate":"масса","value":"80","unit":"кг","source":{"anchor":"${якоря(а)[0]}"},"source_mark":"В"}]}""", "Иванов И.")
        intake.putFacts(проект, б, """{"topics":[],"actions":[],"facts":[{"kind":"quantity","subject":"платформа","predicate":"масса","value":"120","unit":"кг","source":{"anchor":"${якоря(б)[0]}"},"source_mark":"В"}]}""", "Иванов И.")
        val факты = intake.facts(проект)
        assertEquals(2, факты.size)
        assertTrue(факты.all { it.conflicts.size == 1 }, факты.map { it.conflicts }.toString())
    }

    @Test
    fun `допущение без владельца и точки не ставится, с ними — помета П и поля`() {
        val ф = intake.addFact(проект, "терминал", "длительность сеанса", "12", "с", "quantity", null, null, "Иванов И.")
        assertFailsWith<IllegalArgumentException> { intake.dispose(проект, ф.id, Disposition.ASSUMED, "пока не измерено", "Иванов И.") }
        val принято = intake.dispose(проект, ф.id, Disposition.ASSUMED, "пока не измерено", "Иванов И.",
            Assumption("Иванов И.", "MCR", "замер на стенде", "бюджет мощности"))
        assertEquals("MCR", принято.assumption?.confirmBy); assertEquals("П", принято.mark.name)
        val тема = intake.addTopic(проект, "Терминал класса B'", "Иванов И.")
        store.create("TRM-B", "component", область, "7", mapper.readTree("""{"name":"Терминал B'","kind":"element","level":2,"nature":"node"}"""), провенанс)
        assertEquals("TRM-B", intake.resolveTopic(проект, тема.id, "TRM-B", "Иванов И.").resolvedTo)
        store.create("NR-0001", "normative_document", Area.Library, null, mapper.readTree("""{"designation":"ПП РФ № 2216","title":"ЭРА-ГЛОНАСС"}"""), провенанс)
        val нпа = intake.addTopic(проект, "ПП РФ № 2216", "Иванов И.")
        assertEquals("NR-0001", intake.resolveTopic(проект, нпа.id, "NR-0001", "Иванов И.").resolvedTo, "тема разрешается и в карточку полки")
        assertFailsWith<IllegalArgumentException> { intake.resolveTopic(проект, тема.id, "НЕТ-ТАКОЙ", "Иванов И.") }
    }

    @Test
    fun `страница на JS без рендера узнаётся, страница с текстом — нет`() {
        assertTrue(UrlSnapshot.безРендера("""<html><body><div id="root"></div><script src="app.js"></script></body></html>""", UrlSnapshot.безРазметки("""<div id="root"></div>""")))
        val текст = (1..40).joinToString(" ") { "Платформа микрокласса обеспечивает мощность $it Вт." }
        assertFalse(UrlSnapshot.безРендера("<html><body><p>$текст</p></body></html>", UrlSnapshot.безРазметки("<p>$текст</p>")))
    }
}
