// Контроль качества формулировок (TZ-REQ-004). Поведение — эталон
// spec/requirements_semantics.py::check_quality, один в один. Нарушения не
// блокируют Draft, но блокируют переход в Baseline (Baselining).
package orbita.req

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path

/**
 * Перечень правил конфигурируем без изменения кода: ресурс по умолчанию,
 * переопределение — файлом из переменной окружения ORBITA_QUALITY_RULES.
 */
data class QualityRules(
    val modalWords: List<String>,
    val conjunctionRegexes: List<String>,
    val vagueWords: List<String>,
    val measuredCategories: Set<String>,
) {
    companion object {
        private val mapper = ObjectMapper()

        fun default(): QualityRules {
            System.getenv("ORBITA_QUALITY_RULES")?.let { return fromJson(Files.readString(Path.of(it))) }
            val res = QualityRules::class.java.getResourceAsStream("/orbita/req/quality-rules.json")
                ?: error("quality-rules.json resource is missing")
            return res.use { fromJson(it.readAllBytes().decodeToString()) }
        }

        fun fromJson(json: String): QualityRules {
            val n = mapper.readTree(json)
            fun list(key: String) = n.path(key).map { it.asText() }
            return QualityRules(list("modal_words"), list("conjunction_regexes"),
                list("vague_words"), list("measured_categories").toSet())
        }
    }
}

class QualityControl(private val rules: QualityRules = QualityRules.default()) {

    private val conjunctions = rules.conjunctionRegexes.map { Regex(it) }

    /** Список нарушений с указанием фрагмента. Пустой список = пригодно к базированию. */
    fun check(req: JsonNode): List<String> {
        val violations = mutableListOf<String>()
        val text = req.path("statement").asText("")
        val low = text.lowercase()
        if (rules.modalWords.none { it in low }) {
            violations += "нет модального «должна»"
        }
        // конъюнкция в нормативной части: два независимых требования в одном
        if (conjunctions.any { it.containsMatchIn(low) }) {
            violations += "конъюнкция: разделить на отдельные требования"
        }
        rules.vagueWords.firstOrNull { it in low }?.let {
            violations += "неизмеримое определение: «$it»"
        }
        val category = req.path("category").asText("")
        if (category in rules.measuredCategories && !hasMop(req)) {
            violations += "категория $category требует измеримого показателя (MOP)"
        }
        if (text.isBlank()) {
            violations += "пустая формулировка"
        }
        // CR-001: формулировка и оператор условия не должны противоречить
        if (hasMop(req)) {
            violations += validateMop(req.path("mop")).map { "условие: $it" }
            statementMatchesOperator(text, req.path("mop"))?.let { violations += it }
        }
        return violations
    }

    private fun hasMop(req: JsonNode): Boolean =
        req.path("mop").let { !it.isMissingNode && !it.isNull && !(it.isObject && it.isEmpty) }
}

/** Незакрытые TBD/TBR показателя (TZ-REQ-006, TZ-REQ-008). */
fun hasOpenTbd(req: JsonNode): Boolean =
    req.path("mop").let { it.path("tbd").asBoolean(false) || it.path("tbr").asBoolean(false) }
