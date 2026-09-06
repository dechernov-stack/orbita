// Живой контур: вызов модели с журналом и кэшем (ЗАДАНИЕ-ПОЛЕ-ЗНАНИЙ §0).
//
// Решение владельца: разбор входного документа — ЖИВОЙ вызов, разрешён;
// один на версию документа, результат кэшируется отпечатком. Всё остальное
// по-прежнему пакетами. Отсюда устройство порта: служба спрашивает не
// «сделай», а «дай ответ на этот промпт», и сама решает, звонить ли —
// повтор той же версии вызова не делает.
package orbita.ai.api

/** Ответ провайдера: текст и учёт — журналу нужно «сколько и почём». */
data class Answer(
    val text: String,
    val model: String,
    val tokensIn: Int?,
    val tokensOut: Int?,
    /** true — ответ взят из журнала по отпечатку, вызова не было. */
    val cached: Boolean = false,
)

/** Канал недоступен: ключа нет, провайдер перегружен, поток оборван. */
class ProviderUnavailable(reason: String) : RuntimeException(reason)

/**
 * Транспорт к модели. Подменяется в тестах: сеть в тесте не нужна, а
 * поведение службы (кэш, журнал, повтор) проверяется без неё.
 */
fun interface Transport {
    fun ask(prompt: String, model: String?): Answer
}

/** Запись журнала вызовов: по ней видно, за что заплачено. */
data class CallRecord(
    val kind: String,
    val model: String,
    val fingerprint: String,
    val tokensIn: Int?,
    val tokensOut: Int?,
    val at: String,
    val cached: Boolean,
)

interface AiService {
    /**
     * Ответ модели на промпт. Один вызов на отпечаток: повтор того же
     * промпта в том же проекте возвращает записанный ответ и НЕ звонит.
     */
    fun ask(project: String, kind: String, prompt: String, model: String? = null): Answer

    fun journal(project: String): List<CallRecord>
}
