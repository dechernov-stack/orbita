#!/usr/bin/env python3
"""Сид словаря — генератором из истин, не из головы (РЕШЕНИЕ-СЛОВАРЬ-И-БАЗА-ЗНАНИЙ §2,
ЗАДАНИЕ-ШИП-4 §2). Термин — запись вида `glossary_term` (истина схем): каноническое
имя, синонимы, класс, определение, источник, код объекта.

Источники (все — уже существующие истины и поставки):
  · глоссарий системной инженерии — пакет GL-9002 (41);
  · глоссарий «Орбиты» (типы документов, зоны, MOE, классы терминалов) — GL-9001 (11);
  · кросс-терминология NASA ↔ РК-11КТ / ГОСТ ↔ Романов — СИД-ГЛОССАРИЙ-КРОСС (27);
  · коды узлов состава — ПОЛКА-PBS; коды стыков — ПОЛКА-ИНТЕРФЕЙСЫ;
  · роли сторон и роли проекта — истина схем и учётки стенда;
  · сцены — шаблоны фаз Pre-A и Phase A; метки происхождения — истина схем;
  · единицы — справочник единиц (07); документы — шаблоны документов.

Определения правятся рукой один раз — в файле правок СЛОВАРЬ-ПРАВКИ.json
({код: {поле: значение}}): генератор накладывает их поверх и никогда не теряет.

    python3 tools/v2/gen_glossary_seed.py           # записать сид
    python3 tools/v2/gen_glossary_seed.py --check   # сторож расхождения
"""
from __future__ import annotations

import argparse
import glob
import json
import pathlib
import re
import sys

import yaml

КОРЕНЬ = pathlib.Path(__file__).resolve().parent.parent.parent
ИСТИНА = КОРЕНЬ / "docs/tz/v2/СХЕМЫ-ПОЛЕЙ-V2.yaml"
ПАКЕТЫ = КОРЕНЬ / "docs/tz/manual-run/packets"
V2 = КОРЕНЬ / "docs/tz/v2"
ВЫХОД = V2 / "полки-порождённые/СИД-СЛОВАРЬ.json"
ПРАВКИ = V2 / "полки-порождённые/СЛОВАРЬ-ПРАВКИ.json"

# Роли проекта — учётки стенда (HttpApi.actingRoles); в истине схем их перечня нет.
РОЛИ_ПРОЕКТА = {"lead": "руководитель проекта", "lead_se": "ведущий системный инженер",
                "specialist": "инженер", "da_review": "DA — уполномоченный решать точки"}
МЕТКИ_ЛАТИНИЦЕЙ = {"И": "I", "В": "V", "П": "P"}


def код(*части: str) -> str:
    """Код записи — латиницей и цифрами: сторож кода маршрутов иного не пропустит."""
    сырой = "-".join(ч for ч in части if ч)
    return re.sub(r"[^A-Za-z0-9._-]+", "-", сырой).strip("-")


def термин(code: str, term_ru: str, cls: str, definition: str, **прочее) -> dict:
    з = {"code": code, "term_ru": term_ru.strip(), "class": cls, "definition": (definition or "").strip()}
    for к, з_ in прочее.items():
        if з_ in (None, "", [], {}):
            continue
        з[к] = з_
    return з


def уникально(список: list[str]) -> list[str]:
    seen, out = set(), []
    for x in список:
        x = (x or "").strip()
        if x and x.lower() not in seen:
            seen.add(x.lower()); out.append(x)
    return out


def собрать() -> list[dict]:
    истина = yaml.safe_load(ИСТИНА.read_text(encoding="utf-8"))
    метки = истина.get("enum_labels") or {}
    записи: list[dict] = []

    # 1. Глоссарий системной инженерии (41)
    се = json.loads((ПАКЕТЫ / "14-глоссарий-se.json").read_text(encoding="utf-8"))["objects"][0]
    for i, э in enumerate(се["entries"], 1):
        записи.append(термин(
            код("GT-SE", f"{i:03d}"), э["term"], "se_concept", э.get("brief", ""),
            term_en=э.get("en"), synonyms=уникально([э.get("en", "")]),
            not_to_confuse=э.get("not_to_confuse"), source=э.get("source") or "NASA SEH App. B",
            used_in=["постановка", "требования", "документы"],
        ))

    # 2. Глоссарий «Орбиты» (11): типы документов и понятия продукта
    орб = json.loads((ПАКЕТЫ / "08-глоссарий.json").read_text(encoding="utf-8"))["objects"][0]
    for i, э in enumerate(орб["entries"], 1):
        документ = bool(э.get("sd_kind"))
        записи.append(термин(
            код("GT-ORB", f"{i:03d}"), э["term"], "document" if документ else "se_concept", э.get("brief", ""),
            not_to_confuse=э.get("not_to_confuse"), source="глоссарий «Орбиты» (пакет 08)",
            object_code=э.get("sd_kind"),
            doc_type_brief=э.get("extracts") if документ else None,
            default_take_to_prompt=(э.get("prompt_default") == "on") if документ else None,
            used_in=["поле знаний", "разбор документа"] if документ else ["постановка"],
        ))

    # 3. Кросс-терминология (27): интерфейс — в терминах NASA, соответствия — синонимами
    кросс = json.loads((V2 / "сиды/СИД-ГЛОССАРИЙ-КРОСС.json").read_text(encoding="utf-8"))
    for i, э in enumerate(кросс["items"], 1):
        записи.append(термин(
            код("GT-X", f"{i:03d}"), э["term_nasa"], "process",
            f"{э.get('en_full', '')}; по РК-11КТ/ГОСТ — {э.get('ru_equivalent', '')}; по Романову — {э.get('romanov', '')}".strip("; "),
            term_en=э.get("en_full"),
            synonyms=уникально([э.get("ru_equivalent", ""), э.get("en_full", ""), э.get("romanov", "")]),
            source="кросс-терминология NASA ↔ РК-11КТ / ГОСТ ↔ Романов (сид GL-9003)",
            used_in=["интерфейс", "фаза", "точки"],
        ))

    # 4. Узлы состава — коды ПОЛКА-PBS
    pbs = json.loads((V2 / "ПОЛКА-PBS.json").read_text(encoding="utf-8"))
    for у in pbs["nodes"]:
        записи.append(термин(
            код("GT-NODE", у["code"]), у["name"], "node", у.get("desc", "") or f"узел состава {у['code']}",
            synonyms=уникально([у["code"]]), object_code=у["code"],
            source="ПОЛКА-PBS", used_in=["состав", "требования", "стыки"],
        ))

    # 5. Стыки — коды ПОЛКА-ИНТЕРФЕЙСЫ
    ифс = json.loads((V2 / "ПОЛКА-ИНТЕРФЕЙСЫ.json").read_text(encoding="utf-8"))
    типы = ифс.get("interface_types") or {}
    for с in ифс["interfaces"]:
        записи.append(термин(
            код("GT-IF", с["code"]), с["name"], "interface",
            f"стык {с.get('type', '')} между {с.get('a', '')} и {с.get('b', '')}: {типы.get(с.get('type', ''), '')}".strip(": "),
            synonyms=уникально([с["code"]]), object_code=с["code"],
            source="ПОЛКА-ИНТЕРФЕЙСЫ", used_in=["архитектура", "ICD"],
        ))

    # 6. Роли сторон (истина схем) и роли проекта (учётки стенда)
    for кодроли, слово in (метки.get("stakeholder.role") or {}).items():
        записи.append(термин(
            код("GT-ROLE", кодроли), слово, "role", f"роль стороны к проекту: {слово}",
            synonyms=уникально([кодроли]), object_code=кодроли, source="истина схем (stakeholder.role)",
            used_in=["сцена 3", "стороны"],
        ))
    for кодроли, слово in РОЛИ_ПРОЕКТА.items():
        записи.append(термин(
            код("GT-ROLE", кодроли), слово, "role", f"роль в проекте: {слово}",
            synonyms=уникально([кодроли]), object_code=кодроли, source="учётки стенда (роли проекта)",
            used_in=["паспорт", "поручения", "точки"],
        ))

    # 7. Сцены — шаблоны фаз
    for имя in ("ШАБЛОН-ФАЗЫ-PRE-A-NASA.json", "ШАБЛОН-ФАЗЫ-PHASE-A-NASA.json"):
        шаблон = json.loads((V2 / "полки-порождённые" / имя).read_text(encoding="utf-8"))
        for сц in шаблон["scenes"]:
            записи.append(термин(
                код("GT-SCENE", сц["key"]), f"сцена {сц['key']} — {сц['title']}", "scene", сц.get("question", "") or сц["title"],
                synonyms=уникально([сц["title"], f"сцена {сц['key']}"]), object_code=сц["key"],
                source=имя, used_in=["работа", "фаза"],
            ))

    # 8. Метки происхождения
    for м, слово in (метки.get("fact.mark") or {}).items():
        записи.append(термин(
            код("GT-MARK", МЕТКИ_ЛАТИНИЦЕЙ.get(м, м)), f"метка {м} — {слово}", "mark",
            f"ось «происхождение» факта: {м} — {слово}", synonyms=уникально([м, слово]), object_code=м,
            source="истина схем (fact.mark)", used_in=["факты", "поле знаний"],
        ))

    # 9. Единицы — справочник
    ед = json.loads((ПАКЕТЫ / "07-справочник-единиц.json").read_text(encoding="utf-8"))["objects"][0]
    for изм in ед["dimensions"]:
        записи.append(термин(
            код("GT-UNIT", изм["canon"]), f"{изм['name']} — {изм['canon']}", "unit",
            f"единица величины «{изм['name']}»: канон {изм['canon']}; варианты написания — синонимами",
            synonyms=уникально([изм["canon"]] + list(изм.get("spellings", []))
                               + [в["unit"] for в in изм.get("inputs", [])]
                               + [н for в in изм.get("inputs", []) for н in в.get("spellings", [])]),
            object_code=изм["canon"], source="справочник единиц (пакет 07)", used_in=["величины", "требования"],
        ))

    # 10. Документы — шаблоны
    for путь in sorted(glob.glob(str(V2 / "полки-порождённые/ШАБЛОН-*.json"))):
        д = json.loads(pathlib.Path(путь).read_text(encoding="utf-8"))
        if д.get("kind") != "document_template":
            continue
        записи.append(термин(
            код("GT-DOC", д["code"]), д.get("title", д["code"]), "document",
            f"документ фазы по шаблону {д['code']}: {д.get('note', '') or д.get('title', '')}".strip(),
            synonyms=уникально([д["code"], д["code"].upper()]), object_code=д["code"],
            source="шаблон документа", used_in=["документы"],
        ))

    # Правки рукой — поверх, один раз и навсегда.
    if ПРАВКИ.exists():
        правки = json.loads(ПРАВКИ.read_text(encoding="utf-8"))
        for з in записи:
            for поле, значение in (правки.get(з["code"]) or {}).items():
                з[поле] = значение

    коды = [з["code"] for з in записи]
    дубли = sorted({к for к in коды if коды.count(к) > 1})
    if дубли:
        sys.exit(f"сид словаря: коды повторяются — {', '.join(дубли)}")
    return записи


def документ() -> dict:
    записи = собрать()
    return {
        "kind": "glossary_term",
        "code": "СИД-СЛОВАРЬ",
        "title": "Словарь класса миссии — сид из истин (генератор tools/v2/gen_glossary_seed.py)",
        "item_status": "accepted",
        "by_class": {к: sum(1 for з in записи if з["class"] == к) for к in sorted({з["class"] for з in записи})},
        "items": записи,
    }


def main() -> int:
    р = argparse.ArgumentParser()
    р.add_argument("--check", action="store_true")
    а = р.parse_args()
    текст = json.dumps(документ(), ensure_ascii=False, indent=2) + "\n"
    if а.check:
        if not ВЫХОД.exists() or ВЫХОД.read_text(encoding="utf-8") != текст:
            print("сид словаря расходится с истинами: python3 tools/v2/gen_glossary_seed.py")
            return 1
        д = json.loads(текст)
        print(f"сид словаря: {len(д['items'])} терминов, по классам {д['by_class']} — совпадает с генератором")
        return 0
    ВЫХОД.write_text(текст, encoding="utf-8")
    д = json.loads(текст)
    print(f"записан {ВЫХОД.name}: {len(д['items'])} терминов, по классам {д['by_class']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
