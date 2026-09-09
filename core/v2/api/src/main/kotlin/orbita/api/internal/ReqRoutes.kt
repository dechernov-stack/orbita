// Маршруты требований и базирования (сцена 8).
//
// Тонкий файл по правилу ТЗ-BACKEND §3: линт, сторожа базирования и
// подозрительные связи считает модуль `requirements`, здесь только перевод
// HTTP в порты — вместе с отказом, который приходит СПИСКОМ дел.
package orbita.api.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.EntityStore
import orbita.kernel.api.Provenance
import orbita.requirements.api.Applicability
import orbita.requirements.api.Baseline
import orbita.requirements.api.BaselineKind
import orbita.requirements.api.BaselineRefused
import orbita.requirements.api.Baselines
import orbita.requirements.api.Ears
import orbita.requirements.api.RequirementView
import orbita.requirements.api.Requirements

class ReqRoutes(
    private val store: EntityStore,
    private val requirements: Requirements,
    private val baselines: Baselines,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    fun handle(method: String, path: String, query: Map<String, String>, body: String?): V2Router.Ответ? = when {
        method == "GET" && path == "/v2/requirements" -> список(требуется(query, "project"))

        method == "POST" && path == "/v2/requirements" ->
            завестиТребование(требуется(query, "project"), разобрать(body))

        method == "POST" && path == "/v2/requirements/lint" -> линт(разобрать(body))
        // Phase A (шип G): системное из проектного — с основанием и видом уточнения.
        method == "POST" && path == "/v2/requirements/derive" ->
            деривация(требуется(query, "project"), разобрать(body))

        method == "POST" && path == "/v2/requirements/from-typical" ->
            изТипового(требуется(query, "project"), разобрать(body))

        method == "GET" && path == "/v2/baselines" -> снимки(требуется(query, "project"))

        method == "GET" && path == "/v2/baselines/blockers" ->
            помехи(требуется(query, "project"), query["kind"], query["gate"])

        method == "POST" && path == "/v2/baselines" ->
            базировать(требуется(query, "project"), разобрать(body))

        method == "GET" && path == "/v2/suspects" -> подозрения(требуется(query, "project"))

        method == "POST" && path.startsWith("/v2/suspects/") && path.endsWith("/confirm") ->
            подтвердить(
                требуется(query, "project"),
                path.removePrefix("/v2/suspects/").removeSuffix("/confirm"),
                разобрать(body),
            )


        else -> null
    }

    // --- требования -------------------------------------------------------

    private fun список(проект: String): V2Router.Ответ {
        val ответ = mapper.createObjectNode()
        val массив = ответ.putArray("items")
        requirements.list(проект).forEach { массив.add(вид(it)) }
        return V2Router.Ответ(200, ответ)
    }

    private fun вид(т: RequirementView): ObjectNode {
        val узел = mapper.createObjectNode()
        узел.put("id", т.id)
        узел.put("code", т.code)
        узел.put("level", т.level.name.lowercase())
        узел.put("title", т.title)
        узел.put("statement", т.statement)
        узел.put("category", т.category)
        узел.put("ears", т.ears.name.lowercase())
        узел.put("carrier", т.carrier)
        узел.put("carrier_kind", т.carrierKind)
        узел.put("measure", т.measure)
        узел.put("verification_method", т.verificationMethod)
        узел.put("status", т.status)
        узел.put("version", т.version)
        узел.put("template_ref", т.templateRef)
        узел.put("applicability", т.applicability?.name?.lowercase())
        узел.put("after_baseline_changed", т.afterBaselineChanged)
        val источники = узел.putArray("sources")
        т.sources.forEach { источники.add(store.byId(it)?.code ?: it) }
        val пометы = узел.putArray("notes")
        т.notes.forEach { пометы.addObject().put("rule", it.rule).put("what", it.what).put("why", it.why) }
        return узел
    }

    private fun линт(тело: JsonNode): V2Router.Ответ {
        val замечания = requirements.lint(
            тело.path("statement").asText(""),
            Ears.of(тело.path("ears").asText(null)),
        )
        val ответ = mapper.createObjectNode()
        val массив = ответ.putArray("notes")
        замечания.forEach { массив.addObject().put("rule", it.rule).put("what", it.what).put("why", it.why) }
        ответ.put("clean", замечания.isEmpty())
        return V2Router.Ответ(200, ответ)
    }

    private fun завестиТребование(проект: String, тело: JsonNode): V2Router.Ответ {
        val область = Area.Project(проект)
        val документ = тело.deepCopy<ObjectNode>()
        документ.remove(listOf("code", "author", "project"))
        // Носитель приходит КОДОМ (человек знает коды), хранится ссылкой.
        тело.path("carrier").asText("").ifBlank { null }?.let { код ->
            val носитель = store.byCode(область, код) ?: throw IllegalArgumentException(
                "носителя «$код» нет в проекте: без носителя — не требование",
            )
            документ.put("carrier", носитель.id)
        }
        val код = тело.path("code").asText("").ifBlank { следующийКод(область, "requirement", "RQ") }
        val создано = store.create(
            код, "requirement", область, "8", документ,
            Provenance(Channel.MANUAL, тело.path("author").asText("стенд")),
        )
        return V2Router.Ответ(201, вид(requirements.byCode(проект, создано.code)!!))
    }

    private fun деривация(проект: String, тело: JsonNode): V2Router.Ответ {
        val системное = requirements.derive(
            project = проект,
            parent = тело.path("parent").asText(""),
            statement = тело.path("statement").asText(""),
            rationale = тело.path("rationale").asText(""),
            author = тело.path("author").asText("стенд"),
            code = тело.path("code").asText("").ifBlank { null },
            carrier = тело.path("carrier").asText("").ifBlank { null },
            subtype = тело.path("subtype").asText("derivation"),
            category = тело.path("category").asText("").ifBlank { null },
            verificationMethod = тело.path("verification_method").asText("").ifBlank { null },
        )
        return V2Router.Ответ(201, вид(системное))
    }

    private fun изТипового(проект: String, тело: JsonNode): V2Router.Ответ {
        val экземпляр = requirements.instantiate(
            project = проект,
            typicalCode = тело.path("typical").asText(""),
            code = тело.path("code").asText(""),
            carrier = тело.path("carrier").asText(""),
            applicability = Applicability.valueOf(
                тело.path("applicability").asText("applied").uppercase(),
            ),
            author = тело.path("author").asText("стенд"),
            statement = тело.path("statement").asText("").ifBlank { null },
            deviationRationale = тело.path("deviation_rationale").asText("").ifBlank { null },
        )
        return V2Router.Ответ(201, вид(экземпляр))
    }

    // --- базирование ------------------------------------------------------

    private fun снимки(проект: String): V2Router.Ответ {
        val ответ = mapper.createObjectNode()
        val массив = ответ.putArray("items")
        baselines.list(проект).forEach { массив.add(видСнимка(it)) }
        return V2Router.Ответ(200, ответ)
    }

    private fun видСнимка(снимок: Baseline): ObjectNode {
        val узел = mapper.createObjectNode()
        узел.put("name", снимок.name)
        узел.put("kind", снимок.kind.name.lowercase())
        узел.put("gate", снимок.gate)
        узел.put("by", снимок.by)
        узел.put("at", снимок.at)
        узел.put("firm", снимок.items.size - снимок.conditional.size)
        val массив = узел.putArray("items")
        снимок.items.forEach { э ->
            массив.addObject()
                .put("ref", э.ref).put("code", э.code).put("kind", э.kind)
                .put("version", э.version).put("firmness", э.firmness.name.lowercase())
                .put("conditional_on", э.conditionalOn).put("why", э.why)
        }
        return узел
    }

    private fun классСнимка(текст: String?): BaselineKind =
        runCatching { BaselineKind.valueOf((текст ?: "functional").uppercase()) }
            .getOrDefault(BaselineKind.FUNCTIONAL)

    private fun помехи(проект: String, класс: String?, точка: String?): V2Router.Ответ {
        val список = baselines.blockers(проект, классСнимка(класс), точка ?: "SRR")
        val ответ = mapper.createObjectNode()
        val массив = ответ.putArray("items")
        список.forEach { массив.addObject().put("code", it.code).put("rule", it.rule).put("what", it.what) }
        ответ.put(
            "note",
            if (список.isEmpty()) "помех нет: набор можно базировать"
            else "базирование держат ${список.size} помех — каждая называет объект и правило",
        )
        return V2Router.Ответ(200, ответ)
    }

    private fun базировать(проект: String, тело: JsonNode): V2Router.Ответ = try {
        V2Router.Ответ(
            201,
            видСнимка(
                baselines.baseline(
                    проект,
                    тело.path("name").asText(""),
                    классСнимка(тело.path("kind").asText(null)),
                    тело.path("gate").asText("SRR"),
                    тело.path("author").asText("стенд"),
                ),
            ),
        )
    } catch (отказ: BaselineRefused) {
        // Отказ приходит СПИСКОМ дел, а не одним «нельзя».
        val ответ = mapper.createObjectNode()
        ответ.put("refused", true)
        val массив = ответ.putArray("blockers")
        отказ.blockers.forEach { массив.addObject().put("code", it.code).put("rule", it.rule).put("what", it.what) }
        V2Router.Ответ(409, ответ)
    }

    private fun подозрения(проект: String): V2Router.Ответ {
        val ответ = mapper.createObjectNode()
        val массив = ответ.putArray("items")
        baselines.suspects(проект).forEach { с ->
            массив.addObject().put("link", с.linkId).put("type", с.type)
                .put("from", store.byId(с.from)?.code ?: с.from)
                .put("to", store.byId(с.to)?.code ?: с.to)
                .put("why", с.why)
        }
        return V2Router.Ответ(200, ответ)
    }

    private fun подтвердить(проект: String, связь: String, тело: JsonNode): V2Router.Ответ {
        baselines.confirm(проект, связь, тело.path("author").asText("стенд"))
        return V2Router.Ответ(200, mapper.createObjectNode().put("confirmed", связь))
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
