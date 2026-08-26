#!/usr/bin/env python3
"""Нитка Б.1: шаблоны документов — в библиотечную область стенда.
Источник: data/library/document-templates.json (данные бывшего enum).
Идемпотентно: существующие коды пропускаются.
Запуск: python3 tools/seed_templates.py [http://localhost:8080/api]
"""
import json
import sys
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080/api"
AUTHOR = "переезд шаблонов из enum (нитка Б.1)"


def call(method, path, body=None):
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(body, ensure_ascii=False).encode() if body is not None else None,
        headers={"Content-Type": "application/json; charset=utf-8"}, method=method)
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read().decode())


have = {t["code"] for t in call("GET", "/export/documents")}
data = json.load(open("data/library/document-templates.json"))
for t in data["templates"]:
    if t["code"] in have:
        print(f"  = {t['code']} уже на полке")
        continue
    doc = {"code": t["code"], "name": t["title"], "source": t["source"],
           "sections": t["sections"]}
    out = call("POST", "/library/objects",
               {"type": "document_template", "doc": doc, "author": AUTHOR})
    print(f"  + {out['id']}  {t['code']}  {t['title']}")
print("готово")
