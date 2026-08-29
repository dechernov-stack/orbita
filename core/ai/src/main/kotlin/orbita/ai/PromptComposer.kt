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
    val role: String?,
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
            role = doc.path("role").asText("").ifBlank { null },
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
    /** Ф-05: источники промпта операции — данными, с именем и содержимым.
        Пустой источник печатается строкой «— пусто»: видно, чего нет. */
    val sources: List<ContextSource> = emptyList(),
)

/** Источник промпта: имя, счётчик и строки — как их собрал сервер. */
data class ContextSource(
    val key: String,
    val title: String,
    val lines: List<String>,
    val note: String? = null,
)

class PromptComposer(private val kinds: PackageKinds = PackageKinds.default()) {

    /**
     * Задание для модели. Порядок частей постоянен: роль и назначение →
     * ограничения проекта → глоссарий → что уже есть → что требуется →
     * форма ответа. Схема ответа передаётся полем пакета, а не текстом
     * (TZ-AI-001, ловушка 5) — здесь на неё только ссылка.
     */
    /** Кусок промпта с источником: profile — из профиля, model — из модели
        проекта, input — из входа операции. Атрибуция для предпросмотра
        (О-4): инженер видит, откуда взялась каждая строка. */
    data class PromptBlock(val source: String, val title: String, val text: String)

    fun composeBlocks(
        kind: String,
        profile: AiProfile,
        context: ModelContext,
        responseSchema: JsonNode? = null,
    ): List<PromptBlock> {
        val k = kinds.of(kind)
        require(profile.allows(kind)) {
            "профиль ${profile.id} не разрешает вид пакета '$kind' (разрешены: ${profile.kinds})"
        }
        val blocks = mutableListOf<PromptBlock>()
        // Роль — из профиля (СТАРТ-В3 §1): преамбула в коде — «промпт руками»,
        // наш же запрет; профиль без роли промпта-роли не несёт.
        if (profile.role != null || profile.purpose != null) {
            blocks += PromptBlock(
                "profile", "Роль и назначение",
                buildString {
                    profile.role?.let { appendLine(it) }
                    profile.purpose?.let { appendLine("Назначение профиля ${profile.id}: $it.") }
                }.trimEnd(),
            )
        }
        blocks += PromptBlock(
            "profile", "Ограничения проекта",
            buildString {
                appendLine("ОГРАНИЧЕНИЯ ПРОЕКТА (нарушать нельзя):")
                if (profile.prohibitions.isEmpty()) appendLine("— не заданы")
                profile.prohibitions.forEach { appendLine("— $it") }
            }.trimEnd(),
        )
        if (profile.statementRules.isNotEmpty()) {
            blocks += PromptBlock(
                "profile", "Правила формулировок",
                buildString {
                    appendLine("ПРАВИЛА ФОРМУЛИРОВОК:")
                    profile.statementRules.forEach { appendLine("— $it") }
                }.trimEnd(),
            )
        }
        if (profile.glossary.isNotEmpty()) {
            blocks += PromptBlock(
                "profile", "Глоссарий",
                buildString {
                    appendLine("ГЛОССАРИЙ (термины употреблять только в этом значении):")
                    profile.glossary.forEach { (term, meaning, not) ->
                        appendLine("— $term: $meaning" + (not?.let { "; не путать с $it" } ?: ""))
                    }
                }.trimEnd(),
            )
        }
        if (profile.requireSource) {
            blocks += PromptBlock(
                "profile", "Основание значений",
                buildString {
                    appendLine("ОСНОВАНИЕ ЗНАЧЕНИЙ. Каждое числовое значение обязано нести")
                    appendLine("происхождение одного из двух видов:")
                    appendLine("  · расчётное — \"provenance\": {\"source\": \"computed\", \"module\": <модуль>},")
                    appendLine("    где модуль один из: ballistics, spacecraft, consumers, protocol, flows, cost;")
                    appendLine("  · из источника — \"provenance\": {\"source\": \"imported\", \"import\":")
                    appendLine("    {\"dataset\": <наименование источника>, \"dataset_version\": <версия>,")
                    appendLine("     \"retrieved_at\": <дата>, \"terms\": <условия использования>}}.")
                    appendLine("  · значение, ЗАДАННОЕ входом операции (постановкой миссии, техническим")
                    appendLine("    заданием, регламентом), — это тоже источник: imported с dataset =")
                    appendLine("    наименование этого документа и terms = «внутренний документ проекта».")
                    appendLine("Значение, которое ты не можешь обосновать ни расчётом, ни источником,")
                    appendLine("НЕ выдумывай: не давай его вовсе либо помечай source=manual — такое")
                    appendLine("значение снимается фильтром и возвращается инженеру на решение.")
                }.trimEnd(),
            )
        }
        // Состояние модели — агрегатом плюс релевантная выборка операции
        // (СТАРТ-В3 §1): счётчики и занятые диапазоны id стабильны и дёшевы,
        // поимённо — только виды, нужные ЭТОЙ операции (context_types реестра).
        blocks += PromptBlock(
            "model", "Что уже есть в проекте",
            buildString {
                appendLine("ПРОЕКТ: «${context.projectName}», фаза ${context.phase}.")
                appendLine("ЧТО УЖЕ ЕСТЬ (счётчики; идентификаторы заняты по названные):")
                if (context.existing.isEmpty()) appendLine("— проект пуст")
                context.existing.forEach { (kindName, items) ->
                    appendLine("— $kindName: ${items.size}" +
                        (items.lastOrNull()?.substringBefore(" — ")?.let { "; занято до $it" } ?: ""))
                }
                val relevant = context.existing.filterKeys { it in k.contextTypes }
                if (relevant.isNotEmpty()) {
                    appendLine()
                    appendLine("РЕЛЕВАНТНАЯ ВЫБОРКА для «${k.id}»:")
                    relevant.forEach { (kindName, items) ->
                        appendLine("$kindName (${items.size}):")
                        items.take(MAX_LISTED).forEach { appendLine("  · $it") }
                        if (items.size > MAX_LISTED) appendLine("  · … ещё ${items.size - MAX_LISTED}")
                    }
                }
            }.trimEnd(),
        )
        // Правила вида пакета (Д2): «нарушение любого — брак ответа».
        // Они специфичны операции, а не профилю, и живут в реестре видов.
        if (k.rules.isNotEmpty()) {
            blocks += PromptBlock(
                "kind", "Правила разбора",
                buildString {
                    appendLine("ПРАВИЛА (нарушение любого — брак ответа):")
                    k.rules.forEachIndexed { i, r -> appendLine("${i + 1}. $r") }
                }.trimEnd(),
            )
        }
        // Ф-05: источники операции — данными полок и проекта, не общими
        // словами. Пустой источник печатается: «— пусто» честнее тишины.
        if (context.sources.isNotEmpty()) {
            blocks += PromptBlock(
                "sources", "Данные операции",
                buildString {
                    context.sources.forEach { s ->
                        appendLine("${s.title.uppercase()} (${s.lines.size}):")
                        if (s.lines.isEmpty()) appendLine("— пусто${s.note?.let { ": $it" } ?: ""}")
                        s.lines.forEach { appendLine("— $it") }
                        appendLine()
                    }
                }.trimEnd(),
            )
        }
        blocks += PromptBlock(
            "input", "Вход операции",
            buildString {
                appendLine("ВХОД ОПЕРАЦИИ:")
                appendLine(context.statement)
            }.trimEnd(),
        )
        blocks += PromptBlock(
            "format", "Задание и форма ответа",
            buildString {
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
                appendLine()
                appendLine("ФОРМА ВЕЛИЧИНЫ. Величина — это ровно три поля:")
                appendLine("  {\"value\": 0.9, \"unit\": \"1\", \"provenance\": {…}}")
                appendLine("Ни оператора сравнения, ни порога, ни названия внутрь величины класть")
                appendLine("нельзя: сравнение выражается отдельным полем схемы (operator рядом")
                appendLine("с value в mop), а целевое значение MOE — само по себе порог.")
                appendLine("Ничего, кроме массива JSON, в ответе быть не должно: ни пояснений,")
                appendLine("ни обрамления ```json — только сам массив.")
                responseSchema?.let {
                    appendLine()
                    appendLine("СХЕМА ОТВЕТА (JSON Schema; идентификаторы обязаны соответствовать pattern):")
                    appendLine(it.toPrettyString())
                }
            }.trimEnd(),
        )
        return blocks
    }

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
    ): String =
        composeBlocks(kind, profile, context, responseSchema)
            .joinToString("\n\n", postfix = "\n") { it.text }

    private companion object {
        /** Сколько существующих объектов вида перечислять: длинный список дорожает. */
        const val MAX_LISTED = 40
    }
}
