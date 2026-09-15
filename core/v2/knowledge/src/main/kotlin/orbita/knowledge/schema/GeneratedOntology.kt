// СГЕНЕРИРОВАНО tools/v2/gen_ontology.py из docs/tz/v2/ОНТОЛОГИЯ-ФОРМИРОВАНИЯ.yaml — руками не править
//
// Правила образования понятий постановки: чем понятие узнаётся среди
// принятых (identity), что считается противоречием (conflict_on) и без какой
// связи его нельзя принять (must_link). Понятие, которого здесь нет, не
// образуется: придумывать понятия в коде запрещено (ЗАДАНИЕ-ЗНАНИЯ-V2).
package orbita.knowledge.schema

/**
 * Чем понятие узнаётся среди уже принятых.
 *
 * @property key ключ дубля — ступень 1 сверки, без токенов
 * @property semantic что сравнивает вторая ступень, когда ключ не совпал
 * @property threshold порог близости смысла; ниже порога — не дубль
 */
data class ConceptIdentity(
    val key: List<String>,
    val semantic: String,
    val threshold: Double,
)

/**
 * Отбор фактов, из которых образуется понятие.
 *
 * Пустое поле означает «не ограничиваем», а не «пусто»: правило отбирает
 * факты по тем признакам, которые названы.
 */
data class FactRule(
    val kind: String? = null,
    val predicateIn: List<String> = emptyList(),
    val subject: String? = null,
    val subjectIs: String? = null,
    /** Субъект, которым факт быть НЕ должен: «stakeholder проекта» у возможности. */
    val subjectNot: String? = null,
    val mark: String? = null,
    /** Признак, который факт обязан нести: horizon/year, date, designation. */
    val with: String? = null,
    val sectionHint: String? = null,
)

/**
 * Понятие постановки.
 *
 * @property fields поле понятия → откуда оно берётся из фактов
 * @property mustLink связи, без которых понятие не принимается (нехватка)
 * @property conflictOn поля, различие в которых — противоречие, а не дополнение
 */
data class Concept(
    val code: String,
    val fromFacts: List<FactRule>,
    val fields: Map<String, String>,
    val mustLink: List<String>,
    val conflictOn: List<String>,
    /**
     * Чем понятие узнаётся среди принятых. Пусто — ключа в истине ещё нет:
     * ворота 6 («дубль по ключу идентичности») по такому понятию не
     * проходят, и сверка отказывает поимённо.
     */
    val identity: ConceptIdentity? = null,
    val note: String? = null,
    /** Вид сущности, которым понятие становится при акцепте, словами истины. */
    val targetKind: String? = null,
    /**
     * Примеры формулировок понятия для инструкции модели.
     *
     * НЕ фильтр. С РЕШЕНИЕ-ЧИТАТЬ-СМЫСЛ (15.09) предикат воротами не служит:
     * предложение не отбивается из-за того, что нужного слова не нашлось в
     * списке. Ворота проверяют только машинно проверяемое — цитату, якорь,
     * число с единицей, разрешимость ссылок, обязательные поля и дубль.
     */
    val predicateHints: List<String> = emptyList(),
    /** Из чего понятие НЕ образуется — анти-примеры промпта. */
    val notFrom: List<String> = emptyList(),
    /** Роли документов, из фактов которых понятие вообще образуется. */
    val allowedRoles: List<String> = emptyList(),
    /** Поля, которые считает система, а не модель: в промпт не печатаются. */
    val computedFields: Map<String, String> = emptyMap(),
    /** Что вправе документ чужой роли, когда создать понятие он не может. */
    val changeByOthers: String? = null,
    /** Помета истины: подсказки — не фильтр. Идёт в промпт рядом с примерами. */
    val predicateHintsNote: String? = null,
    /** Помета истины: `fromFacts` — подсказка происхождения, а не ворота. */
    val fromFactsNote: String? = null,
) {

    /**
     * Код вида из [targetKind]: «milestone (L1); вехи технологий — gate» → milestone.
     * Пусто — понятие вида не имеет и ложится на факт (допущение).
     */
    val targetKindCode: String? =
        targetKind?.takeWhile { it.isLetterOrDigit() || it == '_' }?.ifBlank { null }

    /**
     * Ключ идентичности или отказ поимённо. Ворота 6 истины — «дубль по ключу
     * идентичности» — без ключа не проходят, и молча пропустить это нельзя:
     * сверка без ключа сливала бы разное или плодила бы одинаковое.
     */
    /**
     * Понятие не заводит сущность, а ПОМЕЧАЕТ факт-основание.
     *
     * Истина 15.09 о допущении: «target_kind: fact (disposition=assumed) —
     * отдельного вида нет», «предложение синтеза для допущения = пометить факт
     * как допущение; реестр допущений — проекция фактов с диспозицией assumed».
     * Такое понятие не попадает в перечень принятых сущностей: иначе срез
     * синтеза считал бы КАЖДЫЙ факт поля принятым понятием.
     */
    val marksFact: Boolean get() = targetKindCode == "fact"

    val identityOrFail: ConceptIdentity
        get() = identity ?: error(
            "у понятия «$code» нет ключа идентичности в ОНТОЛОГИЯ-ФОРМИРОВАНИЯ.yaml: " +
                "дубль сверять нечем — допишите identity владельцу файла истины",
        )
}

object GeneratedOntology {

    /**
     * Отпечаток истины онтологии (sha256 файла). Им помечается каждый запуск
     * синтеза: по нему видно, по каким правилам сделано предложение.
     */
    const val ontologyVersion: String = "877f03f1e2cc67f97cc8a316d9a9ec7499e8a7be342f4423079337681de9b698"

    /** Ранги доверия по убыванию веса — ранг подсказывает, решает человек. */
    val authorityRanks: List<String> = listOf("mandatory", "expert", "reference", "doubtful")

    /** Что значит ранг — словами, для подсказки в противоречии. */
    val authorityNotes: Map<String, String> = mapOf(
        "mandatory" to "заказчик · регулятор · директива — вес нормы; конфликт по умолчанию за ним",
        "expert" to "ручной ввод эксперта проекта: автор · роль · дата; выше справки, ниже документа заказчика; без якоря",
        "reference" to "аналитика · наследие · даташит — свидетельство",
        "doubtful" to "непроверенный — принимается только явно",
    )

    val concepts: List<Concept> = listOf(
        Concept(
            code = "stakeholder",
            fromFacts = listOf(
                FactRule(kind = "relation", predicateIn = listOf("является заказчиком", "является оператором", "регулирует", "поставляет", "учреждается", "потребляет")),
                FactRule(kind = "capability", subject = "организация"),
            ),
            fields = mapOf(
                "name" to "subject",
                "role" to "по предикату",
                "interest" to "из facts kind=assessment|framing о subject",
                "scale" to "quantity о subject",
            ),
            mustLink = emptyList(),
            conflictOn = listOf("role"),
            identity = ConceptIdentity(
                key = listOf("name_normalized|synonyms"),
                semantic = "имя организации/группы, роль",
                threshold = 0.85,
            ),
            note = "сторона без нужды — помета к воротам, не отказ",
            predicateHints = listOf("является заказчиком", "является оператором", "регулирует", "поставляет", "эксплуатирует", "потребляет", "учреждается"),
            notFrom = listOf("автор или подписант нормативного акта без предиката роли к проекту", "ведомство как издатель", "сама проектируемая система", "регион, порт, коридор — это объекты применения"),
            allowedRoles = listOf("charter", "tor", "context", "supplier", "heritage"),
            computedFields = mapOf(
                "influence" to "вычисляется системой из предиката роли (заказчик/регулятор → decides; оператор/партнёр → influences; потребитель → informed) — модель это поле не заполняет",
                "power" to "оценка человека 1–5 на сцене 3; по умолчанию пусто, матрица «влияние × сила» заполняется рукой — это её назначение",
            ),
            predicateHintsNote = "примеры формулировок для инструкции; НЕ фильтр — предложение не отбивается из-за отсутствия предиката в списке",
            fromFactsNote = "подсказка, откуда понятие обычно берётся; ворота на вид факта не ставятся",
        ),
        Concept(
            code = "need",
            fromFacts = listOf(
                FactRule(kind = "framing", predicateIn = listOf("нуждается в", "требует", "не хватает")),
                FactRule(kind = "assessment", subjectIs = "stakeholder", mark = "П"),
                FactRule(sectionHint = "§2.1 общие нужды → привязка к сторонам по субъекту/смыслу"),
            ),
            fields = mapOf(
                "statement" to "предикат+объект",
                "stakeholder" to "subject (owns) — обязательно",
                "qos_class" to "из фактов о задержке/гарантии; иначе TBR{owner,gate=сцена 6}",
            ),
            mustLink = listOf("owns→stakeholder"),
            conflictOn = listOf("statement contradicts"),
            identity = ConceptIdentity(
                key = listOf("stakeholder", "statement_core"),
                semantic = "та же потребность иными словами; отличие — охват, число, класс",
                threshold = 0.8,
            ),
            note = "интерес стороны → нужда [П], не [И]",
            predicateHints = listOf("нуждается в", "требует", "не хватает", "вынужден"),
            notFrom = listOf("роль стороны (поле role)", "описание программы или акта", "свойство системы (цель или сервис)"),
            allowedRoles = listOf("charter", "tor", "context"),
            predicateHintsNote = "примеры формулировок для инструкции; НЕ фильтр — предложение не отбивается из-за отсутствия предиката в списке",
            fromFactsNote = "подсказка, откуда понятие обычно берётся; ворота на вид факта не ставятся",
        ),
        Concept(
            code = "goal",
            fromFacts = listOf(
                FactRule(kind = "quantity", with = "horizon/year"),
                FactRule(kind = "framing", predicateIn = listOf("к году", "достичь", "развернуть")),
            ),
            fields = mapOf(
                "statement" to "предикат",
                "measure" to "quantity с единицей — обязательно",
                "year" to "horizon — обязательно",
                "needs" to "covers ≥1 по субъекту/теме",
            ),
            mustLink = listOf("covers→need>=1"),
            conflictOn = listOf("measure", "year"),
            identity = ConceptIdentity(
                key = listOf("statement_core", "measure.key"),
                semantic = "тот же результат; отличия — значение, год, единица (нормализовать)",
                threshold = 0.8,
            ),
            note = "цели проекта меняются только решением эксперта; всякий документ может предложить изменение с основанием и рангом — не создать цель сам",
            predicateHints = listOf("достичь", "развернуть", "к году"),
            notFrom = listOf("обязательство норматива (constraint)", "описание услуги (service)"),
            allowedRoles = listOf("charter"),
            changeByOthers = "только предложение augment/contradict с обоснованием; решает человек",
            predicateHintsNote = "примеры формулировок для инструкции; НЕ фильтр — предложение не отбивается из-за отсутствия предиката в списке",
            fromFactsNote = "подсказка, откуда понятие обычно берётся; ворота на вид факта не ставятся",
        ),
        Concept(
            code = "service",
            fromFacts = listOf(
                FactRule(kind = "capability", subject = "система"),
                FactRule(sectionHint = "§4 сервисы"),
            ),
            fields = mapOf(
                "name" to "capability",
                "needs" to "covers ≥1",
                "qos_class" to "из фактов о классе/задержке",
                "target_measure" to "quantity",
            ),
            mustLink = listOf("covers→need>=1", "qos_class"),
            conflictOn = listOf("qos_class", "target_measure"),
            identity = ConceptIdentity(
                key = listOf("name_core"),
                semantic = "та же услуга тем же потребителям",
                threshold = 0.8,
            ),
            notFrom = listOf("сервис без нужды не предлагается"),
            allowedRoles = listOf("charter"),
            changeByOthers = "только предложение augment/contradict с обоснованием; решает человек",
            fromFactsNote = "подсказка, откуда понятие обычно берётся; ворота на вид факта не ставятся",
        ),
        Concept(
            code = "constraint",
            fromFacts = listOf(
                FactRule(kind = "obligation"),
                FactRule(kind = "framing", predicateIn = listOf("только", "не рассматривается", "исключается", "не более", "не менее")),
            ),
            fields = mapOf(
                "code" to "Р-N выдаётся при акцепте",
                "type" to "по источнику: obligation→regulatory; framing→technical|programmatic|launch|financial",
                "bound" to "из quantity в факте, если есть",
            ),
            mustLink = listOf("normative_basis if obligation"),
            conflictOn = listOf("bound", "statement"),
            identity = ConceptIdentity(
                key = listOf("bound.key|statement_core"),
                semantic = "та же рамка; отличие — граница или тип",
                threshold = 0.85,
            ),
            note = "ограничение из справочного документа — предложение [П], из обязательного — [И]",
            predicateHints = listOf("только", "не рассматривается", "исключается", "не более", "не менее"),
            notFrom = listOf("желание стороны (need)", "целевой показатель (goal)"),
            allowedRoles = listOf("charter", "tor", "regulatory"),
            predicateHintsNote = "примеры формулировок для инструкции; НЕ фильтр — предложение не отбивается из-за отсутствия предиката в списке",
            fromFactsNote = "подсказка, откуда понятие обычно берётся; ворота на вид факта не ставятся",
        ),
        Concept(
            code = "assumption",
            fromFacts = listOf(
                FactRule(mark = "П"),
                FactRule(kind = "assumption"),
            ),
            fields = mapOf(
                "disposition" to "assumed — ставится при приёме предложения",
                "assumption.owner" to "человек, при акцепте",
                "assumption.confirm_by" to "точка, при акцепте",
                "assumption.validation" to "чем подтвердится, при акцепте",
                "statement" to "остаётся текстом факта",
            ),
            mustLink = emptyList(),
            conflictOn = emptyList(),
            identity = ConceptIdentity(
                key = listOf("statement_core"),
                semantic = "то же допущение",
                threshold = 0.8,
            ),
            note = "предложение синтеза для допущения = «пометить факт как допущение»; владельца, точку и способ подтверждения ставит человек — модель их не предлагает; реестр допущений — проекция фактов с диспозицией assumed",
            targetKind = "fact (disposition=assumed) — отдельного вида нет",
            allowedRoles = listOf("charter", "tor", "context", "supplier", "heritage"),
            fromFactsNote = "подсказка, откуда понятие обычно берётся; ворота на вид факта не ставятся",
        ),
        Concept(
            code = "milestone",
            fromFacts = listOf(
                FactRule(kind = "event", with = "date|year"),
                FactRule(sectionHint = "§7 этапы"),
            ),
            fields = mapOf(
                "name" to "событие",
                "date" to "из факта",
                "fleet" to "quantity если есть",
            ),
            mustLink = emptyList(),
            conflictOn = listOf("date"),
            identity = ConceptIdentity(
                key = listOf("name_core"),
                semantic = "тот же этап/событие; отличие — дата",
                threshold = 0.85,
            ),
            targetKind = "milestone (L1); вехи технологий — gate",
            allowedRoles = listOf("charter", "regulatory", "context"),
            fromFactsNote = "подсказка, откуда понятие обычно берётся; ворота на вид факта не ставятся",
        ),
        Concept(
            code = "normative_document",
            fromFacts = listOf(
                FactRule(kind = "obligation", with = "designation"),
            ),
            fields = mapOf(
                "designation" to "natural key",
                "edition" to "из факта",
                "clauses" to "пункты фактами; limit — полем",
            ),
            mustLink = emptyList(),
            conflictOn = listOf("edition"),
            identity = ConceptIdentity(
                key = listOf("designation_natural"),
                semantic = "—",
                threshold = 1.0,
            ),
            allowedRoles = listOf("regulatory", "charter", "tor"),
            fromFactsNote = "подсказка, откуда понятие обычно берётся; ворота на вид факта не ставятся",
        ),
        Concept(
            code = "opportunity",
            fromFacts = listOf(
                FactRule(kind = "external_need"),
                FactRule(kind = "external_target"),
                FactRule(kind = "framing", predicateIn = listOf("требует", "нуждается в", "не хватает"), subjectNot = "stakeholder проекта"),
            ),
            fields = mapOf(
                "external_item" to "факт чужой нужды/цели с якорем",
                "owner" to "сторона или программа, чья она",
                "our_services" to "сервисы и узлы, которыми закрывается",
                "verdict" to "закрываем · частично · не наш профиль · нужна доработка",
                "missing" to "чего не хватает (параметры, требования, сервисы)",
                "scale" to "объекты/объём/деньги из фактов",
                "proposal" to "предложение эксперту: расширить зону · добавить сервис · в descopes · отклонить",
            ),
            mustLink = listOf("external_item→fact"),
            conflictOn = emptyList(),
            note = "возможность не становится целью; она обосновывает цель или порождает предложение эксперту; «не наш профиль» — законный и ценный вердикт, опирается на границы устава",
            targetKind = "opportunity (L2)",
            fromFactsNote = "подсказка, откуда понятие обычно берётся; ворота на вид факта не ставятся",
        ),
    )

    val byCode: Map<String, Concept> = concepts.associateBy { it.code }

    /** Понятие вне онтологии не образуется: отказ вместо тихого пропуска. */
    fun of(code: String): Concept = byCode[code]
        ?: error(
            "понятие «$code» не описано в ОНТОЛОГИЯ-ФОРМИРОВАНИЯ.yaml — " +
                "понятие вне онтологии не образуется"
        )

    /** Вердикты сверки и правила прав — словами истины, для текста экрана. */
    val reconciliation: Map<String, String> = mapOf(
        "new" to "понятие без совпадения по ключу и смыслу",
        "augment" to "совпадение по ключу; новые поля/связи → предложение «дополнить»",
        "contradict" to "совпадение по ключу; различие в conflict_on → contested с рангами обоих",
        "confirm" to "совпадение полностью из независимого документа → evidence=corroborated",
        "manual_edit" to "правка принятого без факта → предложить ручной факт [И] или помету «без основания»",
        "manual_input" to "ручной ввод → кандидат-факт authority=expert → та же сверка: дубль (ключ, затем семантика по срезу понятия) · противоречие (conflict_on после нормализации) · соединение (supports/refines, must_link) · нехватка (must_link) — предложения с уверенностью и полем отличия; выполняет только человек",
        "batching" to "несколько ручных вводов — один вызов сверки с задержкой; срез ≤ 50 фактов и сущностей",
        "rights" to "ИИ не сливает, не переписывает принятое, не выбирает победителя; «похоже» без «чем именно» — брак",
    )

    /** Чего синтез и сверка не вправе нарушить ни при каком ответе модели. */
    val invariants: List<String> = listOf(
        "принятое не меняется синтезом молча",
        "понятие с must_link незакрытым — предложение с пометой, не сущность",
        "ранг документа не решает за человека — подсказывает",
        "каждое предложение несёт факты-основания (≥1) с якорями и рангами",
    )

    /**
     * Роль документа → что он порождает. Перечень общий с истиной схем
     * (`material.role`): роли вне его у материала не бывает.
     */
    val documentRoles: Map<String, String> = mapOf(
        "charter" to "устав миссии — единственный источник целеполагания; в проекте один",
        "tor" to "источник требований (ТЗ заказчика) — требования, ограничения, стороны, нужды заказчика; целей проекта не порождает",
        "regulatory" to "норматив — нормативные документы и пункты, ограничения regulatory, вехи сроков; сторон и целей не порождает (издатель — не сторона)",
        "context" to "обстановка (исследования, аналитика) — стороны-кандидаты, применения, факты о чужих целях и программах, обоснования, риски; целей и сервисов не порождает",
        "supplier" to "поставщик (даташит, КП) — параметры, кандидаты решений, стоимости, риски",
        "heritage" to "наследие — уроки, типовые риски, параметры, обоснования",
    )

    /** Цели и сервисы — только из устава; прочие роли предлагают, но не создают. */
    val goalHierarchy: Map<String, String> = mapOf(
        "rule" to "цели, сервисы и горизонты проекта образуются только из charter; прочие роли могут confirm · contradict · augment · обосновать, но не создать",
        "external_target" to "факт вида external_target — цель чужой программы/заказчика; в реестр целей проекта не попадает",
    )

    /**
     * Призма устава: точка отсчёта, подставляемая в промпт разбора и синтеза
     * ЛЮБОГО другого документа. `fields` — грани призмы по порядку.
     */
    val charterPrismFields: List<String> = listOf("что строим (класс системы)", "чего не строим (границы)", "сервисы", "цели с показателями", "рамки Р", "приоритетные зоны")
    val charterPrism: Map<String, String> = mapOf(
        "source" to "документ роли charter",
        "use" to "подставляется в промпт разбора и синтеза любого другого документа как точка отсчёта",
        "requirements" to "ТРЕБОВАНИЯ-К-ЗАПИСКЕ-МИССИИ — 12 пунктов минимума, полнота проверяется при загрузке",
    )

    /** Как промпт собирается из истины: факты, принятое, сцена, ссылки, примеры. */
    val promptRender: Map<String, String> = mapOf(
        "facts" to "каждый факт записью: код · вид · ранг · метка · якорь · тема; субъект · предикат · объект · значение с единицей — отдельными полями",
        "accepted" to "принятое — с полями, по которым бывает вердикт (для goal: statement, measure, year; для need: statement, stakeholder, qos_class …)",
        "scope" to "понятия — только для текущей сцены; порядок: стороны → нужды → цели/сервисы",
        "refs" to "ссылка на сторону/нужду: code принятого либо ref предложения того же ответа",
        "examples" to "на каждое понятие — пример «из фактов → предложение» и анти-пример (not_from)",
        "self_check" to "перед выводом — список проверок; не прошедшее удаляется, не исправляется",
        "source" to "ПРОМПТ-ФОРМИРОВАНИЕ-V2.md",
        "example_contract" to "образец ответа в промпте — часть контракта и сильнее текста правил: модель копирует образец. Образцы порождаются из схемы ответа и онтологии, руками не пишутся",
    )

    /** Четыре вопроса, которыми читается любой входной документ. */
    val readingQuestions: List<String> = listOf(
        "что подтверждает, уточняет или оспаривает в принятом",
        "чьи цели и нужды здесь названы (external_target / external_need) и чьи они",
        "чем наша система может быть им полезна → opportunity с вердиктом",
        "что здесь обязательно или запрещено для нас (ограничения, нормы, риски)",
    )

    /**
     * Кто выносит вердикт о принятом. Правило истины, а не вкус кода: пока
     * его считали все четыре вопроса сверки, кандидат с основанием в
     * документе молча признавался узнанным (прогон владельца 15.09).
     */
    const val verdictRule: String = "вердикт о принятом выносят только вопросы тождества — дубль и противоречие; соединение и нехватка о принятом не говорят ничего (иначе кандидат молча признаётся узнанным)"

    /**
     * Что ворота проверяют — и чего не делают никогда.
     *
     * РЕШЕНИЕ-ЧИТАТЬ-СМЫСЛ (15.09): предикаты задумывались подсказкой, а были
     * записаны воротами — и очевидное отбивалось, потому что факт оказался
     * не того вида. Ворота проверяют только машинно проверяемое.
     */
    val gatesCheck: List<String> = listOf(
        "цитата дословно есть в каноне",
        "якорь указывает на блок с цитатой",
        "число с единицей из справочника; порог — полем",
        "ссылки разрешаются (нужда→сторона, цель→нужды)",
        "обязательные поля понятия заполнены",
        "дубль по ключу идентичности — предложение слияния",
    )

    /** Чего ворота не делают ни при каких условиях. */
    val gatesNever: List<String> = listOf(
        "судить о виде факта",
        "требовать предиката из списка",
        "отбивать содержательное предложение по форме",
    )

    /** Как читается документ: смыслом; структура раздела — как структура. */
    val readingMode: Map<String, String> = mapOf(
        "rule" to "документ читается смыслом, структура раздела — как структура: таблица построчно, перечень по пунктам, проза прозой",
        "section_map" to "в промпт подаётся карта разделов канона: якорь · тип (таблица|перечень|проза) · заголовок · колонки таблицы",
        "output" to "ИИ отдаёт понятия сразу, каждое с цитатой и якорем; факт — след предложения (цитата + нормализованное утверждение), не предварительный фильтр",
        "source" to "ПРОМПТ-РАЗБОР-В-ПОСТАНОВКУ.md · РЕШЕНИЕ-ЧИТАТЬ-СМЫСЛ.md",
    )
}
