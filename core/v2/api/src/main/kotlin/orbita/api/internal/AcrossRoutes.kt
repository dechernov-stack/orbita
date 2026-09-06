// Сквозные экраны v2: полки, материалы и загрузка, поле знаний, матрица
// покрытия, «мои задания», перечень сущностей и фиксация точки.
//
// Вынесены из V2Router отдельным файлом по правилу ТЗ-BACKEND §3 (файл
// тонкий, домена внутри нет): экраны сквозные, к одной сцене не привязаны.
package orbita.api.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.formulation.api.Formulation
import orbita.kernel.api.Area
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.knowledge.api.Intake
import orbita.library.api.Shelves
import orbita.process.api.ProcessEngine

class AcrossRoutes(
    private val store: EntityStore,
    private val links: LinkRegistry,
    private val engine: ProcessEngine,
    private val shelves: Shelves,
    private val intake: Intake,
    private val formulation: Formulation,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    fun handle(method: String, path: String, query: Map<String, String>, body: String?): V2Router.Ответ? = when {
        // Полки: загрузка поставки и просмотр.
        method == "POST" && path == "/v2/shelves" -> положитьНаПолку(разобрать(body))

        method == "GET" && path == "/v2/shelves" -> полка(требуется(query, "kind"))

        // Глоссарий: интерфейс говорит терминами NASA, соответствия — здесь.
        // Поиск идёт по ЛЮБОМУ из имён: инженер помнит своё.
        method == "GET" && path == "/v2/glossary" -> глоссарий(query["q"])

        // Волна 2: материалы, задание загрузки и план действий.
        method == "POST" && path == "/v2/materials" -> материал(требуется(query, "project"), разобрать(body))

        method == "POST" && path == "/v2/intake" -> заданиеЗагрузки(требуется(query, "project"), разобрать(body))

        method == "GET" && path == "/v2/facts" -> фактология(требуется(query, "project"))

        // Матрица покрытия: не хранится, а считается по связям.
        method == "GET" && path == "/v2/coverage" -> покрытие(требуется(query, "project"))

        method == "GET" && path == "/v2/my-tasks" -> задания(требуется(query, "project"), query["role"])

        method == "GET" && path == "/v2/entities" ->
            перечень(требуется(query, "project"), требуется(query, "kind"))

        method == "POST" && path.startsWith("/v2/gates/") && path.endsWith("/pass") ->
            фиксировать(
                требуется(query, "project"),
                path.removePrefix("/v2/gates/").removeSuffix("/pass"),
                разобрать(body),
            )

        else -> null
    }

    private fun положитьНаПолку(тело: JsonNode): V2Router.Ответ {
        val вид = тело.path("kind").asText("")
        val код = тело.path("code").asText("")
        require(вид.isNotBlank() && код.isNotBlank()) { "полке нужны вид и код записи" }
        val запись = shelves.put(вид, код, тело.path("doc"), автор(тело))
        return V2Router.Ответ(201, mapper.createObjectNode().put("code", запись.code).put("kind", запись.kind))
    }

    private fun глоссарий(запрос: String?): V2Router.Ответ {
        val полка = shelves.of("glossary_cross_terms").firstOrNull()
        val ответ = mapper.createObjectNode()
        val массив = ответ.putArray("items")
        val игла = запрос?.trim()?.lowercase().orEmpty()
        полка?.doc?.path("items")?.forEach { термин ->
            val строки = listOf("term_nasa", "ru_equivalent", "en_full", "romanov")
                .map { термин.path(it).asText("") }
            if (игла.isEmpty() || строки.any { it.lowercase().contains(игла) }) {
                массив.add(термин)
            }
        }
        ответ.put(
            "note",
            if (полка == null) "глоссарий не загружен: python3 tools/v2/load_shelves.py"
            else "интерфейс — в терминах NASA; здесь соответствия РК-11КТ и схемы Романова",
        )
        return V2Router.Ответ(200, ответ)
    }

    private fun полка(вид: String): V2Router.Ответ {
        val массив = mapper.createArrayNode()
        shelves.of(вид).forEach { запись ->
            массив.addObject().put("code", запись.code).put("kind", запись.kind)
                .set<JsonNode>("doc", запись.doc)
        }
        val ответ = mapper.createObjectNode()
        ответ.set<JsonNode>("items", массив)
        return V2Router.Ответ(200, ответ)
    }


    private fun материал(проект: String, тело: JsonNode): V2Router.Ответ {
        val код = intake.putMaterial(
            проект,
            тело.path("name").asText("материал"),
            тело.path("kind").asText("reference"),
            тело.path("text").asText(""),
            автор(тело),
        )
        return V2Router.Ответ(201, mapper.createObjectNode().put("code", код))
    }

    private fun заданиеЗагрузки(проект: String, тело: JsonNode): V2Router.Ответ {
        val задание = intake.plan(
            проект,
            тело.path("material").asText(""),
            тело.path("intent").asText("разбери по сущностям"),
            автор(тело),
        )
        val узел = mapper.createObjectNode()
        узел.put("task", задание.id)
        узел.put("material", задание.material)
        узел.put("intent", задание.intent)
        узел.put("note", задание.note)
        val факты = узел.putArray("facts")
        задание.facts.forEach { факт ->
            факты.addObject()
                .put("id", факт.id)
                .put("subject", факт.subject)
                .put("predicate", факт.predicate)
                .put("value", факт.value)
                .put("unit", факт.unit)
                .put("anchor", факт.anchor)
                .put("mark", факт.mark.name)
        }
        val план = узел.putArray("plan")
        задание.plan.forEach { действие ->
            план.addObject()
                .put("kind", действие.kind)
                .put("title", действие.title)
                .put("effect", действие.effect)
        }
        return V2Router.Ответ(200, узел)
    }

    private fun фактология(проект: String): V2Router.Ответ {
        val массив = mapper.createArrayNode()
        intake.facts(проект).forEach { факт ->
            массив.addObject()
                .put("id", факт.id)
                .put("subject", факт.subject)
                .put("predicate", факт.predicate)
                .put("value", факт.value)
                .put("unit", факт.unit)
                .put("anchor", факт.anchor)
                .put("mark", факт.mark.name)
                .put("material", факт.material)
        }
        val ответ = mapper.createObjectNode()
        ответ.set<JsonNode>("items", массив)
        return V2Router.Ответ(200, ответ)
    }

    private fun покрытие(проект: String): V2Router.Ответ {
        val матрица = formulation.coverage(проект)
        val ответ = mapper.createObjectNode()
        ответ.put("total", матрица.total)
        ответ.put("covered", матрица.covered)
        ответ.put("summary", матрица.summary)
        val строки = ответ.putArray("needs")
        матрица.needs.forEach { нужда ->
            val n = строки.addObject()
            n.put("code", нужда.code)
            n.put("statement", нужда.statement)
            n.put("owner", нужда.ownerName)
            n.put("covered", нужда.covered)
            n.put("gap", нужда.gap)
            val цели = n.putArray("goals")
            нужда.goals.forEach { цели.add(it) }
            val сервисы = n.putArray("services")
            нужда.services.forEach { сервисы.add(it) }
        }
        val края = ответ.putArray("stakeholders_without_needs")
        матрица.stakeholdersWithoutNeeds.forEach { края.add(it) }
        return V2Router.Ответ(200, ответ)
    }

    private fun задания(проект: String, роль: String?): V2Router.Ответ {
        val фаза = engine.view(проект)
        val массив = mapper.createArrayNode()
        фаза.scenes
            .filter { it.state != orbita.process.api.SceneState.DONE }
            .filter { роль == null || it.role == роль }
            .forEach { сцена ->
                сцена.blockers.forEach { причина ->
                    массив.addObject()
                        .put("scene", сцена.key)
                        .put("scene_title", сцена.title)
                        .put("role", сцена.role)
                        .put("what", причина)
                        // Сцена закрыта — работать нельзя; открыта — это моя работа
                        .put("waiting", сцена.state == orbita.process.api.SceneState.LOCKED)
                }
            }
        val ответ = mapper.createObjectNode()
        ответ.put("project", проект)
        ответ.set<JsonNode>("items", массив)
        ответ.put(
            "note",
            if (массив.isEmpty) "разрывов нет: все сцены фазы прожиты"
            else "разрывы берутся из условий сцен; закрывается разрыв работой в своей сцене",
        )
        return V2Router.Ответ(200, ответ)
    }

    private fun перечень(проект: String, вид: String): V2Router.Ответ {
        val область = Area.Project(проект)
        val массив = mapper.createArrayNode()
        store.list(область, вид).forEach { сущность ->
            val узел = массив.addObject()
            узел.put("id", сущность.id)
            узел.put("code", сущность.code)
            узел.put("status", сущность.status)
            узел.set<JsonNode>("doc", сущность.doc)
            val носители = links.to(сущность.id, "owns").map { it.from }
            if (носители.isNotEmpty()) {
                val массивНосителей = узел.putArray("owned_by")
                носители.forEach { массивНосителей.add(it) }
            }
            val покрытия = links.to(сущность.id, "covers").map { it.from }
            if (покрытия.isNotEmpty()) {
                val массивПокрытий = узел.putArray("covered_by")
                покрытия.forEach { массивПокрытий.add(it) }
            }
        }
        val ответ = mapper.createObjectNode()
        ответ.set<JsonNode>("items", массив)
        return V2Router.Ответ(200, ответ)
    }


    private fun разобрать(тело: String?): JsonNode =
        if (тело.isNullOrBlank()) mapper.createObjectNode() else mapper.readTree(тело)

    private fun автор(тело: JsonNode): String =
        тело.path("author").asText("").ifBlank { "стенд" }

    private fun требуется(query: Map<String, String>, имя: String): String =
        query[имя] ?: throw IllegalArgumentException("нужен параметр «$имя»")

    /** Фиксация точки: решение принимает движок, здесь только перевод. */
    private fun фиксировать(проект: String, точка: String, тело: JsonNode): V2Router.Ответ =
        V2Router.Ответ(200, PhaseJson.вид(engine.passGate(проект, точка, автор(тело)), mapper))
}
