#!/usr/bin/env python3
"""Шаблоны FAD и FA — ИЗ ПОСТАВКИ владельца, а не из головы.

`docs/tz/v2/поставка-09-09/ШАБЛОНЫ-FAD-FA.json` (Прил. 1–2 БП-PPA) описывает
разделы ожиданиями к точкам: «сущность такого вида», «тезис», «запрос».
Здесь ожидания разворачиваются в наш диалект живого документа (как у
MCReport и ConOps): раздел = запросы к реестру + тезисы человека, полнота —
к ступени. Сопоставление ожидания с нашим запросом — явная таблица ниже:
чего у нас нет (сцены Phase A до шипа G, записи tailoring), сказано
пометой раздела, а не спрятано.

  python3 tools/v2/gen_document_templates.py            # записать шаблоны
  python3 tools/v2/gen_document_templates.py --check    # сторож: не разошлись
"""
import argparse
import json
import pathlib
import sys

КОРЕНЬ = pathlib.Path(__file__).resolve().parent.parent.parent
ПОСТАВКА = КОРЕНЬ / "docs/tz/v2/поставка-09-09/ШАБЛОНЫ-FAD-FA.json"
ПОЛКИ = КОРЕНЬ / "docs/tz/v2/полки-порождённые"
ОБРАЗЕЦ = ПОЛКИ / "ШАБЛОН-MCREPORT.json"   # стиль, рецензия и подписи — одни на все документы

ВЫХОДЫ = {"DT-9101": ("fad", "ШАБЛОН-FAD.json"), "DT-9102": ("fa", "ШАБЛОН-FA.json")}

# Ожидание поставки → наш элемент. Ключ: (код шаблона, номер раздела).
# Каждый раздел: сцены-источники, ожидание ступени, запросы, минимум тезисов,
# помета о том, чего мы пока не умеем.
РАЗДЕЛЫ = {
    ("fad", "1"): dict(scenes=["2"], expects="MCR", min_statements=1,
        statement_hint="связь с целями дирекции; основание инициирования",
        elements=[dict(code="1.1", title="Замысел миссии", select="intent", min_rows=1,
            columns=[("Для кого", "for_whom"), ("Что делает", "what"), ("Где", "where"), ("Горизонт", "horizon")])]),
    ("fad", "2"): dict(scenes=["1", "3"], expects="MCR", elements=[
        dict(code="2.1", title="Руководитель проекта", select="project", min_rows=1,
            columns=[("Проект", "name"), ("Руководитель", "lead"), ("Стандарт", "standard")]),
        dict(code="2.2", title="Ведущий центр и участвующие организации", select="stakeholder", min_rows=1,
            columns=[("Сторона", "name"), ("Роль", "role")])]),
    ("fad", "3"): dict(scenes=["5"], expects="MCR", min_statements=1,
        statement_hint="границы санкционируемых работ; категория проекта; интерфейсы с программой",
        elements=[dict(code="3.1", title="Ограничения и исходные требования", select="constraint", min_rows=1,
            columns=[("Код", "code"), ("Ограничение", "text"), ("Категория", "category")])]),
    ("fad", "4"): dict(scenes=["12"], expects="MCR", elements=[
        dict(code="4.1", title="Ресурсы Формулирования", select="cost_estimate", min_rows=1,
            columns=[("Пакет", "package"), ("От", "min"), ("До", "max"), ("Ед.", "unit")])]),
    ("fad", "5"): dict(scenes=["1"], expects="MCR", elements=[
        dict(code="5.1", title="Контрольные события Формулирования", select="gate", min_rows=3,
            columns=[("Точка", "title"), ("Дата", "planned_date"), ("Состояние", "status")])]),
    ("fad", "6"): dict(scenes=[], expects="KDP-A", elements=[
        dict(code="6.1", title="Решения точек (подписи)", select="decision", min_rows=1,
            columns=[("Точка", "gate"), ("Кем", "by"), ("Когда", "at"), ("Исход", "outcome")])]),
    ("fa", "1"): dict(scenes=["1"], expects="MCR", min_statements=1,
        statement_hint="период действия; стороны",
        elements=[
            dict(code="1.1", title="Проект", select="project", min_rows=1,
                columns=[("Проект", "name"), ("Стандарт", "standard"), ("Класс миссии", "mission_class")]),
            dict(code="1.2", title="Документ санкционирования (FAD)", select="document", where={"template": "fad"}, min_rows=1,
                columns=[("Документ", "title"), ("Состояние", "status")])]),
    ("fa", "2"): dict(scenes=["10", "11"], expects="MCR", min_statements=1,
        statement_hint="технические и закупочные работы Phase A",
        note="Работы Phase A сценами придут с шаблоном Phase A (шип G); до него состав работ — тезисом, "
             "созревание технологий и снятие ключевых рисков — запросами.",
        elements=[
            dict(code="2.1", title="Работы по созреванию технологий", select="wbs_package", where={"code_prefix": "04."}, min_rows=1,
                columns=[("Пакет", "code"), ("Название", "name")]),
            dict(code="2.2", title="Ключевые риски (критичность ≥ 12)", select="risk", where={"criticality_min": 12}, min_rows=1,
                columns=[("Код", "code"), ("Риск", "statement"), ("Вероятность", "probability"), ("Влияние", "impact")])]),
    ("fa", "3"): dict(scenes=[], expects="KDP-A", min_statements=1,
        statement_hint="укрупнённый перечень работ Phase B; допущения", elements=[]),
    ("fa", "4"): dict(scenes=["1", "12"], expects="MCR",
        note="Матрица зрелости продуктов к точкам живёт у точки KDP-A (экран «Точки»); в документ идёт план и оценки.",
        elements=[
            dict(code="4.1", title="Календарный план: даты точек", select="plan", expand="gate_dates", min_rows=1,
                columns=[("Точка", "gate"), ("Дата", "date")]),
            dict(code="4.2", title="Диапазоны стоимости и сроков", select="cost_estimate", min_rows=1,
                columns=[("Пакет", "package"), ("От", "min"), ("До", "max"), ("Ед.", "unit")])]),
    ("fa", "5"): dict(scenes=["12"], expects="KDP-A", min_statements=1,
        statement_hint="трудовые ресурсы; инфраструктура",
        elements=[dict(code="5.1", title="Финансирование Phase A–B", select="cost_estimate", min_rows=1,
            columns=[("Пакет", "package"), ("От", "min"), ("До", "max"), ("Ед.", "unit")])]),
    ("fa", "6"): dict(scenes=[], expects="KDP-A", elements=[],
        note="Записей tailoring (неприменимо у критериев, отклонения лестницы, шаблонов) в реестре пока нет — "
             "раздел не держит документ; появятся записи — появится запрос."),
    ("fa", "7"): dict(scenes=["4"], expects="KDP-A", elements=[
        dict(code="7.1", title="Ведущие индикаторы: показатели целей", select="goal", min_rows=1,
            columns=[("Цель", "statement"), ("Показатель", "metric"), ("Год", "year")])]),
    ("fa", "8"): dict(scenes=[], expects="KDP-A", elements=[
        dict(code="8.1", title="Решения точек (согласование)", select="decision", min_rows=1,
            columns=[("Точка", "gate"), ("Кем", "by"), ("Когда", "at"), ("Исход", "outcome")])]),
}


def собрать() -> dict[str, dict]:
    поставка = json.loads(ПОСТАВКА.read_text(encoding="utf-8"))
    образец = json.loads(ОБРАЗЕЦ.read_text(encoding="utf-8"))
    выходы: dict[str, dict] = {}
    for шаблон in поставка["templates"]:
        код, файл = ВЫХОДЫ[шаблон["code"]]
        разделы = []
        for раздел in шаблон["sections"]:
            наш = РАЗДЕЛЫ.get((код, раздел["no"]))
            if наш is None:
                raise SystemExit(f"{код} §{раздел['no']}: ожидание поставки не сопоставлено — допишите таблицу")
            элементы = []
            for э in наш["elements"]:
                элемент = {"code": э["code"], "kind": "query", "title": э["title"], "select": э["select"]}
                if "where" in э:
                    элемент["where"] = э["where"]
                if "expand" in э:
                    элемент["expand"] = э["expand"]
                элемент["min_rows"] = э["min_rows"]
                элемент["columns"] = [{"title": t, "field": f} for t, f in э["columns"]]
                элементы.append(элемент)
            запись = {
                "no": "§" + раздел["no"],
                "title": раздел["title"],
                "scenes": наш["scenes"],
                "elements": элементы,
                "expects": наш["expects"],
                "source": раздел.get("source", ""),
            }
            if наш.get("min_statements"):
                запись["min_statements"] = наш["min_statements"]
                запись["statement_hint"] = наш.get("statement_hint", "")
            if наш.get("note"):
                запись["note"] = наш["note"]
            разделы.append(запись)
        выходы[файл] = {
            "kind": "document_template",
            "code": код,
            "delivery_code": шаблон["code"],
            "title": шаблон["title"],
            "standard": "БП-PPA, приложение " + ("1 (FAD)" if код == "fad" else "2 (Formulation Agreement)"),
            "note": "Порождён из поставки ШАБЛОНЫ-FAD-FA.json генератором tools/v2/gen_document_templates.py; "
                    "правки — в поставку или в таблицу сопоставления, не сюда.",
            "rules": шаблон.get("rules", []),
            "style": образец["style"],
            "review": образец["review"],
            "labels": образец["labels"],
            "sections": разделы,
        }
    return выходы


def main() -> int:
    разбор = argparse.ArgumentParser(description=__doc__)
    разбор.add_argument("--check", action="store_true", help="сторож: шаблоны совпадают с поставкой")
    args = разбор.parse_args()
    расхождения = []
    for файл, данные in собрать().items():
        путь = ПОЛКИ / файл
        новый = json.dumps(данные, ensure_ascii=False, indent=2) + "\n"
        if args.check:
            if not путь.exists() or путь.read_text(encoding="utf-8") != новый:
                расхождения.append(файл)
        else:
            путь.write_text(новый, encoding="utf-8")
            print(f"{файл}: {len(данные['sections'])} разделов")
    if args.check:
        if расхождения:
            print("шаблоны FAD/FA разошлись с поставкой: " + ", ".join(расхождения) +
                  " — перегенерируйте: python3 tools/v2/gen_document_templates.py", file=sys.stderr)
            return 1
        print("шаблоны FAD/FA: совпадают с поставкой (2 файла)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
