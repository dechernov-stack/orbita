// Генерация документов из модели (TZ-OUT-001, шаг 11.1).
// Эталон spec/presentation_semantics.py, один в один.
//
// Генерация — ЧИСТАЯ ФУНКЦИЯ МОДЕЛИ: повторный вызов даёт идентичный результат,
// модель не изменяется. Следствие, о котором предупреждает регламент: ручное
// дополнение текста после генерации не сохраняется — его негде хранить, документ
// целиком выводится из модели. Правка вносится в модель, а не в документ.
//
// СТРУКТУРА РАЗДЕЛОВ ЗАДАНА РЕГЛАМЕНТОМ, а не удобством генератора: приложения
// 2, 3 и 4 БП-PA перечисляют разделы поимённо, и документ обязан состоять
// из них — в том числе из тех, которые модель заполнить пока не может.
//
// ПУСТОЙ РАЗДЕЛ НЕ ВЫБРАСЫВАЕТСЯ. Документ, из которого молча исчезли разделы,
// выглядит полным: читатель видит связный текст и не знает, что раздел
// «Персонал и обеспечение» отсутствует не потому, что не нужен, а потому,
// что в модели нет ни одного объекта, из которого его собрать. Раздел
// остаётся на месте, а рядом с ним стоит разрыв со словами регламента о том,
// что там должно быть. Это та же разница, что между «пусто» и «замечаний нет».
package orbita.out

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.security.MessageDigest

/** Раздел приложения регламента: номер, заголовок и что регламент требует в нём видеть. */
data class SectionTemplate(val number: Int, val title: String, val expects: String)

/** Шаблоны первой очереди: приложения 2–4 регламента БП-PA. */
enum class DocumentTemplate(
    val code: String,
    val title: String,
    /** Откуда взята структура разделов: документ и номер приложения. */
    val source: String,
    val sections: List<SectionTemplate>,
) {
    RequirementSpecification(
        "req_spec", "Спецификация требований", "БП-PA, Приложение 2",
        listOf(
            SectionTemplate(1, "Введение", "Назначение; область; применимые документы"),
            SectionTemplate(
                2, "Требования уровня проекта",
                "Цели миссии; ключевые показатели; программные ограничения",
            ),
            SectionTemplate(
                3, "Системные требования",
                "Функциональные; характеристик; интерфейсные; эксплуатационные; " +
                    "надёжности и безопасности; среды",
            ),
            SectionTemplate(
                4, "Матрица трассировки",
                "Двунаправленная трассировка: цель миссии ↔ требование ↔ элемент " +
                    "архитектуры ↔ метод верификации",
            ),
            SectionTemplate(
                5, "Матрица верификации",
                "Метод (испытание/анализ/демонстрация/инспекция) и этап верификации " +
                    "для каждого требования",
            ),
        ),
    ),
    ConOps(
        "conops", "Концепция применения (заготовка)", "БП-PA, Приложение 3",
        listOf(
            SectionTemplate(
                1, "Назначение и контекст",
                "Назначение системы; внешние системы; пользователи и операторы; допущения",
            ),
            SectionTemplate(
                2, "Архитектура операций",
                "Сегменты; распределение функций; потоки командования и данных",
            ),
            SectionTemplate(3, "Режимы и состояния", "Перечень режимов; условия и логика переходов"),
            SectionTemplate(
                4, "Операционные сценарии",
                "Номинальные сценарии по этапам полёта; нештатные и аварийные сценарии; " +
                    "сценарии завершения миссии",
            ),
            SectionTemplate(
                5, "Операционная среда и ограничения",
                "Орбитальная среда; связь; энергетика; планирование; наземная инфраструктура",
            ),
            SectionTemplate(6, "Персонал и обеспечение", "Состав смен; подготовка; средства поддержки"),
            SectionTemplate(
                7, "Валидационные положения",
                "Критерии, по которым ConOps используется для валидации требований и архитектуры",
            ),
        ),
    ),
    ArchitectureDescription(
        "architecture", "Описание архитектуры", "БП-PA, Приложение 4",
        listOf(
            SectionTemplate(
                1, "Архитектурный контекст",
                "Границы системы; внешние интерфейсы; драйверы архитектуры",
            ),
            SectionTemplate(
                2, "Функциональная архитектура",
                "Функциональная декомпозиция; распределение функций по элементам",
            ),
            SectionTemplate(
                3, "Физическая архитектура",
                "Сегменты, системы, подсистемы; WBS-привязка; MEL с резервами",
            ),
            SectionTemplate(
                4, "Интерфейсы",
                "Реестр внутренних и внешних интерфейсов; ответственность сторон",
            ),
            SectionTemplate(
                5, "Распределение требований",
                "Flow down системных требований на элементы; черновые спецификации подсистем",
            ),
            SectionTemplate(
                6, "Обоснование архитектуры",
                "Результаты trade studies; отклонённые альтернативы; чувствительность",
            ),
            SectionTemplate(
                7, "Бюджеты",
                "Массовый, энергетический, информационный, точностной бюджеты с резервами",
            ),
        ),
    ),
    // ---------- Блок C задания «прогон до KDP B»: комплекты Д1–Д9 и Д1–Д10 ----------
    Fad(
        "fad", "Санкционирование формулирования (FAD)", "БП-PPA, Приложение 1",
        listOf(
            SectionTemplate(1, "Назначение и обоснование", "Наименование проекта; связь с целями Агентства и дирекции; основание для инициирования"),
            SectionTemplate(2, "Полномочия и назначения", "Санкционируемая деятельность (Формулирование); руководитель проекта; ведущий центр; участвующие организации"),
            SectionTemplate(3, "Рамки и исходные требования", "Границы санкционируемых работ; исходные требования дирекции к проекту; категория проекта; интерфейсы с программой"),
            SectionTemplate(4, "Финансирование", "Ресурсы Формулирования с разбивкой по годам; источник финансирования"),
            SectionTemplate(5, "Сроки и контрольные события", "Горизонт Формулирования; обязательные жизнециклические обзоры; независимые проверки"),
            SectionTemplate(6, "Согласование и утверждение", "Подписи: MDAA; согласующие инстанции; дата; версия"),
        ),
    ),
    MissionConcept(
        "mission_concept", "Отчёт о концепции миссии (MCReport)", "БП-PPA, Приложение 3",
        listOf(
            SectionTemplate(1, "Цели и задачи миссии", "Цели; задачи; показатели эффективности миссии (MOE); связь с программными целями"),
            SectionTemplate(2, "Методика и результаты AoA", "Пространство альтернатив; критерии сравнения; матрицы оценки; обоснование выбора"),
            SectionTemplate(3, "Базовая концепция", "Архитектура миссии; сегменты (космический, наземный, пользовательский); ключевые характеристики"),
            SectionTemplate(4, "Концепция операций (draft)", "Ссылка на Д4 либо включение по Прил. 5"),
            SectionTemplate(5, "Перечень оборудования (MEL)", "MEL по WBS с массовыми и энергетическими резервами"),
            SectionTemplate(6, "Проект миссии", "Траектория; окна запуска; варианты средств выведения"),
            SectionTemplate(7, "Технологические потребности", "Ссылка на Д5 либо включение по Прил. 6"),
            SectionTemplate(8, "Предварительные риски", "Ссылка на Д6 либо включение по Прил. 7"),
            SectionTemplate(9, "Возможные сокращения (descopes)", "Перечень допустимых сокращений состава/характеристик с оценкой последствий"),
            SectionTemplate(10, "Концептуальные оценки стоимости и сроков", "Оценка порядка величины по WBS; допущения; неопределённости"),
            SectionTemplate(11, "Выводы и рекомендации", "Заключение об осуществимости; рекомендации для Phase A; открытые вопросы"),
        ),
    ),
    RequirementDraft(
        "req_draft", "Черновик требований уровня проекта", "БП-PPA, Приложение 4",
        listOf(
            SectionTemplate(1, "Записи требований", "ID; формулировка «система должна…»; категория; источник; показатель и целевое значение (допускается TBD/TBR); метод верификации (предв.); статус"),
        ),
    ),
    TechnologyNeeds(
        "tech_needs", "Оценка технологических потребностей", "БП-PPA, Приложение 6",
        listOf(
            SectionTemplate(1, "Записи оценки", "Технология; элемент концепции; текущий TRL с обоснованием; требуемый TRL и срок; разрыв и план созревания; альтернатива"),
        ),
    ),
    RiskList(
        "risk_list", "Предварительный перечень рисков", "БП-PPA, Приложение 7",
        listOf(
            SectionTemplate(1, "Записи рисков", "ID; формулировка «условие — событие — последствие»; категория; вероятность/последствия 1–5 (NPR 8000.4); стратегия реагирования; владелец"),
        ),
    ),
    OdaReport(
        "oda", "Оценка орбитального засорения", "NASA-STD-8719.14",
        listOf(
            SectionTemplate(1, "Оценка увода и рисков", "Срок увода с орбиты; риск для населения; вид оценки (начальная/актуализированная)"),
            SectionTemplate(2, "Соответствие требованиям стандарта", "Проверенные правила NASA-STD-8719.14 с выводом по каждому"),
        ),
    ),
    CostEstimateReport(
        "cost_estimate", "Концептуальные оценки стоимости и сроков", "БП-PPA §6 (Д8)",
        listOf(
            SectionTemplate(1, "Итоговые оценки", "Оценка порядка величины: стоимость (low/high), сроки; основание оценки"),
            SectionTemplate(2, "Разбивка по WBS", "Стоимость по элементам структуры декомпозиции работ"),
        ),
    ),
    FormulationAgreement(
        "formulation_agreement", "Соглашение о формулировании (FA)", "БП-PPA, Приложение 2",
        listOf(
            SectionTemplate(1, "Общие сведения", "Проект; основание (ссылка на FAD); период действия; стороны соглашения"),
            SectionTemplate(2, "Состав работ Phase A", "Детальный перечень технических и закупочных работ; работы по созреванию технологий; работы по снятию ключевых рисков"),
            SectionTemplate(3, "Состав работ Phase B (предварительно)", "Укрупнённый перечень работ; допущения"),
            SectionTemplate(4, "График и вехи", "Календарный план Phase A–B; жизнециклические обзоры (SRR, SDR/MDR, PDR); продукты и их зрелости по прил. I NPR 7120.5; диапазоны стоимости и сроков к KDP B"),
            SectionTemplate(5, "Ресурсы", "Финансирование Phase A–B по годам; трудовые ресурсы; инфраструктура"),
            SectionTemplate(6, "Отклонения (tailoring)", "Перечень согласованных отклонений от требуемых зрелостей продуктов NPR 7120.5 с обоснованием каждого"),
            SectionTemplate(7, "Ведущие индикаторы", "Программные и технические индикаторы хода Формулирования; порядок их представления на обзорах и KDP"),
            SectionTemplate(8, "Согласование и утверждение", "Подписи: DA, MDAA, руководитель проекта; дата; версия (обновляется к KDP B)"),
        ),
    ),
    Semp(
        "semp", "План управления системной инженерией (SEMP)", "БП-PA, Приложение 1",
        listOf(
            SectionTemplate(1, "Назначение и область применения", "Проект; охватываемые фазы; связь с Project Plan и FA"),
            SectionTemplate(2, "Техническое резюме проекта", "Система; границы; ключевые технические задачи фазы"),
            SectionTemplate(3, "Организация технических работ", "Роли и ответственность; интеграция дисциплин; взаимодействие с подрядчиками"),
            SectionTemplate(4, "Применение технических процессов", "Реализация процессов NPR 7123.1 в проекте: определение требований, архитектура, интеграция, верификация/валидация, управление требованиями, интерфейсами, рисками, конфигурацией, техническими данными, оценками"),
            SectionTemplate(5, "Технические обзоры", "Перечень обзоров фазы; входные/выходные критерии; порядок работы с RFA/RID"),
            SectionTemplate(6, "Отклонения (tailoring)", "Согласованные отклонения от NPR 7123.1 с обоснованием"),
            SectionTemplate(7, "Инструменты и среда инженерии", "Система управления требованиями; системная модель (MBSE) и порядок её ведения; CAD/CAE; управление данными"),
            SectionTemplate(8, "Согласование и утверждение", "Подписи; версия; порядок актуализации"),
        ),
    ),
    TechnologyPlan(
        "tech_plan", "План разработки технологий", "БП-PA, Приложение 5",
        listOf(
            SectionTemplate(1, "Перечень критических технологий", "Технологии с текущим и требуемым TRL; привязка к элементам архитектуры"),
            SectionTemplate(2, "Планы созревания", "Работы, вехи и критерии достижения TRL по каждой технологии (целевой уровень — TRL 6 к PDR)"),
            SectionTemplate(3, "Ресурсы и график", "Бюджет и сроки технологических работ; ответственные"),
            SectionTemplate(4, "Резервные решения", "Альтернативы при недостижении требуемого TRL; критерии и сроки принятия решения о переходе на альтернативу"),
            SectionTemplate(5, "Порядок переоценки", "Периодичность и методика переоценки TRL; отчётность"),
        ),
    ),
    RiskPlan(
        "risk_plan", "План управления рисками", "БП-PA, Приложение 6",
        listOf(
            SectionTemplate(1, "Процесс управления рисками", "Идентификация, анализ, планирование реагирования, мониторинг (по NPR 8000.4)"),
            SectionTemplate(2, "Шкалы и матрица", "Шкалы вероятности и последствий (1–5); матрица критичности; пороги эскалации"),
            SectionTemplate(3, "Роли", "Владелец риска; координатор; порядок рассмотрения на советах проекта"),
            SectionTemplate(4, "Реестр рисков", "Атрибуты записи: ID; формулировка (условие—событие—последствие); категория; вероятность/последствия; критичность; стратегия; мероприятия; владелец; статус; срок"),
            SectionTemplate(5, "Отчётность", "Периодичность; представление на обзорах и KDP"),
        ),
    ),
    CostRanges(
        "cost_ranges", "Диапазоны оценок стоимости и сроков", "БП-PA §6 (Д9)",
        listOf(
            SectionTemplate(1, "Диапазоны к KDP B", "Стоимость high/low; сроки; основание; CADRe (акт.)"),
            SectionTemplate(2, "Разбивка по WBS", "Диапазоны по элементам структуры декомпозиции работ"),
        ),
    ),
    ProjectPlan(
        "project_plan", "Предварительный план проекта", "БП-PA, Приложение 7",
        listOf(
            SectionTemplate(1, "Цели и требования проекта", "Цели; программные требования; критерии успеха"),
            SectionTemplate(2, "Организация и управление", "Организационная структура; полномочия; порядок принятия решений"),
            SectionTemplate(3, "Состав работ", "WBS; словарь WBS; распределение по исполнителям"),
            SectionTemplate(4, "График", "Укрупнённый календарный план по фазам; вехи; жизнециклические обзоры"),
            SectionTemplate(5, "Бюджет", "Оценка LCC; финансирование по годам; резервы"),
            SectionTemplate(6, "Управление рисками", "Ссылка на Д7; ключевые риски проекта"),
            SectionTemplate(7, "Закупки и партнёрства", "Стратегия закупок; соглашения о партнёрстве"),
            SectionTemplate(8, "SMA", "Подход к безопасности и гарантии миссии; классификация полезной нагрузки"),
            SectionTemplate(9, "Управление конфигурацией и данными", "Порядок базирования и изменений; управление технической документацией"),
            SectionTemplate(10, "Согласование и утверждение", "Подписи; версия; порядок актуализации (базируется к KDP C)"),
        ),
    ),
    ;

    companion object {
        fun of(code: String): DocumentTemplate = entries.firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("неизвестный шаблон документа: $code")
    }
}


/**
 * Комплекты документов фаз (блок C): Д-код регламента → код шаблона.
 * Составы — РЕСУРС document-kits.json: реестр читают и код, и эталон
 * spec/process_backbone.py, второй копии составов нет.
 */
object DocumentKits {
    private val kits: Map<String, Map<String, String>> = run {
        val mapper = ObjectMapper()
        DocumentKits::class.java.getResourceAsStream("/orbita/out/document-kits.json")!!
            .use { mapper.readTree(it) }
            .properties()
            .filter { (k, _) -> !k.startsWith("_") }
            .associate { (phase, body) ->
                phase to buildMap { body.properties().forEach { (d, t) -> put(d, t.asText()) } }
            }
    }

    val PRE_PHASE_A: Map<String, String> get() = kits.getValue("pre_phase_a")
    val PHASE_A: Map<String, String> get() = kits.getValue("phase_a")

    fun kit(phase: String): Map<String, String> = kits[phase] ?: PRE_PHASE_A
}

/** Разрыв документа: раздел или запись, которую модель заполнить не может. */
data class DocumentGap(val section: Int, val what: String, val expected: String)

/** Документ: тело и слепок содержимого для сверки воспроизводимости. */
data class GeneratedDocument(
    val template: DocumentTemplate,
    val body: ObjectNode,
    val digest: String,
    /** Разрывы: раздел без содержимого либо запись без обязательного атрибута. */
    val gaps: List<DocumentGap>,
)

/**
 * Атрибуты записи требования по Приложению 2. Отсутствие любого из них —
 * разрыв документа: спецификация без обоснования требования проходит вычитку
 * и падает на первом же вопросе «почему именно сто килограммов».
 */
/** Что регламент ждёт во «Введении» (БП-PA, Приложение 2, §1). */
private val INTRODUCTION_ATTRIBUTES = listOf(
    "purpose" to "назначение",
    "scope" to "область",
    "applicable_documents" to "применимые документы",
)

private val REQUIREMENT_ATTRIBUTES = listOf(
    "id" to "идентификатор",
    "statement" to "формулировка",
    "category" to "категория",
    "source" to "источник (родительское требование)",
    "rationale" to "обоснование",
    "mop" to "показатель и значение",
    "verification_method" to "метод верификации",
    "status" to "статус",
    "version" to "версия",
    "owner" to "владелец",
)

class DocumentGenerator(private val mapper: ObjectMapper = ObjectMapper()) {

    /**
     * Сборка документа из выгрузки модели. Функция не принимает изменяемого
     * состояния и ничего не пишет: модель после вызова та же, что и до.
     */
    fun render(model: JsonNode, template: DocumentTemplate): GeneratedDocument {
        val body = mapper.createObjectNode()
        body.put("template", template.code)
        body.put("title", template.title)
        body.put("source", template.source)
        val gaps = mutableListOf<DocumentGap>()
        val sections = body.putArray("sections")

        for (s in template.sections) {
            val node = sections.addObject()
            node.put("number", s.number)
            node.put("title", s.title)
            node.put("expects", s.expects)
            val items = node.putArray("items")
            fill(template, s.number, model, items, gaps)
            // Раздел остаётся в документе пустым, но не молча: регламент
            // сказал, что в нём должно быть, — это и записывается разрывом.
            if (items.isEmpty) gaps += DocumentGap(s.number, "раздел пуст", s.expects)
        }

        // Плоский перечень записей документа: сохраняется для совместимости
        // с потребителями, читавшими документ до появления разделов.
        val items = body.putArray("items")
        sections.forEach { s -> s.path("items").forEach(items::add) }
        return GeneratedDocument(template, body, digestOf(body), gaps.toList())
    }

    private fun fill(
        template: DocumentTemplate,
        section: Int,
        model: JsonNode,
        items: ArrayNode,
        gaps: MutableList<DocumentGap>,
    ) = when (template) {
        DocumentTemplate.RequirementSpecification -> fillRequirementSpec(section, model, items, gaps)
        DocumentTemplate.ConOps -> fillConOps(section, model, items)
        DocumentTemplate.ArchitectureDescription -> fillArchitecture(section, model, items)
        DocumentTemplate.Fad -> fillFad(section, model, items)
        DocumentTemplate.MissionConcept -> fillMissionConcept(section, model, items)
        DocumentTemplate.RequirementDraft -> fillRequirementDraft(section, model, items)
        DocumentTemplate.TechnologyNeeds -> fillTechnologyRecords(section, model, items, gaps)
        DocumentTemplate.RiskList -> fillRiskRecords(section, model, items)
        DocumentTemplate.OdaReport -> fillOda(section, model, items)
        DocumentTemplate.CostEstimateReport -> fillCost(section, model, items, "rom")
        DocumentTemplate.FormulationAgreement -> fillFormulationAgreement(section, model, items)
        DocumentTemplate.Semp -> fillSemp(section, model, items)
        DocumentTemplate.TechnologyPlan -> fillTechnologyPlan(section, model, items, gaps)
        DocumentTemplate.RiskPlan -> fillRiskPlan(section, model, items)
        DocumentTemplate.CostRanges -> fillCost(section, model, items, "range")
        DocumentTemplate.ProjectPlan -> fillProjectPlan(section, model, items, gaps)
    }

    // ---------- Приложение 2: спецификация требований ----------

    private fun fillRequirementSpec(
        section: Int,
        model: JsonNode,
        items: ArrayNode,
        gaps: MutableList<DocumentGap>,
    ) {
        val requirements = model.path("requirements").sortedBy { it.path("id").asText() }
        when (section) {
            1 -> introduction(model, items, gaps, section)
            // Уровень требования задаёт раздел: проектные — во второй, системные —
            // в третий. Свалить всё в один список значило бы потерять различие,
            // которое регламент проводит намеренно.
            2 -> requirements.filter { it.path("level").asText() == "project" }
                .forEach { items.add(requirementRecord(it, gaps, section)) }
            3 -> requirements.filter { it.path("level").asText() != "project" }
                .forEach { items.add(requirementRecord(it, gaps, section)) }
            4 -> requirements.forEach { r ->
                val n = items.addObject()
                n.put("requirement", r.path("id").asText())
                val up = n.putArray("traces_up")
                r.path("traces_up").forEach { up.add(it.path("ref").asText()) }
                val elements = n.putArray("allocated_to")
                r.path("allocated_to").forEach { elements.add(it.path("component").asText()) }
                n.put("verification_method", verificationMethod(r) ?: "")
            }
            5 -> requirements.forEach { r ->
                // Строка на СОБЫТИЕ, а не на требование: требование с анализом
                // на Phase A и испытанием на Phase C — это две разные строки
                // с разными этапами, и сводить их в одну нельзя.
                r.path("verification_events").forEach { e ->
                    val n = items.addObject()
                    n.put("requirement", r.path("id").asText())
                    n.put("event", e.path("id").asText())
                    n.put("method", e.path("method").asText(""))
                    n.put("phase", e.path("phase").asText(""))
                    n.put("level", e.path("level").asText(""))
                    n.put("closes", e.path("closes").asBoolean(false))
                    n.put("status", e.path("status").asText(""))
                }
            }
            else -> {}
        }
    }

    /** Запись требования со всеми атрибутами Приложения 2; недостающие — разрывы. */
    /**
     * «Введение»: назначение, область и применимые документы — из проекта
     * (ADR-022). Незаполненное поле — разрыв ПОИМЁННО, а не молча пустая
     * строка в разделе: инженер обязан видеть, чего именно не хватает, и
     * иметь возможность это дописать. Отсутствие самого проекта оставляет
     * раздел пустым, и общий разрыв «раздел пуст» скажет об этом сам.
     */
    private fun introduction(
        model: JsonNode,
        items: ArrayNode,
        gaps: MutableList<DocumentGap>,
        section: Int,
    ) {
        val p = model.path("project").takeIf { it.isObject && !it.isEmpty } ?: return
        val n = items.addObject()
        n.put("purpose", p.path("purpose").asText(""))
        n.put("scope", p.path("scope").asText(""))
        val docs = n.putArray("applicable_documents")
        p.path("applicable_documents").forEach { d ->
            val code = d.path("code").asText("")
            val title = d.path("title").asText("")
            val revision = d.path("revision").asText("")
            docs.add(listOf(code, title, revision).filter { it.isNotBlank() }.joinToString(" — "))
        }
        for ((field, label) in INTRODUCTION_ATTRIBUTES) {
            val value = n.path(field)
            val blank = (value.isTextual && value.asText().isBlank()) ||
                (value.isArray && value.isEmpty)
            if (blank) gaps += DocumentGap(section, "проект: не заполнено «$label»", "Введение")
        }
    }

    private fun requirementRecord(r: JsonNode, gaps: MutableList<DocumentGap>, section: Int): ObjectNode {
        val n = mapper.createObjectNode()
        val id = r.path("id").asText()
        n.put("id", id)
        n.put("statement", r.path("statement").asText(""))
        n.put("category", r.path("category").asText(""))
        n.put("level", r.path("level").asText(""))
        n.put("source", r.path("traces_up").joinToString(", ") { it.path("ref").asText() })
        n.put("rationale", r.path("rationale").asText(""))
        r.path("mop").takeIf { it.isObject && !it.isEmpty }?.let { n.set<ObjectNode>("mop", it) }
        n.put("verification_method", verificationMethod(r) ?: "")
        n.put("status", r.path("lifecycle").path("status").asText(""))
        n.put("version", r.path("lifecycle").path("version").asText(""))
        n.put("owner", r.path("owner").asText(""))

        for ((field, label) in REQUIREMENT_ATTRIBUTES) {
            val value = n.path(field)
            val blank = value.isMissingNode || value.isNull ||
                (value.isTextual && value.asText().isBlank()) ||
                ((value.isObject || value.isArray) && value.isEmpty)
            if (blank) gaps += DocumentGap(section, "$id: нет атрибута «$label»", "Приложение 2, атрибуты записи")
        }
        return n
    }

    /** Метод верификации: из закрывающего события, иначе из первого (CR-003). */
    private fun verificationMethod(r: JsonNode): String? {
        val events = r.path("verification_events")
        val chosen = events.firstOrNull { it.path("closes").asBoolean(false) } ?: events.firstOrNull()
        return chosen?.path("method")?.asText("")?.ifBlank { null }
    }

    // ---------- Приложение 3: ConOps ----------

    private fun fillConOps(section: Int, model: JsonNode, items: ArrayNode) {
        when (section) {
            1 -> model.path("needs").sortedBy { it.path("id").asText() }.forEach { nd ->
                val n = items.addObject()
                n.put("id", nd.path("id").asText())
                n.put("statement", nd.path("statement").asText(""))
                n.put("stakeholder", nd.path("stakeholder").path("name").asText(""))
                n.put("role", nd.path("stakeholder").path("role").asText(""))
            }
            2 -> components(model).filter { it.second.path("kind").asText() == "segment" }
                .forEach { (id, c) ->
                    val n = items.addObject()
                    n.put("id", id)
                    n.put("name", c.path("name").asText(""))
                    n.put("parent", c.path("parent").asText(""))
                }
            3 -> model.path("spacecraft").path("modes").forEach { m ->
                val n = items.addObject()
                n.put("name", m.path("name").asText(""))
                n.put("power_w", m.path("power_w").asDouble(0.0))
                n.put("orbit_fraction", m.path("orbit_fraction").asDouble(0.0))
            }
            // Шаг 17 C1: операционные сценарии — ХРАНИМЫЕ объекты conops,
            // а не поле, которого в модели никогда не было
            4 -> model.path("conops_scenarios").sortedBy { it.path("id").asText() }.forEach { co ->
                val n = items.addObject()
                n.put("id", co.path("id").asText())
                n.put("name", co.path("name").asText(""))
                n.put("kind", co.path("kind").asText(""))
                n.put("phase", co.path("phase").asText(""))
                n.put("success_criterion", co.path("success_criterion").asText(""))
                val flow = n.putArray("flow")
                co.path("flow").forEach(flow::add)
            }
            5 -> {
                model.path("constellation").takeIf { it.isObject && !it.isEmpty }?.let { c ->
                    val n = items.addObject()
                    n.put("kind", "orbit")
                    n.put("name", c.path("name").asText(""))
                    n.set<ObjectNode>("walker", c.path("walker").deepCopy())
                }
                model.path("ground_stations").path("stations").forEach { s ->
                    val n = items.addObject()
                    n.put("kind", "ground_station")
                    n.put("id", s.path("id").asText())
                    n.put("name", s.path("name").asText(""))
                    n.put("lat_deg", s.path("lat_deg").asDouble(0.0))
                    n.put("lon_deg", s.path("lon_deg").asDouble(0.0))
                }
            }
            6 -> model.path("operations_staffing").forEach(items::add)
            7 -> model.path("validations").sortedBy { it.path("id").asText() }.forEach { v ->
                val n = items.addObject()
                n.put("id", v.path("id").asText())
                n.put("target", v.path("target").asText(""))
                n.put("method", v.path("method").asText(""))
                n.put("approach", v.path("approach").asText(""))
                n.put("status", v.path("status").asText(""))
            }
            else -> {}
        }
    }

    // ---------- Приложение 4: описание архитектуры ----------

    private fun fillArchitecture(section: Int, model: JsonNode, items: ArrayNode) {
        val all = components(model)
        when (section) {
            1 -> all.filter { it.second.path("kind").asText() == "system" }.forEach { (id, c) ->
                val n = items.addObject()
                n.put("id", id)
                n.put("name", c.path("name").asText(""))
            }
            2 -> all.filter { it.second.path("kind").asText() !in setOf("interface", "system") }
                .forEach { (id, c) ->
                    val n = items.addObject()
                    n.put("id", id)
                    n.put("name", c.path("name").asText(""))
                    n.put("kind", c.path("kind").asText(""))
                    n.put("parent", c.path("parent").asText(""))
                }
            3 -> all.filter { it.second.path("kind").asText() != "interface" }.forEach { (id, c) ->
                val n = items.addObject()
                n.put("id", id)
                n.put("name", c.path("name").asText(""))
                n.put("segment", c.path("segment").asText(""))
                n.put("wbs", c.path("wbs").asText(""))
            }
            4 -> all.filter { it.second.path("kind").asText() == "interface" }.forEach { (id, c) ->
                val n = items.addObject()
                n.put("id", id)
                n.put("name", c.path("name").asText(""))
                val owners = n.putArray("owners")
                c.path("owners").forEach { owners.add(it.asText()) }
            }
            5 -> model.path("requirements").sortedBy { it.path("id").asText() }
                .filter { !it.path("allocated_to").isEmpty }
                .forEach { r ->
                    val n = items.addObject()
                    n.put("requirement", r.path("id").asText())
                    n.put("statement", r.path("statement").asText(""))
                    val to = n.putArray("allocated_to")
                    r.path("allocated_to").forEach { to.add(it.path("component").asText()) }
                }
            6 -> model.path("options").forEach { o ->
                val n = items.addObject()
                n.put("name", o.path("name").asText(""))
                o.properties().sortedBy { it.key }
                    .filter { it.key != "name" }
                    .forEach { (k, v) -> n.set<ObjectNode>(k, v.deepCopy()) }
            }
            7 -> model.path("budgets").forEach(items::add)
            else -> {}
        }
    }


    // ---------- Блок C: комплекты Д1–Д9 / Д1–Д10 ----------

    private fun goalsRecords(model: JsonNode, items: ArrayNode) =
        model.path("mission_goals").sortedBy { it.path("id").asText() }.forEach { g ->
            val n = items.addObject()
            n.put("id", g.path("id").asText())
            n.put("kind", g.path("kind").asText(""))
            n.put("statement", g.path("statement").asText(""))
            n.put("program_link", g.path("program_link").asText(""))
            val moe = n.putArray("moe")
            g.path("moe").forEach { m ->
                moe.addObject()
                    .put("id", m.path("id").asText())
                    .put("name", m.path("name").asText(""))
                    .set<ObjectNode>("target", m.path("target").deepCopy())
            }
        }

    private fun milestoneRecords(model: JsonNode, items: ArrayNode) {
        // Планирование длительностями: дата вехи — якорная due либо расчёт
        // цепочкой prev + duration_days (тот же вывод, что /views/gates).
        // Расчёт детерминирован: часов здесь нет, только данные модели.
        var prev: java.time.LocalDate? = null
        model.path("project").path("milestones").forEach { m ->
            val anchor = m.path("due").asText("").takeIf { it.isNotBlank() }
                ?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
            val duration = m.path("duration_days").takeIf { it.isInt }?.asInt()
            val effective = anchor
                ?: if (duration != null && prev != null) prev!!.plusDays(duration.toLong()) else null
            val n = items.addObject()
            n.put("gate", m.path("gate").asText())
            m.path("phase").asText("").takeIf { it.isNotBlank() }?.let { n.put("phase", it) }
            duration?.let { n.put("duration_days", it) }
            n.put("due", effective?.toString() ?: "")
            if (anchor == null && effective != null) n.put("computed", true)
            n.put("held", m.path("held").asBoolean(false))
            prev = effective ?: prev
        }
    }

    private fun costRecords(model: JsonNode, items: ArrayNode, kind: String?) =
        model.path("cost_estimates").sortedBy { it.path("id").asText() }
            .filter { kind == null || it.path("kind").asText() == kind }
            .forEach { c ->
                val n = items.addObject()
                n.put("id", c.path("id").asText())
                n.put("name", c.path("name").asText(""))
                n.put("kind", c.path("kind").asText(""))
                n.put("basis", c.path("basis").asText(""))
                n.set<ObjectNode>("total_low", c.path("total_low").deepCopy())
                n.set<ObjectNode>("total_high", c.path("total_high").deepCopy())
                c.path("schedule_months_low").takeIf { it.isInt }?.let { n.put("schedule_months_low", it.asInt()) }
                c.path("schedule_months_high").takeIf { it.isInt }?.let { n.put("schedule_months_high", it.asInt()) }
            }

    private fun wbsBreakdown(model: JsonNode, items: ArrayNode, kind: String?) =
        model.path("cost_estimates")
            .filter { kind == null || it.path("kind").asText() == kind }
            .sortedBy { it.path("id").asText() }
            .forEach { c ->
                c.path("items").forEach { i ->
                    val n = items.addObject()
                    n.put("estimate", c.path("id").asText())
                    n.put("wbs_ref", i.path("wbs_ref").asText(""))
                    n.put("name", i.path("name").asText(""))
                    n.set<ObjectNode>("low", i.path("low").deepCopy())
                    n.set<ObjectNode>("high", i.path("high").deepCopy())
                }
            }

    private fun riskRecords(model: JsonNode, items: ArrayNode) =
        model.path("risks").sortedBy { it.path("id").asText() }.forEach { r ->
            val n = items.addObject()
            n.put("id", r.path("id").asText())
            n.put("statement", r.path("statement").asText(""))
            n.put("category", r.path("category").asText(""))
            n.put("probability", r.path("probability").asInt(0))
            n.put("impact", r.path("impact").asInt(0))
            n.put("strategy", r.path("strategy").asText(""))
            n.put("owner", r.path("owner").asText(""))
            n.put("status", r.path("status").asText(""))
            r.path("due").takeIf { it.isTextual }?.let { n.put("due", it.asText()) }
        }

    private fun fillFad(section: Int, model: JsonNode, items: ArrayNode) {
        when (section) {
            1 -> model.path("project").takeIf { it.isObject && !it.isEmpty }?.let { p ->
                val n = items.addObject()
                n.put("project", p.path("name").asText(""))
                n.put("phase", p.path("phase").asText(""))
                model.path("mission_goals").forEach { g ->
                    val link = g.path("program_link").asText("")
                    if (link.isNotBlank()) n.withArray("program_links").add(link)
                }
            }
            3 -> model.path("requirements")
                .filter { it.path("level").asText() == "project" }
                .sortedBy { it.path("id").asText() }
                .forEach { r ->
                    items.addObject()
                        .put("id", r.path("id").asText())
                        .put("statement", r.path("statement").asText(""))
                }
            4 -> costRecords(model, items, "rom")
            5 -> milestoneRecords(model, items)
            else -> {}   // полномочия и подписи модель не хранит — честный разрыв
        }
    }

    private fun fillMissionConcept(section: Int, model: JsonNode, items: ArrayNode) {
        val all = components(model)
        when (section) {
            1 -> goalsRecords(model, items)
            2 -> model.path("alternatives").sortedBy { it.path("id").asText() }
                .filter { it.path("kind").asText() == "option" }
                .forEach { a ->
                    val n = items.addObject()
                    n.put("id", a.path("id").asText())
                    n.put("name", a.path("name").asText(""))
                    n.put("summary", a.path("summary").asText(""))
                    n.put("scenario_ref", a.path("scenario_ref").asText(""))
                    val cr = n.putArray("criteria")
                    a.path("criteria").forEach { c ->
                        cr.addObject()
                            .put("name", c.path("name").asText(""))
                            .put("score", c.path("score").asDouble(0.0))
                            .put("rationale", c.path("rationale").asText(""))
                    }
                }
            3 -> all.filter { it.second.path("kind").asText() in setOf("segment", "system") }
                .forEach { (id, c) ->
                    items.addObject()
                        .put("id", id)
                        .put("name", c.path("name").asText(""))
                        .put("kind", c.path("kind").asText(""))
                }
            4 -> model.path("conops_scenarios").sortedBy { it.path("id").asText() }.forEach { co ->
                items.addObject()
                    .put("id", co.path("id").asText())
                    .put("name", co.path("name").asText(""))
                    .put("kind", co.path("kind").asText(""))
            }
            5 -> {
                all.filter { it.second.path("kind").asText() in setOf("subsystem", "assembly") }
                    .forEach { (id, c) ->
                        items.addObject()
                            .put("kind", "component")
                            .put("id", id)
                            .put("name", c.path("name").asText(""))
                    }
                model.path("wbs_elements").sortedBy { it.path("code").asText() }.forEach { w ->
                    items.addObject()
                        .put("kind", "wbs")
                        .put("id", w.path("id").asText())
                        .put("code", w.path("code").asText(""))
                        .put("name", w.path("name").asText(""))
                }
            }
            6 -> model.path("constellation").takeIf { it.isObject && !it.isEmpty }?.let { c ->
                val n = items.addObject()
                n.put("name", c.path("name").asText(""))
                n.set<ObjectNode>("walker", c.path("walker").deepCopy())
            }
            7 -> fillTechnologyRecords(1, model, items, mutableListOf())
            8 -> riskRecords(model, items)
            9 -> model.path("alternatives")
                .filter { it.path("kind").asText() == "descope" }
                .sortedBy { it.path("id").asText() }
                .forEach { a ->
                    items.addObject()
                        .put("id", a.path("id").asText())
                        .put("name", a.path("name").asText(""))
                        .put("summary", a.path("summary").asText(""))
                        .put("consequences", a.path("consequences").asText(""))
                }
            10 -> costRecords(model, items, "rom")
            11 -> model.path("decisions").sortedBy { it.path("id").asText() }
                .filter { it.path("status").asText() == "decided" }
                .forEach { d ->
                    items.addObject()
                        .put("id", d.path("id").asText())
                        .put("question", d.path("question").asText(""))
                        .put("selected", d.path("selected").asText(""))
                        .put("rationale", d.path("rationale").asText(""))
                }
            else -> {}
        }
    }

    private fun fillRequirementDraft(section: Int, model: JsonNode, items: ArrayNode) {
        if (section != 1) return
        // черновик допускает TBD и неполноту (Прил. 4 БП-PPA) — разрывы
        // атрибутов здесь не пишутся, в отличие от спецификации Прил. 2
        model.path("requirements")
            .filter { it.path("level").asText() == "project" }
            .sortedBy { it.path("id").asText() }
            .forEach { r ->
                val n = items.addObject()
                n.put("id", r.path("id").asText())
                n.put("statement", r.path("statement").asText(""))
                n.put("category", r.path("category").asText(""))
                n.put("source", r.path("traces_up").joinToString(", ") { it.path("ref").asText() })
                r.path("mop").takeIf { it.isObject && !it.isEmpty }?.let { n.set<ObjectNode>("mop", it.deepCopy()) }
                n.put("verification_method", verificationMethod(r) ?: "")
                n.put("status", r.path("lifecycle").path("status").asText(""))
            }
    }

    private fun fillTechnologyRecords(
        section: Int,
        model: JsonNode,
        items: ArrayNode,
        gaps: MutableList<DocumentGap>,
    ) {
        if (section != 1) return
        model.path("technologies").sortedBy { it.path("id").asText() }.forEach { t ->
            val id = t.path("id").asText()
            val n = items.addObject()
            n.put("id", id)
            n.put("name", t.path("name").asText(""))
            val comps = n.putArray("components")
            t.path("components").forEach { comps.add(it.asText()) }
            n.put("trl_current", t.path("trl_current").asInt(0))
            n.put("trl_required", t.path("trl_required").asInt(0))
            n.put("gate", t.path("gate").asText(""))
            n.put("maturation_plan", t.path("maturation_plan").asText(""))
            if (t.path("maturation_plan").asText("").isBlank() &&
                t.path("trl_current").asInt(0) < t.path("trl_required").asInt(0)
            ) {
                gaps += DocumentGap(section, "$id: разрыв TRL без плана созревания", "Приложение 6, атрибуты записи")
            }
        }
    }

    private fun fillRiskRecords(section: Int, model: JsonNode, items: ArrayNode) {
        if (section == 1) riskRecords(model, items)
    }

    private fun fillOda(section: Int, model: JsonNode, items: ArrayNode) {
        val assessments = model.path("oda_assessments").sortedBy { it.path("id").asText() }
        when (section) {
            1 -> assessments.forEach { o ->
                val n = items.addObject()
                n.put("id", o.path("id").asText())
                n.put("kind", o.path("kind").asText(""))
                n.set<ObjectNode>("deorbit_years", o.path("deorbit_years").deepCopy())
                o.path("casualty_risk").takeIf { it.isObject }?.let { n.set<ObjectNode>("casualty_risk", it.deepCopy()) }
            }
            2 -> assessments.forEach { o ->
                o.path("findings").forEach { f ->
                    items.addObject()
                        .put("assessment", o.path("id").asText())
                        .put("rule", f.path("rule").asText(""))
                        .put("compliant", f.path("compliant").asBoolean(false))
                        .put("note", f.path("note").asText(""))
                }
            }
            else -> {}
        }
    }

    private fun fillCost(section: Int, model: JsonNode, items: ArrayNode, kind: String) {
        when (section) {
            1 -> costRecords(model, items, kind)
            2 -> wbsBreakdown(model, items, kind)
            else -> {}
        }
    }

    private fun fillFormulationAgreement(section: Int, model: JsonNode, items: ArrayNode) {
        when (section) {
            1 -> model.path("project").takeIf { it.isObject && !it.isEmpty }?.let { p ->
                items.addObject()
                    .put("project", p.path("name").asText(""))
                    .put("phase", p.path("phase").asText(""))
            }
            2 -> {
                model.path("wbs_elements").sortedBy { it.path("code").asText() }.forEach { w ->
                    items.addObject()
                        .put("kind", "work")
                        .put("code", w.path("code").asText(""))
                        .put("name", w.path("name").asText(""))
                }
                model.path("technologies")
                    .filter { it.path("trl_current").asInt(0) < it.path("trl_required").asInt(0) }
                    .forEach { t ->
                        items.addObject()
                            .put("kind", "maturation")
                            .put("id", t.path("id").asText())
                            .put("name", t.path("name").asText(""))
                    }
            }
            4 -> milestoneRecords(model, items)
            5 -> costRecords(model, items, null)
            else -> {}
        }
    }

    private fun fillSemp(section: Int, model: JsonNode, items: ArrayNode) {
        when (section) {
            1 -> model.path("project").takeIf { it.isObject && !it.isEmpty }?.let { p ->
                items.addObject()
                    .put("project", p.path("name").asText(""))
                    .put("phase", p.path("phase").asText(""))
            }
            2 -> components(model).filter { it.second.path("kind").asText() == "system" }
                .forEach { (id, c) ->
                    items.addObject().put("id", id).put("name", c.path("name").asText(""))
                }
            5 -> milestoneRecords(model, items)
            else -> {}   // организация, процессы, tailoring, подписи — вне модели
        }
    }

    private fun fillTechnologyPlan(
        section: Int,
        model: JsonNode,
        items: ArrayNode,
        gaps: MutableList<DocumentGap>,
    ) {
        val technologies = model.path("technologies").sortedBy { it.path("id").asText() }
        when (section) {
            1 -> fillTechnologyRecords(1, model, items, gaps)
            2 -> technologies.forEach { t ->
                items.addObject()
                    .put("id", t.path("id").asText())
                    .put("name", t.path("name").asText(""))
                    .put("maturation_plan", t.path("maturation_plan").asText(""))
                    .put("gate", t.path("gate").asText(""))
            }
            4 -> technologies
                .filter { it.path("risk_ref").asText("").isNotBlank() }
                .forEach { t ->
                    items.addObject()
                        .put("id", t.path("id").asText())
                        .put("risk_ref", t.path("risk_ref").asText(""))
                }
            else -> {}
        }
    }

    private fun fillRiskPlan(section: Int, model: JsonNode, items: ArrayNode) {
        val risks = model.path("risks")
        when (section) {
            2 -> {
                // матрица критичности 5×5 счётом — «шкалы и матрица» из данных
                val counts = HashMap<Pair<Int, Int>, Int>()
                risks.forEach { r ->
                    val key = r.path("probability").asInt(0) to r.path("impact").asInt(0)
                    counts[key] = (counts[key] ?: 0) + 1
                }
                counts.toSortedMap(compareBy({ it.first }, { it.second })).forEach { (k, v) ->
                    items.addObject()
                        .put("probability", k.first)
                        .put("impact", k.second)
                        .put("risks", v)
                }
            }
            3 -> risks.map { it.path("owner").asText("") }.filter { it.isNotBlank() }
                .distinct().sorted().forEach { owner -> items.addObject().put("owner", owner) }
            4 -> riskRecords(model, items)
            else -> {}
        }
    }

    private fun fillProjectPlan(
        section: Int,
        model: JsonNode,
        items: ArrayNode,
        gaps: MutableList<DocumentGap>,
    ) {
        when (section) {
            1 -> {
                goalsRecords(model, items)
                model.path("requirements")
                    .filter { it.path("level").asText() == "project" }
                    .sortedBy { it.path("id").asText() }
                    .forEach { r ->
                        items.addObject()
                            .put("id", r.path("id").asText())
                            .put("statement", r.path("statement").asText(""))
                    }
            }
            3 -> model.path("wbs_elements").sortedBy { it.path("code").asText() }.forEach { w ->
                items.addObject()
                    .put("id", w.path("id").asText())
                    .put("code", w.path("code").asText(""))
                    .put("name", w.path("name").asText(""))
                    .put("owner", w.path("owner").asText(""))
            }
            4 -> {
                // График по фазам — ВЕСЬ жизненный цикл ЕДИНЫМ рядом
                // контрольных точек (замечание прогона: «проект идёт через
                // контрольные точки», а не через текстовые приписки сбоку).
                // Вехи Phase B–F добавляются кнопкой на ленте цикла; ИС их
                // показывает, но не проводит (ворот к ним нет).
                milestoneRecords(model, items)
                val known = orbita.req.Gates().gateNames
                val beyond = model.path("project").path("milestones")
                    .any { it.path("gate").asText() !in known }
                if (!beyond) {
                    gaps += DocumentGap(
                        section,
                        "проект: план обрывается горизонтом Формулирования — добавьте " +
                            "вехи Phase B–F («Жизненный цикл» → «+ вехи Phase B–F»)",
                        "Приложение 7 §4: укрупнённый план по фазам всего жизненного цикла",
                    )
                }
            }
            5 -> costRecords(model, items, null)
            6 -> riskRecords(model, items)
            else -> {}
        }
    }

    /** Элементы архитектуры в устойчивом порядке: словарь идентификатор → объект. */
    private fun components(model: JsonNode): List<Pair<String, JsonNode>> {
        val node = model.path("components")
        return when {
            node.isObject -> node.properties().map { it.key to it.value }.sortedBy { it.first }
            node.isArray -> node.map { it.path("id").asText() to it }.sortedBy { it.first }
            else -> emptyList()
        }
    }

    private fun digestOf(body: JsonNode): String =
        MessageDigest.getInstance("SHA-256").digest(canonical(body).toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }

    /** Каноническая форма: порядок полей не влияет на слепок. */
    private fun canonical(node: JsonNode): String = when {
        node.isObject -> node.properties().sortedBy { it.key }
            .joinToString(",", "{", "}") { (k, v) -> "\"$k\":${canonical(v)}" }
        node.isArray -> node.joinToString(",", "[", "]") { canonical(it) }
        node.isTextual -> "\"${node.asText()}\""
        node.isNull -> "null"
        else -> node.asText()
    }
}
