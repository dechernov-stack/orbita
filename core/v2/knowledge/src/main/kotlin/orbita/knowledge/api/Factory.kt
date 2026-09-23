// Фабрика знаний: наружу отдаётся порт, реализация невидима.
package orbita.knowledge.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.knowledge.internal.EntityIntake
import orbita.knowledge.internal.Reconciler
import orbita.knowledge.internal.ResearchTasks
import orbita.library.api.Shelves

object KnowledgeFactory {
    /**
     * @param links реестр связей: без него сущность не свяжется со своим
     *   фактом, и доля знаний в проекте не посчитается
     * @param shelves полка: по ней принятый норматив узнаёт свою карточку,
     *   вместо того чтобы заводить второй такой же акт под своим кодом
     */
    fun intake(
        store: EntityStore,
        links: LinkRegistry? = null,
        mapper: ObjectMapper = ObjectMapper(),
        shelves: Shelves? = null,
    ): Intake = EntityIntake(store, links, mapper, shelves)

    /**
     * Сверка ввода — ступень 1: ключ идентичности, нормализация и четыре
     * вопроса по онтологии. Токенов не тратит ни одного.
     *
     * Наружу отдаётся ТА ЖЕ ступень, которую надстраивает семантика
     * (`AiFactory.reconcile(…, deterministic = эта)`): собрать её второй раз
     * внутри службы значит развести две сверки с разными кандидат-фактами.
     *
     * @param intake приём знаний: кандидат-факт заводит ОН, и ворота
     *   честности (величина без единицы, роль автора) остаются одни на
     *   проект. Умолчание собирает свой приём — так удобно тесту, но в сборке
     *   границы сюда обязан приходить общий порт знаний
     */
    fun reconcile(
        store: EntityStore,
        links: LinkRegistry? = null,
        mapper: ObjectMapper = ObjectMapper(),
        shelves: Shelves? = null,
        intake: Intake = EntityIntake(store, links, mapper, shelves),
    ): Reconcile = Reconciler(store, links, mapper, shelves, intake)

    /**
     * Исследование полноты — внешний контур: вопросы, промпт файлом, приём
     * результата материалом ранга `doubtful` и подтверждение источников.
     *
     * Вызовов модели здесь нет ни одного, поэтому службы ИИ среди параметров
     * тоже нет: появись она — мера «журнал ИИ после формулирования и запуска
     * не прирастает» держалась бы на честном слове, а не на сборке.
     *
     * @param intake приём знаний: материал результата заводит ОН — второй
     *   записи материалов в проекте не существует
     */
    fun research(
        store: EntityStore,
        links: LinkRegistry? = null,
        mapper: ObjectMapper = ObjectMapper(),
        shelves: Shelves? = null,
        intake: Intake = EntityIntake(store, links, mapper, shelves),
    ): Research = ResearchTasks(store, links, mapper, shelves, intake)

    /**
     * Миграция понятий (шип 4 §1): нужда — много носителей. Копии одной
     * формулировки сливаются, носители — связями owns и полем stakeholders.
     * Зовётся при старте границы; идемпотентна. Возвращает отчёт словами.
     */
    fun migrateNeedOwners(store: EntityStore, links: LinkRegistry, mapper: ObjectMapper = ObjectMapper()): String =
        orbita.knowledge.internal.NeedOwnersMigration(store, links, mapper).run().toString()

    /** Интерес — список у стороны (шип 4 §1.5): строка и перечень строк сходят в список с цитатой. */
    fun migrateStakeholderInterests(store: EntityStore, mapper: ObjectMapper = ObjectMapper()): String =
        orbita.knowledge.internal.StakeholderInterestsMigration(store, mapper).run().toString()

    /**
     * Миграция понятий целиком (ЗАДАНИЕ-ШИП-4 §1) — одна волна при старте ядра,
     * идемпотентная: нужда — много носителей, интерес — список у стороны.
     * Итог — словами, по одной строке на миграцию.
     */
    fun migrateConcepts(store: EntityStore, links: LinkRegistry, mapper: ObjectMapper = ObjectMapper()): List<String> =
        listOf(migrateNeedOwners(store, links, mapper), migrateStakeholderInterests(store, mapper))
}
