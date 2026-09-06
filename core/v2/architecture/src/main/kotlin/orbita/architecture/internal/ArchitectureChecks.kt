// Условия ворот, которые знает контур архитектуры (сцена 7).
package orbita.architecture.internal

import orbita.architecture.api.Architecture
import orbita.architecture.api.Nature
import orbita.kernel.api.Area
import orbita.kernel.api.EntityStore
import orbita.readiness.api.CheckResult
import orbita.readiness.api.ExtraChecks

class ArchitectureChecks(
    private val store: EntityStore,
    private val architecture: Architecture,
) : ExtraChecks {

    override fun of(project: String, check: String): CheckResult? {
        val область = Area.Project(project)
        val (имя, аргумент) = check.split(":", limit = 2).let { it[0] to it.getOrNull(1) }

        return when (имя) {
            "composition_min" -> {
                val нужно = (аргумент ?: "1").toIntOrNull() ?: 1
                val узлы = architecture.components(project)
                if (узлы.size >= нужно) CheckResult.ok
                else CheckResult.no("узлов состава ${узлы.size} из $нужно: состав берётся каркасом класса миссии")
            }

            "each_behaviour_deployed" -> {
                val без = architecture.components(project)
                    .filter { it.nature == Nature.BEHAVIOUR }
                    .filter { узел ->
                        architecture.card(project, узел.code).facets
                            .single { it.key == "deployment" }.lines.none { it.what.startsWith("развёрнут") }
                    }
                if (без.isEmpty()) CheckResult.ok
                else CheckResult.no(
                    "поведение без носителя: " + без.joinToString(", ") { it.code } +
                        " — behaviour обязан быть развёрнут хотя бы на одном node",
                )
            }

            "baseline_concept_chosen" -> {
                val концепция = store.list(область, "baseline_concept").lastOrNull()
                when {
                    концепция == null -> CheckResult.no(
                        "базовый вариант не назван: выбор без обоснования не решение",
                    )
                    концепция.doc.path("rationale").asText("").isBlank() -> CheckResult.no(
                        "у базового варианта нет обоснования: отклонённые остаются с причинами",
                    )
                    else -> CheckResult.ok
                }
            }

            "component_stage_closed" -> {
                val точка = аргумент ?: "MCR"
                val разрывы = architecture.gaps(project, точка)
                if (разрывы.isEmpty()) CheckResult.ok
                else CheckResult.no(
                    "ступень $точка не закрыта у ${разрывы.map { it.component }.distinct().size} узлов: " +
                        разрывы.take(2).joinToString("; ") { it.what },
                )
            }

            else -> null
        }
    }
}
