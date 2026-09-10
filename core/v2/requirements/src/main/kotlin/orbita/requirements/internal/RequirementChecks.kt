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
                // Условие A5 (10.09): родитель — с обоснованием; связь без «почему» родителем не считается.
                val безОснования = системные.filter { т ->
                    links != null && links.from(т.id, "derives_from").let { с -> с.isNotEmpty() && с.all { it.rationale.isNullOrBlank() } }
                }
                if (системные.isEmpty()) CheckResult.no("системных требований нет — выводить некого")
                else if (безРодителя.isEmpty() && безОснования.isEmpty()) CheckResult.ok
                else CheckResult.no(
                    buildString {
                        if (безРодителя.isNotEmpty()) {
                            append("системных без родителя: ${безРодителя.size} — ${безРодителя.take(4).joinToString(", ") { it.code }}")
                        }
                        if (безОснования.isNotEmpty()) {
                            if (isNotEmpty()) append("; ")
                            append("без обоснования деривации: ${безОснования.take(4).joinToString(", ") { it.code }}")
                        }
                        append(" (каждое системное несёт проектного родителя связью derives_from с обоснованием)")
                    },
                )
            }
            // Условие A5 (10.09): «≥ 1 системное на каждый сегмент» — носитель
            // на самом сегменте либо на его элементе.
            "each_segment_has_requirement" -> {
                val узлы = store.list(область, "component").filter { it.status != "cancelled" }
                val сегменты = узлы.filter { it.doc.path("kind").asText("") == "segment" }
                if (сегменты.isEmpty()) return CheckResult.no("сегментов в составе нет: на что распределять системные требования?")
                fun родитель(у: orbita.kernel.api.Entity): orbita.kernel.api.Entity? =
                    у.doc.path("parent").asText("").ifBlank { null }?.let { р -> узлы.firstOrNull { it.id == р || it.code == р } }
                fun сегментУзла(у: orbita.kernel.api.Entity): orbita.kernel.api.Entity? {
                    var текущий: orbita.kernel.api.Entity? = у
                    var шагов = 0
                    while (текущий != null && шагов < 12) {
                        if (текущий.doc.path("kind").asText("") == "segment") return текущий
                        текущий = родитель(текущий); шагов += 1
                    }
                    return null
                }
                val покрытые = требования
                    .filter { Level.of(it.doc.path("level").asText(null)) == Level.SYSTEM }
                    .mapNotNull { т -> т.doc.path("carrier").asText("").ifBlank { null } }
                    .mapNotNull { н -> узлы.firstOrNull { it.id == н || it.code == н } }
                    .mapNotNull { сегментУзла(it) }.map { it.id }.toSet()
                val без = сегменты.filter { it.id !in покрытые }.sortedBy { it.code }
                if (без.isEmpty()) CheckResult.ok
                else CheckResult.no("сегментов без системного требования: " + без.joinToString(", ") { it.code } + " — на каждый сегмент хотя бы одно")
            }
            // Условие A7 (10.09): условные элементы перечислены — снимком базовой
            // линии, где всё, что стоит на незрелой технологии, помечено условным.
            "conditional_requirements_listed" -> {
                val незрелые = store.list(область, "technology")
                    .filter { it.doc.path("trl_current").asInt(9) < it.doc.path("trl_required").asInt(0) }
                    .map { it.doc.path("component").asText("") }.filter { it.isNotBlank() }
                val узлы = store.list(область, "component")
                val незрелыеУзлы = незрелые.flatMap { к -> узлы.filter { it.id == к || it.code == к }.map { it.id } + к }.toSet()
                val условные = требования.filter { it.doc.path("carrier").asText("") in незрелыеУзлы }
                if (условные.isEmpty()) return CheckResult.ok
                val снимок = baselines.list(project).lastOrNull()
                    ?: return CheckResult.no(
                        "условных элементов ${условные.size} (${условные.take(3).joinToString(", ") { it.code }}), а снимка нет — " +
                            "возьмите базовую линию: она перечислит их условными с технологией",
                    )
                // Снимок перечисляет требования СВОИХ уровней (функциональная линия —
                // проектные и системные); уровни ниже ждут распределённой линии к SDR.
                val уровни = снимок.kind.levels()
                val перечислены = снимок.items.filter { it.firmness == orbita.requirements.api.Firmness.CONDITIONAL }.map { it.ref }.toSet()
                val мимо = условные.filter { Level.of(it.doc.path("level").asText(null)) in уровни && it.id !in перечислены }
                if (мимо.isEmpty()) CheckResult.ok
                else CheckResult.no(
                    "в снимке «${снимок.name}» не перечислены условными: " + мимо.take(4).joinToString(", ") { it.code } +
                        " — снимок старше технологии, возьмите новый",
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
                // Аргумент — уровень (system · project · …): «без носителя (системные)» = 0.
                val уровень = аргумент?.let { Level.of(it) }
                val без = требования
                    .filter { уровень == null || Level.of(it.doc.path("level").asText(null)) == уровень }
                    .filter { it.doc.path("carrier").asText("").isBlank() }
                if (без.isEmpty()) CheckResult.ok
                else CheckResult.no(
                    "без носителя${уровень?.let { " (${it.name.lowercase()})" } ?: ""}: ${без.size} — ${без.take(5).joinToString(", ") { it.code }} " +
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
                // Аргумент — имя снимка либо его вид (functional · allocated · product).
                val имяСнимка = аргумент
                val снимки = baselines.list(project).filter {
                    имяСнимка == null || it.name.equals(имяСнимка, ignoreCase = true) ||
                        it.kind.name.equals(имяСнимка, ignoreCase = true)
                }
                if (снимки.isNotEmpty()) CheckResult.ok
                else CheckResult.no("снимок${имяСнимка?.let { " «$it»" } ?: ""} ещё не сделан — базовая линия берётся в реестре требований")
            }

            else -> null
        }
    }
}
