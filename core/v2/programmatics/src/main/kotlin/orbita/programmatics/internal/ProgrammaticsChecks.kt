// Условия ворот сцен 10–12: технологии, риски, стоимость.
package orbita.programmatics.internal

import orbita.kernel.api.Area
import orbita.kernel.api.EntityStore
import orbita.programmatics.api.Programmatics
import orbita.readiness.api.CheckResult
import orbita.readiness.api.ExtraChecks

class ProgrammaticsChecks(
    private val store: EntityStore,
    private val programmatics: Programmatics,
) : ExtraChecks {

    override fun of(project: String, check: String): CheckResult? {
        val область = Area.Project(project)
        val (имя, аргумент) = check.split(":", limit = 2).let { it[0] to it.getOrNull(1) }

        return when (имя) {
            "technologies_named" -> {
                val нужно = (аргумент ?: "1").toIntOrNull() ?: 1
                val есть = store.list(область, "technology").size
                if (есть >= нужно) CheckResult.ok
                else CheckResult.no(
                    "критических технологий названо $есть из $нужно: " +
                        "к MCR список критических технологий с TRL обязателен",
                )
            }

            "maturation_planned" -> {
                val открытые = programmatics.maturation(project, "система")
                    .filter { it.trlCurrent < it.trlRequired }
                val без = открытые.filter { it.packageCode == null || it.milestoneGate == null }
                if (без.isEmpty()) CheckResult.ok
                else CheckResult.no(
                    "без плана созревания: " + без.joinToString(", ") { it.technology } +
                        " — разрыв TRL требует пакета работ и вехи",
                )
            }

            "risks_min" -> {
                val нужно = (аргумент ?: "3").toIntOrNull() ?: 3
                val есть = store.list(область, "risk").size
                if (есть >= нужно) CheckResult.ok
                else CheckResult.no("рисков $есть из $нужно: пустой реестр рисков означает, что их не искали")
            }

            "each_risk_has_due_point" -> {
                val без = store.list(область, "risk")
                    .filter { it.doc.path("due_point").asText("").isBlank() }
                if (без.isEmpty()) CheckResult.ok
                else CheckResult.no(
                    "без срока-точки: " + без.take(3).joinToString(", ") { it.code } +
                        " — срок без точки не наступает",
                )
            }

            "oda_started" -> {
                val оценка = store.list(область, "debris_assessment").lastOrNull()
                if (оценка != null) CheckResult.ok
                else CheckResult.no("оценки засорения нет: ODA к MCR считается от базового варианта")
            }

            "wbs_paired" -> {
                val пакеты = programmatics.packages(project)
                val без = пакеты.filter { !it.crossCutting && it.pbsRefs.isEmpty() }
                when {
                    пакеты.isEmpty() -> CheckResult.no("WBS не взят: пакетов работ в проекте нет")
                    без.isNotEmpty() -> CheckResult.no(
                        "пакетов без пары к узлу: ${без.size} — " + без.take(3).joinToString(", ") { it.code },
                    )
                    else -> CheckResult.ok
                }
            }

            "estimate_ranged" -> {
                val оценки = store.list(область, "cost_estimate")
                val плохие = оценки.filter {
                    it.doc.path("range").path("max").asDouble() <= it.doc.path("range").path("min").asDouble() ||
                        it.doc.path("assumptions").asText("").isBlank()
                }
                when {
                    оценки.isEmpty() -> CheckResult.no("оценки нет: к KDP нужен ROM диапазоном с допущениями")
                    плохие.isNotEmpty() -> CheckResult.no(
                        "оценка без диапазона или допущений: " + плохие.take(3).joinToString(", ") { it.code },
                    )
                    else -> CheckResult.ok
                }
            }

            "estimate_includes_maturation" -> {
                val разрывы = programmatics.maturation(project, "система")
                    .filter { it.trlCurrent < it.trlRequired }
                if (разрывы.isEmpty()) return CheckResult.ok
                val сОценкой = programmatics.packages(project)
                    .filter { it.estimate != null }.map { it.code }.toSet()
                val без = разрывы.filter { it.packageCode !in сОценкой }
                if (без.isEmpty()) CheckResult.ok
                else CheckResult.no(
                    "стоимость не включает созревание: " + без.joinToString(", ") { it.technology } +
                        " — оценка без пакетов созревания при открытых TRL считает не тот объём работ",
                )
            }

            else -> null
        }
    }
}
