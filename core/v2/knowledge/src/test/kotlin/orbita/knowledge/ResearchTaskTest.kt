// Исследование полноты: вопросы, промпт и ни одного вызова модели.
//
// Главная мера контура проверяется здесь буквально: после формулирования,
// сборки промпта и запуска в проекте нет ни одной записи `ai_call` — журнал
// ИИ не прирастает, потому что звать некого. Если однажды вопросы начнут
// сочиняться моделью, сломается именно этот файл, а не мера на стенде.
//
// Хранилище и реестр связей — память: у модуля знаний нет оснастки базы, а
// проверять надо правила контура, а не запись в Postgres.
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
import orbita.knowledge.api.ResearchClass
import orbita.knowledge.api.ResearchTrigger
import orbita.knowledge.internal.ResearchTasks
import java.time.OffsetDateTime
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResearchTaskTest {

    private val mapper = ObjectMapper()
    private val store: EntityStore = ПамятьПоля()
    private val links: LinkRegistry = СвязиПоля()
    private val исследование = ResearchTasks(store, links, mapper)

    private val проект = "PJ-9001"
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
            mapper.createObjectNode()
                .put("what", "национальная спутниковая платформа IoT")
                .put("for_whom", "транспорт, ТЭК, МЧС")
                .put("where", "в Арктике, на СМП и Транссибе")
                .put("horizon", "2033"),
            провенанс,
        )
        рамка("Р1", "полезная нагрузка регенеративная")
        рамка("Р2", "платформы 12U…100 кг")
        рамка("Р9", "российские средства выведения")
        СТОРОНЫ.forEachIndexed { i, имя -> сторона("SK-%04d".format(i + 1), имя) }
    }

    // --- вопросы и промпт --------------------------------------------------

    @Test
    fun `сцена сторон спрашивает по всем пяти классам и начинает со своего`() {
        val задача = исследование.formulate(проект, ResearchTrigger.Scene("3"), автор)

        assertEquals(1, задача.iteration, "первый цикл по месту")
        assertEquals("formulated", задача.status)
        assertTrue(задача.questions.size >= 5, "вопросов ${задача.questions.size}: по классу на каждый и не меньше")
        assertEquals(
            ResearchClass.entries.toSet(),
            задача.questions.mapNotNull { класс(it) }.toSet(),
            "исследование ходит наружу редко: спрашиваются все пять классов сразу",
        )
        assertEquals(
            ResearchClass.STAKEHOLDER, класс(задача.questions.first()),
            "сцена 3 — стороны: её класс идёт первым",
        )
    }

    @Test
    fun `сцена норм ведёт своим классом`() {
        val задача = исследование.formulate(проект, ResearchTrigger.Scene("5"), автор)
        assertEquals(ResearchClass.NORM, класс(задача.questions.first()))
    }

    @Test
    fun `промпт несёт три блока и перечисляет принятое поле`() {
        val задача = исследование.formulate(проект, ResearchTrigger.Scene("3"), автор)

        val текст = исследование.prompt(проект, задача.id)

        assertTrue(текст.contains("## Контекст проекта"), "первый блок — срез поля")
        assertTrue(текст.contains("## Вопросы исследования"), "второй блок — вопросы")
        assertTrue(текст.contains("## Требования к ответу"), "третий блок — источник, дата, метка, уверенность")
        assertTrue(текст.contains("Стороны уже названы (${СТОРОНЫ.size})"), "внешнему контуру сказано, кого уже знают")
        СТОРОНЫ.forEach { имя -> assertTrue(текст.contains(имя), "стороны «$имя» нет в промпте") }
        assertTrue(текст.contains("Р1") && текст.contains("Р9"), "рамка Р уходит целиком: искать вне неё незачем")
        assertTrue(
            текст.contains(задача.sliceFingerprint.take(12)),
            "промпт называет отпечаток среза: по нему видно, по какому полю он собран",
        )
        assertTrue(текст.contains("уверенность 0–1"), "требования к ответу — дословно из образца поставки")
    }

    @Test
    fun `ни формулирование ни промпт ни запуск не зовут модель`() {
        val задача = исследование.formulate(проект, ResearchTrigger.Scene("4"), автор)
        исследование.prompt(проект, задача.id)
        val запущена = исследование.launch(проект, задача.id, автор, "внешний контур с поиском")

        assertEquals("launched", запущена.status)
        assertTrue(
            store.list(область, "ai_call").isEmpty(),
            "в контуре исследования вызовов модели нет вовсе: журнал ИИ не прирастает",
        )
    }

    @Test
    fun `вопросы инженера принимаются вместо собранных`() {
        val задача = исследование.formulate(
            проект, ResearchTrigger.Scene("3"), автор,
            listOf("сторона: кто из регионов ДФО заинтересован?", "аналог: кто делает то же на НОО?"),
        )

        assertEquals(2, задача.questions.size, "снятое снято, дописанное дописано")
        assertEquals(
            listOf(ResearchClass.STAKEHOLDER, ResearchClass.ANALOG),
            задача.questions.mapNotNull { класс(it) },
        )
    }

    // --- гриф --------------------------------------------------------------

    @Test
    fun `запись на закрытом источнике во внешний контур не уходит`() {
        закрытаяСторона("Закрытый партнёр", "dsp")
        val задача = исследование.formulate(проект, ResearchTrigger.Scene("3"), автор)

        val текст = исследование.prompt(проект, задача.id)

        assertFalse(текст.contains("Закрытый партнёр"), "гриф ДСП наружу не уходит без явного разрешения")
        assertTrue(текст.contains("Не включено записей: 1"), "поле показано не целиком — и это сказано словами")
        assertTrue(текст.contains("Стороны уже названы (${СТОРОНЫ.size})"), "счёт сторон — по тем, что ушли")
    }

    // --- отказные ----------------------------------------------------------

    @Test
    fun `из чужой сцены исследование полноты не запускается`() {
        val отказ = assertFailsWith<IllegalArgumentException> {
            исследование.formulate(проект, ResearchTrigger.Scene("6"), автор)
        }
        assertTrue(
            отказ.message.orEmpty().contains("не запускается"),
            "отказ обязан назвать, откуда исследование бывает: ${отказ.message}",
        )
    }

    @Test
    fun `вопрос без класса не принимается`() {
        val отказ = assertFailsWith<IllegalArgumentException> {
            исследование.formulate(проект, ResearchTrigger.Scene("3"), автор, listOf("а что мы ещё не знаем?"))
        }
        assertTrue(
            отказ.message.orEmpty().contains("класс"),
            "ответ некуда будет положить: отказ называет пять классов — ${отказ.message}",
        )
    }

    @Test
    fun `второго исследования по тому же месту не заводится`() {
        исследование.formulate(проект, ResearchTrigger.Scene("3"), автор)

        val отказ = assertFailsWith<IllegalStateException> {
            исследование.formulate(проект, ResearchTrigger.Scene("3"), автор)
        }
        assertTrue(отказ.message.orEmpty().contains("RT-0001"), "отказ называет уже заведённую задачу")
    }

    @Test
    fun `гостайна в поле знаний останавливает сборку промпта`() {
        закрытаяСторона("Особый заказчик", "state_secret")

        val отказ = assertFailsWith<IllegalStateException> {
            исследование.formulate(проект, ResearchTrigger.Scene("3"), автор)
        }
        assertTrue(
            отказ.message.orEmpty().contains("государственная тайна"),
            "такой материал не загружается вовсе: тихо отфильтровать его нельзя — ${отказ.message}",
        )
    }

    @Test
    fun `результат не принимается у задачи которая ещё не запущена`() {
        val задача = исследование.formulate(проект, ResearchTrigger.Scene("3"), автор)

        val отказ = assertFailsWith<IllegalArgumentException> {
            исследование.result(проект, задача.id, "ответ.md", "текст ответа", автор)
        }
        assertTrue(
            отказ.message.orEmpty().contains("запущенной"),
            "исследование сперва уходит во внешний контур: ${отказ.message}",
        )
    }

    // --- оснастка ----------------------------------------------------------

    private fun класс(вопрос: String): ResearchClass? = ResearchClass.of(вопрос.substringBefore(":"))

    private fun рамка(код: String, текст: String) {
        store.create(
            код, "constraint", область, "5",
            mapper.createObjectNode().put("code", код).put("type", "programmatic").put("statement", текст),
            провенанс,
        )
    }

    private fun сторона(код: String, имя: String) {
        store.create(
            код, "stakeholder", область, "3",
            mapper.createObjectNode().put("name", имя).put("role", "потребитель"), провенанс,
        )
    }

    /** Сторона, выведенная из закрытого источника: гриф живёт у материала, а не у карточки. */
    private fun закрытаяСторона(имя: String, гриф: String) {
        val материал = store.create(
            "SD-9001", "material", область, "2",
            mapper.createObjectNode().put("name", "Закрытая записка").put("authority", "reference")
                .put("classification", гриф),
            провенанс,
        )
        val факт = store.create(
            "F-9001", "fact", область, null,
            mapper.createObjectNode().put("kind", "relation").put("subject", имя)
                .put("predicate", "является поставщиком").put("value", "да")
                .put("anchor", "s1#1").put("material", материал.code).put("authority", "reference"),
            провенанс,
        )
        val сторона = store.create(
            "SK-9001", "stakeholder", область, "3",
            mapper.createObjectNode().put("name", имя).put("role", "поставщик"), провенанс,
        )
        links.link("derived_from_fact", сторона.id, факт.id, провенанс)
    }

    private companion object {
        val СТОРОНЫ = listOf(
            "Минтранс России", "АО ГЛОНАСС", "Росавиация", "РЖД", "Оператор СМП",
            "МЧС России", "Рослесхоз", "Росгидромет", "Транснефть", "Агрохолдинги",
            "Роскосмос", "ГКРЧ", "Минцифры России", "Проектная компания",
        )
    }
}

/**
 * Хранилище сущностей в памяти — общая оснастка тестов контура исследования.
 *
 * Живёт не приватным классом файла намеренно: соседний тест результата
 * поднимает то же поле, а вторая копия хранилища однажды разошлась бы с этой.
 */
internal class ПамятьПоля : EntityStore {

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
internal class СвязиПоля : LinkRegistry {

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
