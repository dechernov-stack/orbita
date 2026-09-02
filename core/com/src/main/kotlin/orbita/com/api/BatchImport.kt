// Загрузка и выгрузка пачкой (блок A задания «прогон до KDP B», ADR-024).
//
// Правила пачки: проверка по схемам ДО записи, всё или ничего, порядок
// вставки разрешает сервер, отчёт называет путь до поля. Формат импорта
// равен формату экспорта — выгруженный проект грузится обратно как есть.
package orbita.com.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.model.CoreType
import orbita.mod.model.ObjectId

/** Замечание пачки: номер строки, объект и путь до поля. */
data class BatchProblem(
    val index: Int,
    val id: String?,
    val path: String?,
    val rule: String?,
    val message: String,
    /**
     * Идентификатор строки В ПАКЕТЕ, если при акцепте он был перебит.
     *
     * Черновые id пакета, занятые в системе, получают свежие (TZ-MOD-007),
     * и отказ приходит уже на НОВОЕ имя. Инженер видит в списке старое: снять
     * отметку ему не с чего, «примите оставшихся» не работает, а счётчик
     * выбранного уползает вниз (находка живого прохода ПМИ-3). Здесь —
     * обратный адрес: как строка называлась в том пакете, что он вставил.
     */
    val sourceId: String? = null,
)

data class BatchReport(val written: Int, val problems: List<BatchProblem>) {
    val ok: Boolean get() = problems.isEmpty()
}

/** Пачка не записана; отчёт — в исключении, транзакция откатена. */
class BatchRejectedException(val report: BatchReport) :
    RuntimeException("batch rejected: ${report.problems.size} problem(s)")

class BatchImport(private val boundary: Boundary, private val mapper: ObjectMapper = ObjectMapper()) {

    /**
     * Импорт пачки. Контейнер: объект вида project внутри пачки (ровно один) —
     * вся пачка ложится в него; иначе — проект контекста запроса.
     *
     * Порядок вставки объявлять не нужно: сервер повторяет проходы, пока есть
     * продвижение — ссылки (traces_up, conops_ref, allocated_to, входы
     * сценария) разрешаются, как только их цель записана. Осадок после
     * остановки — отказ всей пачки с причинами поимённо.
     */
    fun import(payload: JsonNode, author: String, contextProject: String?): BatchReport {
        val raw = payload.path("objects")
        require(raw.isArray && raw.size() > 0) {
            "пачка пуста: тело должно содержать objects — список документов объектов"
        }
        // Г-01: подтверждённое инженером сопоставление чужих ссылок — тем же
        // применением, что у акцепта службы. Изоляция не ослабляется: ссылка
        // становится ссылкой ЭТОГО проекта либо остаётся и даёт разрыв
        val карта = mutableMapOf<String, String>()
        payload.path("link_mapping").properties().forEach { (старый, новый) ->
            новый.asText("").takeIf { it.isNotBlank() }?.let { карта[старый] = it }
        }
        val items = if (карта.isEmpty()) raw else LinkMapping.применить(raw.deepCopy(), карта)

        // разбор строк: идентификатор обязателен, вид выводится из префикса
        data class Row(val index: Int, val id: String, val type: CoreType, val doc: ObjectNode)
        val rows = mutableListOf<Row>()
        val problems = mutableListOf<BatchProblem>()
        items.forEachIndexed { i, item ->
            val id = item.path("id").asText("")
            when {
                !item.isObject ->
                    problems += BatchProblem(i, null, "/", "type", "строка пачки — не объект")
                !ObjectId.PATTERN.matches(id) ->
                    problems += BatchProblem(
                        i, id.ifBlank { null }, "/id", "pattern",
                        "идентификатор '$id' не соответствует <ВИД>-NNNN",
                    )
                else -> rows += Row(i, id, ObjectId(id).type, item as ObjectNode)
            }
        }

        val projects = rows.filter { it.type == CoreType.Project }
        if (projects.size > 1) {
            problems += BatchProblem(
                projects[1].index, projects[1].id, "/id", "single_project",
                "в пачке больше одного проекта — контейнер неоднозначен",
            )
        }
        val container = projects.firstOrNull()?.id ?: contextProject
        if (container == null) {
            problems += BatchProblem(
                0, null, null, "project_required",
                "не назначен проект: передайте ?project=PJ-NNNN либо включите объект проекта в пачку",
            )
        }

        // граница переводит (справочник единиц): несистемная единица — в
        // канон с происхождением; неизвестная/курсовая — отказ поимённо
        UnitBoundary.registryOf(boundary)?.let { registry ->
            rows.forEach { row ->
                try {
                    UnitBoundary.normalize(row.doc, registry)
                } catch (e: UnknownUnitException) {
                    problems += BatchProblem(row.index, row.id, null, "unit_unknown", e.message ?: e.unit)
                } catch (e: RateUnitException) {
                    problems += BatchProblem(row.index, row.id, null, "unit_rate", e.message ?: e.unit)
                }
            }
        }
        if (problems.isNotEmpty()) return BatchReport(0, problems.sortedBy { it.index })

        // проверка по схемам ДО записи: отчёт собирается целиком, а не до первой ошибки
        rows.forEach { row ->
            boundary.schemaProblems(row.type, row.doc).forEach { e ->
                problems += BatchProblem(row.index, row.id, e.path, e.rule, e.message)
            }
        }
        if (problems.isNotEmpty()) return BatchReport(0, problems.sortedBy { it.index })

        // запись: проходы до неподвижной точки внутри одной транзакции.
        // Каждая строка — под SAVEPOINT: отказ строки (в том числе SQL,
        // например дубль id) откатывается точечно, не портя транзакцию
        // пачки, — иначе после первой ошибки все последующие строки
        // сыпались каскадом «current transaction is aborted» (находка
        // MVP-прохода, акцепт пакета целей).
        return boundary.transaction {
            var pending = rows.sortedBy { it.index }
            val lastError = mutableMapOf<Int, String>()
            while (pending.isNotEmpty()) {
                val next = mutableListOf<Row>()
                for (row in pending) {
                    val sp = boundary.connection.setSavepoint()
                    try {
                        boundary.ingest(row.type, mapper.writeValueAsString(row.doc), author, container!!)
                        boundary.connection.releaseSavepoint(sp)
                        lastError.remove(row.index)
                    } catch (e: Exception) {
                        boundary.connection.rollback(sp)
                        lastError[row.index] = e.message ?: e.javaClass.simpleName
                        next += row
                    }
                }
                if (next.size == pending.size) {
                    // продвижения нет: причины не в порядке вставки, а в самих данных
                    val stuck = next.map {
                        BatchProblem(it.index, it.id, null, "unresolved", lastError[it.index] ?: "не записан")
                    }
                    throw BatchRejectedException(BatchReport(0, stuck))
                }
                pending = next
            }
            BatchReport(rows.size, emptyList())
        }
    }

    /**
     * Акцепт предложений (Б-01/находка прохода): id из пакета — черновые,
     * их придумала модель или заготовил пакет, а id в системе глобальны и
     * не переиспользуются (TZ-MOD-007). Занятые id получают свежие; ссылки
     * ВНУТРИ пачки (traces_up и прочие) перебиваются на новые. Ссылка на
     * существующий объект, чей id в пачку не входит, остаётся как есть.
     * Импорт пачки это не трогает: формат экспорта == формату импорта.
     */
    fun remapBusyIds(items: com.fasterxml.jackson.databind.node.ArrayNode):
        Pair<com.fasterxml.jackson.databind.node.ArrayNode, Map<String, String>> {
        val packIds = items.mapNotNull { it.path("id").asText("").ifBlank { null } }.toSet()
        val busy = packIds.filter { boundary.objects.current(it) != null }
        if (busy.isEmpty()) return items to emptyMap()
        val counters = mutableMapOf<CoreType, Int>()
        val taken = mutableSetOf<String>()
        val remap = busy.sorted().associateWith { old ->
            val type = ObjectId(old).type
            var n = counters.getOrPut(type) {
                boundary.editing.nextId(type).substringAfterLast('-').toInt()
            }
            // свежий id не должен совпасть ни с другой строкой пачки, ни с
            // уже назначенным — иначе пачка сама себе создаёт дубль
            var candidate: String
            do {
                candidate = "%s-%04d".format(type.idPrefix, n)
                n += 1
            } while (candidate in packIds || candidate in taken)
            counters[type] = n
            taken += candidate
            candidate
        }
        var text = mapper.writeValueAsString(items)
        remap.forEach { (old, new) ->
            text = text.replace(Regex("\\b" + Regex.escape(old) + "\\b"), new)
        }
        return mapper.readTree(text) as com.fasterxml.jackson.databind.node.ArrayNode to remap
    }

    /**
     * Полный акцептный ремап: комплект пакетов согласован МЕЖДУ СОБОЙ по
     * черновым id (02-нужды трассируется на MG-0001 из 01-целей). Сначала
     * ссылки пачки перебиваются по карте проекта (черновой id → настоящий,
     * из provenance.ai.source_id прежних акцептов) — иначе они тихо указали
     * бы в чужой проект; затем занятые id строк получают свежие; настоящий
     * след кладётся в provenance.ai.source_id каждой переназначенной строки.
     */
    fun remapForAccept(items: com.fasterxml.jackson.databind.node.ArrayNode, projectId: String):
        Pair<com.fasterxml.jackson.databind.node.ArrayNode, Map<String, String>> {
        val packIds = items.mapNotNull { it.path("id").asText("").ifBlank { null } }.toSet()
        // СНАЧАЛА строки пачки: занятые id получают свежие, внутренние
        // ссылки (на id строк) перебиваются вместе с ними
        val (remapped, idMap) = remapBusyIds(items)
        // ПОТОМ межпакетные ссылки — по карте проекта, только ключи, НЕ
        // бывшие строками этой пачки: порядок наоборот перебил бы ссылку на
        // существующий объект, чей id совпал со свежим id строки
        var text = mapper.writeValueAsString(remapped)
        projectPacketMap(projectId)
            .filterKeys { it !in packIds }
            .forEach { (old, new) ->
                text = text.replace(Regex("\\b" + Regex.escape(old) + "\\b"), new)
            }
        val crossed = mapper.readTree(text) as com.fasterxml.jackson.databind.node.ArrayNode
        if (idMap.isNotEmpty()) {
            val byNew = idMap.entries.associate { (old, new) -> new to old }
            crossed.forEach { row ->
                val ai = row.path("provenance").path("ai")
                val old = byNew[row.path("id").asText("")]
                if (old != null && ai is ObjectNode) ai.put("source_id", old)
            }
        }
        return crossed to idMap
    }

    /** Карта пакетных id проекта: черновой id → настоящий (по следам акцептов). */
    private fun projectPacketMap(projectId: String): Map<String, String> =
        boundary.objects.listCurrent(projectId)
            .filter { it.status.name != "Cancelled" }
            .mapNotNull { o ->
                o.doc.path("provenance").path("ai").path("source_id").asText("")
                    .ifBlank { null }?.let { it to o.id }
            }
            .toMap()

    /** Выгрузка проекта тем же форматом: текущие версии, включая сам проект. */
    fun export(projectId: String): ObjectNode {
        val out = mapper.createObjectNode()
        out.put("project", projectId)
        val arr = out.putArray("objects")
        boundary.objects.listCurrent(projectId)
            .filter { it.status.name != "Cancelled" }
            .sortedBy { it.id }
            .forEach { arr.add(it.doc) }
        return out
    }
}
