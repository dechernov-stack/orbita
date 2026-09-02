// Модель для документов — ОДНОЙ сборкой на все маршруты (документ, печать,
// выпуск, текст раздела, вход связного текста): прежде восемь маршрутов держали
// по копии одного и того же блока, и SEMP ред. 2 пришлось бы дописывать в
// каждую. Сюда же входит то, чего в срезе объектов нет по построению —
// бюджеты (считаются, не хранятся), роли проекта (учётки), группы готовности
// точки (вычисляются) и выпуски документов историей.
package orbita.com.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.model.Lifecycle

object DocumentModel {

    private val mapper = ObjectMapper()

    /** Обозначение в начале строки печати: «‹ВИД›-NNNN. » либо «‹ВИД›-NNNN — ». */
    private val DESIGNATION = Regex("^[A-Z]{2,3}-[0-9]{4}(\\.\\s+|\\s+—\\s+)")

    fun model(boundary: Boundary, project: String?): JsonNode {
        val budgets = boundary.objects.listCurrent(project).firstOrNull { it.type == "spacecraft" }?.let {
            runCatching {
                orbita.out.ModelSnapshot.budgetsOf(
                    boundary.spacecraft.build(it.doc, orbita.out.SpacecraftConditions()), mapper,
                )
            }.getOrDefault(emptyList())
        } ?: emptyList()
        val m = orbita.out.ModelSnapshot.of(boundary.objects, mapper, budgets = budgets, projectId = project) as ObjectNode
        // МВП-М2 §3.5: последняя матрица сравнения построений — вставкой в
        // раздел AoA; выпуск зафиксирует снимок
        boundary.objects.listCurrent(project)
            .filter { it.type == "scenario" }
            .flatMap { sc -> boundary.results.activeForScenario(sc.id, "constellation_compare") }
            .maxByOrNull { it.pk }
            ?.let { m.set<ObjectNode>("constellation_compare", it.payload.deepCopy()) }
        if (project != null) {
            // класс миссии в паспорте — идентификатор объекта полки; в документ
            // идёт его имя, а не «MC-0001» (печать — человеческим текстом)
            val cls = m.path("project").path("mission_class").asText("")
            if (cls.isNotBlank()) {
                boundary.objects.current(cls)?.doc?.path("name")?.asText("")?.takeIf { it.isNotBlank() }
                    ?.let { (m.path("project") as ObjectNode).put("mission_class_name", it) }
            }
            // роли проекта — организация (SEMP §5); имена — как показывают экраны
            val roles = m.putArray("roles")
            runCatching { boundary.auth.listRoles(project) }.getOrDefault(emptyMap())
                .toSortedMap().forEach { (login, role) ->
                    roles.addObject().put("login", login).put("name", boundary.humanAuthor(login)).put("role", role)
                }
            // группы готовности ближайшей точки — обзоры (SEMP §7)
            val groups = m.putArray("readiness_groups")
            runCatching {
                val gate = boundary.gatePassing.nextGate(project)
                if (gate != null) {
                    boundary.gatePassing.readiness(gate, project).groupBy { it.group }.forEach { (key, checks) ->
                        groups.addObject()
                            .put("gate", gate)
                            .put("title", groupTitle(key))
                            .put("open", checks.count { it.state == "open" })
                            .put("closed", checks.count { it.state == "closed" })
                    }
                }
            }
            // выпуски документов — согласование историей (SEMP §11)
            val issues = m.putArray("document_issues")
            boundary.objects.listCurrent(project)
                .filter { it.type == "document_issue" && it.status != Lifecycle.Cancelled }
                .sortedBy { it.id }
                .forEach { di ->
                    issues.addObject()
                        .put("id", di.id)
                        .put("template", di.doc.path("template").asText(""))
                        .put("version", di.version)
                        .put("issued_at", di.doc.path("issued_at").asText(""))
                        .put("status", di.doc.path("status").asText(""))
                        .put("author", boundary.humanAuthor(di.createdBy))
                }
        }
        return m
    }

    private fun groupTitle(key: String): String = when (key) {
        "blocking" -> "блокирует фиксацию"
        "statement" -> "постановка и требования"
        "ai" -> "служба ИИ"
        "risks" -> "риски"
        else -> key
    }

    /** Авторские тексты разделов документа со снимком строк вставок. */
    fun sectionTexts(boundary: Boundary, code: String, project: String?): Map<Int, orbita.out.SectionAuthorText> =
        boundary.objects.listCurrent(project)
            .filter {
                it.type == "section_text" && it.status != Lifecycle.Cancelled &&
                    it.doc.path("template_code").asText() == code
            }
            .associate {
                it.doc.path("section").asInt() to orbita.out.SectionAuthorText(
                    text = it.doc.path("text").asText(""),
                    insertsFingerprint = it.doc.path("inserts_fingerprint").asText(""),
                    insertsLines = it.doc.path("inserts_lines").map { l -> l.asText() },
                )
            }

    /**
     * Вход для связного текста раздела (шип 1 «трёх пакетов»): подсказка
     * раздела и данные его вставок ЧЕЛОВЕЧЕСКИМИ строками — теми же, что идут
     * в печать. Ничего сверх данных раздела во вход не попадает.
     */
    fun proseInput(boundary: Boundary, project: String, template: orbita.out.TemplateData, section: Int): ObjectNode {
        val rendered = orbita.out.DocumentGenerator(mapper)
            .render(model(boundary, project), template, sectionTexts(boundary, template.code, project))
        val s = rendered.body.path("sections").firstOrNull { it.path("number").asInt() == section }
            ?: throw IllegalArgumentException("раздела $section нет в шаблоне '${template.code}'")
        // во вход прозы строки идут БЕЗ обозначений объектов: текст не должен
        // их пересказывать (правило вида), а модели они только мешают
        val lines = s.path("items").map { orbita.out.PrintHumanizer.line(it).replace(DESIGNATION, "") }
        val out = mapper.createObjectNode()
        out.put("template_code", template.code)
        out.put("section", section)
        out.put("title", s.path("title").asText(""))
        out.put("mode", s.path("mode").asText("table"))
        out.put("expects", s.path("expects").asText(""))
        out.put("inserts_fingerprint", s.path("inserts_fingerprint").asText(""))
        val arr = out.putArray("lines")
        lines.forEach { arr.add(it) }
        out.put(
            "statement",
            buildString {
                appendLine("Документ «${template.title}», раздел ${section} «${s.path("title").asText("")}».")
                appendLine("Регламент ожидает видеть: ${s.path("expects").asText("")}")
                appendLine("template_code: ${template.code}; section: $section")
                appendLine()
                appendLine("ДАННЫЕ ВСТАВОК РАЗДЕЛА (${lines.size}):")
                if (lines.isEmpty()) appendLine("— данных нет: текст обязан честно сказать, что раздел пока не наполнен")
                lines.forEach { appendLine("— $it") }
                s.path("text").asText("").takeIf { it.isNotBlank() }?.let {
                    appendLine()
                    appendLine("ПРИНЯТЫЙ РАНЕЕ ТЕКСТ (для правки, не для повторения):")
                    appendLine(it)
                }
            }.trimEnd(),
        )
        return out
    }
}
