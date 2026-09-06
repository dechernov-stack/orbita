// Компонент гранями: карточка собирается из сущностей вокруг узла.
//
// Ни одна грань не «поле компонента»: функции, стыки, режимы, параметры,
// технологии живут отдельными сущностями и цепляются к узлу ссылкой или
// связью. Поэтому карточка — запрос, а не запись, и пустая грань знает,
// чего в ней ждут (лестница зрелости с полки).
package orbita.architecture.internal

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.architecture.api.Architecture
import orbita.architecture.api.ComponentCard
import orbita.architecture.api.ComponentView
import orbita.architecture.api.Facet
import orbita.architecture.api.FacetLine
import orbita.architecture.api.Gap
import orbita.architecture.api.Layer
import orbita.architecture.api.Maturity
import orbita.architecture.api.Nature
import orbita.architecture.api.ParameterView
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.kernel.api.Provenance

class EntityArchitecture(
    private val store: EntityStore,
    private val links: LinkRegistry,
    private val mapper: ObjectMapper = ObjectMapper(),
) : Architecture {

    private val лестница = Ladder(store)
    private val грани = Facets(store, links, лестница)

    override fun components(project: String): List<ComponentView> =
        store.list(Area.Project(project), "component").map(::вид).sortedWith(
            compareBy({ it.level }, { it.code }),
        )

    private fun вид(e: Entity) = ComponentView(
        id = e.id,
        code = e.code,
        name = e.doc.path("name").asText(e.code),
        level = e.doc.path("level").asInt(0),
        nature = Nature.of(e.doc.path("nature").asText(null)),
        kind = e.doc.path("kind").asText(""),
        parent = e.doc.path("parent").asText("").ifBlank { null }?.let { store.byId(it)?.code },
        external = e.doc.path("external").asBoolean(false),
        templateRef = e.doc.path("template_ref").asText("").ifBlank { null },
        applicability = e.doc.path("applicability").asText("").ifBlank { null },
        version = e.version,
    )

    override fun parameters(project: String, componentCode: String?): List<ParameterView> {
        val область = Area.Project(project)
        val цель = componentCode?.let { store.byCode(область, it)?.id }
        return store.list(область, "parameter")
            .filter { цель == null || it.doc.path("target").asText() == цель }
            .map { п ->
                ParameterView(
                    id = п.id,
                    key = п.doc.path("key").asText(п.code),
                    name = п.doc.path("name").asText(п.doc.path("key").asText(п.code)),
                    target = store.byId(п.doc.path("target").asText())?.code ?: "",
                    measure = п.doc.path("measure").toString(),
                    origin = п.doc.path("origin").asText("manual"),
                    maturity = Maturity.of(п.doc.path("maturity_class").asText(null)),
                    uncertainty = п.doc.path("uncertainty").asDouble(0.0),
                    requiredTo = п.doc.path("required_to").asText(""),
                    basis = п.doc.path("basis").asText("").ifBlank { null },
                    version = п.version,
                )
            }.sortedBy { it.key }
    }

    override fun card(project: String, code: String, gate: String): ComponentCard {
        val область = Area.Project(project)
        val узел = store.byCode(область, code)?.takeIf { it.kind == "component" }
            ?: error("узла «$code» нет в проекте $project")
        val карточка = вид(узел)
        val собранные = грани.собрать(область, узел, карточка)
        return ComponentCard(карточка, собранные, разрывы(узел, карточка, собранные, gate))
    }

    /** Разрывы ступени: обязательная грань пуста — значит, точка не закрыта. */
    private fun разрывы(узел: Entity, карточка: ComponentView, собранные: List<Facet>, gate: String): List<Gap> {
        val разрывы = mutableListOf<Gap>()

        // Правило рода идёт ПЕРВЫМ и говорит точнее лестницы: у поведения без
        // носителя дело не в незакрытой грани, а в том, что модель не сойдётся.
        if (карточка.nature == Nature.BEHAVIOUR &&
            собранные.first { it.key == "deployment" }.lines.none { it.what.startsWith("развёрнут") }
        ) {
            разрывы += Gap(
                карточка.code, "deployment", "SRR",
                "${карточка.code}: поведенческий компонент не развёрнут ни на одном носителе " +
                    "(без `deployed_on` его не соберёт ни модель, ни Capella)",
            )
        }

        val обязательные = лестница.обязательныеДляУзла(узел, карточка.nature.name.lowercase(), gate)
        разрывы += собранные.filter { it.empty && it.key in обязательные }.map { грань ->
            Gap(
                карточка.code, грань.key, обязательные.getValue(грань.key),
                "${карточка.code}: грань «${грань.title}» пуста, а к точке " +
                    "${обязательные.getValue(грань.key)} она обязана быть закрыта — " +
                    (грани.ожидания[грань.key] ?: "нужны данные"),
            )
        }
        return разрывы.distinctBy { it.component to it.facet }
    }

    override fun gaps(project: String, gate: String): List<Gap> =
        components(project).flatMap { узел -> card(project, узел.code, gate).gaps }

    override fun layers(project: String): List<Layer> {
        val область = Area.Project(project)
        fun строки(вид: String, подпись: (Entity) -> String) =
            store.list(область, вид).map { FacetLine(подпись(it), it.id) }

        return listOf(
            Layer(
                "OA", "Операционный анализ",
                строки("capability") { "${it.code} ${it.doc.path("name").asText("")}" } +
                    строки("operational_activity") { "${it.code} ${it.doc.path("name").asText("")}" },
            ),
            Layer(
                "SA", "Системный анализ",
                store.list(область, "function")
                    .filter { it.doc.path("layer").asText("SA") == "SA" }
                    .map { FacetLine("${it.code} ${it.doc.path("name").asText("")}", it.id) },
            ),
            Layer(
                "chains", "Функциональные цепочки",
                строки("functional_chain") { цепочка ->
                    val шаги = цепочка.doc.path("steps").joinToString(" → ") {
                        store.byId(it.asText())?.code ?: it.asText()
                    }
                    "${цепочка.code} ${цепочка.doc.path("name").asText("")}: $шаги"
                },
            ),
            Layer(
                "LA", "Логическая архитектура",
                строки("logical_component") { "${it.code} ${it.doc.path("name").asText("")}" },
            ),
            Layer(
                "PA", "Физическая архитектура",
                components(project).map {
                    FacetLine("${it.code} ${it.name} · ${it.nature.name.lowercase()} · уровень ${it.level}", it.id)
                },
            ),
        )
    }

    override fun deploy(project: String, behaviour: String, node: String, author: String, rationale: String) {
        val область = Area.Project(project)
        val по = store.byCode(область, behaviour) ?: error("узла «$behaviour» нет в проекте")
        val носитель = store.byCode(область, node) ?: error("узла «$node» нет в проекте")
        require(Nature.of(по.doc.path("nature").asText(null)) == Nature.BEHAVIOUR) {
            "«$behaviour» — не поведенческий компонент: развёртывать нечего"
        }
        require(Nature.of(носитель.doc.path("nature").asText(null)) == Nature.NODE) {
            "«$node» — не физический носитель: поведение разворачивается только на node"
        }
        links.link(
            "deployed_on", по.id, носитель.id,
            Provenance(Channel.MANUAL, author), rationale = rationale,
        )
    }
}
