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
}
