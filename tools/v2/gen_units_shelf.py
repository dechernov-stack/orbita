#!/usr/bin/env python3
"""Полка единиц v2 — генератором из справочника единиц, не из головы (§0.1 шипа 6,
правки владельца 28.09).

Долг прогона: `GET /v2/units` отдавал единицы v1-реестра, а `Normalize` читает
вид `unit` хранилища v2, которого на стенде нет — «180 мин» и «3 ч» были
несравнимы. Одна истина значений единиц — v1-справочник (пакет 07,
`UR-9001.dimensions[]`); отсюда порождаем записи вида `unit` (истина
СХЕМЫ-ПОЛЕЙ-V2.yaml) и грузим загрузчиком полок в область LIB.

Каждая размерность даёт каноническую запись (factor 1) и по записи на каждый
вход (factor к канону). symbol — ASCII-код (m/s); name — одно РУССКОЕ имя для
экрана (латиница только у устоявшихся: TRL, U); spellings — ВСЕ написания
источника («мин», «минут», «минуты», «min»), по ним чтение и Normalize узнают
единицу в тексте, из них же сид словаря берёт синонимы термина класса unit.
Тип преобразования: linear · offset (v1 shift) · log · rate; v1 «none» (счётные,
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
ТИП = {"linear": "linear", "shift": "offset", "log": "log", "none": "linear", "rate": "rate"}

# Русское имя для symbol'ов без русского написания в источнике (латиница в name
# запрещена сторожем, кроме устоявшихся). Диф владельца 28.09.
ИМЕНА = {
    "mm": "мм", "m3": "м³", "rad": "радиан", "arcmin": "угл. мин",
    "Pa": "Па", "bar": "бар", "mAh": "мА·ч", "bit": "бит",
    "Gy": "Гр", "rad_dose": "рад", "Sv": "Зв", "mSv": "мЗв",
    "MRUB/kg": "млн руб/кг", "kUSD/kg": "тыс долл/кг",
    "man_month": "чел-мес", "man_h": "чел-ч", "man_year": "чел-год",
    "mission_class": "класс миссии", "pcs": "шт",
}
# Устоявшиеся обозначения: латиница в name и name == symbol допустимы.
УСТОЯВШИЕСЯ = {"TRL", "U"}

ЛАТИНИЦА = re.compile(r"[A-Za-z]")


def слаг(текст: str) -> str:
    return re.sub(r"[^A-Za-z0-9]+", "_", текст).strip("_").lower()


def первое_русское(написания: list[str]) -> str | None:
    for н in написания:
        if н and not ЛАТИНИЦА.search(н):
            return н
    return None


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

    def запись(dimension, symbol, написания, canonical, factor, conv, offset):
        написания = list(dict.fromkeys([w for w in написания if w and w != symbol]))
        name = ИМЕНА.get(symbol) or первое_русское(написания) or symbol
        d = {
            "dimension": dimension,
            "symbol": symbol,
            "name": name,
            "canonical": canonical,
            "conversion_type": conv,
        }
        if factor is not None:
            d["factor"] = factor
        if offset is not None:
            d["offset"] = offset
        if написания:
            d["spellings"] = написания
        d["code"] = код(symbol, dimension)
        return d

    for разм in реестр["dimensions"]:
        имя = разм["name"]
        conv = ТИП.get(разм.get("conversion", "linear"), "linear")
        итог.append(запись(имя, разм["canon"], разм.get("spellings", []), True, 1, conv, None))
        for вход in разм.get("inputs", []):
            тип_входа = "rate" if "rate" in (вход.get("note") or "") else conv
            итог.append(запись(имя, вход["unit"], вход.get("spellings", []), False,
                               вход.get("factor"), тип_входа, вход.get("shift")))
    return итог


def проверить(записи: list[dict]) -> list[str]:
    """Сторож генератора (владелец 28.09): name без латиницы (кроме TRL/U) и не равен symbol."""
    беды: list[str] = []
    for r in записи:
        symbol, name = r["symbol"], r["name"]
        if symbol in УСТОЯВШИЕСЯ:
            continue
        if ЛАТИНИЦА.search(name):
            беды.append(f"{symbol}: имя «{name}» с латиницей — дайте русское (ИМЕНА) либо внесите написание в источник")
        if name == symbol:
            беды.append(f"{symbol}: имя равно символу — name для экрана, symbol для сравнения")
    return беды


def полка() -> dict:
    итог = записи()
    беды = проверить(итог)
    if беды:
        raise SystemExit("полка единиц — сторож имён:\n  " + "\n  ".join(беды))
    return {
        "_comment": "Полка единиц v2 — порождена tools/v2/gen_units_shelf.py из "
                    "справочника единиц (пакет 07, UR-9001). Правки — в источнике, не здесь.",
        "code": "ПОЛКА-ЕДИНИЦЫ",
        "item_status": "Baseline",
        "items": итог,
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
        d = json.loads(свежая)
        всего_нап = sum(len(r.get("spellings", [])) for r in d["items"])
        print(f"полка единиц: {len(d['items'])} записей, {всего_нап} написаний совпадают со справочником (пакет 07)")
        return 0
    ПОЛКА.write_text(свежая, encoding="utf-8")
    d = json.loads(свежая)
    print(f"полка единиц: записано {len(d['items'])} записей, {sum(len(r.get('spellings', [])) for r in d['items'])} написаний в {ПОЛКА.relative_to(КОРЕНЬ)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
