#!/usr/bin/env python3
"""Справочники системы на полку LIB: единицы (UR, СПРАВОЧНИК-ЕДИНИЦ.md) и
глоссарий (GL, Ф-03 — смысловые подсказки данными, не хардкодом клиента).

Запуск: python3 tools/seed_registries.py [http://localhost:8080/api]
Идемпотентно: непустой справочник на полке повторно не заливается — их
правка идёт «Загрузить пачкой» новыми версиями, не пересозданием.
"""
import json
import pathlib
import sys
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080/api"
ROOT = pathlib.Path(__file__).resolve().parent.parent
PACKETS = ROOT / "docs/tz/manual-run/packets"

SEEDS = [
    ("unit_registry", "/library/unit-registry", "07-справочник-единиц.json"),
    ("glossary", "/library/glossary", "08-глоссарий.json"),
]


def call(method: str, path: str, body=None):
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(body, ensure_ascii=False).encode() if body is not None else None,
        headers={"Content-Type": "application/json; charset=utf-8"},
        method=method,
    )
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read().decode())


def obsolete(type_: str, rows: list) -> bool:
    """Справочник на полке старее сида по СОСТАВУ полей — не по версии:
    написания единиц (Д1) добавились к существующим записям."""
    if type_ != "unit_registry":
        return False
    return not any("spellings" in d for d in rows)


for type_, view, fname in SEEDS:
    rows = call("GET", view)
    packet = json.loads((PACKETS / fname).read_text())
    if rows and not obsolete(type_, rows):
        print(f"{type_}: уже на полке — пропуск")
        continue
    if rows:
        # правка справочника — новой версией объекта, не пересозданием:
        # ссылки на него (границы пачек, разбор Д1) обязаны пережить правку
        for obj in packet["objects"]:
            cur = call("GET", f"/objects/{obj['id']}")
            changes = {k: v for k, v in obj.items() if k not in ("id", "lifecycle")}
            call("PATCH", f"/edit/{obj['id']}", {
                "author": packet["author"], "base_version": cur["version"], "changes": changes,
            })
            print(f"{type_}: обновлён {obj['id']} (написания единиц для разбора Д1)")
        continue
    for obj in packet["objects"]:
        out = call("POST", "/library/objects", {"type": type_, "doc": obj, "author": packet["author"]})
        print(f"{type_}: залит {out['id']}")
