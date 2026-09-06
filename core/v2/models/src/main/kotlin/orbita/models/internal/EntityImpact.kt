// Граф влияния: что заденет правка объекта.
//
// Обход идёт по СВЯЗЯМ реестра и по ссылкам документов сущностей — второго
// источника «кто с кем связан» в системе нет. Глубина ограничена: на
// третьем шаге в графе оказывается весь проект, и он перестаёт отвечать
// на вопрос.
package orbita.models.internal

import orbita.kernel.api.Area
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.models.api.Impact
import orbita.models.api.ImpactEdge
import orbita.models.api.ImpactNode
import orbita.models.api.ImpactView

class EntityImpact(
    private val store: EntityStore,
    private val links: LinkRegistry,
) : Impact {

    /** Поля-ссылки: вид сущности → поля, которыми она держится за другие. */
    private val ссылки = mapOf(
        "requirement" to listOf("carrier"),
        "parameter" to listOf("target"),
        "technology" to listOf("component"),
        "interface" to listOf("a", "b"),
        "state_machine" to listOf("owner"),
        "verification_event" to listOf("requirement"),
        "configuration_item" to listOf("component"),
        "wbs_package" to listOf("maturation_for"),
        "model_run" to listOf("model"),
        "cost_estimate" to listOf("scope"),
    )

    override fun of(project: String, code: String, depth: Int): ImpactView {
        val область = Area.Project(project)
        val корень = store.byCode(область, code) ?: error("объекта «$code» нет в проекте $project")

        val все = store.list(область).associateBy { it.id }
        val узлы = LinkedHashMap<String, ImpactNode>()
        val рёбра = mutableListOf<ImpactEdge>()
        узлы[корень.id] = узел(корень, 0)

        var фронт = listOf(корень)
        for (шаг in 1..depth.coerceIn(1, 3)) {
            val следующий = mutableListOf<Entity>()
            фронт.forEach { текущий ->
                // Связи реестра — в обе стороны: правка задевает и тех, кто
                // ссылается на объект, и тех, на кого ссылается он.
                (links.from(текущий.id) + links.to(текущий.id)).forEach { связь ->
                    val другой = все[if (связь.from == текущий.id) связь.to else связь.from] ?: return@forEach
                    if (узлы.putIfAbsent(другой.id, узел(другой, шаг)) == null) следующий += другой
                    рёбра += ImpactEdge(
                        связь.from, связь.to, связь.type,
                        связь.rationale ?: "связь «${связь.type}»",
                    )
                }
                // Ссылки полями: носитель требования, цель параметра, концы стыка.
                ссылки[текущий.kind].orEmpty().forEach { поле ->
                    val id = текущий.doc.path(поле).asText("")
                    val цель = все[id] ?: return@forEach
                    if (узлы.putIfAbsent(цель.id, узел(цель, шаг)) == null) следующий += цель
                    рёбра += ImpactEdge(текущий.id, цель.id, поле, "поле «$поле»")
                }
                // Обратные ссылки: кто держится за этот объект.
                все.values.forEach { кандидат ->
                    ссылки[кандидат.kind].orEmpty().forEach { поле ->
                        if (кандидат.doc.path(поле).asText("") == текущий.id) {
                            if (узлы.putIfAbsent(кандидат.id, узел(кандидат, шаг)) == null) следующий += кандидат
                            рёбра += ImpactEdge(кандидат.id, текущий.id, поле, "поле «$поле»")
                        }
                    }
                }
            }
            фронт = следующий
            if (фронт.isEmpty()) break
        }

        val поВидам = узлы.values.filter { it.depth > 0 }.groupingBy { it.kind }.eachCount()
        return ImpactView(
            root = корень.code,
            nodes = узлы.values.toList(),
            edges = рёбра.distinctBy { listOf(it.from, it.to, it.type) },
            summary = if (поВидам.isEmpty()) {
                "правка «${корень.code}» пока никого не задевает: объект ни с чем не связан"
            } else {
                "заденет: " + поВидам.entries.joinToString(" · ") { (вид, сколько) -> "$вид — $сколько" }
            },
        )
    }

    private fun узел(сущность: Entity, глубина: Int) = ImpactNode(
        id = сущность.id,
        code = сущность.code,
        kind = сущность.kind,
        title = сущность.doc.path("title").asText("").ifBlank {
            сущность.doc.path("name").asText("").ifBlank {
                сущность.doc.path("statement").asText("").take(60).ifBlank { сущность.code }
            }
        },
        depth = глубина,
    )
}
