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
    val identity: ConceptIdentity,
    val note: String? = null,
)

object GeneratedOntology {

    /**
     * Отпечаток истины онтологии (sha256 файла). Им помечается каждый запуск
     * синтеза: по нему видно, по каким правилам сделано предложение.
     */
    const val ontologyVersion: String = "2506643e678678b5c75f64c4b6afaf0244975abfe2b1afa3b3af31b34df48954"

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
                "influence" to "по предикату: заказчик/регулятор → decides; оператор/партнёр → influences; потребитель → informed",
            ),
            mustLink = emptyList(),
            conflictOn = listOf("role"),
            identity = ConceptIdentity(
                key = listOf("name_normalized|synonyms"),
                semantic = "имя организации/группы, роль",
                threshold = 0.85,
            ),
            note = "сторона без нужды — помета к воротам, не отказ",
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
                "qos_class" to "из facts о задержке/гарантии; иначе TBR",
            ),
            mustLink = listOf("owns→stakeholder"),
            conflictOn = listOf("statement contradicts"),
            identity = ConceptIdentity(
                key = listOf("stakeholder", "statement_core"),
                semantic = "та же потребность иными словами; отличие — охват, число, класс",
                threshold = 0.8,
            ),
            note = "интерес стороны → нужда [П], не [И]",
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
            note = "разные годы у одного результата в документах разного ранга — contested, не выбор",
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
        ),
        Concept(
            code = "assumption",
            fromFacts = listOf(
                FactRule(mark = "П"),
                FactRule(kind = "assumption"),
            ),
            fields = mapOf(
                "statement" to "факт",
                "owner" to "обязателен при acceptance",
                "confirm_by" to "gate — обязателен",
            ),
            mustLink = emptyList(),
            conflictOn = emptyList(),
            identity = ConceptIdentity(
                key = listOf("statement_core"),
                semantic = "то же допущение",
                threshold = 0.8,
            ),
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
}
