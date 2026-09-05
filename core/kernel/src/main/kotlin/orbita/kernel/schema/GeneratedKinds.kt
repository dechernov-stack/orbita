// СГЕНЕРИРОВАНО tools/v2/gen_schemas.py из docs/tz/v2/СХЕМЫ-ПОЛЕЙ-V2.yaml — руками не править
//
// Перечень видов v2 — единственный законный источник для кода: вид, которого
// здесь нет, не существует (ТЗ-BACKEND §2.6). Слой и сцена рождения нужны
// сторожам: видимость по сцене и запрет зависимостей вверх по слоям.
package orbita.kernel.schema

/** Слой модели данных: L0 — полки, L5 — выпуск. */
enum class Layer { L0, L1, L2, L3, L4, L5 }

/**
 * Вид сущности v2.
 *
 * @property code машинный код вида — им же названа схема в schemas/v2
 * @property title имя вида по-русски: интерфейс показывает его, не код
 * @property layer слой; зависимости идут только вниз
 * @property bornIn сцена рождения; null — сущность полки, у неё сцены нет
 * @property statusModel статусная модель, если у вида она есть
 */
data class KindSpec(
    val code: String,
    val title: String,
    val layer: Layer,
    val bornIn: String?,
    val statusModel: String?,
    val requiredFields: List<String>,
)

object GeneratedKinds {

    val all: List<KindSpec> = listOf(
        KindSpec("unit", "единица измерения", Layer.L0, null, null, listOf("dimension", "symbol", "name", "canonical", "conversion_type")),
        KindSpec("glossary_term", "термин глоссария", Layer.L0, null, null, listOf("term_ru", "definition")),
        KindSpec("qos_class", "класс обслуживания", Layer.L0, null, null, listOf("code", "name", "latency_max", "guarantee")),
        KindSpec("method_catalog", "справочник методов и шкал", Layer.L0, null, null, listOf("catalog", "items")),
        KindSpec("normative_document", "норматив с редакцией", Layer.L0, null, null, listOf("designation", "title", "edition", "edition_date")),
        KindSpec("mission_class", "класс миссии", Layer.L0, null, null, listOf("code", "name", "recommended_shelves", "mandatory_models", "default_constraints")),
        KindSpec("phase_template", "шаблон фазы", Layer.L0, null, null, listOf("standard", "phase", "correspondence", "roles", "scenes", "points", "stage_output_dataset")),
        KindSpec("document_template", "шаблон документа", Layer.L0, null, null, listOf("code", "title", "sections", "nature")),
        KindSpec("pbs_template", "каркас PBS", Layer.L0, null, null, listOf("nodes")),
        KindSpec("interface_template", "типовые стыки", Layer.L0, null, null, listOf("interfaces")),
        KindSpec("architecture_template", "шаблон Arcadia", Layer.L0, null, null, listOf("actors", "capabilities", "functions", "exchanges", "chains")),
        KindSpec("wbs_template", "типовой WBS", Layer.L0, null, null, listOf("packages")),
        KindSpec("model_template", "набор моделей системы", Layer.L0, null, null, listOf("models")),
        KindSpec("typical_requirement", "типовые записи", Layer.L0, null, null, listOf("typical_requirement", "typical_risk", "stakeholder_profile")),
        KindSpec("typical_risk", "типовые записи", Layer.L0, null, null, listOf("typical_requirement", "typical_risk", "stakeholder_profile")),
        KindSpec("stakeholder_profile", "типовые записи", Layer.L0, null, null, listOf("typical_requirement", "typical_risk", "stakeholder_profile")),
        KindSpec("questionnaire", "анкета (шаблон запроса данных)", Layer.L0, null, null, listOf("code", "target_kind", "fields")),
        KindSpec("package_kind", "вид пакета службы", Layer.L0, null, null, listOf("code", "schema", "rules", "sources", "accepted_classes", "version")),
        KindSpec("site_catalog", "каталог площадок", Layer.L0, null, null, listOf("sites")),
        KindSpec("project", "проект", Layer.L1, "1", "active|cancelled", listOf("name", "mission_class", "manager", "phase_current", "group", "example")),
        KindSpec("phase", "фаза проекта", Layer.L1, "1 / KDP", "current|passed|returned", listOf("project", "template", "case_instance_id", "points", "opened_by")),
        KindSpec("scene", "сцена", Layer.L1, "по шаблону", "вычисляется: locked|available|active|done", listOf("phase", "template_key")),
        KindSpec("step", "шаг сцены", Layer.L1, "по шаблону", "вычисляется: done по условию", listOf("scene", "key")),
        KindSpec("gate", "точка / ворота", Layer.L1, "1", "planned|passed|returned", listOf("phase", "key", "title", "planned_date", "criteria", "kind")),
        KindSpec("criterion", "критерий ворот", Layer.L1, "по шаблону", "вычисляется: satisfied+reason", listOf("gate", "check", "source_scene", "blocking")),
        KindSpec("decision", "решение точки", Layer.L1, "16, 18, A12", null, listOf("gate", "by", "at", "outcome")),
        KindSpec("finding", "замечание RFA/RID", Layer.L1, "15–17, A12", "open|closed", listOf("gate", "text", "returns_to_scene", "author")),
        KindSpec("assignment", "задание", Layer.L1, "любая", "вычисляется: open|done (гаснет закрытием цели)", listOf("target", "assignee", "due_point", "assigned_by")),
        KindSpec("plan", "план дат", Layer.L1, "1, A1", null, listOf("phase", "gate_dates", "set_by")),
        KindSpec("account", "учётка · роль · право", Layer.L1, null, null, listOf("account", "role", "right")),
        KindSpec("role", "учётка · роль · право", Layer.L1, null, null, listOf("account", "role", "right")),
        KindSpec("right", "учётка · роль · право", Layer.L1, null, null, listOf("account", "role", "right")),
        KindSpec("history", "запись истории", Layer.L1, "всегда", null, listOf("object_ref", "version", "valid_from", "recorded_at", "actor", "action")),
        KindSpec("intent", "замысел", Layer.L2, "2", "draft|accepted", listOf("for_whom", "what", "where", "horizon", "accepted_by", "accepted_at")),
        KindSpec("material", "входной документ (материал)", Layer.L2, "2", null, listOf("type", "title", "file", "origin", "doc_version", "doc_date", "classification", "language")),
        KindSpec("proposal", "предложение службы / пакета", Layer.L2, "любая", "pending|closed", listOf("package_kind", "source", "items")),
        KindSpec("stakeholder", "стейкхолдер / актор", Layer.L2, "3", null, listOf("name", "role", "interest")),
        KindSpec("need", "нужда", Layer.L2, "3", null, listOf("statement", "stakeholder", "qos_class")),
        KindSpec("goal", "цель", Layer.L2, "4", null, listOf("statement", "measure", "year", "needs")),
        KindSpec("constraint", "ограничение Р", Layer.L2, "5", null, listOf("code", "type", "statement")),
        KindSpec("service", "сервис", Layer.L2, "6", null, listOf("name", "needs", "qos_class", "target_measure")),
        KindSpec("capability", "способность (OC)", Layer.L2, "6 / A", null, listOf("code", "name", "traced_to")),
        KindSpec("operational_activity", "операционная активность", Layer.L2, "9", null, listOf("name", "actor")),
        KindSpec("scenario", "сценарий операций = цепочка", Layer.L2, "9, A3", null, listOf("name", "layer", "steps")),
        KindSpec("requirement", "требование", Layer.L3, "8, 9, A4, A5", "Draft|Baseline", listOf("level", "code", "title", "statement", "category", "priority", "carrier", "verification_method", "source", "ears_pattern")),
        KindSpec("link", "связь (объект)", Layer.L3, "любая", null, listOf("type", "from", "to", "rationale", "created_by")),
        KindSpec("function", "функция", Layer.L3, "7, A5", null, listOf("code", "name", "layer", "allocated_to")),
        KindSpec("exchange", "обмен", Layer.L3, "A5", null, listOf("code", "name", "source_function", "target", "interface")),
        KindSpec("functional_chain", "функциональная цепочка", Layer.L3, "9, A3", null, listOf("code", "name", "steps")),
        KindSpec("logical_component", "логический компонент", Layer.L3, "A5", null, listOf("code", "name", "functions", "deployed_to")),
        KindSpec("component", "узел состава (определение)", Layer.L3, "7, A5", null, listOf("code", "name", "level", "nature", "kind")),
        KindSpec("component_usage", "вхождение ×N", Layer.L3, "7", null, listOf("definition", "quantity")),
        KindSpec("interface", "стык", Layer.L3, "7, A5", null, listOf("code", "name", "type", "a", "b", "direction", "requirement_classes")),
        KindSpec("model_element", "внешний элемент модели", Layer.L3, "A5", null, listOf("tool", "model_id", "uuid", "type", "layer", "name_snapshot", "refreshed_at", "fixture")),
        KindSpec("constellation_variant", "вариант построения", Layer.L3, "7, A6", null, listOf("name", "subgroups", "working")),
        KindSpec("baseline_concept", "базовая концепция", Layer.L3, "7", null, listOf("variant", "rationale", "rejected", "descopes", "decided_by", "at")),
        KindSpec("technology", "технология / TRL", Layer.L3, "10, A7", null, listOf("name", "component", "trl_current", "trl_required", "required_by")),
        KindSpec("risk", "риск", Layer.L3, "11, A8", "open|closed", listOf("statement", "cec", "category", "probability", "impact", "strategy", "measures", "owner", "due_point")),
        KindSpec("debris_assessment", "оценка засорения (ODA)", Layer.L3, "11, A9", null, listOf("variant", "lifetime_years", "deorbit_dv", "normative", "compliant", "model_run")),
        KindSpec("system_model", "запись модели", Layer.L4, "7, A6", "not_built|proxy|calc", listOf("template_code", "inputs", "verification_status")),
        KindSpec("model_run", "выход расчёта", Layer.L4, "при расчёте", null, listOf("model", "version", "inputs_snapshot", "outputs", "tool", "proxy", "at", "by")),
        KindSpec("metric", "метрика сравнения", Layer.L4, "7", null, listOf("variant", "code", "measure", "group")),
        KindSpec("budget", "бюджет / свёртка", Layer.L4, "7, A5", null, listOf("kind", "root", "reserve_policy")),
        KindSpec("verification_event", "событие верификации", Layer.L4, "A4+", null, listOf("requirement", "method", "result", "at", "by")),
        KindSpec("wbs_package", "пакет работ", Layer.L4, "7, 12, A10", null, listOf("code", "name", "cross_cutting")),
        KindSpec("cost_estimate", "оценка стоимости / сроков", Layer.L4, "12, A10", null, listOf("scope", "range", "assumptions", "method", "date")),
        KindSpec("data_request", "запрос данных (экземпляр анкеты)", Layer.L4, "7+", "вычисляется: maturity{required_now,missing}", listOf("questionnaire", "target", "fields")),
        KindSpec("document", "документ", Layer.L5, "9, 13, 14, A2, A3, A11", "Draft → Preliminary → Approved → Baseline (по baseline документа)", listOf("template", "code", "title", "sections", "owner", "nature")),
        KindSpec("section", "раздел", Layer.L5, "с документом", "вычисляется: completeness{done,total,missing[]} · support_changed{flag,diff[]}", listOf("document", "no", "title", "expects_snapshot")),
        KindSpec("release", "выпуск", Layer.L5, "13, A12", null, listOf("document", "rendering", "version", "print_files", "authors", "released_by", "at")),
        KindSpec("result", "результат в библиотеке", Layer.L5, "с выпуском", null, listOf("release", "authors")),
        KindSpec("baseline_snapshot", "снимок базирования", Layer.L5, "базирование", null, listOf("project", "sdoc_path", "hash", "by", "at", "requirements_count")),
        KindSpec("export", "выгрузка", Layer.L5, "по запросу", null, listOf("type", "scope", "fingerprint", "files", "by", "at")),
        KindSpec("import_batch", "импорт", Layer.L5, "2, A4", null, listOf("type", "source_file", "proposal", "foreign_attributes_kept")),
        KindSpec("ai_call", "вызов службы (журнал)", Layer.L5, "любая", null, listOf("package_kind", "outcome", "at", "by")),
        KindSpec("parameter", "значение параметра узла", Layer.L3, "7, A5+", "вычисляется: filled|missing|tbr", listOf("target", "key", "measure", "origin", "required_to", "maturity_class", "uncertainty")),
        KindSpec("baseline", "именованный снимок набора", Layer.L1, "8, A4, точки", "immutable", listOf("name", "kind", "gate", "items", "sdoc_path", "hash", "by", "at")),
        KindSpec("typical_component", "типовой компонент (библиотека)", Layer.L0, null, null, listOf("code", "name", "kind", "applicability", "nature", "role", "ladder")),
        KindSpec("exchange_item", "элемент обмена", Layer.L3, "A5", null, listOf("code", "name", "type", "elements")),
        KindSpec("data_type", "тип данных", Layer.L0, "A5", null, listOf("code", "name", "base")),
        KindSpec("state_machine", "режимы и состояния компонента", Layer.L3, "A5", null, listOf("owner", "states", "initial", "transitions")),
        KindSpec("configuration_item", "конфигурационная единица", Layer.L4, "A5", null, listOf("code", "component", "type", "responsible")),
        KindSpec("product_version", "версия изделия (не записи)", Layer.L4, "A7+", "mockup|flight_test|operations", listOf("ci", "version", "date", "composition")),
        KindSpec("intake_task", "задание загрузки", Layer.L2, "любая", "planned|awaiting_accept|executed|rejected", listOf("source", "snapshot", "intent", "intent_kind", "context", "plan", "author")),
        KindSpec("fact", "атомарный факт", Layer.L2, "загрузка", "disposition: free|noted|assumed|adopted|rejected|contested|superseded", listOf("kind", "subject", "predicate", "value", "source", "source_mark", "evidence", "confidence", "as_of", "disposition", "classification")),
        KindSpec("action", "действие плана загрузки", Layer.L2, "с intake_task", "proposed|accepted|rejected|done", listOf("task", "kind", "target", "preview", "facts", "decision")),
        KindSpec("topic", "тема (предмет фактов до разрешения в сущность)", Layer.L2, "загрузка", "open|resolved", listOf("label")),
        KindSpec("element", "элемент содержания раздела", Layer.L5, "по разделу", "active|removed", listOf("section", "kind", "author", "at", "order")),
        KindSpec("rendering", "порождённый читаемый текст версии документа", Layer.L5, "базирование/предпросмотр", "draft|reviewed|accepted|superseded", listOf("document", "sections", "prompt_fingerprint", "model")),
        KindSpec("process_catalog", "каталог процессов СИ (двухстандартный)", Layer.L0, null, null, listOf("code", "name", "group", "orbita_mechanism", "place", "tailoring_allowed")),
    )

    val byCode: Map<String, KindSpec> = all.associateBy { it.code }

    /** Вид вне перечня не существует: отказ вместо тихого пропуска. */
    fun of(code: String): KindSpec = byCode[code]
        ?: error("вид «$code» не описан в СХЕМЫ-ПОЛЕЙ-V2.yaml — вид вне истины схем не существует")
}
