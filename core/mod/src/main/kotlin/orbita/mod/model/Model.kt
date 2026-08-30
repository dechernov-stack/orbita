// Типы модели данных из схем (STEP-1 §1.2). Нормативный источник структуры —
// schemas/ (TZ-MOD-001); при расхождении типов и схем дефектны типы.
package orbita.mod.model

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/** Статусная модель управляемого объекта (TZ-COM-003, schemas/common/status.schema.json). */
enum class Lifecycle { Draft, Preliminary, Approved, Baseline, Cancelled }

/** Классы потребителей A'/B'/C' — не путать с классами устройств LoRaWAN A/B/C (CLAUDE.md §3). */
enum class ConsumerClass { A_prime, B_prime, C_prime }

/** Типы объектов ядра цифровой нити (схемы в schemas/core, TZ-MOD-007). */
enum class CoreType(val idPrefix: String, val dbType: String, val schemaName: String) {
    Need("ND", "need", "core/need"),
    Service("SV", "service", "core/service"),
    Requirement("RQ", "requirement", "core/requirement"),
    Component("CM", "component", "core/component"),
    Scenario("SC", "scenario", "core/scenario"),
    // CR-003/ADR-019: свидетельство, валидация и интерфейс — самостоятельные объекты
    Evidence("EV", "evidence", "core/evidence"),
    Validation("VA", "validation", "core/validation"),
    // Своя схема (Шаг 16): правило «ровно две стороны» жило без схемы, и форма,
    // построенная по схеме компонента, не давала завести интерфейс вовсе
    Interface("IF", "interface", "core/interface"),
    // Шаг 7: реестр рисков — входной материал MCR и KDP (NPR 8000.4)
    Risk("RSK", "risk", "core/risk"),

    // CR-005/ADR-021: входы моделирования — хранимые объекты со статусной
    // моделью и версионностью. Схемы остаются на прежних местах: они описывают
    // и обмен между модулями, и хранение; дублировать их в core/ значило бы
    // завести две структуры, которые разойдутся.
    Constellation("CN", "constellation", "core/constellation"),
    Spacecraft("SP", "spacecraft", "contracts/spacecraft"),
    DemandMap("DM", "demand_map", "contracts/demand-map"),
    TerminalProfile("TP", "terminal_profile", "contracts/terminal-profile"),
    GroundStations("GS", "ground_stations", "core/ground-stations"),
    ProtocolAdapter("PA", "protocol_adapter", "contracts/protocol-adapter"),

    // Блок C (Шаг 17): виды, на которые модель уже ссылалась в пустоту —
    // conops_ref валидации, реестр ворот без проекта, документы без статуса.
    Conops("CO", "conops", "core/conops"),
    Technology("TL", "technology", "core/technology"),
    Decision("DN", "decision", "core/decision"),
    Project("PJ", "project", "core/project"),
    DocumentIssue("DI", "document_issue", "core/document-issue"),

    // Блок C задания «прогон до KDP B»: материал точек — виды, на которые
    // регламенты ссылались, а система не хранила (цели и MOE, альтернативы,
    // стоимость, ODA, замечания обзора, WBS).
    MissionGoal("MG", "mission_goal", "core/mission-goal"),
    Alternative("AL", "alternative", "core/alternative"),
    CostEstimate("CE", "cost_estimate", "core/cost-estimate"),
    Oda("OD", "oda", "core/oda"),
    ReviewItem("RF", "review_item", "core/review-item"),
    WbsElement("WB", "wbs_element", "core/wbs-element"),

    // П5: профиль службы ИИ — ограничения инженера объектом, не текстом в коде
    AiProfile("AP", "ai_profile", "core/ai-profile"),

    // ADR-030: исходный документ (записка, стандарт) — библиотека материала,
    // из которого рождается постановка; текст с реквизитами, не бинарь
    SourceDocument("SD", "source_document", "core/source-document"),

    // Библиотека (СТРУКТУРА-БИБЛИОТЕКИ §2): три формы хранения, не вид на
    // каждую полку. Типизированные объекты — там, где структура нужна ИИ и
    // проверкам; фрагмент — одна форма на Б1/Б2/Б5/Б6/Б7/Г1.
    NormativeDocument("NR", "normative_document", "core/normative-document"),
    MissionClass("MC", "mission_class", "core/mission-class"),
    StakeholderProfile("SH", "stakeholder_profile", "core/stakeholder-profile"),
    TypicalRisk("TR", "typical_risk", "core/typical-risk"),
    LibraryFragment("LF", "library_fragment", "core/library-fragment"),
    // Нитка Б.1: шаблон документа — библиотечный объект, не enum в коде
    DocumentTemplate("DT", "document_template", "core/document-template"),
    // В1.2: авторский текст раздела — хранимый и версионируемый
    SectionText("ST", "section_text", "core/section-text"),
    // В2.1: вхождение — определение × количество × роль в родителе
    ComponentUsage("CU", "component_usage", "core/component-usage"),
    // Т-1: сохранённый вид реестра — серверный объект, переживает перезаход
    SavedView("VW", "saved_view", "core/saved-view"),

    // МВП-П1 (процесс к точке): задание = адресованный разрыв готовности.
    // Статусной модели нет — статус вычисляется закрытием разрыва.
    Task("TS", "task", "core/task"),

    // Справочник единиц (решение ранга ADR): полка LIB, один на систему.
    UnitRegistry("UR", "unit_registry", "core/unit-registry"),

    // Глоссарий (Ф-03): полка LIB — один источник смысловых подсказок.
    Glossary("GL", "glossary", "core/glossary"),
    GeoMask("GM", "geo_mask", "core/geo-mask"),
    PropertyForm("PF", "property_form", "core/property-form"),

    // Ф-13: стейкхолдер проекта. Профиль (SH) — шаблон класса миссии на полке;
    // этот объект — факт проекта, на него ссылаются нужды и сервисы.
    Stakeholder("SK", "stakeholder", "core/stakeholder");

    companion object {
        fun byDbType(t: String): CoreType = entries.firstOrNull { it.dbType == t }
            ?: throw IllegalArgumentException("unknown core object type: $t")

        /** Тип по префиксу идентификатора; null — префикс неизвестен. */
        fun byIdPrefix(prefix: String): CoreType? = entries.firstOrNull { it.idPrefix == prefix }
    }
}

/** Идентификатор объекта ядра: <ПРЕФИКС>-NNNN, стабилен, не переиспользуется (TZ-MOD-007). */
@JvmInline
value class ObjectId(val value: String) {
    init {
        require(PATTERN.matches(value)) {
            "TZ-MOD-007: invalid object id '$value', expected " +
                "(${CoreType.entries.joinToString("|") { it.idPrefix }})-NNNN"
        }
    }

    val type: CoreType get() = CoreType.entries.first { it.idPrefix == value.substringBefore('-') }

    override fun toString(): String = value

    companion object {
        // Шаблон выводится из состава типов: добавление вида объекта не должно
        // требовать правки регулярного выражения во втором месте.
        val PATTERN = Regex("^(${CoreType.entries.joinToString("|") { it.idPrefix }})-[0-9]{4}$")
    }
}

/**
 * Происхождение значения (TZ-COM-005, schemas/common/provenance.schema.json).
 * Значение без происхождения не существует на уровне типов (TZ-MOD-004):
 * у [Quantity] нет конструктора без provenance.
 */
sealed interface Provenance {
    val source: String

    data class Manual(val author: String? = null, val timestamp: String? = null) : Provenance {
        override val source get() = "manual"
    }

    /** Для расчётных значений обязательна привязка к модулю и версии входов (TZ-COM-006). */
    data class Computed(
        val module: String,
        val moduleVersion: String? = null,
        val inputVersion: String? = null,
    ) : Provenance {
        override val source get() = "computed"
    }

    /** Предложение ИИ несёт явный признак акцепта; до акцепта не влияет на расчёты (TZ-AI-004). */
    data class AiProposed(
        val promptPackageId: String,
        val accepted: Boolean,
        val acceptedBy: String? = null,
        val llm: String? = null,
        val edited: Boolean? = null,
    ) : Provenance {
        override val source get() = "ai_proposed"
    }

    data class Imported(val author: String? = null) : Provenance {
        override val source get() = "imported"
    }

    /** Сериализация в структуру schemas/common/provenance.schema.json. */
    fun toJson(mapper: ObjectMapper): ObjectNode {
        val n = mapper.createObjectNode()
        n.put("source", source)
        when (this) {
            is Manual -> {
                author?.let { n.put("author", it) }
                timestamp?.let { n.put("timestamp", it) }
            }
            is Computed -> {
                n.put("module", module)
                moduleVersion?.let { n.put("module_version", it) }
                inputVersion?.let { n.put("input_version", it) }
            }
            is AiProposed -> {
                val ai = n.putObject("ai")
                ai.put("prompt_package_id", promptPackageId)
                ai.put("accepted", accepted)
                acceptedBy?.let { ai.put("accepted_by", it) }
                llm?.let { ai.put("llm", it) }
                edited?.let { ai.put("edited", it) }
            }
            is Imported -> author?.let { n.put("author", it) }
        }
        return n
    }
}

/**
 * Величина: значение + обязательная единица + происхождение (TZ-MOD-004, TZ-COM-005).
 * Конструктора без единицы или без происхождения не существует; пустая единица
 * отклоняется при создании, а не постфактум (STEP-1, ловушка 2).
 * Единицы — СИ; углы хранятся в радианах, отображаются в градусах (CLAUDE.md §3).
 */
data class Quantity(
    val value: Double,
    val unit: String,
    val provenance: Provenance,
    val tolerance: Double? = null,
    val marginPct: Double? = null,
) {
    init {
        require(unit.isNotBlank()) { "TZ-MOD-004: unit must not be blank" }
        require(value.isFinite()) { "TZ-MOD-004: value must be finite" }
    }

    fun toJson(mapper: ObjectMapper): ObjectNode {
        val n = mapper.createObjectNode()
        n.put("value", value)
        n.put("unit", unit)
        tolerance?.let { n.put("tolerance", it) }
        marginPct?.let { n.put("margin_pct", it) }
        n.set<ObjectNode>("provenance", provenance.toJson(mapper))
        return n
    }

    /** Отображаемое значение угла в градусах; допустимо только для unit=rad. */
    fun displayDegrees(): Double {
        require(unit == "rad") { "displayDegrees is defined for unit 'rad', got '$unit'" }
        return Math.toDegrees(value)
    }

    companion object {
        /** Ввод угла в градусах конвертируется на границе; хранение — в rad (CLAUDE.md §3). */
        fun angleFromDegrees(degrees: Double, provenance: Provenance): Quantity =
            Quantity(Math.toRadians(degrees), "rad", provenance)
    }
}
