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
        val items = payload.path("objects")
        require(items.isArray && items.size() > 0) {
            "пачка пуста: тело должно содержать objects — список документов объектов"
        }

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

        // проверка по схемам ДО записи: отчёт собирается целиком, а не до первой ошибки
        rows.forEach { row ->
            boundary.schemaProblems(row.type, row.doc).forEach { e ->
                problems += BatchProblem(row.index, row.id, e.path, e.rule, e.message)
            }
        }
        if (problems.isNotEmpty()) return BatchReport(0, problems.sortedBy { it.index })

        // запись: проходы до неподвижной точки внутри одной транзакции
        return boundary.transaction {
            var pending = rows.sortedBy { it.index }
            val lastError = mutableMapOf<Int, String>()
            while (pending.isNotEmpty()) {
                val next = mutableListOf<Row>()
                for (row in pending) {
                    try {
                        boundary.ingest(row.type, mapper.writeValueAsString(row.doc), author, container!!)
                        lastError.remove(row.index)
                    } catch (e: Exception) {
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
