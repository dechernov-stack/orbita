// Маршруты сцен 1–6 (волны 1–2): проект, замысел, стейкхолдеры и нужды,
// цели, ограничения, сервисы.
//
// Вынесены из V2Router отдельным файлом по правилу ТЗ-BACKEND §3: файл
// тонкий, домена внутри нет. Роутер остался диспетчером.
//
// Здесь только перевод HTTP в вызовы портов и обратно. Все решения —
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
import orbita.process.api.ProcessEngine
import java.time.LocalDate

class SceneRoutes(
    private val store: EntityStore,
    private val links: LinkRegistry,
    private val engine: ProcessEngine,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    fun handle(method: String, path: String, query: Map<String, String>, body: String?): V2Router.Ответ? = when {
        method == "GET" && path == "/v2/phase" -> фаза(требуется(query, "project"))

        method == "POST" && path == "/v2/projects" -> открытьПроект(разобрать(body))

        // Портфель: без него продукт теряет проект при перезагрузке страницы —
        // открыть заново можно, вернуться к открытому было нельзя.
        method == "GET" && path == "/v2/projects" -> портфель()

        // План работ фазы: даты точек и окна сцен. Порог владельца — план у
        // первой доступной сцены; остальные окна до обзора — пометы.
        method == "GET" && path == "/v2/plan" -> план(требуется(query, "project"))


        method == "POST" && path == "/v2/plan" -> задатьПлан(требуется(query, "project"), разобрать(body))

        method == "POST" && path == "/v2/intent" -> замысел(требуется(query, "project"), разобрать(body))

        method == "POST" && path == "/v2/stakeholders" ->
            завести(требуется(query, "project"), "stakeholder", "3", разобрать(body))

        method == "POST" && path == "/v2/needs" -> нужда(требуется(query, "project"), разобрать(body))

        method == "POST" && path == "/v2/goals" -> цель(требуется(query, "project"), разобрать(body))

        method == "POST" && path == "/v2/constraints" ->
            ограничение(требуется(query, "project"), разобрать(body))

        method == "POST" && path == "/v2/services" -> сервис(требуется(query, "project"), разобрать(body))


        else -> null
    }

    private fun портфель(): V2Router.Ответ {
        val ответ = mapper.createObjectNode()
        val массив = ответ.putArray("items")
        store.ofKind("project").forEach { проект ->
            массив.addObject()
                .put("code", проект.code)
                .put("name", проект.doc.path("name").asText(проект.code))
                .put("standard", проект.doc.path("standard").asText(""))
                .put("lead", проект.doc.path("lead").asText(""))
                .put("phase", проект.doc.path("phase").asText("Pre-Phase A"))
        }
        return V2Router.Ответ(200, ответ)
    }

    private fun план(проект: String): V2Router.Ответ {
        val запись = store.list(Area.Project(проект), "plan").lastOrNull()
        val ответ = mapper.createObjectNode()
        if (запись == null) {
            ответ.put("planned", false)
            ответ.put("note", "план работ фазы не задан: ленте нечего показывать")
        } else {
            ответ.put("planned", true)
            ответ.put("set_by", запись.doc.path("set_by").asText(""))
            ответ.set<JsonNode>("gate_dates", запись.doc.path("gate_dates"))
            ответ.set<JsonNode>("scene_windows", запись.doc.path("scene_windows"))
        }
        return V2Router.Ответ(200, ответ)
    }

    private fun задатьПлан(проект: String, тело: JsonNode): V2Router.Ответ {
        val область = Area.Project(проект)
        val документ = mapper.createObjectNode()
        документ.put("phase", тело.path("phase").asText("Pre-Phase A"))
        документ.set<JsonNode>("gate_dates", тело.path("gate_dates"))
        документ.set<JsonNode>("scene_windows", тело.path("scene_windows"))
        документ.put("set_by", автор(тело))
        val прежний = store.list(область, "plan").lastOrNull()
        val запись = if (прежний == null) {
            store.create("PLAN-$проект", "plan", область, "1", документ, Provenance(Channel.MANUAL, автор(тело)))
        } else {
            store.update(прежний.id, документ, Provenance(Channel.MANUAL, автор(тело)))
        }
        // Даты точек живут в самих точках: план их задаёт, а не дублирует.
        тело.path("gate_dates").forEach { пара ->
            val точка = store.byCode(область, пара.path("gate").asText()) ?: return@forEach
            val док = точка.doc.deepCopy<ObjectNode>().put("planned_date", пара.path("date").asText(""))
            store.update(точка.id, док, Provenance(Channel.MANUAL, автор(тело)))
        }
        return V2Router.Ответ(201, mapper.createObjectNode().put("code", запись.code).put("version", запись.version))
    }

    private fun фаза(проект: String) = V2Router.Ответ(200, PhaseJson.вид(engine.view(проект), mapper))

    private fun открытьПроект(тело: JsonNode): V2Router.Ответ {
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
        return V2Router.Ответ(201, PhaseJson.вид(engine.view(код), mapper))
    }

    private fun замысел(проект: String, тело: JsonNode): V2Router.Ответ {
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
        ответ.set<ObjectNode>("phase", PhaseJson.вид(engine.view(проект), mapper))
        return V2Router.Ответ(200, ответ)
    }

    private fun завести(проект: String, вид: String, сцена: String, тело: JsonNode): V2Router.Ответ {
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
        return V2Router.Ответ(201, ответ)
    }

    private fun нужда(проект: String, тело: JsonNode): V2Router.Ответ {
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

    private fun цель(проект: String, тело: JsonNode): V2Router.Ответ {
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
    private fun ограничение(проект: String, тело: JsonNode): V2Router.Ответ {
        val область = Area.Project(проект)
        val занято = store.list(область, "constraint").mapNotNull {
            Regex("^Р(\\d+)$").find(it.code)?.groupValues?.get(1)?.toIntOrNull()
        }
        val код = тело.path("code").asText("").ifBlank { "Р${(занято.maxOrNull() ?: 0) + 1}" }
        val документ = тело.deepCopy<ObjectNode>().apply { remove(listOf("code", "author", "project")) }
        val сущность = store.create(код, "constraint", область, "5", документ, Provenance(Channel.MANUAL, автор(тело)))
        return V2Router.Ответ(201, mapper.createObjectNode().put("id", сущность.id).put("code", сущность.code))
    }

    /** Сцена 6: сервис покрывает нужды — без этой связи он ничей. */
    private fun сервис(проект: String, тело: JsonNode): V2Router.Ответ {
        val ответ = завести(проект, "service", "6", тело)
        val область = Area.Project(проект)
        тело.path("covers").forEach { ссылка ->
            val нужда = store.byCode(область, ссылка.asText()) ?: store.byId(ссылка.asText())
            requireNotNull(нужда) { "нужда «${ссылка.asText()}» не найдена" }
            links.link("covers", ответ.body.path("id").asText(), нужда.id, Provenance(Channel.MANUAL, автор(тело)))
        }
        return ответ
    }

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

    private fun разобрать(тело: String?): JsonNode =
        if (тело.isNullOrBlank()) mapper.createObjectNode() else mapper.readTree(тело)

    private fun автор(тело: JsonNode): String =
        тело.path("author").asText("").ifBlank { "стенд" }

    private fun требуется(query: Map<String, String>, имя: String): String =
        query[имя] ?: throw IllegalArgumentException("нужен параметр «$имя»")
}
