// Связный текст и печать — фоновыми заданиями (шип 4 §4, по образцу ADR-069).
//
// Сервер стенда однопоточный: живая модель пишет раздел минуту, Typst
// собирает пакет секунды — пока это шло на потоке запросов, стенд молчал.
// Здесь в фоне живёт ТОЛЬКО сеть и компиляция: промпт и вид печати
// собираются на потоке запросов (это чтение базы), ответ модели применяется
// тоже на потоке запросов — при следующем обращении к заданию. База из
// фонового потока не трогается никогда.
package orbita.api.internal

import orbita.ai.api.AiService
import orbita.ai.api.Answer
import orbita.ai.api.ProviderUnavailable
import orbita.documents.api.Documents
import orbita.documents.api.PrintView
import orbita.documents.api.Printed
import orbita.documents.api.RenderedSection
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class DocumentJobs(
    private val documents: Documents,
    /** Служба ИИ — для связного текста; null — писать нечем, печать работает. */
    private val service: AiService? = null,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "orbita-documents").apply { isDaemon = true } },
) {

    /** Состояние задания наружу: что делает, идёт ли, чем кончилось. */
    data class View(
        val id: String,
        val project: String,
        val document: String,
        val kind: String,
        val section: String?,
        val status: String,
        val startedAt: String,
        val elapsedSeconds: Long,
        val result: RenderedSection?,
        val engine: String?,
        val size: Int,
        val error: String?,
    )

    private class Задание(
        val id: String,
        val project: String,
        val document: String,
        val kind: String,
        val section: String?,
        val author: String,
        val prompt: String?,
        val engine: String?,
        val startedAt: OffsetDateTime,
    ) {
        @Volatile var answer: Answer? = null
        @Volatile var result: RenderedSection? = null
        @Volatile var printed: Printed? = null
        @Volatile var error: String? = null
    }

    private val задания = ConcurrentHashMap<String, Задание>()
    private val счётчик = AtomicInteger(0)

    /** Связный текст: промпт — здесь, сеть — в фоне; ответ из журнала применяется сразу. */
    fun startWrite(project: String, document: String, section: String, author: String): View {
        val служба = service ?: throw IllegalStateException("живая модель не подключена: связный текст писать нечем — проверьте ORBITA_AI_KEY у службы")
        val промпт = documents.prepareWrite(project, document, section)
        val задание = Задание(код(), project, document, "write", section, author, промпт.prompt, null, OffsetDateTime.now())
        задания[задание.id] = задание
        служба.cached(project, промпт.prompt)?.let { ответ ->
            задание.answer = ответ
            return применить(задание)
        }
        executor.submit {
            try {
                задание.answer = служба.askDetached(промпт.prompt)
            } catch (e: ProviderUnavailable) {
                задание.error = "живая модель недоступна: ${e.message}"
            } catch (e: Exception) {
                задание.error = "связный текст не написан: ${e.message}"
            }
        }
        return вид(задание)
    }

    /** Печать: вид собирается здесь (чтение базы), байты — в фоне (Typst или PDFBox). */
    fun startPrint(project: String, document: String, projectName: String, engine: String): View {
        val вид: PrintView = documents.render(project, document)
        val задание = Задание(код(), project, document, "print", null, "", null, engine, OffsetDateTime.now())
        задания[задание.id] = задание
        executor.submit {
            try {
                задание.printed = documents.printBytes(вид, document, projectName, engine)
                println("orbita documents: печать $document движком ${задание.printed?.engine} за ${Duration.between(задание.startedAt, OffsetDateTime.now()).toMillis() / 1000.0} с")
            } catch (e: Exception) {
                задание.error = e.message ?: "печать не состоялась"
            }
        }
        return вид(задание)
    }

    fun poll(project: String, id: String): View? {
        val задание = задания[id]?.takeIf { it.project == project } ?: return null
        if (задание.kind == "write" && задание.result == null && задание.error == null && задание.answer != null) return применить(задание)
        return вид(задание)
    }

    /** Готовый PDF задания печати; null — задания нет или оно ещё идёт. */
    fun printed(project: String, id: String): Printed? = задания[id]?.takeIf { it.project == project }?.printed

    /** Приём ответа — на потоке запросов: журнал, сторож чисел, запись принятого. */
    private fun применить(задание: Задание): View {
        val ответ = задание.answer ?: return вид(задание)
        try {
            if (!ответ.cached) service?.record(задание.project, "document_render", задание.prompt.orEmpty(), ответ)
            задание.result = documents.applyWrite(задание.project, задание.document, задание.section.orEmpty(), задание.author, ответ.text, ответ.model)
            println("orbita documents: связный текст ${задание.document} ${задание.section} — ${ответ.model}, ${ответ.tokensIn ?: 0}/${ответ.tokensOut ?: 0} токенов, ${ответ.seconds ?: 0.0} с${if (ответ.cached) " (из журнала)" else ""}")
        } catch (e: Exception) {
            задание.error = "ответ получен, но не принят: ${e.message}"
        }
        return вид(задание)
    }

    private fun код(): String = "DJ-%04d".format(счётчик.incrementAndGet())

    private fun вид(з: Задание): View {
        val статус = when {
            з.error != null -> "failed"
            з.result != null || з.printed != null -> "done"
            else -> "running"
        }
        return View(
            id = з.id, project = з.project, document = з.document, kind = з.kind, section = з.section,
            status = статус, startedAt = з.startedAt.toString(),
            elapsedSeconds = Duration.between(з.startedAt, OffsetDateTime.now()).seconds,
            result = з.result, engine = з.printed?.engine ?: з.engine?.takeIf { з.kind == "print" },
            size = з.printed?.bytes?.size ?: 0, error = з.error,
        )
    }
}
