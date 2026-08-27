#!/usr/bin/env python3
"""Канонизация единиц живого стенда к СИ (спринт MVP, триаж §2).

Служебная волна: правки идут автором ci-runner с одним основанием — якорь
помет двигается, статус наследуется (ADR-031), пометы не загораются.

    python3 tools/canonize_units.py [http://localhost:8080/api] [PJ-0001]
"""
import json, sys, urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080/api"
PROJECT = sys.argv[2] if len(sys.argv) > 2 else "PJ-0001"
FACTORS = {"min": ("s", 60.0), "h": ("s", 3600.0), "m^3": ("m3", 1.0), "dm3": ("m3", 0.001)}
NOTE = "канонизация единиц к СИ (словарь МВП): min/h → s, m^3/dm3 → m3"


def req(path, payload=None):
    data = json.dumps(payload).encode() if payload is not None else None
    r = urllib.request.Request(f"{BASE}{path}", data=data,
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(r) as resp:
        return json.load(resp)


def canon(node):
    changed = []
    if isinstance(node, dict):
        u = node.get("unit")
        if isinstance(u, str) and u in FACTORS:
            dst, k = FACTORS[u]
            node["unit"] = dst
            for f in ("value", "value_max", "tolerance"):
                if isinstance(node.get(f), (int, float)):
                    node[f] = node[f] * k
            changed.append(f"{u}->{dst}")
        for v in node.values():
            changed += canon(v)
    elif isinstance(node, list):
        for x in node:
            changed += canon(x)
    return changed


rows = req(f"/objects?type=requirement&project={PROJECT}")
touched = 0
for row in rows:
    obj = req(f"/objects/{row['id']}")
    doc = obj["doc"]
    changes = canon(doc)
    if not changes:
        continue
    req(f"/objects/{row['id']}/change?project={PROJECT}",
        {"doc": doc, "change_ref": NOTE, "author": "ci-runner"})
    touched += 1
    print(f"  {row['id']}: {', '.join(sorted(set(changes)))}")
print(f"канонизировано объектов: {touched}")
