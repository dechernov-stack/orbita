// СГЕНЕРИРОВАНО tools/v2/gen_ontology.py из docs/tz/v2/СХЕМЫ-ПОЛЕЙ-V2.yaml (concepts · formation) — руками не править
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
    /**
     * Указания истины к ключу словами: «statement_core (после
     * нормализации стандартных названий этапов)» — 19.09, журнал З-05.
     * Ключом остаётся имя поля; указание живёт здесь, пока нормализация
     * названий этапов не сделана отдельным шипом.
     */
    val keyNotes: List<String> = emptyList(),
    /**
     * Стандартные названия этапов (З-05): каждое — одно слово ключа, чтобы
     * формулировки об одном этапе сходились ядром. Перечень — истина.
     */
    val stageNames: List<String> = emptyList(),
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
    /** Из чего понятие НЕ образуется — анти-примеры промпта. */
    val notFrom: List<String> = emptyList(),
    /** Роли документов, из фактов которых понятие вообще образуется. */
    val allowedRoles: List<String> = emptyList(),
    /** Условие на роль словами: «charter → только при явном разделе требований». */
    val allowedRolesNote: Map<String, String> = emptyMap(),
    /** Поля, которые считает система, а не модель: в промпт не печатаются. */
    val computedFields: Map<String, String> = emptyMap(),
    /** Что вправе документ чужой роли, когда создать понятие он не может. */
    val changeByOthers: String? = null,
    /** Чем спорят два документа об одном понятии — словами, рядом с conflictOn. */
    val conflictNote: String? = null,
    /** Помета истины: подсказки — не фильтр. Идёт в промпт рядом с примерами. */
    /** Подсказка истины: откуда понятие обычно берётся (слова для инструкции, не ворота). */
    val whereToLook: String? = null,
    /** Где в документе искать понятие: подсказки разделов. */
    val whereInDocument: List<String> = emptyList(),
    /**
     * Откуда понятие берётся — словами. У замысла, требования и риска
     * правил по видам факта нет вовсе: они читаются РАЗДЕЛОМ документа.
     */
    val from: String? = null,
    /** Подсказка места в документе рядом с [from]. */
    val fromHint: String? = null,
    /** Как общая нужда раздаётся сторонам — правило владельца. */
    val distributionRule: String? = null,
    /** Какой формы ждать ответ по этому понятию. */
    val answerShape: String? = null,
    /** Чем считается закрытой нужда на воротах сцены 6 — правило владельца. */
    val coverageRule: String? = null,
    /**
     * Поля, которые ставит СИСТЕМА, — словами самой истины («ставит
     * система при принятии»). Их не спрашивают у человека и не
     * предлагают к правке: подставит их сама система в свой момент.
     */
    val systemFields: List<String> = emptyList(),
    /** Поля, которые ставит ЧЕЛОВЕК на своей сцене («ставит человек (1–5, сцена 11)»). */
    val humanFields: List<String> = emptyList(),
    /** Как предлагается связь цель→нужды и что остаётся человеку. */
    val coverageNote: String? = null,
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
    const val ontologyVersion: String = "496be2ebb12a40396ff4d9269d6ea8dc1411a35bb7292694455a96a033db4a01"

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
            fields = mapOf(
                "name" to "subject",
                "role" to "по предикату",
                "interest" to "список из facts kind=assessment|framing о subject, каждый с цитатой; нужда из интереса — решением (interest_to_need_rule)",
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
            notFrom = listOf("автор или подписант нормативного акта без предиката роли к проекту", "ведомство как издатель", "сама проектируемая система", "регион, порт, коридор — это объекты применения"),
            whereToLook = "подсказка, откуда понятие обычно берётся; ворота на вид факта не ставятся",
            allowedRoles = listOf("charter", "tor", "context", "supplier", "heritage"),
            computedFields = mapOf(
                "influence" to "вычисляется системой из role по карте: customer→decides · regulator→decides · established→decides · operator→influences · partner→influences · supplier→influences · consumer→informed; модель поле не заполняет, инженер правит на месте",
                "power" to "оценка человека 1–5 на сцене 3; по умолчанию пусто",
            ),
        ),
        Concept(
            code = "need",
            fields = mapOf(
                "statement" to "предикат+объект",
                "stakeholder" to "subject (owns) — обязательно; в кандидате — имя ОДНОЙ стороны, у принятой нужды носителей много (stakeholders[]) — та же формулировка у другой стороны дополняет нужду носителем, а не заводит копию",
                "qos_class" to "множество классов из текста (A′ · B′ · C′, один или несколько); сервис уточняет один",
                "coverage_expected" to "чем нужда закрывается: service · constraint · programme · requirement; по умолчанию из роли носителя (заказчик/оператор/потребитель/учреждаемый → service; регулятор → constraint; поставщик/партнёр → programme); инженер правит",
            ),
            mustLink = listOf("owns→stakeholder"),
            conflictOn = listOf("statement contradicts"),
            identity = ConceptIdentity(
                key = listOf("statement_core"),
                semantic = "та же потребность иными словами; отличие — охват, число, класс; носитель ключом не является",
                threshold = 0.8,
            ),
            note = "интерес стороны → нужда [П], не [И]; одна нужда — много носителей (n:m), копий по сторонам нет",
            notFrom = listOf("роль стороны (поле role)", "описание программы или акта", "свойство системы (цель или сервис)"),
            whereToLook = "подсказка: нужды берутся из (а) перечня общих нужд документа, (б) интереса каждой стороны — интерес это нужда-кандидат [П], (в) прямых формулировок нехватки в прозе",
            whereInDocument = listOf("§2.1 общие нужды → привязка к сторонам по субъекту/смыслу"),
            allowedRoles = listOf("charter", "tor", "context"),
            distributionRule = "общая нужда (перечень §2.1) раздаётся сторонам с ролями заказчик · оператор · потребитель · учреждаемый, чей интерес или сфера о том же предмете; поставщикам и регуляторам общие нужды не достаются — их нужды только из собственного интереса; раздача — носителями одной нужды, не копиями",
            answerShape = "нужды живут внутри стороны (stakeholders[].needs[]), у каждой ОБЯЗАТЕЛЬНЫЙ свой id вида p1.n2 — на него ссылаются цели и сервисы; ответ без id у нужды — недействителен",
            coverageRule = "выход сцены 6 «каждая нужда покрыта сервисом» считает только нужды с coverage_expected=service; нужды регуляторов закрываются ограничениями и нормативными основаниями (сцена 5/8), нужды поставщиков и партнёров — пакетами WBS и оценками (сцена 12); непокрытая нужда любого вида — помета к MCR, блокирует только service к сцене 6",
        ),
        Concept(
            code = "goal",
            fields = mapOf(
                "statement" to "предикат",
                "measure" to "quantity с единицей — обязательно",
                "year" to "horizon — обязательно",
                "needs" to "covers ≥1 по субъекту/теме",
            ),
            mustLink = listOf("covers→need>=1"),
            conflictOn = listOf("measure", "year"),
            identity = ConceptIdentity(
                key = listOf("statement_core"),
                keyNotes = listOf("statement_core (после нормализации стандартных названий этапов; числа — к показателю, не к ключу)"),
                stageNames = listOf("подтвердить реализуемость", "лётная демонстрация", "программный MVP", "национальная система", "глобальная опция"),
                semantic = "тот же результат/этап; отличия — значение, год, единица → augment measure/year, не new",
                threshold = 0.8,
            ),
            note = "цели проекта меняются только решением эксперта; всякий документ может предложить изменение с основанием и рангом — не создать цель сам",
            notFrom = listOf("обязательство норматива (constraint)", "описание услуги (service)"),
            whereToLook = "подсказка, откуда понятие обычно берётся; ворота на вид факта не ставятся",
            allowedRoles = listOf("charter"),
            changeByOthers = "только предложение augment/contradict с обоснованием; решает человек",
            coverageNote = "связь цель→нужды: модель предлагает по совпадению стороны и предмета; недостающее — сцена 4, решение инженера (матрица покрытия), сверка «соединение» предлагает кандидатов",
        ),
        Concept(
            code = "service",
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
            whereToLook = "подсказка, откуда понятие обычно берётся; ворота на вид факта не ставятся",
            whereInDocument = listOf("§4 сервисы"),
            allowedRoles = listOf("charter"),
            changeByOthers = "только предложение augment/contradict с обоснованием; решает человек",
        ),
        Concept(
            code = "constraint",
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
            notFrom = listOf("желание стороны (need)", "целевой показатель (goal)"),
            whereToLook = "подсказка, откуда понятие обычно берётся; ворота на вид факта не ставятся",
            allowedRoles = listOf("charter", "tor", "regulatory"),
        ),
        Concept(
            code = "assumption",
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
            whereToLook = "подсказка, откуда понятие обычно берётся; ворота на вид факта не ставятся",
            allowedRoles = listOf("charter", "tor", "context", "supplier", "heritage"),
        ),
        Concept(
            code = "milestone",
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
            note = "веха из устава — только этап проекта (program_stage) с горизонтом или диапазоном; срок действия документа, дата акта, событие рынка — не веха, а факт event или valid_until норматива",
            targetKind = "milestone (L1); вехи технологий — gate",
            whereToLook = "подсказка, откуда понятие обычно берётся; ворота на вид факта не ставятся",
            whereInDocument = listOf("§7 этапы"),
            allowedRoles = listOf("charter", "regulatory", "context"),
        ),
        Concept(
            code = "normative_document",
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
            whereToLook = "подсказка, откуда понятие обычно берётся; ворота на вид факта не ставятся",
            allowedRoles = listOf("regulatory", "charter", "tor"),
            fromHint = "перечень НПА устава (§2.2) читается построчно: обозначение · номер · дата · редакция · срок — каждая строка → норматив; пункты — при наличии текста акта",
        ),
        Concept(
            code = "opportunity",
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
            conflictOn = listOf("verdict", "missing"),
            identity = ConceptIdentity(
                key = listOf("external_item.statement_core", "owner"),
                semantic = "та же чужая нужда или цель того же владельца, сказанная иначе",
                threshold = 0.8,
            ),
            note = "возможность не становится целью; она обосновывает цель или порождает предложение эксперту; «не наш профиль» — законный и ценный вердикт, опирается на границы устава",
            targetKind = "opportunity (L2)",
            whereToLook = "подсказка, откуда понятие обычно берётся; ворота на вид факта не ставятся",
            conflictNote = "два документа дают разный вердикт применимости к одной чужой нужде — contested с обоими и их ролями",
        ),
        Concept(
            code = "intent",
            fields = mapOf(
                "for_whom" to "",
                "what" to "",
                "where" to "",
                "horizon" to "",
                "accepted_by" to "ставит система при принятии",
                "accepted_at" to "ставит система при принятии",
            ),
            mustLink = emptyList(),
            conflictOn = emptyList(),
            identity = ConceptIdentity(
                key = listOf("project"),
                semantic = "—",
                threshold = 1.0,
            ),
            note = "один на проект; правится только решением",
            targetKind = "intent",
            allowedRoles = listOf("charter"),
            from = "§1 устава: для кого · что делает · где · горизонт — четыре поля цитатами",
            systemFields = listOf("accepted_by", "accepted_at"),
        ),
        Concept(
            code = "requirement",
            fields = mapOf(
                "code" to "выдаёт система",
                "title" to "",
                "statement" to "формулировка как в тексте",
                "measure" to "{value|min,max,unit} если есть",
                "carrier" to "пусто — ставит инженер на сцене 8",
                "source" to "цитата + якорь",
            ),
            mustLink = emptyList(),
            conflictOn = listOf("measure"),
            identity = ConceptIdentity(
                key = listOf("statement_core"),
                semantic = "то же требование иными словами",
                threshold = 0.85,
            ),
            note = "мера чтения устава без явного раздела — 0 требований; мера сцены 8 — 12 требований из постановки (ПОСТАНОВКА-ИЗ-ЗАПИСКИ.requirements как ожидание)",
            targetKind = "requirement",
            allowedRoles = listOf("tor", "charter"),
            allowedRolesNote = mapOf(
                "charter" to "только при явном разделе «требования к миссии»",
            ),
            from = "ТЗ: требования заказчика — читаются; устав: только явный раздел требований. Требования уровня проекта из целей, нужд и ограничений НЕ читаются из документа — они образуются на сцене 8 (деривация из принятой постановки, решением инженера)",
        ),
        Concept(
            code = "risk",
            fields = mapOf(
                "statement" to "",
                "cec" to "{condition,event,consequence} если в тексте так",
                "category" to "technical|cost|schedule|safety|regulatory|security|programmatic",
                "owner" to "пусто — ставит человек",
                "due_point" to "пусто — ставит человек",
                "probability" to "ставит человек (1–5, сцена 11)",
                "impact" to "ставит человек (1–5, сцена 11)",
                "strategy" to "ставит человек (сцена 11)",
                "measures" to "ставит человек (сцена 11)",
            ),
            mustLink = emptyList(),
            conflictOn = emptyList(),
            identity = ConceptIdentity(
                key = listOf("statement_core"),
                semantic = "тот же риск",
                threshold = 0.8,
            ),
            note = "вероятность и последствия 1–5 — ставит человек; из документа только формулировка и класс",
            targetKind = "risk",
            allowedRoles = listOf("charter", "tor", "context", "supplier", "heritage"),
            from = "раздел рисков документа или оценка с последствием («если … то …»)",
            humanFields = listOf("owner", "due_point", "probability", "impact", "strategy", "measures"),
        ),
    )

    val byCode: Map<String, Concept> = concepts.associateBy { it.code }

    /** Понятие вне онтологии не образуется: отказ вместо тихого пропуска. */
    fun of(code: String): Concept = byCode[code]
        ?: error(
            "понятие «$code» не описано в истине схем (СХЕМЫ-ПОЛЕЙ-V2.yaml, concepts) — " +
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

    /**
     * Канон написания и формы (17.09): штрих ′ вместо апострофа, величины
     * объектом, метка источника с номером. Идёт в промпт и в приведение.
     */
    val normalization: Map<String, String> = mapOf(
        "prime" to "канон — типографский штрих ′ (U+2032); апостроф ' и акцент ´ при чтении нормализуются в ′",
        "measures" to "ЛЮБАЯ величина в ответе — объектом {value|min,max,unit}: показатель цели, target_measure сервиса, масштаб стороны, диапазон этапа, граница ограничения; строка с числом — брак",
        "source_marks" to "метка И/В/П + номер источника из списка документа (И1, В10) — оба поля",
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
     * Роль стороны → её влияние. Поле вычисляет СИСТЕМА, не модель
     * (`stakeholder.computed_fields.influence`): «модель поле не заполняет,
     * инженер правит на месте». Карта — истина владельца целиком; своего
     * правила в коде нет, и роль, которой здесь не названо, влияния не
     * получает.
     */
    val influenceMap: Map<String, String> = mapOf(
        "customer" to "decides",
        "regulator" to "decides",
        "established" to "decides",
        "operator" to "influences",
        "partner" to "influences",
        "supplier" to "influences",
        "consumer" to "informed",
    )

    /**
     * Роль стороны → сила по умолчанию 1–5 (журнал ПМИ-7, З-02). Это
     * ПРЕДЛОЖЕНИЕ [П], не оценка: на сетке оно серым, инженер правит кликом
     * по ячейке; роль, которой здесь нет, силы не получает.
     */
    val powerMap: Map<String, Int> = mapOf("customer" to 5, "regulator" to 5, "established" to 4, "operator" to 4, "consumer" to 3, "supplier" to 3, "partner" to 3)

    /**
     * Базовый набор критериев оценки миссии (журнал ПМИ-7, З-08): сцена 4
     * предлагает его кнопкой, порог — TBR до сцены 7. Группа и направление —
     * перечни вида metric истины схем.
     */
    val missionCriteriaBase: List<Map<String, String>> = listOf(
        mapOf("key" to "coverage_a", "title" to "покрытие A′", "direction" to "max", "group" to "A"),
        mapOf("key" to "p95_latency", "title" to "P95 задержки доставки", "direction" to "min", "group" to "B"),
        mapOf("key" to "lifecycle_cost", "title" to "стоимость жизненного цикла", "direction" to "min", "group" to "V"),
        mapOf("key" to "trl_risk", "title" to "риск TRL", "direction" to "min", "group" to "G"),
    )

    /**
     * Роль носителя нужды → чем её нужда закрывается (`need.coverage_expected`).
     *
     * Истина владельца 17.09: выход сцены 6 «каждая нужда покрыта сервисом»
     * считает ТОЛЬКО нужды с `service`; нужда регулятора закрывается
     * ограничением (сцены 5/8), нужда поставщика и партнёра — пакетом WBS
     * и оценкой (сцена 12). Своего правила в коде нет.
     */
    val coverageByRole: Map<String, String> = mapOf(
        "customer" to "service",
        "operator" to "service",
        "consumer" to "service",
        "established" to "service",
        "regulator" to "constraint",
        "supplier" to "programme",
        "partner" to "programme",
    )

    /** Чем считается пройденным выход сцены по нуждам: сцены 6, 8 и 12. */
    val sceneExitNotes: Map<String, String> = mapOf(
        "scene_6" to "covered(service) для coverage_expected=service = 100 %; остальные виды покрытия — счётчиком, не блокером",
        "scene_8" to "нужды coverage_expected=constraint|requirement имеют ограничение или требование с derives_from",
        "scene_12" to "нужды coverage_expected=programme имеют пакет WBS или оценку",
        "scene_4" to "covered(goal) обязательно только для нужд coverage_expected=service; остальные — счётчиком; критерии оценки миссии — выход мероприятия 0.5 (поверхность на сцене 4 или перенос в 7 — решение владельца)",
    )

    /** Правки истины, подтверждённые владельцем: не догадка кода. */
    val confirmedEdits: List<String> = listOf(
        "17.09: risk.probability/impact/strategy/measures — ставит человек (сцена 11); intent.accepted_by/at — ставит система (подтверждено)",
    )

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

    /**
     * Природа правил формулировки (`lint_rules`, истина 19.09).
     *
     * Правила П13–П20 — РЕКОМЕНДАТЕЛЬНЫЕ пометы: приём предложения они не
     * держат никогда, а базирование держит только то, что названо
     * `hardGatesOnly`. Журнал ПМИ-7, З-15: линт отбивал приём требования.
     */
    val lintNature: String = "все правила формулировок П13–П20 — рекомендательные пометы; ни одно не держит приём предложения; к базированию — мягко, с «принять как есть» (помета сохраняется, отмечена как осознанная)"

    /** Что держит базирование — словами истины; прочий линт мягок. */
    val hardGatesOnly: List<String> = listOf(
        "пустая формулировка",
        "число без единицы справочника (к базированию)",
        "нет носителя (к базированию)",
        "нет источника",
    )

    /** Правило атомарности П14 словами истины: два глагола ≠ два действия. */
    val p14Atomicity: String = "помета только при двух глаголах с разными объектами и разными проверяемыми исходами; «принимать и доставлять сообщение» — одно действие; помета несёт предложение разбиения текстом или не выставляется"
}
