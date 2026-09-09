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
               "SRR", scenes=["A1"], statements=1, hint="зачем документ, к какой фазе относится, связь с FA", source="проект · FA"),
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
               "SRR", scenes=["3", "A1"], statements=1, hint="роли команды и права (В3); поставщики со связями на узлы", source="стейкхолдеры · роли"),
        раздел("§6", "Технические процессы (NPR 7123.1)",
               [q("6.1", "17 процессов: процесс → механизм «Орбиты» → место", "process_catalog",
                  [("Код", "code"), ("Процесс", "name"), ("Группа", "group"), ("Механизм «Орбиты»", "orbita_mechanism"), ("Место", "place"), ("Отклонение (tailoring)", "tailoring")],
                  area="library", expand="processes", min_rows=17)],
               "SRR", scenes=["A1"], statements=1, hint="вводный абзац: процессы реализации — вне области Формулирования, к Phase C/D", source="полка процессов СИ"),
        раздел("§7", "Технические обзоры",
               [q("7.1", "Точки фазы", "gate", [("Точка", "title"), ("Дата", "planned_date"), ("Состояние", "status")])],
               "SRR", scenes=["A1"], statements=1, hint="порядок обзоров: внутренний обзор → SRR → SDR/MDR → KDP-B", source="точки фазы"),
        раздел("§8", "Технические показатели (TPM)",
               [q("8.1", "Свёртки бюджетов", "budget", [("Бюджет", "kind"), ("Корень", "root"), ("Политика резерва", "reserve_policy")])],
               "SDR", scenes=["7", "A4"], statements=1, hint="запас массы и мощности из свёрток; тренды закрытия замечаний — числа из модели", source="бюджеты · замечания точек"),
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
               "SRR", scenes=["A1"], statements=1, hint="свои расчёты — фактом, Orekit — в резерве; без обещаний будущего", source="полка процессов СИ"),
        раздел("§11", "Согласование и актуализация",
               [q("11.1", "Решения точек", "decision", [("Точка", "gate"), ("Кем", "by"), ("Когда", "at"), ("Исход", "outcome")], min_rows=0)],
               "SRR", scenes=["A1"], statements=1, hint="выпуски документа: версия · дата · выпустивший; актуализация — фиксациями переходов", source="решения точек · базирования"),
    ]


def opscon():
    return [
        раздел("§1", "Назначение и режимы работы",
               [q("1.1", "Режимы и состояния компонентов", "state_machine", [("Компонент", "owner"), ("Состояния", "states"), ("Начальное", "initial")])],
               "SDR", scenes=["A2"], statements=1, hint="режимы применения и переходы между ними — словами оператора", source="режимы · сценарии"),
        раздел("§2", "Смены и процедуры",
               [q("2.1", "Сценарии операций", "scenario", [("Код", "code"), ("Сценарий", "name"), ("Слой", "layer"), ("Шаги", "steps")])],
               "SDR", scenes=["9", "A2"], statements=1, hint="смены операторов и процедуры по каждому сценарию", source="сценарии-цепочки"),
        раздел("§3", "Окна сеансов",
               [q("3.1", "Стыки радиолиний", "interface", [("Код", "code"), ("Стык", "name"), ("Тип", "type"), ("Направление", "direction")], where={"type": "rf"}, min_rows=0)],
               "SDR", scenes=["A4"], statements=1, hint="окна сеансов связи из баллистики; что делает оператор между окнами", source="стыки · модели"),
        раздел("§4", "Нештатные регламенты",
               [q("4.1", "Риски операций", "risk", [("Код", "code"), ("Риск", "statement"), ("Стратегия", "strategy"), ("Меры", "measures")])],
               "SDR", scenes=["11", "A7"], statements=1, hint="нештатные ситуации — из рисков: признак, действие оператора, эскалация", source="риски"),
        раздел("§5", "Валидационные положения", [], "SDR", scenes=["A2", "A3"], statements=1,
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
        "ШАБЛОН-ICD.json": шаблон("icd", "Описание стыков (ICD)", "ДОКУМЕНТЫ-2-ПРОМЕЖУТОЧНЫЕ §6 (DT-9003)", icd(),
                                  "ICD собирается из стыков, их параметров и требований с носителем-стыком; полнота к SDR."),
    }


def main() -> int:
    ожидаемые = {имя: json.dumps(д, ensure_ascii=False, indent=2) + "\n" for имя, д in все().items()}
    if "--check" in sys.argv:
        разошлись = [имя for имя, текст in ожидаемые.items() if not (ПОЛКИ / имя).exists() or (ПОЛКИ / имя).read_text(encoding="utf-8") != текст]
        if разошлись:
            print("шаблоны Phase A разошлись с генератором: " + ", ".join(разошлись) + " — python3 tools/v2/gen_phase_a_documents.py")
            return 1
        print(f"шаблоны SEMP/OpsCon/ICD: {len(ожидаемые)} файла совпадают с генератором")
        return 0
    for имя, текст in ожидаемые.items():
        (ПОЛКИ / имя).write_text(текст, encoding="utf-8")
        print(f"записан {имя}: {len(json.loads(текст)['sections'])} разделов")
    return 0


if __name__ == "__main__":
    sys.exit(main())
