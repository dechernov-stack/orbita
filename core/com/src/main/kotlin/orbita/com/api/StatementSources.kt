// Ф-05: промпт постановки — ИЗ ДАННЫХ, не из общих слов. Раньше операция О2
// уходила в модель с одним «входом операции»: без записки генерация была
// холостой, и предупреждение это лишь признавало.
//
// Здесь собираются источники промпта, каждый — со счётчиком и содержимым:
//   · замысел миссии — обязательный (без него генерация заблокирована);
//   · библиотека класса миссии — типовые сервисы, профили стейкхолдеров (А2),
//     типовые риски (Б3), применённые нормативы (Б1), термины глоссария;
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
        return "нет замысла миссии — генерация постановки даст общие места. " +
            "Заполните «для кого · что делает · где · горизонт» в начале проекта " +
            "(мастер-путь, шаг 1) либо одним связным абзацем"
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
                "taken" -> takenSource(project)
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
