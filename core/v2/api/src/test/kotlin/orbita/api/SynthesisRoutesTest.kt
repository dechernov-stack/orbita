// Маршруты синтеза постановки из поля (план «Знания v2», шаг 16).
//
// Проверяется не «маршрут ответил», а то, ради чего он заведён: запуск идёт
// фоном и доводится опросом, строка дифа несёт код карточки и ранг основания
// словами, дрейф поля считает СЕРВЕР, а принятие предложения идёт ЧЕРЕЗ сверку
// — узнанное принятое маршрут сам не заводит и незакрытую обязательную связь
// не пропускает.
//
// Отдельно проверен флаг: на проекте прохода ПМИ-5 (без knowledge_v2) каждый
// здешний адрес отказывает словами, а не работает молча.
//
// Сверка подменена: её собственное поведение проверяется своими тестами, а
// здесь важно, ЧТО маршрут ей отдаёт и что он делает с её ответом.
package orbita.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.ai.api.AiFactory
import orbita.ai.api.Answer
import orbita.ai.api.Transport
import orbita.api.internal.SceneRoutes
import orbita.api.internal.SynthesisRoutes
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.Provenance
import orbita.knowledge.api.MustLinkMissing
import orbita.knowledge.api.Action
import orbita.knowledge.api.Applied
import orbita.knowledge.api.Candidate
import orbita.knowledge.api.CandidateOrigin
import orbita.knowledge.api.Comparison
import orbita.knowledge.api.Disposition
import orbita.knowledge.api.FactSource
import orbita.knowledge.api.FieldDifference
import orbita.knowledge.api.Finding
import orbita.knowledge.api.KnowledgeFactory
import orbita.knowledge.api.Match
import orbita.knowledge.api.Question
import orbita.knowledge.api.Reconcile
import orbita.knowledge.api.ReconcileItem
import orbita.knowledge.api.ReconcileRun
import orbita.knowledge.api.Verdict
import orbita.knowledge.schema.GeneratedOntology
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SynthesisRoutesTest {

    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val intake = KnowledgeFactory.intake(store, links, mapper)
    private val транспорт = КаналСинтеза()
    private val служба = AiFactory.service(store, транспорт, mapper)
    private val сверка = СверкаПодмена()
    private val маршруты = SynthesisRoutes(
        store, AiFactory.synthesisJobs(store, intake, служба, mapper), сверка, mapper, links,
        // Шаблон EARS по форме формулировки — как в жизни его даёт модуль требований.
        ears = { формулировка -> if (формулировка.trimStart().startsWith("Когда")) "event" else "ubiquitous" },
        // Справочник единиц — как в жизни его даёт полка LIB: здесь фикстурой.
        units = { единица -> единица in setOf("%", "мин", "ч", "сут", "КА") },
    )
    private val п = mapOf("project" to ПРОЕКТ)

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
    }

    // --- мера ------------------------------------------------------------

    @Test
    fun `роль документа называется у лежащего материала, без перезагрузки`() {
        // Документ, загруженный до появления ролей, иначе нельзя прочитать
        // как устав: пришлось бы грузить его заново вторым экземпляром.
        проект(ПРОЕКТ, полеЗнаний = true)
        val материал = intake.putMaterial(
            ПРОЕКТ, "Записка заказчика", "mission_memo", "п. 1 Текст записки.", "Иванов И.",
            authority = "mandatory",
        )
        assertTrue(
            store.byCode(Area.Project(ПРОЕКТ), материал)!!.doc.path("role").asText("").isBlank(),
            "роли у прежнего материала нет",
        )

        val ответ = SceneRoutes(store, links, ДвижокНеНужен, mapper).handle(
            "PATCH", "/v2/entities/$материал", п,
            """{"fields":{"role":"charter"},"author":"инженер","reason":"роль названа"}""",
        )

        assertEquals(200, ответ?.code, ответ?.body.toString())
        assertEquals(
            "charter",
            store.byCode(Area.Project(ПРОЕКТ), материал)!!.doc.path("role").asText(),
            "роль легла в карточку документа",
        )
    }

    @Test
    fun `на приёме система подставляет по стадии — уровень, источник, шаблон EARS`() {
        // Журнал ПМИ-7, З-09: форма спрашивала всё подряд. Истина схем 18.09
        // (`required_at`) говорит, кто заполняет: код — система, источник — из
        // оснований, шаблон EARS — автоопределением, уровень — умолчанием.
        val факт = поле()
        транспорт.ответ = """{"proposals":[{"concept":"requirement","payload":{
            "title":"Латентность доставки",
            "statement":"Когда сообщение поставлено в очередь, система должна доставить его за 180 мин"},
            "basis":["$факт"],"verdict":"new"}]}"""
        val код = довестиЗапуск()

        val ответ = маршруты.handle(
            "POST", "/v2/synthesis/runs/$код/accept", п,
            """{"chosen":["${первоеПредложение(код)}"],"author":"инженер","reason":"сцена 8"}""",
        )

        assertEquals(201, ответ?.code, ответ?.body.toString())
        val содержимое = сверка.содержимоеКандидата
        assertTrue(содержимое != null, "кандидат доехал до сверки")
        assertEquals("project", содержимое!!.path("level").asText(""), "уровень — умолчание стадии: $содержимое")
        assertEquals("event", содержимое.path("ears_pattern").asText(""), "шаблон EARS определён по форме")
        assertEquals("material", содержимое.path("source")[0].path("kind").asText(""), "источник — из оснований")
        assertTrue(содержимое.path("source")[0].path("ref").asText().isNotBlank(), содержимое.toString())
        assertTrue(содержимое.path("code").asText("").isBlank(), "код даёт хранилище, а не приём")
    }

    @Test
    fun `показатель предлагается из формулировки, год мерой не становится`() {
        // Журнал ПМИ-7, З-12: «показатель „—", хотя в формулировке 100 % … к
        // 2033 году». Мера из текста идёт ПРЕДЛОЖЕНИЕМ (как шаблон EARS), год —
        // горизонт, а не показатель.
        val факт = поле()
        транспорт.ответ = """{"proposals":[{"concept":"requirement","payload":{
            "title":"Отслеживаемость",
            "statement":"Система должна обеспечивать отслеживаемость не менее 100 % объектов перечня к 2033 году"},
            "basis":["$факт"],"verdict":"new"}]}"""
        val код = довестиЗапуск()

        val ответ = маршруты.handle(
            "POST", "/v2/synthesis/runs/$код/accept", п,
            """{"chosen":["${первоеПредложение(код)}"],"author":"инженер","reason":"сцена 8"}""",
        )

        assertEquals(201, ответ?.code, ответ?.body.toString())
        val величина = сверка.содержимоеКандидата?.path("measure")
        assertTrue(величина != null && величина.isObject, "показатель предложен парой: ${сверка.содержимоеКандидата}")
        assertEquals(100.0, величина!!.path("value").asDouble(), 0.001, величина.toString())
        assertEquals("%", величина.path("unit").asText(), величина.toString())
        assertEquals(">=", величина.path("op").asText(), "оператор — код истины, а не знак: $величина")
    }

    @Test
    fun `единицу показателя узнаёт справочник, а не слово за числом`() {
        // «P95 не более 180 мин»: первое число — 95, а за ним стоит «не». Без
        // справочника единиц показателем стало бы «95 не» — мусор в записи.
        val факт = поле()
        транспорт.ответ = """{"proposals":[{"concept":"requirement","payload":{
            "title":"Латентность B′",
            "statement":"Система должна доставлять приоритетное сообщение класса B′ с P95 не более 180 мин"},
            "basis":["$факт"],"verdict":"new"}]}"""
        val код = довестиЗапуск()

        маршруты.handle(
            "POST", "/v2/synthesis/runs/$код/accept", п,
            """{"chosen":["${первоеПредложение(код)}"],"author":"инженер","reason":"сцена 8"}""",
        )

        val величина = сверка.содержимоеКандидата?.path("measure")
        assertTrue(величина != null && величина.isObject, "показатель предложен: ${сверка.содержимоеКандидата}")
        assertEquals(180.0, величина!!.path("value").asDouble(), 0.001, величина.toString())
        assertEquals("мин", величина.path("unit").asText(), величина.toString())
        assertEquals("<=", величина.path("op").asText(), "оператор — код истины, а не знак: $величина")
    }

    @Test
    fun `правка предложения до приёма едет в сверку, а поле вне истины отбивается`() {
        // Проход владельца 18.09: «во вкладке Постановка нет редактируемых
        // полей» — требование входило черновиком, но дополнить его при приёме
        // было нечем. Правка проверяется истиной схем, а не доверием.
        val факт = поле()
        транспорт.ответ = """{"proposals":[{"concept":"requirement","payload":{
            "title":"Отслеживаемость грузов",
            "statement":"Система должна обеспечивать отслеживаемость 100% объектов перечня"},
            "basis":["$факт"],"verdict":"new"}]}"""
        val код = довестиЗапуск()
        val предложение = первоеПредложение(код)

        val ответ = маршруты.handle(
            "POST", "/v2/synthesis/runs/$код/accept", п,
            """{"chosen":["$предложение"],"author":"инженер","reason":"сцена 8",
                "edits":{"$предложение":{"level":"project","category":"performance","priority":"высокий"}}}""",
        )

        assertEquals(201, ответ?.code, ответ?.body.toString())
        val содержимое = сверка.содержимоеКандидата
        assertEquals("project", содержимое?.path("level")?.asText(), "правка доехала до сверки: $содержимое")
        assertEquals("performance", содержимое?.path("category")?.asText())
        assertTrue("с правкой инженера" in сверка.причина, "причина называет правку: ${сверка.причина}")

        // Поле вне истины и значение вне перечня — отказ словами (маршрутизатор
        // отвечает на него 400; здесь маршрут вызывается напрямую).
        val чужое = assertFailsWith<IllegalArgumentException> {
            маршруты.handle(
                "POST", "/v2/synthesis/runs/$код/accept", п,
                """{"chosen":["$предложение"],"author":"инженер","edits":{"$предложение":{"выдумка":"да"}}}""",
            )
        }
        assertTrue("«выдумка»" in чужое.message.orEmpty() && "истина знает" in чужое.message.orEmpty(), чужое.message.orEmpty())

        val внеПеречня = assertFailsWith<IllegalArgumentException> {
            маршруты.handle(
                "POST", "/v2/synthesis/runs/$код/accept", п,
                """{"chosen":["$предложение"],"author":"инженер","edits":{"$предложение":{"category":"выдуманная"}}}""",
            )
        }
        assertTrue("перечень истины" in внеПеречня.message.orEmpty(), внеПеречня.message.orEmpty())
    }

    @Test
    fun `величина уезжает в сверку парой, а не строкой`() {
        // Порт предложения носит поля строками, а истина схем требует у цели
        // пару «значение · единица». Пока пара ехала строкой, сверка видела
        // величину без единицы и отбивала десять целей из десяти (16.09).
        val факт = поле()
        транспорт.ответ = """{"proposals":[{"concept":"need","payload":{
            "statement":"связь в Арктике вне наземного покрытия","stakeholder":"Минтранс России",
            "qos_class":{"value":"180","unit":"мин"}},
            "basis":["$факт"],"verdict":"new"}]}"""
        val код = довестиЗапуск()

        маршруты.handle(
            "POST", "/v2/synthesis/runs/$код/accept", п,
            """{"chosen":["${первоеПредложение(код)}"],"author":"инженер"}""",
        )

        val величина = сверка.кандидаты.last().payload.path("qos_class")
        assertTrue(величина.isObject, "величина пришла объектом, а не строкой: $величина")
        assertEquals("мин", величина.path("unit").asText())
    }

    @Test
    fun `отмена пакета снимает заведённое с учёта, а факты оставляет`() {
        // 91 предложение принимается одним нажатием — значит, и возвращается
        // одним. Иначе человек не решится нажать «принять всё».
        val факт = поле()
        val код = довестиЗапуск()
        // Сверка-подмена отвечает кодом заведённого, но сущностей не создаёт:
        // заводим её сами — отмена обязана снять именно ЭТУ запись.
        store.create(
            "N-0001", "need", Area.Project(ПРОЕКТ), "3",
            mapper.createObjectNode().put("statement", "связь в Арктике"),
            Provenance(Channel.SERVICE, "инженер"),
        )
        маршруты.handle(
            "POST", "/v2/synthesis/runs/$код/accept", п,
            """{"chosen":["${первоеПредложение(код)}"],"author":"инженер"}""",
        )

        val ответ = маршруты.handle("POST", "/v2/synthesis/runs/$код/undo", п, """{"author":"инженер"}""")

        assertEquals(200, ответ?.code, ответ?.body.toString())
        assertEquals(1, ответ?.body?.path("undone")?.asInt(), ответ?.body.toString())
        assertEquals(
            "cancelled",
            store.byCode(Area.Project(ПРОЕКТ), "N-0001")?.status,
            "заведённое снято с учёта",
        )
        assertTrue(
            "факты-основания остались" in (ответ?.body?.path("note")?.asText() ?: ""),
            "человеку сказано, что осталось: ${ответ?.body?.path("note")?.asText()}",
        )
        assertEquals(
            "fact",
            store.byCode(Area.Project(ПРОЕКТ), факт)?.kind,
            "факт-основание на месте: он след документа, а не решение человека",
        )
    }

    @Test
    fun `отмена без принятого пакета отказывает словами`() {
        поле()
        val код = довестиЗапуск()

        val ответ = маршруты.handle("POST", "/v2/synthesis/runs/$код/undo", п, """{"author":"инженер"}""")

        assertEquals(409, ответ?.code)
        assertTrue(
            "отменять нечего" in (ответ?.body?.path("error")?.asText() ?: ""),
            ответ?.body.toString(),
        )
    }

    /** Запуск, доведённый до дифа: дальше по нему принимают и отменяют. */
    private fun довестиЗапуск(): String {
        val старт = маршруты.handle("POST", "/v2/synthesis/runs", п, """{"trigger":"manual","author":"инженер"}""")!!
        val код = старт.body.path("id").asText()
        довести(код)
        return код
    }

    private fun первоеПредложение(код: String): String =
        маршруты.handle("GET", "/v2/synthesis/runs/$код", п, null)!!
            .body.path("diff").path("new").first().path("proposal").asText()

    @Test
    fun `запуск доводится опросом до дифа с кодом предложения и рангом основания`() {
        val факт = поле()
        val старт = маршруты.handle("POST", "/v2/synthesis/runs", п, """{"trigger":"manual","author":"Иванов И."}""")!!
        assertEquals(202, старт.code, старт.body.toString())
        val код = старт.body.path("id").asText()
        assertTrue(код.startsWith("SR-"), "запуск получает свой код: $код")
        assertTrue(старт.body.path("status").asText() in ИДЁТ, старт.body.toString())

        val готово = довести(код)
        assertEquals("done", готово.path("status").asText(), готово.toString())
        assertEquals(1, готово.path("counts").path("new").asInt(), "счётчики групп считает сервер")
        val предложение = готово.path("diff").path("new").single()
        assertTrue(
            предложение.path("proposal").asText().startsWith("PR-"),
            "строка дифа несёт код карточки — по нему человек и выбирает: $предложение",
        )
        assertEquals("need", предложение.path("concept").asText())
        val основание = предложение.path("basis").single()
        assertEquals(факт, основание.path("fact").asText(), "у предложения есть факт-основание")
        assertEquals("mandatory", основание.path("authority").asText())
        assertEquals("обязательный", основание.path("authority_word").asText(), "ранг называется словами")
    }

    @Test
    fun `дрейф поля виден до синтеза и гаснет после него`() {
        поле()
        val до = маршруты.handle("GET", "/v2/synthesis/pending", п, null)!!
        assertTrue(до.body.path("changed").asInt() > 0, "«поле изменилось: N» считает сервер: ${до.body}")
        assertEquals("", до.body.path("since").asText(), "синтеза на проекте ещё не было")

        val код = маршруты.handle("POST", "/v2/synthesis/runs", п, """{"author":"Иванов И."}""")!!
            .body.path("id").asText()
        довести(код)

        val после = маршруты.handle("GET", "/v2/synthesis/pending", п, null)!!
        assertEquals(0, после.body.path("changed").asInt(), "после синтеза дрейфа нет")
        assertEquals(код, после.body.path("since").asText())
    }

    @Test
    fun `список запусков идёт без дифа, а диф отдаётся последним доведённым`() {
        поле()
        val код = маршруты.handle("POST", "/v2/synthesis/runs", п, """{"author":"Иванов И."}""")!!
            .body.path("id").asText()
        довести(код)

        val строка = маршруты.handle("GET", "/v2/synthesis/runs", п, null)!!.body.path("items").single()
        assertEquals(код, строка.path("id").asText())
        assertEquals(1, строка.path("counts").path("new").asInt(), "счёт в журнале есть")
        assertTrue(строка.path("diff").isMissingNode, "журнал запусков не тянет всю постановку проекта")

        val диф = маршруты.handle("GET", "/v2/synthesis/diff", п, null)!!
        assertEquals(код, диф.body.path("id").asText())
        assertEquals(1, диф.body.path("diff").path("new").size())
    }

    @Test
    fun `правила образования отдаются версией и понятиями онтологии`() {
        проект(ПРОЕКТ, полеЗнаний = true)
        val ответ = маршруты.handle("GET", "/v2/ontology/formation", п, null)!!
        assertEquals(200, ответ.code)
        assertEquals(GeneratedOntology.ontologyVersion, ответ.body.path("version").asText())
        val нужда = ответ.body.path("concepts").single { it.path("code").asText() == "need" }
        assertTrue(
            нужда.path("must_link").any { it.asText() == "owns→stakeholder" },
            "без обязательной связи понятие не принимается — экран обязан это знать: $нужда",
        )
        assertEquals(0.8, нужда.path("identity").path("threshold").asDouble(), 1e-9)
    }

    @Test
    fun `акцепт идёт через сверку и заводит только новое`() {
        val предложение = доПредложения()
        val принято = маршруты.handle(
            "POST", "/v2/synthesis/runs/${запуск()}/accept", п,
            """{"chosen":["$предложение"],"author":"Иванов И.","role":"ведущий системный инженер"}""",
        )!!
        assertEquals(201, принято.code, принято.body.toString())
        assertEquals(1, принято.body.path("accepted").asInt())
        assertEquals(listOf("N-0001"), принято.body.path("created").map { it.asText() })

        val кандидат = сверка.кандидаты.single()
        assertEquals(предложение, кандидат.localId, "кандидат идёт под кодом карточки предложения")
        assertEquals(CandidateOrigin.SYNTHESIS, кандидат.origin, "происхождение — предложение синтеза")
        assertEquals("need", кандидат.concept)
        assertTrue(сверка.решения.single().startsWith("$предложение|ACCEPT_NEW|"), сверка.решения.toString())
    }

    // --- отказные ---------------------------------------------------------

    @Test
    fun `узнанное принятое акцептом не заводится — решает человек`() {
        сверка.вердикт = Verdict.AUGMENT
        val предложение = доПредложения()
        val ответ = маршруты.handle(
            "POST", "/v2/synthesis/runs/${запуск()}/accept", п,
            """{"chosen":["$предложение"],"author":"Иванов И."}""",
        )!!
        assertEquals(201, ответ.code, ответ.body.toString())
        assertEquals(0, ответ.body.path("accepted").asInt(), "дубль сам собой в модель не попадает")
        assertEquals(0, ответ.body.path("created").size(), "ни одной заведённой сущности")
        assertEquals(предложение, ответ.body.path("pending").single().path("proposal").asText())
        assertEquals("дополнить", ответ.body.path("pending").single().path("verdict").asText())
        assertTrue(сверка.решения.isEmpty(), "ни одного решения без клика человека")
    }

    @Test
    fun `незакрытая связь оставляет строку ждущей, а пакет идёт дальше`() {
        // Нехватка связи считается ДО заведения, то есть против проекта, каким
        // он был до пакета: нужда, чья сторона предложена ЭТИМ ЖЕ пакетом,
        // выглядит незакрытой. Отбивать из-за неё весь пакет нельзя — одна
        // такая строка уводила в отказ тридцать годных (прогон владельца 15.09).
        сверка.блокирует = "owns→stakeholder"
        val предложение = доПредложения()
        val ответ = маршруты.handle(
            "POST", "/v2/synthesis/runs/${запуск()}/accept", п,
            """{"chosen":["$предложение"],"author":"Иванов И."}""",
        )!!

        assertEquals(201, ответ.code, ответ.body.toString())
        assertEquals(0, ответ.body.path("accepted").asInt(), "сущности из незакрытой строки не завелось")
        val ждут = ответ.body.path("pending")
        assertEquals(1, ждут.size(), "строка названа ждущей: $ждут")
        assertEquals(предложение, ждут.first().path("proposal").asText())
        assertTrue(
            ждут.first().path("why").asText().contains("owns→stakeholder"),
            "сказано, какая связь не закрыта: ${ждут.first()}",
        )
    }

    @Test
    fun `акцепт без выбора и с чужим кодом предложения отказывает`() {
        доПредложения()
        val код = запуск()
        val пусто = assertFailsWith<IllegalArgumentException> {
            маршруты.handle("POST", "/v2/synthesis/runs/$код/accept", п, """{"chosen":[],"author":"Иванов И."}""")
        }
        assertTrue("не выбрано" in пусто.message!!, пусто.message!!)
        val чужой = assertFailsWith<IllegalArgumentException> {
            маршруты.handle("POST", "/v2/synthesis/runs/$код/accept", п, """{"chosen":["PR-9999"]}""")
        }
        assertTrue("обновите диф" in чужой.message!!, чужой.message!!)
        assertTrue(сверка.кандидаты.isEmpty(), "до сверки дело не дошло — кандидатов не заводилось")
    }

    @Test
    fun `на проекте без поля знаний каждый адрес отказывает словами`() {
        проект(ПРОХОД, полеЗнаний = false)
        val прохода = mapOf("project" to ПРОХОД)
        АДРЕСА.forEach { (метод, путь, тело) ->
            val ответ = маршруты.handle(метод, путь, прохода, тело)!!
            assertEquals(409, ответ.code, "$метод $путь")
            assertEquals("синтез выключен на этом проекте", ответ.body.path("error").asText(), "$метод $путь")
            assertTrue(ответ.body.path("what_to_do").asText().isNotBlank(), "отказ говорит, что делать")
        }
        assertTrue(
            store.list(Area.Project(ПРОХОД), "synthesis_run").isEmpty(),
            "на проекте прохода ни одного запуска не заводится",
        )
        assertTrue(транспорт.промпты.isEmpty(), "и ни одного живого вызова")
    }

    @Test
    fun `чужой путь не мой, свой без проекта и чужой код запуска отказывают`() {
        проект(ПРОЕКТ, полеЗнаний = true)
        assertNull(маршруты.handle("GET", "/v2/facts", п, null), "чужой адрес — не мой маршрут")
        assertNull(маршруты.handle("DELETE", "/v2/synthesis/runs", п, null), "удаления запусков не бывает")
        assertFailsWith<IllegalArgumentException> {
            маршруты.handle("GET", "/v2/synthesis/pending", emptyMap(), null)
        }
        assertFailsWith<NoSuchElementException> {
            маршруты.handle("GET", "/v2/synthesis/runs/SR-9999", п, null)
        }
    }

    @Test
    fun `диф до первого синтеза говорит словами, а не пустотой`() {
        проект(ПРОЕКТ, полеЗнаний = true)
        val ответ = маршруты.handle("GET", "/v2/synthesis/diff", п, null)!!
        assertEquals(200, ответ.code)
        assertEquals("", ответ.body.path("run").asText())
        assertTrue("синтеза" in ответ.body.path("note").asText(), ответ.body.toString())
    }

    // --- поле проекта -----------------------------------------------------

    private fun проект(код: String, полеЗнаний: Boolean) {
        val документ = mapper.createObjectNode().put("name", "Знания v2")
        if (полеЗнаний) документ.put("knowledge_v2", true)
        store.create(код, "project", Area.Project(код), "1", документ, Provenance(Channel.MANUAL, "Иванов И."))
    }

    /** Поле с одним принятым фактом обязательного документа: срезу есть из чего синтезировать. */
    private fun поле(): String {
        проект(ПРОЕКТ, полеЗнаний = true)
        val материал = intake.putMaterial(
            ПРОЕКТ, "Записка заказчика", "mission_memo",
            "п. 1 Минтранс России нуждается в связи в Арктике вне наземного покрытия.",
            "Иванов И.", authority = "mandatory",
        )
        val якорь = intake.canon(ПРОЕКТ, материал).first().anchor
        val итог = intake.putFacts(
            ПРОЕКТ, материал,
            """{"topics":[],"actions":[],"facts":[{"kind":"framing","subject":"Минтранс России",
               "predicate":"нуждается в","value":"связь в Арктике вне наземного покрытия",
               "source":{"anchor":"$якорь"},"source_mark":"И"}]}""",
            "Иванов И.",
        )
        val факт = итог.accepted.single().id
        intake.dispose(ПРОЕКТ, факт, Disposition.ADOPTED, "принято инженером", "Иванов И.")
        транспорт.ответ = """{"proposals":[{"concept":"need","payload":{
            "statement":"связь в Арктике вне наземного покрытия","stakeholder":"Минтранс России"},
            "basis":["$факт"],"verdict":"new"}]}"""
        return факт
    }

    /** Поле, запуск и доведение его опросом: возвращает код карточки предложения. */
    private fun доПредложения(): String {
        поле()
        маршруты.handle("POST", "/v2/synthesis/runs", п, """{"author":"Иванов И."}""")
        return довести(запуск()).path("diff").path("new").single().path("proposal").asText()
    }

    private fun запуск(): String =
        маршруты.handle("GET", "/v2/synthesis/runs", п, null)!!.body.path("items").last().path("id").asText()

    /** Опрос до готовности: ответ службы применяется на потоке запросов (ADR-069). */
    private fun довести(код: String): JsonNode {
        var тело = маршруты.handle("GET", "/v2/synthesis/runs/$код", п, null)!!.body
        var попыток = 0
        while (тело.path("status").asText() in ИДЁТ && попыток < 100) {
            Thread.sleep(50)
            тело = маршруты.handle("GET", "/v2/synthesis/runs/$код", п, null)!!.body
            попыток += 1
        }
        return тело
    }

    private companion object {
        const val ПРОЕКТ = "PJ-9620"
        const val ПРОХОД = "PJ-9621"

        val ИДЁТ: Set<String> = setOf("queued", "running")

        /** Все адреса файла: флаг закрывает каждый, а не только запуск. */
        val АДРЕСА: List<Triple<String, String, String?>> = listOf(
            Triple("POST", "/v2/synthesis/runs", "{}"),
            Triple("GET", "/v2/synthesis/runs", null),
            Triple("GET", "/v2/synthesis/runs/SR-0001", null),
            Triple("GET", "/v2/synthesis/diff", null),
            Triple("GET", "/v2/synthesis/pending", null),
            Triple("GET", "/v2/ontology/formation", null),
            Triple("POST", "/v2/synthesis/runs/SR-0001/accept", """{"chosen":["PR-0001"]}"""),
        )
    }
}

/** Канал-подмена: сеть в тесте не нужна, а число вызовов видно. */
private class КаналСинтеза : Transport {

    var ответ: String = """{"proposals":[]}"""
    val промпты = mutableListOf<String>()

    override fun ask(prompt: String, model: String?, maxTokens: Int?, schema: JsonNode?): Answer {
        промпты += prompt
        return Answer(text = ответ, model = "модель-теста", tokensIn = 100, tokensOut = 200)
    }
}

/**
 * Сверка-подмена: её собственные правила проверяются своими тестами, а здесь
 * важно, что маршрут отдаёт ей кандидатов происхождения «синтез» и уважает её
 * ответ — заводит только «новое» и не трогает ничего при блокирующей нехватке.
 */
private class СверкаПодмена : Reconcile {

    var вердикт: Verdict = Verdict.NEW
    var блокирует: String? = null
    val кандидаты = mutableListOf<Candidate>()
    val решения = mutableListOf<String>()

    /** Содержимое последнего кандидата и причина решения — ими видно правку инженера. */
    val содержимоеКандидата: com.fasterxml.jackson.databind.JsonNode? get() = кандидаты.lastOrNull()?.payload
    var причина: String = ""

    override fun preview(
        project: String,
        candidates: List<Candidate>,
        author: String,
        role: String,
        semantic: Boolean,
    ): ReconcileRun {
        кандидаты += candidates
        return ReconcileRun(
            id = "SR-0100", status = "done", sliceFingerprint = "срез", ontologyVersion = "правила",
            aiCalled = false, open = true,
            items = candidates.map { кандидат ->
                ReconcileItem(
                    localId = кандидат.localId, concept = кандидат.concept, candidateFact = "F-0900",
                    authority = "expert",
                    source = FactSource.FromExpert("Иванов И.", "ведущий системный инженер", "2026-09-12"),
                    verdict = вердикт, findings = находки(), blocking = listOfNotNull(блокирует),
                )
            },
        )
    }

    override fun run(project: String, run: String): ReconcileRun = error("маршрут синтеза сверку по коду не читает")

    override fun open(project: String): List<ReconcileRun> = emptyList()

    override fun apply(
        project: String,
        run: String,
        localId: String,
        finding: Int,
        action: Action,
        target: String?,
        reason: String,
        author: String,
    ): Applied {
        причина = reason
        решения += "$localId|${action.name}|$reason"
        // Живая сверка отказывает заведению, пока обязательная связь не
        // закрыта, — подмена обязана вести себя так же, иначе тест охранял бы
        // поведение, которого у изделия нет.
        блокирует?.let { throw MustLinkMissing(it, "связь «$it» не закрыта: сущности не будет") }
        return Applied(listOf("N-0001"), emptyList(), listOf("derived_from_fact"), listOf("F-0900"), "заведено")
    }

    private fun находки(): List<Finding> {
        val дубль = if (вердикт == Verdict.NEW) {
            Finding(
                question = Question.DUPLICATE, verdict = Verdict.NEW, match = Match.KEY,
                comparedFields = listOf("stakeholder", "statement_core"),
                offers = listOf(Action.ACCEPT_NEW, Action.DISMISS),
            )
        } else {
            Finding(
                question = Question.DUPLICATE, verdict = вердикт, target = "N-0007", match = Match.KEY,
                comparedFields = listOf("statement"),
                difference = FieldDifference(Comparison.DIFFERS, "statement", "моё", "принятое"),
                offers = listOf(Action.MERGE_INTO, Action.ACCEPT_NEW, Action.DISMISS),
            )
        }
        val нехватка = блокирует?.let {
            Finding(
                question = Question.GAP, verdict = Verdict.NEW, missing = it, blocking = true,
                offers = listOf(Action.LINK_BASIS, Action.DISMISS),
            )
        }
        return listOfNotNull(дубль, нехватка)
    }
}

/**
 * Движок процесса правке записи не нужен: маршрут сцен трогает его только
 * при открытии фазы и прохождении точек. Заглушка отказывает громко — если
 * правка вдруг пойдёт через движок, тест это увидит, а не пропустит.
 */
private object ДвижокНеНужен : orbita.process.api.ProcessEngine {
    override fun openPhase(project: String, templateCode: String) = error("движок правке не нужен")
    override fun view(project: String) = error("движок правке не нужен")
    override fun passGate(project: String, gate: String, decidedBy: String) = error("движок правке не нужен")
    override fun decide(
        project: String,
        gate: String,
        by: String,
        roles: Set<String>,
        outcome: String,
        note: String?,
    ) = error("движок правке не нужен")
}
