// Модели как сущности: запись модели, прогон со снимком входов, разрывы.
package orbita.models.internal

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.Provenance
import orbita.models.api.ModelView
import orbita.models.api.Models
import orbita.models.api.RunView
import orbita.models.api.Tool
import orbita.models.api.Verification
import java.time.OffsetDateTime

class EntityModels(
    private val store: EntityStore,
    private val mapper: ObjectMapper = ObjectMapper(),
) : Models {

    private val лестница = listOf("MCR", "SRR", "SDR", "PDR")

    private fun полка(): Entity? =
        store.list(Area.Library, "model_template").maxByOrNull { it.version }

    override fun take(project: String, author: String): List<ModelView> {
        val область = Area.Project(project)
        val шаблон = полка() ?: error(
            "полки моделей нет: python3 tools/v2/gen_models_shelf.py и загрузчик полок",
        )
        шаблон.doc.path("models").forEach { модель ->
            val код = модель.path("code").asText()
            if (store.byCode(область, код) != null) return@forEach
            val документ = mapper.createObjectNode()
            документ.put("template_code", код)
            документ.put("name", модель.path("name").asText())
            документ.put("question", модель.path("question").asText(""))
            документ.put("required_to", модель.path("required_to").asText(""))
            документ.put("tool_status", модель.path("tool_status").asText("build"))
            документ.put("interface_bound", модель.path("interface_bound").asBoolean(false))
            документ.put("verification_status", "unverified")
            документ.putArray("inputs")
            // Статус говорит правду с первого дня: запись есть, ответа нет.
            store.create(
                код, "system_model", область, "10", документ,
                Provenance(Channel.SHELF, author, source = шаблон.code), status = "not_built",
            )
        }
        return list(project)
    }

    override fun list(project: String): List<ModelView> =
        store.list(Area.Project(project), "system_model")
            .sortedBy { it.code }
            .map { вид(project, it) }

    private fun вид(project: String, модель: Entity): ModelView {
        val область = Area.Project(project)
        val прогон = модель.doc.path("last_run").asText("").ifBlank { null }
            ?.let { store.byId(it) }
        val входы = модель.doc.path("inputs").map { it.path("param_ref").asText() }
        val стык = модель.doc.path("interface_ref").asText("").ifBlank { null }
            ?.let { store.byId(it)?.code }
        return ModelView(
            code = модель.code,
            name = модель.doc.path("name").asText(модель.code),
            question = модель.doc.path("question").asText(""),
            requiredTo = модель.doc.path("required_to").asText(""),
            tool = Tool.of(модель.doc.path("tool_status").asText(null)),
            verification = runCatching {
                Verification.valueOf(модель.doc.path("verification_status").asText("unverified").uppercase())
            }.getOrDefault(Verification.UNVERIFIED),
            interfaceCode = стык,
            inputs = входы.mapNotNull { store.byId(it)?.code },
            lastRun = прогон?.let { прогонВид(project, it) },
            gaps = разрывыМодели(область, модель, прогон),
        )
    }

    private fun прогонВид(project: String, прогон: Entity): RunView {
        val снимок = прогон.doc.path("inputs_snapshot").fields().asSequence()
            .associate { (id, версия) -> id to версия.asInt() }
        // Вход, уехавший после прогона, делает ответ устаревшим — это надо
        // видеть, а не узнавать на обзоре.
        val устаревшие = снимок.mapNotNull { (id, была) ->
            val сущность = store.byId(id) ?: return@mapNotNull null
            if (сущность.version > была) "${сущность.code}: версия ${сущность.version} против $была" else null
        }
        return RunView(
            code = прогон.code,
            model = прогон.doc.path("model").asText(""),
            at = прогон.doc.path("at").asText(""),
            by = прогон.doc.path("by").asText(""),
            inputsSnapshot = снимок.mapKeys { (id, _) -> store.byId(id)?.code ?: id },
            outputs = прогон.doc.path("outputs").fields().asSequence()
                .associate { (к, з) -> к to з.asText() },
            proxy = прогон.doc.path("proxy").asBoolean(false),
            staleInputs = устаревшие,
        )
    }

    private fun разрывыМодели(область: Area, модель: Entity, прогон: Entity?): List<String> {
        val разрывы = mutableListOf<String>()
        val входы = модель.doc.path("inputs").map { it.path("param_ref").asText() }
        входы.filter { store.byId(it) == null }.forEach {
            разрывы += "вход модели не задан: параметра «$it» нет — заполните анкету узла"
        }
        if (прогон == null) {
            разрывы += "модель не дала ответ: прогона не было"
        }
        if (модель.doc.path("interface_bound").asBoolean(false) &&
            модель.doc.path("interface_ref").asText("").isBlank()
        ) {
            разрывы += "модель потока не привязана к стыку: поток идёт по ребру состава, а не в воздухе"
        }
        return разрывы
    }

    override fun run(
        project: String,
        model: String,
        author: String,
        outputs: Map<String, String>,
    ): RunView {
        val область = Area.Project(project)
        val запись = store.byCode(область, model)?.takeIf { it.kind == "system_model" }
            ?: error("модели «$model» нет в проекте: возьмите набор с полки")

        val входы = запись.doc.path("inputs").map { it.path("param_ref").asText() }
        val потерянные = входы.filter { store.byId(it) == null }
        require(потерянные.isEmpty()) {
            "вход модели не задан: ${потерянные.joinToString(", ")} — расчёт по пустому месту не делается"
        }

        val прокси = Tool.of(запись.doc.path("tool_status").asText(null)) == Tool.PROXY
        val документ = mapper.createObjectNode()
        документ.put("model", запись.id)
        документ.put("at", OffsetDateTime.now().toString())
        документ.put("by", author)
        документ.put("proxy", прокси)
        val снимок = документ.putObject("inputs_snapshot")
        входы.forEach { id -> store.byId(id)?.let { снимок.put(id, it.version) } }
        val выходы = документ.putObject("outputs")
        outputs.forEach { (к, з) -> выходы.put(к, з) }
        if (прокси) выходы.put("_proxy", "значение получено прокси-оценкой, не расчётом")

        val номер = store.list(область, "model_run").count { it.doc.path("model").asText() == запись.id } + 1
        val прогон = store.create(
            "RUN-${запись.code}-$номер", "model_run", область, "10", документ,
            Provenance(Channel.SERVICE, author, source = запись.code),
        )
        store.update(
            запись.id,
            запись.doc.deepCopy<ObjectNode>().put("last_run", прогон.id),
            Provenance(Channel.SERVICE, author),
            status = "answered",
        )
        return прогонВид(project, прогон)
    }

    override fun budget(project: String, kind: String, gate: String) =
        Budgets(store, полка()).свёртка(project, kind, gate)

    override fun gaps(project: String, gate: String): List<String> {
        val предел = лестница.indexOf(gate.uppercase()).let { if (it < 0) лестница.lastIndex else it }
        return list(project)
            .filter { лестница.indexOf(it.requiredTo).let { i -> i in 0..предел } }
            .flatMap { модель -> модель.gaps.map { "${модель.code} «${модель.name}»: $it" } }
    }
}
