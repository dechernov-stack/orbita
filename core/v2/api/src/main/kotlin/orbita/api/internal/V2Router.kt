// Тонкие маршруты v2 (ТЗ-BACKEND §3: файл ≤ 300 строк, домена внутри нет).
//
// Роутер — диспетчер: он знает только, кому передать путь. Обработчики живут
// рядом файлами по волнам (SceneRoutes — сцены 1–6, ReqArchRoutes — требования
// и архитектура). Ни одного правила предметной области здесь нет: движок
// считает состояние сцен, оценщик отвечает за условия, хранилище держит версии.
package orbita.api.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
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
    // ——— Поле знаний v2 ———
    //
    // Четыре маршрута приходят СНАРУЖИ и по умолчанию пусты: сборка без них
    // (стенд прохода ПМИ-5) отвечает на новые адреса тем же `null`, что и на
    // выдуманный путь, — обвязка превращает его в 404, и ни один прежний
    // ответ не меняется. Сами маршруты спрашивают флаг проекта у ядра, а не
    // у роутера: один стенд держит оба порядка сразу.
    /** Синтез постановки из поля: запуски, диф, дрейф, принятие предложений. */
    private val synthesisRoutes: SynthesisRoutes? = null,
    /** Сверка ввода: четыре вопроса и ЕДИНСТВЕННЫЙ путь изменения модели. */
    private val reconcileRoutes: ReconcileRoutes? = null,
    /** Исследование полноты: вопросы, промпт файлом, результат, источники. */
    private val researchRoutes: ResearchRoutes? = null,
    /** Верификация документа против поля: отчёт, перенос находки, закрытие. */
    private val verifyRoutes: VerifyRoutes? = null,
    /** Текст из двоичного файла материала (docx · pdf · xlsx · pptx) — подставляет граница. */
    private val extract: ((fileName: String, bytes: ByteArray) -> String?)? = null,
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
    private val сквозные = AcrossRoutes(store, links, engine, shelves, intake, formulation, mapper, extract = extract)
    // Точки есть у любого роутера: фиксация точки — часть хребта, а не
    // отдельной волны; сборка без явных записей берёт записи над тем же
    // хранилищем.
    private val точки: PointRoutes = pointRoutes ?: PointRoutes(engine, GateRecords(store, mapper), mapper)

    private val базирование = Regex("/v2/documents/([a-z_]+)/baseline")

    /** @param actor учётка и роли — для маршрутов, где решает роль; null — вход не включён */
    fun handle(method: String, path: String, query: Map<String, String>, body: String?, actor: Actor? = null): Ответ? {
        val ответ = цепочка(method, path, query, body, actor) ?: return null
        return приБазировании(method, path, query, ответ)
    }

    private fun цепочка(
        method: String,
        path: String,
        query: Map<String, String>,
        body: String?,
        actor: Actor?,
    ): Ответ? =
        точки.handle(method, path, query, body, actor)
            ?: сцены.handle(method, path, query, body)
            ?: сквозные.handle(method, path, query, body)
            ?: reqArch?.handle(method, path, query, body)
            ?: modelRoutes?.handle(method, path, query, body)
            ?: docRoutes?.handle(method, path, query, body)
            ?: knowledgeRoutes?.handle(method, path, query, body)
            ?: exchangeRoutes?.handle(method, path, query, body)
            ?: externalModelRoutes?.handle(method, path, query)
            ?: synthesisRoutes?.handle(method, path, query, body)
            ?: reconcileRoutes?.handle(method, path, query, body)
            ?: researchRoutes?.handle(method, path, query, body)
            ?: verifyRoutes?.handle(method, path, query, body, actor)

    /**
     * Автоотчёт верификации после базирования — единственное место, где
     * роутер складывает два ответа в один.
     *
     * Почему здесь, а не в DocRoutes: базирует документ его собственный
     * маршрут, и знать о поле знаний v2 ему незачем — снимок базовой линии
     * одинаков на обоих порядках. Сложение идёт СБОКУ и только добавлением
     * ключа: код ответа, снимок и его поля остаются прежними до буквы,
     * поэтому прежние тесты базирования не меняются.
     *
     * `null` возвращает сама верификация, когда поле знаний v2 на проекте
     * выключено, — на проекте прохода ПМИ-5 базирование не прирастает ни
     * одной записью.
     */
    private fun приБазировании(method: String, path: String, query: Map<String, String>, ответ: Ответ): Ответ {
        if (method != "POST" || !базирование.matches(path)) return ответ
        val маршрут = verifyRoutes ?: return ответ
        // Только состоявшееся базирование: на отказе проверять нечего.
        if (ответ.code != 201) return ответ
        val тело = ответ.body as? ObjectNode ?: return ответ
        val проект = query["project"]?.takeIf { it.isNotBlank() } ?: return ответ
        // Автор отчёта — тот, кто базировал: отчёт предъявляется человеку, и
        // безымянного автора проверка не принимает. Снимок без имени —
        // не повод отказать в базировании, поэтому отчёт просто не заводится.
        val кем = тело.path("by").asText("").trim().ifBlank { return ответ }
        val отчёт = маршрут.послеБазирования(
            проект,
            document = базирование.matchEntire(path)!!.groupValues[1],
            author = кем,
            baseline = тело.path("name").asText("").ifBlank { null },
        ) ?: return ответ
        тело.set<JsonNode>("verification", отчёт)
        return ответ
    }
}
