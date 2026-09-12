#!/usr/bin/env python3
"""Мера правила промпта (поставка 12.09, ЗАМЕЧАНИЯ-ПМИ5-СЦЕНА-3): у каждой
стороны проекта ≥ 1 нужда, и стороны пакета `ПАКЕТ-СТОРОНЫ-И-НУЖДЫ.json`
найдены в проекте по имени. Читает проект на стенде и сверяет с пакетом;
возвращает 1, если сторона без нужды есть либо сторона пакета не найдена.

  python3 tools/v2/check_stakeholder_needs.py --project PJ-… [--base URL] [--packet файл]
"""
from __future__ import annotations

import argparse
import http.cookiejar
import json
import pathlib
import re
import sys
import urllib.error
import urllib.request

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import stand_session  # noqa: E402

КОРЕНЬ = pathlib.Path(__file__).resolve().parent.parent.parent
ПАКЕТ = КОРЕНЬ / "docs/tz/v2/поставка-09-12/ПАКЕТ-СТОРОНЫ-И-НУЖДЫ.json"
_opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))


def вызов(base: str, путь: str):
    з = urllib.request.Request(base + путь, headers={"Content-Type": "application/json; charset=utf-8"})
    try:
        with _opener.open(з, timeout=60) as о:
            return json.loads(о.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        raise SystemExit(f"GET {путь} → {e.code}: {e.read().decode()[:200]}")


def норм(s: str) -> str:
    return re.sub(r"[^\wа-яё]+", " ", (s or "").lower().replace("ё", "е")).strip()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--base", default="http://localhost:8080/api")
    ap.add_argument("--project", required=True)
    ap.add_argument("--packet", default=str(ПАКЕТ))
    a = ap.parse_args()
    stand_session.войти(a.base, _opener, "chernov")
    стороны = вызов(a.base, f"/v2/entities?project={a.project}&kind=stakeholder").get("items", [])
    нужды = вызов(a.base, f"/v2/entities?project={a.project}&kind=need").get("items", [])
    по_стороне = {с["id"]: [] for с in стороны}
    for н in нужды:
        for владелец in н.get("owned_by") or []:
            по_стороне.setdefault(владелец, []).append(н)
    без_нужд = [с for с in стороны if not по_стороне.get(с["id"])]
    с_влиянием = [с for с in стороны if с["doc"].get("influence")]
    print(f"проект {a.project}: сторон {len(стороны)}, нужд {len(нужды)}, сторон без нужд {len(без_нужд)}, с влиянием {len(с_влиянием)}")
    for с in без_нужд:
        print("  без нужд:", с["code"], с["doc"].get("name"))
    пакет = json.loads(pathlib.Path(a.packet).read_text(encoding="utf-8"))
    имена = [норм(с["doc"].get("name", "")) for с in стороны]
    не_найдены = []
    for item in пакет["items"]:
        части = [норм(ч) for ч in re.split(r"\s*/\s*", item["name"])]
        if not any(н == норм(item["name"]) or н in части or (len(н) > 8 and н in норм(item["name"])) for н in имена):
            не_найдены.append(item["name"])
    print(f"стороны пакета ({len(пакет['items'])}): не найдены в проекте — {len(не_найдены)}")
    for и in не_найдены:
        print("  нет:", и[:80])
    ok = not без_нужд and not не_найдены
    print("мера:", "выполнена — у каждой стороны есть нужда, стороны пакета на месте" if ok else "НЕ выполнена")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
