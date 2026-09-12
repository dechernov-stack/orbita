// Синтез фоновой задачей (ADR-069): в фоне живёт ТОЛЬКО сеть.
//
// Та же беда, из-за которой в фон уехал разбор: сервер стенда однопоточный, а
// синтез по всему полю идёт минутами — пока он шёл бы прямым вызовом, стенд не
// отвечал бы вовсе. Поэтому схема повторяется буква в букву: срез поля
// читается на потоке запросов (`Synthesizer.prepare`), в фоне выполняется один
// сетевой вызов, ответ применяется — карточки предложений и диф запуска —
// снова на потоке запросов, при следующем обращении к заданию. База из
// фонового потока не трогается никогда.
//
// Второе здешнее правило — ОКНО НАКОПЛЕНИЯ. Событие поля (загрузили материал,
// приняли факты, поправили принятое) приходит пачками: разбор одного ТЗ даёт
// сотню фактов, и синтез на каждый факт означал бы сотню живых вызовов на один
// документ. События копятся в окно (SYNTHESIS_DEBOUNCE) и дают ОДИН запуск;
// окно закрывается раньше срока, когда поле приросло на целый потолок среза
// или когда человек нажал «Сформировать постановку из поля» — рука ждать не
// обязана.
//
// Рука не заводит второй запуск поверх открытого окна: живой вызов один на
// срез, и нажатие лишь закрывает уже открытое окно немедленно.
package orbita.ai.internal

import orbita.ai.api.AiService
import orbita.ai.api.Answer
import orbita.ai.api.FieldDrift
import orbita.ai.api.ProviderUnavailable
import orbita.ai.api.SynthesisJobs
import orbita.ai.api.SynthesisRun
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * @param debounce окно накопления событий поля; задаётся в тесте, чтобы
 *   проверять закрытие окна, а не ждать его
 * @param clock часы окна — тем же способом: время в тесте задаётся, а не ждётся
 */
class BackgroundSynthesizer(
    private val synthesizer: Synthesizer,
    private val service: AiService,
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "orbita-synthesis").apply { isDaemon = true }
        },
    private val debounce: Duration = SYNTHESIS_DEBOUNCE,
    private val clock: () -> OffsetDateTime = { OffsetDateTime.now() },
) : SynthesisJobs {

    /** Окно накопления: один запуск синтеза на пачку событий поля. */
    private class Окно(
        val run: String,
        val project: String,
        val author: String,
        val openedAt: OffsetDateTime,
        /** Сколько элементов было в поле, когда окно открылось. */
        val открытоПри: Int,
    ) {
        /** Последний собранный срез: его читает фоновый поток — базы там нет. */
        @Volatile
        var slice: Synthesizer.Slice? = null

        @Volatile
        var answer: Answer? = null

        @Volatile
        var error: String? = null

        /** Ответ применён (или отказ записан): окно своё отжило. */
        @Volatile
        var applied: Boolean = false

        /** Сетевой вызов ушёл: второй на тот же срез не уходит никогда. */
        val отправлено = AtomicBoolean(false)
    }

    /** Открытое окно проекта: событие присоединяется к нему, а не заводит второе. */
    private val окна = ConcurrentHashMap<String, Окно>()

    /** Задания по коду запуска: по ним `poll` находит готовый ответ. */
    private val задания = ConcurrentHashMap<String, Окно>()

    override fun start(project: String, trigger: String, author: String): SynthesisRun {
        val открытое = окна[project]
        if (открытое != null) {
            if (принимает(открытое)) return присоединить(открытое, trigger)
            // Окно отжило своё, а вызов почему-то не ушёл: отправляем его и
            // заводим новое — молча потерянный запуск хуже лишнего.
            отправить(открытое)
            окна.remove(project, открытое)
        }
        return открыть(project, trigger, author)
    }

    override fun poll(project: String, id: String): SynthesisRun? {
        // Задания нет в памяти — запуск прежнего процесса: он лежит в поле, и
        // показать его всё равно надо.
        val окно = задания[id]?.takeIf { it.project == project } ?: return synthesizer.view(project, id)
        // Окно ещё копит события, а срок вышел: закрываем его здесь, не
        // полагаясь на один только таймер.
        if (!окно.отправлено.get() && !свежо(окно)) отправить(окно)
        if (!окно.applied) {
            if (окно.answer != null) return применить(окно)
            окно.error?.let { беда ->
                окно.applied = true
                окна.remove(окно.project, окно)
                return synthesizer.fail(окно.project, окно.run, беда)
            }
            // Вызов ушёл, ответа ещё нет: запись переводится в «идёт» здесь —
            // фоновый поток базу не трогает.
            if (окно.отправлено.get()) return synthesizer.running(окно.project, окно.run)
        }
        return вид(окно)
    }

    override fun list(project: String): List<SynthesisRun> = synthesizer.list(project)

    override fun pending(project: String): FieldDrift = synthesizer.drift(project)

    // --- окно ----------------------------------------------------------------

    private fun принимает(окно: Окно): Boolean =
        !окно.отправлено.get() && !окно.applied && свежо(окно)

    private fun свежо(окно: Окно): Boolean = clock().isBefore(окно.openedAt.plus(debounce))

    /**
     * Событие присоединяется к открытому окну: срез перечитывается ЗДЕСЬ, на
     * потоке запросов, и фоновый вызов уйдёт по последнему состоянию поля.
     */
    private fun присоединить(окно: Окно, trigger: String): SynthesisRun {
        val срез = synthesizer.prepare(окно.project)
        окно.slice = срез
        if (trigger == РУЧНОЙ || набралось(окно, срез)) отправить(окно)
        return вид(окно)
    }

    /**
     * Окно набрало потолок: ждать его конца больше незачем.
     *
     * Считается ПРИРОСТ поля с открытия окна, а не размер среза: срез на живом
     * проекте и так упирается в потолок, и сравнение с ним закрывало бы окно
     * на каждом событии — то есть отменяло бы накопление вовсе.
     */
    private fun набралось(окно: Окно, срез: Synthesizer.Slice): Boolean =
        срез.total - окно.открытоПри >= Synthesizer.ПОТОЛОК

    private fun открыть(project: String, trigger: String, author: String): SynthesisRun {
        val срез = synthesizer.prepare(project)
        val запуск = synthesizer.open(project, trigger, author, срез)
        val окно = Окно(запуск.id, project, author, clock(), срез.total)
        окно.slice = срез
        задания[запуск.id] = окно
        окна[project] = окно
        // Ответ на этот срез уже в журнале — применяем сразу: фон не нужен,
        // вызова не будет (один живой вызов на срез).
        synthesizer.cached(project, срез)?.let { ответ ->
            окно.отправлено.set(true)
            окно.answer = ответ
            return применить(окно)
        }
        if (trigger == РУЧНОЙ) отправить(окно)
        else executor.schedule(Runnable { отправить(окно) }, debounce.toMillis(), TimeUnit.MILLISECONDS)
        return вид(окно)
    }

    /** Отправка одна на окно: повторный заход отбивается сравнением с обменом. */
    private fun отправить(окно: Окно) {
        if (!окно.отправлено.compareAndSet(false, true)) return
        val срез = окно.slice ?: return
        executor.submit(Runnable { спросить(окно, срез) })
    }

    /** Фоновый поток: только сеть. Ни чтения, ни записи базы (ADR-069). */
    private fun спросить(окно: Окно, срез: Synthesizer.Slice) {
        try {
            окно.answer = service.askDetached(срез.prompt, null, Synthesizer.БЮДЖЕТ_СИНТЕЗА)
        } catch (e: ProviderUnavailable) {
            окно.error = "синтез недоступен: ${e.message}"
        } catch (e: Exception) {
            окно.error = "синтез не удался: ${e.message}"
        }
    }

    /** Приём ответа — на потоке запросов; ошибка приёма не теряется, а называется. */
    private fun применить(окно: Окно): SynthesisRun {
        val ответ = окно.answer ?: return вид(окно)
        val срез = окно.slice ?: return вид(окно)
        окно.applied = true
        // Окно закрыто: следующее событие поля откроет новое.
        окна.remove(окно.project, окно)
        return try {
            synthesizer.apply(окно.project, окно.run, окно.author, срез, ответ, journaled = false)
        } catch (e: Exception) {
            synthesizer.fail(окно.project, окно.run, "ответ получен, но не принят: ${e.message}")
        }
    }

    private fun вид(окно: Окно): SynthesisRun =
        synthesizer.view(окно.project, окно.run)
            ?: error("запуска синтеза «${окно.run}» нет в проекте «${окно.project}»")

    companion object {

        /**
         * Окно накопления событий поля. Тридцать секунд — время, за которое
         * разбор одного документа успевает выложить свои факты: синтез на
         * каждый факт означал бы сотню живых вызовов на один документ.
         */
        val SYNTHESIS_DEBOUNCE: Duration = Duration.ofSeconds(30)

        /**
         * Причина запуска рукой. Перечень причин живёт в истине схем (вид
         * `synthesis_run`), здесь нужна ровно одна: рука ждать окна не обязана.
         */
        const val РУЧНОЙ: String = "manual"
    }
}
