// Реестр операций процесса (задача «реестры процесса», ADR-029): операции
// О1–О15 / О1–О17 из operations.json и состояние каждой, посчитанное
// сервером по объектам проекта. Эталон — spec/process_backbone.py.
package orbita.req

import com.fasterxml.jackson.databind.ObjectMapper

/** Операция регламента (§5 БП-PPA / БП-PA), дословно из реестра. */
data class Operation(
    val code: String,
    val phase: String,
    val name: String,
    val executor: String,
    val input: String,
    val inputs: List<String>,
    val docs: List<String>,
    val kinds: List<String>,
    val gate: String?,
    val requiredStatus: String?,
    /** Рабочее место операции — ключ экрана клиента (назначен П2 по дизайну). */
    val screen: String? = null,
)

/**
 * Состояние операции. `NotMeasurable` — вид выхода в системе отсутствует
 * (стоимость, ODA до блока C): видимый пробел, а не прочерк.
 */
enum class OperationState { NotStarted, InProgress, Done, NotMeasurable }

data class OperationRow(
    val operation: Operation,
    val state: OperationState,
    /** Сколько объектов видов выхода уже есть в проекте. */
    val objects: Int,
    /** Операция — цель действующего возврата (ADR-029 п. 5). */
    val returnedTo: Boolean = false,
)

/** Допустимые цели возврата точки (§5.1 регламентов, ADR-029 п. 5). */
data class ReturnRule(val phase: String, val gate: String, val to: List<String>)

class Operations(
    private val registry: List<Operation> = load(),
    private val returns: List<ReturnRule> = loadReturns(),
) {

    /** Куда разрешён возврат от точки в фазе; пусто — точка возвратов не имеет. */
    fun returnTargets(phase: String, gate: String): List<String> =
        returns.firstOrNull { it.phase == phase && it.gate == gate }?.to ?: emptyList()


    fun ofPhase(phase: String): List<Operation> = registry.filter { it.phase == phase }

    /** Операции точки — куда идти чинить незакрытое (ADR-029 п. 1). */
    fun ofGate(gate: String): List<Operation> = registry.filter { it.gate == gate }

    /**
     * Состояние операций фазы по снимкам объектов проекта (ADR-029 п. 6):
     * не «строки есть», а «выход достиг требуемого статуса».
     */
    fun states(
        phase: String,
        snapshots: List<ObjectSnapshot>,
        returnedTo: Set<String> = emptySet(),
    ): List<OperationRow> {
        val order = Gates.ORDER
        val byKind = snapshots.filter { it.status != "Cancelled" }.groupBy { it.type }
        return ofPhase(phase).map { op ->
            val relevant = op.kinds.flatMap { byKind[it].orEmpty() }
            val state = when {
                op.kinds.isEmpty() -> OperationState.NotMeasurable
                relevant.isEmpty() -> OperationState.NotStarted
                // статус к точке не требуется — наличие выхода и есть выполнение
                op.requiredStatus == null -> OperationState.Done
                op.kinds.all { kind ->
                    val objs = byKind[kind].orEmpty()
                    objs.isNotEmpty() && objs.all {
                        order.indexOf(it.status) >= order.indexOf(op.requiredStatus)
                    }
                } -> OperationState.Done
                else -> OperationState.InProgress
            }
            OperationRow(op, state, relevant.size, returnedTo = op.code in returnedTo)
        }
    }

    companion object {
        private val mapper = ObjectMapper()

        fun loadReturns(): List<ReturnRule> =
            Operations::class.java.getResourceAsStream("/orbita/req/operations.json")!!
                .use { mapper.readTree(it) }
                .path("returns")
                .map { r ->
                    ReturnRule(
                        phase = r.path("phase").asText(),
                        gate = r.path("gate").asText(),
                        to = r.path("to").map { it.asText() },
                    )
                }

        fun load(): List<Operation> =
            Operations::class.java.getResourceAsStream("/orbita/req/operations.json")!!
                .use { mapper.readTree(it) }
                .path("operations")
                .map { o ->
                    val doc = o.path("output").path("doc")
                    Operation(
                        code = o.path("code").asText(),
                        phase = o.path("phase").asText(),
                        name = o.path("name").asText(),
                        executor = o.path("executor").asText(),
                        input = o.path("input").asText(""),
                        inputs = o.path("inputs").map { it.asText() },
                        docs = when {
                            doc.isNull || doc.isMissingNode -> emptyList()
                            doc.isArray -> doc.map { it.asText() }
                            else -> listOf(doc.asText())
                        },
                        kinds = o.path("output").path("kinds").map { it.asText() },
                        gate = o.path("gate").takeIf { !it.isNull }?.asText(),
                        requiredStatus = o.path("required_status").takeIf { !it.isNull }?.asText(),
                        screen = o.path("screen").takeIf { it.isTextual }?.asText(),
                    )
                }
    }
}
