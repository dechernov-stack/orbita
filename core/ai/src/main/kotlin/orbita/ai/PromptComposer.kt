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
    fun compose(
        kind: String,
        profile: AiProfile,
        context: ModelContext,
        /**
         * Схема ответа. В закрытом контуре её несёт сам пакет (инженер копирует
         * пакет целиком), а прямой канал шлёт модели ТОЛЬКО текст — и без схемы
         * модель отвечает своей формой: первый же живой вызов вернул выдуманные
         * поля и префикс MO- вместо MG-. Схема берётся из реестра, второй копии
         * нет: это не «промпт в коде», а тот же артефакт пакета.
         */
        responseSchema: JsonNode? = null,
    ): String {
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
                appendLine("ОСНОВАНИЕ ЗНАЧЕНИЙ. Каждое числовое значение обязано нести")
                appendLine("происхождение одного из двух видов:")
                appendLine("  · расчётное — \"provenance\": {\"source\": \"computed\", \"module\": <модуль>},")
                appendLine("    где модуль один из: ballistics, spacecraft, consumers, protocol, flows, cost;")
                appendLine("  · из источника — \"provenance\": {\"source\": \"imported\", \"import\":")
                appendLine("    {\"dataset\": <наименование источника>, \"dataset_version\": <версия>,")
                appendLine("     \"retrieved_at\": <дата>, \"terms\": <условия использования>}}.")
                appendLine("Значение, которое ты не можешь обосновать ни расчётом, ни источником,")
                appendLine("НЕ выдумывай: не давай его вовсе либо помечай source=manual — такое")
                appendLine("значение снимается фильтром и возвращается инженеру на решение.")
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
            appendLine("Ответ — массив объектов строго по схеме ниже. Схема исполняется")
            appendLine("буквально: поля вне схемы запрещены (additionalProperties: false),")
            appendLine("обязательные поля обязательны. В частности, статусная модель —")
            appendLine("\"lifecycle\": {\"status\": \"Draft\", \"version\": \"1\"}, а происхождение")
            appendLine("предложения — \"provenance\": {\"source\": \"ai_proposed\"}.")
            appendLine("Ничего, кроме массива JSON, в ответе быть не должно: ни пояснений,")
            appendLine("ни обрамления ```json — только сам массив.")
            responseSchema?.let {
                appendLine()
                appendLine("СХЕМА ОТВЕТА (JSON Schema; идентификаторы обязаны соответствовать pattern):")
                appendLine(it.toPrettyString())
            }
        }
    }

    private companion object {
        /** Сколько существующих объектов вида перечислять: длинный список дорожает. */
        const val MAX_LISTED = 40
    }
}
