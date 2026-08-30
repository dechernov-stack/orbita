// Д2 (ADR-032, ядро ценности): смысловой разбор документа — ОДИН вызов на
// документ по всем классам сразу, поверх выжимки Д1. Здесь живут три вещи:
//
//  · сборка промпта — правила вида (реестр пакетов) + карточка документа +
//    выжимка блоками из канона: службе уходит выжимка, не сырой файл;
//  · приём урожая — пакет проверяется нормативной схемой и кладётся рядом
//    с разбором (тем же способом, что канон и карта);
//  · раскладка урожая по адресам — кандидаты становятся объектами системы
//    через акцепт инженера, с координатой блока в происхождении.
//
// Урожай — НЕ объекты модели: класс кандидата отображается на вид объекта
// правилами ниже, недостающее обязательное поле спрашивается у инженера, а
// не выдумывается (Р-правило проекта: «не выдумывать значения»).
package orbita.com.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.model.CoreType
import orbita.mod.store.StoredObject
import java.nio.file.Files
import java.nio.file.Path

/** Чего не хватает кандидату, чтобы стать объектом: спрашивается у инженера. */
data class HarvestGap(val field: String, val prompt: String, val options: List<String> = emptyList())

/** Класс урожая → вид объекта системы и правило раскладки. */
data class HarvestTarget(
    val type: CoreType?,
    val where: String,
    val gaps: List<HarvestGap> = emptyList(),
    val note: String? = null,
)

object DocumentHarvest {

    private val mapper = ObjectMapper()

    const val KIND = "document_semantic_parse"

    /** Раскладка классов по адресам системы (манифест пачки-1). */
    val TARGETS: Map<String, HarvestTarget> = mapOf(
        // Ф-13: стейкхолдер документа — ФАКТ ПРОЕКТА, а не шаблон полки.
        // Раньше кандидат уходил профилем в библиотеку: обобщение делалось
        // само собой, без решения инженера, а в проекте стейкхолдеров не
        // заводилось вовсе. Обобщить в профиль А2 — отдельное действие.
        "stakeholder" to HarvestTarget(
            CoreType.Stakeholder, "проект — стейкхолдеры (обобщение в профиль А2 — отдельно)",
            gaps = listOf(
                HarvestGap(
                    "role", "роль стейкхолдера в проекте",
                    listOf("customer", "regulator", "operator", "consumer", "partner", "established"),
                ),
            ),
        ),
        "constraint" to HarvestTarget(
            null, "паспорт проекта — ограничение Р-кодом",
            note = "код присваивает система: следующий свободный в Р-серии",
        ),
        "budget" to HarvestTarget(
            CoreType.CostEstimate, "оценки стоимости — каноном денег",
            note = "диапазон документа ложится в total_low/total_high каноном млн ₽",
        ),
        "goal" to HarvestTarget(
            CoreType.MissionGoal, "постановка — цели миссии",
            gaps = listOf(HarvestGap("kind", "цель или задача", listOf("goal", "objective"))),
        ),
        "need" to HarvestTarget(
            CoreType.Need, "постановка — нужды",
            gaps = listOf(
                HarvestGap("stakeholder_name", "чья это нужда — имя стейкхолдера"),
                HarvestGap(
                    "stakeholder_role", "его роль",
                    listOf("customer", "operator", "end_user", "regulator", "agency", "supplier"),
                ),
            ),
        ),
        "normative_ref" to HarvestTarget(
            CoreType.NormativeDocument, "полка А1 — нормативы",
            gaps = listOf(
                HarvestGap("number", "обозначение акта (ГОСТ Р …, ПП № …)"),
                HarvestGap("edition_date", "дата редакции"),
                HarvestGap(
                    "kind", "вид акта",
                    listOf("law", "decree", "tech_reg", "order", "gost", "standard", "convention", "regulation"),
                ),
            ),
            note = "реквизиты документ не назвал (need_ref) — их вносит инженер: разрыв готов, выдумки нет",
        ),
        "service" to HarvestTarget(
            CoreType.Service, "постановка — сервисы",
            gaps = listOf(HarvestGap("need_ref", "нужда, которую сервис закрывает (traces_up)")),
            note = "сервис без нужды и QoS схему не проходит — сначала нужда",
        ),
        // Ответ владельца: ни «руками», ни автоматикой — ЗАГОТОВКА с честной
        // пустотой. Объект создаётся, значение не выдумывается; пустота видима
        // и приглашает там, где она разрыв.
        "geography" to HarvestTarget(
            CoreType.GeoMask, "карта спроса — область приоритета заготовкой",
            note = "имя и приоритет из документа; геометрия не задана — разрыв готовности карты спроса, границу рисует инженер",
        ),
        // Ф-06: характеристика из даташита ложится в анкету носителя —
        // предзаполнением поля, которое инженер сверяет и принимает формой
        "property" to HarvestTarget(
            null, "анкета характеристик — предзаполнение поля",
            note = "значение подставляется в форму носителя с координатой блока; принимает его инженер, сверив с даташитом",
        ),
        "milestone" to HarvestTarget(
            null, "лента вех паспорта — веха без даты",
            note = "длительность документа в дату не превращается: веха ложится с примечанием-происхождением, дата — тихий дефис до руки инженера",
        ),
    )

    // ——— хранение рядом с разбором: тот же каталог, что канон и карта ———

    private fun dirOf(filesDir: String, sdId: String): Path = Path.of(filesDir, sdId, "parse")

    fun store(filesDir: String, sdId: String, fingerprint: String, harvest: JsonNode) {
        val dir = dirOf(filesDir, sdId)
        Files.createDirectories(dir)
        Files.writeString(
            dir.resolve("$fingerprint.harvest.json"),
            mapper.writerWithDefaultPrettyPrinter().writeValueAsString(harvest),
        )
    }

    fun of(filesDir: String, sdId: String): JsonNode? {
        val dir = dirOf(filesDir, sdId)
        if (!Files.isDirectory(dir)) return null
        Files.list(dir).use { s ->
            val file = s.filter { it.fileName.toString().endsWith(".harvest.json") }
                .max(compareBy { Files.getLastModifiedTime(it) })
                .orElse(null) ?: return null
            return mapper.readTree(Files.readString(file))
        }
    }

    /** Счётчики по классам — ими урожай сверяют с эталоном. */
    fun summaryOf(harvest: JsonNode): ObjectNode {
        val out = mapper.createObjectNode()
        harvest.path("items").forEach { i ->
            val cls = i.path("class").asText("")
            out.put(cls, out.path(cls).asInt(0) + 1)
        }
        val needRefs = harvest.path("items").count { it.path("need_ref").asBoolean(false) }
        if (needRefs > 0) out.put("need_ref_flags", needRefs)
        return out
    }

    /**
     * Выжимка документа блоками — вход операции для службы: канон Д1 целиком
     * с якорями. Он и есть «блоки с координатами»: модель отвечает адресами,
     * которые система умеет разложить.
     */
    /**
     * Ф-06 путь 3: даташит предзаполняет анкету. Чтобы это работало, служба
     * обязана знать КЛЮЧИ ПОЛЕЙ анкет — иначе характеристику некуда метить,
     * и путь остаётся мёртвым (механика чтения была, входа для неё не было —
     * находка сверки владельца по репозиторию).
     *
     * Перечень идёт полем `form_field`: ключ · имя · единица справочника.
     */
    fun formFieldsOf(forms: List<StoredObject>): String = buildString {
        if (forms.isEmpty()) return ""
        appendLine("ПОЛЯ АНКЕТ ХАРАКТЕРИСТИК (для класса property — метить ключом form_field):")
        forms.sortedBy { it.id }.forEach { pf ->
            appendLine("— анкета ${pf.id} «${pf.doc.path("name").asText(pf.id)}» " +
                "(роль ${pf.doc.path("role").asText("")}):")
            pf.doc.path("fields").forEach { f ->
                val unit = f.path("unit").asText("")
                appendLine(
                    "    ${f.path("key").asText("")} — ${f.path("name").asText("")}" +
                        (if (unit.isNotBlank()) ", единица $unit" else ""),
                )
            }
        }
    }

    fun statementOf(
        card: JsonNode,
        sdId: String,
        canon: String,
        map: JsonNode?,
        forms: List<StoredObject> = emptyList(),
    ): String = buildString {
        appendLine("КАРТОЧКА ДОКУМЕНТА")
        appendLine("— идентификатор: $sdId")
        appendLine("— наименование: ${card.path("name").asText("")}")
        card.path("kind").takeIf { it.isTextual }?.let { appendLine("— тип: ${it.asText()}") }
        card.path("org").takeIf { it.isTextual }?.let { appendLine("— организация: ${it.asText()}") }
        card.path("doc_date").takeIf { it.isTextual }?.let { appendLine("— дата: ${it.asText()}") }
        map?.path("summary")?.let { s ->
            appendLine(
                "— разбор: блоков ${s.path("blocks").asInt()}, разделов ${s.path("sections").asInt()}, " +
                    "таблиц ${s.path("tables").asInt()}",
            )
        }
        val fields = formFieldsOf(forms)
        if (fields.isNotBlank()) {
            appendLine()
            append(fields)
        }
        appendLine()
        appendLine("ВЫЖИМКА ДОКУМЕНТА БЛОКАМИ (якорь блока — в комментарии либо в {#…}):")
        appendLine(canon)
    }

    // ——— раскладка: кандидат → документ объекта нужного вида ———

    /**
     * Документ объекта из кандидата и дозаполнения инженера. Возвращает null,
     * если класс кладётся не объектом (ограничение, география, веха) — такие
     * маршруты обрабатываются отдельно, по месту их адреса.
     */
    fun objectOf(
        item: JsonNode,
        filled: JsonNode,
        sdId: String,
        sdVersion: String,
        sdName: String,
        acceptedOn: String,
    ): ObjectNode? {
        val cls = item.path("class").asText("")
        val target = TARGETS[cls]?.type ?: return null
        val doc = mapper.createObjectNode()
        val text = item.path("statement").asText("").ifBlank { item.path("name").asText("") }
        when (target) {
            // Ф-13: стейкхолдер проекта — с интересом и происхождением.
            // Учреждаемая сторона помечается: у неё нет ни решений, ни
            // обязательств, и выдавать её за действующую нельзя.
            CoreType.Stakeholder -> {
                doc.put("name", item.path("name").asText("").ifBlank { text })
                doc.put("role", filled.path("role").asText(""))
                val interest = listOf("statement", "note")
                    .firstNotNullOfOrNull { item.path(it).asText("").ifBlank { null } }
                if (interest != null && interest != doc.path("name").asText()) doc.put("interest", interest)
                if (filled.path("role").asText("") == "established") doc.put("establishes", true)
                val anchors = blocksOf(item)
                if (anchors.isNotEmpty()) {
                    val arr = doc.putArray("anchors")
                    anchors.forEach { arr.add(it) }
                }
            }
            CoreType.MissionGoal -> {
                doc.put("kind", filled.path("kind").asText("goal"))
                doc.put("statement", text)
            }
            CoreType.Need -> {
                doc.put("statement", text)
                doc.putObject("stakeholder")
                    .put("name", filled.path("stakeholder_name").asText(""))
                    .put("role", filled.path("stakeholder_role").asText(""))
            }
            CoreType.NormativeDocument -> {
                doc.put("name", text)
                doc.put("kind", filled.path("kind").asText(""))
                doc.put("number", filled.path("number").asText(""))
                doc.put("edition_date", filled.path("edition_date").asText(""))
                doc.put("in_force", filled.path("in_force").asText("in_force"))
                doc.put("summary", text)
            }
            CoreType.CostEstimate -> {
                doc.put("name", text.ifBlank { "оценка из $sdId" })
                doc.put("kind", "rom")
                val canonical = item.path("canonical").takeIf { it.isObject } ?: item.path("range")
                val low = canonical.path("min").takeIf { it.isNumber }?.asDouble()
                    ?: canonical.path("value").asDouble()
                val high = canonical.path("max").takeIf { it.isNumber }?.asDouble() ?: low
                quantity(doc.putObject("total_low"), low, sdId, sdVersion, sdName, acceptedOn, item)
                quantity(doc.putObject("total_high"), high, sdId, sdVersion, sdName, acceptedOn, item)
                doc.put("basis", "величина из документа $sdId, блок ${blocksOf(item).joinToString(", ")}")
            }
            CoreType.GeoMask -> {
                doc.put("name", item.path("name").asText("").ifBlank { text })
                if (item.path("priority").asBoolean(false)) doc.put("priority", true)
                val said = item.path("statement").asText("")
                if (said.isNotBlank()) doc.put("note", said)
                // геометрии НЕТ намеренно: контур «Арктики» из слова был бы
                // витриной. Пустота видна разрывом готовности карты спроса.
            }
            CoreType.Service -> {
                doc.put("name", item.path("name").asText("").ifBlank { text })
                doc.put("description", text)
                val traces = doc.putArray("traces_up")
                filled.path("need_ref").takeIf { it.isTextual && it.asText().isNotBlank() }
                    ?.let { traces.add(it.asText()) }
                doc.putArray("qos_profiles")
            }
            else -> return null
        }
        // происхождение: документ-источник И координата блока — по ним
        // потом видно, из какого места какого документа выросла сущность
        val prov = doc.putObject("provenance")
        prov.put("source", "imported")
        prov.putObject("import")
            .put("dataset", "$sdId «$sdName»")
            .put("dataset_version", sdVersion)
            .put("retrieved_at", acceptedOn)
            // координата блока — полем источника: видно, из какого места
            // документа выросла сущность (это и есть «акцепт по адресам»)
            .put("item_ref", blocksOf(item).joinToString(", "))
            .put("terms", "внутренний документ проекта")
        doc.putObject("lifecycle").put("status", "Draft").put("version", "1")
        return doc
    }

    private fun quantity(
        node: ObjectNode,
        value: Double,
        sdId: String,
        sdVersion: String,
        sdName: String,
        acceptedOn: String,
        item: JsonNode,
    ) {
        node.put("value", value)
        node.put("unit", "MRUB")
        node.putObject("provenance").put("source", "imported").putObject("import")
            .put("dataset", "$sdId «$sdName»")
            .put("dataset_version", sdVersion)
            .put("retrieved_at", acceptedOn)
            .put("item_ref", blocksOf(item).joinToString(", "))
            .put("terms", "внутренний документ проекта")
    }

    fun blocksOf(item: JsonNode): List<String> = when {
        item.path("block").isArray -> item.path("block").map { it.asText() }
        item.path("block").isTextual -> listOf(item.path("block").asText())
        item.path("anchor").isTextual -> listOf(item.path("anchor").asText())
        else -> emptyList()
    }

    /** Ограничение проекта из кандидата: Р-код присваивает система. */
    fun constraintOf(item: JsonNode, existing: JsonNode, sdId: String): ObjectNode {
        var top = 0
        existing.forEach { c ->
            Regex("^Р(\\d+)$").find(c.path("code").asText(""))?.let { m ->
                val n = m.groupValues[1].toInt()
                if (n > top) top = n
            }
        }
        return mapper.createObjectNode()
            .put("code", "Р${top + 1}")
            .put("text", item.path("statement").asText("").ifBlank { item.path("name").asText("") })
            .put("source", "$sdId, блоки ${blocksOf(item).joinToString(", ")}")
    }

    /**
     * Веха программы из кандидата: имя этапа воротами ленты, происхождение —
     * примечанием, дата НЕ ЗАДАЁТСЯ. Длительность документа («0–2 года») в
     * дату не превращается: планирование сроками умерло решением О-10 и через
     * документ не воскресает.
     */
    fun milestoneOf(item: JsonNode, sdId: String): ObjectNode {
        val name = item.path("name").asText("").ifBlank { item.path("statement").asText("") }
        val said = buildString {
            item.path("statement").takeIf { it.isTextual }?.let { append(it.asText()).append("; ") }
            val span = item.path("span")
            if (span.isObject) {
                append("длительность по документу ")
                append(span.path("min").asText("")).append("–").append(span.path("max").asText(""))
                append(" ").append(span.path("unit").asText("")).append("; ")
            }
            val fleet = item.path("fleet")
            if (fleet.isObject) {
                append(if (fleet.path("approx").asBoolean(false)) "около " else "")
                append(fleet.path("value").asText("")).append(" ")
                append(fleet.path("unit").asText("")).append("; ")
            }
            append("источник $sdId, блоки ").append(blocksOf(item).joinToString(", "))
        }
        return mapper.createObjectNode().put("gate", name).put("note", said)
    }

    /** Кандидаты, готовые к раскладке без вопросов инженеру. */
    fun gapsOf(cls: String, item: JsonNode, filled: JsonNode): List<HarvestGap> =
        (TARGETS[cls]?.gaps ?: emptyList()).filter { gap ->
            val given = filled.path(gap.field)
            !(given.isTextual && given.asText().isNotBlank()) && !given.isBoolean &&
                !(gap.field == "need_ref" && item.path("need_ref").asBoolean(false))
        }

    /**
     * Готовая строка величины кандидата: единица и канон — дело сервера,
     * клиент печатает (сторож «обход клиента»: вердиктов и сравнений величин
     * в вебе нет).
     */
    fun displayOf(item: JsonNode): String {
        val q = listOf("canonical", "range", "span", "measure", "fleet")
            .map { item.path(it) }.firstOrNull { it.isObject } ?: return ""
        val unit = q.path("unit").asText("")
        val approx = if (q.path("approx").asBoolean(false)) "≈ " else ""
        return when {
            q.path("min").isNumber && q.path("max").isNumber ->
                "$approx${num(q.path("min").asDouble())}…${num(q.path("max").asDouble())} $unit".trim()
            q.path("value").isNumber -> "$approx${num(q.path("value").asDouble())} $unit".trim()
            else -> ""
        }
    }

    private fun num(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    fun itemsOf(harvest: JsonNode): ArrayNode =
        harvest.path("items").takeIf { it.isArray } as? ArrayNode ?: mapper.createArrayNode()
}
