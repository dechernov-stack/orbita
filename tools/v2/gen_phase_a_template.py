#!/usr/bin/env python3
"""Шаблон фазы Phase A (PHT-9002) — из содержания владельца и полки Романова,
не из головы.

Сцены и условия выхода — `УСЛОВИЯ-СЦЕН-PHASE-A.json` (поставка 10.09,
`ШИП-G-ПРИНЯТ`): двенадцать сцен A1–A12, 44 условия `kind · min · where`,
каждое читает реестр, привязано к мероприятиям Романова и к точке фазы.
Условие владельца становится проверкой словаря готовности (таблица
ПРОВЕРКИ ниже — по одной на условие, в порядке поставки); само ожидание
(`kind · min · where`) остаётся в шаблоне полем `reads`, чтобы было видно,
что именно читается. Мероприятия — блоки схемы Романова по кодам поставки
(стадии 0–2, `ПОЛКА-ПРОЦЕССЫ-РОМАНОВ.json`); экспертизы SRR/SDR — EXP-2A /
EXP-2B полки; точки — цепочка внутренний обзор → SRR → SDR/MDR → KDP-B,
каждая ждёт сцены, что в неё пишут (`gate` у сцены поставки).

Аванпроект рекурсивен (Романов, стадия 2): сцена A4 несёт `instantiate_per:
element` — движок разворачивает её в экземпляр на каждый элемент состава,
условия параметризованы `{node}`. Связи сцен названы явно (`depends`: FS ·
SS · FF · INPUT) с обоснованием.

  python3 tools/v2/gen_phase_a_template.py            # записать шаблон
  python3 tools/v2/gen_phase_a_template.py --check    # сторож: не разошёлся
"""
import json
import pathlib
import sys

КОРЕНЬ = pathlib.Path(__file__).resolve().parent.parent.parent
ПОЛКА = КОРЕНЬ / "docs/tz/v2/ПОЛКА-ПРОЦЕССЫ-РОМАНОВ.json"
УСЛОВИЯ = КОРЕНЬ / "docs/tz/v2/поставка-09-10/УСЛОВИЯ-СЦЕН-PHASE-A.json"
ШАБЛОН = КОРЕНЬ / "docs/tz/v2/полки-порождённые/ШАБЛОН-ФАЗЫ-PHASE-A-NASA.json"
ОБРАЗЕЦ = КОРЕНЬ / "docs/tz/v2/полки-порождённые/ШАБЛОН-ФАЗЫ-PRE-A-NASA.json"

# Наш вид сущности для выходов мероприятий Романова — по имени выхода.
# Чего у нас нет (окружающая среда, документация на макеты) — выход без вида:
# он показывается словами, но условием не считается.
ВИД_ВЫХОДА = {
    "системные требования": ("requirement", 1),
    "обновлённые требования к системе": ("requirement", 1),
    "требования более низкого уровня": ("requirement", 1),
    "требования к интерфейсам": ("interface", 1),
    "спецификация системы": ("requirement", 1),
    "реализуемая концепция": ("baseline_concept", 1),
    "оптимальный проект системы": ("baseline_concept", 1),
    "критерии оценки": ("model_evaluation_criteria", 1),
    "поток требований": ("requirement", 1),
    "функциональная и структурная декомпозиция": ("component", 1),
    "описание физических интерфейсов": ("interface", 1),
    "инженерная проработка": ("parameter", 1),
    "стоимость/эффективность": ("cost_estimate", 1),
    "бюджет TPM": ("budget", 1),
    "планы управления программой/проектом": ("plan", 1),
    "план управления процессами системного инжиниринга": ("document:semp", 1),
    "план развития технологии": ("technology", 1),
    "план управления рисками": ("risk", 1),
}

# Условие владельца → проверка словаря готовности. Порядок — как в поставке;
# одно условие может читаться двумя проверками (список). `{node}` — узел
# экземпляра сцены A4. Условие «желательно» — не держит сцену (blocking False).
ПРОВЕРКИ = {
    "A1": ["phase_points_dated", "scene_responsible_min:1", "phase_plan_started"],
    "A2": ["document_started:semp", "document_complete:semp:SRR"],
    "A3": ["document_baselined:conops", "scenarios_min:3", "modes_defined"],
    "A4": [
        "node_requirements_min:{node}:1", "node_functions_min:{node}:1", "node_interfaces_min:{node}:1",
        "node_stage_closed:{node}:SDR", "node_exchange_items_min:{node}:1",
    ],
    "A5": [
        "each_segment_has_requirement", "no_carrierless_requirement:system", "each_system_requirement_derived",
        "functions_allocated", "chains_realized", "budgets_min:mass,power,link",
        {"check": "logical_components_deployed", "blocking": False},
    ],
    "A6": ["variants_min:2", "baseline_concept_with_rejected", "model_runs_verified:М1,М2а,М3а,М6", "document_section_started:icd:§6"],
    "A7": ["maturation_planned", "conditional_requirements_listed", "technology_fallback_named:4"],
    "A8": [["each_risk_has_owner", "each_risk_has_due_point"], "critical_risks_have_measures:12", "risks_due_closed:SRR"],
    "A9": ["oda_compliant", "document_started:sma", "oda_normative_bound"],
    "A10": [["estimate_ranged", "estimate_method:parametric,bottom_up"], "wbs_paired", "estimate_includes_maturation", "plan_consistent_with_gates"],
    "A11": ["document_started:projectplan", "document_version_min:fa:2", "document_section_started:fa:§6"],
    "A12": [
        "baseline_taken:functional", "baseline_taken:allocated",
        # EXP-2A на полке без позиций (ПМИ-5): помета, точку не держит; EXP-2B — держит
        [{"check": "expertise_positions_reviewed:SRR", "blocking": False}, "expertise_positions_reviewed:SDR"],
        "maturity_matrix_complete:KDP-B",
    ],
}

# Наше содержание сцен: роль, дорожка, вопрос, вход, шаги, что открывает,
# связи. Заголовок и условия выхода — из поставки.
СЦЕНЫ = {
    "A1": {
        "role": "lead", "track": "management",
        "question": "Кто и в какие сроки ведёт фазу: даты точек, ответственные сцен, окна работ",
        "opens_kinds": ["plan", "gate"],
        "entry": [{"check": "gate_passed:KDP-A", "title": "KDP-A решён — фаза открыта"}],
        "steps": [
            {"title": "Даты точек фазы", "place": "scene:A1#plan", "hint": "SRR · SDR · KDP-B — по FA", "check": "phase_points_dated"},
            {"title": "Ответственные сцен", "place": "scene:A1#plan", "hint": "РП и ведущий СИ — за каждой сценой лицо", "check": "scene_responsible_min:1"},
            {"title": "Окна сцен", "place": "scene:A1#plan", "hint": "план хотя бы у первой доступной сцены", "check": "phase_plan_started"},
        ],
        "output": "план фазы: даты точек, ответственные, окна сцен",
        "depends": [{"on": "KDP-A", "type": "FS", "why": "фаза открывается только решением KDP-A"}],
    },
    "A2": {
        "role": "lead", "track": "management",
        "question": "Как ведётся системная инженерия фазы: процессы, обзоры, инструменты, отклонения",
        "opens_kinds": ["document"],
        "entry": [{"check": "scene_started:A1", "title": "фаза развёрнута"}],
        "steps": [
            {"title": "SEMP по шаблону", "place": "documents:semp", "hint": "11 разделов; §6 — 17 процессов с полки, §2 — нормативы полки", "check": "document_started:semp"},
            {"title": "Полнота к SRR", "place": "documents:semp", "hint": "каждый раздел, ожидаемый к SRR, полон", "check": "document_complete:semp:SRR"},
            {"title": "Базировать до SRR", "place": "documents:semp#baseline", "hint": "переходом, DA не требуется", "check": "document_baselined:semp"},
        ],
        "output": "SEMP (Preliminary → Baseline к SRR)",
        "depends": [{"on": "A1", "type": "SS", "why": "SEMP пишется с первого дня фазы — ждать окончания развёртывания незачем"}],
    },
    "A3": {
        "role": "lead_se", "track": "design",
        "question": "Как система применяется: режимы, номинальные и нештатные сценарии, завершение миссии — и чем ConOps проверяет требования",
        "opens_kinds": ["scenario", "state_machine"],
        "entry": [{"check": "scene_started:A1", "title": "фаза развёрнута"}],
        "steps": [
            {"title": "Дорастить сценарии", "place": "documents:conops", "hint": "номинальный · два нештатных · завершение миссии", "check": "scenarios_min:3"},
            {"title": "Режимы аппарата", "place": "concept#modes", "hint": "машина режимов КА с переходами", "check": "modes_defined"},
            {"title": "Базировать", "place": "documents:conops#baseline", "hint": "после SEMP (2.3 → 3.3); Baseline к SRR", "check": "document_baselined:conops"},
        ],
        "output": "ConOps Baseline",
        "depends": [
            {"on": "A1", "type": "SS", "why": "сценарии дорастают параллельно развёртыванию"},
            {"on": "A2", "type": "FF", "why": "ConOps базируется после SEMP (2.3 → 3.3): порядок работы фазы задан планом СИ"},
        ],
    },
    "A4": {
        "role": "specialist", "track": "design",
        "question": "Из чего состоит элемент, какие системные требования и функции на него легли, какими стыками и обменами он живёт",
        "opens_kinds": ["component", "component_usage", "interface", "parameter", "function", "exchange_item"],
        "entry": [
            {"check": "scene_started:A3", "title": "ConOps в работе — есть чем проверять требования элемента"},
            {"check": "system_requirements_min:1", "title": "есть системные требования, которые нужно распределить"},
        ],
        "steps": [
            {"title": "Распределение системных требований", "place": "requirements#carrier", "hint": "«без носителя» → 0", "check": "node_requirements_min:{node}:1"},
            {"title": "Функции элемента", "place": "architecture#functions", "hint": "функции SA распределены на узел 100 %", "check": "node_functions_min:{node}:1"},
            {"title": "Стыки и элементы обмена", "place": "architecture#interfaces", "hint": "реестр стыков с параметрами ступени SRR; элементы обмена на стыках данных", "check": "node_interfaces_min:{node}:1"},
            {"title": "Ступень «архитектура»", "place": "concept#ladder", "hint": "анкета узла закрыта до SDR", "check": "node_stage_closed:{node}:SDR"},
        ],
        "output": "аванпроект элемента: состав, требования, функции, стыки, обмены",
        "depends": [
            {"on": "A5", "type": "INPUT", "why": "системные требования и функции — вход распределения по элементам"},
            {"on": "A5", "type": "SS", "why": "распределение начинается, как только появились первые системные требования"},
        ],
    },
    "A5": {
        "role": "lead_se", "track": "design",
        "question": "Какие системные требования выведены из проектных, на что распределены функции и цепочки, сходятся ли бюджеты",
        "opens_kinds": ["requirement", "function", "functional_chain", "budget", "logical_component"],
        "entry": [{"check": "scene_started:A3", "title": "ConOps в работе — сценарии рождают требования"}],
        "steps": [
            {"title": "Системные из проектных", "place": "requirements#derive", "hint": "каждое системное несёт родителя с обоснованием; ≥ 1 на сегмент", "check": "each_system_requirement_derived"},
            {"title": "Носители", "place": "requirements#carrier", "hint": "«без носителя (системные)» = 0", "check": "no_carrierless_requirement:system"},
            {"title": "Функции и цепочки", "place": "architecture#layers", "hint": "функций без узла = 0; у каждой цепочки сценарное требование", "check": "functions_allocated"},
            {"title": "Бюджеты", "place": "models#budgets", "hint": "масса · энергия · линк с двумя резервами против рамок", "check": "budgets_min:mass,power,link"},
        ],
        "output": "Д5 архитектура: требования, функции, цепочки, бюджеты; MCReport → SDR-пакет",
        "depends": [
            {"on": "A3", "type": "SS", "why": "требования и ConOps растут вместе: сценарий рождает требование, требование проверяется сценарием"},
            {"on": "A4", "type": "FF", "why": "архитектура закрывается вместе с аванпроектами: последний элемент меняет свёртку"},
        ],
    },
    "A6": {
        "role": "lead_se", "track": "design",
        "question": "Какой вариант принят, почему отклонённые отклонены и какими моделями это показано",
        "opens_kinds": ["constellation_variant", "baseline_concept", "model_run"],
        "entry": [{"check": "scene_started:A4", "title": "аванпроекты элементов начаты — есть что сравнивать"}],
        "steps": [
            {"title": "Варианты с метриками", "place": "models#compare", "hint": "≥ 2 варианта; метрики без скрытого балла", "check": "variants_min:2"},
            {"title": "Четыре модели", "place": "models", "hint": "М1 · М2а · М3а · М6 дали ответ и верифицированы", "check": "model_runs_verified:М1,М2а,М3а,М6"},
            {"title": "Протокол решения", "place": "documents:icd#6", "hint": "базовый с отклонёнными и обоснованием; протокол — вставкой сравнения", "check": "baseline_concept_with_rejected"},
        ],
        "output": "протоколы trade studies (Д5 §6), AoA",
        "depends": [{"on": "A4", "type": "FF", "why": "сравнение закрывается вместе с аванпроектами: последний элемент меняет свёртку"}],
    },
    "A7": {
        "role": "specialist", "track": "design",
        "question": "Что ниже требуемого TRL, как дозреет, что при этом условно и чем заменяется",
        "opens_kinds": ["technology"],
        "entry": [{"check": "scene_started:A4", "title": "элементы названы — есть где искать критические технологии"}],
        "steps": [
            {"title": "Пакет и веха на каждый разрыв", "place": "programmatics#maturation", "hint": "разрыв TRL требует пакета работ и вехи с датой", "check": "maturation_planned"},
            {"title": "Условные элементы", "place": "requirements#baseline", "hint": "перечислены снимком: условно то, что стоит на незрелой технологии", "check": "conditional_requirements_listed"},
            {"title": "Резервы", "place": "library#trl", "hint": "у технологии TRL ≤ 4 назван резерв", "check": "technology_fallback_named:4"},
        ],
        "output": "Д6 план созревания, перечень условных элементов, резервы",
        "depends": [{"on": "A4", "type": "SS", "why": "технологии всплывают из аванпроектов элементов"}],
    },
    "A8": {
        "role": "lead", "track": "management",
        "question": "Какие риски актуальны, у кого владелец, к какой точке срок и чем отвечают на критические",
        "opens_kinds": ["risk"],
        "entry": [{"check": "scene_started:A1", "title": "фаза развёрнута"}],
        "steps": [
            {"title": "Владелец и срок", "place": "risks", "hint": "у каждого риска — владелец и срок-точка", "check": "each_risk_has_owner"},
            {"title": "Меры по критическим", "place": "risks", "hint": "критичность ≥ 12 — мера обязательна", "check": "critical_risks_have_measures:12"},
            {"title": "Сроки к SRR", "place": "risks", "hint": "просроченных к SRR — 0", "check": "risks_due_closed:SRR"},
        ],
        "output": "Д7 реестр рисков",
        "depends": [{"on": "A4", "type": "INPUT", "why": "риски элементов приходят из аванпроектов"}],
    },
    "A9": {
        "role": "specialist", "track": "design",
        "question": "Соблюдён ли норматив увода в обоих случаях и заведены ли планы обеспечения",
        "opens_kinds": ["debris_assessment", "document"],
        "entry": [{"check": "scene_started:A4", "title": "элементы названы — есть что уводить"}],
        "steps": [
            {"title": "ОСЗ базового варианта", "place": "models#debris", "hint": "активный увод и пассивный сход — оба по нормативу", "check": "oda_compliant"},
            {"title": "Норматив привязан", "place": "models#debris", "hint": "норма увода — ссылка на полку нормативов", "check": "oda_normative_bound"},
            {"title": "План обеспечения", "place": "documents:sma", "hint": "надёжность · безопасность · EMI/EMC — черновик", "check": "document_started:sma"},
        ],
        "output": "Д8 ОСЗ · Д9 планы обеспечения (SMA)",
        "depends": [
            {"on": "A4", "type": "INPUT", "why": "масса и орбита элементов — вход увода"},
            {"on": "A6", "type": "INPUT", "why": "ОСЗ считается от базового варианта"},
        ],
    },
    "A10": {
        "role": "lead", "track": "management",
        "question": "Диапазоны стоимости и сроков к KDP-B, что в них вошло и сходятся ли сроки с точками",
        "opens_kinds": ["wbs_package", "cost_estimate"],
        "entry": [{"check": "scene_started:A4", "title": "состав элементов — основа WBS"}],
        "steps": [
            {"title": "Диапазон по WBS", "place": "programmatics#estimate", "hint": "параметрика или снизу вверх; допущения явно", "check": "estimate_ranged"},
            {"title": "Пары пакетов", "place": "programmatics#wbs", "hint": "пар нет только у сквозных", "check": "wbs_paired"},
            {"title": "Созревание в оценке", "place": "programmatics#estimate", "hint": "пакеты созревания — строки оценки", "check": "estimate_includes_maturation"},
            {"title": "Сроки против точек", "place": "programmatics#plan", "hint": "свёртка сроков WBS не выходит за даты точек", "check": "plan_consistent_with_gates"},
        ],
        "output": "Д9 оценка · Project Plan §3",
        "depends": [{"on": "A7", "type": "INPUT", "why": "пакеты созревания — строки оценки"}],
    },
    "A11": {
        "role": "lead", "track": "management",
        "question": "Собран ли план проекта из Д2–Д9 и обновлено ли FA планом Phase B с отклонениями",
        "opens_kinds": ["document"],
        "entry": [{"check": "scene_done:A10", "title": "оценки готовы — план собирается из них"}],
        "steps": [
            {"title": "Project Plan по шаблону", "place": "documents:projectplan", "hint": "ссылки на Д2–Д9, не копии", "check": "document_started:projectplan"},
            {"title": "FA: план Phase B", "place": "documents:fa#4", "hint": "вехи и зрелости по прил. I — новая версия FA", "check": "document_version_min:fa:2"},
            {"title": "Отклонения", "place": "documents:fa#6", "hint": "tailoring перечислен с обоснованием", "check": "document_section_started:fa:§6"},
        ],
        "output": "Д10 Project Plan (Preliminary), FA обновлённое",
        "depends": [{"on": "A10", "type": "FS", "why": "план проекта не собирается раньше оценок"}],
    },
    "A12": {
        "role": "lead", "track": "management",
        "question": "Готовы ли базовые линии и позиции экспертиз к точкам и полна ли матрица зрелости",
        "opens_kinds": ["baseline"],
        "entry": [{"check": "scene_started:A5", "title": "системные требования есть — есть что базировать"}],
        "steps": [
            {"title": "Функциональная базовая линия", "place": "requirements#baseline", "hint": "снимок к SRR", "check": "baseline_taken:functional"},
            {"title": "Распределённая базовая линия", "place": "requirements#baseline", "hint": "снимок к SDR", "check": "baseline_taken:allocated"},
            {"title": "Позиции экспертиз", "place": "points", "hint": "EXP-2A / EXP-2B — вердикт по каждой позиции", "check": "expertise_positions_reviewed:SRR"},
            {"title": "Матрица зрелости", "place": "points#KDP-B", "hint": "Д1–Д10 без дыр", "check": "maturity_matrix_complete:KDP-B"},
        ],
        "output": "пакеты точек SRR · SDR/MDR · KDP-B",
        "depends": [
            {"on": "A5", "type": "SS", "why": "функциональная линия берётся с первых системных требований"},
            {"on": "A11", "type": "FF", "why": "пакет KDP-B закрывается вместе с планом проекта"},
        ],
    },
}

# Дорожки схемы фазы: моделирование сегмента и элемента, управление.
ДОРОЖКИ = [
    {"key": "design", "title": "Проектирование", "of": "scenes"},
    {"key": "modeling", "title": "Моделирование", "of": "activities",
     "codes": ["2.M1", "2.M2", "2.M3", "2.M4", "2.M5", "2.M6", "2.M7", "2.M8", "2.ME1", "2.ME2", "2.ME3", "2.ME4", "2.ME5", "2.ME6"]},
    {"key": "management", "title": "Планирование и управление", "of": "activities", "codes": ["2.P", "2.PE"]},
]

ДОКУМЕНТЫ = [
    {"template": "semp", "title": "План управления системной инженерией (SEMP)", "due": "SRR"},
    {"template": "conops", "title": "Концепция применения (ConOps, Baseline)", "due": "SRR"},
    {"template": "opscon", "title": "Концепция операций (OpsCon)", "due": "SDR"},
    {"template": "icd", "title": "Описание стыков и архитектуры (ICD, Д5)", "due": "SDR"},
    {"template": "sma", "title": "План обеспечения (SMA: надёжность · безопасность · EMI/EMC)", "due": "SDR"},
    {"template": "projectplan", "title": "Project Plan (Д10)", "due": "KDP-B"},
    {"template": "fa", "title": "Соглашение о Формулировании (FA, план Phase B)", "due": "KDP-B"},
]

# Контрольные позиции экспертиз — на наши артефакты, где они есть.
НАША_ССЫЛКА = {
    "системные требования": "kind:requirement",
    "спецификация системы": "kind:requirement",
    "спецификации на проектирование": "kind:requirement",
    "требования к интерфейсам": "kind:interface",
    "матрица верификационных требований": "kind:requirement",
    "критерии приемлемости": "kind:model_evaluation_criteria",
    "инженерная продукция": "kind:component",
    "соблюдаемые стандарты": "kind:constraint",
    "план управления процессами системного инжиниринга": "document_template:semp",
    "программа инженерного управления системой (SEMP)": "document_template:semp",
    "концепция операций": "document_template:opscon",
    "план управления рисками": "kind:risk",
    "план развития технологии": "kind:technology",
    "интерфейсный контрольный документ": "document_template:icd",
    "план обеспечения надёжности": "document_template:sma",
    "план обеспечения безопасности системы": "document_template:sma",
    "план контроля EMI/EMC": "document_template:sma",
}

МАТРИЦА_KDP_B = [
    ("Д1 · Соглашение о Формулировании (FA, план Phase B)", "document_template:fa", {"internal_review_a": "°", "SRR": "°", "SDR": "°", "KDP-B": "Р"}),
    ("Д2 · SEMP", "document_template:semp", {"internal_review_a": "С", "SRR": "F", "SDR": "F", "KDP-B": "F"}),
    ("Д3 · Требования (проектные и системные)", "kind:requirement", {"internal_review_a": "С", "SRR": "Р", "SDR": "Р", "KDP-B": "Р"}),
    ("Д4 · ConOps", "document_template:conops", {"internal_review_a": "Р", "SRR": "F", "SDR": "F", "KDP-B": "F"}),
    ("Д4а · OpsCon", "document_template:opscon", {"internal_review_a": "°", "SRR": "С", "SDR": "Р", "KDP-B": "Р"}),
    ("Д5 · Описание архитектуры и ICD", "document_template:icd", {"internal_review_a": "°", "SRR": "С", "SDR": "Р", "KDP-B": "Р"}),
    ("Д6 · План созревания технологий", "kind:technology", {"internal_review_a": "°", "SRR": "С", "SDR": "Р", "KDP-B": "Р"}),
    ("Д7 · Реестр и план рисков", "kind:risk", {"internal_review_a": "С", "SRR": "Р", "SDR": "Р", "KDP-B": "Р"}),
    ("Д8 · ОСЗ и планы SMA", "kind:debris_assessment", {"internal_review_a": "°", "SRR": "°", "SDR": "С", "KDP-B": "Р"}),
    ("Д9 · Диапазоны стоимости и сроков", "kind:cost_estimate", {"internal_review_a": "°", "SRR": "°", "SDR": "С", "KDP-B": "Р"}),
    ("Д10 · Project Plan", "document_template:projectplan", {"internal_review_a": "°", "SRR": "°", "SDR": "°", "KDP-B": "Р"}),
]


def мероприятия_полки(полка: dict) -> tuple[dict, dict]:
    """Код → мероприятие любой стадии (0–2) с дорожкой и группой; стадия 2 отдельно."""
    out = {}
    стадия2 = None
    for s in полка["stages"]:
        if str(s.get("stage")) == "2":
            стадия2 = s
        for дорожка, группы in s["tracks"].items():
            for г in группы:
                if "activities" in г:
                    for a in г["activities"]:
                        out[a["code"]] = {**a, "track": дорожка, "group": г.get("group", ""), "stage": str(s.get("stage"))}
                else:
                    out[г["code"]] = {**г, "track": дорожка, "group": г.get("group", ""), "stage": str(s.get("stage"))}
    assert стадия2 is not None, "в полке Романова нет стадии 2"
    return out, стадия2


def мероприятие(код: str, полка: dict, сцена_ключ: str | None, роль: str) -> dict:
    a = полка[код]
    выходы = []
    for имя in a.get("outputs", []):
        вид = ВИД_ВЫХОДА.get(имя)
        выход = {"what": имя}
        if вид:
            k, минимум = вид
            if k.startswith("document:"):
                выход["document"] = k.split(":", 1)[1]
            else:
                выход["kind"] = k
                выход["min"] = минимум
        выходы.append(выход)
    return {
        "code": код, "name": a["name"], "goal": a.get("group") or a["name"],
        "role": роль, "track": a["track"], "method_group": a.get("group", ""),
        "inputs": [{"what": в} for в in a.get("inputs", [])],
        "outputs": выходы,
        "surface": f"scene:{сцена_ключ}" if сцена_ключ else "lane",
    }


def экспертиза(полка: dict, код: str) -> dict:
    """Полная экспертиза полки; если у полки только имя (EXP-2A) — имя и цель
    из короткой записи стадии: вопросы и позиции придут с поставкой, а не из головы."""
    э = next((e for e in полка["expertises_full"] if e["code"] == код), None)
    if э is None:
        стадия = next(s for s in полка["stages"] if str(s.get("stage")) == "2")
        краткая = next((e for e in стадия.get("expertises", []) if e.get("code") == код), {})
        э = {"goal": краткая.get("name", код), "questions": [], "results": [], "main_outcome": [], "control_items": []}
    return {
        "goal": э.get("goal", ""),
        "validation_questions": э.get("questions", []),
        "results": э.get("results", []),
        "main_outcome": э.get("main_outcome", []),
        "control_items": [
            {"artifact": c["artifact"], "maturity": c.get("maturity", "°"),
             **({"our_ref": НАША_ССЫЛКА[c["artifact"]]} if c["artifact"] in НАША_ССЫЛКА else {})}
            for c in э.get("control_items", [])
        ],
        "source": код,
    }


def условия_выхода(ключ: str, поставка: dict) -> list[dict]:
    """Условия владельца → проверки: по одной на условие, в порядке поставки;
    ожидание (`kind · min · where`) остаётся в шаблоне полем `reads`."""
    проверки = ПРОВЕРКИ[ключ]
    условия = поставка["exit_conditions"]
    assert len(проверки) == len(условия), f"{ключ}: проверок {len(проверки)}, условий поставки {len(условия)}"
    out = []
    for проверка, условие in zip(проверки, условия):
        варианты = проверка if isinstance(проверка, list) else [проверка]
        for в in варианты:
            держит = True
            имя = в
            if isinstance(в, dict):
                имя, держит = в["check"], в.get("blocking", True)
            out.append({
                "check": имя, "title": условие["note"],
                "reads": {"kind": условие["kind"], "min": условие["min"], "where": условие.get("where")},
                **({} if держит else {"blocking": False}),
            })
    return out


def собрать() -> dict:
    полка = json.loads(ПОЛКА.read_text(encoding="utf-8"))
    поставка = json.loads(УСЛОВИЯ.read_text(encoding="utf-8"))
    образец = json.loads(ОБРАЗЕЦ.read_text(encoding="utf-8"))
    мероприятия, стадия = мероприятия_полки(полка)
    сцены = []
    сцена_точки: dict[str, list[str]] = {}
    for i, п in enumerate(поставка["scenes"], 1):
        ключ = п["key"]
        с = СЦЕНЫ[ключ]
        коды = [k for k in п["romanov"] if k in мероприятия]
        заголовок = "Аванпроект элемента {node}" if п.get("instantiate_per") else п["title"]
        сцена_точки.setdefault(п["gate"], []).append(ключ)
        сцены.append({
            "key": ключ, "title": заголовок, "order": i, "track": с["track"], "role": с["role"],
            "gate": п["gate"], "writes_to": п.get("writes_to", []),
            "question": с["question"], "opens_kinds": с["opens_kinds"],
            **({"instantiate_per": п["instantiate_per"]} if п.get("instantiate_per") else {}),
            "entry": с["entry"], "exit": условия_выхода(ключ, п), "steps": с["steps"], "output": с["output"],
            "depends": с.get("depends", []),
            "process_ref": [f"ROM-{k}" if not k.startswith("EXP-") else k for k in п["romanov"]],
            "activities": [мероприятие(k, мероприятия, ключ, с["role"]) for k in коды],
        })
    дорожки = []
    for д in ДОРОЖКИ:
        дорожка = {"key": д["key"], "title": д["title"], "of": д["of"], "activities": []}
        if д["of"] == "activities":
            роль = "specialist" if д["key"] == "modeling" else "lead"
            дорожка["activities"] = [мероприятие(k, мероприятия, None, роль) for k in д["codes"]]
        дорожки.append(дорожка)

    def прожиты(точка: str) -> list[dict]:
        return [{"check": f"scene_done:{k}", "source_scene": k, "blocking": True,
                 "title": f"сцена {k} «{СЦЕНЫ_ЗАГОЛОВКИ[k]}» прожита"} for k in сцена_точки.get(точка, [])]

    СЦЕНЫ_ЗАГОЛОВКИ = {п["key"]: п["title"] for п in поставка["scenes"]}
    точки = [
        {
            "key": "internal_review_a", "title": "Внутренний обзор Phase A", "order": 1, "kind": "review",
            "offset_days": 60, "role": "lead", "checklist_of": "SRR",
            "aliases": [{"standard": "NASA-7120", "name": "internal review before SRR"}],
            "criteria": [
                {"check": "document_started:semp", "source_scene": "A2", "blocking": True, "title": "SEMP начат"},
                {"check": "system_requirements_min:1", "source_scene": "A5", "blocking": True, "title": "системные требования есть"},
                {"check": "findings_closed:internal_review_a", "blocking": True, "title": "замечания обзора закрыты"},
            ],
        },
        {
            "key": "SRR", "title": "SRR — обзор системных требований", "order": 2, "kind": "review",
            "offset_days": 120, "role": "da_review",
            "aliases": [{"standard": "NASA-7120", "name": "System Requirements Review"}, {"standard": "RK-11KT", "name": "экспертиза требований к системе (EXP-2A)"}],
            "criteria": [
                {"check": "gate_passed:internal_review_a", "blocking": True, "title": "внутренний обзор пройден"},
                *прожиты("SRR"),
                {"check": "system_requirements_min:3", "source_scene": "A5", "blocking": True, "title": "системных требований не меньше трёх"},
                {"check": "each_system_requirement_derived", "source_scene": "A5", "blocking": True, "title": "каждое системное выведено из проектного с обоснованием"},
                {"check": "no_suspect_links", "source_scene": "A5", "blocking": True, "title": "подозрительных связей нет"},
                {"check": "baseline_taken:functional", "source_scene": "A12", "blocking": True, "title": "функциональная базовая линия взята"},
                {"check": "risks_due_closed:SRR", "source_scene": "A8", "blocking": True, "title": "риски и TBR со сроком к SRR закрыты"},
                {"check": "assumptions_confirmed:SRR", "blocking": True, "title": "допущения к SRR подтверждены или сняты"},
                {"check": "findings_closed:SRR", "blocking": True, "title": "RFA и RID обзора закрыты"},
            ],
            "expertise": экспертиза(полка, "EXP-2A"),
        },
        {
            "key": "SDR", "title": "SDR/MDR — обзор облика системы", "order": 3, "kind": "review",
            "offset_days": 200, "role": "da_review",
            "aliases": [{"standard": "NASA-7120", "name": "System Definition Review / Mission Definition Review"}, {"standard": "RK-11KT", "name": "экспертиза облика системы (EXP-2B)"}],
            "criteria": [
                {"check": "gate_passed:SRR", "blocking": True, "title": "SRR пройден"},
                *прожиты("SDR"),
                {"check": "no_carrierless_requirement:system", "source_scene": "A5", "blocking": True, "title": "системных требований без носителя — 0"},
                {"check": "document_complete:icd:SDR", "source_scene": "A4", "blocking": True, "title": "ICD полон к SDR"},
                {"check": "document_complete:opscon:SDR", "source_scene": "A3", "blocking": True, "title": "OpsCon полон к SDR"},
                {"check": "baseline_taken:allocated", "source_scene": "A12", "blocking": True, "title": "распределённая базовая линия взята"},
                {"check": "findings_closed:SDR", "blocking": True, "title": "RFA и RID обзора закрыты"},
            ],
            "expertise": экспертиза(полка, "EXP-2B"),
        },
        {
            "key": "KDP-B", "title": "KDP-B — решение о переходе к Phase B", "order": 4, "kind": "phase",
            "offset_days": 240, "role": "da_review", "opens_phase": "Phase B",
            "aliases": [{"standard": "NASA-7120", "name": "Key Decision Point B"}, {"standard": "RK-11KT", "name": "решение о начале эскизного проекта"}],
            "criteria": [
                {"check": "gate_passed:SDR", "blocking": True, "title": "SDR пройден"},
                *прожиты("KDP-B"),
                {"check": "maturity_matrix_complete:KDP-B", "source_scene": "A12", "blocking": True, "title": "матрица зрелости Д1–Д10 без дыр"},
                {"check": "risks_due_closed:KDP-B", "source_scene": "A8", "blocking": True, "title": "риски со сроком к KDP-B закрыты"},
                {"check": "findings_closed:SDR", "blocking": True, "title": "замечания SDR закрыты"},
            ],
            "maturity_matrix": [
                {"artifact": a, "our_ref": r, "codes": c} for a, r, c in МАТРИЦА_KDP_B
            ],
        },
    ]
    return {
        "kind": "phase_template", "code": "PHT-9002", "standard": "NASA-7120", "phase": "Phase A",
        "title": "Phase A — концептуальная и технологическая проработка (аванпроект)",
        "note": "Сцены A1–A12 и 44 условия выхода — содержание владельца (УСЛОВИЯ-СЦЕН-PHASE-A, 10.09): "
                "каждое условие читает реестр (kind · min · where — поле reads), привязано к мероприятиям "
                "Романова и к точке фазы. Точки — цепочка внутренний обзор → SRR → SDR/MDR → KDP-B; "
                "сцена A4 разворачивается в экземпляр на каждый элемент состава (instantiate_per).",
        "source": {"conditions": поставка["code"], "rules": поставка.get("rules", [])},
        "correspondence": {
            "rk11kt": стадия.get("rk11kt"), "nasa": стадия.get("nasa"),
            "recursion": стадия.get("recursion"),
            "writes_to": {п["key"]: п.get("writes_to", []) for п in поставка["scenes"]},
        },
        "roles": образец["roles"],
        "documents": ДОКУМЕНТЫ,
        "scenes": сцены,
        "points": точки,
        "stage_output_dataset": {
            "composition_depth": 4, "requirement_levels": ["project", "system"], "interfaces": True,
            "models": ["М1", "М2а", "М3а", "М6"], "documents": ["SEMP", "ConOps", "OpsCon", "ICD", "SMA", "Project Plan", "FA"],
            "plans_per_element": стадия["tracks"]["management"][1].get("outputs", []) if len(стадия["tracks"]["management"]) > 1 else [],
        },
        "lanes": дорожки,
        "maturity_note": образец.get("maturity_note", ""),
    }


def main() -> int:
    новый = json.dumps(собрать(), ensure_ascii=False, indent=2) + "\n"
    d = json.loads(новый)
    условий = sum(len(s["exit"]) for s in d["scenes"])
    if "--check" in sys.argv:
        if not ШАБЛОН.exists() or ШАБЛОН.read_text(encoding="utf-8") != новый:
            print("шаблон Phase A разошёлся с генератором — python3 tools/v2/gen_phase_a_template.py")
            return 1
        print(f"шаблон Phase A: {len(d['scenes'])} сцен, {условий} проверок выхода из 44 условий поставки, {len(d['points'])} точки, "
              f"{sum(len(s['activities']) for s in d['scenes'])} мероприятий сцен, "
              f"{sum(len(l['activities']) for l in d['lanes'])} на дорожках — совпадает с генератором")
        return 0
    ШАБЛОН.write_text(новый, encoding="utf-8")
    print(f"записан {ШАБЛОН.name}: {len(d['scenes'])} сцен, {условий} проверок выхода, {len(d['points'])} точки")
    return 0


if __name__ == "__main__":
    sys.exit(main())
