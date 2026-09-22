// Сценарии сцены 9 — предложением из сервисов и цепочек Arcadia (шип 1, п. 1.7).
//
// Правило поставки СЦЕНАРИИ-PRE-A-СЦЕНА-9: «при входе в сцену 9 сценарии
// предлагаются из сервисов проекта и цепочек полки Arcadia — кнопкой, а не
// руками». Один вызов: сервисы проекта (с классами обслуживания и нуждами),
// узлы состава, стороны и цепочки полки → сценарии с шагами «участник — что
// происходит». Участники разрешаются в узлы (по коду или имени), стороны и
// внешние системы; неразрешённый участник назван поимённо, а не подставлен.
//
// Устроено как раздачи (NeedDistributor, GoalCoverageDistributor): запуск
// лежит записью synthesis_run, приём галками заводит цепочки, откат снимает
// ровно заведённое. Ничего не заводится само.
package orbita.ai.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.ai.api.AiService
import orbita.ai.api.ProposedScenario
import orbita.ai.api.ProposedStep
import orbita.ai.api.ScenarioProposalRun
import orbita.ai.api.ScenariosAccepted
import orbita.ai.api.ScenariosUndone
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.Provenance
import orbita.knowledge.schema.GeneratedOntology

class ScenarioProposer(
    private val store: EntityStore,
    private val service: AiService,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    /** Один вызов: сервисы, узлы, стороны и цепочки полки → сценарии предложениями. */
    fun propose(project: String, author: String): ScenarioProposalRun {
        val область = Area.Project(project)
        val сервисы = живые(область, "service")
        require(сервисы.isNotEmpty()) { "сервисов в проекте нет — предлагать сценарии не из чего: сначала сцена 6" }
        val узлы = живые(область, "component")
        val стороны = живые(область, "stakeholder")
        val имеющиеся = живые(область, "functional_chain")
        val полка = store.list(Area.Library, "architecture_template").firstOrNull()

        val промпт = prompt(сервисы, узлы, стороны, имеющиеся, полка)
        val изЖурнала = service.cached(project, промпт) != null
        val ответ = service.ask(project, KIND, промпт, maxTokens = БЮДЖЕТ, schema = схема())
        val корень = развернуть(mapper.readTree(ответ.text))

        val узлыПоКоду = узлы.associateBy { it.code }
        val узлыПоИмени = узлы.associateBy { имя(it).lowercase() }
        val стороныПоКоду = стороны.associateBy { it.code }
        val стороныПоИмени = стороны.associateBy { имя(it).lowercase() }
        val кодыСервисов = сервисы.map { it.code }.toSet()
        val занятыеИмена = имеющиеся.map { имя(it).lowercase().trim() }.toSet()

        val сценарии = mutableListOf<ObjectNode>()
        val отбито = mutableListOf<String>()
        var номер = 0
        корень.path("scenarios").forEach { с ->
            val название = с.path("name").asText("").trim()
            if (название.isBlank()) { отбито += "сценарий без имени"; return@forEach }
            val шаги = с.path("steps").filter { it.path("what").asText("").isNotBlank() }
            if (шаги.isEmpty()) { отбито += "«$название»: без шагов — это название, а не сценарий"; return@forEach }
            номер += 1
            val узел = mapper.createObjectNode()
                .put("id", "S$номер")
                .put("name", название)
                .put("mode", с.path("mode").asText("").ifBlank { "nominal" }.let { if (it in РЕЖИМЫ) it else "nominal" })
                .put("reason", с.path("reason").asText("").trim())
                .put("exists", название.lowercase() in занятыеИмена)
            val свои = с.path("services").map { it.asText().trim() }.filter { it in кодыСервисов }.distinct()
            с.path("services").map { it.asText().trim() }.filter { it.isNotBlank() && it !in кодыСервисов }
                .forEach { отбито += "«$название»: сервиса «$it» в проекте нет" }
            узел.putArray("services").also { м -> свои.forEach { м.add(it) } }
            val неразрешённые = mutableListOf<String>()
            val массив = узел.putArray("steps")
            шаги.forEach { ш ->
                val участник = ш.path("participant").asText("").trim()
                val (вид, ссылка) = разрешить(участник, узлыПоКоду, узлыПоИмени, стороныПоКоду, стороныПоИмени)
                if (вид == "unresolved" && участник !in неразрешённые) неразрешённые += участник
                массив.addObject().put("participant", участник).put("kind", вид)
                    .put("ref", ссылка).put("what", ш.path("what").asText("").trim())
            }
            узел.putArray("unresolved").also { м -> неразрешённые.forEach { м.add(it) } }
            сценарии += узел
        }

        val документ = mapper.createObjectNode()
        документ.put("trigger", KIND)
        документ.put("cached", изЖурнала)
        документ.put("slice_size", сервисы.size + узлы.size + стороны.size)
        документ.put("ontology_version", GeneratedOntology.ontologyVersion)
        документ.putArray("tags").add(МЕТКА)
        документ.set<ArrayNode>("scenarios", mapper.createArrayNode().also { м -> сценарии.forEach { м.add(it) } })
        документ.putArray("refused").also { м -> отбито.forEach { м.add(it) } }
        документ.putArray("accepted")
        документ.put("note", заметка(сценарии, отбито, изЖурнала))
        val запуск = store.create(
            следующий(область, "synthesis_run", "SR"), "synthesis_run", область, null, документ,
            Provenance(Channel.SERVICE, author, fingerprint = ответ.model), status = "done",
        )
        return вид(запуск)
    }

    fun latest(project: String): ScenarioProposalRun? =
        store.list(Area.Project(project), "synthesis_run").filter { наша(it) }.maxByOrNull { it.code }?.let { вид(it) }

    fun view(project: String, run: String): ScenarioProposalRun = вид(запуск(Area.Project(project), run))

    /**
     * Принять отмеченные: каждый — цепочкой сцены 9 с шагами «участник — что
     * происходит»; участник-узел записывается и кодом узла, чтобы сценарий
     * держался состава. Что заведено, записано в запуск — откат снимает это.
     */
    fun accept(project: String, run: String, chosen: List<String>, author: String): ScenariosAccepted {
        require(chosen.isNotEmpty()) { "не отмечено ни одного сценария: отметьте строки предложения — само оно ничего не заводит" }
        val область = Area.Project(project)
        val запуск = запуск(область, run)
        val документ = запуск.doc.deepCopy<JsonNode>() as ObjectNode
        val принятые = документ.path("accepted") as? ArrayNode ?: документ.putArray("accepted")
        val ужеПринято = принятые.map { it.path("id").asText() }.toSet()
        val поId = документ.path("scenarios").associateBy { it.path("id").asText() }
        val пропущено = mutableListOf<String>()
        val заведено = mutableListOf<String>()
        chosen.map { it.trim() }.filter { it.isNotBlank() }.distinct().forEach { id ->
            val с = поId[id] ?: run { пропущено += "$id: такого сценария в предложении нет"; return@forEach }
            if (id in ужеПринято) { пропущено += "$id: уже принят"; return@forEach }
            val название = с.path("name").asText("")
            if (живые(область, "functional_chain").any { имя(it).equals(название, ignoreCase = true) }) {
                пропущено += "$id: сценарий «$название» в проекте уже есть"
                return@forEach
            }
            val док = mapper.createObjectNode().put("name", название)
            val шаги = док.putArray("steps")
            с.path("steps").forEach { ш ->
                val шаг = шаги.addObject().put("what", ш.path("what").asText("")).put("actor", ш.path("participant").asText(""))
                if (ш.path("kind").asText() == "node") ш.path("ref").asText("").ifBlank { null }?.let { шаг.put("component", it) }
            }
            // Режим и сервисы — заметкой: у вида functional_chain истина этих
            // полей не называет (вопрос владельцу: сценарий = цепочка?).
            val сервисы = с.path("services").map { it.asText() }
            док.put("notes", "режим: ${РЕЖИМ_СЛОВАМИ[с.path("mode").asText()] ?: с.path("mode").asText()}" +
                (if (сервисы.isEmpty()) "" else " · сервисы: ${сервисы.joinToString(", ")}") +
                " · предложено из сервисов и цепочек Arcadia ($run)")
            val создано = store.create(
                следующий(область, "functional_chain", "SCN"), "functional_chain", область, "9", док,
                Provenance(Channel.SERVICE, "$author (предложение сценариев $run)", source = run),
            )
            заведено += создано.code
            принятые.addObject().put("id", id).put("code", создано.code)
        }
        store.update(запуск.id, документ, Provenance(Channel.SERVICE, author, source = run), status = запуск.status)
        val заметка = "заведено сценариев ${заведено.size}" + (if (пропущено.isEmpty()) "" else " · мимо ${пропущено.size}")
        return ScenariosAccepted(run, заведено, пропущено, заметка)
    }

    /** Откат: заведённые предложением цепочки снимаются с учёта, руками заведённые целы. */
    fun undo(project: String, run: String, author: String): ScenariosUndone {
        val область = Area.Project(project)
        val запуск = запуск(область, run)
        val документ = запуск.doc.deepCopy<JsonNode>() as ObjectNode
        val принятые = документ.path("accepted") as? ArrayNode
        if (принятые == null || принятые.isEmpty) throw IllegalStateException("у предложения «$run» нет принятых сценариев — отменять нечего")
        var снято = 0
        принятые.forEach { з ->
            val цепочка = store.byCode(область, з.path("code").asText("")) ?: return@forEach
            if (цепочка.status == СНЯТО) return@forEach
            store.update(цепочка.id, цепочка.doc, Provenance(Channel.MANUAL, author, source = run), status = СНЯТО)
            снято += 1
        }
        документ.putArray("accepted")
        store.update(запуск.id, документ, Provenance(Channel.MANUAL, author, source = run), status = запуск.status)
        return ScenariosUndone(run, снято, "предложение отменено: сценариев снято $снято, заведённые руками целы")
    }

    // --- промпт ------------------------------------------------------------

    fun prompt(сервисы: List<Entity>, узлы: List<Entity>, стороны: List<Entity>, имеющиеся: List<Entity>, полка: Entity?): String = buildString {
        val сценарий = GeneratedOntology.byCode["scenario"]
        appendLine("# Операционные сценарии из сервисов проекта и цепочек Arcadia")
        appendLine()
        appendLine("Ты предлагаешь операционные сценарии для сцены 9 (режимы и сценарии): путь по")
        appendLine("составу — кто участвует и что происходит по шагам. Ответ — JSON по схеме.")
        appendLine()
        appendLine("## Истина")
        сценарий?.note?.takeIf { it.isNotBlank() }?.let { appendLine("- сценарий: $it") }
        appendLine("- участник шага — узел состава, сторона (актор) или внешняя система.")
        appendLine("- узел и сторону называй ТОЛЬКО кодом из перечней ниже; внешнюю систему —")
        appendLine("  словами с пометой «(внешняя система)». Выдуманных участников не бывает.")
        appendLine("- сценарий показывает сервис: у каждого сценария — коды сервисов, которые он")
        appendLine("  демонстрирует; сервис без сценария — пробел, назови его в reason.")
        appendLine()
        appendLine("## Задача")
        appendLine("Не меньше трёх сценариев: штатный путь сервиса (mode nominal), нештатный —")
        appendLine("потеря канала или отказ (off_nominal), тревога с приоритетом класса A′ (alarm),")
        appendLine("если такой сервис есть. У каждого 4–7 шагов «участник — что происходит»,")
        appendLine("по одному действию на шаг, в порядке времени. Имя сценария — короткое, без")
        appendLine("повторов уже имеющихся. Причина одной строкой: какие сервисы и цепочки показаны.")
        appendLine()
        appendLine("## Сервисы проекта (${сервисы.size})")
        сервисы.forEach { с ->
            append("- ${с.code} · «${имя(с)}»")
            с.doc.path("qos_class").takeIf { !it.isMissingNode && !it.isNull }?.let { к ->
                val класс = if (к.isArray) к.joinToString(", ") { it.asText() } else if (к.isObject) "TBR" else к.asText()
                if (класс.isNotBlank()) append(" · класс: $класс")
            }
            appendLine()
        }
        appendLine()
        appendLine("## Узлы состава (${узлы.size}) — код · имя")
        if (узлы.isEmpty()) appendLine("- узлов ещё нет: участники — стороны и внешние системы")
        узлы.forEach { у -> appendLine("- ${у.code} · «${имя(у)}»") }
        appendLine()
        appendLine("## Стороны (${стороны.size}) — код · имя · роль")
        if (стороны.isEmpty()) appendLine("- сторон ещё нет")
        стороны.forEach { с -> appendLine("- ${с.code} · «${имя(с)}» · ${с.doc.path("role").asText("")}") }
        appendLine()
        val цепочки = полка?.doc?.path("SA")?.path("functional_chains")?.takeIf { it.isArray }
        val функции = полка?.doc?.path("SA")?.path("functions")?.associate { it.path("code").asText() to it.path("name").asText("") }.orEmpty()
        appendLine("## Цепочки полки Arcadia (${цепочки?.size() ?: 0})")
        if (цепочки == null || цепочки.isEmpty) appendLine("- полки нет: сценарии — из сервисов")
        цепочки?.forEach { ц ->
            append("- ${ц.path("code").asText()} · «${ц.path("name").asText()}»: ")
            appendLine(ц.path("steps").joinToString(" → ") { ш -> функции[ш.asText()] ?: ш.asText() })
        }
        appendLine()
        appendLine("## Сценарии, которые уже есть (${имеющиеся.size}) — не повторять")
        if (имеющиеся.isEmpty()) appendLine("- пока ни одного")
        имеющиеся.forEach { appendLine("- «${имя(it)}»") }
    }

    private fun схема(): JsonNode {
        val шаг = mapper.createObjectNode().put("type", "object")
        шаг.putObject("properties").also { с ->
            с.putObject("participant").put("type", "string").put("description", "код узла или стороны из перечня, либо внешняя система словами с пометой «(внешняя система)»")
            с.putObject("what").put("type", "string").put("description", "что происходит — одно действие")
        }
        шаг.putArray("required").add("participant").add("what")
        val сценарий = mapper.createObjectNode().put("type", "object")
        сценарий.putObject("properties").also { с ->
            с.putObject("name").put("type", "string")
            с.putObject("mode").put("type", "string").put("description", "nominal · off_nominal · alarm")
            с.putObject("services").put("type", "array").put("description", "коды сервисов проекта").putObject("items").put("type", "string")
            с.putObject("reason").put("type", "string")
            с.putObject("steps").put("type", "array").set<JsonNode>("items", шаг)
        }
        сценарий.putArray("required").add("name").add("mode").add("services").add("reason").add("steps")
        val корень = mapper.createObjectNode().put("type", "object")
        корень.putObject("properties").putObject("scenarios").put("type", "array").set<JsonNode>("items", сценарий)
        корень.putArray("required").add("scenarios")
        return корень
    }

    private fun развернуть(узел: JsonNode): JsonNode {
        if (узел.path("scenarios").isArray) return узел
        val один = узел.properties().singleOrNull()?.takeIf { it.value.isObject } ?: return узел
        return if (один.value.path("scenarios").isArray) один.value else узел
    }

    // --- разрешение участников --------------------------------------------

    private fun разрешить(
        участник: String,
        узлыПоКоду: Map<String, Entity>,
        узлыПоИмени: Map<String, Entity>,
        стороныПоКоду: Map<String, Entity>,
        стороныПоИмени: Map<String, Entity>,
    ): Pair<String, String?> {
        if (участник.isBlank()) return "unresolved" to null
        узлыПоКоду[участник]?.let { return "node" to it.code }
        стороныПоКоду[участник]?.let { return "side" to it.code }
        val ключ = участник.lowercase()
        if ("внешняя система" in ключ || "external" in ключ) return "external" to null
        узлыПоИмени[ключ]?.let { return "node" to it.code }
        стороныПоИмени[ключ]?.let { return "side" to it.code }
        // Помета «(сторона)» / «(узел)» из образца поставки: имя до скобки.
        val безПометы = ключ.substringBefore("(").trim()
        узлыПоИмени[безПометы]?.let { return "node" to it.code }
        стороныПоИмени[безПометы]?.let { return "side" to it.code }
        return "unresolved" to null
    }

    // --- поле --------------------------------------------------------------

    private fun живые(область: Area, вид: String): List<Entity> =
        store.list(область, вид).filter { it.status != СНЯТО }.sortedBy { it.code }

    private fun имя(запись: Entity): String =
        listOf("name", "statement", "title").firstNotNullOfOrNull { запись.doc.path(it).asText("").trim().ifBlank { null } } ?: запись.code

    private fun запуск(область: Area, код: String): Entity =
        store.byCode(область, код)?.takeIf { наша(it) } ?: throw NoSuchElementException("предложения сценариев «$код» в проекте нет")

    private fun наша(запись: Entity): Boolean =
        запись.kind == "synthesis_run" && запись.doc.path("tags").any { it.asText() == МЕТКА }

    private fun вид(запуск: Entity): ScenarioProposalRun {
        val принятые = запуск.doc.path("accepted").map { it.path("id").asText() }.toSet()
        return ScenarioProposalRun(
            id = запуск.code,
            status = запуск.status,
            cached = запуск.doc.path("cached").asBoolean(false),
            note = запуск.doc.path("note").asText(""),
            scenarios = запуск.doc.path("scenarios").map { с ->
                ProposedScenario(
                    id = с.path("id").asText(),
                    name = с.path("name").asText(""),
                    mode = с.path("mode").asText(""),
                    services = с.path("services").map { it.asText() },
                    reason = с.path("reason").asText(""),
                    steps = с.path("steps").map { ш ->
                        ProposedStep(
                            participant = ш.path("participant").asText(""),
                            participantKind = ш.path("kind").asText("unresolved"),
                            ref = ш.path("ref").asText("").ifBlank { null },
                            what = ш.path("what").asText(""),
                        )
                    },
                    unresolved = с.path("unresolved").map { it.asText() },
                    exists = с.path("exists").asBoolean(false),
                    accepted = с.path("id").asText() in принятые,
                )
            },
            refused = запуск.doc.path("refused").map { it.asText() },
        )
    }

    private fun заметка(сценарии: List<ObjectNode>, отбито: List<String>, изЖурнала: Boolean): String = buildString {
        append("предложено сценариев ${сценарии.size}")
        val неразрешено = сценарии.sumOf { it.path("unresolved").size() }
        if (неразрешено > 0) append(" · участников без записи $неразрешено")
        val есть = сценарии.count { it.path("exists").asBoolean(false) }
        if (есть > 0) append(" · уже есть $есть")
        if (отбито.isNotEmpty()) append(" · отбито ${отбито.size}")
        if (изЖурнала) append(" · ответ из журнала, живого вызова не было")
    }

    private fun следующий(область: Area, вид: String, префикс: String): String {
        val занято = store.list(область, вид).mapNotNull {
            Regex("^$префикс-(\\d+)$").find(it.code)?.groupValues?.get(1)?.toIntOrNull()
        }
        return "%s-%04d".format(префикс, (занято.maxOrNull() ?: 0) + 1)
    }

    private companion object {
        const val KIND: String = "scenarios"
        const val МЕТКА: String = "scenarios"
        const val СНЯТО: String = "cancelled"
        const val БЮДЖЕТ: Int = 16000
        val РЕЖИМЫ = setOf("nominal", "off_nominal", "alarm")
        val РЕЖИМ_СЛОВАМИ = mapOf("nominal" to "штатный", "off_nominal" to "нештатный", "alarm" to "тревога")
    }
}
