// Раздача требований по целям — один вызов (просьба владельца 20.09, ПМИ-7).
//
// Условие сцены 8 из шаблона фазы — «каждая цель покрыта требованием»; оно
// смотрит на ИСТОЧНИК требования (`source[].ref`). Требования записки пришли с
// источником-материалом, и восемь целей стояли непокрытыми: закрывать их
// приходилось по одной руками. Здесь та же задача решается вызовом с ПОЛНЫМИ
// перечнями: цели и требования проекта — а ответ модели есть карта «требование
// → цели» с причиной по каждой связи.
//
// Устроено как раздача нужд (NeedDistributor), и намеренно: владелец уже знает
// этот порядок — предложения, отметки, «Принять», «Отменить раздачу». Разница
// одна: там заводилась связь `covers`, здесь дописывается ИСТОЧНИК требования —
// потому что ворота сцены смотрят именно на него, а не на связь.
//
// Ничего не заводится само. Ворота машинные: код цели и код требования обязаны
// существовать в проекте; источник, который уже стоит, назван «уже есть» и
// второй раз не пишется; цель, которой модель не нашла требования, названа
// поимённо — «не раздано».
package orbita.ai.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.ai.api.AiService
import orbita.ai.api.DistributionAccepted
import orbita.ai.api.DistributionLink
import orbita.ai.api.DistributionRun
import orbita.ai.api.DistributionUndone
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.Provenance
import orbita.knowledge.schema.GeneratedOntology

class GoalCoverageDistributor(
    private val store: EntityStore,
    private val service: AiService,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    /** Один вызов: цели и требования проекта → карта «требование → цели». */
    fun distribute(project: String, author: String): DistributionRun {
        val область = Area.Project(project)
        val цели = живые(область, "goal")
        val требования = живые(область, "requirement")
        require(цели.isNotEmpty()) { "целей в проекте нет — покрывать нечего: сначала цели (сцена 4)" }
        require(требования.isNotEmpty()) { "требований в проекте нет — покрывать нечем: сначала черновик требований" }

        val промпт = prompt(цели, требования)
        val изЖурнала = service.cached(project, промпт) != null
        val ответ = service.ask(project, KIND, промпт, maxTokens = БЮДЖЕТ, schema = схема())
        val корень = развернуть(mapper.readTree(ответ.text))

        val поКоду = (цели + требования).associateBy { it.code }
        val связи = mutableListOf<ObjectNode>()
        val отбито = mutableListOf<String>()
        val покрыты = mutableSetOf<String>()
        var номер = 0
        корень.path("requirements").forEach { строка ->
            val кодТребования = строка.path("requirement").asText("").trim()
            val требование = поКоду[кодТребования]?.takeIf { it.kind == "requirement" }
            if (требование == null) {
                отбито += "требование «$кодТребования»: в проекте нет — в перечне его не было"
                return@forEach
            }
            val причина = строка.path("reason").asText("").trim()
            строка.path("goals").map { it.asText("").trim() }.filter { it.isNotBlank() }.distinct().forEach { кодЦели ->
                val цель = поКоду[кодЦели]?.takeIf { it.kind == "goal" }
                if (цель == null) {
                    отбито += "цель «$кодЦели» у требования $кодТребования: в проекте нет"
                    return@forEach
                }
                if (связи.any { it.path("need").asText() == требование.code && it.path("target").asText() == цель.code }) {
                    return@forEach
                }
                номер += 1
                покрыты += цель.code
                связи += mapper.createObjectNode()
                    .put("id", "L$номер")
                    .put("need", требование.code)
                    .put("need_text", формулировка(требование))
                    .put("target", цель.code)
                    .put("kind", "goal")
                    .put("target_text", формулировка(цель))
                    .putNull("qos_class")
                    .put("reason", причина)
                    .put("exists", естьИсточник(требование, цель))
                    .put("class_pending", false)
            }
        }
        val неРаздано = цели.map { it.code }.filter { it !in покрыты }

        val документ = mapper.createObjectNode()
        документ.put("trigger", KIND)
        документ.put("cached", изЖурнала)
        документ.put("slice_size", цели.size + требования.size)
        документ.put("ontology_version", GeneratedOntology.ontologyVersion)
        документ.putArray("tags").add(МЕТКА)
        документ.set<ArrayNode>("links", mapper.createArrayNode().also { м -> связи.forEach { м.add(it) } })
        документ.putArray("unassigned").also { м -> неРаздано.forEach { м.add(it) } }
        документ.putArray("refused").also { м -> отбито.forEach { м.add(it) } }
        документ.putArray("accepted")
        документ.put("note", заметка(связи, неРаздано, отбито, изЖурнала))
        val запуск = store.create(
            следующий(область, "synthesis_run", "SR"), "synthesis_run", область, null, документ,
            Provenance(Channel.SERVICE, author, fingerprint = ответ.model), status = "done",
        )
        return вид(запуск)
    }

    /** Последняя раздача целей в проекте; null — её ещё не было. */
    fun latest(project: String): DistributionRun? =
        store.list(Area.Project(project), "synthesis_run")
            .filter { наша(it) }
            .maxByOrNull { it.code }
            ?.let { вид(it) }

    fun view(project: String, run: String): DistributionRun = вид(запуск(Area.Project(project), run))

    /**
     * Принять отмеченные связи: цель дописывается ИСТОЧНИКОМ требования.
     *
     * Прежние основания целы — источник добавляется, а не заменяет: требование
     * записки остаётся выведенным из своего материала. Что дописано, записано в
     * запуск: отмена снимает ровно это и ничего больше.
     */
    fun accept(project: String, run: String, chosen: List<String>, author: String, reason: String): DistributionAccepted {
        require(chosen.isNotEmpty()) { "не отмечено ни одной связи: отметьте строки раздачи — сама она ничего не заводит" }
        val область = Area.Project(project)
        val запуск = запуск(область, run)
        val документ = запуск.doc.deepCopy<JsonNode>() as ObjectNode
        val принятые = документ.path("accepted") as? ArrayNode ?: документ.putArray("accepted")
        val ужеПринято = принятые.map { it.path("id").asText() }.toSet()
        val поId = документ.path("links").associateBy { it.path("id").asText() }
        val пропущено = mutableListOf<String>()
        var записей = 0
        chosen.map { it.trim() }.filter { it.isNotBlank() }.distinct().forEach { id ->
            val связь = поId[id] ?: run { пропущено += "$id: такой связи в раздаче нет"; return@forEach }
            if (id in ужеПринято) { пропущено += "$id: уже принята"; return@forEach }
            val требование = store.byCode(область, связь.path("need").asText())
            val цель = store.byCode(область, связь.path("target").asText())
            if (требование == null || цель == null || требование.status == СНЯТО || цель.status == СНЯТО) {
                пропущено += "$id: требование или его цель снята с учёта"
                return@forEach
            }
            if (естьИсточник(требование, цель)) {
                пропущено += "$id: цель уже стоит источником"
                return@forEach
            }
            val док = требование.doc.deepCopy<JsonNode>() as ObjectNode
            val источники = док.path("source") as? ArrayNode ?: док.putArray("source")
            источники.addObject().put("kind", "goal").put("ref", цель.id)
            val обоснование = "раздача целей $run: ${reason.ifBlank { "принято инженером" }}" +
                связь.path("reason").asText("").trim().ifBlank { null }?.let { " — $it" }.orEmpty()
            store.update(требование.id, док, Provenance(Channel.SERVICE, "$author ($обоснование)", source = run))
            записей += 1
            принятые.addObject().put("id", id).put("requirement", требование.code).put("goal", цель.id)
        }
        val заметка = "принято источников $записей" + (if (пропущено.isEmpty()) "" else " · мимо ${пропущено.size}")
        store.update(запуск.id, документ, Provenance(Channel.SERVICE, author, source = run), status = запуск.status)
        return DistributionAccepted(run, записей, 0, пропущено, заметка)
    }

    /** Отменить принятое: дописанные источники снимаются, прежние остаются. */
    fun undo(project: String, run: String, author: String): DistributionUndone {
        val область = Area.Project(project)
        val запуск = запуск(область, run)
        val документ = запуск.doc.deepCopy<JsonNode>() as ObjectNode
        val принятые = документ.path("accepted") as? ArrayNode
        if (принятые == null || принятые.isEmpty) {
            throw IllegalStateException("у раздачи «$run» нет принятых связей — отменять нечего")
        }
        var снято = 0
        принятые.forEach { запись ->
            val требование = store.byCode(область, запись.path("requirement").asText("")) ?: return@forEach
            val цель = запись.path("goal").asText("")
            val док = требование.doc.deepCopy<JsonNode>() as ObjectNode
            val прежние = док.path("source")
            if (!прежние.isArray) return@forEach
            val оставшиеся = mapper.createArrayNode()
            var убрано = false
            прежние.forEach { и ->
                val это = и.path("kind").asText() == "goal" && и.path("ref").asText() == цель
                if (это && !убрано) { убрано = true } else оставшиеся.add(и)
            }
            if (!убрано) return@forEach
            док.set<JsonNode>("source", оставшиеся)
            store.update(требование.id, док, Provenance(Channel.MANUAL, author, source = run))
            снято += 1
        }
        документ.putArray("accepted")
        store.update(запуск.id, документ, Provenance(Channel.MANUAL, author, source = run), status = запуск.status)
        return DistributionUndone(run, снято, "раздача отменена: источников снято $снято, прежние основания целы")
    }

    // --- промпт ------------------------------------------------------------

    /** Промпт — задача и полные перечни; правил связи в коде нет. */
    fun prompt(цели: List<Entity>, требования: List<Entity>): String = buildString {
        val цель = GeneratedOntology.byCode["goal"]
        appendLine("# Раздача требований по целям")
        appendLine()
        appendLine("Ты называешь, какая цель проекта закрывается каким требованием. Ничего не")
        appendLine("выписываешь заново: только коды из перечней ниже. Ответ — JSON по схеме.")
        appendLine()
        appendLine("## Истина (ОНТОЛОГИЯ-ФОРМИРОВАНИЯ)")
        цель?.note?.takeIf { it.isNotBlank() }?.let { appendLine("- цель: $it") }
        appendLine("- требование выводится ИЗ цели: цель становится его источником (`source`),")
        appendLine("  и условие сцены 8 «каждая цель покрыта требованием» считает именно это.")
        appendLine()
        appendLine("## Задача")
        appendLine("Для КАЖДОГО требования назови цели, ради которых оно написано, — по смыслу")
        appendLine("формулировок, а не по совпадению слов. Требование может служить нескольким")
        appendLine("целям, цель может закрываться несколькими требованиями.")
        appendLine("Причина — одной строкой: чем требование отвечает названным целям.")
        appendLine("Требование, которое честно ни к одной цели не отнести, назови с пустым списком")
        appendLine("и причиной «не к чему»: выдумывать связь нельзя.")
        appendLine("Источники, которые уже стоят, повторять можно — они не удвоятся.")
        appendLine()
        appendLine("## Цели (${цели.size})")
        цели.forEach { ц ->
            append("- ${ц.code} · «${формулировка(ц)}»")
            ц.doc.path("measure").takeIf { it.isObject }?.let { м ->
                val значение = м.path("value").asText("").ifBlank { "${м.path("min").asText("")}–${м.path("max").asText("")}" }
                append(" · показатель: $значение ${м.path("unit").asText("")}".trimEnd())
            }
            ц.doc.path("year").asText("").ifBlank { null }?.let { append(" · год: $it") }
            appendLine()
        }
        appendLine()
        appendLine("## Требования (${требования.size})")
        требования.forEach { т ->
            append("- ${т.code} · «${формулировка(т)}»")
            т.doc.path("title").asText("").ifBlank { null }?.let { append(" · заголовок: $it") }
            val цели0 = источникиЦелями(т)
            if (цели0.isNotEmpty()) append(" · уже выведено из: ${цели0.joinToString(", ")}")
            appendLine()
        }
    }

    private fun схема(): JsonNode {
        val связь = mapper.createObjectNode().put("type", "object")
        val свойства = связь.putObject("properties")
        свойства.putObject("requirement").put("type", "string").put("description", "код требования из перечня")
        свойства.putObject("goals").put("type", "array").put("description", "коды целей, ради которых требование написано")
            .putObject("items").put("type", "string")
        свойства.putObject("reason").put("type", "string").put("description", "чем отвечает — одной строкой")
        связь.putArray("required").add("requirement").add("goals").add("reason")
        val корень = mapper.createObjectNode().put("type", "object")
        корень.putObject("properties").putObject("requirements").put("type", "array").set<JsonNode>("items", связь)
        корень.putArray("required").add("requirements")
        return корень
    }

    /** Ответ в обёртке вызова инструмента (`{"parameters": {…}}`) разворачивается. */
    private fun развернуть(узел: JsonNode): JsonNode {
        if (узел.path("requirements").isArray) return узел
        val один = узел.properties().singleOrNull()?.takeIf { it.value.isObject } ?: return узел
        return if (один.value.path("requirements").isArray) один.value else узел
    }

    // --- поле --------------------------------------------------------------

    private fun живые(область: Area, вид: String): List<Entity> =
        store.list(область, вид).filter { it.status != СНЯТО }.sortedBy { it.code }

    private fun формулировка(запись: Entity): String =
        listOf("statement", "name", "text", "title")
            .firstNotNullOfOrNull { запись.doc.path(it).asText("").trim().ifBlank { null } } ?: запись.code

    /** Цель уже стоит источником этого требования. */
    private fun естьИсточник(требование: Entity, цель: Entity): Boolean =
        требование.doc.path("source").any { it.path("ref").asText() == цель.id }

    private fun источникиЦелями(требование: Entity): List<String> =
        требование.doc.path("source")
            .filter { it.path("kind").asText() == "goal" }
            .mapNotNull { store.byId(it.path("ref").asText())?.code }

    private fun запуск(область: Area, код: String): Entity =
        store.byCode(область, код)?.takeIf { наша(it) }
            ?: throw NoSuchElementException("раздачи целей «$код» в проекте нет")

    private fun наша(запись: Entity): Boolean =
        запись.kind == "synthesis_run" && запись.doc.path("tags").any { it.asText() == МЕТКА }

    private fun вид(запуск: Entity): DistributionRun {
        val принятые = запуск.doc.path("accepted").map { it.path("id").asText() }.toSet()
        return DistributionRun(
            id = запуск.code,
            status = запуск.status,
            cached = запуск.doc.path("cached").asBoolean(false),
            note = запуск.doc.path("note").asText(""),
            links = запуск.doc.path("links").map { с ->
                DistributionLink(
                    id = с.path("id").asText(),
                    need = с.path("need").asText(),
                    needText = с.path("need_text").asText(""),
                    target = с.path("target").asText(),
                    targetKind = с.path("kind").asText(),
                    targetText = с.path("target_text").asText(""),
                    qosClass = null,
                    reason = с.path("reason").asText(""),
                    exists = с.path("exists").asBoolean(false),
                    classPending = false,
                    accepted = с.path("id").asText() in принятые,
                )
            },
            unassigned = запуск.doc.path("unassigned").map { it.asText() },
            refused = запуск.doc.path("refused").map { it.asText() },
        )
    }

    private fun заметка(связи: List<ObjectNode>, неРаздано: List<String>, отбито: List<String>, изЖурнала: Boolean): String {
        val есть = связи.count { it.path("exists").asBoolean(false) }
        return buildString {
            append("раздача целей: связей ${связи.size}")
            if (есть > 0) append(" · уже стоят источником $есть")
            if (неРаздано.isNotEmpty()) {
                append(" · без требования ${неРаздано.size}: ${неРаздано.joinToString(", ")}")
            }
            if (отбито.isNotEmpty()) append(" · отбито ${отбито.size}")
            if (изЖурнала) append(" · ответ из журнала, живого вызова не было")
        }
    }

    private fun следующий(область: Area, вид: String, префикс: String): String {
        val занято = store.list(область, вид).mapNotNull {
            Regex("^$префикс-(\\d+)$").find(it.code)?.groupValues?.get(1)?.toIntOrNull()
        }
        return "%s-%04d".format(префикс, (занято.maxOrNull() ?: 0) + 1)
    }

    private companion object {
        const val KIND: String = "goal_coverage"
        const val МЕТКА: String = "goal_coverage"
        const val СНЯТО: String = "cancelled"
        const val БЮДЖЕТ: Int = 16000
    }
}
