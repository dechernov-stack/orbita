// Ф-09: библиотека участвует в порождении НОВОГО знания, а не только хранит
// старое. Владелец: «документы полок плохо разобраны, не в фактологии, не
// порождают ограничений и требований» — Ф-05 дал промпту имена позиций, но не
// знание из них.
//
// Здесь: вход операции «норматив → кандидаты» (пункты НПА полки и блоки их
// канонов), ворота нормативной схемой и укладка принятого. Требование ложится
// объектом с основанием-трассой на норматив; ограничение — Р-кодом в паспорт,
// тем же законом, что урожай Д2. Значения не выдумываются: без основания
// кандидат не принимается вовсе.
package orbita.com.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.store.ObjectStore
import orbita.mod.store.StoredObject

object NormativeCandidates {

    const val KIND = "normative_to_candidates"

    private val mapper = ObjectMapper()

    /**
     * Есть ли из чего порождать: нормативу нужны либо свои пункты, либо
     * разобранный документ. Норматив, знающий только собственное имя, в
     * операцию не идёт — и об этом говорится прямо, а не молчанием.
     */
    fun readiness(boundary: Boundary, filesDir: String, projectId: String): ObjectNode {
        val lib = boundary.objects.listCurrent(ObjectStore.LIBRARY_PROJECT)
            .filter { it.status.name != "Cancelled" }
        val classRef = boundary.objects.current(projectId)?.doc?.path("mission_class")?.asText("") ?: ""
        val norms = lib.filter { it.type == "normative_document" }.filter { forClass(it, classRef) }
        val speaking = norms.filter { hasKnowledge(it, lib, filesDir) }
        val out = mapper.createObjectNode()
        out.put("normatives", norms.size)
        out.put("speaking", speaking.size)
        out.put("can_compose", speaking.isNotEmpty())
        out.put(
            "why",
            when {
                norms.isEmpty() -> "нормативов класса на полке нет — кандидатов порождать не из чего"
                speaking.isEmpty() ->
                    "нормативы полки знают только свои наименования: впишите пункты в карточку " +
                        "либо приложите документ — он разберётся и отдаст блоки"
                else -> "нормативов со знанием: ${speaking.size} из ${norms.size} — кандидаты собираются по их пунктам и блокам"
            },
        )
        val list = out.putArray("sources")
        norms.sortedBy { it.id }.forEach { nr ->
            val doc = shelfDocumentOf(nr, lib)
            list.addObject()
                .put("id", nr.id)
                .put("name", nr.doc.path("name").asText(nr.id))
                .put("clauses", nr.doc.path("clauses").size())
                .put("document", doc?.id)
                .put("parsed", doc != null && DocumentParseStore.canonOf(filesDir, doc.id) != null)
                .put("speaks", hasKnowledge(nr, lib, filesDir))
        }
        // Ф-09 п.1: карточка полки говорит, ЧТО система из документа знает —
        // разобран ли он, сколько блоков идёт в промпт. «Лежит файл» — не знание.
        val docs = out.putArray("documents")
        lib.filter { it.type == "source_document" }.sortedBy { it.id }.forEach { sd ->
            val parsed = DocumentParseStore.canonOf(filesDir, sd.id) != null
            val harvested = DocumentHarvest.of(filesDir, sd.id) != null
            docs.addObject()
                .put("id", sd.id)
                .put("name", sd.doc.path("name").asText(sd.id))
                .put("kind", sd.doc.path("kind").asText(""))
                .put("parsed", parsed)
                .put("harvested", harvested)
                .put("in_prompt", sd.doc.path("prompt").path("included").asBoolean(false))
                .put("blocks", sd.doc.path("prompt").path("blocks").size())
        }
        return out
    }

    /**
     * Вход операции: нормативы своими пунктами и блоками канона, плюс то,
     * что в проекте уже есть, — чтобы кандидаты не дублировали принятое.
     */
    fun statementOf(boundary: Boundary, filesDir: String, projectId: String): String = buildString {
        val lib = boundary.objects.listCurrent(ObjectStore.LIBRARY_PROJECT)
            .filter { it.status.name != "Cancelled" }
        val classRef = boundary.objects.current(projectId)?.doc?.path("mission_class")?.asText("") ?: ""
        val norms = lib.filter { it.type == "normative_document" }
            .filter { forClass(it, classRef) }
            .sortedBy { it.id }
        appendLine("НОРМАТИВЫ ПОЛКИ (кандидаты порождаются ТОЛЬКО из приведённого ниже):")
        norms.forEach { nr ->
            val designation = listOf("number", "name")
                .firstNotNullOfOrNull { nr.doc.path(it).asText("").ifBlank { null } } ?: nr.id
            val edition = nr.doc.path("edition").asText("").ifBlank { nr.doc.path("edition_date").asText("") }
            appendLine()
            appendLine("— ${nr.id} «$designation»" + (if (edition.isNotBlank()) ", редакция $edition" else ""))
            nr.doc.path("summary").asText("").takeIf { it.isNotBlank() }?.let { appendLine("  о чём: $it") }
            nr.doc.path("clauses").forEach { c ->
                appendLine("  ${c.path("clause").asText("")}: ${c.path("text").asText("")}")
            }
            val sd = shelfDocumentOf(nr, lib)
            val canon = sd?.let { DocumentParseStore.canonOf(filesDir, it.id) }
            if (canon != null) {
                appendLine("  КАНОН ${sd.id} (блоки с якорями):")
                canon.lineSequence().forEach { appendLine("  $it") }
            }
        }
        val own = boundary.objects.listCurrent(projectId).filter { it.status.name != "Cancelled" }
        val requirements = own.filter { it.type == "requirement" }
        appendLine()
        appendLine("УЖЕ В ПРОЕКТЕ (не дублировать; опираться):")
        if (requirements.isEmpty()) appendLine("— требований нет")
        requirements.sortedBy { it.id }.take(60).forEach {
            appendLine("— ${it.id}: ${it.doc.path("statement").asText("").take(160)}")
        }
        val passport = boundary.objects.current(projectId)?.doc
        val constraints = passport?.path("constraints")?.filterNot { it.path("removed").asBoolean(false) }.orEmpty()
        appendLine()
        appendLine("ОГРАНИЧЕНИЯ ПАСПОРТА (действующие; повторять не нужно):")
        if (constraints.isEmpty()) appendLine("— ограничений нет")
        constraints.forEach { appendLine("— ${it.path("code").asText("")}: ${it.path("text").asText("")}") }
    }

    /** Ворота: чужая форма внутрь не идёт — та же нормативная схема, что у всех. */
    fun problems(boundary: Boundary, packet: JsonNode): List<String> =
        boundary.schemaProblems("core/normative-candidates", packet).map { "${it.path}: ${it.message}" }

    /**
     * Кандидат требования → объект требования. Основание ложится трассой на
     * норматив (traces_up допускает NR) и словами в rationale: пункт и якорь
     * видны инженеру, проверить их можно, не выходя из системы.
     */
    fun requirementOf(item: JsonNode, owner: String = "инженер", level: String = "system"): ObjectNode {
        val doc = mapper.createObjectNode()
        doc.put("level", level)
        doc.put("statement", item.path("statement").asText(""))
        // категория: норма-запрет и норма-требование различаются по существу,
        // и это видно в реестре требований без чтения основания
        doc.put("category", if (item.path("class").asText() == "constraint") "constraint" else "functional")
        doc.put("owner", owner)
        // метод верификации на ранней фазе не выдумывается: событие
        // появится решением инженера (ADR-031, TBD живёт до своей точки)
        doc.putArray("verification_events")
        val basis = item.path("basis")
        val nr = basis.path("normative_ref").asText("")
        val clause = basis.path("clause").asText("")
        val anchors = basis.path("anchors").mapNotNull { it.asText().takeIf(String::isNotBlank) }
        doc.put(
            "rationale",
            buildString {
                append("основание: $nr")
                if (clause.isNotBlank()) append(", $clause")
                if (anchors.isNotEmpty()) append(" [${anchors.joinToString(", ")}]")
                basis.path("quote").asText("").takeIf { it.isNotBlank() }?.let { append(" — $it") }
            },
        )
        val traces = doc.putArray("traces_up")
        if (nr.isNotBlank()) traces.addObject().put("ref", nr)
        val provenance = doc.putObject("provenance")
        provenance.put("source", "ai_proposed")
        provenance.put("author", "служба: $KIND")
        return doc
    }

    /**
     * Кандидат ограничения → строка паспорта. Код — следующий свободный в
     * серии Р (Ф-02: один префикс, коды стабильны, дыры законны); источник
     * называет норматив и пункт, чтобы решение было проверяемым.
     */
    fun constraintOf(item: JsonNode, existing: JsonNode): ObjectNode {
        var top = 0
        existing.forEach { c ->
            Regex("^Р(\\d+)$").find(c.path("code").asText(""))?.let { m ->
                val n = m.groupValues[1].toInt()
                if (n > top) top = n
            }
        }
        val basis = item.path("basis")
        val clause = basis.path("clause").asText("")
        val anchors = basis.path("anchors").mapNotNull { it.asText().takeIf(String::isNotBlank) }
        val out = mapper.createObjectNode()
            .put("code", "Р${top + 1}")
            .put("text", item.path("statement").asText(""))
            .put(
                "source",
                buildString {
                    append(basis.path("normative_ref").asText(""))
                    if (clause.isNotBlank()) append(", $clause")
                    if (anchors.isNotEmpty()) append(" [${anchors.joinToString(", ")}]")
                },
            )
        item.path("category").asText("").takeIf { it.isNotBlank() }?.let { out.put("category", it) }
        return out
    }

    /** Норматив относится к классу миссии — или он общий для всех классов. */
    private fun forClass(o: StoredObject, classRef: String): Boolean {
        val ref = o.doc.path("mission_class_ref").asText("")
        return ref.isBlank() || classRef.isBlank() || ref == classRef
    }

    /**
     * Документ полки, приложенный к нормативу: связь — по ссылке карточки
     * (file_ref/document_ref) либо по совпадению обозначения в наименовании.
     */
    fun shelfDocumentOf(nr: StoredObject, lib: List<StoredObject>): StoredObject? {
        val direct = listOf("document_ref", "file_ref")
            .firstNotNullOfOrNull { nr.doc.path(it).asText("").ifBlank { null } }
        if (direct != null) return lib.firstOrNull { it.type == "source_document" && it.id == direct }
        val number = nr.doc.path("number").asText("").trim()
        if (number.isBlank()) return null
        return lib.firstOrNull {
            it.type == "source_document" && it.doc.path("name").asText("").contains(number, ignoreCase = true)
        }
    }

    /** Норматив «говорит», если у него есть пункты либо разобранный документ. */
    private fun hasKnowledge(nr: StoredObject, lib: List<StoredObject>, filesDir: String): Boolean {
        if (!nr.doc.path("clauses").isEmpty) return true
        val sd = shelfDocumentOf(nr, lib) ?: return false
        return DocumentParseStore.canonOf(filesDir, sd.id) != null
    }
}
