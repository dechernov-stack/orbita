// Тонкие маршруты v2 (ТЗ-BACKEND §3: файл ≤ 300 строк, домена внутри нет).
//
// Роутер только переводит HTTP в вызовы портов и обратно. Все решения —
// в модулях: движок считает состояние сцен, оценщик отвечает за условия,
// хранилище держит версии. Здесь нет ни одного правила предметной области.
package orbita.api.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.kernel.api.Provenance
import orbita.library.api.Shelves
import orbita.process.api.PhaseView
import orbita.process.api.ProcessEngine
import java.time.LocalDate

class V2Router(
    private val store: EntityStore,
    private val links: LinkRegistry,
    private val engine: ProcessEngine,
    private val shelves: Shelves,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    /** Ответ роутера: код и тело. HTTP-обвязка — снаружи. */
    data class Ответ(val code: Int, val body: JsonNode)

    fun handle(method: String, path: String, query: Map<String, String>, body: String?): Ответ? = when {
        method == "GET" && path == "/v2/phase" -> фаза(требуется(query, "project"))

        method == "POST" && path == "/v2/projects" -> открытьПроект(разобрать(body))

        method == "POST" && path == "/v2/intent" -> замысел(требуется(query, "project"), разобрать(body))

        method == "POST" && path == "/v2/stakeholders" ->
            завести(требуется(query, "project"), "stakeholder", "3", разобрать(body))

        method == "POST" && path == "/v2/needs" -> нужда(требуется(query, "project"), разобрать(body))

        method == "POST" && path == "/v2/goals" -> цель(требуется(query, "project"), разобрать(body))

        method == "POST" && path == "/v2/constraints" ->
            ограничение(требуется(query, "project"), разобрать(body))

        method == "POST" && path == "/v2/services" -> сервис(требуется(query, "project"), разобрать(body))

        // Полки: загрузка поставки и просмотр. Виды v2 приземляются сюда —
        // это и есть приёмник, которого не хватало волне 0.
        method == "POST" && path == "/v2/shelves" -> положитьНаПолку(разобрать(body))

        method == "GET" && path == "/v2/shelves" -> полка(требуется(query, "kind"))

        // «Мои задания» (волна 1): задание — это адресованный разрыв, а не
        // отдельная сущность со своим статусом. Разрывы берутся из условий
        // сцен, адрес — из роли сцены: кто отвечает за сцену, тот и работает.
        method == "GET" && path == "/v2/my-tasks" ->
            задания(требуется(query, "project"), query["role"])

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

    private fun фаза(проект: String) = Ответ(200, вид(engine.view(проект)))

    private fun положитьНаПолку(тело: JsonNode): Ответ {
        val вид = тело.path("kind").asText("")
        val код = тело.path("code").asText("")
        require(вид.isNotBlank() && код.isNotBlank()) { "полке нужны вид и код записи" }
        val запись = shelves.put(вид, код, тело.path("doc"), автор(тело))
        return Ответ(201, mapper.createObjectNode().put("code", запись.code).put("kind", запись.kind))
    }

    private fun полка(вид: String): Ответ {
        val массив = mapper.createArrayNode()
        shelves.of(вид).forEach { запись ->
            массив.addObject().put("code", запись.code).put("kind", запись.kind)
                .set<JsonNode>("doc", запись.doc)
        }
        val ответ = mapper.createObjectNode()
        ответ.set<JsonNode>("items", массив)
        return Ответ(200, ответ)
    }

    private fun открытьПроект(тело: JsonNode): Ответ {
        val код = тело.path("code").asText("").ifBlank { "PJ-" + LocalDate.now().toString().replace("-", "") }
        val область = Area.Project(код)
        val автор = автор(тело)
        store.create(
            код, "project", область, "1",
            mapper.createObjectNode()
                .put("name", тело.path("name").asText(код))
                .put("standard", тело.path("standard").asText("NASA-7120"))
                .put("mission_class", тело.path("mission_class").asText(""))
                .put("lead", тело.path("lead").asText(автор)),
            Provenance(Channel.MANUAL, автор),
        )
        // Точки фазы заводятся сразу с датами по умолчанию от сегодняшнего дня:
        // сцена 1 обязана оставить фазу с датами, а не с пустотой.
        val шаблон = engine.openPhase(код, тело.path("template").asText("PHT-9001"))
        шаблон.gates.forEach { точка ->
            store.create(
                точка.key, "gate", область, "1",
                mapper.createObjectNode()
                    .put("title", точка.title)
                    .put("planned_date", точка.plannedDate ?: ""),
                Provenance(Channel.MANUAL, автор),
            )
        }
        return Ответ(201, вид(engine.view(код)))
    }

    private fun замысел(проект: String, тело: JsonNode): Ответ {
        val область = Area.Project(проект)
        val автор = автор(тело)
        val документ = mapper.createObjectNode()
        listOf("for_whom", "what", "where", "horizon", "text").forEach { поле ->
            тело.path(поле).asText("").takeIf { it.isNotBlank() }?.let { документ.put(поле, it) }
        }
        val принят = тело.path("accepted").asBoolean(false)
        val прежний = store.list(область, "intent").firstOrNull()
        val сущность = if (прежний == null) {
            store.create(
                "INT-0001", "intent", область, "2", документ,
                Provenance(Channel.MANUAL, автор), status = if (принят) "accepted" else "draft",
            )
        } else {
            store.update(прежний.id, документ, Provenance(Channel.MANUAL, автор), if (принят) "accepted" else null)
        }
        val ответ = mapper.createObjectNode()
        ответ.put("id", сущность.id)
        ответ.put("status", сущность.status)
        ответ.set<ObjectNode>("phase", вид(engine.view(проект)))
        return Ответ(200, ответ)
    }

    private fun завести(проект: String, вид: String, сцена: String, тело: JsonNode): Ответ {
        val область = Area.Project(проект)
        val автор = автор(тело)
        val код = тело.path("code").asText("").ifBlank { следующийКод(область, вид) }
        val документ = тело.deepCopy<ObjectNode>().apply {
            remove(listOf("code", "author", "project", "owner", "covers"))
        }
        val сущность = store.create(код, вид, область, сцена, документ, Provenance(Channel.MANUAL, автор))
        val ответ = mapper.createObjectNode()
        ответ.put("id", сущность.id)
        ответ.put("code", сущность.code)
        return Ответ(201, ответ)
    }

    private fun нужда(проект: String, тело: JsonNode): Ответ {
        val ответ = завести(проект, "need", "3", тело)
        // Носитель нужды — обязательная связь: нужда без стейкхолдера повиснет
        // и на выходе сцены 3, и в матрице покрытия.
        val носитель = тело.path("owner").asText("")
        if (носитель.isNotBlank()) {
            val область = Area.Project(проект)
            val стейкхолдер = store.byCode(область, носитель) ?: store.byId(носитель)
            requireNotNull(стейкхолдер) { "стейкхолдер «$носитель» не найден: нужде нужен носитель" }
            links.link("owns", стейкхолдер.id, ответ.body.path("id").asText(), Provenance(Channel.MANUAL, автор(тело)))
        }
        return ответ
    }

    private fun цель(проект: String, тело: JsonNode): Ответ {
        val ответ = завести(проект, "goal", "4", тело)
        val область = Area.Project(проект)
        // Цель покрывает нужды: без этой связи нужда останется невыполненной,
        // и сцена 4 честно об этом скажет.
        тело.path("covers").forEach { ссылка ->
            val нужда = store.byCode(область, ссылка.asText()) ?: store.byId(ссылка.asText())
            requireNotNull(нужда) { "нужда «${ссылка.asText()}» не найдена" }
            links.link("covers", ответ.body.path("id").asText(), нужда.id, Provenance(Channel.MANUAL, автор(тело)))
        }
        return ответ
    }

    /** Сцена 5: ограничение получает код Р-серии — он стабилен и на него ссылаются. */
    private fun ограничение(проект: String, тело: JsonNode): Ответ {
        val область = Area.Project(проект)
        val занято = store.list(область, "constraint").mapNotNull {
            Regex("^Р(\\d+)$").find(it.code)?.groupValues?.get(1)?.toIntOrNull()
        }
        val код = тело.path("code").asText("").ifBlank { "Р${(занято.maxOrNull() ?: 0) + 1}" }
        val документ = тело.deepCopy<ObjectNode>().apply { remove(listOf("code", "author", "project")) }
        val сущность = store.create(код, "constraint", область, "5", документ, Provenance(Channel.MANUAL, автор(тело)))
        return Ответ(201, mapper.createObjectNode().put("id", сущность.id).put("code", сущность.code))
    }

    /** Сцена 6: сервис покрывает нужды — без этой связи он ничей. */
    private fun сервис(проект: String, тело: JsonNode): Ответ {
        val ответ = завести(проект, "service", "6", тело)
        val область = Area.Project(проект)
        тело.path("covers").forEach { ссылка ->
            val нужда = store.byCode(область, ссылка.asText()) ?: store.byId(ссылка.asText())
            requireNotNull(нужда) { "нужда «${ссылка.asText()}» не найдена" }
            links.link("covers", ответ.body.path("id").asText(), нужда.id, Provenance(Channel.MANUAL, автор(тело)))
        }
        return ответ
    }

    private fun задания(проект: String, роль: String?): Ответ {
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
        return Ответ(200, ответ)
    }

    private fun перечень(проект: String, вид: String): Ответ {
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
        return Ответ(200, ответ)
    }

    private fun фиксировать(проект: String, точка: String, тело: JsonNode): Ответ =
        Ответ(200, вид(engine.passGate(проект, точка, автор(тело))))

    private fun следующийКод(область: Area, вид: String): String {
        val префикс = when (вид) {
            "stakeholder" -> "SK"
            "need" -> "ND"
            "goal" -> "MG"
            "service" -> "SV"
            else -> вид.take(2).uppercase()
        }
        val занято = store.list(область, вид).mapNotNull {
            Regex("^$префикс-(\\d+)$").find(it.code)?.groupValues?.get(1)?.toIntOrNull()
        }
        return "%s-%04d".format(префикс, (занято.maxOrNull() ?: 0) + 1)
    }

    private fun вид(фаза: PhaseView): ObjectNode {
        val узел = mapper.createObjectNode()
        узел.put("project", фаза.project)
        узел.put("standard", фаза.standard)
        узел.put("phase", фаза.phase)
        узел.put("current_scene", фаза.currentScene)
        val сцены = узел.putArray("scenes")
        фаза.scenes.forEach { сцена ->
            val с = сцены.addObject()
            с.put("key", сцена.key)
            с.put("title", сцена.title)
            с.put("order", сцена.order)
            с.put("role", сцена.role)
            с.put("question", сцена.question)
            с.put("state", сцена.state.name.lowercase())
            val причины = с.putArray("blockers")
            сцена.blockers.forEach { причины.add(it) }
            val шаги = с.putArray("steps")
            сцена.steps.forEach { шаг ->
                шаги.addObject()
                    .put("title", шаг.title)
                    .put("place", шаг.place)
                    .put("hint", шаг.hint)
                    .put("done", шаг.done)
            }
        }
        val точки = узел.putArray("gates")
        фаза.gates.forEach { точка ->
            val т = точки.addObject()
            т.put("key", точка.key)
            т.put("title", точка.title)
            т.put("order", точка.order)
            т.put("planned_date", точка.plannedDate)
            т.put("passed", точка.passed)
            val блок = т.putArray("blocking")
            точка.blocking.forEach { блок.add(it) }
        }
        return узел
    }

    private fun разобрать(тело: String?): JsonNode =
        if (тело.isNullOrBlank()) mapper.createObjectNode() else mapper.readTree(тело)

    private fun автор(тело: JsonNode): String =
        тело.path("author").asText("").ifBlank { "стенд" }

    private fun требуется(query: Map<String, String>, имя: String): String =
        query[имя] ?: throw IllegalArgumentException("нужен параметр «$имя»")
}
