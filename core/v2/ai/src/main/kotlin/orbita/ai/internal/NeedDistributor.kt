// Раздача нужд по целям и сервисам — один вызов (решение владельца 17.09, ПМИ-7).
//
// Чтение документа даёт цели и сервисы со ссылками на нужды, но не на все:
// на записке модель называла у целей 6–10 нужд из 33, и сцены 4 и 6
// («каждая нужда ведёт хотя бы к одной цели», «каждая нужда покрыта хотя бы
// одним сервисом») не закрывались содержанием. Здесь та же задача решается
// отдельным вызовом с ПОЛНЫМИ перечнями: нужды, цели и сервисы проекта — и
// ответ модели есть карта связей «нужда → цели · сервисы» с причиной по
// каждой связи.
//
// Порядок раздачи общий (LinkDistributor); здесь — только случай: связь
// `covers` от цели или сервиса к нужде, класс покрывшего сервиса — нужде
// без класса, откат возвращает класс как был. Правила связи — из истины
// ОНТОЛОГИИ (`goal.needs`, `service.needs`, `need.qos_class`,
// `distribution_rule`), второй их копии в коде нет.
package orbita.ai.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.ai.api.AiService
import orbita.ai.api.DistributionAccepted
import orbita.ai.api.DistributionRun
import orbita.ai.api.DistributionUndone
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.kernel.api.Provenance
import orbita.kernel.api.QosClass
import orbita.knowledge.api.Coverage
import orbita.knowledge.schema.GeneratedOntology

class NeedDistributor(
    private val store: EntityStore,
    private val links: LinkRegistry?,
    service: AiService,
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    private val случай = НуждыКЦелямИСервисам(store, links, mapper)
    private val общий = LinkDistributor(store, service, mapper, случай)

    fun distribute(project: String, author: String): DistributionRun = общий.distribute(project, author)
    fun latest(project: String): DistributionRun? = общий.latest(project)
    fun view(project: String, run: String): DistributionRun = общий.view(project, run)
    fun accept(project: String, run: String, chosen: List<String>, author: String, reason: String): DistributionAccepted {
        links ?: throw IllegalStateException("реестр связей на этом стенде не подключён — раздача не принимается")
        return общий.accept(project, run, chosen, author, reason)
    }
    fun undo(project: String, run: String, author: String): DistributionUndone {
        links ?: throw IllegalStateException("реестр связей на этом стенде не подключён")
        return общий.undo(project, run, author)
    }

    /** Промпт — истина о связях и полные перечни; правил в коде нет. */
    fun prompt(область: Area, нужды: List<Entity>, цели: List<Entity>, сервисы: List<Entity>): String =
        случай.prompt(область, нужды, mapOf("goal" to цели, "service" to сервисы))
}

/** Случай: нужды → цели и сервисы связью `covers`, класс покрывшего сервиса — нужде. */
internal class НуждыКЦелямИСервисам(
    private val store: EntityStore,
    private val links: LinkRegistry?,
    private val mapper: ObjectMapper,
) : DistributionCase {
    override val linkType = "covers"
    override val tag = "distribution"
    override val answerKey = "needs"
    override val sourceKey = "need"
    override val targetFields = linkedMapOf("goals" to "goal", "services" to "service")
    override val sourceKind = "need"
    override val runWord = "раздачи"
    override val cancelledWord = "нужда или её цель снята с учёта"
    override val existsWord = "связь уже есть"

    override fun describe(field: String): String = when (field) {
        "need" -> "код нужды из перечня"
        "goals" -> "коды целей, к которым нужда ведёт"
        "services" -> "коды сервисов, которые нужду закрывают"
        else -> "чем связана — одной строкой"
    }
    override fun word(kind: String): String = when (kind) { "goal" -> "цель"; "service" -> "сервис"; else -> "нужда" }
    override fun ofSource(): String = "у нужды"

    override fun sources(область: Area): List<Entity> = LinkDistributor.живые(store, область, "need")
    override fun targets(область: Area, kind: String): List<Entity> = LinkDistributor.живые(store, область, kind)
    override fun check(sources: List<Entity>, targets: Map<String, List<Entity>>) {
        require(sources.isNotEmpty()) { "нужд в проекте нет — раздавать нечего: сначала стороны и нужды (сцена 3)" }
        require(targets.values.any { it.isNotEmpty() }) { "ни целей, ни сервисов в проекте нет — раздавать не по чему" }
    }

    override fun exists(source: Entity, target: Entity): Boolean =
        links?.to(source.id, "covers")?.any { it.from == target.id } == true
    override fun qosClass(target: Entity): String? =
        if (target.kind == "service") target.doc.path(QosClass.FIELD).asText("").ifBlank { null } else null
    // Покрытая сервисом нужда без класса: TBR закрывается на сцене 6 — приём
    // даст класс и без новой связи (8 нужд на PJ-ПМИ7 остались с TBR, потому
    // что их связи «уже есть»).
    override fun classPending(source: Entity, класс: String?): Boolean =
        класс != null && !QosClass.assigned(source.doc.path(QosClass.FIELD))

    override fun apply(source: Entity, target: Entity, link: JsonNode, author: String, run: String, reason: String, record: ObjectNode): Written {
        val реестр = links ?: throw IllegalStateException("реестр связей на этом стенде не подключён — раздача не принимается")
        val естьУже = link.path("exists").asBoolean(false) || exists(source, target)
        var связь = false
        if (!естьУже) {
            val обоснование = "раздача нужд $run: ${reason.ifBlank { "принято инженером" }}" +
                link.path("reason").asText("").trim().ifBlank { null }?.let { " — $it" }.orEmpty()
            val сделано = реестр.link("covers", target.id, source.id, Provenance(Channel.SERVICE, author, source = run), rationale = обоснование)
            record.put("link", сделано.id)
            связь = true
        }
        // Класс — от покрывшего сервиса, и только если у нужды его ещё нет:
        // назначенное человеком раздача не переписывает.
        val класс = link.path("qos_class").asText("").trim().ifBlank { null }
        var классДан = false
        if (link.path("kind").asText() == "service" && classPending(source, класс)) {
            val прежний = source.doc.path(QosClass.FIELD)
            val док = source.doc.deepCopy<JsonNode>() as ObjectNode
            док.put(QosClass.FIELD, класс)
            store.update(source.id, док, Provenance(Channel.SERVICE, author, source = run))
            record.put("qos_on", source.code)
            if (прежний.isMissingNode || прежний.isNull) record.putNull("prev_qos") else record.set<JsonNode>("prev_qos", прежний)
            классДан = true
        }
        return Written(связь, классДан)
    }

    override fun revert(record: JsonNode, area: Area, author: String, run: String): Boolean {
        val реестр = links ?: throw IllegalStateException("реестр связей на этом стенде не подключён")
        var снято = false
        val связь = record.path("link").asText("")
        if (связь.isNotBlank()) {
            runCatching { реестр.unlink(связь, Provenance(Channel.MANUAL, author, source = run)) }.onSuccess { снято = true }
        }
        val кодНужды = record.path("qos_on").asText("")
        if (кодНужды.isNotBlank()) {
            store.byCode(area, кодНужды)?.let { нужда ->
                val док = нужда.doc.deepCopy<JsonNode>() as ObjectNode
                val прежний = record.path("prev_qos")
                if (прежний.isMissingNode || прежний.isNull) док.remove(QosClass.FIELD) else док.set<JsonNode>(QosClass.FIELD, прежний)
                store.update(нужда.id, док, Provenance(Channel.MANUAL, author, source = run))
            }
        }
        return снято
    }

    override fun note(links: List<ObjectNode>, unassigned: List<String>, refused: List<String>, cached: Boolean): String {
        val кЦелям = links.count { it.path("kind").asText() == "goal" }
        val кСервисам = links.count { it.path("kind").asText() == "service" }
        val есть = links.count { it.path("exists").asBoolean(false) }
        return buildString {
            append("раздача: связей ${links.size} (к целям $кЦелям · к сервисам $кСервисам)")
            if (есть > 0) append(" · уже есть $есть")
            if (unassigned.isNotEmpty()) append(" · не раздано ${unassigned.size}: ${unassigned.joinToString(", ")}")
            if (refused.isNotEmpty()) append(" · отбито ${refused.size}")
            if (cached) append(" · ответ из журнала, живого вызова не было")
        }
    }
    override fun acceptNote(linked: Int, classes: Int, skipped: Int): String =
        "принято связей $linked" + (if (classes > 0) " · классов назначено $classes" else "") + (if (skipped == 0) "" else " · мимо $skipped")
    override fun undoNote(unlinked: Int): String = "раздача отменена: связей снято $unlinked, классы возвращены как были"

    override fun prompt(область: Area, sources: List<Entity>, targets: Map<String, List<Entity>>): String {
        val нужды = sources
        val цели = targets["goal"].orEmpty()
        val сервисы = targets["service"].orEmpty()
        val цель = GeneratedOntology.byCode["goal"]
        val сервис = GeneratedOntology.byCode["service"]
        val нужда = GeneratedOntology.byCode["need"]
        return buildString {
            appendLine("# Раздача нужд по целям и сервисам")
            appendLine()
            appendLine("Ты раздаёшь нужды проекта по его целям и сервисам. Ничего не выписываешь заново:")
            appendLine("только коды из перечней ниже. Ответ — JSON по схеме.")
            appendLine()
            appendLine("## Истина (ОНТОЛОГИЯ-ФОРМИРОВАНИЯ)")
            appendLine("- цель · `needs`: ${цель?.fields?.get("needs").orEmpty().ifBlank { "нужды, которые цель закрывает" }}")
            appendLine("- сервис · `needs`: ${сервис?.fields?.get("needs").orEmpty().ifBlank { "нужды, которые сервис закрывает" }}")
            appendLine("- сервис · `qos_class`: ${сервис?.fields?.get("qos_class").orEmpty()}")
            appendLine("- нужда · `qos_class`: ${нужда?.fields?.get("qos_class").orEmpty()}")
            нужда?.distributionRule?.takeIf { it.isNotBlank() }?.let { appendLine("- правило раздачи общих нужд: $it") }
            Coverage.rule.takeIf { it.isNotBlank() }?.let { appendLine("- чем закрывается нужда: $it") }
            appendLine()
            appendLine("## Задача")
            appendLine("Для КАЖДОЙ нужды назови цели, к которым она ведёт (не меньше одной), и сервисы,")
            appendLine("которые её закрывают, — по смыслу формулировок, а не по слову.")
            appendLine("Сервис нужен только нужде, помеченной в перечне «ждёт: service»: нужда регулятора")
            appendLine("закрывается ограничением, нужда поставщика и партнёра — пакетом работ и оценкой,")
            appendLine("и сервиса им не придумывай — у таких `services` оставляй пустым.")
            appendLine("Одна нужда может вести к нескольким целям и закрываться несколькими сервисами.")
            appendLine("Причина — одной строкой: чем нужда связана с названными целями и сервисами.")
            appendLine("Нужду, которую честно не к чему отнести, назови с пустыми списками и причиной «не к чему».")
            appendLine("Связи, которые уже есть, повторять можно — они не удвоятся.")
            appendLine()
            appendLine("## Нужды (${нужды.size})")
            нужды.forEach { н ->
                append("- ${н.code} · «${LinkDistributor.формулировка(н)}» · сторона: ${носитель(н)}")
                // Чем нужда закрывается (истина `need.coverage_expected`): сервис
                // ждёт не всякая, и выход сцены 6 считает только таких.
                Coverage.expected(н.doc, рольНосителя(н))?.let { append(" · ждёт: $it") }
                val ведёт = покрывающие(н, "goal")
                val покрыта = покрывающие(н, "service")
                if (ведёт.isNotEmpty()) append(" · уже ведёт к: ${ведёт.joinToString(", ")}")
                if (покрыта.isNotEmpty()) append(" · уже покрыта: ${покрыта.joinToString(", ")}")
                appendLine()
            }
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
            appendLine("## Сервисы (${сервисы.size})")
            сервисы.forEach { с ->
                append("- ${с.code} · «${LinkDistributor.формулировка(с)}»")
                с.doc.path(QosClass.FIELD).asText("").ifBlank { null }?.let { append(" · класс: $it") }
                appendLine()
            }
        }
    }

    private fun рольНосителя(нужда: Entity): String? = Coverage.рольНосителя(store, links, нужда)

    /** Сторона нужды: связью `owns`, а если её нет — полем документа. */
    private fun носитель(нужда: Entity): String {
        val поСвязи = links?.to(нужда.id, "owns")?.mapNotNull { store.byId(it.from) }?.firstOrNull()
        if (поСвязи != null) return LinkDistributor.формулировка(поСвязи)
        val поле = нужда.doc.path("stakeholder")
        val текст = if (поле.isObject) поле.path("code").asText("") else поле.asText("")
        return текст.trim().ifBlank { "—" }
    }

    private fun покрывающие(нужда: Entity, вид: String): List<String> =
        links?.to(нужда.id, "covers")?.mapNotNull { store.byId(it.from) }?.filter { it.kind == вид }?.map { it.code }.orEmpty()
}
