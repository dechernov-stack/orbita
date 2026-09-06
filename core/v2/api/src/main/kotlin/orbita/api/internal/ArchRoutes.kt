// Маршруты архитектуры (сцена 7): состав, карточка гранями, слои, параметры.
//
// Тонкий файл по правилу ТЗ-BACKEND §3: грани, лестницу зрелости и сторожа
// рода считает модуль `architecture`, здесь только перевод HTTP в порты.
package orbita.api.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.architecture.api.Architecture
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.EntityStore
import orbita.kernel.api.Provenance

class ArchRoutes(
    private val store: EntityStore,
    private val architecture: Architecture,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    fun handle(method: String, path: String, query: Map<String, String>, body: String?): V2Router.Ответ? = when {
        method == "GET" && path == "/v2/components" -> состав(требуется(query, "project"))

        method == "POST" && path == "/v2/components" ->
            завестиУзел(требуется(query, "project"), разобрать(body))

        method == "POST" && path == "/v2/components/deploy" ->
            развернуть(требуется(query, "project"), разобрать(body))

        method == "GET" && path.startsWith("/v2/components/") ->
            карточка(
                требуется(query, "project"),
                path.removePrefix("/v2/components/"),
                query["gate"] ?: "SDR",
            )

        method == "GET" && path == "/v2/architecture" -> слои(требуется(query, "project"))

        method == "POST" && path == "/v2/concept" ->
            базоваяКонцепция(требуется(query, "project"), разобрать(body))

        method == "GET" && path == "/v2/concept" -> концепция(требуется(query, "project"))

        method == "GET" && path == "/v2/parameters" ->
            параметры(требуется(query, "project"), query["component"])

        method == "POST" && path == "/v2/parameters" ->
            завестиПараметр(требуется(query, "project"), разобрать(body))

        else -> null
    }

    private fun состав(проект: String): V2Router.Ответ {
        val ответ = mapper.createObjectNode()
        val массив = ответ.putArray("items")
        architecture.components(проект).forEach { к ->
            массив.addObject()
                .put("id", к.id).put("code", к.code).put("name", к.name)
                .put("level", к.level).put("nature", к.nature.name.lowercase())
                .put("kind", к.kind).put("parent", к.parent).put("external", к.external)
                .put("version", к.version)
        }
        return V2Router.Ответ(200, ответ)
    }

    private fun карточка(проект: String, код: String, точка: String): V2Router.Ответ {
        val карточка = architecture.card(проект, код, точка)
        val ответ = mapper.createObjectNode()
        ответ.put("code", карточка.component.code)
        ответ.put("name", карточка.component.name)
        ответ.put("nature", карточка.component.nature.name.lowercase())
        ответ.put("level", карточка.component.level)
        ответ.put("gate", точка)
        val грани = ответ.putArray("facets")
        карточка.facets.forEach { грань ->
            val узел = грани.addObject()
            узел.put("key", грань.key)
            узел.put("title", грань.title)
            узел.put("required_to", грань.requiredTo)
            узел.put("expected", грань.expected)
            val строки = узел.putArray("lines")
            грань.lines.forEach { строки.addObject().put("what", it.what).put("ref", it.ref) }
        }
        val разрывы = ответ.putArray("gaps")
        карточка.gaps.forEach { разрывы.addObject().put("facet", it.facet).put("gate", it.gate).put("what", it.what) }
        return V2Router.Ответ(200, ответ)
    }

    private fun слои(проект: String): V2Router.Ответ {
        val ответ = mapper.createObjectNode()
        val массив = ответ.putArray("layers")
        architecture.layers(проект).forEach { слой ->
            val узел = массив.addObject()
            узел.put("key", слой.key)
            узел.put("title", слой.title)
            val строки = узел.putArray("lines")
            слой.lines.forEach { строки.addObject().put("what", it.what).put("ref", it.ref) }
        }
        return V2Router.Ответ(200, ответ)
    }

    /**
     * Базовый вариант с обоснованием (сцена 7, О3 — AoA).
     *
     * Выбор без обоснования — не решение: отклонённые варианты остаются с
     * причинами, отказы от объёма — списком. Поэтому обоснование обязательно
     * на входе, а не «желательно».
     */
    private fun базоваяКонцепция(проект: String, тело: JsonNode): V2Router.Ответ {
        val область = Area.Project(проект)
        val обоснование = тело.path("rationale").asText("")
        require(обоснование.isNotBlank()) {
            "у базового варианта нет обоснования: выбор без причины — не решение"
        }
        val документ = тело.deepCopy<ObjectNode>()
        документ.remove(listOf("code", "author", "project"))
        документ.put("decided_by", тело.path("author").asText("стенд"))
        документ.put("at", java.time.OffsetDateTime.now().toString())
        val код = тело.path("code").asText("").ifBlank { следующийКод(область, "baseline_concept", "BC") }
        val создано = store.create(
            код, "baseline_concept", область, "7", документ,
            Provenance(Channel.MANUAL, тело.path("author").asText("стенд")),
        )
        return V2Router.Ответ(201, mapper.createObjectNode().put("code", создано.code).put("id", создано.id))
    }

    private fun концепция(проект: String): V2Router.Ответ {
        val ответ = mapper.createObjectNode()
        val массив = ответ.putArray("items")
        store.list(Area.Project(проект), "baseline_concept").forEach { к ->
            массив.addObject()
                .put("code", к.code)
                .put("variant", к.doc.path("variant").asText(""))
                .put("rationale", к.doc.path("rationale").asText(""))
                .put("decided_by", к.doc.path("decided_by").asText(""))
                .put("at", к.doc.path("at").asText(""))
                .set<JsonNode>("rejected", к.doc.path("rejected"))
        }
        return V2Router.Ответ(200, ответ)
    }

    private fun завестиУзел(проект: String, тело: JsonNode): V2Router.Ответ {
        val область = Area.Project(проект)
        val документ = тело.deepCopy<ObjectNode>()
        документ.remove(listOf("code", "author", "project"))
        тело.path("parent").asText("").ifBlank { null }?.let { код ->
            val родитель = store.byCode(область, код)
                ?: throw IllegalArgumentException("родителя «$код» нет в проекте")
            // Инвариант 6: у внешнего узла структуру не правят — она читается.
            require(!родитель.doc.path("external").asBoolean(false)) {
                "узел «$код» внешний — структура читается из модели, править её здесь нельзя"
            }
            документ.put("parent", родитель.id)
        }
        val код = тело.path("code").asText("").ifBlank { следующийКод(область, "component", "CM") }
        val создано = store.create(
            код, "component", область, "8", документ,
            Provenance(Channel.MANUAL, тело.path("author").asText("стенд")),
        )
        return V2Router.Ответ(201, mapper.createObjectNode().put("code", создано.code).put("id", создано.id))
    }

    private fun развернуть(проект: String, тело: JsonNode): V2Router.Ответ {
        architecture.deploy(
            проект,
            тело.path("behaviour").asText(""),
            тело.path("node").asText(""),
            тело.path("author").asText("стенд"),
            тело.path("rationale").asText("").ifBlank { "развёртывание поведения на носителе" },
        )
        return V2Router.Ответ(
            201,
            mapper.createObjectNode()
                .put("behaviour", тело.path("behaviour").asText(""))
                .put("node", тело.path("node").asText("")),
        )
    }

    private fun параметры(проект: String, узел: String?): V2Router.Ответ {
        val ответ = mapper.createObjectNode()
        val массив = ответ.putArray("items")
        architecture.parameters(проект, узел).forEach { п ->
            массив.addObject()
                .put("key", п.key).put("name", п.name).put("target", п.target)
                .put("origin", п.origin).put("maturity_class", п.maturity.name.lowercase())
                .put("reserve_percent", п.maturity.reservePercent)
                .put("uncertainty", п.uncertainty).put("required_to", п.requiredTo)
                .put("version", п.version)
                .set<JsonNode>("measure", mapper.readTree(п.measure))
        }
        return V2Router.Ответ(200, ответ)
    }

    private fun завестиПараметр(проект: String, тело: JsonNode): V2Router.Ответ {
        val область = Area.Project(проект)
        val носитель = store.byCode(область, тело.path("target").asText(""))
            ?: throw IllegalArgumentException("узла «${тело.path("target").asText()}» нет в проекте")
        val документ = тело.deepCopy<ObjectNode>()
        документ.remove(listOf("code", "author", "project"))
        документ.put("target", носитель.id)
        // Параметр — сущность, а не поле: без происхождения он не принимается.
        require(документ.path("origin").asText("").isNotBlank()) {
            "у параметра нет происхождения: значение без источника проверить нечем"
        }
        val ключ = тело.path("key").asText("")
        val создано = store.create(
            "${носитель.code}.$ключ", "parameter", область, "8", документ,
            Provenance(Channel.MANUAL, тело.path("author").asText("стенд")),
        )
        return V2Router.Ответ(201, mapper.createObjectNode().put("code", создано.code).put("id", создано.id))
    }

    // --- общее ------------------------------------------------------------

    private fun следующийКод(область: Area, вид: String, префикс: String): String {
        val занято = store.list(область, вид).mapNotNull {
            Regex("^$префикс-(\\d+)$").find(it.code)?.groupValues?.get(1)?.toIntOrNull()
        }
        return "%s-%04d".format(префикс, (занято.maxOrNull() ?: 0) + 1)
    }

    private fun разобрать(тело: String?): JsonNode =
        if (тело.isNullOrBlank()) mapper.createObjectNode() else mapper.readTree(тело)

    private fun требуется(query: Map<String, String>, имя: String): String =
        query[имя] ?: throw IllegalArgumentException("нужен параметр «$имя»")
}
