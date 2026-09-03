// Одно дерево носителей (ADR-044): модель аппарата НЕ хранится отдельным
// объектом — она собирается из поддерева узла КА в составе системы. Величины
// живут в parameters узлов (имя = ключ анкеты Ф-06, единица — справочника),
// структурные не-величины (пресет, режимы, каналы, политика буфера) — в
// profile. Сборщик кладёт их в форму контракта contracts/spacecraft, которую
// уже читают расчёты SpacecraftViews: расчёту всё равно, откуда пришёл
// документ, а хранить два состава (PBS и «модель аппарата») система больше
// не имеет права.
//
// Единицы не пересчитываются молча: параметр в чужой единице — проблема
// сборки, а не тихое умножение. Чего в дереве нет — того нет и в контракте;
// контракт затем проходит схему, и считать по непрошедшему нельзя.
package orbita.out

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/** Собранный контракт аппарата и список того, чего дереву не хватило. */
data class AssembledCarrier(
    val doc: ObjectNode,
    val problems: List<String>,
    /** Узлы, вошедшие в сборку (для показа «из чего собрано»). */
    val nodes: List<String>,
    /** Что сборка ВЫЧИСЛИЛА, а не прочитала: энергия АКБ из заряда и напряжения, Δv суммой — с происхождением словами. */
    val computed: List<String> = emptyList(),
)

/** Перевод величины из канона справочника в единицу контракта; null — справочник конверсии не знает. */
typealias UnitConverter = (value: Double, fromUnit: String, toUnit: String) -> Double?

object CarrierAssembly {
    const val ROLE_SPACECRAFT = "spacecraft"
    const val ROLE_PLATFORM = "platform"
    const val ROLE_PAYLOAD = "payload"
    const val ROLE_SUBSYSTEM = "subsystem"

    /** Соответствие «параметр узла (имя, единица) → поле контракта». */
    private data class Field(val param: String, val unit: String, val target: String, val required: Boolean = false)

    private val PLATFORM_FIELDS = listOf(
        Field("dry_mass", "kg", "/platform/dry_mass_kg", required = true),
        Field("wet_mass", "kg", "/platform/wet_mass_kg"),
        Field("design_life", "a", "/platform/design_life_years"),
        Field("reliability_at_eol", "1", "/platform/reliability_at_eol"),
        Field("sa_area", "m2", "/platform/power/sa_area_m2", required = true),
        Field("sa_efficiency", "1", "/platform/power/sa_efficiency", required = true),
        Field("sa_degradation_pct_per_year", "%", "/platform/power/sa_degradation_pct_per_year"),
        Field("battery_energy", "Wh", "/platform/power/battery_wh", required = true),
        Field("battery_max_dod", "1", "/platform/power/battery_max_dod"),
        Field("attitude_accuracy", "deg", "/platform/attitude/pointing_accuracy_deg", required = true),
    )

    fun role(doc: JsonNode): String = doc.path("profile").path("role").asText("")

    /** Величина параметра узла по имени: значение и единица, если параметр задан числом. */
    fun quantityOf(doc: JsonNode, name: String): Pair<Double, String>? =
        doc.path("parameters").firstOrNull { it.path("name").asText("") == name }
            ?.path("quantity")?.takeIf { it.path("value").isNumber }
            ?.let { it.path("value").asDouble() to it.path("unit").asText("") }

    /** Поддерево определения: узел и все потомки по parent, в порядке обхода в ширину. */
    fun subtree(root: JsonNode, all: List<JsonNode>): List<JsonNode> {
        val byParent = all.groupBy { it.path("parent").asText("") }
        val out = mutableListOf<JsonNode>()
        val queue = ArrayDeque(listOf(root))
        val seen = mutableSetOf<String>()
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            val id = n.path("id").asText("")
            if (!seen.add(id)) continue
            out.add(n)
            byParent[id].orEmpty().sortedBy { it.path("id").asText("") }.forEach(queue::addLast)
        }
        return out
    }

    /**
     * Сборка контракта аппарата из узла КА и определений проекта. Возвращает
     * документ даже при проблемах: вызывающий решает, годится ли он
     * (валидация схемой — на границе, здесь — только форма и претензии).
     */
    private val DELTA_V_CATEGORIES = listOf("phasing", "maintenance", "collision_avoidance", "deorbit")

    fun assemble(
        root: JsonNode,
        all: List<JsonNode>,
        mapper: ObjectMapper = ObjectMapper(),
        convert: UnitConverter? = null,
    ): AssembledCarrier {
        val rootId = root.path("id").asText("")
        val problems = mutableListOf<String>()
        val computed = mutableListOf<String>()
        val used = mutableListOf(rootId)
        val doc = mapper.createObjectNode()
        doc.put("id", rootId)
        root.path("profile").path("preset").asText("").takeIf { it.isNotBlank() }?.let { doc.put("preset", it) }
        if (root.has("lifecycle")) doc.set<JsonNode>("lifecycle", root.path("lifecycle").deepCopy())

        val nodes = subtree(root, all)
        val platform = nodes.firstOrNull { it !== root && role(it) == ROLE_PLATFORM }
        val payload = nodes.firstOrNull { it !== root && role(it) == ROLE_PAYLOAD }

        val platformNode = doc.putObject("platform")
        if (platform == null) {
            problems += "$rootId: в поддереве КА нет узла платформы (profile.role = platform)"
        } else {
            used += platform.path("id").asText("")
            PLATFORM_FIELDS.forEach { f -> place(platform, f, doc, problems) }
            val pid = platform.path("id").asText("")
            // энергия АКБ: записана энергией (Вт·ч) либо ВЫЧИСЛЯЕТСЯ из заряда (А·ч)
            // и напряжения (В) — разные величины, молча не подменяются
            if (quantityOf(platform, "battery_energy") == null) {
                val cap = quantityOf(platform, "battery_capacity")
                val volt = quantityOf(platform, "battery_voltage")
                when {
                    cap == null -> Unit
                    cap.second != "Ah" -> problems += "$pid: заряд АКБ в «${cap.second}», ожидалась Ah"
                    volt == null -> problems += "$pid: нет напряжения для энергии — заряд АКБ (А·ч) без напряжения (В) в энергию не переводится"
                    volt.second != "V" -> problems += "$pid: напряжение АКБ в «${volt.second}», ожидалась V"
                    else -> {
                        val wh = cap.first * volt.first // вычислено явно, с происхождением (А·ч × В)
                        platformNode.with("power").put("battery_wh", wh)
                        computed += "$pid: энергия АКБ $wh Wh = ${cap.first} Ah × ${volt.first} V (вычислено сборкой)"
                    }
                }
            }
            // скорость разворота: в узле — канон (rad/s), контракт ждёт °/с; перевод
            // делает справочник единиц, а не сборщик — без справочника это претензия
            quantityOf(platform, "slew_rate")?.let { (v, u) ->
                val degS = when (u) {
                    "deg/s" -> v
                    "rad/s" -> convert?.invoke(v, "rad/s", "deg/s")
                    else -> null
                }
                if (degS == null) problems += "$pid: скорость разворота в «$u» — нет перевода в deg/s по справочнику"
                else platformNode.with("attitude").put("slew_rate_deg_s", degS)
            }
            val pp = platform.path("profile")
            pp.path("mel_margin_policy").takeIf { it.isObject }?.let { platformNode.set<JsonNode>("mel_margin_policy", it.deepCopy()) }
            pp.path("power").path("sa_mounting").asText("").takeIf { it.isNotBlank() }
                ?.let { platformNode.with("power").put("sa_mounting", it) }
            pp.path("attitude").path("modes").takeIf { it.isArray }
                ?.let { platformNode.with("attitude").set<JsonNode>("modes", it.deepCopy()) }
            pp.path("propulsion").takeIf { it.isObject }?.let { platformNode.set<JsonNode>("propulsion", it.deepCopy()) }
        }
        // обязательные блоки контракта существуют и при пустой платформе:
        // схема назовёт недостающие поля поимённо, а не «platform не объект»
        platformNode.with("power")
        platformNode.with("attitude")

        // Ведомость масс — строки из подсистем всего поддерева КА и самой ПН:
        // расчёт резерва по зрелости читает её как раньше.
        val mel = platformNode.putArray("mel")
        nodes.filter { it !== root && role(it) == ROLE_SUBSYSTEM }.forEach { n -> melItem(n, mel, problems, used) }
        payload?.let { melItem(it, mel, problems, used, subsystem = "payload") }

        val payloadNode = doc.putObject("payload")
        if (payload == null) {
            problems += "$rootId: в поддереве КА нет узла полезной нагрузки (profile.role = payload)"
            payloadNode.putArray("links")
            payloadNode.putObject("onboard")
        } else {
            val pp = payload.path("profile")
            pp.path("architecture").asText("").takeIf { it.isNotBlank() }?.let { payloadNode.put("architecture", it) }
                ?: problems.add("${payload.path("id").asText("")}: у узла ПН не задана архитектура (profile.architecture)")
            payloadNode.set<JsonNode>("links", pp.path("links").takeIf { it.isArray }?.deepCopy() ?: mapper.createArrayNode())
            val onboard = pp.path("onboard").takeIf { it.isObject }?.deepCopy<ObjectNode>() ?: mapper.createObjectNode()
            quantityOf(payload, "buffer_size")?.let { (v, u) ->
                if (u == "MB") onboard.put("buffer_mb", v)
                else problems += "${payload.path("id").asText("")}: параметр buffer_size в «$u», ожидалась MB"
            }
            payloadNode.set<JsonNode>("onboard", onboard)
            pp.path("ephemeris_beacon").takeIf { it.isObject }?.let { payloadNode.set<JsonNode>("ephemeris_beacon", it.deepCopy()) }
        }

        root.path("profile").path("modes").takeIf { it.isArray }?.let { doc.set<JsonNode>("modes", it.deepCopy()) }

        // Δv-бюджет — таблица манёвров узла КА: категории контракта ложатся
        // в него, прочие названия — претензия; свёртка — сумма в м/с
        val maneuvers = root.path("maneuvers")
        if (maneuvers.isArray && !maneuvers.isEmpty) {
            var total = 0.0
            val budget = platformNode.with("propulsion").with("delta_v_budget_ms")
            maneuvers.forEach { m ->
                val name = m.path("name").asText("")
                val q = m.path("delta_v")
                val unit = q.path("unit").asText("")
                if (!q.path("value").isNumber || unit != "m/s") {
                    problems += "$rootId: манёвр «$name» — Δv в «$unit», ожидалась m/s"
                    return@forEach
                }
                val v = q.path("value").asDouble()
                total += v // вычислено явно, с происхождением (сумма манёвров)
                if (name in DELTA_V_CATEGORIES) budget.put(name, budget.path(name).asDouble(0.0) + v) // вычислено явно, с происхождением (сумма по категории)
                else problems += "$rootId: манёвр «$name» не относится к категориям контракта (${DELTA_V_CATEGORIES.joinToString(", ")}) — в бюджет аппарата не вошёл"
            }
            computed += "$rootId: Δv-бюджет $total m/s — сумма ${maneuvers.size()} манёвров (вычислено сборкой)"
            if (budget.isEmpty) platformNode.path("propulsion").let { (it as ObjectNode).remove("delta_v_budget_ms") }
        }
        return AssembledCarrier(doc, problems, used.distinct(), computed)
    }

    /** Суммарный Δv по таблице манёвров узла КА, м/с — для показа и анкеты. */
    fun deltaVTotal(root: JsonNode): Double? =
        root.path("maneuvers").takeIf { it.isArray && !it.isEmpty }
            ?.sumOf { m -> m.path("delta_v").takeIf { it.path("unit").asText("") == "m/s" }?.path("value")?.asDouble(0.0) ?: 0.0 }

    private fun place(node: JsonNode, f: Field, doc: ObjectNode, problems: MutableList<String>) {
        val id = node.path("id").asText("")
        val q = quantityOf(node, f.param)
        if (q == null) {
            if (f.required) problems += "$id: не задан параметр ${f.param} (${f.unit}) — поле ${f.target.substringAfterLast('/')} контракта"
            return
        }
        if (q.second != f.unit) {
            problems += "$id: параметр ${f.param} в «${q.second}», ожидалась ${f.unit} — единицы не пересчитываются молча"
            return
        }
        val parts = f.target.trim('/').split('/')
        var cur: ObjectNode = doc
        parts.dropLast(1).forEach { cur = cur.with(it) }
        cur.put(parts.last(), q.first)
    }

    private fun melItem(
        node: JsonNode,
        mel: com.fasterxml.jackson.databind.node.ArrayNode,
        problems: MutableList<String>,
        used: MutableList<String>,
        subsystem: String? = null,
    ) {
        val id = node.path("id").asText("")
        val mass = quantityOf(node, "mass")
        if (mass == null) {
            if (subsystem == null) problems += "$id: у подсистемы не задана масса (параметр mass, kg)"
            return
        }
        if (mass.second != "kg") {
            problems += "$id: масса в «${mass.second}», ожидалась kg"
            return
        }
        val profile = node.path("profile")
        val sub = subsystem ?: profile.path("subsystem").asText("")
        if (sub.isBlank()) {
            problems += "$id: у подсистемы не назван вид (profile.subsystem)"
            return
        }
        val item = mel.addObject()
            .put("name", node.path("name").asText(id))
            .put("subsystem", sub)
            .put("mass_kg", mass.first)
            .put("maturity", profile.path("maturity").asText("").ifBlank { "new" })
        quantityOf(node, "quantity")?.let { (v, u) ->
            if (u == "pcs") item.put("quantity", v.toInt())
            else problems += "$id: количество в «$u», ожидалась pcs"
        }
        used += id
    }
}
