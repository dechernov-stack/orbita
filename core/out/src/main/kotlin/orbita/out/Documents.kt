// Генерация документов из модели (TZ-OUT-001, шаг 11.1).
// Эталон spec/presentation_semantics.py, один в один.
//
// Генерация — ЧИСТАЯ ФУНКЦИЯ МОДЕЛИ: повторный вызов даёт идентичный результат,
// модель не изменяется. Следствие, о котором предупреждает регламент: ручное
// дополнение текста после генерации не сохраняется — его негде хранить, документ
// целиком выводится из модели. Правка вносится в модель, а не в документ.
//
// СТРУКТУРА РАЗДЕЛОВ ЗАДАНА РЕГЛАМЕНТОМ, а не удобством генератора: приложения
// 2, 3 и 4 БП-PA перечисляют разделы поимённо, и документ обязан состоять
// из них — в том числе из тех, которые модель заполнить пока не может.
//
// ПУСТОЙ РАЗДЕЛ НЕ ВЫБРАСЫВАЕТСЯ. Документ, из которого молча исчезли разделы,
// выглядит полным: читатель видит связный текст и не знает, что раздел
// «Персонал и обеспечение» отсутствует не потому, что не нужен, а потому,
// что в модели нет ни одного объекта, из которого его собрать. Раздел
// остаётся на месте, а рядом с ним стоит разрыв со словами регламента о том,
// что там должно быть. Это та же разница, что между «пусто» и «замечаний нет».
package orbita.out

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.security.MessageDigest

/** Раздел приложения регламента: номер, заголовок и что регламент требует в нём видеть. */
data class SectionTemplate(val number: Int, val title: String, val expects: String)

/**
 * Шаблон документа — ДАННЫЕ из библиотечной области (нитка Б.1): структура
 * разделов приезжает объектом document_template (DT), не enum-ом в коде.
 * Прежний enum удалён; его содержимое переехало сидом
 * data/library/document-templates.json.
 */
data class TemplateData(
    val code: String,
    val title: String,
    /** Откуда взята структура разделов: документ и номер приложения. */
    val source: String,
    val sections: List<SectionTemplate>,
) {
    companion object {
        /** Разбор из документа объекта document_template. */
        fun of(doc: com.fasterxml.jackson.databind.JsonNode): TemplateData = TemplateData(
            code = doc.path("code").asText(),
            title = doc.path("name").asText(""),
            source = doc.path("source").asText(""),
            sections = doc.path("sections").map { sc ->
                SectionTemplate(
                    number = sc.path("number").asInt(),
                    title = sc.path("title").asText(""),
                    expects = sc.path("expects").asText(""),
                )
            },
        )
    }
}

/**
 * Комплекты документов фаз (блок C): Д-код регламента → код шаблона.
 * Составы — РЕСУРС document-kits.json: реестр читают и код, и эталон
 * spec/process_backbone.py, второй копии составов нет.
 */
object DocumentKits {
    private val kits: Map<String, Map<String, String>> = run {
        val mapper = ObjectMapper()
        DocumentKits::class.java.getResourceAsStream("/orbita/out/document-kits.json")!!
            .use { mapper.readTree(it) }
            .properties()
            .filter { (k, _) -> !k.startsWith("_") }
            .associate { (phase, body) ->
                phase to buildMap { body.properties().forEach { (d, t) -> put(d, t.asText()) } }
            }
    }

    val PRE_PHASE_A: Map<String, String> get() = kits.getValue("pre_phase_a")
    val PHASE_A: Map<String, String> get() = kits.getValue("phase_a")

    fun kit(phase: String): Map<String, String> = kits[phase] ?: PRE_PHASE_A
}

/** Авторский текст раздела с отпечатком вставок на момент его сохранения. */
data class SectionAuthorText(val text: String, val insertsFingerprint: String)

/** Разрыв документа: раздел или запись, которую модель заполнить не может. */
data class DocumentGap(val section: Int, val what: String, val expected: String)

/** Документ: тело и слепок содержимого для сверки воспроизводимости. */
data class GeneratedDocument(
    val template: TemplateData,
    val body: ObjectNode,
    val digest: String,
    /** Разрывы: раздел без содержимого либо запись без обязательного атрибута. */
    val gaps: List<DocumentGap>,
)

/**
 * Атрибуты записи требования по Приложению 2. Отсутствие любого из них —
 * разрыв документа: спецификация без обоснования требования проходит вычитку
 * и падает на первом же вопросе «почему именно сто килограммов».
 */
/** Что регламент ждёт во «Введении» (БП-PA, Приложение 2, §1). */
private val INTRODUCTION_ATTRIBUTES = listOf(
    "purpose" to "назначение",
    "scope" to "область",
    "applicable_documents" to "применимые документы",
)

private val REQUIREMENT_ATTRIBUTES = listOf(
    "id" to "идентификатор",
    "statement" to "формулировка",
    "category" to "категория",
    "source" to "источник (родительское требование)",
    "rationale" to "обоснование",
    "mop" to "показатель и значение",
    "verification_method" to "метод верификации",
    "status" to "статус",
    "version" to "версия",
    "owner" to "владелец",
)

class DocumentGenerator(private val mapper: ObjectMapper = ObjectMapper()) {

    /**
     * Сборка документа из выгрузки модели. Функция не принимает изменяемого
     * состояния и ничего не пишет: модель после вызова та же, что и до.
     */
    fun render(
        model: JsonNode,
        template: TemplateData,
        /**
         * Авторские тексты разделов (В1.2): номер раздела → (текст, отпечаток
         * вставок на момент сохранения). Раздел документа = авторский текст +
         * данные вставок; расхождение отпечатка — помета «текст устарел»
         * в gaps-механику: помета, не блокировка и не перезапись.
         */
        texts: Map<Int, SectionAuthorText> = emptyMap(),
    ): GeneratedDocument {
        val body = mapper.createObjectNode()
        body.put("template", template.code)
        body.put("title", template.title)
        body.put("source", template.source)
        val gaps = mutableListOf<DocumentGap>()
        val sections = body.putArray("sections")

        for (s in template.sections) {
            val node = sections.addObject()
            node.put("number", s.number)
            node.put("title", s.title)
            node.put("expects", s.expects)
            val items = node.putArray("items")
            fill(template, s.number, model, items, gaps)
            // отпечаток данных вставок раздела — по нему авторский текст
            // узнаёт, что модель уехала из-под него
            val fingerprint = digestOf(items)
            node.put("inserts_fingerprint", fingerprint)
            texts[s.number]?.let { t ->
                node.put("text", t.text)
                if (t.insertsFingerprint.isNotBlank() && t.insertsFingerprint != fingerprint) {
                    gaps += DocumentGap(
                        s.number, "текст устарел",
                        "данные вставок изменились после сохранения авторского текста — перечитайте и сохраните заново",
                    )
                }
            }
            // Раздел без текста и записей остаётся в документе пустым, но не
            // молча: регламент сказал, что в нём должно быть, — это и
            // записывается разрывом.
            if (items.isEmpty && texts[s.number] == null) {
                gaps += DocumentGap(s.number, "раздел пуст", s.expects)
            }
        }

        // Плоский перечень записей документа: сохраняется для совместимости
        // с потребителями, читавшими документ до появления разделов.
        val items = body.putArray("items")
        sections.forEach { s -> s.path("items").forEach(items::add) }
        return GeneratedDocument(template, body, digestOf(body), gaps.toList())
    }

    private fun fill(
        template: TemplateData,
        section: Int,
        model: JsonNode,
        items: ArrayNode,
        gaps: MutableList<DocumentGap>,
    ) = when (template.code) {
        "req_spec" -> fillRequirementSpec(section, model, items, gaps)
        "conops" -> fillConOps(section, model, items)
        "architecture" -> fillArchitecture(section, model, items)
        "fad" -> fillFad(section, model, items)
        "mission_concept" -> fillMissionConcept(section, model, items)
        "req_draft" -> fillRequirementDraft(section, model, items)
        "tech_needs" -> fillTechnologyRecords(section, model, items, gaps)
        "risk_list" -> fillRiskRecords(section, model, items)
        "oda" -> fillOda(section, model, items)
        "cost_estimate" -> fillCost(section, model, items, "rom")
        "formulation_agreement" -> fillFormulationAgreement(section, model, items)
        "semp" -> fillSemp(section, model, items)
        "tech_plan" -> fillTechnologyPlan(section, model, items, gaps)
        "risk_plan" -> fillRiskPlan(section, model, items)
        "cost_ranges" -> fillCost(section, model, items, "range")
        "project_plan" -> fillProjectPlan(section, model, items, gaps)
        // документ неизвестного кода: наполнения нет — честный разрыв раздела
        else -> gaps.add(DocumentGap(section, "содержимое", "наполнение для кода '" + template.code + "' не заведено"))
    }

    // ---------- Приложение 2: спецификация требований ----------

    private fun fillRequirementSpec(
        section: Int,
        model: JsonNode,
        items: ArrayNode,
        gaps: MutableList<DocumentGap>,
    ) {
        val requirements = model.path("requirements").sortedBy { it.path("id").asText() }
        when (section) {
            1 -> introduction(model, items, gaps, section)
            // Уровень требования задаёт раздел: проектные — во второй, системные —
            // в третий. Свалить всё в один список значило бы потерять различие,
            // которое регламент проводит намеренно.
            2 -> requirements.filter { it.path("level").asText() == "project" }
                .forEach { items.add(requirementRecord(it, gaps, section)) }
            3 -> requirements.filter { it.path("level").asText() != "project" }
                .forEach { items.add(requirementRecord(it, gaps, section)) }
            4 -> requirements.forEach { r ->
                val n = items.addObject()
                n.put("requirement", r.path("id").asText())
                val up = n.putArray("traces_up")
                r.path("traces_up").forEach { up.add(it.path("ref").asText()) }
                val elements = n.putArray("allocated_to")
                r.path("allocated_to").forEach { elements.add(it.path("component").asText()) }
                n.put("verification_method", verificationMethod(r) ?: "")
            }
            5 -> requirements.forEach { r ->
                // Строка на СОБЫТИЕ, а не на требование: требование с анализом
                // на Phase A и испытанием на Phase C — это две разные строки
                // с разными этапами, и сводить их в одну нельзя.
                r.path("verification_events").forEach { e ->
                    val n = items.addObject()
                    n.put("requirement", r.path("id").asText())
                    n.put("event", e.path("id").asText())
                    n.put("method", e.path("method").asText(""))
                    n.put("phase", e.path("phase").asText(""))
                    n.put("level", e.path("level").asText(""))
                    n.put("closes", e.path("closes").asBoolean(false))
                    n.put("status", e.path("status").asText(""))
                }
            }
            else -> {}
        }
    }

    /** Запись требования со всеми атрибутами Приложения 2; недостающие — разрывы. */
    /**
     * «Введение»: назначение, область и применимые документы — из проекта
     * (ADR-022). Незаполненное поле — разрыв ПОИМЁННО, а не молча пустая
     * строка в разделе: инженер обязан видеть, чего именно не хватает, и
     * иметь возможность это дописать. Отсутствие самого проекта оставляет
     * раздел пустым, и общий разрыв «раздел пуст» скажет об этом сам.
     */
    private fun introduction(
        model: JsonNode,
        items: ArrayNode,
        gaps: MutableList<DocumentGap>,
        section: Int,
    ) {
        val p = model.path("project").takeIf { it.isObject && !it.isEmpty } ?: return
        val n = items.addObject()
        n.put("purpose", p.path("purpose").asText(""))
        n.put("scope", p.path("scope").asText(""))
        val docs = n.putArray("applicable_documents")
        p.path("applicable_documents").forEach { d ->
            val code = d.path("code").asText("")
            val title = d.path("title").asText("")
            val revision = d.path("revision").asText("")
            docs.add(listOf(code, title, revision).filter { it.isNotBlank() }.joinToString(" — "))
        }
        for ((field, label) in INTRODUCTION_ATTRIBUTES) {
            val value = n.path(field)
            val blank = (value.isTextual && value.asText().isBlank()) ||
                (value.isArray && value.isEmpty)
            if (blank) gaps += DocumentGap(section, "проект: не заполнено «$label»", "Введение")
        }
    }

    private fun requirementRecord(r: JsonNode, gaps: MutableList<DocumentGap>, section: Int): ObjectNode {
        val n = mapper.createObjectNode()
        val id = r.path("id").asText()
        n.put("id", id)
        n.put("statement", r.path("statement").asText(""))
        n.put("category", r.path("category").asText(""))
        n.put("level", r.path("level").asText(""))
        n.put("source", r.path("traces_up").joinToString(", ") { it.path("ref").asText() })
        n.put("rationale", r.path("rationale").asText(""))
        r.path("mop").takeIf { it.isObject && !it.isEmpty }?.let { n.set<ObjectNode>("mop", it) }
        n.put("verification_method", verificationMethod(r) ?: "")
        n.put("status", r.path("lifecycle").path("status").asText(""))
        n.put("version", r.path("lifecycle").path("version").asText(""))
        n.put("owner", r.path("owner").asText(""))

        for ((field, label) in REQUIREMENT_ATTRIBUTES) {
            val value = n.path(field)
            val blank = value.isMissingNode || value.isNull ||
                (value.isTextual && value.asText().isBlank()) ||
                ((value.isObject || value.isArray) && value.isEmpty)
            if (blank) gaps += DocumentGap(section, "$id: нет атрибута «$label»", "Приложение 2, атрибуты записи")
        }
        return n
    }

    /** Метод верификации: из закрывающего события, иначе из первого (CR-003). */
    private fun verificationMethod(r: JsonNode): String? {
        val events = r.path("verification_events")
        val chosen = events.firstOrNull { it.path("closes").asBoolean(false) } ?: events.firstOrNull()
        return chosen?.path("method")?.asText("")?.ifBlank { null }
    }

    // ---------- Приложение 3: ConOps ----------

    private fun fillConOps(section: Int, model: JsonNode, items: ArrayNode) {
        when (section) {
            1 -> model.path("needs").sortedBy { it.path("id").asText() }.forEach { nd ->
                val n = items.addObject()
                n.put("id", nd.path("id").asText())
                n.put("statement", nd.path("statement").asText(""))
                n.put("stakeholder", nd.path("stakeholder").path("name").asText(""))
                n.put("role", nd.path("stakeholder").path("role").asText(""))
            }
            2 -> components(model).filter { it.second.path("kind").asText() == "segment" }
                .forEach { (id, c) ->
                    val n = items.addObject()
                    n.put("id", id)
                    n.put("name", c.path("name").asText(""))
                    n.put("parent", c.path("parent").asText(""))
                }
            3 -> model.path("spacecraft").path("modes").forEach { m ->
                val n = items.addObject()
                n.put("name", m.path("name").asText(""))
                n.put("power_w", m.path("power_w").asDouble(0.0))
                n.put("orbit_fraction", m.path("orbit_fraction").asDouble(0.0))
            }
            // Шаг 17 C1: операционные сценарии — ХРАНИМЫЕ объекты conops,
            // а не поле, которого в модели никогда не было
            4 -> model.path("conops_scenarios").sortedBy { it.path("id").asText() }.forEach { co ->
                val n = items.addObject()
                n.put("id", co.path("id").asText())
                n.put("name", co.path("name").asText(""))
                n.put("kind", co.path("kind").asText(""))
                n.put("phase", co.path("phase").asText(""))
                n.put("success_criterion", co.path("success_criterion").asText(""))
                val flow = n.putArray("flow")
                co.path("flow").forEach(flow::add)
            }
            5 -> {
                model.path("constellation").takeIf { it.isObject && !it.isEmpty }?.let { c ->
                    val n = items.addObject()
                    n.put("kind", "orbit")
                    n.put("name", c.path("name").asText(""))
                    n.set<ObjectNode>("walker", c.path("walker").deepCopy())
                }
                model.path("ground_stations").path("stations").forEach { s ->
                    val n = items.addObject()
                    n.put("kind", "ground_station")
                    n.put("id", s.path("id").asText())
                    n.put("name", s.path("name").asText(""))
                    n.put("lat_deg", s.path("lat_deg").asDouble(0.0))
                    n.put("lon_deg", s.path("lon_deg").asDouble(0.0))
                }
            }
            6 -> model.path("operations_staffing").forEach(items::add)
            7 -> model.path("validations").sortedBy { it.path("id").asText() }.forEach { v ->
                val n = items.addObject()
                n.put("id", v.path("id").asText())
                n.put("target", v.path("target").asText(""))
                n.put("method", v.path("method").asText(""))
                n.put("approach", v.path("approach").asText(""))
                n.put("status", v.path("status").asText(""))
            }
            else -> {}
        }
    }

    // ---------- Приложение 4: описание архитектуры ----------

    private fun fillArchitecture(section: Int, model: JsonNode, items: ArrayNode) {
        val all = components(model)
        when (section) {
            1 -> all.filter { it.second.path("kind").asText() == "system" }.forEach { (id, c) ->
                val n = items.addObject()
                n.put("id", id)
                n.put("name", c.path("name").asText(""))
            }
            2 -> all.filter { it.second.path("kind").asText() !in setOf("interface", "system") }
                .forEach { (id, c) ->
                    val n = items.addObject()
                    n.put("id", id)
                    n.put("name", c.path("name").asText(""))
                    n.put("kind", c.path("kind").asText(""))
                    n.put("parent", c.path("parent").asText(""))
                }
            3 -> all.filter { it.second.path("kind").asText() != "interface" }.forEach { (id, c) ->
                val n = items.addObject()
                n.put("id", id)
                n.put("name", c.path("name").asText(""))
                n.put("segment", c.path("segment").asText(""))
                n.put("wbs", c.path("wbs").asText(""))
            }
            4 -> all.filter { it.second.path("kind").asText() == "interface" }.forEach { (id, c) ->
                val n = items.addObject()
                n.put("id", id)
                n.put("name", c.path("name").asText(""))
                val owners = n.putArray("owners")
                c.path("owners").forEach { owners.add(it.asText()) }
            }
            5 -> model.path("requirements").sortedBy { it.path("id").asText() }
                .filter { !it.path("allocated_to").isEmpty }
                .forEach { r ->
                    val n = items.addObject()
                    n.put("requirement", r.path("id").asText())
                    n.put("statement", r.path("statement").asText(""))
                    val to = n.putArray("allocated_to")
                    r.path("allocated_to").forEach { to.add(it.path("component").asText()) }
                }
            6 -> model.path("options").forEach { o ->
                val n = items.addObject()
                n.put("name", o.path("name").asText(""))
                o.properties().sortedBy { it.key }
                    .filter { it.key != "name" }
                    .forEach { (k, v) -> n.set<ObjectNode>(k, v.deepCopy()) }
            }
            7 -> model.path("budgets").forEach(items::add)
            else -> {}
        }
    }


    // ---------- Блок C: комплекты Д1–Д9 / Д1–Д10 ----------

    private fun goalsRecords(model: JsonNode, items: ArrayNode) =
        model.path("mission_goals").sortedBy { it.path("id").asText() }.forEach { g ->
            val n = items.addObject()
            n.put("id", g.path("id").asText())
            n.put("kind", g.path("kind").asText(""))
            n.put("statement", g.path("statement").asText(""))
            n.put("program_link", g.path("program_link").asText(""))
            val moe = n.putArray("moe")
            g.path("moe").forEach { m ->
                moe.addObject()
                    .put("id", m.path("id").asText())
                    .put("name", m.path("name").asText(""))
                    .set<ObjectNode>("target", m.path("target").deepCopy())
            }
        }

    private fun milestoneRecords(model: JsonNode, items: ArrayNode) {
        // Планирование длительностями: дата вехи — якорная due либо расчёт
        // цепочкой prev + duration_days (тот же вывод, что /views/gates).
        // Расчёт детерминирован: часов здесь нет, только данные модели.
        var prev: java.time.LocalDate? = null
        model.path("project").path("milestones").forEach { m ->
            val anchor = m.path("due").asText("").takeIf { it.isNotBlank() }
                ?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
            val duration = m.path("duration_days").takeIf { it.isInt }?.asInt()
            val effective = anchor
                ?: if (duration != null && prev != null) prev!!.plusDays(duration.toLong()) else null
            val n = items.addObject()
            n.put("gate", m.path("gate").asText())
            m.path("phase").asText("").takeIf { it.isNotBlank() }?.let { n.put("phase", it) }
            duration?.let { n.put("duration_days", it) }
            n.put("due", effective?.toString() ?: "")
            if (anchor == null && effective != null) n.put("computed", true)
            n.put("held", m.path("held").asBoolean(false))
            prev = effective ?: prev
        }
    }

    private fun costRecords(model: JsonNode, items: ArrayNode, kind: String?) =
        model.path("cost_estimates").sortedBy { it.path("id").asText() }
            .filter { kind == null || it.path("kind").asText() == kind }
            .forEach { c ->
                val n = items.addObject()
                n.put("id", c.path("id").asText())
                n.put("name", c.path("name").asText(""))
                n.put("kind", c.path("kind").asText(""))
                n.put("basis", c.path("basis").asText(""))
                n.set<ObjectNode>("total_low", c.path("total_low").deepCopy())
                n.set<ObjectNode>("total_high", c.path("total_high").deepCopy())
                c.path("schedule_months_low").takeIf { it.isInt }?.let { n.put("schedule_months_low", it.asInt()) }
                c.path("schedule_months_high").takeIf { it.isInt }?.let { n.put("schedule_months_high", it.asInt()) }
            }

    private fun wbsBreakdown(model: JsonNode, items: ArrayNode, kind: String?) =
        model.path("cost_estimates")
            .filter { kind == null || it.path("kind").asText() == kind }
            .sortedBy { it.path("id").asText() }
            .forEach { c ->
                c.path("items").forEach { i ->
                    val n = items.addObject()
                    n.put("estimate", c.path("id").asText())
                    n.put("wbs_ref", i.path("wbs_ref").asText(""))
                    n.put("name", i.path("name").asText(""))
                    n.set<ObjectNode>("low", i.path("low").deepCopy())
                    n.set<ObjectNode>("high", i.path("high").deepCopy())
                }
            }

    private fun riskRecords(model: JsonNode, items: ArrayNode) =
        model.path("risks").sortedBy { it.path("id").asText() }.forEach { r ->
            val n = items.addObject()
            n.put("id", r.path("id").asText())
            n.put("statement", r.path("statement").asText(""))
            n.put("category", r.path("category").asText(""))
            n.put("probability", r.path("probability").asInt(0))
            n.put("impact", r.path("impact").asInt(0))
            n.put("strategy", r.path("strategy").asText(""))
            n.put("owner", r.path("owner").asText(""))
            n.put("status", r.path("status").asText(""))
            r.path("due").takeIf { it.isTextual }?.let { n.put("due", it.asText()) }
        }

    private fun fillFad(section: Int, model: JsonNode, items: ArrayNode) {
        when (section) {
            1 -> model.path("project").takeIf { it.isObject && !it.isEmpty }?.let { p ->
                val n = items.addObject()
                n.put("project", p.path("name").asText(""))
                n.put("phase", p.path("phase").asText(""))
                model.path("mission_goals").forEach { g ->
                    val link = g.path("program_link").asText("")
                    if (link.isNotBlank()) n.withArray("program_links").add(link)
                }
            }
            3 -> model.path("requirements")
                .filter { it.path("level").asText() == "project" }
                .sortedBy { it.path("id").asText() }
                .forEach { r ->
                    items.addObject()
                        .put("id", r.path("id").asText())
                        .put("statement", r.path("statement").asText(""))
                }
            4 -> costRecords(model, items, "rom")
            5 -> milestoneRecords(model, items)
            else -> {}   // полномочия и подписи модель не хранит — честный разрыв
        }
    }

    private fun fillMissionConcept(section: Int, model: JsonNode, items: ArrayNode) {
        val all = components(model)
        when (section) {
            1 -> goalsRecords(model, items)
            2 -> {
                // МВП-М2 §3.5: живая матрица сравнения построений — вставкой
                // механизма В1; выпуск фиксирует снимок вместе с телом
                val cmp = model.path("constellation_compare")
                if (cmp.isObject && cmp.path("variants").isArray && cmp.path("variants").size() > 0) {
                    val n = items.addObject()
                    n.put("kind", "constellation_compare_table")
                    n.put("computed_at", cmp.path("computed_at").asText(""))
                    n.put("scenario_ref", cmp.path("scenario_ref").asText(""))
                    n.set<com.fasterxml.jackson.databind.node.ArrayNode>(
                        "variants", cmp.path("variants").deepCopy(),
                    )
                }
                model.path("alternatives").sortedBy { it.path("id").asText() }
                .filter { it.path("kind").asText() == "option" }
                .forEach { a ->
                    val n = items.addObject()
                    n.put("id", a.path("id").asText())
                    n.put("name", a.path("name").asText(""))
                    n.put("summary", a.path("summary").asText(""))
                    n.put("scenario_ref", a.path("scenario_ref").asText(""))
                    val cr = n.putArray("criteria")
                    a.path("criteria").forEach { c ->
                        cr.addObject()
                            .put("name", c.path("name").asText(""))
                            .put("score", c.path("score").asDouble(0.0))
                            .put("rationale", c.path("rationale").asText(""))
                    }
                }
            }
            3 -> all.filter { it.second.path("kind").asText() in setOf("segment", "system") }
                .forEach { (id, c) ->
                    items.addObject()
                        .put("id", id)
                        .put("name", c.path("name").asText(""))
                        .put("kind", c.path("kind").asText(""))
                }
            4 -> model.path("conops_scenarios").sortedBy { it.path("id").asText() }.forEach { co ->
                items.addObject()
                    .put("id", co.path("id").asText())
                    .put("name", co.path("name").asText(""))
                    .put("kind", co.path("kind").asText(""))
            }
            5 -> {
                all.filter { it.second.path("kind").asText() in setOf("subsystem", "assembly") }
                    .forEach { (id, c) ->
                        items.addObject()
                            .put("kind", "component")
                            .put("id", id)
                            .put("name", c.path("name").asText(""))
                    }
                model.path("wbs_elements").sortedBy { it.path("code").asText() }.forEach { w ->
                    items.addObject()
                        .put("kind", "wbs")
                        .put("id", w.path("id").asText())
                        .put("code", w.path("code").asText(""))
                        .put("name", w.path("name").asText(""))
                }
            }
            6 -> model.path("constellation").takeIf { it.isObject && !it.isEmpty }?.let { c ->
                val n = items.addObject()
                n.put("name", c.path("name").asText(""))
                n.set<ObjectNode>("walker", c.path("walker").deepCopy())
            }
            7 -> fillTechnologyRecords(1, model, items, mutableListOf())
            8 -> riskRecords(model, items)
            9 -> model.path("alternatives")
                .filter { it.path("kind").asText() == "descope" }
                .sortedBy { it.path("id").asText() }
                .forEach { a ->
                    items.addObject()
                        .put("id", a.path("id").asText())
                        .put("name", a.path("name").asText(""))
                        .put("summary", a.path("summary").asText(""))
                        .put("consequences", a.path("consequences").asText(""))
                }
            10 -> costRecords(model, items, "rom")
            11 -> model.path("decisions").sortedBy { it.path("id").asText() }
                .filter { it.path("status").asText() == "decided" }
                .forEach { d ->
                    items.addObject()
                        .put("id", d.path("id").asText())
                        .put("question", d.path("question").asText(""))
                        .put("selected", d.path("selected").asText(""))
                        .put("rationale", d.path("rationale").asText(""))
                }
            else -> {}
        }
    }

    private fun fillRequirementDraft(section: Int, model: JsonNode, items: ArrayNode) {
        if (section != 1) return
        // черновик допускает TBD и неполноту (Прил. 4 БП-PPA) — разрывы
        // атрибутов здесь не пишутся, в отличие от спецификации Прил. 2
        model.path("requirements")
            .filter { it.path("level").asText() == "project" }
            .sortedBy { it.path("id").asText() }
            .forEach { r ->
                val n = items.addObject()
                n.put("id", r.path("id").asText())
                n.put("statement", r.path("statement").asText(""))
                n.put("category", r.path("category").asText(""))
                n.put("source", r.path("traces_up").joinToString(", ") { it.path("ref").asText() })
                r.path("mop").takeIf { it.isObject && !it.isEmpty }?.let { n.set<ObjectNode>("mop", it.deepCopy()) }
                n.put("verification_method", verificationMethod(r) ?: "")
                n.put("status", r.path("lifecycle").path("status").asText(""))
            }
    }

    private fun fillTechnologyRecords(
        section: Int,
        model: JsonNode,
        items: ArrayNode,
        gaps: MutableList<DocumentGap>,
    ) {
        if (section != 1) return
        model.path("technologies").sortedBy { it.path("id").asText() }.forEach { t ->
            val id = t.path("id").asText()
            val n = items.addObject()
            n.put("id", id)
            n.put("name", t.path("name").asText(""))
            val comps = n.putArray("components")
            t.path("components").forEach { comps.add(it.asText()) }
            n.put("trl_current", t.path("trl_current").asInt(0))
            n.put("trl_required", t.path("trl_required").asInt(0))
            n.put("gate", t.path("gate").asText(""))
            n.put("maturation_plan", t.path("maturation_plan").asText(""))
            if (t.path("maturation_plan").asText("").isBlank() &&
                t.path("trl_current").asInt(0) < t.path("trl_required").asInt(0)
            ) {
                gaps += DocumentGap(section, "$id: разрыв TRL без плана созревания", "Приложение 6, атрибуты записи")
            }
        }
    }

    private fun fillRiskRecords(section: Int, model: JsonNode, items: ArrayNode) {
        if (section == 1) riskRecords(model, items)
    }

    private fun fillOda(section: Int, model: JsonNode, items: ArrayNode) {
        val assessments = model.path("oda_assessments").sortedBy { it.path("id").asText() }
        when (section) {
            1 -> assessments.forEach { o ->
                val n = items.addObject()
                n.put("id", o.path("id").asText())
                n.put("kind", o.path("kind").asText(""))
                n.set<ObjectNode>("deorbit_years", o.path("deorbit_years").deepCopy())
                o.path("casualty_risk").takeIf { it.isObject }?.let { n.set<ObjectNode>("casualty_risk", it.deepCopy()) }
            }
            2 -> assessments.forEach { o ->
                o.path("findings").forEach { f ->
                    items.addObject()
                        .put("assessment", o.path("id").asText())
                        .put("rule", f.path("rule").asText(""))
                        .put("compliant", f.path("compliant").asBoolean(false))
                        .put("note", f.path("note").asText(""))
                }
            }
            else -> {}
        }
    }

    private fun fillCost(section: Int, model: JsonNode, items: ArrayNode, kind: String) {
        when (section) {
            1 -> costRecords(model, items, kind)
            2 -> wbsBreakdown(model, items, kind)
            else -> {}
        }
    }

    private fun fillFormulationAgreement(section: Int, model: JsonNode, items: ArrayNode) {
        when (section) {
            1 -> model.path("project").takeIf { it.isObject && !it.isEmpty }?.let { p ->
                items.addObject()
                    .put("project", p.path("name").asText(""))
                    .put("phase", p.path("phase").asText(""))
            }
            2 -> {
                model.path("wbs_elements").sortedBy { it.path("code").asText() }.forEach { w ->
                    items.addObject()
                        .put("kind", "work")
                        .put("code", w.path("code").asText(""))
                        .put("name", w.path("name").asText(""))
                }
                model.path("technologies")
                    .filter { it.path("trl_current").asInt(0) < it.path("trl_required").asInt(0) }
                    .forEach { t ->
                        items.addObject()
                            .put("kind", "maturation")
                            .put("id", t.path("id").asText())
                            .put("name", t.path("name").asText(""))
                    }
            }
            4 -> milestoneRecords(model, items)
            5 -> costRecords(model, items, null)
            else -> {}
        }
    }

    private fun fillSemp(section: Int, model: JsonNode, items: ArrayNode) {
        when (section) {
            1 -> model.path("project").takeIf { it.isObject && !it.isEmpty }?.let { p ->
                items.addObject()
                    .put("project", p.path("name").asText(""))
                    .put("phase", p.path("phase").asText(""))
            }
            2 -> components(model).filter { it.second.path("kind").asText() == "system" }
                .forEach { (id, c) ->
                    items.addObject().put("id", id).put("name", c.path("name").asText(""))
                }
            5 -> milestoneRecords(model, items)
            else -> {}   // организация, процессы, tailoring, подписи — вне модели
        }
    }

    private fun fillTechnologyPlan(
        section: Int,
        model: JsonNode,
        items: ArrayNode,
        gaps: MutableList<DocumentGap>,
    ) {
        val technologies = model.path("technologies").sortedBy { it.path("id").asText() }
        when (section) {
            1 -> fillTechnologyRecords(1, model, items, gaps)
            2 -> technologies.forEach { t ->
                items.addObject()
                    .put("id", t.path("id").asText())
                    .put("name", t.path("name").asText(""))
                    .put("maturation_plan", t.path("maturation_plan").asText(""))
                    .put("gate", t.path("gate").asText(""))
            }
            4 -> technologies
                .filter { it.path("risk_ref").asText("").isNotBlank() }
                .forEach { t ->
                    items.addObject()
                        .put("id", t.path("id").asText())
                        .put("risk_ref", t.path("risk_ref").asText(""))
                }
            else -> {}
        }
    }

    private fun fillRiskPlan(section: Int, model: JsonNode, items: ArrayNode) {
        val risks = model.path("risks")
        when (section) {
            2 -> {
                // матрица критичности 5×5 счётом — «шкалы и матрица» из данных
                val counts = HashMap<Pair<Int, Int>, Int>()
                risks.forEach { r ->
                    val key = r.path("probability").asInt(0) to r.path("impact").asInt(0)
                    counts[key] = (counts[key] ?: 0) + 1
                }
                counts.toSortedMap(compareBy({ it.first }, { it.second })).forEach { (k, v) ->
                    items.addObject()
                        .put("probability", k.first)
                        .put("impact", k.second)
                        .put("risks", v)
                }
            }
            3 -> risks.map { it.path("owner").asText("") }.filter { it.isNotBlank() }
                .distinct().sorted().forEach { owner -> items.addObject().put("owner", owner) }
            4 -> riskRecords(model, items)
            else -> {}
        }
    }

    private fun fillProjectPlan(
        section: Int,
        model: JsonNode,
        items: ArrayNode,
        gaps: MutableList<DocumentGap>,
    ) {
        when (section) {
            1 -> {
                goalsRecords(model, items)
                model.path("requirements")
                    .filter { it.path("level").asText() == "project" }
                    .sortedBy { it.path("id").asText() }
                    .forEach { r ->
                        items.addObject()
                            .put("id", r.path("id").asText())
                            .put("statement", r.path("statement").asText(""))
                    }
            }
            3 -> model.path("wbs_elements").sortedBy { it.path("code").asText() }.forEach { w ->
                items.addObject()
                    .put("id", w.path("id").asText())
                    .put("code", w.path("code").asText(""))
                    .put("name", w.path("name").asText(""))
                    .put("owner", w.path("owner").asText(""))
            }
            4 -> {
                // График по фазам — ВЕСЬ жизненный цикл ЕДИНЫМ рядом
                // контрольных точек (замечание прогона: «проект идёт через
                // контрольные точки», а не через текстовые приписки сбоку).
                // Вехи Phase B–F добавляются кнопкой на ленте цикла; ИС их
                // показывает, но не проводит (ворот к ним нет).
                milestoneRecords(model, items)
                val known = orbita.req.Gates().gateNames
                val beyond = model.path("project").path("milestones")
                    .any { it.path("gate").asText() !in known }
                if (!beyond) {
                    gaps += DocumentGap(
                        section,
                        "проект: план обрывается горизонтом Формулирования — добавьте " +
                            "вехи Phase B–F («Жизненный цикл» → «+ вехи Phase B–F»)",
                        "Приложение 7 §4: укрупнённый план по фазам всего жизненного цикла",
                    )
                }
            }
            5 -> costRecords(model, items, null)
            6 -> riskRecords(model, items)
            else -> {}
        }
    }

    /** Элементы архитектуры в устойчивом порядке: словарь идентификатор → объект. */
    private fun components(model: JsonNode): List<Pair<String, JsonNode>> {
        val node = model.path("components")
        return when {
            node.isObject -> node.properties().map { it.key to it.value }.sortedBy { it.first }
            node.isArray -> node.map { it.path("id").asText() to it }.sortedBy { it.first }
            else -> emptyList()
        }
    }

    private fun digestOf(body: JsonNode): String =
        MessageDigest.getInstance("SHA-256").digest(canonical(body).toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }

    /** Каноническая форма: порядок полей не влияет на слепок. */
    private fun canonical(node: JsonNode): String = when {
        node.isObject -> node.properties().sortedBy { it.key }
            .joinToString(",", "{", "}") { (k, v) -> "\"$k\":${canonical(v)}" }
        node.isArray -> node.joinToString(",", "[", "]") { canonical(it) }
        node.isTextual -> "\"${node.asText()}\""
        node.isNull -> "null"
        else -> node.asText()
    }
}
