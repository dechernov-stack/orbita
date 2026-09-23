// Условия про точки фазы, которые знает только слой над контурами: шаблон
// фазы (позиции экспертиз, матрица зрелости), записи точек, план фазы.
// Как и остальные условия — только читают (правило 1, 08.09).
package orbita.api.internal

import com.fasterxml.jackson.databind.JsonNode
import orbita.documents.api.Documents
import orbita.kernel.api.Area
import orbita.kernel.api.EntityStore
import orbita.readiness.api.CheckResult
import orbita.readiness.api.ExtraChecks

class PointChecks(
    private val store: EntityStore,
    private val records: GateRecords,
    private val documents: Documents?,
    /**
     * Роли проекта (учётка → роль) — то, что истина зовёт `account_role`.
     * Условие A1 «ответственные сцен назначены» читает окна плана, а роли —
     * умолчание для незаполненных окон по роли сцены (истина 23.09).
     */
    private val roles: (project: String) -> Map<String, String> = { emptyMap() },
    /** Шаблон фазы проекта — откуда берутся позиции экспертиз и матрица зрелости точки. */
    private val template: (project: String) -> JsonNode?,
) : ExtraChecks {

    override fun of(project: String, check: String): CheckResult? {
        val область = Area.Project(project)
        val (имя, аргумент) = check.split(":", limit = 2).let { it[0] to it.getOrNull(1) }
        return when (имя) {
            // A1: даты точек ТЕКУЩЕЙ фазы заданы (SRR · SDR · KDP-B), а не всех подряд.
            "phase_points_dated" -> {
                val фаза = records.phaseOf(project)
                val точки = store.list(область, "gate").filter {
                    it.doc.path("kind").asText("phase") != "technology" && (фаза == null || it.doc.path("phase").asText("") == фаза)
                }
                val без = точки.filter { it.doc.path("planned_date").asText("").isBlank() }
                when {
                    точки.isEmpty() -> CheckResult.no("точек фазы${фаза?.let { " «$it»" } ?: ""} в проекте нет")
                    без.isEmpty() -> CheckResult.ok
                    else -> CheckResult.no("даты не заданы у точек: " + без.joinToString(", ") { it.code } + " — план фазы задаёт даты точек")
                }
            }
            // A1: ответственные сцен — в окнах плана работ фазы (истина 23.09:
            // `plan.scene_windows[].responsible`); незаполненное — умолчание из
            // ролей проекта по роли сцены в шаблоне (РП / ведущий СИ).
            "scene_responsible_min" -> {
                val нужно = (аргумент ?: "1").toIntOrNull() ?: 1
                val окна = store.list(область, "plan").lastOrNull()?.doc?.path("scene_windows")
                    ?.associate { it.path("scene").asText() to it.path("responsible").asText("").trim() }
                    .orEmpty()
                val роли = roles(project)
                val умолчание = { роль: String -> роль in ОТВЕТСТВЕННЫЕ_РОЛИ && роли.containsValue(роль) }
                val сцены = template(project)?.path("scenes")
                    ?.map { it.path("key").asText() to it.path("role").asText("") }
                    ?.filter { (ключ, _) -> ключ.isNotBlank() }
                    .orEmpty()
                val назначено = if (сцены.isEmpty()) окна.values.count { it.isNotBlank() }
                else сцены.count { (ключ, роль) -> окна[ключ].orEmpty().isNotBlank() || умолчание(роль) }
                val всего = if (сцены.isEmpty()) окна.size else сцены.size
                if (назначено >= нужно) CheckResult.ok
                else CheckResult.no(
                    "ответственных сцен назначено $назначено из $всего (нужно $нужно) — назначьте в окне сцены " +
                        "плана работ фазы (сцена A1, сцена 1, паспорт) либо дайте роль РП или ведущего СИ в паспорте: " +
                        "она станет умолчанием для сцен своей роли",
                )
            }
            // A12: по каждой позиции экспертизы точки записан вердикт.
            "expertise_positions_reviewed" -> {
                val ключ = аргумент ?: return CheckResult.no("условию нужен ключ точки")
                val точка = точкаШаблона(project, ключ) ?: return CheckResult.no("точки «$ключ» в шаблоне фазы нет")
                val позиции = точка.path("expertise").path("control_items").map { it.path("artifact").asText("") }.filter { it.isNotBlank() }
                if (позиции.isEmpty()) return CheckResult.no("у точки «$ключ» нет позиций экспертизы: полка не транскрибирована")
                val вердикты = records.positions(project, ключ)
                val без = позиции.filter { вердикты.path(it).path("verdict").asText("").isBlank() }
                if (без.isEmpty()) CheckResult.ok
                else CheckResult.no("позиций ${точка.path("expertise").path("source").asText(ключ)} без вердикта: ${без.size} из ${позиции.size} — " + без.take(3).joinToString("; "))
            }
            // A12: матрица зрелости точки без дыр — каждая строка сведена к нашему артефакту и зрелость достигнута.
            "maturity_matrix_complete" -> {
                val ключ = аргумент ?: return CheckResult.no("условию нужен ключ точки")
                val точка = точкаШаблона(project, ключ) ?: return CheckResult.no("точки «$ключ» в шаблоне фазы нет")
                val строки = точка.path("maturity_matrix")
                if (!строки.isArray || строки.isEmpty) return CheckResult.no("у точки «$ключ» нет матрицы зрелости")
                val дыры = строки.mapNotNull { строка ->
                    val код = строка.path("codes").path(ключ).asText("").trim()
                    if (код.isBlank() || код == "°") return@mapNotNull null
                    val артефакт = строка.path("artifact").asText("")
                    val ссылка = строка.path("our_ref").asText("").ifBlank { return@mapNotNull "$артефакт — не сведена к нашему артефакту" }
                    val (вид, значение) = ссылка.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                    when (вид) {
                        "document_template" -> {
                            val документ = documents?.list(project)?.firstOrNull { it.template == значение }
                                ?: return@mapNotNull "$артефакт — документа «$значение» нет"
                            when (код) {
                                "F" -> if (documents.baselines(project, документ.code).isEmpty()) "$артефакт — не базирован (F)" else null
                                else -> {
                                    val начат = документ.complete > 0 || documents.document(project, документ.code, ключ).sections
                                        .any { р -> р.elements.any { it.rows.isNotEmpty() || !it.text.isNullOrBlank() } }
                                    if (!начат) "$артефакт — пуст ($код)" else null
                                }
                            }
                        }
                        "kind" -> if (store.list(область, значение).none { it.status != "cancelled" }) "$артефакт — нет ни одной записи вида $значение" else null
                        else -> "$артефакт — ссылка «$ссылка» неизвестного вида"
                    }
                }
                if (дыры.isEmpty()) CheckResult.ok
                else CheckResult.no("матрица зрелости к $ключ с дырами: " + дыры.take(4).joinToString("; "))
            }
            else -> null
        }
    }

    private companion object {
        /** РП и ведущий СИ — те, кто ведёт сцены (коды ролей учёток стенда). */
        val ОТВЕТСТВЕННЫЕ_РОЛИ = setOf("lead", "lead_se")
    }

    private fun точкаШаблона(project: String, ключ: String): JsonNode? =
        template(project)?.path("points")?.firstOrNull { it.path("key").asText() == ключ }
}
