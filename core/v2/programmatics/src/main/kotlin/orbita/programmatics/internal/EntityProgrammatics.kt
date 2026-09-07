// Программатика как сущности: пакеты, оценки, созревание, риски.
package orbita.programmatics.internal

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.Provenance
import orbita.programmatics.api.EstimateMethod
import orbita.programmatics.api.EstimateView
import orbita.programmatics.api.MaturationView
import orbita.programmatics.api.PackageView
import orbita.programmatics.api.WbsOffer
import orbita.programmatics.api.Programmatics
import orbita.programmatics.api.RiskView
import java.time.LocalDate

class EntityProgrammatics(
    private val store: EntityStore,
    private val mapper: ObjectMapper = ObjectMapper(),
) : Programmatics {

    private fun полкаWbs(): Entity? =
        store.list(Area.Library, "wbs_template").maxByOrNull { it.version }

    override fun wbsOffer(project: String): List<WbsOffer> {
        val область = Area.Project(project)
        val полка = полкаWbs() ?: error("полки WBS нет: загрузите полки (tools/v2/load_shelves.py)")
        val узлыПоКоду = store.list(область, "component").associateBy { it.code }
        val взятые = store.list(область, "wbs_package").map { it.code }.toSet()

        return полка.doc.path("packages").mapNotNull { пакет ->
            val код = пакет.path("code").asText("")
            if (код.isBlank()) return@mapNotNull null
            val сквозной = пакет.path("cross_cutting").asBoolean(false)
            val названы = пакет.path("pbs_refs").map { it.asText().removePrefix("@") }
            val есть = названы.filter { it in узлыПоКоду }
            val нет = названы.filterNot { it in узлыПоКоду }
            // Рекомендован: сквозной (пары нет по определению) либо есть
            // хотя бы один узел состава, которому пакет парен.
            val рекомендован = сквозной || есть.isNotEmpty()
            WbsOffer(
                code = код,
                name = пакет.path("name").asText(""),
                crossCutting = сквозной,
                nodes = есть,
                missingNodes = нет,
                recommended = рекомендован,
                taken = код in взятые,
                why = when {
                    сквозной -> "сквозной пакет: пары к узлу у него нет по определению"
                    есть.isNotEmpty() -> "парен узлам: " + есть.joinToString(", ")
                    названы.isEmpty() -> "полка не назвала узлов, и пакет не сквозной"
                    else -> "узлов нет в составе: " + нет.joinToString(", ")
                },
            )
        }
    }

    override fun takeWbs(project: String, author: String, codes: List<String>): List<PackageView> {
        val область = Area.Project(project)
        val полка = полкаWbs() ?: error("полки WBS нет: загрузите полки (tools/v2/load_shelves.py)")
        val узлыПоКоду = store.list(область, "component").associateBy { it.code }
        // Пусто — рекомендованный набор: пакеты, которым есть с чем быть
        // парными, плюс сквозные. Всё подряд не берётся: 41 пакет без пары
        // держал бы выход сцены 12 без всякой пользы.
        val брать = codes.ifEmpty { wbsOffer(project).filter { it.recommended }.map { it.code } }.toSet()

        полка.doc.path("packages").forEach { пакет ->
            val код = пакет.path("code").asText()
            if (код.isBlank() || код !in брать || store.byCode(область, код) != null) return@forEach
            val документ = mapper.createObjectNode()
            документ.put("name", пакет.path("name").asText())
            документ.put("cross_cutting", пакет.path("cross_cutting").asBoolean(false))
            пакет.path("parent").asText("").ifBlank { null }?.let { документ.put("parent_code", it) }
            // Пара к узлу: полка называет узлы кодом со знаком @, резолв — здесь.
            val пары = документ.putArray("pbs_refs")
            пакет.path("pbs_refs").forEach { ссылка ->
                val кодУзла = ссылка.asText().removePrefix("@")
                узлыПоКоду[кодУзла]?.let { пары.add(it.id) }
            }
            store.create(
                код, "wbs_package", область, "12", документ,
                Provenance(Channel.SHELF, author, source = полка.code),
            )
        }
        return packages(project)
    }

    override fun packages(project: String): List<PackageView> {
        val область = Area.Project(project)
        val оценки = store.list(область, "cost_estimate").associateBy { it.doc.path("scope").asText() }
        return store.list(область, "wbs_package").sortedBy { it.code }.map { пакет ->
            val оценка = оценки[пакет.id]
            val пары = пакет.doc.path("pbs_refs").mapNotNull { store.byId(it.asText())?.code }
            val сквозной = пакет.doc.path("cross_cutting").asBoolean(false)
            PackageView(
                code = пакет.code,
                name = пакет.doc.path("name").asText(пакет.code),
                parent = пакет.doc.path("parent_code").asText("").ifBlank { null },
                crossCutting = сквозной,
                pbsRefs = пары,
                estimate = оценка?.let { вид(it) },
                gaps = buildList {
                    if (!сквозной && пары.isEmpty()) {
                        add("пакет без пары к узлу состава: работа без предмета не защищается на обзоре")
                    }
                    if (оценка == null) add("оценки нет: к KDP нужен диапазон с допущениями")
                },
            )
        }
    }

    private fun вид(оценка: Entity) = EstimateView(
        min = оценка.doc.path("range").path("min").asDouble(),
        max = оценка.doc.path("range").path("max").asDouble(),
        unit = оценка.doc.path("range").path("unit").asText("млн ₽"),
        method = runCatching {
            EstimateMethod.valueOf(оценка.doc.path("method").asText("rom").uppercase())
        }.getOrDefault(EstimateMethod.ROM),
        assumptions = оценка.doc.path("assumptions").asText(""),
        date = оценка.doc.path("date").asText(""),
    )

    override fun estimate(
        project: String,
        packageCode: String,
        min: Double,
        max: Double,
        unit: String,
        method: EstimateMethod,
        assumptions: String,
        author: String,
    ): EstimateView {
        val область = Area.Project(project)
        val пакет = store.byCode(область, packageCode)
            ?: error("пакета работ «$packageCode» нет в проекте")
        require(max >= min) { "диапазон вывернут: максимум ($max) меньше минимума ($min)" }
        // Точка вместо диапазона на Pre-A — это ложная точность: её потом
        // цитируют как обязательство.
        require(max > min) {
            "оценка одним числом на Pre-A не принимается: дайте диапазон — его и будут читать как оценку"
        }
        require(assumptions.isNotBlank()) {
            "оценка без допущений непроверяема: на чём она держится?"
        }
        val документ = mapper.createObjectNode()
        документ.put("scope", пакет.id)
        документ.putObject("range").put("min", min).put("max", max).put("unit", unit)
        документ.put("assumptions", assumptions)
        документ.put("method", method.name.lowercase())
        документ.put("date", LocalDate.now().toString())
        val запись = store.create(
            "EST-$packageCode", "cost_estimate", область, "12", документ,
            Provenance(Channel.MANUAL, author),
        )
        return вид(запись)
    }

    override fun maturation(project: String, author: String): List<MaturationView> {
        val область = Area.Project(project)
        return store.list(область, "technology").map { технология ->
            val текущий = технология.doc.path("trl_current").asInt(9)
            val нужный = технология.doc.path("trl_required").asInt(0)
            val точка = технология.doc.path("required_by").asText("—")
            val узел = технология.doc.path("component").asText("").ifBlank { null }
                ?.let { store.byId(it)?.code }
            if (текущий >= нужный) {
                return@map MaturationView(
                    технология.code, узел, текущий, нужный, точка, null, null,
                    технология.doc.path("fallback").asText("").ifBlank { null },
                    "разрыва нет: TRL $текущий не ниже требуемого $нужный",
                )
            }

            // Разрыв TRL рождает пакет работ и веху — молча он не остаётся.
            val кодПакета = "04.${технология.code}"
            val пакет = store.byCode(область, кодПакета) ?: store.create(
                кодПакета, "wbs_package", область, "12",
                mapper.createObjectNode().apply {
                    put("name", "созревание: ${технология.doc.path("name").asText(технология.code)}")
                    put("cross_cutting", false)
                    put("maturation_for", технология.id)
                    val пары = putArray("pbs_refs")
                    технология.doc.path("component").asText("").ifBlank { null }?.let { пары.add(it) }
                },
                Provenance(Channel.SERVICE, author, source = технология.code),
            )
            val кодВехи = "TRL-${технология.code}"
            val веха = store.byCode(область, кодВехи) ?: store.create(
                кодВехи, "gate", область, "10",
                mapper.createObjectNode().apply {
                    put("title", "TRL $нужный достигнут: ${технология.doc.path("name").asText(технология.code)}")
                    put("kind", "technology")
                    put("planned_date", "")
                    put("technology", технология.id)
                },
                Provenance(Channel.SERVICE, author, source = технология.code),
            )
            if (технология.doc.path("maturation_package").asText("").isBlank()) {
                store.update(
                    технология.id,
                    технология.doc.deepCopy<ObjectNode>()
                        .put("maturation_package", пакет.id)
                        .put("milestone", веха.id),
                    Provenance(Channel.SERVICE, author),
                )
            }
            MaturationView(
                технология.code, узел, текущий, нужный, точка, пакет.code, веха.code,
                технология.doc.path("fallback").asText("").ifBlank { null },
                "разрыв TRL $текущий → $нужный к точке $точка: заведён пакет ${пакет.code} и веха ${веха.code}" +
                    (технология.doc.path("fallback").asText("").ifBlank { null }
                        ?.let { "; резервное решение: $it" } ?: "; резервного решения нет"),
            )
        }
    }

    override fun risks(project: String): List<RiskView> =
        store.list(Area.Project(project), "risk").map { риск ->
            RiskView(
                code = риск.code,
                statement = риск.doc.path("statement").asText(риск.code),
                category = риск.doc.path("category").asText(""),
                probability = риск.doc.path("probability").asInt(0),
                impact = риск.doc.path("impact").asInt(0),
                strategy = риск.doc.path("strategy").asText(""),
                owner = риск.doc.path("owner").asText(""),
                duePoint = риск.doc.path("due_point").asText("").ifBlank { null }
                    ?.let { store.byId(it)?.code ?: it } ?: "—",
                level = риск.doc.path("probability").asInt(0) * риск.doc.path("impact").asInt(0),
            )
        }.sortedByDescending { it.level }

    override fun gaps(project: String, gate: String): List<String> {
        val разрывы = mutableListOf<String>()
        val созревание = maturation(project, "система")
        val открытые = созревание.filter { it.trlCurrent < it.trlRequired }

        // Оценка без пакетов созревания при открытых разрывах TRL — разрыв к
        // KDP-B: считали не тот объём работ.
        if (открытые.isNotEmpty()) {
            val пакеты = packages(project).map { it.code }.toSet()
            открытые.filter { it.packageCode !in пакеты }.forEach {
                разрывы += "технология ${it.technology}: разрыв TRL есть, пакета созревания нет"
            }
        }
        packages(project).forEach { пакет ->
            пакет.gaps.forEach { разрывы += "${пакет.code} «${пакет.name}»: $it" }
        }
        store.list(Area.Project(project), "risk")
            .filter { it.doc.path("due_point").asText("").isBlank() }
            .forEach { разрывы += "риск ${it.code}: нет срока-точки — срок без точки не наступает" }
        return разрывы
    }
}
