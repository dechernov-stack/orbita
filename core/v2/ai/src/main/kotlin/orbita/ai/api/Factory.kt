// Фабрика живого контура: наружу отдаются порты, реализация невидима.
package orbita.ai.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.ai.internal.Atomizer
import orbita.ai.internal.BackgroundAtomizer
import orbita.ai.internal.BackgroundSynthesizer
import orbita.ai.internal.HttpTransport
import orbita.ai.internal.JournalService
import orbita.ai.internal.RetryingTransport
import orbita.ai.internal.SemanticReconciler
import orbita.ai.internal.Synthesizer
import orbita.kernel.api.EntityStore
import orbita.knowledge.api.FactIntake
import orbita.knowledge.api.Intake
import orbita.knowledge.api.Reconcile

/** Разбор материала живым вызовом: канон → факты с якорями. */
fun interface Atomize {
    fun atomize(project: String, material: String, intent: String, author: String): FactIntake
}

object AiFactory {

    /** Транспорт по умолчанию: прямой канал с тремя попытками при перегрузке. */
    fun transport(mapper: ObjectMapper = ObjectMapper()): Transport =
        RetryingTransport(HttpTransport(mapper))

    fun service(
        store: EntityStore,
        transport: Transport = transport(),
        mapper: ObjectMapper = ObjectMapper(),
    ): AiService = JournalService(store, transport, mapper)

    fun atomize(
        store: EntityStore,
        intake: Intake,
        service: AiService,
        mapper: ObjectMapper = ObjectMapper(),
    ): Atomize {
        val работник = Atomizer(store, intake, service, mapper)
        return Atomize { project, material, intent, author ->
            работник.atomize(project, material, intent, author)
        }
    }

    /** Разбор фоновой задачей (ADR-069): сеть в фоне, база на потоке запросов. */
    fun atomizeJobs(
        store: EntityStore,
        intake: Intake,
        service: AiService,
        mapper: ObjectMapper = ObjectMapper(),
    ): AtomizeJobs = BackgroundAtomizer(Atomizer(store, intake, service, mapper), service)

    // ——— Поле знаний v2: синтез и вторая ступень сверки ———
    //
    // Здесь же объявлен договор о реализациях: `Synthesizer(store, intake,
    // service, mapper)`, `BackgroundSynthesizer(synthesizer, service)` и
    // `SemanticReconciler(store, deterministic, service, mapper)`. Наружу они
    // не видны — снаружи модуль виден только портами этого пакета.

    /**
     * Синтез постановки из поля: срез поля → предложения понятий.
     *
     * Служба отдаёт ПРЕДЛОЖЕНИЯ и принятое не меняет: единственный путь
     * изменения модели — сверка. Знания службе нужны целиком (факты, темы,
     * принятые сущности), поэтому `Intake` приходит сюда, как и разбору.
     */
    fun synthesize(
        store: EntityStore,
        intake: Intake,
        service: AiService,
        mapper: ObjectMapper = ObjectMapper(),
    ): Synthesize {
        val работник = Synthesizer(store, intake, service, mapper)
        return Synthesize { project, trigger, author -> работник.synthesize(project, trigger, author) }
    }

    /**
     * Синтез фоновой задачей (ADR-069): сеть в фоне, база на потоке запросов.
     *
     * Синтез идёт по всему полю и считается минутами; на однопоточном стенде
     * прямой вызов означает молчание сервера всё это время — та же беда, из-за
     * которой разбор уехал в фон.
     */
    fun synthesisJobs(
        store: EntityStore,
        intake: Intake,
        service: AiService,
        mapper: ObjectMapper = ObjectMapper(),
    ): SynthesisJobs = BackgroundSynthesizer(Synthesizer(store, intake, service, mapper), service)

    /**
     * Сверка с семантической ступенью поверх детерминированной.
     *
     * Ступень 1 (ключ идентичности, без токенов) приходит сюда ГОТОВОЙ —
     * `deterministic` из знаний — и остаётся первой: живой вызов идёт только
     * там, где ключ не совпал. Порядок ступеней и есть гарантия «сначала
     * бесплатно, потом токены» (мера: дубль по ключу найден без прироста
     * журнала ИИ), поэтому собирать ступень 1 здесь заново нельзя.
     */
    fun reconcile(
        store: EntityStore,
        deterministic: Reconcile,
        service: AiService,
        mapper: ObjectMapper = ObjectMapper(),
    ): Reconcile = SemanticReconciler(store, deterministic, service, mapper)
}
