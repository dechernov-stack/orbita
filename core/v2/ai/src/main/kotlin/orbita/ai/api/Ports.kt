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
    /**
     * @param maxTokens потолок ответа. Разбору документа его задаёт вызов:
     *   урожай в сто фактов не помещается в бюджет короткого ответа, а
     *   обрыв на полуслове — не ответ (поймано на записке в 36 тыс. знаков)
     */
    fun ask(prompt: String, model: String?, maxTokens: Int?): Answer
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
    fun ask(
        project: String,
        kind: String,
        prompt: String,
        model: String? = null,
        maxTokens: Int? = null,
    ): Answer

    /** Ответ из журнала по отпечатку промпта — без вызова; null, если такого не было. */
    fun cached(project: String, prompt: String): Answer?

    /**
     * Чистый вызов провайдера — только сеть, без чтения и записи базы:
     * его можно выполнять в фоновом потоке (ADR-069: разбор фоновой задачей).
     */
    fun askDetached(prompt: String, model: String? = null, maxTokens: Int? = null): Answer

    /** Записать ответ в журнал вызовов (на потоке запросов; база — только здесь). */
    fun record(project: String, kind: String, prompt: String, answer: Answer)

    fun journal(project: String): List<CallRecord>
}

/** Состояние фонового разбора: идёт · готов (задание с планом) · не удался. */
data class AtomizeJob(
    val id: String,
    val project: String,
    val material: String,
    val status: String,
    val startedAt: String,
    val elapsedSeconds: Long,
    val task: String? = null,
    val note: String? = null,
    val accepted: Int = 0,
    val refused: Int = 0,
    val refusals: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * Разбор фоновой задачей: сеть — в фоне, база — на потоке запросов.
 * `start` возвращает задание сразу (либо готовый итог, если ответ уже в
 * журнале); `poll` применяет готовый ответ при следующем обращении.
 */
interface AtomizeJobs {
    fun start(project: String, material: String, intent: String, author: String): AtomizeJob
    fun poll(project: String, id: String): AtomizeJob?
    fun list(project: String): List<AtomizeJob>
}
