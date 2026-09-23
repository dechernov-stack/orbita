// З-03 (ЗАМЕЧАНИЯ-ПМИ5-СЦЕНА-3): принятое редактируется на месте — новая
// версия с провенансом «правка инженера», связи и код целы; поле вне схемы
// и служебные виды — отказ словами; пустое значение снимает поле.
package orbita.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.api.api.Actor
import orbita.api.internal.V2Router
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.Provenance
import orbita.library.api.LibraryFactory
import orbita.process.api.ProcessFactory
import orbita.readiness.api.ReadinessFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EditInPlaceTest {
    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val полки = TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые")
    private val шаблон = mapper.readTree(полки.resolve("ШАБЛОН-ФАЗЫ-PRE-A-NASA.json").toFile())
    private val проект = "PJ-9809"
    private val область = Area.Project(проект)
    private val провенанс = Provenance(Channel.MANUAL, "Чернов Д.")
    private val п = mapOf("project" to проект)
    private val си = Actor("ivanov", "Иванов И.", setOf("lead_se"))

    private fun router(): V2Router {
        val движок = ProcessFactory.engine(
            template = { шаблон },
            evaluator = ReadinessFactory.gateEvaluator(store, links, scenesDone = { emptySet() }, gatesPassed = { emptySet() }),
            passedGates = { mutableSetOf() }, gatePlan = { emptyMap() },
        )
        return V2Router(
            store, links, движок, LibraryFactory.shelves(store) { шаблон },
            orbita.knowledge.api.KnowledgeFactory.intake(store, links, mapper),
            orbita.formulation.api.FormulationFactory.formulation(store, links), mapper,
        )
    }

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        router().handle("POST", "/v2/projects", emptyMap(), """{"name":"Правка на месте","code":"$проект","author":"Чернов Д."}""")
    }

    @Test
    fun `сторона и нужда правятся на месте — новая версия, провенанс правки, связь носителя цела`() {
        val r = router()
        r.handle("POST", "/v2/stakeholders", п, """{"name":"Минтранс России","role":"customer","interest":"телематика","author":"Иванов И."}""", си)
        r.handle("POST", "/v2/needs", п, """{"statement":"Непрерывный мониторинг","owner":"SK-0001","author":"Иванов И."}""", си)
        val сторона = store.byCode(область, "SK-0001")!!
        assertEquals(1, сторона.version)

        val ответ = r.handle("PATCH", "/v2/entities/SK-0001", п,
            """{"fields":{"name":"Минтранс России / Ространснадзор","influence":"decides","power":5,"attitude":"neutral"},"author":"Иванов И.","reason":"по пакету сцены 3"}""", си)!!
        assertEquals(200, ответ.code, ответ.body.toString())
        assertEquals(2, ответ.body.path("version").asInt())
        assertEquals(4, ответ.body.path("changed").asInt(), "имя, влияние, сила, отношение")
        // Провенанс — у той версии, которую эта правка и создала.
        val послеПравки = store.byCode(область, "SK-0001")!!
        assertTrue(послеПравки.provenance.author.startsWith("правка инженера: Иванов И."), послеПравки.provenance.author)
        assertTrue(послеПравки.provenance.author.contains("по пакету сцены 3"))
        // Необязательное поле пустым снимается: отношение стороны знают не всегда.
        val безОтношения = r.handle("PATCH", "/v2/entities/SK-0001", п,
            """{"fields":{"attitude":""},"author":"Иванов И."}""", си)!!
        assertEquals(1, безОтношения.body.path("changed").asInt(), "пустое значение снимает поле")
        // Обязательное — не снимается: сторона без интереса не отвечала бы
        // собственной схеме, а снять его молча значило бы потерять содержание.
        val снятие = runCatching {
            r.handle("PATCH", "/v2/entities/SK-0001", п, """{"fields":{"interest":""},"author":"Иванов И."}""", си)
        }.exceptionOrNull()
        assertNotNull(снятие, "снятие обязательного поля обязано отказать")
        assertTrue("interest" in (снятие.message ?: ""), снятие.message ?: "")
        val после = store.byCode(область, "SK-0001")!!
        assertEquals("Минтранс России / Ространснадзор", после.doc.path("name").asText())
        assertEquals("decides", после.doc.path("influence").asText())
        assertEquals(5, после.doc.path("power").asInt())
        // Интерес — список слов стороны (истина 24.09): строка ввода легла первым интересом.
        assertEquals(listOf("телематика"), orbita.kernel.schema.Interests.словами(после.doc.path("interest")), "обязательное поле цело")
        assertTrue(после.doc.get("attitude") == null, "необязательное снято")
        assertEquals(сторона.id, после.id, "id стабилен — связи живут")
        val нужда = store.byCode(область, "ND-0001")!!
        assertEquals(listOf(после.id), links.to(нужда.id, "owns").map { it.from }, "носитель нужды остался")

        val нуждаПосле = r.handle("PATCH", "/v2/entities/ND-0001", п, """{"fields":{"statement":"Непрерывный мониторинг вне наземного покрытия"},"author":"Иванов И."}""", си)!!
        assertEquals(2, нуждаПосле.body.path("version").asInt())
        // повтор того же — версия не плодится
        val повтор = r.handle("PATCH", "/v2/entities/ND-0001", п, """{"fields":{"statement":"Непрерывный мониторинг вне наземного покрытия"},"author":"Иванов И."}""", си)!!
        assertEquals(0, повтор.body.path("changed").asInt())
        assertEquals(2, store.byCode(область, "ND-0001")!!.version)
    }

    @Test
    fun `смена роли стороны пересчитывает влияние, а названное человеком — нет`() {
        // Журнал ПМИ-7, З-01: влияние считает система из роли — «не только при
        // синтезе». После правки роли оно обязано сойтись само.
        val r = router()
        val сторона = store.create(
            "SK-0007", "stakeholder", область, "3",
            mapper.readTree("""{"name":"ГКРЧ","role":"consumer","influence":"informed"}"""), провенанс,
        )

        r.handle(
            "PATCH", "/v2/entities/${сторона.code}", п,
            """{"fields":{"role":"regulator"},"author":"инженер","reason":"это регулятор"}""",
        )

        assertEquals("decides", store.byCode(область, "SK-0007")!!.doc.path("influence").asText(), "влияние пересчитано по новой роли")

        // Названное человеком не трогаем: истина — «инженер правит на месте».
        r.handle(
            "PATCH", "/v2/entities/${сторона.code}", п,
            """{"fields":{"influence":"informed"},"author":"инженер","reason":"решил инженер"}""",
        )
        r.handle(
            "PATCH", "/v2/entities/${сторона.code}", п,
            """{"fields":{"role":"customer"},"author":"инженер","reason":"на деле заказчик"}""",
        )
        assertEquals("informed", store.byCode(область, "SK-0007")!!.doc.path("influence").asText(), "решение человека держится")
    }

    @Test
    fun `поле вне схемы, служебный вид и смена кода — отказ словами, версия не плодится`() {
        val r = router()
        r.handle("POST", "/v2/stakeholders", п, """{"name":"ГКРЧ","role":"regulator","interest":"частоты","author":"Иванов И."}""", си)
        assertFailsWith<Exception>("поле вне схемы") {
            r.handle("PATCH", "/v2/entities/SK-0001", п, """{"fields":{"colour":"red"},"author":"Иванов И."}""", си)
        }
        assertEquals(1, store.byCode(область, "SK-0001")!!.version, "отказ схемы не плодит версию")
        val е = assertFailsWith<IllegalArgumentException> {
            r.handle("PATCH", "/v2/entities/SK-0001", п, """{"fields":{"code":"SK-0099"},"author":"Иванов И."}""", си)
        }
        assertTrue("код и вид стабильны" in е.message!!, е.message)
        val е2 = assertFailsWith<IllegalArgumentException> {
            r.handle("PATCH", "/v2/entities/$проект", п, """{"fields":{"name":"Другое имя"},"author":"Иванов И."}""", си)
        }
        assertTrue("на месте не правятся" in е2.message!!, е2.message)
    }

    @Test
    fun `правка берёт русское значение перечисления, чужое отбивает словами`() {
        // Журнал ПМИ-7, З-11: категория требования пришла ПО-РУССКИ, а сторожа
        // перечислений на записи не было вовсе. Истина 19.09 (`enum_labels`)
        // даёт русские значения — экран ими показывает, ими же и возвращает.
        val r = router()
        r.handle("POST", "/v2/stakeholders", п, """{"name":"Минтранс","role":"customer","interest":"телематика","author":"Иванов И."}""", си)

        r.handle(
            "PATCH", "/v2/entities/SK-0001", п,
            """{"fields":{"attitude":"поддерживает"},"author":"инженер","reason":"по русскому имени"}""",
            си,
        )
        assertEquals(
            "supports", store.byCode(область, "SK-0001")!!.doc.path("attitude").asText(),
            "русское имя значения ложится КОДОМ истины",
        )

        // Прежнее русское значение в записи лечится тем же PATCH, даже если
        // «новое» значение то же самое: приведение — правка, и она сохраняется.
        store.update(
            store.byCode(область, "SK-0001")!!.id,
            store.byCode(область, "SK-0001")!!.doc.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
                .put("attitude", "нейтрален"),
            Provenance(Channel.MANUAL, "прежний приём"),
        )
        val лечение = router().handle(
            "PATCH", "/v2/entities/SK-0001", п,
            """{"fields":{"attitude":"нейтрален"},"author":"инженер"}""", си,
        )
        assertEquals("neutral", store.byCode(область, "SK-0001")!!.doc.path("attitude").asText(), лечение?.body.toString())
        assertTrue(
            лечение!!.body.path("changed").asInt() > 0 &&
                "нейтрален" in лечение.body.path("note").asText() &&
                "neutral" in лечение.body.path("note").asText(),
            "приведение сохранено и названо словами: ${лечение.body}",
        )

        val чужое = assertFailsWith<IllegalArgumentException> {
            r.handle(
                "PATCH", "/v2/entities/SK-0001", п,
                """{"fields":{"attitude":"сочувствует"},"author":"инженер"}""", си,
            )
        }
        assertTrue(
            "в перечне истины нет" in чужое.message.orEmpty() && "поддерживает" in чужое.message.orEmpty(),
            "отказ обязан назвать перечень русскими словами: ${чужое.message}",
        )
    }

    @Test
    fun `вид для экрана несёт русские имена полей и значений`() {
        // Истина 19.09: «клиент показывает только label», «русские значения
        // перечислений — только из enum_labels». У экрана своих списков нет.
        val ответ = router().handle("GET", "/v2/kinds/requirement", emptyMap(), null)
        assertEquals(200, ответ?.code, ответ?.body.toString())
        val вид = assertNotNull(ответ?.body)
        assertEquals("Формулировка", вид.path("labels").path("statement").asText(), вид.toString())
        assertEquals("accept", вид.path("required_at").path("statement").asText(), вид.toString())
        assertEquals("характеристика", вид.path("enum_labels").path("category").path("performance").asText(), вид.toString())
        assertEquals("проектное", вид.path("enum_labels").path("level").path("project").asText(), вид.toString())
        assertEquals("черновик", вид.path("enum_labels").path("status").path("Draft").asText(), "статус тоже назван по-русски")
        assertTrue(вид.path("measures").any { it.asText() == "measure" }, "показатель — величина, а не строка: $вид")
        assertEquals("всегда", вид.path("enum_labels").path("ears_pattern").path("ubiquitous").asText(), вид.toString())

        // Вложенные перечисления (истина 20.09) идут тем же путём и со своими
        // метками: у экрана копии этих значений быть не может.
        val машина = router().handle("GET", "/v2/kinds/state_machine", emptyMap(), null)!!.body
        assertEquals(
            listOf("mode", "state"), машина.path("enums").path("states.kind").map { it.asText() },
            машина.toString(),
        )
        assertEquals("режим", машина.path("enum_labels").path("states.kind").path("mode").asText(), машина.toString())
        assertEquals(
            "таймер", машина.path("enum_labels").path("transitions.trigger").path("timer").asText(),
            машина.toString(),
        )
        // Примечание поля и операторы величины — тоже истина, а не догадка экрана.
        assertEquals(
            "обязателен для performance", вид.path("notes").path("measure").asText(),
            "примечание поля идёт на экран: $вид",
        )
        assertEquals("≥", вид.path("measure_ops").path(">=").asText(), "знак для экрана, код в записи: $вид")

        val нет = assertFailsWith<NoSuchElementException> {
            router().handle("GET", "/v2/kinds/nonexistent", emptyMap(), null)
        }
        assertTrue("истине схем" in нет.message.orEmpty(), нет.message)
    }


    @Test
    fun `величина правится парой — оператор, число и единица ложатся объектом`() {
        // Блокер владельца 19.09: «не могу ввести показатель — он не
        // сохраняется». Правка величины идёт объектом (истина схем:
        // `measure{op,value|min,max,unit→unit}`), оператор — КОДОМ («>=»), а не
        // типографским знаком, которого схема величины не знает.
        val r = router()
        r.handle("POST", "/v2/stakeholders", п, """{"name":"Роскосмос","role":"customer","interest":"группировка","author":"Иванов И."}""", си)

        val ответ = r.handle(
            "PATCH", "/v2/entities/SK-0001", п,
            """{"fields":{"scale":{"op":">=","value":150,"unit":"КА"}},"author":"инженер","reason":"масштаб стороны"}""",
            си,
        )
        assertEquals(1, ответ!!.body.path("changed").asInt(), ответ.body.toString())
        val величина = store.byCode(область, "SK-0001")!!.doc.path("scale")
        assertTrue(величина.isObject, "величина легла ПАРОЙ, а не строкой: $величина")
        assertEquals(">=", величина.path("op").asText(), величина.toString())
        assertEquals(150.0, величина.path("value").asDouble(), 0.001, величина.toString())
        assertEquals("КА", величина.path("unit").asText(), величина.toString())
    }

    @Test
    fun `единицы для выбора приходят из справочника, пустой справочник назван словами`() {
        // Владелец 19.09: «единиц измерения нет — блок». Перечня единиц у кода
        // нет: их ведёт справочник (полка LIB), и подставляет его граница.
        val сСправочником = V2Router(
            store, links,
            ProcessFactory.engine(
                template = { шаблон },
                evaluator = ReadinessFactory.gateEvaluator(store, links, scenesDone = { emptySet() }, gatesPassed = { emptySet() }),
                passedGates = { mutableSetOf() }, gatePlan = { emptyMap() },
            ),
            LibraryFactory.shelves(store) { шаблон },
            orbita.knowledge.api.KnowledgeFactory.intake(store, links, mapper),
            orbita.formulation.api.FormulationFactory.formulation(store, links), mapper,
            units = { mapOf("мин" to "мин · время", "%" to "% · доля и вероятность") },
        )
        val есть = сСправочником.handle("GET", "/v2/units", emptyMap(), null)
        assertEquals(200, есть?.code, есть?.body.toString())
        assertEquals(2, есть!!.body.path("count").asInt(), есть.body.toString())
        // Порядок — по коду единицы: список для выбора идёт предсказуемо.
        assertTrue(
            есть.body.path("items").any { it.path("code").asText() == "мин" && it.path("label").asText() == "мин · время" },
            есть.body.toString(),
        )

        // Без справочника экран не молчит пустым списком, а говорит почему.
        val пусто = router().handle("GET", "/v2/units", emptyMap(), null)
        assertEquals(0, пусто!!.body.path("count").asInt())
        assertTrue("справочник единиц" in пусто.body.path("why").asText(), пусто.body.toString())
    }

}
