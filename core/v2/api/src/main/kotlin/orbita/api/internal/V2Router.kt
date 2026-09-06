// Тонкие маршруты v2 (ТЗ-BACKEND §3: файл ≤ 300 строк, домена внутри нет).
//
// Роутер — диспетчер: он знает только, кому передать путь. Обработчики живут
// рядом файлами по волнам (SceneRoutes — сцены 1–6, ReqArchRoutes — требования
// и архитектура). Ни одного правила предметной области здесь нет: движок
// считает состояние сцен, оценщик отвечает за условия, хранилище держит версии.
package orbita.api.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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
) {

    /** Ответ роутера: код и тело. HTTP-обвязка — снаружи. */
    data class Ответ(val code: Int, val body: JsonNode)

    private val сцены = SceneRoutes(store, links, engine, mapper)
    private val сквозные = AcrossRoutes(store, links, engine, shelves, intake, formulation, mapper)

    fun handle(method: String, path: String, query: Map<String, String>, body: String?): Ответ? =
        сцены.handle(method, path, query, body)
            ?: сквозные.handle(method, path, query, body)
            ?: reqArch?.handle(method, path, query, body)
}
