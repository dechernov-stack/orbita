// Печать по-человечески (шип 0 задачи «три пакета», сверка SEMP с каноном:
// «вставки рендерятся сырыми парами kind: mission_intent; held: false —
// служебные ключи на латинице в продуктовом документе»).
//
// Запись вставки в печати — предложение или строка таблицы на русском:
// веха «SRR — 02.09.2026, не проведена», узел именем без id-префикса,
// нужда «Минтранс России (заказчик): …». Ключи полей переводятся по словарю,
// значения перечислений — по словарю значений, величины — числом с единицей
// показа. Ключ без перевода — не «как-нибудь», а брак печати: сторож
// `tools/validate_print_keys.py` сверяет словарь с генератором, а выпуск
// отказывает документу, в тексте которого остался латинский ключ.
package orbita.out

import com.fasterxml.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object PrintHumanizer {

    /** Единица показа: по умолчанию код как есть; граница модуля даёт справочник. */
    var unitLabel: (String) -> String = { it }

    /**
     * Подписи перечислений — ОБЩИМ справочником сервера (EnumLabels), тем же,
     * что кормит экраны: второй таблицы подписей печать не заводит. Ключ поля
     * подсказывает группу; словарь ниже — только для кодов, которых в
     * справочнике нет (виды записей документа, точки, фазы регламента).
     */
    var enumLabel: (String, String) -> String = { _, code -> code }

    private val GROUPS_BY_KEY: Map<String, List<String>> = mapOf(
        "role" to listOf("stakeholder_role"),
        "category" to listOf("requirement_category", "risk_category"),
        "level" to listOf("requirement_level", "verification_level"),
        "method" to listOf("verification_method"),
        "verification_method" to listOf("verification_method"),
        "status" to listOf("lifecycle", "verification_status", "risk_status"),
        "phase" to listOf("phase"),
        "strategy" to listOf("risk_strategy"),
        "segment" to listOf("segment"),
        "kind" to listOf("component_kind", "verification_kind", "evidence_kind", "product_kind"),
        "operator" to listOf("mop_operator"),
        "rollup" to listOf("mop_rollup"),
        "consumer_class" to listOf("consumer_class"),
        "review" to listOf("review"),
        "source" to listOf("provenance_source"),
    )

    private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    /** Поля записей вставок — по-русски. Ключ без подписи — брак печати. */
    val LABELS: Map<String, String> = mapOf(
        // ADR-044: аппарат в срезе — собранный из узла КА дерева состава
        "spacecraft" to "Модель аппарата",
        "id" to "обозначение", "statement" to "формулировка", "category" to "категория",
        "level" to "уровень", "source" to "источник", "rationale" to "обоснование",
        "mop" to "показатель", "verification_method" to "метод верификации", "status" to "статус",
        "version" to "версия", "owner" to "ответственный", "stakeholder" to "стейкхолдер",
        "role" to "роль", "name" to "наименование", "parent" to "входит в", "power_w" to "мощность, Вт",
        "orbit_fraction" to "доля витка", "kind" to "вид", "phase" to "фаза",
        "success_criterion" to "критерий успеха", "flow" to "ход", "walker" to "построение Уокера",
        "lat_deg" to "широта, °", "lon_deg" to "долгота, °", "target" to "цель", "method" to "метод",
        "approach" to "подход", "segment" to "сегмент", "wbs" to "элемент СРР", "owners" to "владельцы",
        "requirement" to "требование", "allocated_to" to "распределено на", "traces_up" to "восходит к",
        "event" to "событие", "closes" to "закрывает", "program_link" to "связь с программой",
        "moe" to "показатели эффективности", "gate" to "точка", "due" to "срок", "held" to "проведена",
        "computed" to "расчётная", "duration_days" to "длительность, дней", "basis" to "основание",
        "total_low" to "итого, нижняя", "total_high" to "итого, верхняя",
        "schedule_months_low" to "срок, мес., нижняя", "schedule_months_high" to "срок, мес., верхняя",
        "estimate" to "оценка", "wbs_ref" to "элемент СРР", "low" to "нижняя", "high" to "верхняя",
        "probability" to "вероятность", "impact" to "влияние", "strategy" to "стратегия",
        "project" to "проект", "program_links" to "связи с программой", "purpose" to "назначение",
        "scope" to "область", "applicable_documents" to "применимые документы",
        "computed_at" to "рассчитано", "scenario_ref" to "сценарий", "variants" to "варианты",
        "summary" to "суть", "criteria" to "критерии", "score" to "оценка", "code" to "код",
        "consequences" to "последствия", "question" to "вопрос", "selected" to "выбрано",
        "components" to "узлы", "trl_current" to "TRL сейчас", "trl_required" to "TRL требуемый",
        "maturation_plan" to "план созревания", "deorbit_years" to "срок схода, лет",
        "casualty_risk" to "риск поражения", "assessment" to "оценка", "rule" to "правило",
        "compliant" to "соответствует", "note" to "примечание", "for_whom" to "для кого",
        "what" to "что", "where" to "где", "horizon" to "горизонт", "text" to "текст",
        "interest" to "интерес", "supplies" to "поставляет", "process" to "процесс",
        "mechanism" to "механизм", "place" to "место", "check" to "проверка", "author" to "автор",
        "at" to "когда", "area" to "область работ", "tool" to "чем ведётся", "risk_ref" to "риск",
        "risks" to "рисков", "unit" to "единица", "nominal" to "номинал", "system_margin_pct" to "системный запас, %",
        "dry" to "сухая масса", "wet" to "полная масса", "reserve" to "резерв",
        "within_platform_range" to "в диапазоне платформы", "generated" to "выработка",
        "consumed" to "потребление", "planned_payload_duty" to "план нагрузки ПН",
        "allowed_payload_duty" to "допустимая нагрузка ПН", "battery_dod" to "глубина разряда АБ",
        "ok" to "в норме", "beacon_downlink_load" to "загрузка маяком", "beacon_period_s" to "период маяка, с",
        "current" to "текущее", "margin_pct" to "запас, %", "required_margin_pct" to "требуемый запас, %",
        "breached" to "нарушен", "value" to "значение", "operator" to "условие", "rollup" to "свёртка",
        "ref" to "ссылка", "component" to "узел", "provenance" to "происхождение",
        "consumer_class" to "класс потребителя", "review" to "обзор", "trend" to "тренд",
        "planes" to "плоскостей", "sats_per_plane" to "аппаратов в плоскости", "inclination_deg" to "наклонение, °",
        "altitude_km" to "высота, км", "phasing" to "фазирование", "total" to "всего",
        "template_code" to "шаблон", "section" to "раздел", "rows" to "строки",
        "number" to "№", "tailoring" to "отклонение", "open" to "открыто", "closed" to "закрыто",
        "stale" to "устарел", "rng_seed" to "зерно ГПСЧ", "input_versions" to "версии входов",
        "retransmission_ratio" to "доля повторных передач", "variant" to "вариант",
        // варианты сравнения (KPI прогона): §6 описания архитектуры
        "blind_transmission_losses" to "потери передачи вслепую", "buffer_overflow_losses" to "потери переполнения буфера",
        "carried_msgs" to "доставлено сообщений", "delivery_probability" to "вероятность доставки",
        "feeder_downlink" to "фидерная линия вниз", "isl" to "межспутниковая линия", "location" to "расположение",
        "mean_attempts" to "среднее число попыток", "offered_msgs" to "предложено сообщений",
        "onboard_buffer" to "бортовой буфер", "p_within_required" to "доля в требуемое время", "runs" to "прогонов",
        "user_uplink" to "линия вверх, пользователь", "utilization" to "загрузка", "wall_time_s" to "время счёта, с",
    )

    /** Значения перечислений — по-русски; неизвестное значение печатается как есть. */
    private val VALUES: Map<String, String> = mapOf(
        // статусы жизненного цикла
        "Draft" to "черновик", "Preliminary" to "предварительный", "Approved" to "утверждён",
        "Baseline" to "базирован", "Cancelled" to "отменён", "issued" to "выпущен",
        // уровни и категории требований
        "project" to "проектный", "system" to "системный", "element" to "элементный",
        "functional" to "функциональное", "performance" to "показатель", "interface" to "интерфейсное",
        "operational" to "эксплуатационное", "reliability" to "надёжность", "safety" to "безопасность",
        "environmental" to "среда", "constraint" to "ограничение",
        // верификация
        "test" to "испытание", "analysis" to "анализ", "demonstration" to "демонстрация",
        "inspection" to "инспекция", "planned" to "запланировано", "in_progress" to "в работе",
        "passed" to "пройдено", "failed" to "не пройдено", "waived" to "снято",
        "preliminary" to "предварительная", "qualification" to "квалификационная",
        "acceptance" to "приёмочная", "certification" to "сертификационная",
        "subsystem" to "подсистема", "end_to_end" to "сквозная",
        // роли стейкхолдеров
        "customer" to "заказчик", "regulator" to "регулятор", "operator" to "оператор",
        "consumer" to "потребитель", "supplier" to "поставщик", "partner" to "партнёр",
        "established" to "сложившийся участник",
        // виды
        "segment" to "сегмент", "assembly" to "сборка", "orbit" to "орбитальное построение",
        "ground_station" to "наземная станция", "mission_intent" to "замысел миссии",
        "goal" to "цель", "objective" to "задача", "rom" to "ROM-оценка", "range" to "диапазон",
        "option" to "вариант", "descope" to "сокращение объёма", "work" to "работа",
        "maturation" to "созревание", "component" to "узел", "wbs" to "элемент СРР",
        "initial" to "начальная", "updated" to "актуализированная", "mass" to "масса",
        "power" to "энергия", "data" to "данные", "tpm" to "технический показатель",
        "nominal" to "штатный", "off_nominal" to "нештатный", "contingency" to "аварийный",
        "disposal" to "утилизация", "deployment" to "развёртывание", "commissioning" to "ввод в строй",
        "operations" to "эксплуатация", "decommissioning" to "вывод из эксплуатации",
        "constellation_compare_table" to "таблица сравнения построений",
        "readiness_group" to "группа готовности", "review_trend" to "тренд замечаний",
        "intent_boundaries" to "границы замысла", "mission_class" to "класс миссии", "role" to "роль",
        // риски и решения
        "technical" to "технический", "cost" to "стоимость", "schedule" to "сроки",
        "mitigate" to "снижать", "accept" to "принять", "transfer" to "передать", "avoid" to "избежать",
        "open" to "открыт", "mitigating" to "снижается", "closed" to "закрыт", "accepted" to "принят",
        "decided" to "принято",
        // фазы и точки
        "pre_phase_a" to "Pre-Phase A", "phase_a" to "Phase A", "phase_b" to "Phase B",
        "PhaseA" to "Phase A", "PhaseB" to "Phase B", "PhaseC" to "Phase C", "PhaseD" to "Phase D",
        "PhaseE" to "Phase E", "space" to "космический", "ground" to "наземный", "user" to "пользовательский",
        // условия показателей
        "eq" to "=", "le" to "≤", "ge" to "≥", "lt" to "<", "gt" to ">", "tolerance" to "допуск",
        "sum" to "сумма", "max" to "максимум", "min" to "минимум", "none" to "без свёртки",
        "manual" to "рукой", "auto_root" to "автоматически", "full" to "полностью", "partial" to "частично",
        "A_prime" to "A′", "B_prime" to "B′", "C_prime" to "C′",
        "applied" to "применён", "deviation" to "отклонение", "not_applicable" to "неприменим",
    )

    /** Одна запись вставки — одной человеческой строкой. */
    fun line(item: JsonNode): String {
        milestone(item)?.let { return it }
        val id = item.path("id").asText("")
        val name = item.path("name").asText("")
        val statement = item.path("statement").asText("")
        // узел, стейкхолдер, станция — именем; id остаётся в подсказке экрана
        val head = when {
            statement.isNotBlank() -> {
                val кто = item.path("stakeholder").asText("")
                val роль = value("role", item.path("role").asText(""))
                val prefix = when {
                    кто.isNotBlank() && роль.isNotBlank() -> "$кто ($роль): "
                    кто.isNotBlank() -> "$кто: "
                    else -> ""
                }
                (if (id.isNotBlank()) "$id. " else "") + prefix + statement
            }
            name.isNotBlank() -> name
            else -> ""
        }
        val skip = setOf("id", "name", "statement", "stakeholder", "role", "provenance") +
            if (statement.isBlank() && name.isBlank()) emptySet() else setOf("kind").filter { item.path("kind").asText("") == "component" }
        val rest = item.properties()
            .filter { (k, v) -> k !in skip && !isEmpty(v) }
            .joinToString("; ") { (k, v) -> "${label(k)}: ${text(k, v)}" }
        return listOf(head, rest).filter { it.isNotBlank() }.joinToString(" — ")
    }

    /** Веха: «SRR — 02.09.2026, не проведена (расчётная)». */
    private fun milestone(item: JsonNode): String? {
        if (!item.has("gate") || !item.has("held")) return null
        val gate = item.path("gate").asText("")
        val due = item.path("due").asText("").let { d -> date(d) ?: "дата не задана" }
        val held = if (item.path("held").asBoolean(false)) "проведена" else "не проведена"
        val extra = listOfNotNull(
            item.path("phase").asText("").ifBlank { null }?.let { value("phase", it) },
            if (item.path("computed").asBoolean(false)) "дата расчётная" else null,
            item.path("duration_days").takeIf { it.isInt }?.let { "длительность ${it.asInt()} дн." },
        )
        return "$gate — $due, $held" + if (extra.isEmpty()) "" else " (${extra.joinToString(", ")})"
    }

    fun label(key: String): String = LABELS[key] ?: key

    private fun value(key: String, v: String): String {
        for (group in GROUPS_BY_KEY[key].orEmpty()) {
            val hit = enumLabel(group, v)
            if (hit != v) return hit
        }
        return VALUES[v] ?: v
    }

    private fun date(s: String): String? =
        runCatching { LocalDate.parse(s.take(10)).format(DATE) }.getOrNull()

    private fun isEmpty(v: JsonNode): Boolean =
        v.isNull || v.isMissingNode || (v.isTextual && v.asText().isBlank()) ||
            ((v.isArray || v.isObject) && v.isEmpty)

    /** Значение поля — человеческим текстом: даты, да/нет, величины, списки. */
    fun text(key: String, v: JsonNode): String = when {
        v.isBoolean -> if (v.asBoolean()) "да" else "нет"
        v.isTextual -> date(v.asText())?.takeIf { key in setOf("due", "at", "computed_at") } ?: value(key, v.asText())
        v.isNumber -> number(v)
        v.isArray -> v.joinToString(", ") { if (it.isObject) line(it) else text(key, it) }
        v.isObject && v.path("value").isNumber && v.path("unit").isTextual ->
            "${number(v.path("value"))} ${unitLabel(v.path("unit").asText())}"
        v.isObject && v.path("value").isTextual && v.path("unit").isTextual ->
            "${v.path("value").asText()} ${unitLabel(v.path("unit").asText())}"
        v.isObject -> v.properties()
            .filter { (k, x) -> k != "provenance" && !isEmpty(x) }
            .joinToString(", ") { (k, x) -> "${label(k)} ${text(k, x)}" }
        else -> ""
    }

    private fun number(v: JsonNode): String {
        if (v.isInt || v.isLong) return v.asText()
        val d = v.asDouble()
        return if (d == Math.floor(d) && Math.abs(d) < 1e9) d.toLong().toString()
        else String.format(java.util.Locale.ROOT, "%.4g", d).replace('.', ',')
    }

    // ---- сторож печати: латинский служебный ключ в тексте — отказ выпуска

    private val KEY = Regex("""(?<![\p{L}\p{N}_/.:])([a-z][a-z0-9_]{1,})\s*[:=]\s""")
    private val ALLOWED = setOf("http", "https", "mailto", "id")

    /** Служебные ключи, найденные в печатном тексте (пусто — чисто). */
    fun serviceKeys(lines: Iterable<String>): List<String> =
        lines.flatMap { l -> KEY.findAll(l).map { it.groupValues[1] } }
            .filter { it !in ALLOWED }
            .distinct()
}
