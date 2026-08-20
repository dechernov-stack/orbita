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
    Interface("IF", "interface", "core/component"),
    // Шаг 7: реестр рисков — входной материал MCR и KDP (NPR 8000.4)
    Risk("RSK", "risk", "core/risk");

    companion object {
        fun byDbType(t: String): CoreType = entries.firstOrNull { it.dbType == t }
            ?: throw IllegalArgumentException("unknown core object type: $t")
    }
}

/** Идентификатор объекта ядра: ND-/SV-/RQ-/CM-/SC-NNNN, стабилен, не переиспользуется (TZ-MOD-007). */
@JvmInline
value class ObjectId(val value: String) {
    init {
        require(PATTERN.matches(value)) {
            "TZ-MOD-007: invalid object id '$value', expected (ND|SV|RQ|CM|SC|EV|VA|IF|RSK)-NNNN"
        }
    }

    val type: CoreType get() = CoreType.entries.first { it.idPrefix == value.substringBefore('-') }

    override fun toString(): String = value

    companion object {
        val PATTERN = Regex("^(ND|SV|RQ|CM|SC|EV|VA|IF|RSK)-[0-9]{4}$")
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
