// Раздача функций по узлам состава — четвёртая раздача на общем порядке
// (шип 1, «+ одна раздача связей»: «функции → узлы на сцене 7 — получается
// бесплатно»).
//
// Истина: `function.allocated_to: ref[] component`, «≥1 к SDR»; условие A5
// `functions_allocated` считает функции SA без узла. Случай: связь
// `allocated_to`, записывается поле `allocated_to` функции (кодом узла — id),
// откат снимает ровно дописанный узел. Подсказкой модели — полка Arcadia:
// её функции уже отнесены к узлам PBS.
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

class FunctionAllocationDistributor(
    private val store: EntityStore,
    service: AiService,
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    private val случай = ФункцииКУзлам(store, mapper)
    private val общий = LinkDistributor(store, service, mapper, случай)

    fun distribute(project: String, author: String): DistributionRun = общий.distribute(project, author)
    fun latest(project: String): DistributionRun? = общий.latest(project)
    fun view(project: String, run: String): DistributionRun = общий.view(project, run)
    fun accept(project: String, run: String, chosen: List<String>, author: String, reason: String): DistributionAccepted =
        общий.accept(project, run, chosen, author, reason)
    fun undo(project: String, run: String, author: String): DistributionUndone = общий.undo(project, run, author)
}

/** Случай: функции → узлы состава полем `allocated_to` (связь `allocated_to` реестра). */
internal class ФункцииКУзлам(
    private val store: EntityStore,
    private val mapper: ObjectMapper,
) : DistributionCase {
    override val linkType = "allocated_to"
    override val tag = "allocation"
    override val answerKey = "functions"
    override val sourceKey = "function"
    override val targetFields = linkedMapOf("nodes" to "component")
    override val sourceKind = "function"
    override val runWord = "раздачи функций"
    override val cancelledWord = "функция или её узел сняты с учёта"
    override val existsWord = "функция уже распределена на этот узел"

    override fun describe(field: String): String = when (field) {
        "function" -> "код функции из перечня"
        "nodes" -> "коды узлов состава, которые функцию исполняют"
        else -> "почему этот узел — одной строкой"
    }
    override fun word(kind: String): String = if (kind == "component") "узел" else "функция"
    override fun ofSource(): String = "у функции"

    override fun sources(область: Area): List<Entity> = LinkDistributor.живые(store, область, "function")
    override fun targets(область: Area, kind: String): List<Entity> =
        LinkDistributor.живые(store, область, kind).filter { it.doc.path("nature").asText("node") != "behaviour" }
    override fun check(sources: List<Entity>, targets: Map<String, List<Entity>>) {
        require(sources.isNotEmpty()) { "функций в проекте нет — распределять нечего: возьмите полку архитектуры или заведите функции" }
        require(targets["component"].orEmpty().isNotEmpty()) { "узлов состава в проекте нет — распределять не на что: сначала состав (сцена 7)" }
    }

    override fun exists(source: Entity, target: Entity): Boolean =
        source.doc.path("allocated_to").let { а ->
            when {
                а.isArray -> а.any { it.asText() == target.id || it.asText() == target.code }
                а.isTextual -> а.asText() == target.id || а.asText() == target.code
                else -> false
            }
        }

    override fun apply(source: Entity, target: Entity, link: JsonNode, author: String, run: String, reason: String, record: ObjectNode): Written {
        val док = source.doc.deepCopy<JsonNode>() as ObjectNode
        val прежнее = док.path("allocated_to")
        val узлы = mapper.createArrayNode()
        when {
            прежнее.isArray -> прежнее.forEach { узлы.add(it) }
            прежнее.isTextual && прежнее.asText().isNotBlank() -> узлы.add(прежнее.asText())
        }
        узлы.add(target.id)
        док.set<JsonNode>("allocated_to", узлы)
        val обоснование = "раздача функций $run: ${reason.ifBlank { "принято инженером" }}" +
            link.path("reason").asText("").trim().ifBlank { null }?.let { " — $it" }.orEmpty()
        store.update(source.id, док, Provenance(Channel.SERVICE, "$author ($обоснование)", source = run))
        record.put("function", source.code).put("node", target.id)
        return Written(linked = true)
    }

    override fun revert(record: JsonNode, area: Area, author: String, run: String): Boolean {
        val функция = store.byCode(area, record.path("function").asText("")) ?: return false
        val узел = record.path("node").asText("")
        val док = функция.doc.deepCopy<JsonNode>() as ObjectNode
        val прежние = док.path("allocated_to")
        if (!прежние.isArray) return false
        val оставшиеся = mapper.createArrayNode()
        var убрано = false
        прежние.forEach { и -> if (и.asText() == узел && !убрано) убрано = true else оставшиеся.add(и) }
        if (!убрано) return false
        док.set<JsonNode>("allocated_to", оставшиеся)
        store.update(функция.id, док, Provenance(Channel.MANUAL, author, source = run))
        return true
    }

    override fun note(links: List<ObjectNode>, unassigned: List<String>, refused: List<String>, cached: Boolean): String {
        val есть = links.count { it.path("exists").asBoolean(false) }
        return buildString {
            append("раздача функций: связей ${links.size}")
            if (есть > 0) append(" · уже распределены $есть")
            if (unassigned.isNotEmpty()) append(" · без узла ${unassigned.size}: ${unassigned.joinToString(", ")}")
            if (refused.isNotEmpty()) append(" · отбито ${refused.size}")
            if (cached) append(" · ответ из журнала, живого вызова не было")
        }
    }
    override fun acceptNote(linked: Int, classes: Int, skipped: Int): String =
        "распределено функций $linked" + (if (skipped == 0) "" else " · мимо $skipped")
    override fun undoNote(unlinked: Int): String = "раздача отменена: распределений снято $unlinked, прежние узлы целы"

    override fun prompt(область: Area, sources: List<Entity>, targets: Map<String, List<Entity>>): String = buildString {
        val функции = sources
        val узлы = targets["component"].orEmpty()
        val функция = GeneratedOntology.byCode["function"]
        appendLine("# Раздача функций по узлам состава")
        appendLine()
        appendLine("Ты называешь, какой узел состава исполняет какую функцию системы. Ничего не")
        appendLine("выписываешь заново: только коды из перечней ниже. Ответ — JSON по схеме.")
        appendLine()
        appendLine("## Истина")
        функция?.note?.takeIf { it.isNotBlank() }?.let { appendLine("- функция: $it") }
        appendLine("- функция · `allocated_to`: узлы состава, которые её исполняют; функция без")
        appendLine("  носителя — не архитектура (условие A5 «функции SA распределяются на узлы 100 %»).")
        appendLine()
        appendLine("## Задача")
        appendLine("Для КАЖДОЙ функции назови узлы, которые её исполняют, — по смыслу имени функции и")
        appendLine("узла, а не по совпадению слов. Функция может исполняться несколькими узлами;")
        appendLine("узел исполняет несколько функций. Причина — одной строкой.")
        appendLine("Функцию, которой честно нет носителя среди узлов, назови с пустым списком и")
        appendLine("причиной «не на что»: выдумывать узел нельзя.")
        appendLine("Распределения, которые уже есть, повторять можно — они не удвоятся.")
        appendLine()
        val полка = store.list(Area.Library, "architecture_template").firstOrNull()
        val подсказки = полка?.doc?.path("SA")?.path("functions")
            ?.filter { it.path("allocated_to").asText("").isNotBlank() }
            ?.associate { it.path("name").asText("").lowercase() to it.path("allocated_to").asText() }.orEmpty()
        appendLine("## Функции (${функции.size})")
        функции.forEach { ф ->
            append("- ${ф.code} · «${LinkDistributor.формулировка(ф)}» · слой: ${ф.doc.path("layer").asText("SA")}")
            val уже = ф.doc.path("allocated_to").let { а -> if (а.isArray) а.map { it.asText() } else listOfNotNull(а.asText("").ifBlank { null }) }
                .mapNotNull { store.byId(it)?.code ?: it.ifBlank { null } }
            if (уже.isNotEmpty()) append(" · уже распределена на: ${уже.joinToString(", ")}")
            подсказки[LinkDistributor.формулировка(ф).lowercase()]?.let { append(" · полка Arcadia относит к узлу PBS $it") }
            appendLine()
        }
        appendLine()
        appendLine("## Узлы состава (${узлы.size}) — код · имя · уровень · вид")
        узлы.forEach { у ->
            append("- ${у.code} · «${LinkDistributor.формулировка(у)}»")
            у.doc.path("level").asText("").ifBlank { null }?.let { append(" · уровень $it") }
            у.doc.path("kind").asText("").ifBlank { null }?.let { append(" · $it") }
            appendLine()
        }
    }
}
