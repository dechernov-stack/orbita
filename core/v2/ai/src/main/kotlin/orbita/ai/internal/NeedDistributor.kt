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
// Ничего не заводится само: карта ложится запуском с предложениями, приём —
// массовый и обратимый («Отменить раздачу» снимает связи и возвращает
// классы). Правила связи — из истины ОНТОЛОГИИ (`goal.needs`, `service.needs`,
// `need.qos_class`, `distribution_rule`), второй их копии в коде нет.
//
// Ворота — только машинные: код нужды, цели или сервиса обязан существовать
// в проекте; связь, которая уже есть, называется «уже есть» и второй раз не
// заводится; нужда без единой связи названа поимённо — «не раздано».
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
import orbita.kernel.api.LinkRegistry
import orbita.kernel.api.Provenance
import orbita.kernel.api.QosClass
import orbita.knowledge.api.Coverage
import orbita.knowledge.schema.GeneratedOntology

class NeedDistributor(
    private val store: EntityStore,
    private val links: LinkRegistry?,
    private val service: AiService,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    /** Один вызов: полные перечни → карта связей запуском с предложениями. */
    fun distribute(project: String, author: String): DistributionRun {
        val область = Area.Project(project)
        val нужды = живые(область, "need")
        val цели = живые(область, "goal")
        val сервисы = живые(область, "service")
        require(нужды.isNotEmpty()) { "нужд в проекте нет — раздавать нечего: сначала стороны и нужды (сцена 3)" }
        require(цели.isNotEmpty() || сервисы.isNotEmpty()) { "ни целей, ни сервисов в проекте нет — раздавать не по чему" }

        val промпт = prompt(область, нужды, цели, сервисы)
        val изЖурнала = service.cached(project, промпт) != null
        val ответ = service.ask(project, KIND, промпт, maxTokens = БЮДЖЕТ, schema = схема())
        val корень = развернуть(mapper.readTree(ответ.text))

        val поКоду = (нужды + цели + сервисы).associateBy { it.code }
        val связи = mutableListOf<ObjectNode>()
        val отбито = mutableListOf<String>()
        val раздано = mutableSetOf<String>()
        var номер = 0
        корень.path("needs").forEach { строка ->
            val кодНужды = строка.path("need").asText("").trim()
            val нужда = поКоду[кодНужды]?.takeIf { it.kind == "need" }
            if (нужда == null) {
                отбито += "нужда «$кодНужды»: в проекте нет — в перечне её не было"
                return@forEach
            }
            val причина = строка.path("reason").asText("").trim()
            listOf("goals" to "goal", "services" to "service").forEach { (поле, вид) ->
                строка.path(поле).map { it.asText("").trim() }.filter { it.isNotBlank() }.distinct().forEach { кодЦели ->
                    val цель = поКоду[кодЦели]?.takeIf { it.kind == вид }
                    if (цель == null) {
                        отбито += "${слово(вид)} «$кодЦели» у нужды $кодНужды: в проекте нет"
                        return@forEach
                    }
                    if (связи.any { it.path("need").asText() == нужда.code && it.path("target").asText() == цель.code }) return@forEach
                    номер += 1
                    раздано += нужда.code
                    val класс = if (вид == "service") цель.doc.path(QosClass.FIELD).asText("").ifBlank { null } else null
                    связи += mapper.createObjectNode()
                        .put("id", "L$номер")
                        .put("need", нужда.code)
                        .put("need_text", формулировка(нужда))
                        .put("target", цель.code)
                        .put("kind", вид)
                        .put("target_text", формулировка(цель))
                        .put("qos_class", класс)
                        .put("reason", причина)
                        .put("exists", естьСвязь(цель, нужда))
                        // Покрытая сервисом нужда без класса: TBR закрывается на
                        // сцене 6 — приём даст класс и без новой связи (8 нужд
                        // на PJ-ПМИ7 остались с TBR, потому что их связи «уже есть»).
                        .put("class_pending", класс != null && !QosClass.assigned(нужда.doc.path(QosClass.FIELD)))
                }
            }
        }
        val неРаздано = нужды.map { it.code }.filter { it !in раздано }

        val документ = mapper.createObjectNode()
        документ.put("trigger", KIND)
        документ.put("cached", изЖурнала)
        документ.put("slice_size", нужды.size + цели.size + сервисы.size)
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

    /** Последняя раздача проекта; null — раздачи ещё не было. */
    fun latest(project: String): DistributionRun? =
        store.list(Area.Project(project), "synthesis_run")
            .filter { наша(it) }
            .maxByOrNull { it.code }
            ?.let { вид(it) }

    fun view(project: String, run: String): DistributionRun = вид(запуск(Area.Project(project), run))

    /**
     * Принять отмеченные связи: `covers` от цели или сервиса к нужде; нужде
     * без класса обслуживания достаётся класс покрывшего сервиса (истина:
     * «класс обслуживания покрытой нужды — выход сцены сервисов»). Что
     * сделано — записано в запуск: отмена снимает ровно это.
     */
    fun accept(project: String, run: String, chosen: List<String>, author: String, reason: String): DistributionAccepted {
        val реестр = links ?: throw IllegalStateException("реестр связей на этом стенде не подключён — раздача не принимается")
        require(chosen.isNotEmpty()) { "не отмечено ни одной связи: отметьте строки раздачи — сама она ничего не заводит" }
        val область = Area.Project(project)
        val запуск = запуск(область, run)
        val документ = запуск.doc.deepCopy<JsonNode>() as ObjectNode
        val принятые = документ.path("accepted") as? ArrayNode ?: документ.putArray("accepted")
        val ужеПринято = принятые.map { it.path("id").asText() }.toSet()
        val поId = документ.path("links").associateBy { it.path("id").asText() }
        val пропущено = mutableListOf<String>()
        var связей = 0
        var классов = 0
        chosen.map { it.trim() }.filter { it.isNotBlank() }.distinct().forEach { id ->
            val связь = поId[id] ?: run { пропущено += "$id: такой связи в раздаче нет"; return@forEach }
            if (id in ужеПринято) { пропущено += "$id: уже принята"; return@forEach }
            val нужда = store.byCode(область, связь.path("need").asText())
            val цель = store.byCode(область, связь.path("target").asText())
            if (нужда == null || цель == null || нужда.status == СНЯТО || цель.status == СНЯТО) {
                пропущено += "$id: нужда или её цель снята с учёта"
                return@forEach
            }
            val класс = связь.path("qos_class").asText("").trim()
            val классЖдёт = связь.path("kind").asText() == "service" && класс.isNotBlank() &&
                !QosClass.assigned(нужда.doc.path(QosClass.FIELD))
            val естьУже = связь.path("exists").asBoolean(false) || естьСвязь(цель, нужда)
            if (естьУже && !классЖдёт) {
                пропущено += "$id: связь уже есть"
                return@forEach
            }
            val запись = принятые.addObject().put("id", id)
            if (!естьУже) {
                val обоснование = "раздача нужд $run: ${reason.ifBlank { "принято инженером" }}" +
                    связь.path("reason").asText("").trim().ifBlank { null }?.let { " — $it" }.orEmpty()
                val сделано = реестр.link("covers", цель.id, нужда.id, Provenance(Channel.SERVICE, author, source = run), rationale = обоснование)
                связей += 1
                запись.put("link", сделано.id)
            }
            // Класс — от покрывшего сервиса, и только если у нужды его ещё нет:
            // назначенное человеком раздача не переписывает.
            if (классЖдёт) {
                val прежний = нужда.doc.path(QosClass.FIELD)
                val док = нужда.doc.deepCopy<JsonNode>() as ObjectNode
                док.put(QosClass.FIELD, класс)
                store.update(нужда.id, док, Provenance(Channel.SERVICE, author, source = run))
                классов += 1
                запись.put("qos_on", нужда.code)
                if (прежний.isMissingNode || прежний.isNull) запись.putNull("prev_qos") else запись.set<JsonNode>("prev_qos", прежний)
            }
        }
        val заметка = "принято связей $связей" + (if (классов > 0) " · классов назначено $классов" else "") +
            (if (пропущено.isEmpty()) "" else " · мимо ${пропущено.size}")
        store.update(запуск.id, документ, Provenance(Channel.SERVICE, author, source = run), status = запуск.status)
        return DistributionAccepted(run, связей, классов, пропущено, заметка)
    }

    /** Отменить принятое: связи снимаются, классы возвращаются как были. */
    fun undo(project: String, run: String, author: String): DistributionUndone {
        val реестр = links ?: throw IllegalStateException("реестр связей на этом стенде не подключён")
        val область = Area.Project(project)
        val запуск = запуск(область, run)
        val документ = запуск.doc.deepCopy<JsonNode>() as ObjectNode
        val принятые = документ.path("accepted") as? ArrayNode
        if (принятые == null || принятые.isEmpty) {
            throw IllegalStateException("у раздачи «$run» нет принятых связей — отменять нечего")
        }
        var снято = 0
        принятые.forEach { запись ->
            val связь = запись.path("link").asText("")
            if (связь.isNotBlank()) {
                runCatching { реестр.unlink(связь, Provenance(Channel.MANUAL, author, source = run)) }
                    .onSuccess { снято += 1 }
            }
            val кодНужды = запись.path("qos_on").asText("")
            if (кодНужды.isNotBlank()) {
                store.byCode(область, кодНужды)?.let { нужда ->
                    val док = нужда.doc.deepCopy<JsonNode>() as ObjectNode
                    val прежний = запись.path("prev_qos")
                    if (прежний.isMissingNode || прежний.isNull) док.remove(QosClass.FIELD) else док.set<JsonNode>(QosClass.FIELD, прежний)
                    store.update(нужда.id, док, Provenance(Channel.MANUAL, author, source = run))
                }
            }
        }
        документ.putArray("accepted")
        store.update(запуск.id, документ, Provenance(Channel.MANUAL, author, source = run), status = запуск.status)
        return DistributionUndone(run, снято, "раздача отменена: связей снято $снято, классы возвращены как были")
    }

    // --- промпт ------------------------------------------------------------

    /** Промпт — истина о связях и полные перечни; правил в коде нет. */
    fun prompt(область: Area, нужды: List<Entity>, цели: List<Entity>, сервисы: List<Entity>): String {
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
                append("- ${н.code} · «${формулировка(н)}» · сторона: ${носитель(н)}")
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
                append("- ${ц.code} · «${формулировка(ц)}»")
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
                append("- ${с.code} · «${формулировка(с)}»")
                с.doc.path(QosClass.FIELD).asText("").ifBlank { null }?.let { append(" · класс: $it") }
                appendLine()
            }
        }
    }

    private fun схема(): JsonNode {
        val связь = mapper.createObjectNode().put("type", "object")
        val свойства = связь.putObject("properties")
        свойства.putObject("need").put("type", "string").put("description", "код нужды из перечня")
        свойства.putObject("goals").put("type", "array").put("description", "коды целей, к которым нужда ведёт")
            .putObject("items").put("type", "string")
        свойства.putObject("services").put("type", "array").put("description", "коды сервисов, которые нужду закрывают")
            .putObject("items").put("type", "string")
        свойства.putObject("reason").put("type", "string").put("description", "чем связана — одной строкой")
        связь.putArray("required").add("need").add("goals").add("services").add("reason")
        val корень = mapper.createObjectNode().put("type", "object")
        корень.putObject("properties").putObject("needs").put("type", "array").set<JsonNode>("items", связь)
        корень.putArray("required").add("needs")
        return корень
    }

    /** Ответ в обёртке вызова инструмента (`{"parameters": {…}}`) разворачивается. */
    private fun развернуть(узел: JsonNode): JsonNode {
        if (узел.path("needs").isArray) return узел
        val один = узел.properties().singleOrNull()?.takeIf { it.value.isObject } ?: return узел
        return if (один.value.path("needs").isArray) один.value else узел
    }

    // --- поле --------------------------------------------------------------

    private fun живые(область: Area, вид: String): List<Entity> =
        store.list(область, вид).filter { it.status != СНЯТО }.sortedBy { it.code }

    private fun формулировка(запись: Entity): String =
        listOf("statement", "name", "text", "designation")
            .firstNotNullOfOrNull { запись.doc.path(it).asText("").trim().ifBlank { null } } ?: запись.code

    private fun рольНосителя(нужда: Entity): String? = Coverage.рольНосителя(store, links, нужда)

    /** Сторона нужды: связью `owns`, а если её нет — полем документа. */
    private fun носитель(нужда: Entity): String {
        val поСвязи = links?.to(нужда.id, "owns")?.mapNotNull { store.byId(it.from) }?.firstOrNull()
        if (поСвязи != null) return формулировка(поСвязи)
        val поле = нужда.doc.path("stakeholder")
        val текст = if (поле.isObject) поле.path("code").asText("") else поле.asText("")
        return текст.trim().ifBlank { "—" }
    }

    private fun покрывающие(нужда: Entity, вид: String): List<String> =
        links?.to(нужда.id, "covers")?.mapNotNull { store.byId(it.from) }?.filter { it.kind == вид }?.map { it.code }.orEmpty()

    private fun естьСвязь(цель: Entity, нужда: Entity): Boolean =
        links?.to(нужда.id, "covers")?.any { it.from == цель.id } == true

    private fun запуск(область: Area, код: String): Entity =
        store.byCode(область, код)?.takeIf { наша(it) }
            ?: throw NoSuchElementException("раздачи «$код» в проекте нет")

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
                    qosClass = с.path("qos_class").asText("").ifBlank { null },
                    reason = с.path("reason").asText(""),
                    exists = с.path("exists").asBoolean(false),
                    classPending = с.path("class_pending").asBoolean(false),
                    accepted = с.path("id").asText() in принятые,
                )
            },
            unassigned = запуск.doc.path("unassigned").map { it.asText() },
            refused = запуск.doc.path("refused").map { it.asText() },
        )
    }

    private fun заметка(связи: List<ObjectNode>, неРаздано: List<String>, отбито: List<String>, изЖурнала: Boolean): String {
        val кЦелям = связи.count { it.path("kind").asText() == "goal" }
        val кСервисам = связи.count { it.path("kind").asText() == "service" }
        val есть = связи.count { it.path("exists").asBoolean(false) }
        return buildString {
            append("раздача: связей ${связи.size} (к целям $кЦелям · к сервисам $кСервисам)")
            if (есть > 0) append(" · уже есть $есть")
            if (неРаздано.isNotEmpty()) append(" · не раздано ${неРаздано.size}: ${неРаздано.joinToString(", ")}")
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

    private fun слово(вид: String): String = if (вид == "goal") "цель" else "сервис"

    private companion object {
        const val KIND: String = "distribution"
        const val МЕТКА: String = "distribution"
        const val СНЯТО: String = "cancelled"
        const val БЮДЖЕТ: Int = 16000
    }
}
