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

/** Шаблоны первой очереди: приложения 2–4 регламента БП-PA. */
enum class DocumentTemplate(
    val code: String,
    val title: String,
    /** Откуда взята структура разделов: документ и номер приложения. */
    val source: String,
    val sections: List<SectionTemplate>,
) {
    RequirementSpecification(
        "req_spec", "Спецификация требований", "БП-PA, Приложение 2",
        listOf(
            SectionTemplate(1, "Введение", "Назначение; область; применимые документы"),
            SectionTemplate(
                2, "Требования уровня проекта",
                "Цели миссии; ключевые показатели; программные ограничения",
            ),
            SectionTemplate(
                3, "Системные требования",
                "Функциональные; характеристик; интерфейсные; эксплуатационные; " +
                    "надёжности и безопасности; среды",
            ),
            SectionTemplate(
                4, "Матрица трассировки",
                "Двунаправленная трассировка: цель миссии ↔ требование ↔ элемент " +
                    "архитектуры ↔ метод верификации",
            ),
            SectionTemplate(
                5, "Матрица верификации",
                "Метод (испытание/анализ/демонстрация/инспекция) и этап верификации " +
                    "для каждого требования",
            ),
        ),
    ),
    ConOps(
        "conops", "Концепция применения (заготовка)", "БП-PA, Приложение 3",
        listOf(
            SectionTemplate(
                1, "Назначение и контекст",
                "Назначение системы; внешние системы; пользователи и операторы; допущения",
            ),
            SectionTemplate(
                2, "Архитектура операций",
                "Сегменты; распределение функций; потоки командования и данных",
            ),
            SectionTemplate(3, "Режимы и состояния", "Перечень режимов; условия и логика переходов"),
            SectionTemplate(
                4, "Операционные сценарии",
                "Номинальные сценарии по этапам полёта; нештатные и аварийные сценарии; " +
                    "сценарии завершения миссии",
            ),
            SectionTemplate(
                5, "Операционная среда и ограничения",
                "Орбитальная среда; связь; энергетика; планирование; наземная инфраструктура",
            ),
            SectionTemplate(6, "Персонал и обеспечение", "Состав смен; подготовка; средства поддержки"),
            SectionTemplate(
                7, "Валидационные положения",
                "Критерии, по которым ConOps используется для валидации требований и архитектуры",
            ),
        ),
    ),
    ArchitectureDescription(
        "architecture", "Описание архитектуры", "БП-PA, Приложение 4",
        listOf(
            SectionTemplate(
                1, "Архитектурный контекст",
                "Границы системы; внешние интерфейсы; драйверы архитектуры",
            ),
            SectionTemplate(
                2, "Функциональная архитектура",
                "Функциональная декомпозиция; распределение функций по элементам",
            ),
            SectionTemplate(
                3, "Физическая архитектура",
                "Сегменты, системы, подсистемы; WBS-привязка; MEL с резервами",
            ),
            SectionTemplate(
                4, "Интерфейсы",
                "Реестр внутренних и внешних интерфейсов; ответственность сторон",
            ),
            SectionTemplate(
                5, "Распределение требований",
                "Flow down системных требований на элементы; черновые спецификации подсистем",
            ),
            SectionTemplate(
                6, "Обоснование архитектуры",
                "Результаты trade studies; отклонённые альтернативы; чувствительность",
            ),
            SectionTemplate(
                7, "Бюджеты",
                "Массовый, энергетический, информационный, точностной бюджеты с резервами",
            ),
        ),
    ),
    ;

    companion object {
        fun of(code: String): DocumentTemplate = entries.firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("неизвестный шаблон документа: $code")
    }
}

/** Разрыв документа: раздел или запись, которую модель заполнить не может. */
data class DocumentGap(val section: Int, val what: String, val expected: String)

/** Документ: тело и слепок содержимого для сверки воспроизводимости. */
data class GeneratedDocument(
    val template: DocumentTemplate,
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
    fun render(model: JsonNode, template: DocumentTemplate): GeneratedDocument {
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
            // Раздел остаётся в документе пустым, но не молча: регламент
            // сказал, что в нём должно быть, — это и записывается разрывом.
            if (items.isEmpty) gaps += DocumentGap(s.number, "раздел пуст", s.expects)
        }

        // Плоский перечень записей документа: сохраняется для совместимости
        // с потребителями, читавшими документ до появления разделов.
        val items = body.putArray("items")
        sections.forEach { s -> s.path("items").forEach(items::add) }
        return GeneratedDocument(template, body, digestOf(body), gaps.toList())
    }

    private fun fill(
        template: DocumentTemplate,
        section: Int,
        model: JsonNode,
        items: ArrayNode,
        gaps: MutableList<DocumentGap>,
    ) = when (template) {
        DocumentTemplate.RequirementSpecification -> fillRequirementSpec(section, model, items, gaps)
        DocumentTemplate.ConOps -> fillConOps(section, model, items)
        DocumentTemplate.ArchitectureDescription -> fillArchitecture(section, model, items)
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
            1 -> model.path("project").takeIf { it.isObject && !it.isEmpty }?.let { p ->
                val n = items.addObject()
                n.put("purpose", p.path("purpose").asText(""))
                n.put("scope", p.path("scope").asText(""))
                val docs = n.putArray("applicable_documents")
                p.path("applicable_documents").forEach { docs.add(it.asText()) }
            }
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
