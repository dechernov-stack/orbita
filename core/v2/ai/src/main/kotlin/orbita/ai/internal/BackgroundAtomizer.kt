// Разбор фоновой задачей (ADR-069, предусловие ПМИ-5): сервер стенда
// однопоточный, а разбор ТЗ живой моделью идёт минуты — пока он шёл,
// стенд не отвечал и healthcheck падал. Здесь в фоне живёт ТОЛЬКО сеть:
// промпт собирается на потоке запросов, ответ применяется (журнал + приём
// фактов) тоже на потоке запросов — при следующем обращении к заданию.
// База из фонового потока не трогается никогда.
package orbita.ai.internal

import orbita.ai.api.AiService
import orbita.ai.api.Answer
import orbita.ai.api.AtomizeJob
import orbita.ai.api.AtomizeJobs
import orbita.ai.api.ProviderUnavailable
import orbita.knowledge.api.FactIntake
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class BackgroundAtomizer(
    private val atomizer: Atomizer,
    private val service: AiService,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "orbita-atomize").apply { isDaemon = true } },
) : AtomizeJobs {

    private class Задание(
        val id: String,
        val project: String,
        val material: String,
        val intent: String,
        val author: String,
        val prompt: String,
        val startedAt: OffsetDateTime,
    ) {
        @Volatile var answer: Answer? = null
        @Volatile var error: String? = null
        @Volatile var result: FactIntake? = null
        @Volatile var applyError: String? = null
    }

    private val задания = ConcurrentHashMap<String, Задание>()
    private val счётчик = AtomicInteger(0)

    override fun start(project: String, material: String, intent: String, author: String): AtomizeJob {
        val промпт = atomizer.prepare(project, material, intent)
        val id = "JOB-%04d".format(счётчик.incrementAndGet())
        val задание = Задание(id, project, material, intent, author, промпт, OffsetDateTime.now())
        // Ответ уже в журнале — применяем сразу, фон не нужен.
        service.cached(project, промпт)?.let { ответ ->
            задание.answer = ответ
            задания[id] = задание
            return применить(задание)
        }
        задания[id] = задание
        executor.submit {
            try {
                задание.answer = service.askDetached(промпт, null, Atomizer.БЮДЖЕТ_РАЗБОРА)
            } catch (e: ProviderUnavailable) {
                задание.error = "живой разбор недоступен: ${e.message}"
            } catch (e: Exception) {
                задание.error = "разбор не удался: ${e.message}"
            }
        }
        return вид(задание)
    }

    override fun poll(project: String, id: String): AtomizeJob? {
        val задание = задания[id]?.takeIf { it.project == project } ?: return null
        if (задание.result == null && задание.applyError == null && задание.answer != null) return применить(задание)
        return вид(задание)
    }

    override fun list(project: String): List<AtomizeJob> =
        задания.values.filter { it.project == project }.sortedBy { it.startedAt }.map { вид(it) }

    /** Приём ответа — на потоке запросов; ошибка приёма не теряется, а называется. */
    private fun применить(задание: Задание): AtomizeJob {
        val ответ = задание.answer ?: return вид(задание)
        try {
            задание.result = atomizer.apply(задание.project, задание.material, задание.intent, задание.author, ответ, journaled = false, prompt = задание.prompt)
        } catch (e: Exception) {
            задание.applyError = "ответ получен, но не принят: ${e.message}"
        }
        return вид(задание)
    }

    private fun вид(з: Задание): AtomizeJob {
        val итог = з.result
        val статус = when {
            итог != null -> "done"
            з.applyError != null || з.error != null -> "failed"
            else -> "running"
        }
        return AtomizeJob(
            id = з.id, project = з.project, material = з.material, status = статус,
            startedAt = з.startedAt.toString(),
            elapsedSeconds = Duration.between(з.startedAt, OffsetDateTime.now()).seconds,
            task = итог?.task, note = итог?.note,
            accepted = итог?.accepted?.size ?: 0, refused = итог?.refused?.size ?: 0,
            refusals = итог?.refused.orEmpty(),
            error = з.applyError ?: з.error,
        )
    }
}
