// Ф-10: библиотека работает во ВНЕШНЕМ контуре. Владелец: знания загружаются
// в проект внешней службы один раз, промпты становятся короткими, ответы
// возвращаются пакетами. Это канал 1 (TZ-AI-001), доведённый до удобства, —
// второй канал, не замена внутреннему.
//
// Здесь: сборка пакета знаний набором MD-файлов, отпечаток выгрузки и
// инструкция, КОТОРАЯ ГЕНЕРИРУЕТСЯ ИЗ РЕЕСТРА видов пакетов. Две редакции
// правил существовать не могут: внутренний контур и внешний читают один и
// тот же источник. Каноны документов уже лежат в MD — выгрузка почти
// бесплатна: она их переносит, а не пересобирает.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.store.ObjectStore
import orbita.mod.store.StoredObject
import java.security.MessageDigest

object KnowledgeExport {

    private val mapper = ObjectMapper()

    /** Части выгрузки: имя файла, заголовок, что внутри. Порядок постоянен. */
    val PARTS: List<Part> = listOf(
        Part("instruction", "00-инструкция.md", "Инструкция внешнего контура"),
        Part("intent", "01-замысел.md", "Замысел миссии"),
        Part("constraints", "02-ограничения.md", "Ограничения проекта (Р-коды)"),
        Part("statement", "03-цели-нужды-сервисы.md", "Постановка: цели, нужды, сервисы"),
        Part("stakeholders", "04-стейкхолдеры.md", "Стейкхолдеры и их профили"),
        Part("normatives", "05-нормативы.md", "Нормативы полки: пункты и каноны"),
        Part("units", "06-справочник-единиц.md", "Справочник единиц"),
        Part("glossary", "07-глоссарий.md", "Глоссарий"),
        Part("materials", "08-материалы.md", "Каноны материалов проекта"),
    )

    data class Part(val key: String, val file: String, val title: String)

    /**
     * Отпечаток знаний: хеш содержимого выгрузки. Он ложится в шапку КАЖДОГО
     * файла и обязан вернуться в ответе службы — по нему видно, отвечала ли
     * она на актуальные знания или на прошлогодние.
     */
    fun fingerprintOf(files: Map<String, String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.toSortedMap().forEach { (name, body) ->
            digest.update(name.toByteArray())
            // шапка с отпечатком в хеш не входит — иначе он зависел бы от себя
            digest.update(body.substringAfter(HEADER_END, body).toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    private const val HEADER_END = "<!-- /отпечаток -->\n"

    /**
     * Пакет знаний: выбранные части. Возвращаются файлы С ШАПКОЙ, в которой
     * стоит отпечаток всей выгрузки, — файл, вырванный из пакета, всё равно
     * знает, частью чего он был.
     */
    fun bundle(
        boundary: Boundary,
        filesDir: String,
        projectId: String,
        parts: Set<String>,
    ): Bundle {
        val chosen = PARTS.filter { it.key in parts }
        val bodies = chosen.associate { part -> part.file to bodyOf(part.key, boundary, filesDir, projectId) }
        val fingerprint = fingerprintOf(bodies)
        val stamped = bodies.mapValues { (_, body) -> header(fingerprint, projectId) + body }
        return Bundle(fingerprint, stamped)
    }

    data class Bundle(val fingerprint: String, val files: Map<String, String>)

    private fun header(fingerprint: String, projectId: String): String = buildString {
        appendLine("<!-- отпечаток знаний: $fingerprint · проект: $projectId -->")
        appendLine("<!-- Ответ службы ОБЯЗАН нести \"knowledge_fingerprint\": \"$fingerprint\" -->")
        append(HEADER_END)
        appendLine()
    }

    private fun bodyOf(key: String, boundary: Boundary, filesDir: String, projectId: String): String {
        val project = boundary.objects.current(projectId)?.doc ?: mapper.createObjectNode()
        val own = boundary.objects.listCurrent(projectId).filter { it.status.name != "Cancelled" }
        val lib = boundary.objects.listCurrent(ObjectStore.LIBRARY_PROJECT)
            .filter { it.status.name != "Cancelled" }
        return when (key) {
            "instruction" -> instruction(project)
            "intent" -> intent(project)
            "constraints" -> constraints(project)
            "statement" -> statement(own)
            "stakeholders" -> stakeholders(own, lib)
            "normatives" -> normatives(lib, filesDir)
            "units" -> units(lib)
            "glossary" -> glossary(lib)
            "materials" -> materials(own, filesDir)
            else -> ""
        }
    }

    /**
     * Инструкция — ИЗ РЕЕСТРА видов, а не написанная рядом руками. Роль,
     * общие правила контура и перечень видов пакетов со схемами приходят
     * оттуда же, откуда их берёт внутренний промпт.
     */
    fun instruction(project: com.fasterxml.jackson.databind.JsonNode): String = buildString {
        val kinds = orbita.ai.PackageKinds.default()
        appendLine("# Инструкция внешнего контура «${project.path("name").asText("проект")}»")
        appendLine()
        appendLine("Ты — служба генерации инженерной системы «Орбита» (космическая миссия")
        appendLine("ранних фаз, NPR 7120.5 / NPR 7123.1). Знания проекта — в файлах этого")
        appendLine("пакета: замысел, ограничения (Р-коды), постановка, стейкхолдеры, каноны")
        appendLine("нормативов и материалов (MD с якорями `{#sN}` / `<!-- bN -->`), справочник")
        appendLine("единиц, глоссарий.")
        appendLine()
        appendLine("## Правила контура (нарушение любого — брак ответа)")
        appendLine()
        CONTOUR_RULES.forEachIndexed { i, r -> appendLine("${i + 1}. $r") }
        appendLine()
        appendLine("## Виды пакетов")
        appendLine()
        appendLine("Ответ — только JSON по схеме запрошенного вида, без преамбулы и пояснений.")
        appendLine("Не ложится в схему — верни `{\"error\": \"…\", \"needs\": [...]}`.")
        appendLine()
        kinds.ids.sorted().map(kinds::of).forEach { k ->
            appendLine("### `${k.id}`")
            appendLine()
            appendLine("- вход: ${k.input}")
            appendLine("- ответ: ${k.output}")
            k.targetSchema?.let { appendLine("- схема ответа: `$it`") }
            if (k.rules.isNotEmpty()) {
                appendLine("- правила вида (редакция ${k.rulesVersion}):")
                k.rules.forEach { appendLine("  - $it") }
            }
            appendLine()
        }
    }

    /** Общие правила внешнего контура — те же законы, что у внутреннего. */
    val CONTOUR_RULES: List<String> = listOf(
        "Отвечай только из знаний этого пакета и поставленной задачи; ничего не привноси. " +
            "Обобщение без опоры на блок-якорь запрещено.",
        "Каждое порождённое утверждение с фактическим основанием несёт `anchors` — " +
            "якоря блоков канона, из которых оно выведено.",
        "Реквизиты нормативных актов по памяти не восстанавливай: нет в канонах — " +
            "верни `\"need_ref\": true` вместо выдуманного реквизита.",
        "Числа — парой `{value, unit}`, единица из справочника единиц; деньги — " +
            "дополнительно `canonical` в млн ₽.",
        "Действующие ограничения Р-* — жёсткие запреты: предложение, нарушающее Р-код, " +
            "не выдаётся вовсе.",
        "Метки источников: [И] — внутренний документ (наш материал); [В] — внешний источник, " +
            "проверенный на указанную дату; [П] — предлагаемая цель или инженерно-финансовое " +
            "допущение, требующее подтверждения. Переноси метку в `source_mark`; " +
            "[П]-допущение не выдавай за факт.",
        "Диапазоны — парой `{min, max}`; «около» без диапазона запрещено.",
        "В каждый ответ включай `knowledge_fingerprint` из шапки файлов этого пакета — " +
            "по нему стенд проверит, не устарели ли знания.",
    )

    private fun intent(project: com.fasterxml.jackson.databind.JsonNode): String = buildString {
        appendLine("# Замысел миссии")
        appendLine()
        val intent = project.path("mission_intent")
        val text = intent.path("text").asText("").trim()
        if (text.isNotBlank()) appendLine(text)
        listOf(
            "for_whom" to "Для кого", "what" to "Что делает",
            "where" to "Где", "horizon" to "Горизонт",
        ).forEach { (field, label) ->
            val value = intent.path(field).asText("").trim()
            if (value.isNotBlank()) {
                append("- **$label:** $value")
                val anchors = intent.path("sources").path(field).map { it.asText() }
                if (anchors.isNotEmpty()) append(" [${anchors.joinToString(", ")}]")
                appendLine()
            }
        }
        if (text.isBlank() && !intent.has("for_whom")) appendLine("_замысел не задан_")
        appendLine()
        appendLine("Класс миссии: `${project.path("mission_class").asText("—")}`, " +
            "фаза: `${project.path("phase").asText("—")}`.")
    }

    private fun constraints(project: com.fasterxml.jackson.databind.JsonNode): String = buildString {
        appendLine("# Ограничения проекта")
        appendLine()
        appendLine("Действующие ограничения — жёсткие запреты: предложение, нарушающее код, не выдаётся.")
        appendLine()
        val live = project.path("constraints").filterNot { it.path("removed").asBoolean(false) }
        if (live.isEmpty()) appendLine("_ограничений нет_")
        live.forEach { c ->
            append("- **${c.path("code").asText("")}**: ${c.path("text").asText("")}")
            c.path("category").asText("").takeIf { it.isNotBlank() }?.let { append(" _(${it})_") }
            c.path("source").asText("").takeIf { it.isNotBlank() }?.let { append(" — источник: $it") }
            appendLine()
        }
        val removed = project.path("constraints").filter { it.path("removed").asBoolean(false) }
        if (removed.isNotEmpty()) {
            appendLine()
            appendLine("## Отменённые (след решения; в силу не возвращаются сами)")
            appendLine()
            removed.forEach { appendLine("- ~~${it.path("code").asText("")}~~: ${it.path("text").asText("")}") }
        }
    }

    private fun statement(own: List<StoredObject>): String = buildString {
        appendLine("# Постановка: цели, нужды, сервисы")
        appendLine()
        listOf(
            "mission_goal" to "Цели миссии",
            "need" to "Нужды стейкхолдеров",
            "service" to "Сервисы",
        ).forEach { (type, title) ->
            val rows = own.filter { it.type == type }.sortedBy { it.id }
            appendLine("## $title (${rows.size})")
            appendLine()
            if (rows.isEmpty()) appendLine("_пусто_")
            rows.forEach { o ->
                val text = listOf("statement", "name")
                    .firstNotNullOfOrNull { o.doc.path(it).asText("").ifBlank { null } } ?: ""
                appendLine("- `${o.id}` $text")
            }
            appendLine()
        }
    }

    private fun stakeholders(own: List<StoredObject>, lib: List<StoredObject>): String = buildString {
        appendLine("# Стейкхолдеры")
        appendLine()
        val rows = (own + lib).filter { it.type == "stakeholder" || it.type == "stakeholder_profile" }
            .sortedBy { it.id }
        if (rows.isEmpty()) appendLine("_пусто_")
        rows.forEach { o ->
            appendLine("- `${o.id}` ${o.doc.path("name").asText("")}" +
                o.doc.path("role").asText("").takeIf { it.isNotBlank() }?.let { " — роль: $it" }.orEmpty() +
                (if (o.type == "stakeholder_profile") " _(профиль полки)_" else ""))
        }
    }

    private fun normatives(lib: List<StoredObject>, filesDir: String): String = buildString {
        appendLine("# Нормативы полки")
        appendLine()
        appendLine("Реквизиты по памяти не восстанавливаются: чего здесь нет — того нет.")
        appendLine()
        val norms = lib.filter { it.type == "normative_document" }.sortedBy { it.id }
        if (norms.isEmpty()) appendLine("_нормативов нет_")
        norms.forEach { nr ->
            val number = nr.doc.path("number").asText("")
            appendLine("## `${nr.id}` ${nr.doc.path("name").asText("")}" +
                (if (number.isNotBlank()) " ($number)" else ""))
            appendLine()
            nr.doc.path("org").asText("").takeIf { it.isNotBlank() }?.let { appendLine("- орган: $it") }
            nr.doc.path("edition_date").asText("").takeIf { it.isNotBlank() }?.let { appendLine("- редакция: $it") }
            nr.doc.path("summary").asText("").takeIf { it.isNotBlank() }?.let { appendLine("- о чём: $it") }
            val clauses = nr.doc.path("clauses")
            if (clauses.isEmpty) {
                appendLine("- _пункты не внесены: знание этого норматива системе недоступно_")
            } else {
                appendLine()
                clauses.forEach { c ->
                    appendLine("- **${c.path("clause").asText("")}** — ${c.path("text").asText("")}")
                }
            }
            val sd = NormativeCandidates.shelfDocumentOf(nr, lib)
            val canon = sd?.let { DocumentParseStore.canonOf(filesDir, it.id) }
            if (canon != null) {
                appendLine()
                appendLine("### Канон `${sd.id}` (блоки с якорями)")
                appendLine()
                appendLine(canon)
            }
            appendLine()
        }
    }

    private fun units(lib: List<StoredObject>): String = buildString {
        appendLine("# Справочник единиц")
        appendLine()
        appendLine("Величина без единицы — ошибка. Единица вне справочника — ошибка.")
        appendLine()
        lib.filter { it.type == "unit_registry" }.forEach { ur ->
            ur.doc.path("units").forEach { u ->
                val canon = u.path("unit").asText("")
                val spellings = u.path("spellings").mapNotNull { it.asText().takeIf(String::isNotBlank) }
                append("- `$canon` — ${u.path("name").asText("")}")
                if (spellings.isNotEmpty()) append(" _(написания: ${spellings.joinToString(", ")})_")
                appendLine()
            }
        }
    }

    private fun glossary(lib: List<StoredObject>): String = buildString {
        appendLine("# Глоссарий")
        appendLine()
        lib.filter { it.type == "glossary" }.forEach { g ->
            g.doc.path("entries").forEach { e ->
                appendLine("- **${e.path("term").asText("")}** — ${e.path("brief").asText("")}")
            }
        }
    }

    private fun materials(own: List<StoredObject>, filesDir: String): String = buildString {
        appendLine("# Каноны материалов проекта")
        appendLine()
        appendLine("Якоря `{#sN}` (разделы) и `<!-- bN -->` (блоки) — координаты оснований:")
        appendLine("ссылайся на них в `anchors`, иначе утверждение непроверяемо.")
        appendLine()
        val docs = own.filter { it.type == "source_document" }.sortedBy { it.id }
        val parsed = docs.filter { DocumentParseStore.canonOf(filesDir, it.id) != null }
        if (parsed.isEmpty()) appendLine("_разобранных материалов нет_")
        parsed.forEach { sd ->
            appendLine("## `${sd.id}` ${sd.doc.path("name").asText("")}")
            appendLine()
            appendLine(DocumentParseStore.canonOf(filesDir, sd.id))
            appendLine()
        }
    }

    /**
     * Сверка отпечатка: пакет, собранный по прошлой выгрузке, принимается —
     * но с предупреждением. Отказ здесь был бы вреден: знания на стенде
     * меняются чаще, чем идёт диалог во внешнем контуре.
     */
    fun staleWarning(packet: com.fasterxml.jackson.databind.JsonNode, current: String): String? {
        val said = packet.path("knowledge_fingerprint").asText("")
        if (said.isBlank()) {
            return "пакет не назвал отпечаток знаний — проверить, на какой выгрузке он собран, невозможно"
        }
        if (said == current) return null
        return "знания устарели: пакет собран по выгрузке $said, на стенде сейчас $current — " +
            "сверьте основания или пересоберите выгрузку"
    }

    fun toJson(bundle: Bundle): ObjectNode {
        val out = mapper.createObjectNode()
        out.put("fingerprint", bundle.fingerprint)
        val files = out.putArray("files")
        bundle.files.forEach { (name, body) ->
            files.addObject().put("name", name).put("size", body.toByteArray().size)
        }
        return out
    }
}
