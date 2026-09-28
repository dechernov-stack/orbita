#!/usr/bin/env python3
"""Полка единиц v2 — генератором из справочника единиц, не из головы (§0.1 шипа 6).

Долг прогона: `GET /v2/units` отдавал единицы v1-реестра, а `Normalize` читает
вид `unit` хранилища v2, которого на стенде нет — «180 мин» и «3 ч» были
несравнимы. Одна истина значений единиц — v1-справочник (пакет 07,
`UR-9001.dimensions[]`); отсюда порождаем записи вида `unit` (истина
СХЕМЫ-ПОЛЕЙ-V2.yaml) и грузим загрузчиком полок в область LIB. `/v2/units`,
`Normalize` и пикер величины читают одно — эти записи.

Каждая размерность даёт каноническую запись (factor 1) и по записи на каждый
вход (factor к канону). symbol — ASCII-код (m/s), name — основное русское
написание (мин, ч), чтобы величина находилась и кодом, и по-русски. Тип
преобразования: linear · offset (v1 shift) · log · rate; v1 «none» (счётные,
шкалы) — linear с factor 1.

    python3 tools/v2/gen_units_shelf.py           # записать полку
    python3 tools/v2/gen_units_shelf.py --check   # сторож расхождения с источником
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys

КОРЕНЬ = pathlib.Path(__file__).resolve().parent.parent.parent
ИСТОЧНИК = КОРЕНЬ / "docs/tz/manual-run/packets/07-справочник-единиц.json"
ПОЛКА = КОРЕНЬ / "docs/tz/v2/полки-порождённые/ПОЛКА-ЕДИНИЦЫ.json"

# v1 conversion → v2 conversion_type (enum истины [linear,offset,log,rate]).
# «none» (счётные, шкалы) сравниваются как есть — linear с factor 1.
ТИП = {"linear": "linear", "shift": "offset", "log": "log", "none": "linear", "rate": "rate"}


def слаг(текст: str) -> str:
    s = re.sub(r"[^A-Za-z0-9]+", "_", текст).strip("_").lower()
    return s


def записи() -> list[dict]:
    пакет = json.loads(ИСТОЧНИК.read_text(encoding="utf-8"))
    реестр = пакет["objects"][0]
    итог: list[dict] = []
    коды: set[str] = set()

    def код(symbol: str, dimension: str) -> str:
        основа = слаг(symbol) or слаг(dimension) or "u"
        код = основа
        n = 2
        while код in коды:
            код = f"{основа}_{n}"
            n += 1
        коды.add(код)
        return код

    def запись(dimension, symbol, name, canonical, factor, conv, offset):
        d = {
            "dimension": dimension,
            "symbol": symbol,
            "name": name or symbol,
            "canonical": canonical,
            "conversion_type": conv,
        }
        if factor is not None:
            d["factor"] = factor
        if offset is not None:
            d["offset"] = offset
        d["code"] = код(symbol, dimension)
        return d

    for разм in реестр["dimensions"]:
        имя = разм["name"]
        conv = ТИП.get(разм.get("conversion", "linear"), "linear")
        канон_имя = (разм.get("spellings") or [разм["canon"]])[0]
        итог.append(запись(имя, разм["canon"], канон_имя, True, 1, conv, None))
        for вход in разм.get("inputs", []):
            вх_имя = (вход.get("spellings") or [вход["unit"]])[0]
            # Вход с пометой «rate» (валюта без курса) — курсовой, не линейный.
            тип_входа = "rate" if "rate" in (вход.get("note") or "") else conv
            итог.append(запись(
                имя, вход["unit"], вх_имя, False,
                вход.get("factor"), тип_входа, вход.get("shift"),
            ))
    return итог


def полка() -> dict:
    return {
        "_comment": "Полка единиц v2 — порождена tools/v2/gen_units_shelf.py из "
                    "справочника единиц (пакет 07, UR-9001). Правки — в источнике, не здесь.",
        "code": "ПОЛКА-ЕДИНИЦЫ",
        "item_status": "Baseline",
        "items": записи(),
    }


def main() -> int:
    ap = argparse.ArgumentParser(description="полка единиц v2")
    ap.add_argument("--check", action="store_true", help="сторож расхождения с источником")
    args = ap.parse_args()
    свежая = json.dumps(полка(), ensure_ascii=False, indent=2) + "\n"
    if args.check:
        было = ПОЛКА.read_text(encoding="utf-8") if ПОЛКА.exists() else ""
        if было != свежая:
            print("полка единиц разошлась со справочником — перегенерируйте: python3 tools/v2/gen_units_shelf.py", file=sys.stderr)
            return 1
        n = len(json.loads(свежая)["items"])
        print(f"полка единиц: {n} записей совпадают со справочником (пакет 07)")
        return 0
    ПОЛКА.write_text(свежая, encoding="utf-8")
    print(f"полка единиц: записано {len(полка()['items'])} записей в {ПОЛКА.relative_to(КОРЕНЬ)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
