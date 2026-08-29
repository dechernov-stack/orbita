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


for type_, view, fname in SEEDS:
    if call("GET", view):
        print(f"{type_}: уже на полке — пропуск")
        continue
    packet = json.loads((PACKETS / fname).read_text())
    for obj in packet["objects"]:
        out = call("POST", "/library/objects", {"type": type_, "doc": obj, "author": packet["author"]})
        print(f"{type_}: залит {out['id']}")
