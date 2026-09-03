#!/usr/bin/env python3
"""Сторож справочника единиц (решение ранга ADR, правило 1): все unit-строки
системы принадлежат справочнику. Сканируются пакеты поставок, фикстуры
эталонов и клиентский словарь подписей; строка мимо словаря валит сборку.

--selftest: нарочная строка мимо справочника обязана быть поймана.
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SEED = ROOT / "docs/tz/manual-run/packets/07-справочник-единиц.json"


def registry_units() -> set[str]:
    """Единица справочника — сама единица И её написания: пачки владельца
    приходят по-русски («Вт», «А·ч», «сут»), и написание — часть словаря,
    а не исключение из проверки."""
    doc = json.loads(SEED.read_text(encoding="utf-8"))
    units: set[str] = set()
    for dim in doc["objects"][0]["dimensions"]:
        units.add(dim["canon"])
        units.update(dim.get("spellings", []))
        for i in dim.get("inputs", []):
            units.add(i["unit"])
            units.update(i.get("spellings", []))
    return units


def unit_strings() -> list[tuple[str, str]]:
    out: list[tuple[str, str]] = []
    for pattern in ("docs/tz/manual-run/packets/*.json", "spec/fixtures/*.json"):
        for f in sorted(ROOT.glob(pattern)):
            if f.name == SEED.name:
                continue
            for m in re.finditer(r'"unit"\s*:\s*"([^"]+)"', f.read_text(encoding="utf-8")):
                out.append((str(f.relative_to(ROOT)), m.group(1)))
    labels = json.loads(
        (ROOT / "core/req/src/main/resources/orbita/req/unit-labels-ru.json").read_text(encoding="utf-8"),
    )["labels"]
    out += [("unit-labels-ru.json", k) for k in labels]
    return out


def main() -> int:
    known = registry_units()
    rows = unit_strings()
    if "--selftest" in sys.argv:
        rows.append(("<selftest>", "parsec"))
    bad = [(src, u) for src, u in rows if u not in known]
    if "--selftest" in sys.argv:
        if any(u == "parsec" for _, u in bad):
            print("единицы: самопроверка сторожа — нарочная строка поймана")
            return 0
        print("единицы: САМОПРОВЕРКА ПРОВАЛЕНА — нарочная строка прошла мимо")
        return 1
    if bad:
        print("единицы: строки мимо справочника (добавьте запись в справочник, не в код):")
        for src, u in sorted(set(bad)):
            print(f"  {src}: '{u}'")
        return 1
    print(f"единицы: {len(rows)} unit-строк ∈ справочнику ({len(known)} единиц)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
