// Условия ворот, которые знает контур требований (сцена 8).
//
// Правило носителя живёт здесь же, где само требование: «интерфейсное — на
// стык, сценарное — на цепочку, системное — на узел». Оценщик готовности
// спрашивает это правило, а не хранит его копию.
package orbita.requirements.internal

import orbita.kernel.api.Area
import orbita.kernel.api.EntityStore
import orbita.readiness.api.CheckResult
import orbita.readiness.api.ExtraChecks
import orbita.requirements.api.Baselines
import orbita.requirements.api.Level

class RequirementChecks(
    private val store: EntityStore,
    private val baselines: Baselines,
    /** Реестр связей — для деривации (derives_from); null — условие деривации отвечает «родителей нет». */
    private val links: orbita.kernel.api.LinkRegistry? = null,
) : ExtraChecks {

    override fun of(project: String, check: String): CheckResult? {
        val область = Area.Project(project)
        val (имя, аргумент) = check.split(":", limit = 2).let { it[0] to it.getOrNull(1) }
        val требования = store.list(область, "requirement")

        return when (имя) {
            "requirements_min" -> {
                val нужно = (аргумент ?: "1").toIntOrNull() ?: 1
                if (требования.size >= нужно) CheckResult.ok
                else CheckResult.no("требований ${требования.size} из $нужно: черновик уровня проекта пуст")
            }

            "each_goal_has_requirement" -> {
                val покрыты = требования.flatMap { т -> т.doc.path("source").map { it.path("ref").asText() } }.toSet()
                val без = store.list(область, "goal").filter { it.id !in покрыты }
                if (без.isEmpty()) CheckResult.ok
                else CheckResult.no(
                    "целей без требования: ${без.size} — " +
                        без.take(3).joinToString("; ") { it.doc.path("statement").asText(it.code).take(60) },
                )
            }

            // Phase A (шип G): системные требования выведены из проектных и
            // распределены по элементам состава.
            "system_requirements_min" -> {
                val нужно = (аргумент ?: "1").toIntOrNull() ?: 1
                val системные = требования.filter { Level.of(it.doc.path("level").asText(null)) == Level.SYSTEM }
                if (системные.size >= нужно) CheckResult.ok
                else CheckResult.no("системных требований ${системные.size} из $нужно: выведите их из проектных (деривация с основанием)")
            }
            "each_system_requirement_derived" -> {
                val системные = требования.filter { Level.of(it.doc.path("level").asText(null)) == Level.SYSTEM }
                val безРодителя = системные.filter { т -> links == null || links.from(т.id, "derives_from").isEmpty() }
                if (системные.isEmpty()) CheckResult.no("системных требований нет — выводить некого")
                else if (безРодителя.isEmpty()) CheckResult.ok
                else CheckResult.no(
                    "системных без родителя: ${безРодителя.size} — ${безРодителя.take(4).joinToString(", ") { it.code }} " +
                        "(каждое системное несёт проектного родителя связью derives_from)",
                )
            }
            "node_requirements_min" -> {
                val (код, число) = (аргумент ?: "").split(":", limit = 2).let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 1) }
                val узел = store.byCode(область, код)
                    ?: return CheckResult.no("узла «$код» в составе нет")
                val свои = требования.filter { it.doc.path("carrier").asText("") == узел.id }
                if (свои.size >= число) CheckResult.ok
                else CheckResult.no("на узел ${узел.code} распределено ${свои.size} требований из $число — назначьте носителем в реестре")
            }
            "no_carrierless_requirement" -> {
                val без = требования.filter { it.doc.path("carrier").asText("").isBlank() }
                if (без.isEmpty()) CheckResult.ok
                else CheckResult.no(
                    "без носителя: ${без.size} — ${без.take(5).joinToString(", ") { it.code }} " +
                        "(без носителя — не требование)",
                )
            }

            "carrier_nature_ok" -> {
                val чужие = требования.mapNotNull { т ->
                    val уровень = Level.of(т.doc.path("level").asText(null))
                    val носитель = т.doc.path("carrier").asText("").ifBlank { null }
                        ?.let { store.byId(it) } ?: return@mapNotNull null
                    if (носитель.kind in уровень.carrierKinds()) null
                    else "${т.code} → ${носитель.code} (${носитель.kind}, а нужен ${уровень.carrierWords()})"
                }
                if (чужие.isEmpty()) CheckResult.ok
                else CheckResult.no("носитель не той природы: " + чужие.take(3).joinToString("; "))
            }

            "no_suspect_links" -> {
                val подозрения = baselines.suspects(project)
                if (подозрения.isEmpty()) CheckResult.ok
                else CheckResult.no(
                    "подозрительных связей: ${подозрения.size} — " +
                        подозрения.take(2).joinToString("; ") { it.why },
                )
            }

            "baseline_taken" -> {
                val имяСнимка = аргумент
                val снимки = baselines.list(project)
                    .filter { имяСнимка == null || it.name.equals(имяСнимка, ignoreCase = true) }
                if (снимки.isNotEmpty()) CheckResult.ok
                else CheckResult.no("снимок${имяСнимка?.let { " «$it»" } ?: ""} ещё не сделан")
            }

            else -> null
        }
    }
}
