// Прямой API-канал для структурных операций малого объёма (TZ-AI-005).
//
// Транспорт ИНЖЕКТИРУЕТСЯ: сам вызов провайдера — инфраструктура, которой в этом
// шаге нет (ни провайдер, ни учётные данные не заданы). Здесь реализовано то,
// что требованием и является: перечень операций конфигурируем, результат
// валидируется по схеме целевого объекта, а отказ или ошибка API не нарушают
// состояние модели.
package orbita.ai

import com.fasterxml.jackson.databind.JsonNode
import orbita.mod.schema.SchemaRegistry
import orbita.mod.schema.ValidationError

/** Транспорт прямого канала. Реализация живёт вне ядра: ядро её только вызывает. */
fun interface StructuralApi {
    /** @throws Exception любая ошибка транспорта — канал обязан её пережить. */
    fun call(operation: String, payload: JsonNode): JsonNode
}

/** Структурная операция малого объёма и схема, которой обязан отвечать результат. */
data class StructuralOperation(val id: String, val targetSchema: String)

/** Исход вызова: состояние модели меняется только при Success. */
sealed interface ChannelResult {
    data class Success(val value: JsonNode) : ChannelResult
    data class Rejected(val errors: List<ValidationError>) : ChannelResult
    data class Failed(val reason: String) : ChannelResult
}

class DirectChannel(
    private val api: StructuralApi,
    private val registry: SchemaRegistry,
    private val operations: Map<String, StructuralOperation> = defaultOperations(),
) {

    val operationIds: Set<String> get() = operations.keys

    /**
     * Вызов операции. Ни отказ транспорта, ни несоответствие схеме не меняют
     * состояние модели: функция ничего не записывает — она возвращает исход,
     * и решение о применении принимает вызывающий после акцепта (TZ-AI-004).
     */
    fun invoke(operation: String, payload: JsonNode): ChannelResult {
        val op = operations[operation]
            ?: return ChannelResult.Failed("операция $operation не входит в перечень канала")
        val raw = try {
            api.call(operation, payload)
        } catch (e: Exception) {
            return ChannelResult.Failed("отказ API: ${e.message ?: e::class.simpleName}")
        }
        val errors = registry.validate(op.targetSchema, raw)
        return if (errors.isEmpty()) ChannelResult.Success(raw) else ChannelResult.Rejected(errors)
    }

    companion object {
        /** Перечень операций конфигурируем: значение по умолчанию — первая очередь. */
        fun defaultOperations(): Map<String, StructuralOperation> = listOf(
            StructuralOperation("decompose_to_components", "core/requirement"),
            StructuralOperation("check_budget_consistency", "core/requirement"),
            StructuralOperation("propose_verification_method", "core/requirement"),
        ).associateBy { it.id }
    }
}
