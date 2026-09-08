// Маршруты волны 4: модели, свёртки, варианты, программатика, влияние.
//
// Тонкий файл по правилу ТЗ-BACKEND §3: физику, резервы, пороги и Парето
// считают модули, здесь только перевод HTTP в порты.
package orbita.api.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.EntityStore
import orbita.kernel.api.Provenance
import orbita.models.api.Impact
import orbita.models.api.Models
import orbita.models.api.Variants
import orbita.programmatics.api.EstimateMethod
import orbita.programmatics.api.Programmatics

class ModelRoutes(
    private val store: EntityStore,
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

        // Варианты и пороги заводятся каналом: сравнение читало их с
        // первого дня волны 4, а завести было нечем — сцене 7 нечего было
        // сравнивать, и §2 отчёта наполниться не мог.
        method == "POST" && path == "/v2/variants" ->
            завестиВариант(требуется(query, "project"), разобрать(body))

        method == "POST" && path == "/v2/criteria" ->
            завестиПорог(требуется(query, "project"), разобрать(body))

        method == "GET" && path == "/v2/impact" ->
            влияние(требуется(query, "project"), требуется(query, "code"), query["depth"]?.toIntOrNull() ?: 2)

        method == "GET" && path == "/v2/wbs" -> пакеты(требуется(query, "project"))

        method == "GET" && path == "/v2/wbs/offer" -> окноWbs(требуется(query, "project"))

        method == "POST" && path == "/v2/wbs/take" -> взятьWbs(требуется(query, "project"), разобрать(body))

        method == "POST" && path == "/v2/wbs/estimate" -> оценка(требуется(query, "project"), разобрать(body))

        // План пакета: для пакета созревания он ведёт дату вехи.
        method == "POST" && path == "/v2/wbs/plan" -> планПакета(требуется(query, "project"), разобрать(body))

        method == "GET" && path == "/v2/maturation" -> созревание(требуется(query, "project"))

        // Записи сцен 10–12: технология, риск, оценка засорения. Без них
        // сцены видно, но работать в них нечем.
        method == "GET" && path == "/v2/technologies" -> технологии(требуется(query, "project"))

        method == "POST" && path == "/v2/technologies" ->
            завестиТехнологию(требуется(query, "project"), разобрать(body))

        method == "POST" && path == "/v2/risks" -> завестиРиск(требуется(query, "project"), разобрать(body))

        method == "GET" && path == "/v2/oda" -> засорение(требуется(query, "project"))

        // Дата вехи технологии. Точки фазы получают даты планом работ, а
        // веха созревания рождается разрывом TRL и даты не имеет — без неё
        // точка не может учесть срок риска, привязанного к этой вехе.
        method == "POST" && path.matches(Regex("/v2/gates/[A-Za-zА-Яа-я0-9-]+/date")) ->
            датаВехи(
                требуется(query, "project"),
                path.removePrefix("/v2/gates/").removeSuffix("/date"),
                разобрать(body),
            )

        method == "POST" && path == "/v2/oda" -> завестиОсз(требуется(query, "project"), разобрать(body))

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
        // Рамка проекта — рядом со свёрткой: перебор виден там же, где сумма.
        val рамка = ответ.putArray("frame")
        бюджет.frame.forEach { р ->
            рамка.addObject()
                .put("constraint", р.constraint).put("statement", р.statement)
                .put("op", р.op).put("limit", р.limit).put("unit", р.unit)
                .put("with_class_reserve", р.withClassReserve)
                .put("within_by_class", р.withinByClass)
                .put("actual", р.actual).put("within", р.within)
                .put("excess", р.excess)
                .put("gate", р.gate).put("system_margin_percent", р.systemMarginPercent)
                .put("words", р.words)
        }
        return V2Router.Ответ(200, ответ)
    }

    /**
     * Завести вариант построения с его показателями.
     *
     * Показатели лежат В ВАРИАНТЕ, а не отдельными сущностями: они не
     * живут без него и правятся вместе с ним одной версией. Порог —
     * наоборот, общий для всех вариантов, поэтому он отдельно.
     */
    private fun завестиВариант(проект: String, тело: JsonNode): V2Router.Ответ {
        val область = Area.Project(проект)
        val имя = тело.path("name").asText("")
        require(имя.isNotBlank()) { "у варианта построения нет имени" }
        val показатели = тело.path("metrics")
        require(показатели.isArray && показатели.size() > 0) {
            "вариант без показателей сравнивать не с чем: назовите хотя бы один"
        }
        показатели.forEach { м ->
            require(м.path("key").asText("").isNotBlank() && м.path("value").isNumber) {
                "у показателя нужен ключ и числовое значение: ${м.toString().take(120)}"
            }
        }
        val документ = тело.deepCopy<ObjectNode>()
        документ.remove(listOf("code", "author", "project"))
        val код = тело.path("code").asText("")
            .ifBlank { следующийКод(область, "constellation_variant", "VAR") }
        val создано = store.create(
            код, "constellation_variant", область, "7", документ,
            Provenance(Channel.MANUAL, тело.path("author").asText("стенд")),
        )
        return V2Router.Ответ(201, mapper.createObjectNode().put("code", создано.code))
    }

    /** Порог показателя: чем именно вариант отсеивается. */
    private fun завестиПорог(проект: String, тело: JsonNode): V2Router.Ответ {
        val область = Area.Project(проект)
        val ключ = тело.path("key").asText("")
        require(ключ.isNotBlank()) { "порогу нужен ключ показателя" }
        require(тело.path("threshold").isNumber) { "порог — число, иначе сравнивать нечем" }
        val хуже = тело.path("worse_if").asText("greater")
        require(хуже in setOf("greater", "less")) {
            "«хуже если» — greater или less: без этого не видно, в какую сторону порог"
        }
        val документ = тело.deepCopy<ObjectNode>()
        документ.remove(listOf("code", "author", "project"))
        val прежний = store.list(область, "criterion").firstOrNull {
            it.doc.path("key").asText() == ключ
        }
        val создано = if (прежний != null) {
            store.update(прежний.id, документ, Provenance(Channel.MANUAL, тело.path("author").asText("стенд")))
        } else {
            store.create(
                тело.path("code").asText("").ifBlank { следующийКод(область, "criterion", "CRIT") },
                "criterion", область, "7", документ,
                Provenance(Channel.MANUAL, тело.path("author").asText("стенд")),
            )
        }
        return V2Router.Ответ(201, mapper.createObjectNode().put("code", создано.code))
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
            п.estimate?.let { о -> узел.set<JsonNode>("estimate", KindJson.оценка(mapper, о)) }
            val разрывы = узел.putArray("gaps")
            п.gaps.forEach { разрывы.add(it) }
        }
        return V2Router.Ответ(200, ответ)
    }

    private fun окноWbs(проект: String): V2Router.Ответ {
        val ответ = mapper.createObjectNode()
        val массив = ответ.putArray("items")
        val окно = programmatics.wbsOffer(проект)
        окно.forEach { п ->
            массив.addObject()
                .put("code", п.code).put("name", п.name)
                .put("cross_cutting", п.crossCutting)
                .put("recommended", п.recommended).put("taken", п.taken)
                .put("why", п.why)
                .also { у ->
                    у.putArray("nodes").also { а -> п.nodes.forEach { а.add(it) } }
                    у.putArray("missing_nodes").also { а -> п.missingNodes.forEach { а.add(it) } }
                }
        }
        ответ.put("total", окно.size)
        ответ.put("recommended", окно.count { it.recommended })
        ответ.put("taken", окно.count { it.taken })
        return V2Router.Ответ(200, ответ)
    }

    private fun взятьWbs(проект: String, тело: JsonNode): V2Router.Ответ {
        val коды = тело.path("codes").map { it.asText() }.filter { it.isNotBlank() }
        val было = programmatics.packages(проект).size
        val пакеты = programmatics.takeWbs(проект, тело.path("author").asText("стенд"), коды)
        return V2Router.Ответ(
            201,
            mapper.createObjectNode()
                .put("taken", пакеты.size - было)
                .put("already", было)
                .put("total", пакеты.size),
        )
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
        return V2Router.Ответ(201, KindJson.оценка(mapper, оценка))
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

    private fun технологии(проект: String): V2Router.Ответ {
        val ответ = mapper.createObjectNode()
        val массив = ответ.putArray("items")
        store.list(Area.Project(проект), "technology").forEach { т ->
            массив.addObject()
                .put("code", т.code)
                .put("name", т.doc.path("name").asText(т.code))
                .put("component", store.byId(т.doc.path("component").asText())?.code)
                .put("trl_current", т.doc.path("trl_current").asInt())
                .put("trl_required", т.doc.path("trl_required").asInt())
                .put("required_by", т.doc.path("required_by").asText(""))
                .put("fallback", т.doc.path("fallback").asText("").ifBlank { null })
        }
        return V2Router.Ответ(200, ответ)
    }

    private fun завестиТехнологию(проект: String, тело: JsonNode): V2Router.Ответ {
        val область = Area.Project(проект)
        val узел = store.byCode(область, тело.path("component").asText(""))
            ?: throw IllegalArgumentException(
                "узла «${тело.path("component").asText()}» нет в проекте: " +
                    "технология критична для КОНКРЕТНОГО узла, иначе её нечем закрывать",
            )
        val текущий = тело.path("trl_current").asInt(0)
        val нужный = тело.path("trl_required").asInt(0)
        require(текущий in 1..9 && нужный in 1..9) { "TRL — целое от 1 до 9 (шкала NASA)" }
        val документ = mapper.createObjectNode()
        документ.put("name", тело.path("name").asText(""))
        документ.put("component", узел.id)
        документ.put("trl_current", текущий)
        документ.put("trl_required", нужный)
        документ.put("required_by", тело.path("required_by").asText("PDR"))
        тело.path("fallback").asText("").ifBlank { null }?.let { документ.put("fallback", it) }
        val код = тело.path("code").asText("").ifBlank { следующийКод(область, "technology", "TECH") }
        val создано = store.create(
            код, "technology", область, "10", документ,
            Provenance(Channel.MANUAL, тело.path("author").asText("стенд")),
        )
        // Разрыв TRL сразу рождает пакет созревания и веху — не отдельной кнопкой.
        programmatics.maturation(проект, тело.path("author").asText("стенд"))
        return V2Router.Ответ(201, mapper.createObjectNode().put("code", создано.code))
    }

    private fun завестиРиск(проект: String, тело: JsonNode): V2Router.Ответ {
        val область = Area.Project(проект)
        val формулировка = тело.path("statement").asText("")
        require(формулировка.isNotBlank()) { "риск без формулировки — беспокойство, а не риск" }
        val документ = тело.deepCopy<ObjectNode>()
        документ.remove(listOf("code", "author", "project"))
        // Срок — ТОЧКА: даты плывут, точки нет.
        тело.path("due_point").asText("").ifBlank { null }?.let { ключ ->
            store.byCode(область, ключ)?.let { документ.put("due_point", it.id) }
        }
        val код = тело.path("code").asText("").ifBlank { следующийКод(область, "risk", "RSK") }
        val создано = store.create(
            код, "risk", область, "11", документ,
            Provenance(Channel.MANUAL, тело.path("author").asText("стенд")),
        )
        return V2Router.Ответ(201, mapper.createObjectNode().put("code", создано.code))
    }

    private fun планПакета(проект: String, тело: JsonNode): V2Router.Ответ {
        val веха = programmatics.planPackage(
            проект,
            тело.path("package").asText(""),
            тело.path("start").asText(""),
            тело.path("end").asText(""),
            тело.path("author").asText("стенд"),
        )
        return V2Router.Ответ(
            200,
            mapper.createObjectNode()
                .put("package", тело.path("package").asText(""))
                .put("start", тело.path("start").asText(""))
                .put("end", тело.path("end").asText(""))
                // Веха, чья дата поехала следом: связь плана и срока видна.
                .put("milestone", веха ?: ""),
        )
    }

    private fun датаВехи(проект: String, ключ: String, тело: JsonNode): V2Router.Ответ {
        val область = Area.Project(проект)
        val веха = store.byCode(область, ключ) ?: throw IllegalArgumentException("вехи «$ключ» нет в проекте")
        require(веха.kind == "gate") { "«$ключ» — не веха, а ${веха.kind}" }
        require(веха.doc.path("kind").asText("") == "technology") {
            "даты точек фазы задаются планом работ, а не поштучно: правьте план"
        }
        val дата = тело.path("date").asText("")
        require(Regex("""\d{4}-\d{2}-\d{2}""").matches(дата)) {
            "дата вехи — ГГГГ-ММ-ДД; пришло «$дата»"
        }
        // Источник даты — плановый конец пакета созревания. Рука допустима,
        // ТОЛЬКО пока плана нет: иначе две даты разъезжаются, и никто не
        // замечает, пока срок не наступит (решение владельца 08.09).
        val технология = веха.doc.path("technology").asText("").ifBlank { null }
        val пакет = технология?.let { т ->
            store.list(область, "wbs_package").firstOrNull { it.doc.path("maturation_for").asText("") == т }
        }
        val планКонец = пакет?.doc?.path("plan")?.path("end")?.asText("")?.ifBlank { null }
        require(планКонец == null) {
            "у вехи есть пакет созревания ${пакет?.code} с планом до $планКонец: " +
                "дата вехи идёт от плана пакета — правьте план, а не веху"
        }
        val обновлён = store.update(
            веха.id,
            веха.doc.deepCopy<ObjectNode>()
                .put("planned_date", дата)
                // Помета источника: по ней видно, что дата поставлена рукой,
                // а не посчитана планом, — и что план ещё предстоит.
                .put("date_source", "вручную"),
            Provenance(Channel.MANUAL, тело.path("author").asText("стенд")),
        )
        return V2Router.Ответ(
            200,
            mapper.createObjectNode().put("gate", обновлён.code).put("planned_date", дата)
                .put("date_source", "вручную"),
        )
    }

    private fun засорение(проект: String): V2Router.Ответ {
        val ответ = mapper.createObjectNode()
        val массив = ответ.putArray("items")
        store.list(Area.Project(проект), "debris_assessment").forEach { о ->
            массив.addObject()
                .put("code", о.code)
                .put("variant", о.doc.path("variant").asText(""))
                .put("active_lifetime_years", о.doc.path("active_lifetime_years").asDouble())
                .put("deorbit_dv", о.doc.path("deorbit_dv").asText(""))
                .put("passive_lifetime_years", о.doc.path("passive_lifetime_years").asDouble())
                .put("normative_active", о.doc.path("normative_active").asText(""))
                .put("normative_passive", о.doc.path("normative_passive").asText(""))
                .put("compliant_active", о.doc.path("compliant_active").asBoolean(false))
                .put("compliant_passive", о.doc.path("compliant_passive").asBoolean(false))
                .put("atmosphere_model", о.doc.path("atmosphere_model").asText(""))
                .put("ballistic_coefficient", о.doc.path("ballistic_coefficient").asText(""))
        }
        return V2Router.Ответ(200, ответ)
    }

    /**
     * Оценка засорения: ДВА случая на один вариант — штатный увод и
     * отказ ДУ (поставка владельца 08.09).
     *
     * Без второго случая ОСЗ неполна: она отвечает «уложимся ли, если всё
     * сработает», и молчит о том, что будет, если двигательная установка
     * откажет, — а норматив на этот случай другой (25 лет против 5).
     * Вердикты считаются, а не принимаются на слово: `compliant_*` в теле
     * запроса игнорируется.
     */
    private fun завестиОсз(проект: String, тело: JsonNode): V2Router.Ответ {
        val область = Area.Project(проект)
        val документ = тело.deepCopy<ObjectNode>()
        документ.remove(listOf("code", "author", "project", "compliant_active", "compliant_passive"))

        val активный = требуетсяЧисло(тело, "active_lifetime_years", "время существования после увода ДУ")
        val пассивный = требуетсяЧисло(тело, "passive_lifetime_years", "пассивный сход при отказе ДУ")
        // Модель атмосферы подписывается: срок схода без неё — не число, а
        // мнение, и на обзоре его нечем защитить.
        require(тело.path("atmosphere_model").asText("").isNotBlank()) {
            "модель атмосферы не названа: срок схода без неё не проверить"
        }
        require(!тело.path("ballistic_coefficient").isMissingNode) {
            "баллистический коэффициент m/(Cd·A) не задан: сход считается по нему"
        }
        val нормаА = порогЛет(тело.path("normative_active").asText(""), "active_lifetime")
        val нормаП = порогЛет(тело.path("normative_passive").asText(""), "passive_lifetime")
        документ.put("compliant_active", активный <= нормаА)
        документ.put("compliant_passive", пассивный <= нормаП)

        val код = тело.path("code").asText("").ifBlank { следующийКод(область, "debris_assessment", "ODA") }
        val создано = store.create(
            код, "debris_assessment", область, "11", документ,
            Provenance(Channel.MANUAL, тело.path("author").asText("стенд")),
        )
        return V2Router.Ответ(
            201,
            mapper.createObjectNode().put("code", создано.code)
                .put("compliant_active", активный <= нормаА)
                .put("compliant_passive", пассивный <= нормаП),
        )
    }

    private fun требуетсяЧисло(тело: JsonNode, поле: String, что: String): Double {
        val узел = тело.path(поле)
        require(узел.isNumber) { "$что: поле «$поле» обязано быть числом лет" }
        return узел.asDouble()
    }

    /**
     * Порог нормы — ПОЛЕ `limit` пункта норматива, а не число из прозы.
     *
     * Разбор строки был дефектом того же класса, что «проверка, которая
     * молча проходит»: «Б1: увод не более 5 лет» давал порог 1 из
     * ОБОЗНАЧЕНИЯ норматива, и честный увод за 3,8 года объявлялся
     * нарушением. Правка «брать число перед словом лет» лечила случай, а
     * не класс: это всё ещё разбор прозы. Правило владельца 08.09 —
     * пороги только полями.
     *
     * Норматив называется ссылкой на карточку полки; порог берётся из
     * пункта с нужным ключом. Нет карточки или нет поля — ОТКАЗ: сверять
     * не с чем, и молча подставлять умолчание значит вернуть тот же класс
     * дефекта с другой стороны.
     */
    private fun порогЛет(норматив: String, ключ: String): Double {
        require(норматив.isNotBlank()) { "норматив не назван: сверять срок не с чем" }
        val карточка = store.byCode(Area.Library, норматив)
            ?: store.list(Area.Library, "normative_document").firstOrNull {
                it.doc.path("designation").asText() == норматив
            }
            ?: throw IllegalArgumentException(
                "норматива «$норматив» нет на полке: порог живёт полем `limit` его пункта, " +
                    "а не числом в тексте",
            )
        val предел = карточка.doc.path("clauses").firstNotNullOfOrNull { пункт ->
            пункт.path("limit").takeIf { it.path("key").asText("") == ключ }
        } ?: throw IllegalArgumentException(
            "у норматива «$норматив» нет пункта с порогом «$ключ»: " +
                "добавьте limit{key,op,value,unit} в поставку норматива",
        )
        require(предел.path("value").isNumber) { "порог «$ключ» не число: ${предел.path("value")}" }
        return предел.path("value").asDouble()
    }

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
