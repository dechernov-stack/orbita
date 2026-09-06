#!/usr/bin/env python3
"""Мероприятия сцен — из полки процессов ЖЦ, а не из головы.

РЕШЕНИЕ-ПРОЦЕСС-НА-ЭКРАНЕ: единица работы — МЕРОПРИЯТИЕ (блок Романова с
входами и выходами), сцена — группа мероприятий с потоками между ними.
Здесь карта «наша сцена ↔ мероприятия метода» разворачивается в шаблон
фазы: имя мероприятия и его потоки берутся с полки, а виды выходов и
рабочая поверхность — наши (это уже наш продукт, а не метод).

  python3 tools/v2/gen_phase_activities.py           # записать в шаблон
  python3 tools/v2/gen_phase_activities.py --check   # сторож расхождения
"""
import argparse
import json
import pathlib
import sys

КОРЕНЬ = pathlib.Path(__file__).resolve().parent.parent.parent
ПОЛКА = КОРЕНЬ / "docs/tz/v2/ПОЛКА-ПРОЦЕССЫ-РОМАНОВ.json"
ШАБЛОН = КОРЕНЬ / "docs/tz/v2/полки-порождённые/ШАБЛОН-ФАЗЫ-PRE-A-NASA.json"

# Карта: сцена → мероприятия. Наши коды (с буквой) там, где у метода
# отдельного блока нет: замысел и открытие проекта — вход в метод.
#
# Выход мероприятия может рождаться в ДРУГОЙ нашей сцене: тогда он помечен
# `produced_in` и в условие завершения не входит — он показывается стрелкой
# «→ сцена N», а не пустотой (правило эталона).
КАРТА = {
    "1": [
        {"code": "П.1", "name": "Открыть проект и завести точки фазы", "role": "lead",
         "goal": "проект существует, у фазы есть точки с датами",
         "inputs": [{"what": "решение о начале работ"}],
         "outputs": [{"what": "проект", "kind": "project", "min": 1},
                     {"what": "точки фазы", "kind": "gate", "min": 3}],
         "surface": "scene:1#passport"},
        {"code": "0.P", "role": "lead", "track": "management",
         "goal": "план работ фазы: даты точек и окна сцен",
         "outputs": [{"what": "план управления проектом", "kind": "plan", "min": 1}],
         "surface": "scene:1#plan"},
    ],
    "2": [
        {"code": "З.1", "name": "Уточнить замысел миссии", "role": "lead",
         "goal": "для кого, что делает, где и к какому горизонту — принято руководителем",
         "inputs": [{"what": "записка миссии (канон Д1)"}],
         "outputs": [{"what": "замысел миссии", "kind": "intent", "min": 1, "status": "accepted"}],
         "surface": "scene:2#intent"},
    ],
    "3": [
        {"code": "0.1", "role": "lead_se",
         "goal": "кто пользователи миссии и что им нужно",
         "outputs": [{"what": "пользователи миссии", "kind": "stakeholder", "min": 3},
                     {"what": "потребности", "kind": "need", "min": 3},
                     {"what": "цели и задачи", "kind": "goal", "min": 1, "produced_in": "4"}],
         "surface": "scene:3#stakeholders"},
        {"code": "0.2", "role": "lead",
         "goal": "чем работа ограничена и какими принципами ведётся",
         "outputs": [{"what": "допущения и ограничения", "kind": "constraint", "min": 1,
                      "produced_in": "5"}],
         "surface": "scene:5#constraints"},
    ],
    "4": [
        {"code": "0.5", "role": "lead_se",
         "goal": "измеримые цели миссии и критерии её оценки",
         "outputs": [{"what": "цели и задачи", "kind": "goal", "min": 1},
                     {"what": "критерии оценки миссии", "kind": "criterion", "min": 0}],
         "surface": "scene:4#goals"},
    ],
    "5": [
        {"code": "0.2", "role": "lead",
         "goal": "рамки проекта: ограничения с кодами Р",
         "outputs": [{"what": "допущения и ограничения", "kind": "constraint", "min": 1}],
         "surface": "scene:5#constraints"},
    ],
    "6": [
        {"code": "0.4", "role": "lead_se",
         "goal": "что система даёт кому и с каким качеством",
         "outputs": [{"what": "функции миссии", "kind": "service", "min": 1}],
         "surface": "scene:6#services"},
    ],
    "7": [
        {"code": "0.8", "role": "lead_se",
         "goal": "состав системы и эксплуатационная концепция",
         "outputs": [{"what": "предварительная схема деления системы", "kind": "component", "min": 3}],
         "surface": "scene:7#composition"},
        {"code": "0.9", "role": "lead_se",
         "goal": "варианты построения сравнены, базовый назван с обоснованием",
         "outputs": [{"what": "базовая концепция", "kind": "baseline_concept", "min": 1},
                     {"what": "варианты построения", "kind": "constellation_variant", "min": 0}],
         "surface": "scene:7#alternatives"},
    ],
    "8": [
        {"code": "0.6", "role": "lead_se",
         "goal": "перечень требований уровня проекта и системы",
         "outputs": [{"what": "требования к системе", "kind": "requirement", "min": 3}],
         "surface": "scene:8#requirements"},
        {"code": "0.7", "role": "lead_se",
         "goal": "каждое требование живёт на носителе своей природы",
         "outputs": [{"what": "распределённые требования", "kind": "requirement", "min": 3,
                      "condition": "carrier_nature_ok"}],
         "surface": "scene:8#carrier"},
    ],
    "10": [
        {"code": "1.7", "role": "lead_se",
         "goal": "критические технологии названы, разрывы TRL закрыты планом",
         "outputs": [{"what": "критические технологии", "kind": "technology", "min": 1},
                     {"what": "пакеты созревания", "kind": "wbs_package", "min": 0}],
         "surface": "scene:10#technologies"},
    ],
    "11": [
        {"code": "1.7", "role": "lead",
         "goal": "риски со сроками-точками и начальная оценка засорения",
         "outputs": [{"what": "риски", "kind": "risk", "min": 3},
                     {"what": "оценка засорения", "kind": "debris_assessment", "min": 1}],
         "surface": "scene:11#risks"},
    ],
    "12": [
        {"code": "0.9", "role": "lead",
         "goal": "стоимость диапазоном и работы парами к узлам",
         "outputs": [{"what": "пакеты работ", "kind": "wbs_package", "min": 1},
                     {"what": "оценки стоимости", "kind": "cost_estimate", "min": 1}],
         "surface": "scene:12#wbs"},
    ],
}


def мероприятия_полки() -> dict:
    полка = json.loads(ПОЛКА.read_text(encoding="utf-8"))
    все = {}
    for стадия in полка["stages"]:
        for дорожка, группы in стадия["tracks"].items():
            for группа in группы or []:
                # Дорожка управления в поставке хранит мероприятия ПЛОСКО
                # (без групп) — это её формат, а не ошибка разбора.
                дела = группа.get("activities") or ([группа] if группа.get("code") else [])
                for дело in дела:
                    все[дело["code"]] = {
                        "name": дело["name"],
                        "inputs": дело.get("inputs", []),
                        "outputs": дело.get("outputs", []),
                        "track": дорожка,
                        "group": группа.get("group", ""),
                        "stage": стадия["stage"],
                    }
    return все


def собрать() -> dict:
    полка = мероприятия_полки()
    шаблон = json.loads(ШАБЛОН.read_text(encoding="utf-8"))
    названия = {м["code"]: м["name"] for м in полка.values() if False}  # заполняется ниже

    for сцена in шаблон["scenes"]:
        карта = КАРТА.get(сцена["key"])
        if карта is None:
            continue
        мероприятия = []
        for наше in карта:
            код = наше["code"]
            из_полки = полка.get(код, {})
            # Входы: свои, если названы; иначе — потоки метода, где ссылки на
            # соседние мероприятия развёрнуты в их названия.
            входы = наше.get("inputs")
            if входы is None:
                входы = [
                    {"what": полка[в.strip()]["name"] if в.strip() in полка else в}
                    for в in из_полки.get("inputs", [])
                ]
            мероприятия.append({
                "code": код,
                "name": наше.get("name") or из_полки.get("name", код),
                "goal": наше["goal"],
                "role": наше.get("role", сцена.get("role", "lead_se")),
                "track": наше.get("track", из_полки.get("track", "design")),
                "method_group": из_полки.get("group", ""),
                "inputs": входы,
                "outputs": наше["outputs"],
                "surface": наше["surface"],
            })
        сцена["activities"] = мероприятия

    # Дорожка моделирования: мероприятия метода без своей сцены — они
    # показываются в схеме фазы и ждут артефактов из сцен.
    шаблон["lanes"] = [
        {"key": "design", "title": "Проектирование", "of": "scenes"},
        {"key": "modeling", "title": "Моделирование", "of": "activities", "activities": [
            {
                "code": код,
                "name": м["name"],
                "inputs": [
                    {"what": полка[в.strip()]["name"] if в.strip() in полка else в}
                    for в in м.get("inputs", [])
                ],
                "outputs": [{"what": о} for о in м.get("outputs", [])],
            }
            for код, м in полка.items()
            if м["track"] == "modeling" and м["stage"] == 0
        ]},
        {"key": "management", "title": "Планирование и управление", "of": "activities", "activities": [
            {
                "code": код,
                "name": м["name"],
                "inputs": [],
                "outputs": [{"what": о} for о in м.get("outputs", [])],
            }
            for код, м in полка.items()
            if м["track"] == "management" and м["stage"] == 0
        ]},
    ]
    return шаблон


if __name__ == "__main__":
    разбор = argparse.ArgumentParser(description="мероприятия сцен из полки процессов ЖЦ")
    разбор.add_argument("--check", action="store_true", help="сторож: шаблон совпадает с картой")
    аргументы = разбор.parse_args()

    новый = json.dumps(собрать(), ensure_ascii=False, indent=2) + "\n"
    if аргументы.check:
        if ШАБЛОН.read_text(encoding="utf-8") != новый:
            print("шаблон фазы разошёлся с картой мероприятий — перегенерируйте:")
            print("  python3 tools/v2/gen_phase_activities.py")
            sys.exit(1)
        сцен = len(json.loads(новый)["scenes"])
        print(f"мероприятия сцен: шаблон ({сцен} сцен) совпадает с картой")
        sys.exit(0)
    ШАБЛОН.write_text(новый, encoding="utf-8")
    шаблон = json.loads(новый)
    всего = sum(len(с.get("activities", [])) for с in шаблон["scenes"])
    print(f"мероприятия сцен: {всего} в {len(шаблон['scenes'])} сценах; дорожек {len(шаблон['lanes'])}")
