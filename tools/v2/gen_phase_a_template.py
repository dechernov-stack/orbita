#!/usr/bin/env python3
"""Шаблон фазы Phase A (PHT-9002) — из БП-PA и полки Романова, не из головы.

Задачи 1–11 `КОНТЕНТ-ЗАДАЧИ-PHASE-A.md` (О1–О17) становятся сценами A1–A10 и
цепочкой точек (внутренний обзор → SRR → SDR/MDR → KDP-B); мероприятия сцен и
дорожек — блоки стадии 2 схемы Романова (`ПОЛКА-ПРОЦЕССЫ-РОМАНОВ.json`, коды
2.x · 2.E* · 2.M* · 2.ME* · 2.P · 2.PE) с их входами и выходами; экспертизы
SRR/SDR — EXP-2A/EXP-2B полки с контрольными позициями.

Аванпроект рекурсивен (Романов, стадия 2): сцена A4 несёт `instantiate_per:
element` — движок разворачивает её в экземпляр на каждый элемент состава
(«Аванпроект элемента КА»), условия выхода параметризованы `{node}`.
Связи между сценами названы явно (`depends`: FS · SS · FF · INPUT) с
обоснованием — чем сцена держится, видно словами.

  python3 tools/v2/gen_phase_a_template.py            # записать шаблон
  python3 tools/v2/gen_phase_a_template.py --check    # сторож: не разошёлся
"""
import json
import pathlib
import sys

КОРЕНЬ = pathlib.Path(__file__).resolve().parent.parent.parent
ПОЛКА = КОРЕНЬ / "docs/tz/v2/ПОЛКА-ПРОЦЕССЫ-РОМАНОВ.json"
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

# Сцены A1–A10: задача БП-PA → роль, вопрос, шаги (заголовок — подсказка —
# место), условия, мероприятия Романова (коды полки), документы.
СЦЕНЫ = [
    {
        "key": "A1", "title": "Развёртывание фазы и SEMP", "role": "lead", "track": "design",
        "task": "О1–О2", "question": "Кто и как ведёт фазу: роли, план работ, SEMP по шаблону",
        "opens_kinds": ["plan", "gate"],
        "entry": [{"check": "gate_passed:KDP-A", "title": "KDP-A решён — фаза открыта"}],
        "exit": [
            {"check": "points_planned", "title": "точки фазы с датами"},
            {"check": "document_started:semp", "title": "SEMP начат по шаблону"},
        ],
        "steps": [
            {"title": "Орг-структура и план работ", "place": "scene:A1#plan", "hint": "роли по FA; реестр прав В3 — паспорт", "check": "points_planned"},
            {"title": "SEMP по шаблону", "place": "documents:semp", "hint": "11 разделов, tailoring с обоснованием — след обязателен", "check": "document_started:semp"},
            {"title": "Базировать до SRR", "place": "documents:semp#baseline", "hint": "переходом, DA не требуется", "check": "document_baselined:semp"},
        ],
        "activities": ["2.P"], "output": "план фазы и SEMP",
        "depends": [{"on": "KDP-A", "type": "FS", "why": "фаза открывается только решением KDP-A"}],
    },
    {
        "key": "A2", "title": "ConOps — базирование", "role": "lead_se", "track": "design",
        "task": "О3", "question": "Как система применяется: режимы, переходы, нештатные — и чем ConOps проверяет требования",
        "opens_kinds": ["scenario", "state_machine"],
        "entry": [{"check": "scene_started:A1", "title": "фаза развёрнута"}],
        "exit": [
            {"check": "scenarios_min:1", "title": "сценарии-цепочки доращены"},
            {"check": "document_complete:conops:SRR", "title": "ConOps полон к SRR"},
        ],
        "steps": [
            {"title": "Дорастить сценарии", "place": "documents:conops", "hint": "режимы и переходы; нештатные — из рисков", "check": "scenarios_min:1"},
            {"title": "Валидационные положения", "place": "documents:conops#7", "hint": "§7: чем ConOps проверяет требования"},
            {"title": "Базировать", "place": "documents:conops#baseline", "hint": "Baseline к SRR", "check": "document_baselined:conops"},
        ],
        "activities": ["2.2"], "output": "ConOps Baseline",
        "depends": [{"on": "A1", "type": "SS", "why": "ConOps дорастает параллельно развёртыванию; ждать SEMP незачем"}],
    },
    {
        "key": "A3", "title": "Иерархия требований", "role": "lead_se", "track": "design",
        "task": "О4", "question": "Какие системные требования выведены из проектных и чем каждое обосновано",
        "opens_kinds": ["requirement", "baseline"],
        "entry": [{"check": "scene_started:A2", "title": "ConOps в работе — есть чем проверять требования"}],
        "exit": [
            {"check": "system_requirements_min:1", "title": "системные требования заведены"},
            {"check": "each_system_requirement_derived", "title": "каждое системное несёт родителя (деривация с основанием)"},
            {"check": "no_suspect_links", "title": "подозрительных связей после правок нет"},
        ],
        "steps": [
            {"title": "Базировать проектный уровень", "place": "requirements#baseline", "hint": "TBD закрыть или обосновать", "check": "baseline_taken"},
            {"title": "Системные из проектных", "place": "requirements#derive", "hint": "каждое системное несёт родителя — деривация, вывод или уточнение с основанием", "check": "each_system_requirement_derived"},
            {"title": "Матрицы", "place": "requirements#matrices", "hint": "трассировка и верификация без дыр", "check": "no_suspect_links"},
        ],
        "activities": ["2.1", "2.3"], "output": "проектные и системные требования, Baseline на SRR",
        "depends": [{"on": "A2", "type": "SS", "why": "требования и ConOps растут вместе: сценарий рождает требование, требование проверяется сценарием"}],
    },
    {
        "key": "A4", "title": "Аванпроект элемента {node}", "role": "specialist", "track": "design",
        "task": "О5", "instantiate_per": "element",
        "question": "Из чего состоит элемент, какие системные требования на него легли и какими стыками он живёт",
        "opens_kinds": ["component", "component_usage", "interface", "parameter", "function", "exchange_item"],
        "entry": [{"check": "system_requirements_min:1", "title": "есть системные требования, которые нужно распределить"}],
        "exit": [
            {"check": "node_requirements_min:{node}:1", "title": "на элемент {node} распределено хотя бы одно системное требование"},
            {"check": "node_interfaces_min:{node}:1", "title": "у элемента {node} назван хотя бы один стык"},
            {"check": "carrier_nature_ok", "title": "носитель каждого требования той природы, что требует уровень"},
        ],
        "steps": [
            {"title": "Декомпозиция на подсистемы", "place": "concept#composition", "hint": "дерево носителей; вхождения ×N", "check": "composition_min:3"},
            {"title": "Распределение системных требований", "place": "requirements#carrier", "hint": "«без носителя» → 0", "check": "node_requirements_min:{node}:1"},
            {"title": "Интерфейсы", "place": "architecture#interfaces", "hint": "реестр внутренних/внешних, ответственность сторон", "check": "node_interfaces_min:{node}:1"},
            {"title": "Бюджеты", "place": "models#budgets", "hint": "масса/энергия/линк с резервами — свёртки"},
        ],
        "activities": ["2.E1", "2.E2", "2.E3", "2.E4", "2.E5"], "output": "аванпроект элемента: состав, требования, стыки, бюджеты",
        "depends": [
            {"on": "A3", "type": "INPUT", "why": "системные требования — вход распределения по элементам"},
            {"on": "A3", "type": "SS", "why": "распределение начинается, как только появились первые системные требования"},
        ],
    },
    {
        "key": "A5", "title": "Анализы и trade studies", "role": "lead_se", "track": "design",
        "task": "О6", "question": "Какой вариант принят и почему отклонённые отклонены",
        "opens_kinds": ["constellation_variant", "baseline_concept"],
        "entry": [{"check": "scene_started:A4", "title": "аванпроекты элементов начаты — есть что сравнивать"}],
        "exit": [{"check": "baseline_concept_chosen", "title": "базовый вариант назван с обоснованием и протоколом отклонённых"}],
        "steps": [
            {"title": "Сравнение вариантов", "place": "models#compare", "hint": "метрики без скрытого балла; Парето"},
            {"title": "Протоколы решений", "place": "concept#baseline", "hint": "отклонённое с причиной — в архитектурное обоснование", "check": "baseline_concept_chosen"},
        ],
        "activities": ["2.6", "2.7"], "output": "протоколы trade studies (Д5 §6)",
        "depends": [{"on": "A4", "type": "FF", "why": "сравнение закрывается вместе с аванпроектами: последний элемент меняет свёртку"}],
    },
    {
        "key": "A6", "title": "Созревание технологий", "role": "specialist", "track": "design",
        "task": "О7", "question": "Что ниже TRL 6 к PDR и как это дозреет",
        "opens_kinds": ["technology"],
        "entry": [{"check": "scene_started:A4", "title": "элементы названы — есть где искать критические технологии"}],
        "exit": [
            {"check": "technologies_named", "title": "критические технологии названы с TRL"},
            {"check": "maturation_planned", "title": "планы созревания с вехами"},
        ],
        "steps": [
            {"title": "Актуализовать перечень", "place": "library#trl", "hint": "критическое = ниже TRL 6 к PDR", "check": "technologies_named"},
            {"title": "Планы созревания", "place": "programmatics#maturation", "hint": "вехи и критерии достижения; резервные решения", "check": "maturation_planned"},
            {"title": "Переоценка TRL", "place": "library#trl", "hint": "периодичность по плану; отчёт к точкам"},
        ],
        "activities": ["2.T"], "output": "Д6 план созревания (Approved), отчёты TRL",
        "depends": [{"on": "A4", "type": "SS", "why": "технологии всплывают из аванпроектов элементов"}],
    },
    {
        "key": "A7", "title": "Риски", "role": "lead", "track": "management",
        "task": "О8", "question": "Какие риски актуальны, у кого владелец и к какой точке срок",
        "opens_kinds": ["risk"],
        "entry": [{"check": "scene_started:A1", "title": "фаза развёрнута"}],
        "exit": [
            {"check": "risks_min:1", "title": "реестр актуален"},
            {"check": "each_risk_has_due_point", "title": "у каждого риска — срок-точка"},
        ],
        "steps": [
            {"title": "План управления", "place": "risks#plan", "hint": "шкалы 1–5, пороги эскалации"},
            {"title": "Реестр актуален", "place": "risks", "hint": "условие—событие—последствие; владелец у каждого", "check": "each_risk_has_due_point"},
        ],
        "activities": ["2.P"], "output": "Д7 план (Preliminary) + живой реестр",
        "depends": [{"on": "A4", "type": "INPUT", "why": "риски элементов приходят из аванпроектов"}],
    },
    {
        "key": "A8", "title": "ОСЗ и планы SMA", "role": "specialist", "track": "design",
        "task": "О9", "question": "Актуальна ли оценка засорения и как классифицирована ПН",
        "opens_kinds": ["debris_assessment"],
        "entry": [{"check": "scene_started:A4", "title": "элементы названы — есть что уводить"}],
        "exit": [{"check": "oda_started", "title": "ОСЗ актуализована по нормативу полки"}],
        "steps": [
            {"title": "Актуализовать ОСЗ", "place": "models#debris", "hint": "увод по нормативу полки Б1; Δv в бюджете", "check": "oda_started"},
            {"title": "Предварительные планы SMA", "place": "documents", "hint": "классификация ПН"},
        ],
        "activities": ["2.6"], "output": "Д8 (Updated / Preliminary)",
        "depends": [{"on": "A4", "type": "INPUT", "why": "масса и орбита элементов — вход увода"}],
    },
    {
        "key": "A9", "title": "Программатика", "role": "lead", "track": "management",
        "task": "О10", "question": "Диапазоны стоимости и сроков к KDP-B и что в них не вошло",
        "opens_kinds": ["wbs_package", "cost_estimate"],
        "entry": [{"check": "scene_started:A4", "title": "состав элементов — основа WBS"}],
        "exit": [
            {"check": "estimate_ranged", "title": "диапазоны high/low с допущениями"},
            {"check": "estimate_includes_maturation", "title": "пакеты созревания включены"},
        ],
        "steps": [
            {"title": "Диапазоны стоимости и сроков", "place": "programmatics#estimate", "hint": "high/low, допущения явно", "check": "estimate_ranged"},
            {"title": "CADRe и закупки", "place": "programmatics#procurement", "hint": "партнёрства и длинные позиции"},
        ],
        "activities": ["2.P"], "output": "Д9 диапазоны к KDP-B, CADRe, материалы закупок",
        "depends": [{"on": "A6", "type": "INPUT", "why": "пакеты созревания — строки оценки"}],
    },
    {
        "key": "A10", "title": "Project Plan и обновление FA", "role": "lead", "track": "management",
        "task": "О11–О12", "question": "Собран ли план проекта из Д2–Д9 и обновлено ли FA планом Phase B",
        "opens_kinds": [],
        "entry": [{"check": "scene_done:A9", "title": "оценки готовы — план собирается из них"}],
        "exit": [{"check": "document_started:fa", "title": "FA обновлено планом Phase B"}],
        "steps": [
            {"title": "Project Plan по шаблону", "place": "documents", "hint": "10 разделов; ссылки, не копии"},
            {"title": "FA: план Phase B", "place": "documents:fa#4", "hint": "вехи и зрелости по прил. I", "check": "document_started:fa"},
        ],
        "activities": ["2.PE"], "output": "Д10 (Preliminary), Д1 проект обновлённого FA",
        "depends": [{"on": "A9", "type": "FS", "why": "план проекта не собирается раньше оценок"}],
    },
]

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
    {"template": "icd", "title": "Описание стыков (ICD)", "due": "SDR"},
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
]


def мероприятия_полки(полка: dict) -> dict:
    """Код → мероприятие стадии 2 с дорожкой и группой."""
    стадия = next(s for s in полка["stages"] if str(s.get("stage")) == "2")
    out = {}
    for дорожка, группы in стадия["tracks"].items():
        for г in группы:
            if "activities" in г:
                for a in г["activities"]:
                    out[a["code"]] = {**a, "track": дорожка, "group": г.get("group", "")}
            else:
                out[г["code"]] = {**г, "track": дорожка, "group": г.get("group", "")}
    return out, стадия


def мероприятие(код: str, полка: dict, сцена: dict | None, роль: str) -> dict:
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
        "surface": f"scene:{сцена['key']}" if сцена else "lane",
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


def собрать() -> dict:
    полка = json.loads(ПОЛКА.read_text(encoding="utf-8"))
    образец = json.loads(ОБРАЗЕЦ.read_text(encoding="utf-8"))
    мероприятия, стадия = мероприятия_полки(полка)
    сцены = []
    for i, с in enumerate(СЦЕНЫ, 1):
        сцены.append({
            "key": с["key"], "title": с["title"], "order": i, "track": с["track"], "role": с["role"],
            "task": с["task"], "question": с["question"], "opens_kinds": с["opens_kinds"],
            **({"instantiate_per": с["instantiate_per"]} if "instantiate_per" in с else {}),
            "entry": с["entry"], "exit": с["exit"], "steps": с["steps"], "output": с["output"],
            "depends": с.get("depends", []),
            "process_ref": [f"ROM-{k}" for k in с["activities"]],
            "activities": [мероприятие(k, мероприятия, с, с["role"]) for k in с["activities"]],
        })
    дорожки = []
    for д in ДОРОЖКИ:
        дорожка = {"key": д["key"], "title": д["title"], "of": д["of"], "activities": []}
        if д["of"] == "activities":
            роль = "specialist" if д["key"] == "modeling" else "lead"
            дорожка["activities"] = [мероприятие(k, мероприятия, None, роль) for k in д["codes"]]
        дорожки.append(дорожка)
    точки = [
        {
            "key": "internal_review_a", "title": "Внутренний обзор Phase A", "order": 1, "kind": "review",
            "offset_days": 60, "role": "lead", "checklist_of": "SRR",
            "aliases": [{"standard": "NASA-7120", "name": "internal review before SRR"}],
            "criteria": [
                {"check": "document_started:semp", "source_scene": "A1", "blocking": True, "title": "SEMP начат"},
                {"check": "system_requirements_min:1", "source_scene": "A3", "blocking": True, "title": "системные требования есть"},
                {"check": "findings_closed:internal_review_a", "blocking": True, "title": "замечания обзора закрыты"},
            ],
        },
        {
            "key": "SRR", "title": "SRR — обзор системных требований", "order": 2, "kind": "review",
            "offset_days": 120, "role": "da_review",
            "aliases": [{"standard": "NASA-7120", "name": "System Requirements Review"}, {"standard": "RK-11KT", "name": "экспертиза требований к системе (EXP-2A)"}],
            "criteria": [
                {"check": "gate_passed:internal_review_a", "blocking": True, "title": "внутренний обзор пройден"},
                {"check": "document_baselined:semp", "source_scene": "A1", "blocking": True, "title": "SEMP базирован"},
                {"check": "document_complete:conops:SRR", "source_scene": "A2", "blocking": True, "title": "ConOps полон к SRR"},
                {"check": "system_requirements_min:3", "source_scene": "A3", "blocking": True, "title": "системных требований не меньше трёх"},
                {"check": "each_system_requirement_derived", "source_scene": "A3", "blocking": True, "title": "каждое системное выведено из проектного"},
                {"check": "no_suspect_links", "source_scene": "A3", "blocking": True, "title": "подозрительных связей нет"},
                {"check": "risks_due_closed:SRR", "blocking": True, "title": "риски и TBR со сроком к SRR закрыты"},
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
                {"check": "scene_done:A4", "source_scene": "A4", "blocking": True, "title": "аванпроекты всех элементов прожиты"},
                {"check": "no_carrierless_requirement", "source_scene": "A4", "blocking": True, "title": "требований без носителя — 0"},
                {"check": "document_complete:icd:SDR", "source_scene": "A4", "blocking": True, "title": "ICD полон к SDR"},
                {"check": "document_complete:opscon:SDR", "source_scene": "A2", "blocking": True, "title": "OpsCon полон к SDR"},
                {"check": "baseline_concept_chosen", "source_scene": "A5", "blocking": True, "title": "базовый вариант выбран"},
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
                {"check": "scene_done:A9", "source_scene": "A9", "blocking": True, "title": "стоимость и сроки оценены диапазонами"},
                {"check": "scene_done:A10", "source_scene": "A10", "blocking": True, "title": "план проекта и FA обновлены"},
                {"check": "risks_due_closed:KDP-B", "blocking": True, "title": "риски со сроком к KDP-B закрыты"},
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
        "note": "Сцены A1–A10 — задачи 1–10 БП-PA (О1–О12); точки — задача 11 строгой цепочкой. "
                "Мероприятия — стадия 2 схемы Романова (аванпроект сегмента и элемента); "
                "сцена A4 разворачивается в экземпляр на каждый элемент состава (instantiate_per).",
        "correspondence": {
            "rk11kt": стадия.get("rk11kt"), "nasa": стадия.get("nasa"),
            "recursion": стадия.get("recursion"),
            "tasks": {с["key"]: с["task"] for с in СЦЕНЫ},
        },
        "roles": образец["roles"],
        "documents": ДОКУМЕНТЫ,
        "scenes": сцены,
        "points": точки,
        "stage_output_dataset": {
            "composition_depth": 4, "requirement_levels": ["project", "system"], "interfaces": True,
            "models": ["М1", "М3", "М4"], "documents": ["SEMP", "ConOps", "OpsCon", "ICD", "FA"],
            "plans_per_element": стадия["tracks"]["management"][1].get("outputs", []) if len(стадия["tracks"]["management"]) > 1 else [],
        },
        "lanes": дорожки,
        "maturity_note": образец.get("maturity_note", ""),
    }


def main() -> int:
    новый = json.dumps(собрать(), ensure_ascii=False, indent=2) + "\n"
    if "--check" in sys.argv:
        if not ШАБЛОН.exists() or ШАБЛОН.read_text(encoding="utf-8") != новый:
            print("шаблон Phase A разошёлся с генератором — python3 tools/v2/gen_phase_a_template.py")
            return 1
        d = json.loads(новый)
        print(f"шаблон Phase A: {len(d['scenes'])} сцен, {len(d['points'])} точки, "
              f"{sum(len(s['activities']) for s in d['scenes'])} мероприятий сцен, "
              f"{sum(len(l['activities']) for l in d['lanes'])} на дорожках — совпадает с генератором")
        return 0
    ШАБЛОН.write_text(новый, encoding="utf-8")
    d = json.loads(новый)
    print(f"записан {ШАБЛОН.name}: {len(d['scenes'])} сцен, {len(d['points'])} точки")
    return 0


if __name__ == "__main__":
    sys.exit(main())
