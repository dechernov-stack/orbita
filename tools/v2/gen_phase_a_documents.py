#!/usr/bin/env python3
"""Шаблоны документов Phase A — SEMP, OpsCon, ICD — в диалекте живого документа.

Источники: SEMP — БП-PA Прил. 1 (11 разделов, как в шаблоне первой версии
`data/library/document-templates.json` code `semp`), §6 читает полку
процессов СИ (17 строк), §2 — полку нормативов, §8 — свёртки бюджетов;
OpsCon — ДОКУМЕНТЫ-2-ПРОМЕЖУТОЧНЫЕ §6 (смены · процедуры · окна сеансов ·
нештатные регламенты); ICD стыка — из параметров и требований на стык
(ДОКУМЕНТЫ-2 §6, DT-9003). Стиль, рецензия и подписи — как у MCReport.

Раздел = запросы к реестру + тезисы человека, полнота — к ступени (SRR/SDR);
чего в реестре нет (журнал выпусков), сказано подсказкой тезиса.

  python3 tools/v2/gen_phase_a_documents.py            # записать шаблоны
  python3 tools/v2/gen_phase_a_documents.py --check    # сторож: не разошлись
"""
import json
import pathlib
import sys

КОРЕНЬ = pathlib.Path(__file__).resolve().parent.parent.parent
ПОЛКИ = КОРЕНЬ / "docs/tz/v2/полки-порождённые"
ОБРАЗЕЦ = ПОЛКИ / "ШАБЛОН-MCREPORT.json"


def q(code, title, select, columns, *, where=None, min_rows=1, area=None, expand=None):
    э = {"code": code, "kind": "query", "title": title, "select": select, "min_rows": min_rows,
         "columns": [{"title": t, "field": f} for t, f in columns]}
    if where:
        э["where"] = where
    if area:
        э["area"] = area
    if expand:
        э["expand"] = expand
    return э


def раздел(no, title, elements, expects, scenes=(), statements=0, hint=None, source=None):
    р = {"no": no, "title": title, "scenes": list(scenes), "elements": elements, "expects": expects}
    if source:
        р["source"] = source
    if statements:
        р["min_statements"] = statements
        р["statement_hint"] = hint or ""
    return р


def semp():
    return [
        раздел("§1", "Назначение и область применения",
               [q("1.1", "Проект", "project", [("Проект", "name"), ("Стандарт", "standard"), ("Класс миссии", "mission_class"), ("Фаза", "phase")])],
               "SRR", scenes=["A2"], statements=1, hint="зачем документ, к какой фазе относится, связь с FA", source="проект · FA"),
        раздел("§2", "Применимые документы",
               [q("2.1", "Нормативы полки", "normative_document",
                  [("Обозначение", "designation"), ("Название", "title"), ("Редакция", "edition_date"), ("Действует до", "valid_until")], area="library")],
               "SRR", scenes=["5"], statements=1, hint="как применён каждый норматив: класс миссии или рука", source="полка нормативов"),
        раздел("§3", "Техническое резюме",
               [q("3.1", "Замысел миссии", "intent", [("Для кого", "for_whom"), ("Что делает", "what"), ("Где", "where"), ("Горизонт", "horizon")]),
                q("3.2", "Цели миссии", "goal", [("Код", "code"), ("Цель", "statement"), ("Год", "year")])],
               "SRR", scenes=["2", "4"], statements=1, hint="замысел и цели связным текстом — главный связный раздел", source="замысел · цели"),
        раздел("§4", "Границы технического усилия", [], "SRR", scenes=["2"], statements=1,
               hint="что внутри и вне контроля команды — заготовка из замысла, рука инженера", source="тезисы"),
        раздел("§5", "Организация и подрядчики",
               [q("5.1", "Стороны миссии", "stakeholder", [("Код", "code"), ("Сторона", "name"), ("Роль", "role")])],
               "SRR", scenes=["3", "A2"], statements=1, hint="роли команды и права (В3); поставщики со связями на узлы", source="стейкхолдеры · роли"),
        раздел("§6", "Технические процессы (NPR 7123.1)",
               [q("6.1", "17 процессов: процесс → механизм «Орбиты» → место", "process_catalog",
                  [("Код", "code"), ("Процесс", "name"), ("Группа", "group"), ("Механизм «Орбиты»", "orbita_mechanism"), ("Место", "place"), ("Отклонение (tailoring)", "tailoring")],
                  area="library", expand="processes", min_rows=17)],
               "SRR", scenes=["A2"], statements=1, hint="вводный абзац: процессы реализации — вне области Формулирования, к Phase C/D", source="полка процессов СИ"),
        раздел("§7", "Технические обзоры",
               [q("7.1", "Точки фазы", "gate", [("Точка", "title"), ("Дата", "planned_date"), ("Состояние", "status")])],
               "SRR", scenes=["A1", "A2"], statements=1, hint="порядок обзоров: внутренний обзор → SRR → SDR/MDR → KDP-B", source="точки фазы"),
        раздел("§8", "Технические показатели (TPM)",
               [q("8.1", "Свёртки бюджетов", "budget", [("Бюджет", "kind"), ("Корень", "root"), ("Политика резерва", "reserve_policy")])],
               "SDR", scenes=["7", "A5"], statements=1, hint="запас массы и мощности из свёрток; тренды закрытия замечаний — числа из модели", source="бюджеты · замечания точек"),
        раздел("§9", "Отклонения (tailoring)",
               [q("9.1", "Типовые требования, применённые с отклонением", "requirement",
                  [("Код", "code"), ("Требование", "statement"), ("Применимость", "applicability"), ("Обоснование", "deviation_rationale")],
                  where={"applicability": "applied_with_deviation"}, min_rows=0),
                q("9.2", "Типовые требования, признанные неприменимыми", "requirement",
                  [("Код", "code"), ("Требование", "statement"), ("Обоснование", "deviation_rationale")],
                  where={"applicability": "not_applicable"}, min_rows=0)],
               "SDR", scenes=["8"], statements=1, hint="кто · когда · обоснование каждого отклонения; пометки процессов §6", source="требования с отклонением · §6"),
        раздел("§10", "Инструменты и среда",
               [q("10.1", "Среда инженерии", "process_catalog", [("Область", "area"), ("Инструмент", "tool")], area="library", expand="tools")],
               "SRR", scenes=["A2"], statements=1, hint="свои расчёты — фактом, Orekit — в резерве; без обещаний будущего", source="полка процессов СИ"),
        раздел("§11", "Согласование и актуализация",
               [q("11.1", "Решения точек", "decision", [("Точка", "gate"), ("Кем", "by"), ("Когда", "at"), ("Исход", "outcome")], min_rows=0)],
               "SRR", scenes=["A2"], statements=1, hint="выпуски документа: версия · дата · выпустивший; актуализация — фиксациями переходов", source="решения точек · базирования"),
    ]


def opscon():
    return [
        раздел("§1", "Назначение и режимы работы",
               [q("1.1", "Режимы и состояния компонентов", "state_machine", [("Компонент", "owner"), ("Состояния", "states"), ("Начальное", "initial")])],
               "SDR", scenes=["A3"], statements=1, hint="режимы применения и переходы между ними — словами оператора", source="режимы · сценарии"),
        раздел("§2", "Смены и процедуры",
               [q("2.1", "Сценарии операций", "scenario", [("Код", "code"), ("Сценарий", "name"), ("Слой", "layer"), ("Шаги", "steps")])],
               "SDR", scenes=["9", "A3"], statements=1, hint="смены операторов и процедуры по каждому сценарию", source="сценарии-цепочки"),
        раздел("§3", "Окна сеансов",
               [q("3.1", "Стыки радиолиний", "interface", [("Код", "code"), ("Стык", "name"), ("Тип", "type"), ("Направление", "direction")], where={"type": "rf"}, min_rows=0)],
               "SDR", scenes=["A4"], statements=1, hint="окна сеансов связи из баллистики; что делает оператор между окнами", source="стыки · модели"),
        раздел("§4", "Нештатные регламенты",
               [q("4.1", "Риски операций", "risk", [("Код", "code"), ("Риск", "statement"), ("Стратегия", "strategy"), ("Меры", "measures")])],
               "SDR", scenes=["11", "A8"], statements=1, hint="нештатные ситуации — из рисков: признак, действие оператора, эскалация", source="риски"),
        раздел("§5", "Валидационные положения", [], "SDR", scenes=["A3", "A5"], statements=1,
               hint="чем OpsCon проверяет требования: положение → требование → сценарий", source="тезисы · требования"),
    ]


def icd():
    return [
        раздел("§1", "Реестр стыков",
               [q("1.1", "Стыки", "interface", [("Код", "code"), ("Стык", "name"), ("Тип", "type"), ("Сторона A", "a"), ("Сторона B", "b"), ("Направление", "direction"), ("Классы требований", "requirement_classes")])],
               "SDR", scenes=["7", "A4"], statements=1, hint="назначение стыков и ответственность сторон", source="стыки"),
        раздел("§2", "Параметры стыков",
               [q("2.1", "Параметры на стыках", "parameter", [("Стык", "target"), ("Ключ", "key"), ("Значение", "measure"), ("Происхождение", "origin")], where={"target_kind": "interface"}, min_rows=0)],
               "SDR", scenes=["A4"], statements=0, source="параметры стыков"),
        раздел("§3", "Требования к стыкам",
               [q("3.1", "Требования с носителем-стыком", "requirement", [("Код", "code"), ("Стык", "carrier"), ("Требование", "statement"), ("Уровень", "level"), ("Верификация", "verification_method")], where={"carrier_kind": "interface"})],
               "SDR", scenes=["8", "A4"], statements=0, source="требования"),
        раздел("§4", "Элементы обмена",
               [q("4.1", "Элементы обмена", "exchange_item", [("Код", "code"), ("Элемент", "name"), ("Тип", "type"), ("Состав", "elements")], min_rows=0)],
               "SDR", scenes=["A4"], statements=0, source="элементы обмена"),
        раздел("§5", "Ответственность сторон", [], "SDR", scenes=["A4"], statements=1,
               hint="кто отвечает за каждую сторону стыка и за изменение его параметров", source="тезисы"),
        # Д5 §6 (условие A6, 10.09): протокол trade study — базовый вариант с
        # отклонёнными и обоснованием; протокол — вставкой сравнения вариантов.
        раздел("§6", "Протоколы решений (trade studies)",
               [q("6.1", "Базовый вариант и отклонённые", "baseline_concept", [("Вариант", "variant"), ("Обоснование", "rationale"), ("Отклонённые", "rejected"), ("Кем", "decided_by"), ("Когда", "at")], min_rows=0),
                q("6.2", "Сравниваемые варианты", "constellation_variant", [("Код", "code"), ("Вариант", "name"), ("Подгруппы", "subgroups")], min_rows=0)],
               "SDR", scenes=["7", "A6"], statements=1, hint="протокол решения: что сравнивали, по каким метрикам, почему отклонённые отклонены", source="варианты · базовый вариант"),
    ]


def sma():
    """План обеспечения (SMA): надёжность · безопасность · EMI/EMC — черновик к
    SDR (условие A9), полка Phase B; наполняется рисками и стыками."""
    return [
        раздел("§1", "Классификация полезной нагрузки и ОСЗ",
               [q("1.1", "Оценка засорения", "debris_assessment", [("Код", "code"), ("Вариант", "variant"), ("Активный увод, лет", "active_lifetime_years"), ("Пассивный сход, лет", "passive_lifetime_years"), ("Норма активного", "normative_active"), ("Норма пассивного", "normative_passive")])],
               "SDR", scenes=["11", "A9"], statements=1, hint="класс ПН по нормативу полки; что следует из ОСЗ для обеспечения", source="ОСЗ"),
        раздел("§2", "Обеспечение надёжности",
               [q("2.1", "Риски технические и безопасности", "risk", [("Код", "code"), ("Риск", "statement"), ("Категория", "category"), ("Меры", "measures"), ("Владелец", "owner")], where={"category": "technical"}, min_rows=0)],
               "SDR", scenes=["A8", "A9"], statements=1, hint="программа надёжности: резервирование, FDIR, испытания — по рискам", source="риски"),
        раздел("§3", "Безопасность системы",
               [q("3.1", "Риски безопасности", "risk", [("Код", "code"), ("Риск", "statement"), ("Стратегия", "strategy"), ("Меры", "measures")], where={"category": "safety"}, min_rows=0)],
               "SDR", scenes=["A8", "A9"], statements=1, hint="опасности и меры: увод, пассивация, наземная безопасность", source="риски"),
        раздел("§4", "EMI/EMC и стыки",
               [q("4.1", "Радиостыки", "interface", [("Код", "code"), ("Стык", "name"), ("Тип", "type"), ("Стандарт", "standard")], where={"type": "rf"}, min_rows=0)],
               "SDR", scenes=["A4", "A9"], statements=1, hint="план контроля ЭМС по радиостыкам: диапазоны, совместимость, испытания", source="стыки"),
    ]


def projectplan():
    """Project Plan (Д10, условие A11): собирается ССЫЛКАМИ на Д2–Д9, не
    копиями — разделы читают те же реестры, что и документы-источники."""
    return [
        раздел("§1", "Проект и его цели",
               [q("1.1", "Проект", "project", [("Проект", "name"), ("Стандарт", "standard"), ("Фаза", "phase")]),
                q("1.2", "Цели миссии", "goal", [("Код", "code"), ("Цель", "statement"), ("Год", "year")])],
               "KDP-B", scenes=["1", "4"], statements=1, hint="назначение плана; ссылка на FA (Д1)", source="проект · цели"),
        раздел("§2", "Организация и управление (→ SEMP, Д2)",
               [q("2.1", "Стороны и роли", "stakeholder", [("Код", "code"), ("Сторона", "name"), ("Роль", "role")])],
               "KDP-B", scenes=["3", "A2"], statements=1, hint="ссылка на SEMP: процессы и обзоры не переписываются", source="стейкхолдеры · SEMP"),
        раздел("§3", "Стоимость и сроки (→ Д9)",
               [q("3.1", "Оценки", "cost_estimate", [("Код", "code"), ("Метод", "method"), ("Диапазон", "range"), ("Допущения", "assumptions")]),
                q("3.2", "Точки фазы", "gate", [("Точка", "title"), ("Дата", "planned_date"), ("Состояние", "status")])],
               "KDP-B", scenes=["A10", "A1"], statements=1, hint="диапазон стоимости и вехи — ссылками на оценку и план фазы", source="оценки · точки"),
        раздел("§4", "Технические требования и архитектура (→ Д3, Д5)",
               [q("4.1", "Системные требования", "requirement", [("Код", "code"), ("Требование", "statement"), ("Носитель", "carrier")], where={"level": "system"}, min_rows=0)],
               "KDP-B", scenes=["A5"], statements=1, hint="ссылка на реестр требований и ICD; здесь — только объём", source="требования"),
        раздел("§5", "Технологии (→ Д6)",
               [q("5.1", "Критические технологии", "technology", [("Код", "code"), ("Технология", "name"), ("TRL", "trl_current"), ("Нужен TRL", "trl_required"), ("Резерв", "fallback")])],
               "KDP-B", scenes=["A7"], statements=1, hint="план созревания — ссылкой; здесь итог и резервы", source="технологии"),
        раздел("§6", "Риски (→ Д7)",
               [q("6.1", "Реестр рисков", "risk", [("Код", "code"), ("Риск", "statement"), ("Владелец", "owner"), ("Срок", "due_point")])],
               "KDP-B", scenes=["A8"], statements=1, hint="ссылка на реестр; здесь — верхние риски проекта", source="риски"),
        раздел("§7", "Обеспечение и ОСЗ (→ Д8, SMA)",
               [q("7.1", "Оценка засорения", "debris_assessment", [("Код", "code"), ("Вариант", "variant"), ("Активный увод", "compliant_active"), ("Пассивный сход", "compliant_passive")])],
               "KDP-B", scenes=["A9"], statements=1, hint="ссылка на ОСЗ и план обеспечения", source="ОСЗ · SMA"),
        раздел("§8", "Работы и WBS",
               [q("8.1", "Пакеты работ", "wbs_package", [("Код", "code"), ("Пакет", "name"), ("Узлы", "pbs_refs"), ("Сквозной", "cross_cutting")])],
               "KDP-B", scenes=["12", "A10"], statements=1, hint="состав работ Phase B — пакетами; сроки — из плана пакетов", source="WBS"),
        раздел("§9", "Решения и допущения",
               [q("9.1", "Решения точек", "decision", [("Точка", "gate"), ("Кем", "by"), ("Когда", "at"), ("Исход", "outcome")], min_rows=0)],
               "KDP-B", scenes=["A12"], statements=1, hint="решения точек фазы и допущения, на которых стоит план", source="решения точек · допущения"),
        раздел("§10", "Согласование", [], "KDP-B", scenes=["A11"], statements=1,
               hint="кто утверждает план и когда он актуализируется", source="тезисы"),
    ]


def шаблон(code, title, standard, sections, note):
    образец = json.loads(ОБРАЗЕЦ.read_text(encoding="utf-8"))
    return {
        "kind": "document_template", "code": code, "title": title, "standard": standard,
        "note": note + " Порождён генератором tools/v2/gen_phase_a_documents.py; правки — в генератор, не сюда.",
        "style": образец["style"], "review": образец["review"], "labels": образец["labels"],
        "sections": sections,
    }


def все() -> dict:
    return {
        "ШАБЛОН-SEMP.json": шаблон("semp", "План управления системной инженерией (SEMP)", "БП-PA, приложение 1 (NPR 7123.1 App. J)", semp(),
                                   "11 разделов; §6 — 17 процессов с полки процессов СИ; §2 — полка нормативов; §8 — свёртки бюджетов. Baseline до SRR."),
        "ШАБЛОН-OPSCON.json": шаблон("opscon", "Концепция операций (OpsCon)", "ДОКУМЕНТЫ-2-ПРОМЕЖУТОЧНЫЕ §6 (детализация ConOps для операторов, Phase A)", opscon(),
                                     "Смены · процедуры · окна сеансов · нештатные регламенты — из режимов, сценариев, стыков и рисков; полнота к SDR."),
        "ШАБЛОН-ICD.json": шаблон("icd", "Описание стыков и архитектуры (ICD, Д5)", "ДОКУМЕНТЫ-2-ПРОМЕЖУТОЧНЫЕ §6 (DT-9003)", icd(),
                                  "ICD собирается из стыков, их параметров и требований с носителем-стыком; §6 — протоколы trade studies (Д5 §6); полнота к SDR."),
        "ШАБЛОН-SMA.json": шаблон("sma", "План обеспечения (SMA: надёжность · безопасность · EMI/EMC)", "УСЛОВИЯ-СЦЕН-PHASE-A A9 (10.09); NPR 7120.5 App. I, Д9 планы обеспечения", sma(),
                                  "Черновик к SDR из ОСЗ, рисков и радиостыков; полка Phase B."),
        "ШАБЛОН-PROJECTPLAN.json": шаблон("projectplan", "Project Plan (Д10)", "УСЛОВИЯ-СЦЕН-PHASE-A A11 (10.09); NPR 7120.5 Project Plan", projectplan(),
                                          "Собирается ссылками на Д2–Д9, не копиями; Preliminary к KDP-B."),
    }


def main() -> int:
    ожидаемые = {имя: json.dumps(д, ensure_ascii=False, indent=2) + "\n" for имя, д in все().items()}
    if "--check" in sys.argv:
        разошлись = [имя for имя, текст in ожидаемые.items() if not (ПОЛКИ / имя).exists() or (ПОЛКИ / имя).read_text(encoding="utf-8") != текст]
        if разошлись:
            print("шаблоны Phase A разошлись с генератором: " + ", ".join(разошлись) + " — python3 tools/v2/gen_phase_a_documents.py")
            return 1
        print(f"шаблоны SEMP/OpsCon/ICD/SMA/ProjectPlan: {len(ожидаемые)} файлов совпадают с генератором")
        return 0
    for имя, текст in ожидаемые.items():
        (ПОЛКИ / имя).write_text(текст, encoding="utf-8")
        print(f"записан {имя}: {len(json.loads(текст)['sections'])} разделов")
    return 0


if __name__ == "__main__":
    sys.exit(main())
