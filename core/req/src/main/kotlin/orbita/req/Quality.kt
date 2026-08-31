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
    /** L-C6: слова цели — целям не место среди требований (П18). */
    val goalWords: List<String> = emptyList(),
    /** L-C4: негативная форма, где выразимо позитивно (П17). */
    val negativeWords: List<String> = emptyList(),
    /** L-C3: начало формулировки не от носителя — пассив или безличность (П13). */
    val passiveStarts: List<String> = emptyList(),
) {
    companion object {
        private val mapper = ObjectMapper()

        fun default(): QualityRules {
            System.getenv("ORBITA_QUALITY_RULES")?.let { return fromJson(Files.readString(Path.of(it))) }
            val res = QualityRules::class.java.getResourceAsStream("/orbita/req/quality-rules.json")
                ?: error("quality-rules.json resource is missing")
            return res.use { fromJson(it.readAllBytes().decodeToString()) }
        }

        /**
         * Правила из документа полки (quality_dictionary). Полка ПЕРЕКРЫВАЕТ
         * ресурс целиком: пустой список означает «правило не проверяется», а
         * не «взять умолчание» — тайно вернувшееся умолчание объясняло бы
         * пометы, которых инженер не заводил.
         */
        fun fromShelf(doc: JsonNode): QualityRules {
            fun list(key: String) = doc.path(key).map { it.asText() }
            return QualityRules(
                list("modal_words"), list("conjunction_regexes"),
                list("vague_words"), list("measured_categories").toSet(),
                goalWords = list("goal_words"),
                negativeWords = list("negative_words"),
                passiveStarts = list("passive_starts"),
            )
        }

        fun fromJson(json: String): QualityRules {
            val n = mapper.readTree(json)
            fun list(key: String) = n.path(key).map { it.asText() }
            return QualityRules(
                list("modal_words"), list("conjunction_regexes"),
                list("vague_words"), list("measured_categories").toSet(),
                goalWords = list("goal_words"),
                negativeWords = list("negative_words"),
                passiveStarts = list("passive_starts"),
            )
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

    /**
     * Мягкие пометы формулировки (L-C1…L-C6, NASA SEH App. C).
     *
     * Не запрет, а совет: базирование они не держат — держит `check`. Текст
     * пометы говорит, ЧТО не так и что с этим делать, а не называет правило
     * кодом: инженер читает подсказку, а не расшифровывает шифр.
     */
    fun lint(req: JsonNode): List<LintNote> {
        val notes = mutableListOf<LintNote>()
        val text = req.path("statement").asText("")
        if (text.isBlank()) return notes
        val low = text.lowercase()

        if (conjunctions.any { it.containsMatchIn(low) }) {
            notes += LintNote("L-C1", "возможно, два требования в одном — рассмотрите разделение")
        }
        rules.vagueWords.firstOrNull { it in low }?.let {
            notes += LintNote("L-C2", "неопределённое слово «$it» — уточните")
        }
        if (rules.passiveStarts.any { low.trimStart().startsWith(it) }) {
            notes += LintNote("L-C3", "пассив или безличная форма — перепишите от изделия или стороны")
        }
        rules.negativeWords.firstOrNull { it in low }?.let {
            notes += LintNote("L-C4", "негативная форма «$it» — выразима ли позитивно?")
        }
        if (hasOpenTbd(req)) {
            val владелец = req.path("mop").path("tbd_owner").asText("")
                .ifBlank { req.path("owner").asText("") }
            val срок = req.path("mop").path("tbd_due").asText("")
            if (владелец.isBlank() || срок.isBlank()) {
                notes += LintNote(
                    "L-C5",
                    "канон TBR: назовите, что сделать, кто отвечает и к какому сроку" +
                        (if (владелец.isBlank()) "; владелец не назван" else "") +
                        (if (срок.isBlank()) "; срок устранения не назван" else ""),
                )
            }
        }
        rules.goalWords.firstOrNull { it in low }?.let {
            notes += LintNote("L-C6", "«$it» — это цель, а не требование: вынесите отдельным классом")
        }
        return notes
    }
}

/** Мягкая помета формулировки: код правила и человеческий текст. */
data class LintNote(val id: String, val text: String)

/** Незакрытые TBD/TBR показателя (TZ-REQ-006, TZ-REQ-008). */
fun hasOpenTbd(req: JsonNode): Boolean =
    req.path("mop").let { it.path("tbd").asBoolean(false) || it.path("tbr").asBoolean(false) }
