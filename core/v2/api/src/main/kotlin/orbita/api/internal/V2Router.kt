// Тонкие маршруты v2 (ТЗ-BACKEND §3: файл ≤ 300 строк, домена внутри нет).
//
// Роутер — диспетчер: он знает только, кому передать путь. Обработчики живут
// рядом файлами по волнам (SceneRoutes — сцены 1–6, ReqArchRoutes — требования
// и архитектура). Ни одного правила предметной области здесь нет: движок
// считает состояние сцен, оценщик отвечает за условия, хранилище держит версии.
package orbita.api.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.api.api.Actor
import orbita.formulation.api.Formulation
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.knowledge.api.Intake
import orbita.library.api.Shelves
import orbita.process.api.ProcessEngine

class V2Router(
    store: EntityStore,
    links: LinkRegistry,
    engine: ProcessEngine,
    shelves: Shelves,
    intake: Intake,
    formulation: Formulation,
    mapper: ObjectMapper = ObjectMapper(),
    /** Маршруты волны 3 (требования и архитектура) — отдельным файлом. */
    private val reqArch: ReqArchRoutes? = null,
    /** Маршруты волны 4 (модели и программатика). */
    private val modelRoutes: ModelRoutes? = null,
    /** Документы: живой снимок сцен — идут перед фронтом волны 4. */
    private val docRoutes: DocRoutes? = null,
    /** Поле знаний: канон, живой разбор, факты и диспозиции. */
    private val knowledgeRoutes: KnowledgeRoutes? = null,
    /** Точки (шип D): чек обзора, замечания, решения, переход фазы. */
    private val pointRoutes: PointRoutes? = null,
    /** Обмен (шип F): StrictDoc, выгрузка знаний с отпечатком, пакет точки. */
    private val exchangeRoutes: ExchangeRoutes? = null,
    /** Внешняя модель (шип G): Capella либо fixture с баннером. */
    private val externalModelRoutes: ExternalModelRoutes? = null,
) {

    /**
     * Ответ роутера: код и тело. HTTP-обвязка — снаружи.
     *
     * @property binary печать: единственный ответ, который не JSON. Байты
     *   объявлены отдельным полем, чтобы обвязка не гадала по телу
     */
    data class Ответ(
        val code: Int,
        val body: JsonNode,
        val binary: ByteArray? = null,
        val contentType: String? = null,
        val fileName: String? = null,
    )

    private val сцены = SceneRoutes(store, links, engine, mapper)
    private val сквозные = AcrossRoutes(store, links, engine, shelves, intake, formulation, mapper)
    // Точки есть у любого роутера: фиксация точки — часть хребта, а не
    // отдельной волны; сборка без явных записей берёт записи над тем же
    // хранилищем.
    private val точки: PointRoutes = pointRoutes ?: PointRoutes(engine, GateRecords(store, mapper), mapper)

    /** @param actor учётка и роли — для маршрутов, где решает роль; null — вход не включён */
    fun handle(method: String, path: String, query: Map<String, String>, body: String?, actor: Actor? = null): Ответ? =
        точки.handle(method, path, query, body, actor)
            ?: сцены.handle(method, path, query, body)
            ?: сквозные.handle(method, path, query, body)
            ?: reqArch?.handle(method, path, query, body)
            ?: modelRoutes?.handle(method, path, query, body)
            ?: docRoutes?.handle(method, path, query, body)
            ?: knowledgeRoutes?.handle(method, path, query, body)
            ?: exchangeRoutes?.handle(method, path, query, body)
            ?: externalModelRoutes?.handle(method, path, query)
}
