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
    /** Все поля вида по истине YAML — правка на месте отбивает поле вне схемы (З-03). */
    val fields: List<String> = emptyList(),
)

object GeneratedKinds {

    val all: List<KindSpec> = listOf(
        KindSpec("unit", "единица измерения", Layer.L0, null, null, listOf("dimension", "symbol", "name", "canonical", "conversion_type"), listOf("dimension", "symbol", "name", "canonical", "factor", "conversion_type", "offset", "rate_date", "display_default")),
        KindSpec("glossary_term", "термин глоссария", Layer.L0, null, null, listOf("term_ru", "definition"), listOf("term_ru", "term_en", "definition", "source", "doc_type_brief", "default_take_to_prompt")),
        KindSpec("qos_class", "класс обслуживания", Layer.L0, null, null, listOf("code", "name", "latency_max", "guarantee"), listOf("code", "name", "latency_max", "guarantee")),
        KindSpec("method_catalog", "справочник методов и шкал", Layer.L0, null, null, listOf("catalog", "items"), listOf("catalog", "items")),
        KindSpec("normative_document", "норматив с редакцией", Layer.L0, null, null, listOf("designation", "title", "edition", "edition_date"), listOf("designation", "title", "edition", "edition_date", "valid_until", "clauses", "source_mark")),
        KindSpec("mission_class", "класс миссии", Layer.L0, null, null, listOf("code", "name", "recommended_shelves", "mandatory_models", "default_constraints"), listOf("code", "name", "recommended_shelves", "mandatory_models", "default_constraints", "prompt_context")),
        KindSpec("phase_template", "шаблон фазы", Layer.L0, null, null, listOf("standard", "phase", "correspondence", "roles", "scenes", "points", "stage_output_dataset"), listOf("standard", "phase", "correspondence", "roles", "scenes", "points", "stage_output_dataset")),
        KindSpec("document_template", "шаблон документа", Layer.L0, null, null, listOf("code", "title", "sections", "nature"), listOf("code", "title", "sections", "nature")),
        KindSpec("pbs_template", "каркас PBS", Layer.L0, null, null, listOf("nodes"), listOf("nodes")),
        KindSpec("interface_template", "типовые стыки", Layer.L0, null, null, listOf("interfaces"), listOf("interfaces")),
        KindSpec("architecture_template", "шаблон Arcadia", Layer.L0, null, null, listOf("actors", "capabilities", "functions", "exchanges", "chains"), listOf("actors", "capabilities", "activities", "functions", "exchanges", "chains", "logical_components")),
        KindSpec("wbs_template", "типовой WBS", Layer.L0, null, null, listOf("packages"), listOf("packages")),
        KindSpec("model_template", "набор моделей системы", Layer.L0, null, null, listOf("models"), listOf("models")),
        KindSpec("typical_requirement", "типовое требование (библиотека)", Layer.L0, null, null, listOf("code", "title", "ears_pattern", "statement_template", "category", "verification", "applicability", "version"), listOf("code", "title", "ears_pattern", "statement_template", "category", "measure", "verification", "acceptance_criteria", "applicability", "normative_basis", "version")),
        KindSpec("questionnaire", "анкета (шаблон запроса данных)", Layer.L0, null, null, listOf("code", "target_kind", "fields"), listOf("code", "target_kind", "fields", "kind_presets")),
        KindSpec("package_kind", "вид пакета службы", Layer.L0, null, null, listOf("code", "schema", "rules", "sources", "accepted_classes", "version"), listOf("code", "schema", "rules", "sources", "accepted_classes", "version")),
        KindSpec("site_catalog", "каталог площадок", Layer.L0, null, null, listOf("sites"), listOf("sites")),
        KindSpec("project", "проект", Layer.L1, "1", "active|cancelled", listOf("name", "mission_class", "manager", "phase_current", "group", "example"), listOf("name", "mission_class", "manager", "phase_current", "group", "example")),
        KindSpec("phase", "фаза проекта", Layer.L1, "1 / KDP", "current|passed|returned", listOf("project", "template", "case_instance_id", "points", "opened_by"), listOf("project", "template", "case_instance_id", "points", "opened_by")),
        KindSpec("scene", "сцена", Layer.L1, "по шаблону", "вычисляется: locked|available|active|done", listOf("phase", "template_key"), listOf("phase", "template_key", "responsible", "plan", "output_refs", "node")),
        KindSpec("step", "шаг сцены", Layer.L1, "по шаблону", "вычисляется: done по условию", listOf("scene", "key"), listOf("scene", "key", "responsible", "plan")),
        KindSpec("gate", "точка / ворота", Layer.L1, "1", "planned|passed|returned", listOf("phase", "key", "title", "planned_date", "criteria", "kind"), listOf("phase", "key", "title", "planned_date", "criteria", "decision", "kind", "aliases", "expertise")),
        KindSpec("criterion", "критерий ворот", Layer.L1, "по шаблону", "вычисляется: satisfied+reason", listOf("gate", "check", "source_scene", "blocking"), listOf("gate", "check", "source_scene", "blocking")),
        KindSpec("decision", "решение точки", Layer.L1, "16, 18, A12", null, listOf("gate", "by", "at", "outcome"), listOf("gate", "by", "at", "outcome", "conditions")),
        KindSpec("finding", "замечание RFA/RID", Layer.L1, "15–17, A12", "open|closed", listOf("gate", "text", "returns_to_scene", "author"), listOf("gate", "text", "returns_to_scene", "author", "assignee", "due_point")),
        KindSpec("assignment", "задание", Layer.L1, "любая", "вычисляется: open|done (гаснет закрытием цели)", listOf("target", "assignee", "due_point", "assigned_by"), listOf("target", "assignee", "due_point", "assigned_by")),
        KindSpec("plan", "план дат", Layer.L1, "1, A1", null, listOf("phase", "gate_dates", "set_by"), listOf("phase", "gate_dates", "scene_windows", "set_by")),
        KindSpec("account", "учётка", Layer.L1, null, "active|disabled", listOf("login", "name", "roles"), listOf("login", "name", "roles", "email")),
        KindSpec("history", "запись истории", Layer.L1, "всегда", null, listOf("object_ref", "version", "valid_from", "recorded_at", "actor", "action"), listOf("object_ref", "version", "valid_from", "valid_to", "recorded_at", "actor", "action", "basis")),
        KindSpec("intent", "замысел", Layer.L2, "2", "draft|accepted", listOf("for_whom", "what", "where", "horizon", "accepted_by", "accepted_at"), listOf("for_whom", "what", "where", "horizon", "anchors", "accepted_by", "accepted_at")),
        KindSpec("material", "входной документ (материал)", Layer.L2, "2", null, listOf("type", "title", "file", "origin", "doc_version", "doc_date", "classification", "language"), listOf("type", "title", "file", "origin", "doc_version", "doc_date", "valid_until", "classification", "source_marks", "language", "canon", "map", "harvest", "supersedes", "in_prompt_blocks")),
        KindSpec("proposal", "предложение службы / пакета", Layer.L2, "любая", "pending|closed", listOf("package_kind", "source", "items"), listOf("package_kind", "source", "items", "fingerprint")),
        KindSpec("stakeholder", "стейкхолдер / актор", Layer.L2, "3", null, listOf("name", "role", "interest"), listOf("name", "role", "interest", "scale", "establishes", "influence", "power", "attitude", "liaison", "normatives")),
        KindSpec("need", "нужда", Layer.L2, "3", null, listOf("statement", "stakeholder", "qos_class"), listOf("statement", "stakeholder", "qos_class", "anchors")),
        KindSpec("goal", "цель", Layer.L2, "4", null, listOf("statement", "measure", "year", "needs"), listOf("statement", "measure", "year", "needs")),
        KindSpec("constraint", "ограничение Р", Layer.L2, "5", null, listOf("code", "type", "statement"), listOf("code", "type", "statement", "cancelled", "bound")),
        KindSpec("service", "сервис", Layer.L2, "6", null, listOf("name", "needs", "qos_class", "target_measure"), listOf("name", "needs", "qos_class", "target_measure")),
        KindSpec("capability", "способность (OC)", Layer.L2, "6 / A", null, listOf("code", "name", "traced_to"), listOf("code", "name", "traced_to")),
        KindSpec("operational_activity", "операционная активность", Layer.L2, "9", null, listOf("name", "actor"), listOf("name", "actor", "description")),
        KindSpec("scenario", "сценарий операций = цепочка", Layer.L2, "9, A3", null, listOf("name", "layer", "steps"), listOf("name", "layer", "steps", "mode", "capability")),
        KindSpec("requirement", "требование", Layer.L3, "8, 9, A4, A5", "Draft|Baseline", listOf("level", "code", "title", "statement", "category", "priority", "carrier", "verification_method", "source", "ears_pattern"), listOf("level", "code", "title", "statement", "category", "priority", "measure", "carrier", "verification_method", "source", "acceptance_criteria", "normative_basis", "ears_pattern", "template_ref", "applicability", "deviation_rationale", "after_baseline_changed")),
        KindSpec("link", "связь (объект)", Layer.L3, "любая", null, listOf("type", "from", "to", "rationale", "created_by"), listOf("type", "subtype", "from", "to", "rationale", "created_by", "suspect", "confirmed_by", "confirmed_at", "revision_expected", "sensitivity")),
        KindSpec("function", "функция", Layer.L3, "7, A5", null, listOf("code", "name", "layer", "allocated_to"), listOf("code", "name", "layer", "allocated_to")),
        KindSpec("exchange", "обмен", Layer.L3, "A5", null, listOf("code", "name", "source_function", "target", "interface"), listOf("code", "name", "source_function", "target", "interface", "payload")),
        KindSpec("functional_chain", "функциональная цепочка", Layer.L3, "9, A3", null, listOf("code", "name", "steps"), listOf("code", "name", "steps", "ack_steps", "capability")),
        KindSpec("logical_component", "логический компонент", Layer.L3, "A5", null, listOf("code", "name", "functions", "deployed_to"), listOf("code", "name", "functions", "deployed_to")),
        KindSpec("component", "узел состава (определение)", Layer.L3, "7, A5", null, listOf("code", "name", "level", "nature", "kind"), listOf("code", "name", "parent", "level", "nature", "kind", "external", "external_identity", "wbs_pair", "template_ref", "applicability", "deviation_rationale", "structure_readonly", "standards", "maturity_tailoring")),
        KindSpec("component_usage", "вхождение ×N", Layer.L3, "7", null, listOf("definition", "quantity"), listOf("definition", "parent_usage", "quantity", "role", "orbit")),
        KindSpec("interface", "стык", Layer.L3, "7, A5", null, listOf("code", "name", "type", "a", "b", "direction", "requirement_classes"), listOf("code", "name", "type", "a", "b", "direction", "standard", "params", "icd_sections", "requirement_classes")),
        KindSpec("model_element", "внешний элемент модели", Layer.L3, "A5", null, listOf("tool", "model_id", "uuid", "type", "layer", "name_snapshot", "refreshed_at", "fixture"), listOf("tool", "model_id", "uuid", "type", "layer", "name_snapshot", "refreshed_at", "fixture")),
        KindSpec("constellation_variant", "вариант построения", Layer.L3, "7, A6", null, listOf("name", "subgroups", "working"), listOf("name", "subgroups", "working")),
        KindSpec("baseline_concept", "базовая концепция", Layer.L3, "7", null, listOf("variant", "rationale", "rejected", "descopes", "decided_by", "at"), listOf("variant", "rationale", "rejected", "descopes", "decided_by", "at")),
        KindSpec("technology", "технология / TRL", Layer.L3, "10, A7", null, listOf("name", "component", "trl_current", "trl_required", "required_by"), listOf("name", "component", "trl_current", "trl_required", "required_by", "gap_plan", "fallback", "maturation_package", "milestone", "fallback_component", "decision_criteria")),
        KindSpec("risk", "риск", Layer.L3, "11, A8", "open|closed", listOf("statement", "cec", "category", "probability", "impact", "strategy", "measures", "owner", "due_point"), listOf("statement", "cec", "category", "probability", "impact", "strategy", "measures", "owner", "due_point", "refs")),
        KindSpec("debris_assessment", "оценка засорения (ODA): активный увод и пассивный сход", Layer.L3, "11, A9", null, listOf("variant", "active_lifetime_years", "deorbit_dv", "passive_lifetime_years", "normative_active", "normative_passive", "compliant_active", "compliant_passive", "atmosphere_model", "ballistic_coefficient", "model_run"), listOf("variant", "active_lifetime_years", "deorbit_dv", "passive_lifetime_years", "normative_active", "normative_passive", "compliant_active", "compliant_passive", "atmosphere_model", "ballistic_coefficient", "model_run")),
        KindSpec("system_model", "запись модели", Layer.L4, "7, A6", "not_built|proxy|calc", listOf("template_code", "inputs", "verification_status"), listOf("template_code", "inputs", "interface_ref", "last_run", "verification_status", "evaluation_criteria")),
        KindSpec("model_run", "выход расчёта", Layer.L4, "при расчёте", null, listOf("model", "version", "inputs_snapshot", "outputs", "tool", "proxy", "at", "by"), listOf("model", "version", "inputs_snapshot", "outputs", "tool", "proxy", "at", "by")),
        KindSpec("metric", "метрика сравнения", Layer.L4, "7", null, listOf("variant", "code", "measure", "group"), listOf("variant", "code", "measure", "threshold", "group", "proxy")),
        KindSpec("budget", "бюджет / свёртка", Layer.L4, "7, A5", null, listOf("kind", "root", "reserve_policy"), listOf("kind", "root", "reserve_policy")),
        KindSpec("verification_event", "событие верификации", Layer.L4, "A4+", null, listOf("requirement", "method", "result", "at", "by"), listOf("requirement", "method", "result", "evidence", "at", "by")),
        KindSpec("wbs_package", "пакет работ", Layer.L4, "7, 12, A10", null, listOf("code", "name", "cross_cutting"), listOf("code", "name", "parent", "cross_cutting", "pbs_refs", "phase_scenes", "estimate")),
        KindSpec("cost_estimate", "оценка стоимости / сроков", Layer.L4, "12, A10", null, listOf("scope", "range", "assumptions", "method", "date"), listOf("scope", "range", "assumptions", "method", "date", "cadre", "includes_maturation")),
        KindSpec("data_request", "запрос данных (экземпляр анкеты)", Layer.L4, "7+", "вычисляется: maturity{required_now,missing}", listOf("questionnaire", "target", "fields"), listOf("questionnaire", "target", "fields")),
        KindSpec("document", "документ", Layer.L5, "9, 13, 14, A2, A3, A11", "Draft → Preliminary → Approved → Baseline (по baseline документа)", listOf("template", "code", "title", "sections", "owner", "nature"), listOf("template", "code", "title", "sections", "owner", "nature")),
        KindSpec("section", "раздел", Layer.L5, "с документом", "вычисляется: completeness{done,total,missing[]} · support_changed{flag,diff[]}", listOf("document", "no", "title", "expects_snapshot"), listOf("document", "no", "title", "elements", "expects_snapshot")),
        KindSpec("release", "выпуск", Layer.L5, "13, A12", null, listOf("document", "rendering", "version", "print_files", "authors", "released_by", "at"), listOf("document", "rendering", "version", "print_files", "authors", "released_by", "at")),
        KindSpec("result", "результат в библиотеке", Layer.L5, "с выпуском", null, listOf("release", "authors"), listOf("release", "authors", "generalize_to")),
        KindSpec("baseline_snapshot", "снимок базирования", Layer.L5, "базирование", null, listOf("project", "sdoc_path", "hash", "by", "at"), listOf("project", "sdoc_path", "hash", "by", "at", "requirements_count", "document", "tag", "commit", "elements")),
        KindSpec("export", "выгрузка", Layer.L5, "по запросу", null, listOf("type", "scope", "fingerprint", "files", "by", "at"), listOf("type", "scope", "fingerprint", "files", "by", "at", "reqif_profile")),
        KindSpec("import_batch", "импорт", Layer.L5, "2, A4", null, listOf("type", "source_file", "proposal", "foreign_attributes_kept"), listOf("type", "source_file", "proposal", "foreign_attributes_kept")),
        KindSpec("ai_call", "вызов службы (журнал)", Layer.L5, "любая", null, listOf("package_kind", "outcome", "at", "by"), listOf("package_kind", "model", "tokens_in", "tokens_out", "outcome", "reason", "fingerprint", "at", "by")),
        KindSpec("parameter", "значение параметра узла", Layer.L3, "7, A5+", "вычисляется: filled|missing|tbr", listOf("target", "key", "measure", "origin", "required_to", "maturity_class", "uncertainty"), listOf("target", "key", "measure", "origin", "source", "required_to", "basis", "maturity_class", "uncertainty")),
        KindSpec("baseline", "именованный снимок набора", Layer.L1, "8, A4, точки", "immutable", listOf("name", "kind", "gate", "items", "sdoc_path", "hash", "by", "at"), listOf("name", "kind", "gate", "items", "sdoc_path", "hash", "by", "at", "diff_from")),
        KindSpec("typical_component", "типовой компонент (библиотека)", Layer.L0, null, null, listOf("code", "name", "kind", "applicability", "nature", "role", "ladder"), listOf("code", "name", "kind", "applicability", "typical_requirements", "nature", "role", "ladder", "typical_functions", "typical_interfaces", "models", "standards", "verification", "typical_risks", "hard_facets")),
        KindSpec("exchange_item", "элемент обмена", Layer.L3, "A5", null, listOf("code", "name", "type", "elements"), listOf("code", "name", "type", "elements", "exchanges")),
        KindSpec("data_type", "тип данных", Layer.L0, "A5", null, listOf("code", "name", "base"), listOf("code", "name", "base", "unit", "range", "enum_values", "fields")),
        KindSpec("state_machine", "режимы и состояния компонента", Layer.L3, "A5", null, listOf("owner", "states", "initial", "transitions"), listOf("owner", "states", "initial", "transitions")),
        KindSpec("configuration_item", "конфигурационная единица", Layer.L4, "A5", null, listOf("code", "component", "type", "responsible"), listOf("code", "component", "type", "responsible")),
        KindSpec("product_version", "версия изделия (не записи)", Layer.L4, "A7+", "mockup|flight_test|operations", listOf("ci", "version", "date", "composition"), listOf("ci", "version", "date", "composition", "notes")),
        KindSpec("intake_task", "задание загрузки", Layer.L2, "любая", "planned|awaiting_accept|executed|rejected", listOf("source", "snapshot", "intent", "intent_kind", "context", "plan", "author"), listOf("source", "snapshot", "intent", "intent_kind", "context", "classification", "plan", "author")),
        KindSpec("fact", "атомарный факт", Layer.L2, "загрузка", "disposition: free|noted|assumed|adopted|rejected|contested|superseded", listOf("kind", "subject", "predicate", "value", "source", "source_mark", "evidence", "confidence", "as_of", "disposition", "classification"), listOf("kind", "entity_class", "subject", "predicate", "value", "source", "source_mark", "evidence", "confidence", "as_of", "valid_until", "disposition", "disposition_decision", "assumption", "classification")),
        KindSpec("action", "действие плана загрузки", Layer.L2, "с intake_task", "proposed|accepted|rejected|done", listOf("task", "kind", "target", "preview", "facts", "decision"), listOf("task", "kind", "target", "preview", "facts", "decision")),
        KindSpec("topic", "тема (предмет фактов до разрешения в сущность)", Layer.L2, "загрузка", "open|resolved", listOf("label"), listOf("label", "resolved_to", "scene")),
        KindSpec("element", "элемент содержания раздела", Layer.L5, "по разделу", "active|removed", listOf("section", "kind", "author", "at", "order"), listOf("section", "kind", "ref", "ref_version", "text", "supports", "query", "author", "at", "order")),
        KindSpec("rendering", "порождённый читаемый текст версии документа", Layer.L5, "базирование/предпросмотр", "draft|reviewed|accepted|superseded", listOf("document", "sections", "prompt_fingerprint", "model"), listOf("document", "baseline", "sections", "prompt_fingerprint", "model", "style_patches", "reviewer", "accepted_at")),
        KindSpec("process_catalog", "каталог процессов СИ (двухстандартный)", Layer.L0, null, null, listOf("code", "name", "group", "orbita_mechanism", "place", "tailoring_allowed"), listOf("code", "name", "npr_7123", "romanov_activity", "iso_15288", "group", "orbita_mechanism", "place", "tailoring_allowed")),
        KindSpec("typical_risk", "типовой риск (библиотека)", Layer.L0, null, null, listOf("code", "statement", "cec", "category", "probability", "impact", "strategy", "measures", "applicability"), listOf("code", "statement", "cec", "category", "probability", "impact", "strategy", "measures", "applicability")),
        KindSpec("stakeholder_profile", "профиль стейкхолдера (библиотека)", Layer.L0, null, null, listOf("code", "role", "name", "interests", "applicability"), listOf("code", "role", "name", "interests", "typical_needs", "applicability")),
        KindSpec("role", "роль", Layer.L1, null, null, listOf("code", "name", "rights"), listOf("code", "name", "rights", "phase_involvement")),
        KindSpec("right", "право", Layer.L1, null, null, listOf("code", "description", "scope"), listOf("code", "description", "scope")),
    )

    val byCode: Map<String, KindSpec> = all.associateBy { it.code }

    /** Вид вне перечня не существует: отказ вместо тихого пропуска. */
    fun of(code: String): KindSpec = byCode[code]
        ?: error("вид «$code» не описан в СХЕМЫ-ПОЛЕЙ-V2.yaml — вид вне истины схем не существует")
}
