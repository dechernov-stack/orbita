// Раздача требований по целям — один вызов (просьба владельца 20.09, ПМИ-7).
//
// Условие сцены 8 из шаблона фазы — «каждая цель покрыта требованием»; оно
// смотрит на ИСТОЧНИК требования (`source[].ref`). Требования записки пришли с
// источником-материалом, и восемь целей стояли непокрытыми: закрывать их
// приходилось по одной руками. Здесь та же задача решается вызовом с ПОЛНЫМИ
// перечнями: цели и требования проекта — а ответ модели есть карта «требование
// → цели» с причиной по каждой связи.
//
// Порядок раздачи общий (LinkDistributor); случай один: вид связи
// `derives_from`, а записывается не связь, а ИСТОЧНИК требования — потому что
// ворота сцены смотрят именно на него.
package orbita.ai.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.ai.api.AiService
import orbita.ai.api.DistributionAccepted
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
    service: AiService,
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    private val случай = ТребованияКЦелям(store, mapper)
    private val общий = LinkDistributor(store, service, mapper, случай)

    fun distribute(project: String, author: String): DistributionRun = общий.distribute(project, author)
    fun latest(project: String): DistributionRun? = общий.latest(project)
    fun view(project: String, run: String): DistributionRun = общий.view(project, run)
    fun accept(project: String, run: String, chosen: List<String>, author: String, reason: String): DistributionAccepted =
        общий.accept(project, run, chosen, author, reason)
    fun undo(project: String, run: String, author: String): DistributionUndone = общий.undo(project, run, author)

    /** Промпт — задача и полные перечни; правил связи в коде нет. */
    fun prompt(цели: List<Entity>, требования: List<Entity>): String =
        случай.prompt(Area.Library, требования, mapOf("goal" to цели))
}

/** Случай: требования → цели; записывается источник требования (`derives_from`). */
internal class ТребованияКЦелям(
    private val store: EntityStore,
    private val mapper: ObjectMapper,
) : DistributionCase {
    override val linkType = "derives_from"
    override val tag = "goal_coverage"
    override val answerKey = "requirements"
    override val sourceKey = "requirement"
    override val targetFields = linkedMapOf("goals" to "goal")
    override val sourceKind = "requirement"
    override val runWord = "раздачи целей"
    override val cancelledWord = "требование или его цель снята с учёта"
    override val existsWord = "цель уже стоит источником"

    override fun describe(field: String): String = when (field) {
        "requirement" -> "код требования из перечня"
        "goals" -> "коды целей, ради которых требование написано"
        else -> "чем отвечает — одной строкой"
    }
    override fun word(kind: String): String = if (kind == "goal") "цель" else "требование"
    override fun ofSource(): String = "у требования"

    override fun sources(область: Area): List<Entity> = LinkDistributor.живые(store, область, "requirement")
    override fun targets(область: Area, kind: String): List<Entity> = LinkDistributor.живые(store, область, kind)
    override fun check(sources: List<Entity>, targets: Map<String, List<Entity>>) {
        require(targets["goal"].orEmpty().isNotEmpty()) { "целей в проекте нет — покрывать нечего: сначала цели (сцена 4)" }
        require(sources.isNotEmpty()) { "требований в проекте нет — покрывать нечем: сначала черновик требований" }
    }

    /** Не раздано — ЦЕЛИ без требования: условие сцены 8 считает от цели. */
    override fun unassigned(sources: List<Entity>, targets: Map<String, List<Entity>>, раздано: Set<String>, покрыто: Set<String>): List<String> =
        targets["goal"].orEmpty().map { it.code }.filter { it !in покрыто }

    /** Цель уже стоит источником этого требования. */
    override fun exists(source: Entity, target: Entity): Boolean =
        source.doc.path("source").any { it.path("ref").asText() == target.id }

    /**
     * Прежние основания целы — источник добавляется, а не заменяет: требование
     * записки остаётся выведенным из своего материала.
     */
    override fun apply(source: Entity, target: Entity, link: JsonNode, author: String, run: String, reason: String, record: ObjectNode): Written {
        val док = source.doc.deepCopy<JsonNode>() as ObjectNode
        val источники = док.path("source") as? ArrayNode ?: док.putArray("source")
        источники.addObject().put("kind", "goal").put("ref", target.id)
        val обоснование = "раздача целей $run: ${reason.ifBlank { "принято инженером" }}" +
            link.path("reason").asText("").trim().ifBlank { null }?.let { " — $it" }.orEmpty()
        store.update(source.id, док, Provenance(Channel.SERVICE, "$author ($обоснование)", source = run))
        record.put("requirement", source.code).put("goal", target.id)
        return Written(linked = true)
    }

    /** Снимается ровно одна дописанная запись `{goal, ref}`; прежние основания целы. */
    override fun revert(record: JsonNode, area: Area, author: String, run: String): Boolean {
        val требование = store.byCode(area, record.path("requirement").asText("")) ?: return false
        val цель = record.path("goal").asText("")
        val док = требование.doc.deepCopy<JsonNode>() as ObjectNode
        val прежние = док.path("source")
        if (!прежние.isArray) return false
        val оставшиеся = mapper.createArrayNode()
        var убрано = false
        прежние.forEach { и ->
            val это = и.path("kind").asText() == "goal" && и.path("ref").asText() == цель
            if (это && !убрано) убрано = true else оставшиеся.add(и)
        }
        if (!убрано) return false
        док.set<JsonNode>("source", оставшиеся)
        store.update(требование.id, док, Provenance(Channel.MANUAL, author, source = run))
        return true
    }

    override fun note(links: List<ObjectNode>, unassigned: List<String>, refused: List<String>, cached: Boolean): String {
        val есть = links.count { it.path("exists").asBoolean(false) }
        return buildString {
            append("раздача целей: связей ${links.size}")
            if (есть > 0) append(" · уже стоят источником $есть")
            if (unassigned.isNotEmpty()) append(" · без требования ${unassigned.size}: ${unassigned.joinToString(", ")}")
            if (refused.isNotEmpty()) append(" · отбито ${refused.size}")
            if (cached) append(" · ответ из журнала, живого вызова не было")
        }
    }
    override fun acceptNote(linked: Int, classes: Int, skipped: Int): String =
        "принято источников $linked" + (if (skipped == 0) "" else " · мимо $skipped")
    override fun undoNote(unlinked: Int): String = "раздача отменена: источников снято $unlinked, прежние основания целы"

    override fun prompt(область: Area, sources: List<Entity>, targets: Map<String, List<Entity>>): String = buildString {
        val требования = sources
        val цели = targets["goal"].orEmpty()
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
            append("- ${ц.code} · «${LinkDistributor.формулировка(ц)}»")
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
            append("- ${т.code} · «${LinkDistributor.формулировка(т)}»")
            т.doc.path("title").asText("").ifBlank { null }?.let { append(" · заголовок: $it") }
            val цели0 = источникиЦелями(т)
            if (цели0.isNotEmpty()) append(" · уже выведено из: ${цели0.joinToString(", ")}")
            appendLine()
        }
    }

    private fun источникиЦелями(требование: Entity): List<String> =
        требование.doc.path("source")
            .filter { it.path("kind").asText() == "goal" }
            .mapNotNull { store.byId(it.path("ref").asText())?.code }
}
