#!/usr/bin/env python3
"""Проверка пакетов материала ПМИ по нормативным схемам — ДО сессии.

Пакет, который не грузится, срывает сессию: инженер упрётся в отказ, которого
не должно быть в его журнале. Проверка идёт локально, по тем же схемам, что
применяет сервер, и НИЧЕГО не пишет в стенд — иначе идентификаторы окажутся
заняты (TZ-MOD-007: они не переиспользуются), и на сессии те же пакеты уже
не лягут.

    python3 docs/tz/manual-run/packets/проверить.py

Требуется jsonschema (pip install jsonschema).
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

try:
    from jsonschema import Draft202012Validator
    from referencing import Registry, Resource
except ImportError:  # pragma: no cover
    print("нужен jsonschema: pip install jsonschema referencing")
    sys.exit(2)

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
SCHEMAS = ROOT / "schemas"

PREFIX_SCHEMA = {
    "MG": "core/mission-goal", "ND": "core/need", "SV": "core/service",
    "RQ": "core/requirement", "CO": "core/conops", "CM": "core/component",
    "IF": "core/interface", "WB": "core/wbs-element", "AL": "core/alternative",
    "CE": "core/cost-estimate", "OD": "core/oda", "RSK": "core/risk",
    "TL": "core/technology", "RF": "core/review-item", "AP": "core/ai-profile",
    "CN": "core/constellation", "SP": "contracts/spacecraft",
    "DM": "contracts/demand-map", "GS": "core/ground-stations",
    "PA": "contracts/protocol-adapter", "TP": "contracts/terminal-profile",
    "SC": "core/scenario", "PJ": "core/project",
}


def registry() -> Registry:
    reg = Registry()
    for path in SCHEMAS.rglob("*.json"):
        doc = json.loads(path.read_text())
        if "$id" in doc:
            reg = reg.with_resource(doc["$id"], Resource.from_contents(doc))
    return reg


def schema_of(name: str) -> dict:
    return json.loads((SCHEMAS / f"{name}.schema.json").read_text())


def objects_of(payload) -> list:
    """Пакет канала ИИ — массив предложений; пакет импорта — {objects: [...]}"""
    if isinstance(payload, list):
        return payload
    return payload.get("objects", [])


def main() -> int:
    reg = registry()
    total = bad = 0
    for path in sorted(HERE.glob("*.json")):
        payload = json.loads(path.read_text())
        items = objects_of(payload)
        errors: list[str] = []
        seen: dict[str, int] = {}
        for i, item in enumerate(items):
            oid = str(item.get("id", ""))
            prefix = oid.split("-")[0]
            seen[oid] = seen.get(oid, 0) + 1
            name = PREFIX_SCHEMA.get(prefix)
            if name is None:
                errors.append(f"[{i}] {oid or '<без id>'}: неизвестный вид объекта")
                continue
            validator = Draft202012Validator(schema_of(name), registry=reg)
            for e in sorted(validator.iter_errors(item), key=lambda e: list(e.path)):
                where = "/" + "/".join(str(p) for p in e.path)
                errors.append(f"[{i}] {oid}: {where}: {e.message}")
        for oid, count in seen.items():
            if count > 1:
                errors.append(f"{oid}: повторяется {count} раза — идентификаторы не переиспользуются")

        total += len(items)
        bad += len(errors)
        mark = "+" if not errors else "-"
        print(f"  {mark} {path.name}: объектов {len(items)}, замечаний {len(errors)}")
        for e in errors[:8]:
            print(f"      {e}")
        if len(errors) > 8:
            print(f"      … ещё {len(errors) - 8}")

    print(f"\nВсего объектов {total}, замечаний {bad}")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
