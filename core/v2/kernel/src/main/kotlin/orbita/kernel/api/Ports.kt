// Порты ядра: чем модуль пользуются снаружи (ТЗ-BACKEND §3).
//
// Другие модули видят только эти интерфейсы. Реализация — в internal, и
// подменить её (например, на память в тестах) можно, не трогая вызывающих.
package orbita.kernel.api

import com.fasterxml.jackson.databind.JsonNode

/** Хранилище сущностей: одна текущая версия на id, история рядом. */
interface EntityStore {
    fun create(
        code: String,
        kind: String,
        area: Area,
        bornIn: String?,
        doc: JsonNode,
        provenance: Provenance,
        status: String = "draft",
    ): Entity

    /** Правка заводит НОВУЮ версию; прежняя остаётся историей. */
    fun update(id: String, doc: JsonNode, provenance: Provenance, status: String? = null): Entity

    fun byId(id: String): Entity?
    fun byCode(area: Area, code: String): Entity?
    fun list(area: Area, kind: String? = null): List<Entity>

    /**
     * Все сущности вида по ВСЕМ областям. Нужно ровно там, где области ещё
     * не знают: портфель проектов — это список областей, и спросить его
     * внутри области нельзя.
     */
    fun ofKind(kind: String): List<Entity>

    fun history(id: String): List<Entity>
}

/**
 * Типизированная связь из реестра связей (МОДЕЛЬ-ДАННЫХ §4).
 *
 * @property rationale обоснование; у части связей реестр требует его обязательно —
 *   связь без причины неотличима от случайной
 */
data class Link(
    val id: String,
    val type: String,
    val from: String,
    val to: String,
    val rationale: String?,
    val provenance: Provenance,
    /** Вид уточнения: для `derives_from` — decomposition · derivation · refinement. */
    val subtype: String? = null,
    /** Кто подтвердил связь после того, как она стала подозрительной. */
    val confirmedBy: String? = null,
    val confirmedAt: String? = null,
)

/** Реестр связей: типы известны заранее, произвольных связей не бывает. */
interface LinkRegistry {
    fun link(
        type: String,
        from: String,
        to: String,
        provenance: Provenance,
        rationale: String? = null,
        subtype: String? = null,
    ): Link

    fun unlink(id: String, provenance: Provenance)
    fun byId(id: String): Link?
    fun from(id: String, type: String? = null): List<Link>
    fun to(id: String, type: String? = null): List<Link>

    /**
     * Подтвердить связь: подозрение снимает ЧЕЛОВЕК, а не время и не
     * перезапись снимка (снимок базирования неизменяем).
     */
    fun confirm(id: String, by: String): Link
}

/** Проверка документа по сгенерированной схеме вида. */
interface SchemaRegistry {
    fun kinds(): List<String>
    fun problems(kind: String, doc: JsonNode): List<String>
}

/** Событие домена: «сущность появилась/изменилась» — повод переоценить ворота. */
data class DomainEvent(val kind: String, val entityId: String, val area: Area, val what: String)

interface DomainEvents {
    fun publish(event: DomainEvent)
    fun subscribe(listener: (DomainEvent) -> Unit)
}

/** Блок текстового индекса базы знаний: откуда, что и с какими фильтрами. */
data class IndexBlock(
    /** Ключ блока: `canon:<материал>:<якорь>` · `term:<код>` · `clause:<код>:<n>` · `fact:<код>`. */
    val key: String,
    /** canon_block · term · clause · fact. */
    val kind: String,
    /** Область: `library` либо `project:<код>`. */
    val area: String,
    /** Код записи-источника. */
    val ref: String,
    val title: String,
    val text: String,
    /** role · rank · scene · class · mark — чем фильтруется выборка. */
    val filters: Map<String, String> = emptyMap(),
    /** Отпечаток содержимого: неизменённый блок не перезаписывается и не переэмбеддится. */
    val fingerprint: String,
)

/** Находка поиска: блок с оценками — лексической, семантической и общей. */
data class IndexHit(
    val key: String,
    val kind: String,
    val area: String,
    val ref: String,
    val title: String,
    val snippet: String,
    val score: Double,
    val lexical: Double,
    val semantic: Double?,
    val filters: Map<String, String>,
)

/**
 * Текстовый индекс базы знаний (шип 4 §2): полнотекст в той же базе и вектор
 * (pgvector), если расширение есть. Схема — ядра; наполняет его модуль знаний.
 */
interface TextIndex {
    /** Есть ли векторная колонка: без расширения pgvector поиск — только лексический. */
    val vectorReady: Boolean

    /** Размерность вектора, с которой индекс заведён. */
    val vectorDim: Int

    /** Положить блоки: ключ — адрес, неизменённый отпечаток — пропуск. Возвращает число записанных. */
    fun upsert(blocks: List<IndexBlock>): Int

    /** Снять блоки области и вида, которых больше нет среди `keep`. */
    fun removeMissing(area: String, kind: String, keep: Set<String>): Int

    /** Блоки без вектора (новые или изменённые) — очередь на эмбеддинг. */
    fun withoutVector(area: String?, limit: Int = 200): List<IndexBlock>

    fun setVector(key: String, vector: FloatArray)

    /**
     * Гибридный поиск: `query` — слова (полнотекст), `vector` — смысл (если есть);
     * `areas` — библиотека и проект; `filters` — точное совпадение полей блока;
     * `kinds` — виды блоков (пусто — все).
     */
    fun search(
        query: String,
        areas: List<String>,
        filters: Map<String, String> = emptyMap(),
        kinds: List<String> = emptyList(),
        vector: FloatArray? = null,
        limit: Int = 20,
    ): List<IndexHit>

    /** Сколько блоков в области (по видам) — для сводки. */
    fun count(area: String): Map<String, Int>
}
