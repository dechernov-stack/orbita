// Вызовы службы фоновой задачей (ADR-069, живой отказ ПМИ-5 11.09): сервер
// однопоточный, вызов модели идёт минуты, а путь браузера до стенда рвал
// соединение на 60-й секунде — ответ терялся, повтор запускал новый вызов, и
// api стоял, пока дожёвывал очередь. Здесь в фоне живёт ТОЛЬКО сеть: промпт
// собирается на потоке запросов, фильтр и журнал применяются на потоке
// запросов при следующем опросе задания. База из фонового потока не
// трогается никогда. Реестр — в памяти api: перезапуск теряет идущее
// задание, и опрос честно говорит «повторите».
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.ai.ProviderAnswer
import orbita.ai.ProviderUnavailableException
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class AiJobs(
    private val service: AiService,
    private val mapper: ObjectMapper = ObjectMapper(),
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "orbita-ai-ask").apply { isDaemon = true } },
) {
    /** Что делать с ответом: пачка предложений на акцепт (ask) либо сырой документ (raw). */
    enum class Mode { ASK, RAW }

    class Job(
        val id: String,
        val projectId: String,
        val kind: String,
        val mode: Mode,
        val prepared: AiService.Prepared,
        val startedAt: OffsetDateTime,
    ) {
        @Volatile var answer: ProviderAnswer? = null
        @Volatile var failure: String? = null
        @Volatile var run: AiServiceRun? = null
        @Volatile var raw: AiService.RawAnswer? = null
        @Volatile var applyError: String? = null
        val finished: Boolean get() = run != null || raw != null || applyError != null
        val status: String get() = when {
            applyError != null -> "failed"
            run != null && run!!.report.path("failed").asBoolean(false) -> "failed"
            raw != null && raw!!.failure != null -> "failed"
            run != null || raw != null -> "done"
            else -> "running"
        }
    }

    private val jobs = ConcurrentHashMap<String, Job>()
    private val counter = AtomicInteger(0)

    /** Промпт — на потоке запросов; сеть — в фоне. Отказ подготовки (нет профиля, закрытый контур) — сразу исключением. */
    fun start(kind: String, profileId: String, projectId: String, statement: String, author: String, mode: Mode): Job {
        val prepared = service.prepare(kind, profileId, projectId, statement, author)
        val job = Job("AJ-%04d".format(counter.incrementAndGet()), projectId, kind, mode, prepared, OffsetDateTime.now())
        jobs[job.id] = job
        executor.submit {
            try {
                job.answer = service.call(prepared)
            } catch (e: ProviderUnavailableException) {
                job.failure = e.message ?: "провайдер недоступен"
            } catch (e: Exception) {
                job.failure = "вызов не удался: ${e.message}"
            }
        }
        return job
    }

    /** Опрос: готовый ответ применяется здесь — фильтр и журнал на потоке запросов. */
    fun poll(projectId: String, id: String): Job? {
        val job = jobs[id]?.takeIf { it.projectId == projectId } ?: return null
        if (!job.finished && (job.answer != null || job.failure != null)) {
            try {
                when (job.mode) {
                    Mode.ASK -> job.run = service.finishAsk(job.prepared, job.answer, job.failure)
                    Mode.RAW -> job.raw = service.finishRaw(job.prepared, job.answer, job.failure)
                }
            } catch (e: Exception) {
                job.applyError = "ответ получен, но не принят: ${e.message}"
            }
        }
        return job
    }

    fun list(projectId: String): List<Job> = jobs.values.filter { it.projectId == projectId }.sortedBy { it.startedAt }

    fun view(job: Job): ObjectNode {
        val out = mapper.createObjectNode()
        out.put("job", job.id).put("status", job.status).put("kind", job.kind).put("mode", job.mode.name.lowercase())
            .put("started_at", job.startedAt.toString())
            .put("elapsed_seconds", Duration.between(job.startedAt, OffsetDateTime.now()).seconds)
        job.run?.let { out.set<ObjectNode>("report", it.report) }
        job.raw?.let { r ->
            val узел = out.putObject("raw")
            узел.put("call", r.call)
            r.text?.let { узел.put("text", it) }
            r.model?.let { узел.put("model", it) }
            r.failure?.let { узел.put("failure", it) }
        }
        (job.applyError ?: job.failure?.takeIf { job.finished.not() })?.let { out.put("error", it) }
        return out
    }
}
