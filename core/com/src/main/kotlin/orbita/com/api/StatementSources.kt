// Ф-05: промпт постановки — ИЗ ДАННЫХ, не из общих слов. Раньше операция О2
// уходила в модель с одним «входом операции»: без записки генерация была
// холостой, и предупреждение это лишь признавало.
//
// Здесь собираются источники промпта, каждый — со счётчиком и содержимым:
//   · замысел миссии — обязательный (без него генерация заблокирована);
//   · библиотека класса миссии — типовые сервисы, профили стейкхолдеров (А2),
//     типовые риски (Б3), применённые нормативы (Б1), термины глоссария;
//   · знание полки (Ф-09) — пункты нормативов с реквизитами и блоки канонов
//     библиотечных документов: не перечень позиций, а то, что в них написано;
//   · взятые наборы Ш2 — рамкой «опираться, не дублировать»;
//   · принятый урожай разбора (Д2) — контекстом «уже принято»;
//   · запреты проекта — ограничениями паспорта.
//
// Пустой источник не исчезает: он приходит строкой «— пусто». Общие слова
// вместо данных становятся невозможны по построению — видно, чего нет.
package orbita.com.api

import com.fasterxml.jackson.databind.JsonNode
import orbita.mod.store.ObjectStore
import orbita.mod.store.StoredObject

/** Источник промпта: что вошло, сколько позиций, чем именно. */
data class StatementSource(
    val key: String,
    val title: String,
    val count: Int,
    val lines: List<String>,
    val note: String? = null,
) {
    val empty: Boolean get() = count == 0
}

class StatementSources(private val boundary: Boundary) {

    /** Замысел миссии задан, если есть связный абзац либо все четыре поля. */
    fun intentOf(project: JsonNode): String? {
        val intent = project.path("mission_intent")
        if (!intent.isObject) return null
        val text = intent.path("text").asText("").trim()
        if (text.isNotBlank()) return text
        val fields = listOf("for_whom", "what", "where", "horizon")
            .map { intent.path(it).asText("").trim() }
        if (fields.any { it.isBlank() }) return null
        return "Для кого: ${fields[0]}. Что делает: ${fields[1]}. " +
            "Где: ${fields[2]}. Горизонт: ${fields[3]}."
    }

    /** Причина отказа для видов, которым замысел обязателен; null — можно. */
    fun refusalFor(kind: String, projectId: String): String? {
        val requires = orbita.ai.PackageKinds.default().of(kind).requiresMissionIntent
        if (!requires) return null
        val project = boundary.objects.current(projectId)?.doc ?: return null
        if (intentOf(project) != null) return null
        // Ф-11: замысел спрашивается ПОСЛЕ материалов — отказ обязан звать
        // туда, где замысел действительно заполняется, а не в «начало»
        return "нет замысла миссии — генерация постановки даст общие места. " +
            "Заполните «для кого · что делает · где · горизонт» на шаге 3 " +
            "мастер-пути («Замысел миссии») — рукой либо сборкой из документов, " +
            "или задайте одним связным абзацем"
    }

    /**
     * Источники промпта операции. Порядок постоянен — это и порядок
     * предпросмотра: замысел, библиотека класса, взятое, принятое, запреты.
     */
    fun of(kind: String, projectId: String): List<StatementSource> {
        val keys = orbita.ai.PackageKinds.default().of(kind).statementSources
        if (keys.isEmpty()) return emptyList()
        val project = boundary.objects.current(projectId)?.doc
            ?: return emptyList()
        val own = boundary.objects.listCurrent(projectId).filter { it.status.name != "Cancelled" }
        val lib = boundary.objects.listCurrent(ObjectStore.LIBRARY_PROJECT)
            .filter { it.status.name != "Cancelled" }
        return keys.mapNotNull { key ->
            when (key) {
                "intent" -> intentSource(project)
                "class_library" -> classLibrarySource(project, lib)
                "library_facts" -> libraryFactsSource(project, lib)
                "taken" -> takenSource(project)
                "materials" -> materialsSource(project, projectId)
                "accepted" -> acceptedSource(own)
                "prohibitions" -> prohibitionsSource(project)
                else -> null
            }
        }
    }

    private fun intentSource(project: JsonNode): StatementSource {
        val intent = intentOf(project)
        return StatementSource(
            "intent", "Замысел миссии",
            count = if (intent == null) 0 else 1,
            lines = listOfNotNull(intent),
            note = if (intent == null) "без замысла генерация постановки заблокирована" else null,
        )
    }

    /**
     * Библиотека класса миссии: класс тянет в промпт своё — типовые сервисы,
     * профили стейкхолдеров, типовые риски, нормативы и термины глоссария.
     */
    private fun classLibrarySource(project: JsonNode, lib: List<StoredObject>): StatementSource {
        val classRef = project.path("mission_class").asText("")
        val missionClass = lib.firstOrNull { it.type == "mission_class" && it.id == classRef }
        val lines = mutableListOf<String>()
        missionClass?.let { mc ->
            lines += "класс миссии: ${mc.doc.path("name").asText(mc.id)}"
            mc.doc.path("typical_services").forEach { s ->
                lines += "типовой сервис: ${s.path("name").asText(s.asText(""))}"
            }
        }
        fun ofType(type: String, label: String, field: String = "name") {
            lib.filter { it.type == type }
                .filter { o ->
                    val ref = o.doc.path("mission_class_ref").asText("")
                    ref.isBlank() || classRef.isBlank() || ref == classRef
                }
                .sortedBy { it.id }
                .forEach { o -> lines += "$label: ${o.id} — ${o.doc.path(field).asText("").take(120)}" }
        }
        ofType("stakeholder_profile", "профиль стейкхолдера (А2)")
        ofType("typical_risk", "типовой риск (Б3)", "statement")
        ofType("normative_document", "норматив (Б1)")
        lib.filter { it.type == "glossary" }.forEach { g ->
            g.doc.path("entries").filterNot { it.has("sd_kind") }.forEach { e ->
                lines += "терм: ${e.path("term").asText()} — ${e.path("brief").asText("").take(120)}"
            }
        }
        return StatementSource(
            "class_library", "Библиотека класса миссии", lines.size, lines,
            note = if (missionClass == null) "класс миссии не выбран — полки не подтянуты" else null,
        )
    }

    /**
     * Ф-09: библиотека отдаёт ЗНАНИЕ, а не имена позиций. Источник
     * «класс миссии» перечисляет, ЧТО есть на полке; этот — ЧТО В НЁМ
     * НАПИСАНО: пункты нормативов с реквизитом и числами и блоки канонов
     * библиотечных документов, помеченных «в промпт».
     *
     * Координата обязательна у каждой строки: у пункта — реквизит
     * («ПП №2216, п. 3»), у блока — якорь канона. По ней потом ложится
     * основание требования, и проверить его можно, не выходя из системы.
     */
    private fun libraryFactsSource(project: JsonNode, lib: List<StoredObject>): StatementSource {
        val classRef = project.path("mission_class").asText("")
        fun forClass(o: StoredObject): Boolean {
            val ref = o.doc.path("mission_class_ref").asText("")
            return ref.isBlank() || classRef.isBlank() || ref == classRef
        }
        val lines = mutableListOf<String>()
        var facts = 0
        var silent = 0
        // 1) нормативы полки — своими пунктами, а не наименованием
        lib.filter { it.type == "normative_document" }.filter(::forClass).sortedBy { it.id }.forEach { nr ->
            val designation = listOf("number", "name")
                .firstNotNullOfOrNull { nr.doc.path(it).asText("").ifBlank { null } } ?: nr.id
            val clauses = nr.doc.path("clauses")
            if (clauses.isEmpty) {
                silent++
                return@forEach
            }
            clauses.forEach { c ->
                val clause = c.path("clause").asText("")
                val text = c.path("text").asText("").trim()
                if (text.isNotBlank()) {
                    facts++
                    lines += "${nr.id} «$designation», $clause: ${text.take(400)}"
                }
            }
        }
        // 2) документы полки — выбранными блоками канона (бюджет токенов)
        lib.filter { it.type == "source_document" }
            .filter { it.doc.path("prompt").path("included").asBoolean(false) }
            .sortedBy { it.id }
            .forEach { sd ->
                val name = sd.doc.path("name").asText(sd.id)
                val canon = DocumentParseStore.canonOf(filesDir(), sd.id)
                if (canon == null) {
                    lines += "${sd.id} «$name»: разбора нет — документ полки в промпт не идёт"
                    return@forEach
                }
                val wanted = sd.doc.path("prompt").path("blocks").map { it.asText() }.toSet()
                if (wanted.isEmpty()) {
                    // включён, но блоки не выбраны: отдаём оглавление —
                    // промпт знает, что можно попросить, токены не жжём
                    val map = DocumentParseStore.mapOf(filesDir(), sd.id)
                    val titles = (map?.path("structure")?.toList() ?: emptyList())
                        .filter { it.path("type").asText() == "section" }
                        .joinToString("; ") { it.path("anchor").asText() + " " + it.path("title").asText() }
                    lines += "${sd.id} «$name»: блоки не выбраны — оглавление: ${titles.take(300)}"
                    return@forEach
                }
                val texts = blockTexts(canon, wanted)
                texts.forEach { (anchor, text) ->
                    facts++
                    lines += "${sd.id} «$name» [$anchor]: ${text.take(500)}"
                }
            }
        val note = when {
            facts == 0 && silent > 0 ->
                "нормативы полки ($silent) знают только своё наименование — " +
                    "разберите их документы или впишите пункты, иначе промпт получит имена вместо норм"
            facts == 0 -> "полка не отдала фактов: ни пунктов нормативов, ни блоков документов в промпте"
            silent > 0 -> "нормативов без пунктов на полке: $silent — их знание в промпт не попало"
            else -> null
        }
        return StatementSource("library_facts", "Знание полки (нормы и блоки)", facts, lines, note = note)
    }

    /** Взятые наборы Ш2 — рамкой: «опираться, не дублировать». */
    private fun takenSource(project: JsonNode): StatementSource {
        val counts = project.path("start_path").path("created_counts")
        val lines = counts.properties().map { (type, n) ->
            "в проекте уже есть: $type — ${n.asInt()} (опираться, не дублировать)"
        }
        return StatementSource(
            "taken", "Взято из библиотеки", lines.size, lines,
            note = if (lines.isEmpty()) "наборы библиотеки не брались" else null,
        )
    }

    /**
     * Д3: материалы — ВЫБРАННЫМИ БЛОКАМИ канона, а не файлом целиком.
     * Документ, отмеченный в промпт, отдаёт указанные якоря; без выбора —
     * оглавление документа, чтобы промпт знал, что можно попросить.
     */
    private fun materialsSource(project: JsonNode, projectId: String): StatementSource {
        val chosen = project.path("start_path").path("source_refs").map { it.asText() }
        val blocksByDoc = project.path("start_path").path("source_blocks")
        val lines = mutableListOf<String>()
        var blocks = 0
        chosen.forEach { sdId ->
            val sd = boundary.objects.current(sdId) ?: return@forEach
            val name = sd.doc.path("name").asText(sdId)
            val canon = DocumentParseStore.canonOf(filesDir(), sdId)
            val map = DocumentParseStore.mapOf(filesDir(), sdId)
            val wanted = blocksByDoc.path(sdId).map { it.asText() }
            if (canon == null || map == null) {
                lines += "$sdId «$name»: разбора нет — документ в промпт не идёт"
                return@forEach
            }
            if (wanted.isEmpty()) {
                val titles = map.path("structure").filter { it.path("type").asText() == "section" }
                    .joinToString("; ") { it.path("anchor").asText() + " " + it.path("title").asText() }
                lines += "$sdId «$name»: блоки не выбраны — оглавление: ${titles.take(300)}"
                return@forEach
            }
            val texts = blockTexts(canon, wanted.toSet())
            texts.forEach { (anchor, text) ->
                blocks++
                lines += "$sdId «$name» [$anchor]: ${text.take(500)}"
            }
        }
        return StatementSource(
            "materials", "Материалы блоками", blocks.coerceAtLeast(lines.size), lines,
            note = if (chosen.isEmpty()) "документы в промпт не выбраны" else null,
        )
    }

    /** Тексты выбранных блоков канона — по тем же якорям, что в карте. */
    private fun blockTexts(canon: String, wanted: Set<String>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var anchor: String? = null
        val text = StringBuilder()
        fun flush() {
            val a = anchor
            if (a != null && a in wanted) {
                val body = text.toString().trim()
                if (body.isNotEmpty()) out += a to body
            }
            text.setLength(0)
        }
        canon.lineSequence().forEach { line ->
            val comment = Regex("""^<!--\s*([bt]\d+[^\s]*)\s*-->$""").find(line.trim())
            val heading = Regex("""^(#{1,6})\s+(.*?)\s*\{#([bs]\d+)}$""").find(line.trim())
            when {
                comment != null -> { flush(); anchor = comment.groupValues[1] }
                heading != null -> {
                    flush()
                    anchor = heading.groupValues[3]
                    text.append(heading.groupValues[2])
                }
                else -> if (anchor != null) text.appendLine(line)
            }
        }
        flush()
        return out
    }

    private fun filesDir(): String =
        System.getProperty("orbita.test.filesDir")
            ?: System.getenv("ORBITA_FILES_DIR")
            ?: "files"

    /** Принятый урожай разбора документов (Д2): «уже принято». */
    private fun acceptedSource(own: List<StoredObject>): StatementSource {
        val lines = own.filter { o ->
            val dataset = o.doc.path("provenance").path("import").path("dataset").asText("")
            dataset.startsWith("SD-")
        }.sortedBy { it.id }.map { o ->
            val title = listOf("name", "statement").firstNotNullOfOrNull {
                o.doc.path(it).asText("").ifBlank { null }
            } ?: ""
            val from = o.doc.path("provenance").path("import").path("dataset").asText("").take(40)
            "принято из документа: ${o.id} — ${title.take(110)} [$from]"
        }
        return StatementSource(
            "accepted", "Принято из документов", lines.size, lines,
            note = if (lines.isEmpty()) "урожай разбора документов ещё не принимался" else null,
        )
    }

    private fun prohibitionsSource(project: JsonNode): StatementSource {
        val lines = project.path("constraints")
            .filterNot { it.path("removed").asBoolean(false) }
            .map { c ->
                val code = c.path("code").asText("")
                val text = c.path("text").asText("")
                if (code.isBlank()) text else "$code: $text"
            }
        return StatementSource("prohibitions", "Запреты проекта", lines.size, lines)
    }
}
