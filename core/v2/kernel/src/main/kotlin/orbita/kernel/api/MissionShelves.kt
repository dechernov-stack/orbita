package orbita.kernel.api

/** Видимые проекту полки. Старые поставки без меток остаются общими.
 * Для каркасов разрешается рекомендация класса, а не версия полки другой миссии.
 * В паспортах встречаются и код класса, и отображаемое имя.
 */
fun EntityStore.projectShelves(project: String, kind: String): List<Entity> {
    val area = Area.Project(project)
    val passport = byCode(area, project)?.takeIf { it.kind == "project" }
        ?: list(area, "project").firstOrNull()
    val selected = passport?.doc?.path("mission_class")?.asText("")?.trim().orEmpty()
    val mission = if (selected.isEmpty()) null else list(Area.Library, "mission_class").firstOrNull {
        it.status != "cancelled" && (it.code.equals(selected, true) || it.doc.path("name").asText("").equals(selected, true))
    }
    val tag = mission?.let { "mission:" + it.code }
    val available = list(Area.Library, kind).filter { row ->
        val scopes = row.doc.path("tags").map { it.asText() }.filter { it.startsWith("mission:") }
        row.status != "cancelled" && (scopes.isEmpty() || tag in scopes)
    }
    if (mission == null) return available
    val names = mission.doc.path("recommended_shelves").map { it.asText() }
    val recommended = available.filter { it.code in names }
    if (recommended.isNotEmpty()) return if (kind == "pbs_template") recommended.take(1) else recommended
    // Класс с явной областью поставки не получает старый каркас, если поставка неполна.
    if (mission.doc.path("tags").any { it.asText() == tag } &&
        kind in setOf("pbs_template", "wbs_template", "model_template", "architecture_template", "interface_template", "constraint")
    ) return available.filter { row -> row.doc.path("tags").any { it.asText() == tag } }
    return available
}
