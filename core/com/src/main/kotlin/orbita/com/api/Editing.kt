// Рабочий слой: создание, правка и отмена объектов через интерфейс (шаг 15).
// Эталон — spec/editing_semantics.py (26 проверок).
//
// До этого шага единственным входом данных был seedDemo: мастер вёл инженера
// по чужому заполненному проекту. Здесь появляется ввод — с теми же правилами,
// что у импорта (ADR-024) и предложений ИИ (шаг 5), и с оптимистичной
// блокировкой: сессия параллельного проектирования это несколько человек
// одновременно, а модель однопользовательская.
package orbita.com.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.model.CoreType
import orbita.mod.model.Lifecycle
import orbita.mod.store.StoredObject

/** Происхождение объекта, введённого руками (TZ-COM-005). */
const val SOURCE_MANUAL = "manual"

/**
 * Отказ в правке базированного объекта. Причина адресована инженеру и потому
 * по-русски — как и прочие замечания, попадающие на экран (замечания мастера,
 * правила рисков). Технические сообщения исключений остаются английскими.
 */
class BaselineEditBlockedException(val id: String, val reason: String) :
    RuntimeException("TZ-COM-003: object '$id' is baselined: $reason")

class Editing(
    private val boundary: Boundary,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    /**
     * Следующий свободный идентификатор вида. Идентификаторы не переиспользуются
     * (TZ-MOD-007), поэтому счёт идёт от наибольшего существующего, а не от числа
     * объектов: отменённая нужда своё имя не отдаёт.
     */
    fun nextId(type: CoreType): String {
        val max = boundary.objects.listCurrent()
            .filter { it.type == type.dbType }
            .mapNotNull { it.id.substringAfterLast('-').toIntOrNull() }
            .maxOrNull() ?: 0
        return "%s-%04d".format(type.idPrefix, max + 1)
    }

    /**
     * Создание. Объект получает идентификатор, происхождение и автора; статус —
     * черновик. Проверка — та же, что у приёма по API: forms не имеют
     * собственного облегчённого набора правил (§1.3).
     */
    fun create(
        type: CoreType,
        draft: JsonNode,
        author: String,
        projectId: String = orbita.mod.store.ObjectStore.DEFAULT_PROJECT,
    ): StoredObject {
        require(author.isNotBlank()) { "TZ-COM-005: change without an author is not accepted" }
        val doc = draft.deepCopy<ObjectNode>()
        if (doc.path("id").asText("").isBlank()) doc.put("id", nextId(type))
        // Статусная модель — только у объектов, чья схема её несёт: сценарий —
        // расчётный случай, не управляемый объект, и схема поля lifecycle не
        // содержит. Навешенный всем подряд lifecycle отклонялся схемой, и
        // создать сценарий через интерфейс было нельзя (приёмка Шага 16 §4).
        if (boundary.schemaAllows(type, "lifecycle")) {
            val lifecycle = doc.withObject("/lifecycle")
            if (lifecycle.path("status").asText("").isBlank()) lifecycle.put("status", Lifecycle.Draft.name)
            if (lifecycle.path("version").asText("").isBlank()) lifecycle.put("version", "1")
        }
        // Происхождение чужого канала (импорт, предложение ИИ) не перебивается:
        // ручным ввод считается только тогда, когда его действительно ввели руками.
        if (boundary.schemaAllows(type, "provenance") &&
            doc.path("provenance").path("source").asText("").isBlank()
        ) {
            doc.withObject("/provenance").put("source", SOURCE_MANUAL).put("author", author)
        }
        fillRequiredCollections(type, doc)
        stampQuantities(doc, author)
        return boundary.ingest(type, mapper.writeValueAsString(doc), author, projectId)
    }

    /**
     * Правка. [baseVersion] — версия, на которой основана правка; расхождение
     * даёт отказ с показом чужого значения и автора (VersionConflictException),
     * а не тихую перезапись. Базированный объект правится только процедурой
     * изменения с основанием (TZ-COM-003).
     */
    fun update(
        type: CoreType,
        id: String,
        changes: JsonNode,
        baseVersion: String,
        author: String,
        /**
         * Основание изменения базированного объекта (TZ-COM-003): без него
         * правка Baseline отклоняется. Процессные события — прохождение точки,
         * возврат — несут решением своё основание и проходят этим путём:
         * иначе базированный паспорт наглухо запирал ворота (находка второго
         * захода: 409 на «Запросить прохождение»).
         */
        changeRef: String? = null,
    ): StoredObject {
        require(author.isNotBlank()) { "TZ-COM-005: change without an author is not accepted" }
        val cur = boundary.objects.current(id) ?: throw NoSuchElementException("object '$id' not found")
        // Порядок проверок как в эталоне: сначала версия, потом статус. Иначе
        // правка на устаревшей версии базированного объекта сообщила бы о статусе
        // и умолчала о том, что её основание вообще устарело.
        if (cur.version != baseVersion) throw conflict(cur, baseVersion, changes)
        if (cur.status == Lifecycle.Baseline && changeRef.isNullOrBlank()) {
            throw BaselineEditBlockedException(
                id, "объект базирован: изменение через процедуру с основанием",
            )
        }
        val merged = cur.doc.deepCopy<ObjectNode>()
        changes.properties().forEach { (k, v) -> merged.set<ObjectNode>(k, v) }
        val next = boundary.objects.bumpVersion(cur.version)
        // Статусная модель — только у видов, чья схема её несёт (как в create):
        // риск и замечание обзора живут своим циклом, и навешенный lifecycle
        // отклонялся бы их схемой — правка была бы невозможна вовсе.
        if (boundary.schemaAllows(type, "lifecycle")) {
            merged.withObject("/lifecycle").put("version", next)
        }
        stampQuantities(merged, author)
        boundary.validate(type, merged)
        val stored = boundary.objects.change(
            id, merged, changeRef = changeRef, createdBy = author, baseVersion = baseVersion,
        )
        // Связи выводятся из документа при каждой записи (ADR-027): до этого
        // «создал, потом привязал правкой» не давал ни одной связи — дерево
        // оставалось плоским, матрица трассировки пустой
        boundary.req.syncLinks(stored.type, stored.id, stored.doc, stored.projectId)
        return stored
    }

    /**
     * Отмена объекта: статус `Cancelled`, объект остаётся. Настоящего удаления
     * нет — на объект могут ссылаться, и удаление рвёт нить трассировки молча.
     */
    fun cancel(id: String, author: String, baseVersion: String? = null): StoredObject {
        require(author.isNotBlank()) { "TZ-COM-005: change without an author is not accepted" }
        return boundary.objects.cancel(id, createdBy = author, baseVersion = baseVersion)
    }

    /** История версий объекта — от ранних к поздним. */
    fun history(id: String): List<StoredObject> = boundary.objects.history(id)

    /**
     * Отмена действия (§1.4): содержание предыдущей версии становится новой
     * текущей. История НЕ переписывается — откат сам по себе является версией
     * и виден в истории вместе со своим автором.
     */
    fun undo(id: String, author: String): StoredObject? {
        require(author.isNotBlank()) { "TZ-COM-005: change without an author is not accepted" }
        val prev = boundary.objects.previous(id) ?: return null
        val cur = boundary.objects.current(id) ?: throw NoSuchElementException("object '$id' not found")
        val type = CoreType.byDbType(cur.type)
        val restored = prev.doc.deepCopy<ObjectNode>()
        // Блок lifecycle — только видам, чья схема его несёт (как в update):
        // прочим он не проставляется, а исторический мусор (до починки
        // transition) вычищается, не воскресая откатом.
        if (boundary.schemaAllows(type, "lifecycle")) {
            restored.withObject("/lifecycle").put("version", boundary.objects.bumpVersion(cur.version))
        } else {
            restored.remove("lifecycle")
        }
        // Восстановленное обязано проходить НЫНЕШНЮЮ схему: откат, молча
        // возвращающий недействительное содержание, делал объект
        // нередактируемым (находка второго захода — RF-0001).
        boundary.validate(type, restored)
        val stored = boundary.objects.change(id, restored, createdBy = author, baseVersion = cur.version)
        boundary.req.syncLinks(stored.type, stored.id, stored.doc, stored.projectId)
        return stored
    }

    /**
     * Что мешает перевести объект в Baseline. Пустой список — переводится.
     * Черновик допускает неполноту (CR-002): неполный объект существует,
     * но не базируется, и блокировка называет причины поимённо.
     */
    fun promotionIssues(id: String): List<String> {
        val cur = boundary.objects.current(id) ?: throw NoSuchElementException("object '$id' not found")
        return boundary.req.baselineIssues(cur.type, cur.doc, cur.projectId)
    }

    /** Полный вердикт базирования — форме: что отводимо и что уже отведено. */
    fun promotionVerdict(id: String): orbita.req.BaselineVerdict {
        val cur = boundary.objects.current(id) ?: throw NoSuchElementException("object '$id' not found")
        return boundary.req.baselineVerdict(cur.type, cur.doc, cur.projectId)
    }

    /**
     * Обязательные схемой коллекции, которых нет в черновике, заводятся пустыми
     * (CR-002): полнота верификации и трассировки — условие БАЗИРОВАНИЯ,
     * а не сохранения. Иначе первое же требование нельзя было бы записать,
     * пока не назначен план верификации, и работа встала бы.
     *
     * Список полей берётся ИЗ СХЕМЫ, а не пишется руками: перечень в коде
     * разошёлся бы со схемой молча. Пустая коллекция — честное «пока пусто»:
     * разрывы трассировки и отсутствие плана видны в отчётах и в мастере.
     */
    private fun fillRequiredCollections(type: CoreType, doc: ObjectNode) {
        if (type == CoreType.Interface) return          // интерфейс схемы своего вида не имеет
        val schema = boundary.rawSchema(type.schemaName)
        val props = schema.path("properties")
        schema.path("required").forEach { field ->
            val name = field.asText()
            val spec = props.path(name)
            // Коллекция с minItems ≥ 1 пустой быть не может: это не структурная
            // формальность, а содержательное требование (требование без traces_up
            // — сирота, TZ-COM-002). Подставлять туда пустой список значило бы
            // подменить внятный отказ схемы отказом об «одном элементе».
            val structuralOnly = spec.path("type").asText("") == "array" &&
                spec.path("minItems").asInt(0) == 0
            if (!doc.has(name) && structuralOnly) doc.putArray(name)
        }
    }

    /**
     * Происхождение величин, введённых руками (TZ-COM-005). Схема требует
     * происхождение у КАЖДОГО значения модели, а инженер в форме вводит число
     * и единицу — проставляет система. Без этого ручной ввод величины
     * отклонялся бы схемой, и формы были бы неработоспособны.
     *
     * Чужое происхождение (расчёт, импорт, предложение ИИ) не трогается:
     * пометить вычисленное значение ручным значило бы соврать о том, откуда
     * оно взялось.
     */
    private fun stampQuantities(node: JsonNode, author: String) {
        when {
            node.isObject -> {
                val obj = node as ObjectNode
                val looksLikeQuantity = obj.path("value").isNumber && obj.path("unit").isTextual
                if (looksLikeQuantity && obj.path("provenance").path("source").asText("").isBlank()) {
                    obj.withObject("/provenance").put("source", SOURCE_MANUAL).put("author", author)
                }
                obj.properties().forEach { (name, child) ->
                    if (name != "provenance") stampQuantities(child, author)
                }
            }

            node.isArray -> node.forEach { stampQuantities(it, author) }
        }
    }

    private fun conflict(cur: StoredObject, base: String, changes: JsonNode) =
        orbita.mod.store.VersionConflictException(
            id = cur.id,
            yourBase = base,
            currentVersion = cur.version,
            changedBy = cur.createdBy,
            theirValues = changes.properties().map { it.key }.associateWith { cur.doc.path(it) },
            // обе версии разошедшихся полей — материал экрана конфликта (В3)
            yourValues = changes.properties().associate { (k, v) -> k to v },
        )
}
