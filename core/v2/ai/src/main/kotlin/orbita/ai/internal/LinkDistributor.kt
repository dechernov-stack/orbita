// Одна раздача связей (шип 1, «+ одна раздача связей»).
//
// Три раздачи — нужды → цели и сервисы (`covers`), требования → цели
// (`derives_from`, источником требования) — были двумя копиями одного
// порядка: полные перечни в промпт, карта с причиной на связь, ворота (код
// вне проекта, «уже есть»), приём галками, откат. Здесь порядок один, а
// раздачи различаются СЛУЧАЕМ: вид связи из реестра, откуда и куда, чем
// связь считается лежащей, как пишется и как снимается. Четвёртая —
// функции → узлы (`allocated_to`) на сцене 7 — получается случаем без
// новой копии порядка.
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

/** Что записано приёмом одной связи: считается в заметке и хранится для отката. */
data class Written(val linked: Boolean, val classAssigned: Boolean = false)

/**
 * Случай раздачи — всё, чем раздачи отличаются. Общий порядок их не знает.
 */
interface DistributionCase {
    /** Вид связи из реестра связей (истина `link.type`). */
    val linkType: String
    /** Тег запуска (`synthesis_run.tags`) и род вызова службы. */
    val tag: String
    /** Ключ массива ответа модели и ключ кода источника в его строке. */
    val answerKey: String
    val sourceKey: String
    /** Поле строки ответа → вид цели (goals → goal, services → service, nodes → component). */
    val targetFields: Map<String, String>
    val sourceKind: String
    /** Как называется раздача в сообщениях: «раздачи нужд», «раздачи целей». */
    val runWord: String
    /** Описание полей схемы ответа — словами задачи. */
    fun describe(field: String): String

    fun sources(область: Area): List<Entity>
    fun targets(область: Area, kind: String): List<Entity>
    /** Отказ словами до вызова модели: раздавать нечего или не по чему. */
    fun check(sources: List<Entity>, targets: Map<String, List<Entity>>)
    fun prompt(область: Area, sources: List<Entity>, targets: Map<String, List<Entity>>): String
    /** Связь уже лежит в модели: второй раз не заводится. */
    fun exists(source: Entity, target: Entity): Boolean
    /** Класс обслуживания цели, который едет со связью (только сервисы). */
    fun qosClass(target: Entity): String? = null
    /** Источник ждёт класса и без новой связи. */
    fun classPending(source: Entity, класс: String?): Boolean = false
    /** Записать связь; что записано — в `record` для отката. */
    fun apply(source: Entity, target: Entity, link: JsonNode, author: String, run: String, reason: String, record: ObjectNode): Written
    /** Снять записанное; true — снято. */
    fun revert(record: JsonNode, area: Area, author: String, run: String): Boolean
    /**
     * Что осталось «не раздано»: по умолчанию источники без единой связи;
     * раздача целей называет ЦЕЛИ без требования — считает от цели.
     */
    fun unassigned(sources: List<Entity>, targets: Map<String, List<Entity>>, раздано: Set<String>, покрыто: Set<String>): List<String> =
        sources.map { it.code }.filter { it !in раздано }
    fun note(links: List<ObjectNode>, unassigned: List<String>, refused: List<String>, cached: Boolean): String
    fun acceptNote(linked: Int, classes: Int, skipped: Int): String
    fun undoNote(unlinked: Int): String
    /** «нужда или её цель снята с учёта» — источник или цель сняты. */
    val cancelledWord: String
    /** «связь уже есть» — приём пропускает лежащую связь. */
    val existsWord: String
    /** Слово вида для отказов: «нужда», «цель», «узел». */
    fun word(kind: String): String
    /** Родительный падеж источника: «у нужды», «у требования». */
    fun ofSource(): String
}

class LinkDistributor(
    private val store: EntityStore,
    private val service: AiService,
    private val mapper: ObjectMapper,
    private val случай: DistributionCase,
) {

    /** Один вызов: полные перечни → карта связей запуском с предложениями. */
    fun distribute(project: String, author: String): DistributionRun {
        val область = Area.Project(project)
        val источники = случай.sources(область)
        val виды = случай.targetFields.values.distinct()
        val цели = виды.associateWith { случай.targets(область, it) }
        случай.check(источники, цели)

        val промпт = случай.prompt(область, источники, цели)
        val изЖурнала = service.cached(project, промпт) != null
        val ответ = service.ask(project, случай.tag, промпт, maxTokens = БЮДЖЕТ, schema = схема())
        val корень = развернуть(mapper.readTree(ответ.text))

        val поКоду = (источники + цели.values.flatten()).associateBy { it.code }
        val связи = mutableListOf<ObjectNode>()
        val отбито = mutableListOf<String>()
        val раздано = mutableSetOf<String>()
        val покрыто = mutableSetOf<String>()
        var номер = 0
        корень.path(случай.answerKey).forEach { строка ->
            val кодИсточника = строка.path(случай.sourceKey).asText("").trim()
            val источник = поКоду[кодИсточника]?.takeIf { it.kind == случай.sourceKind }
            if (источник == null) {
                отбито += "${случай.word(случай.sourceKind)} «$кодИсточника»: в проекте нет — в перечне не было"
                return@forEach
            }
            val причина = строка.path("reason").asText("").trim()
            случай.targetFields.forEach { (поле, вид) ->
                строка.path(поле).map { it.asText("").trim() }.filter { it.isNotBlank() }.distinct().forEach { кодЦели ->
                    val цель = поКоду[кодЦели]?.takeIf { it.kind == вид }
                    if (цель == null) {
                        отбито += "${случай.word(вид)} «$кодЦели» ${случай.ofSource()} $кодИсточника: в проекте нет"
                        return@forEach
                    }
                    if (связи.any { it.path("need").asText() == источник.code && it.path("target").asText() == цель.code }) return@forEach
                    номер += 1
                    раздано += источник.code
                    покрыто += цель.code
                    val класс = случай.qosClass(цель)
                    связи += mapper.createObjectNode()
                        .put("id", "L$номер")
                        .put("need", источник.code)
                        .put("need_text", формулировка(источник))
                        .put("target", цель.code)
                        .put("kind", вид)
                        .put("target_text", формулировка(цель))
                        .put("qos_class", класс)
                        .put("reason", причина)
                        .put("exists", случай.exists(источник, цель))
                        .put("class_pending", случай.classPending(источник, класс))
                }
            }
        }
        val неРаздано = случай.unassigned(источники, цели, раздано, покрыто)

        val документ = mapper.createObjectNode()
        документ.put("trigger", случай.tag)
        документ.put("link_type", случай.linkType)
        документ.put("cached", изЖурнала)
        документ.put("slice_size", источники.size + цели.values.sumOf { it.size })
        документ.put("ontology_version", GeneratedOntology.ontologyVersion)
        документ.putArray("tags").add(случай.tag)
        документ.set<ArrayNode>("links", mapper.createArrayNode().also { м -> связи.forEach { м.add(it) } })
        документ.putArray("unassigned").also { м -> неРаздано.forEach { м.add(it) } }
        документ.putArray("refused").also { м -> отбито.forEach { м.add(it) } }
        документ.putArray("accepted")
        документ.put("note", случай.note(связи, неРаздано, отбито, изЖурнала))
        val запуск = store.create(
            следующий(область, "synthesis_run", "SR"), "synthesis_run", область, null, документ,
            Provenance(Channel.SERVICE, author, fingerprint = ответ.model), status = "done",
        )
        return вид(запуск)
    }

    fun latest(project: String): DistributionRun? =
        store.list(Area.Project(project), "synthesis_run").filter { наша(it) }.maxByOrNull { it.code }?.let { вид(it) }

    fun view(project: String, run: String): DistributionRun = вид(запуск(Area.Project(project), run))

    /** Принять отмеченные: пишет случай; что сделано — в запуске, откат снимает ровно это. */
    fun accept(project: String, run: String, chosen: List<String>, author: String, reason: String): DistributionAccepted {
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
            val источник = store.byCode(область, связь.path("need").asText())
            val цель = store.byCode(область, связь.path("target").asText())
            if (источник == null || цель == null || источник.status == СНЯТО || цель.status == СНЯТО) {
                пропущено += "$id: ${случай.cancelledWord}"
                return@forEach
            }
            val естьУже = связь.path("exists").asBoolean(false) || случай.exists(источник, цель)
            val классЖдёт = случай.classPending(источник, связь.path("qos_class").asText("").ifBlank { null })
            if (естьУже && !классЖдёт) {
                пропущено += "$id: ${случай.existsWord}"
                return@forEach
            }
            val запись = mapper.createObjectNode().put("id", id)
            val сделано = случай.apply(источник, цель, связь, author, run, reason, запись)
            if (сделано.linked) связей += 1
            if (сделано.classAssigned) классов += 1
            принятые.add(запись)
        }
        store.update(запуск.id, документ, Provenance(Channel.SERVICE, author, source = run), status = запуск.status)
        return DistributionAccepted(run, связей, классов, пропущено, случай.acceptNote(связей, классов, пропущено.size))
    }

    /** Отменить принятое: случай снимает ровно записанное. */
    fun undo(project: String, run: String, author: String): DistributionUndone {
        val область = Area.Project(project)
        val запуск = запуск(область, run)
        val документ = запуск.doc.deepCopy<JsonNode>() as ObjectNode
        val принятые = документ.path("accepted") as? ArrayNode
        if (принятые == null || принятые.isEmpty) throw IllegalStateException("у ${случай.runWord} «$run» нет принятых связей — отменять нечего")
        var снято = 0
        принятые.forEach { запись -> if (случай.revert(запись, область, author, run)) снято += 1 }
        документ.putArray("accepted")
        store.update(запуск.id, документ, Provenance(Channel.MANUAL, author, source = run), status = запуск.status)
        return DistributionUndone(run, снято, случай.undoNote(снято))
    }

    // --- ответ модели ------------------------------------------------------

    private fun схема(): JsonNode {
        val связь = mapper.createObjectNode().put("type", "object")
        val свойства = связь.putObject("properties")
        свойства.putObject(случай.sourceKey).put("type", "string").put("description", случай.describe(случай.sourceKey))
        случай.targetFields.keys.forEach { поле ->
            свойства.putObject(поле).put("type", "array").put("description", случай.describe(поле)).putObject("items").put("type", "string")
        }
        свойства.putObject("reason").put("type", "string").put("description", случай.describe("reason"))
        val обязательные = связь.putArray("required").add(случай.sourceKey)
        случай.targetFields.keys.forEach { обязательные.add(it) }
        обязательные.add("reason")
        val корень = mapper.createObjectNode().put("type", "object")
        корень.putObject("properties").putObject(случай.answerKey).put("type", "array").set<JsonNode>("items", связь)
        корень.putArray("required").add(случай.answerKey)
        return корень
    }

    /** Ответ в обёртке вызова инструмента (`{"parameters": {…}}`) разворачивается. */
    private fun развернуть(узел: JsonNode): JsonNode {
        if (узел.path(случай.answerKey).isArray) return узел
        val один = узел.properties().singleOrNull()?.takeIf { it.value.isObject } ?: return узел
        return if (один.value.path(случай.answerKey).isArray) один.value else узел
    }

    // --- поле --------------------------------------------------------------

    private fun запуск(область: Area, код: String): Entity =
        store.byCode(область, код)?.takeIf { наша(it) } ?: throw NoSuchElementException("${случай.runWord} «$код» в проекте нет")

    private fun наша(запись: Entity): Boolean =
        запись.kind == "synthesis_run" && запись.doc.path("tags").any { it.asText() == случай.tag }

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

    private fun следующий(область: Area, вид: String, префикс: String): String {
        val занято = store.list(область, вид).mapNotNull {
            Regex("^$префикс-(\\d+)$").find(it.code)?.groupValues?.get(1)?.toIntOrNull()
        }
        return "%s-%04d".format(префикс, (занято.maxOrNull() ?: 0) + 1)
    }

    companion object {
        const val СНЯТО: String = "cancelled"
        const val БЮДЖЕТ: Int = 16000

        fun формулировка(запись: Entity): String =
            listOf("statement", "name", "text", "title", "designation")
                .firstNotNullOfOrNull { запись.doc.path(it).asText("").trim().ifBlank { null } } ?: запись.code

        fun живые(store: EntityStore, область: Area, вид: String): List<Entity> =
            store.list(область, вид).filter { it.status != СНЯТО }.sortedBy { it.code }
    }
}
