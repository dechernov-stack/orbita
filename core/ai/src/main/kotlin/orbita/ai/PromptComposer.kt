// Сборка промпта СЛУЖБОЙ (П5 задания «прогон до KDP B»).
//
// Промптов в коде и скриптах нет: текст задания собирается из профиля
// (ограничения инженера объектом), состояния модели (что уже есть в проекте)
// и вида пакета (что требуется получить). Промпт, вписанный в код, — второй
// свод правил рядом с профилем, и расходятся они молча.
package orbita.ai

import com.fasterxml.jackson.databind.JsonNode

/** Профиль службы, разобранный из объекта AP-NNNN. */
data class AiProfile(
    val id: String,
    val version: String,
    val name: String,
    val purpose: String?,
    val kinds: List<String>,
    val transport: String,
    val modelHint: String?,
    val statementRules: List<String>,
    val glossary: List<Triple<String, String, String?>>,
    val prohibitions: List<String>,
    val requireSource: Boolean,
    val reviewOnly: Boolean,
) {
    /** Вид пакета разрешён профилем; иначе служба вызов не делает. */
    fun allows(kind: String): Boolean = kind in kinds

    companion object {
        fun of(doc: JsonNode): AiProfile = AiProfile(
            id = doc.path("id").asText(),
            version = doc.path("lifecycle").path("version").asText("1"),
            name = doc.path("name").asText(""),
            purpose = doc.path("purpose").asText("").ifBlank { null },
            kinds = doc.path("kinds").map { it.asText() },
            transport = doc.path("transport").asText("package"),
            modelHint = doc.path("model_hint").asText("").ifBlank { null },
            statementRules = doc.path("statement_rules").map { it.asText() },
            glossary = doc.path("glossary").map {
                Triple(
                    it.path("term").asText(),
                    it.path("meaning").asText(),
                    it.path("not").asText("").ifBlank { null },
                )
            },
            prohibitions = doc.path("prohibitions").map { it.asText() },
            requireSource = doc.path("require_source").asBoolean(true),
            reviewOnly = doc.path("review_only").asBoolean(false),
        )
    }
}

/**
 * Состояние модели, попадающее в промпт: чем служба располагает и на что
 * обязана ссылаться. Не вся модель — только то, что относится к виду пакета:
 * пакет с полным проектом внутри и дороже, и хуже.
 */
data class ModelContext(
    val projectName: String,
    val phase: String,
    /** Что уже есть: вид → перечень «идентификатор — подпись». */
    val existing: Map<String, List<String>>,
    /** Постановка миссии либо иной вход операции — от инженера. */
    val statement: String,
)

class PromptComposer(private val kinds: PackageKinds = PackageKinds.default()) {

    /**
     * Задание для модели. Порядок частей постоянен: роль и назначение →
     * ограничения проекта → глоссарий → что уже есть → что требуется →
     * форма ответа. Схема ответа передаётся полем пакета, а не текстом
     * (TZ-AI-001, ловушка 5) — здесь на неё только ссылка.
     */
    fun compose(kind: String, profile: AiProfile, context: ModelContext): String {
        val k = kinds.of(kind)
        require(profile.allows(kind)) {
            "профиль ${profile.id} не разрешает вид пакета '$kind' (разрешены: ${profile.kinds})"
        }
        return buildString {
            appendLine("Ты — инженерная служба проекта «${context.projectName}», фаза ${context.phase}.")
            profile.purpose?.let { appendLine("Назначение профиля ${profile.id}: $it.") }
            appendLine()

            appendLine("ОГРАНИЧЕНИЯ ПРОЕКТА (нарушать нельзя):")
            if (profile.prohibitions.isEmpty()) appendLine("— не заданы")
            profile.prohibitions.forEach { appendLine("— $it") }
            appendLine()

            if (profile.statementRules.isNotEmpty()) {
                appendLine("ПРАВИЛА ФОРМУЛИРОВОК:")
                profile.statementRules.forEach { appendLine("— $it") }
                appendLine()
            }

            if (profile.glossary.isNotEmpty()) {
                appendLine("ГЛОССАРИЙ (термины употреблять только в этом значении):")
                profile.glossary.forEach { (term, meaning, not) ->
                    appendLine("— $term: $meaning" + (not?.let { "; не путать с $it" } ?: ""))
                }
                appendLine()
            }

            if (profile.requireSource) {
                appendLine("ОСНОВАНИЕ ЗНАЧЕНИЙ: каждое числовое значение сопровождай источником —")
                appendLine("ссылкой на объект модели, документ или расчёт. Значение без основания")
                appendLine("будет снято фильтром и вернётся инженеру на ручное решение.")
                appendLine()
            }

            appendLine("ЧТО УЖЕ ЕСТЬ В ПРОЕКТЕ:")
            if (context.existing.isEmpty()) appendLine("— проект пуст")
            context.existing.forEach { (kindName, items) ->
                appendLine("$kindName (${items.size}):")
                items.take(MAX_LISTED).forEach { appendLine("  · $it") }
                if (items.size > MAX_LISTED) appendLine("  · … ещё ${items.size - MAX_LISTED}")
            }
            appendLine()

            appendLine("ВХОД ОПЕРАЦИИ:")
            appendLine(context.statement)
            appendLine()

            appendLine("ЗАДАНИЕ: из входа «${k.input}» получи «${k.output}».")
            if (profile.reviewOnly) {
                appendLine("Профиль рецензионный: новых объектов не создавай — верни замечания")
                appendLine("к существующим формулировкам с предлагаемой правкой поля.")
            }
            appendLine("Ответ — массив объектов строго по схеме ответа пакета (поле response_schema).")
            appendLine("Ничего, кроме массива JSON, в ответе быть не должно.")
        }
    }

    private companion object {
        /** Сколько существующих объектов вида перечислять: длинный список дорожает. */
        const val MAX_LISTED = 40
    }
}
