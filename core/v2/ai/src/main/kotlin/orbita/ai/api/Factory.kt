// Фабрика живого контура: наружу отдаются порты, реализация невидима.
package orbita.ai.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.ai.internal.Atomizer
import orbita.ai.internal.BackgroundAtomizer
import orbita.ai.internal.BackgroundSynthesizer
import orbita.ai.internal.DocumentReader
import orbita.ai.internal.GoalCoverageDistributor
import orbita.ai.internal.NeedDistributor
import orbita.ai.internal.StatementImporter
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

/**
 * Чтение документа в постановку одним вызовом (РЕШЕНИЕ-ЧИТАТЬ-СМЫСЛ, 15.09).
 * Прочитанное ложится запуском постановки — тем же экраном и тем же акцептом,
 * что и синтез из поля.
 */
fun interface ReadDocument {
    fun read(project: String, material: String, author: String): SynthesisRun
}

/**
 * Эталон постановки → предложения (ПМИ-7): чего чтение не добрало против
 * эталона, добирается пакетом тем же экраном и тем же акцептом.
 */
fun interface ImportStatement {
    fun import(project: String, material: String, statement: com.fasterxml.jackson.databind.JsonNode, author: String): SynthesisRun
}

/**
 * Раздача нужд по целям и сервисам одним вызовом (решение владельца 17.09):
 * полные перечни проекта → карта связей предложениями; приём массовый и
 * обратимый.
 */
interface DistributeNeeds {
    fun distribute(project: String, author: String): DistributionRun
    fun latest(project: String): DistributionRun?
    fun view(project: String, run: String): DistributionRun
    fun accept(project: String, run: String, chosen: List<String>, author: String, reason: String): DistributionAccepted
    fun undo(project: String, run: String, author: String): DistributionUndone
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

    /**
     * Чтение документа в постановку. Собирается из двух внутренних работников:
     * читатель добывает понятия с цитатами, синтез кладёт их запуском — форма
     * записи на экране одна, и второй её копии нет.
     */
    fun readDocument(
        store: EntityStore,
        intake: Intake,
        service: AiService,
        mapper: ObjectMapper = ObjectMapper(),
    ): ReadDocument {
        val читатель = DocumentReader(store, intake, service, mapper)
        val синтез = Synthesizer(store, intake, service, mapper)
        return ReadDocument { project, material, author ->
            читатель.readInto(project, material, author, синтез)
        }
    }

    /** Эталон постановки предложениями — той же записью запуска, что и чтение. */
    fun importStatement(
        store: EntityStore,
        intake: Intake,
        service: AiService,
        mapper: ObjectMapper = ObjectMapper(),
    ): ImportStatement {
        val импорт = StatementImporter(store, intake, mapper)
        val синтез = Synthesizer(store, intake, service, mapper)
        return ImportStatement { project, material, statement, author ->
            импорт.import(project, material, statement, author, синтез)
        }
    }

    /** Раздача нужд по целям и сервисам — один вызов, приём обратимый. */
    fun distributeNeeds(
        store: EntityStore,
        links: orbita.kernel.api.LinkRegistry?,
        service: AiService,
        mapper: ObjectMapper = ObjectMapper(),
    ): DistributeNeeds {
        val раздача = NeedDistributor(store, links, service, mapper)
        return object : DistributeNeeds {
            override fun distribute(project: String, author: String) = раздача.distribute(project, author)
            override fun latest(project: String) = раздача.latest(project)
            override fun view(project: String, run: String) = раздача.view(project, run)
            override fun accept(project: String, run: String, chosen: List<String>, author: String, reason: String) =
                раздача.accept(project, run, chosen, author, reason)
            override fun undo(project: String, run: String, author: String) = раздача.undo(project, run, author)
        }
    }

    /**
     * Раздача требований по целям — один вызов, приём обратимый (20.09).
     *
     * Порядок тот же, что у раздачи нужд: предложения, отметки, «Принять»,
     * «Отменить». Разница в том, ЧТО записывается: не связь, а источник
     * требования — условие сцены 8 смотрит на него.
     */
    fun distributeGoals(
        store: EntityStore,
        service: AiService,
        mapper: ObjectMapper = ObjectMapper(),
    ): DistributeNeeds {
        val раздача = GoalCoverageDistributor(store, service, mapper)
        return object : DistributeNeeds {
            override fun distribute(project: String, author: String) = раздача.distribute(project, author)
            override fun latest(project: String) = раздача.latest(project)
            override fun view(project: String, run: String) = раздача.view(project, run)
            override fun accept(project: String, run: String, chosen: List<String>, author: String, reason: String) =
                раздача.accept(project, run, chosen, author, reason)
            override fun undo(project: String, run: String, author: String) = раздача.undo(project, run, author)
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
