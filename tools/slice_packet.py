#!/usr/bin/env python3
"""Нарезка среза пакета для ДРУГОГО проекта (Г-01, п. 1): перемап ссылок по
смысловому ключу — обязанность нарезки, а не ручная правка JSON.

Срез пакетов из журнала несёт ссылки исходного проекта (ND-0101 из PJ-0001).
Изоляция проектов честно их режет (ADR-022), но инженер оставался с ручной
правкой JSON. Скрипт берёт пакет, спрашивает у сервера сопоставление по
совпадению формулировки (/views/link-mapping — тем же расчётом, что диалог на
экране) и переписывает ссылки на объекты целевого проекта. Несопоставленное
остаётся как есть и честно даст разрыв трассировки при вставке; отчёт — на
экран. Изоляция не ослабляется: скрипт ничего не пишет в модель.

    python3 tools/slice_packet.py --project PJ-0004 ПАКЕТ-Г1-СЕРВИСЫ.json > срез-для-PJ-0004.json
    python3 tools/slice_packet.py --project PJ-0004 --min 60 пакет.json   # порог совпадения, %
"""
import argparse
import json
import os
import sys
import urllib.request

BASE = os.environ.get("ORBITA_API", "http://localhost:8080/api")


def call(path: str, body=None):
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(body, ensure_ascii=False).encode() if body is not None else None,
        headers={"Content-Type": "application/json; charset=utf-8"},
        method="POST" if body is not None else "GET",
    )
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read().decode())


def remap(node, mapping: dict):
    """Ссылки перезаписываются везде, где стоит строка-ключ; структура не меняется."""
    if isinstance(node, dict):
        return {k: remap(v, mapping) for k, v in node.items()}
    if isinstance(node, list):
        return [remap(x, mapping) for x in node]
    if isinstance(node, str) and node in mapping:
        return mapping[node]
    return node


def main() -> int:
    ap = argparse.ArgumentParser(description="перемап ссылок пакета на целевой проект по смыслу")
    ap.add_argument("packet")
    ap.add_argument("--project", required=True, help="целевой проект, PJ-NNNN")
    ap.add_argument("--min", type=int, default=50, help="минимальное совпадение формулировки, %% (по умолчанию 50)")
    args = ap.parse_args()

    packet = json.load(open(args.packet, encoding="utf-8"))
    items = packet.get("items") or packet.get("objects") or packet
    if not isinstance(items, list):
        sys.exit("пакет обязан нести массив items либо objects")

    view = call(f"/views/link-mapping?project={args.project}", {"items": items})
    mapping, unmapped = {}, []
    for link in view.get("links", []):
        best = link.get("suggested")
        if best and best.get("percent", 0) >= args.min:
            mapping[link["ref"]] = best["id"]
        else:
            unmapped.append(link)

    out = dict(packet) if isinstance(packet, dict) else {"items": packet}
    key = "items" if "items" in out else ("objects" if "objects" in out else "items")
    out[key] = remap(items, mapping)
    out["_slice"] = {
        "target_project": args.project,
        "remapped": mapping,
        "unmapped": [l["ref"] for l in unmapped],
        "note": "ссылки перемаплены по совпадению формулировки; несопоставленные оставлены — при вставке дадут разрыв трассировки",
    }
    json.dump(out, sys.stdout, ensure_ascii=False, indent=2)
    print(file=sys.stdout)
    print(f"чужих ссылок: {view.get('foreign', 0)}; перемаплено: {len(mapping)}; без пары: {len(unmapped)}", file=sys.stderr)
    for l in unmapped:
        print(f"  · {l['ref']} — {str(l.get('text', ''))[:80]}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
