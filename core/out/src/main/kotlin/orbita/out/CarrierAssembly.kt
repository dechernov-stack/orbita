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
)

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
    fun assemble(root: JsonNode, all: List<JsonNode>, mapper: ObjectMapper = ObjectMapper()): AssembledCarrier {
        val rootId = root.path("id").asText("")
        val problems = mutableListOf<String>()
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
        return AssembledCarrier(doc, problems, used.distinct())
    }

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
