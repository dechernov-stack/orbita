// Маршруты волны 4: модели, свёртки, варианты, программатика, влияние.
//
// Тонкий файл по правилу ТЗ-BACKEND §3: физику, резервы, пороги и Парето
// считают модули, здесь только перевод HTTP в порты.
package orbita.api.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.models.api.Impact
import orbita.models.api.Models
import orbita.models.api.Variants
import orbita.programmatics.api.EstimateMethod
import orbita.programmatics.api.Programmatics

class ModelRoutes(
    private val models: Models,
    private val variants: Variants,
    private val impact: Impact,
    private val programmatics: Programmatics,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    fun handle(method: String, path: String, query: Map<String, String>, body: String?): V2Router.Ответ? = when {
        method == "GET" && path == "/v2/models" -> модели(требуется(query, "project"))

        method == "POST" && path == "/v2/models/take" ->
            взять(требуется(query, "project"), разобрать(body))

        method == "POST" && path.startsWith("/v2/models/") && path.endsWith("/run") ->
            прогон(
                требуется(query, "project"),
                path.removePrefix("/v2/models/").removeSuffix("/run"),
                разобрать(body),
            )

        method == "GET" && path == "/v2/budget" ->
            свёртка(требуется(query, "project"), query["kind"] ?: "mass", query["gate"] ?: "MCR")

        method == "GET" && path == "/v2/variants" -> сравнение(требуется(query, "project"))

        method == "GET" && path == "/v2/impact" ->
            влияние(требуется(query, "project"), требуется(query, "code"), query["depth"]?.toIntOrNull() ?: 2)

        method == "GET" && path == "/v2/wbs" -> пакеты(требуется(query, "project"))

        method == "POST" && path == "/v2/wbs/take" -> взятьWbs(требуется(query, "project"), разобрать(body))

        method == "POST" && path == "/v2/wbs/estimate" -> оценка(требуется(query, "project"), разобрать(body))

        method == "GET" && path == "/v2/maturation" -> созревание(требуется(query, "project"))

        method == "GET" && path == "/v2/risks" -> риски(требуется(query, "project"))

        else -> null
    }

    private fun модели(проект: String): V2Router.Ответ {
        val ответ = mapper.createObjectNode()
        val массив = ответ.putArray("items")
        models.list(проект).forEach { м ->
            val узел = массив.addObject()
            узел.put("code", м.code).put("name", м.name).put("question", м.question)
            узел.put("required_to", м.requiredTo).put("tool", м.tool.name.lowercase())
            узел.put("verification", м.verification.name.lowercase())
            узел.put("interface", м.interfaceCode)
            val входы = узел.putArray("inputs")
            м.inputs.forEach { входы.add(it) }
            val разрывы = узел.putArray("gaps")
            м.gaps.forEach { разрывы.add(it) }
            м.lastRun?.let { прогон ->
                узел.putObject("last_run").apply {
                    put("code", прогон.code).put("at", прогон.at).put("by", прогон.by)
                    put("proxy", прогон.proxy)
                    val устаревшие = putArray("stale_inputs")
                    прогон.staleInputs.forEach { устаревшие.add(it) }
                }
            }
        }
        return V2Router.Ответ(200, ответ)
    }

    private fun взять(проект: String, тело: JsonNode): V2Router.Ответ {
        val взято = models.take(проект, тело.path("author").asText("стенд"))
        return V2Router.Ответ(201, mapper.createObjectNode().put("taken", взято.size))
    }

    private fun прогон(проект: String, модель: String, тело: JsonNode): V2Router.Ответ {
        val выходы = тело.path("outputs").fields().asSequence()
            .associate { (к, з) -> к to з.asText() }
        val прогон = models.run(проект, модель, тело.path("author").asText("стенд"), выходы)
        val ответ = mapper.createObjectNode()
        ответ.put("code", прогон.code).put("at", прогон.at).put("proxy", прогон.proxy)
        val снимок = ответ.putObject("inputs_snapshot")
        прогон.inputsSnapshot.forEach { (к, в) -> снимок.put(к, в) }
        val выход = ответ.putObject("outputs")
        прогон.outputs.forEach { (к, з) -> выход.put(к, з) }
        return V2Router.Ответ(201, ответ)
    }

    private fun свёртка(проект: String, вид: String, точка: String): V2Router.Ответ {
        val бюджет = models.budget(проект, вид, точка)
        val ответ = mapper.createObjectNode()
        ответ.put("kind", бюджет.kind).put("gate", бюджет.gate)
        ответ.put("sum", бюджет.sum).put("unit", бюджет.unit)
        ответ.put("with_class_reserve", бюджет.withClassReserve)
        ответ.put("system_margin_percent", бюджет.systemMarginPercent)
        ответ.put("with_system_margin", бюджет.withSystemMargin)
        ответ.put("note", бюджет.note)
        val строки = ответ.putArray("lines")
        бюджет.lines.forEach { с ->
            строки.addObject()
                .put("component", с.component).put("value", с.value).put("unit", с.unit)
                .put("maturity", с.maturity).put("class_reserve", с.classReserve).put("origin", с.origin)
        }
        return V2Router.Ответ(200, ответ)
    }

    private fun сравнение(проект: String): V2Router.Ответ {
        val сравнение = variants.compare(проект)
        val ответ = mapper.createObjectNode()
        ответ.put("note", сравнение.note)
        val массив = ответ.putArray("items")
        сравнение.variants.forEach { в ->
            val узел = массив.addObject()
            узел.put("code", в.code).put("name", в.name)
            узел.put("rejected_by", в.rejectedBy).put("pareto", в.pareto)
            val метрики = узел.putArray("metrics")
            в.metrics.forEach { м ->
                метрики.addObject()
                    .put("key", м.key).put("title", м.title).put("value", м.value).put("unit", м.unit)
                    .put("threshold", м.threshold).put("worse_if", м.worseIf)
                    .put("passed", м.passed).put("group", м.group)
            }
        }
        return V2Router.Ответ(200, ответ)
    }

    private fun влияние(проект: String, код: String, глубина: Int): V2Router.Ответ {
        val граф = impact.of(проект, код, глубина)
        val ответ = mapper.createObjectNode()
        ответ.put("root", граф.root).put("summary", граф.summary)
        val узлы = ответ.putArray("nodes")
        граф.nodes.forEach { у ->
            узлы.addObject().put("id", у.id).put("code", у.code)
                .put("kind", у.kind).put("title", у.title).put("depth", у.depth)
        }
        val рёбра = ответ.putArray("edges")
        граф.edges.forEach { р ->
            рёбра.addObject().put("from", р.from).put("to", р.to).put("type", р.type).put("why", р.why)
        }
        return V2Router.Ответ(200, ответ)
    }

    private fun пакеты(проект: String): V2Router.Ответ {
        val ответ = mapper.createObjectNode()
        val массив = ответ.putArray("items")
        programmatics.packages(проект).forEach { п ->
            val узел = массив.addObject()
            узел.put("code", п.code).put("name", п.name).put("parent", п.parent)
            узел.put("cross_cutting", п.crossCutting)
            val пары = узел.putArray("pbs_refs")
            п.pbsRefs.forEach { пары.add(it) }
            п.estimate?.let { о ->
                узел.putObject("estimate")
                    .put("min", о.min).put("max", о.max).put("unit", о.unit)
                    .put("method", о.method.name.lowercase()).put("assumptions", о.assumptions)
                    .put("date", о.date)
            }
            val разрывы = узел.putArray("gaps")
            п.gaps.forEach { разрывы.add(it) }
        }
        return V2Router.Ответ(200, ответ)
    }

    private fun взятьWbs(проект: String, тело: JsonNode): V2Router.Ответ {
        val пакеты = programmatics.takeWbs(проект, тело.path("author").asText("стенд"))
        return V2Router.Ответ(201, mapper.createObjectNode().put("taken", пакеты.size))
    }

    private fun оценка(проект: String, тело: JsonNode): V2Router.Ответ {
        val оценка = programmatics.estimate(
            проект,
            тело.path("package").asText(""),
            тело.path("min").asDouble(),
            тело.path("max").asDouble(),
            тело.path("unit").asText("млн ₽"),
            runCatching { EstimateMethod.valueOf(тело.path("method").asText("rom").uppercase()) }
                .getOrDefault(EstimateMethod.ROM),
            тело.path("assumptions").asText(""),
            тело.path("author").asText("стенд"),
        )
        return V2Router.Ответ(
            201,
            mapper.createObjectNode().put("min", оценка.min).put("max", оценка.max)
                .put("unit", оценка.unit).put("method", оценка.method.name.lowercase()),
        )
    }

    private fun созревание(проект: String): V2Router.Ответ {
        val ответ = mapper.createObjectNode()
        val массив = ответ.putArray("items")
        programmatics.maturation(проект, "стенд").forEach { м ->
            массив.addObject()
                .put("technology", м.technology).put("component", м.component)
                .put("trl_current", м.trlCurrent).put("trl_required", м.trlRequired)
                .put("required_by", м.requiredBy).put("package", м.packageCode)
                .put("milestone", м.milestoneGate).put("fallback", м.fallback)
                .put("words", м.words)
        }
        return V2Router.Ответ(200, ответ)
    }

    private fun риски(проект: String): V2Router.Ответ {
        val ответ = mapper.createObjectNode()
        val массив = ответ.putArray("items")
        programmatics.risks(проект).forEach { р ->
            массив.addObject()
                .put("code", р.code).put("statement", р.statement).put("category", р.category)
                .put("probability", р.probability).put("impact", р.impact).put("level", р.level)
                .put("strategy", р.strategy).put("owner", р.owner).put("due_point", р.duePoint)
        }
        return V2Router.Ответ(200, ответ)
    }

    private fun разобрать(тело: String?): JsonNode =
        if (тело.isNullOrBlank()) mapper.createObjectNode() else mapper.readTree(тело)

    private fun требуется(query: Map<String, String>, имя: String): String =
        query[имя] ?: throw IllegalArgumentException("нужен параметр «$имя»")
}
