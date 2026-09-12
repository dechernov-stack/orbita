#!/usr/bin/env python3
"""Пакет «стороны и нужды» (поставка владельца, `ПАКЕТ-СТОРОНЫ-И-НУЖДЫ.json`)
→ сцена 3 проекта v2: 14 сторон · 43 нужды с метками источников.

Правило пакета: нужды §2.1 записки — общие, привязаны к сторонам по смыслу
[И]; нужды из колонки «роль/интерес» §3 — по стороне [П]. У нужды один
носитель, поэтому общая нужда ложится каждой своей стороне отдельной
записью (как и считает пакет: 43). Сторона узнаётся по имени (точное,
часть до « / », вхождение) — уже заведённые разбором не дублируются; нужда
узнаётся по формулировке у той же стороны.

  python3 tools/v2/load_stakeholders_needs.py --project PJ-… [--base URL] [--apply]

Без --apply — только план: что найдено, что будет заведено. Вход на стенд —
stand_session (учётка стенда либо Telegram с ролью «ведущий СИ»).
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
РОЛИ = {
    "заказчик": "customer", "регулятор": "regulator", "оператор": "operator", "потребитель": "consumer",
    "поставщик": "supplier", "партнёр": "partner", "партнер": "partner", "учреждаемый": "established",
}
ВЛИЯНИЕ = {"решает": "decides", "влияет": "influences", "информируется": "informed"}
АВТОР = "Иванов И."

_opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))


def вызов(base: str, метод: str, путь: str, тело=None):
    данные = json.dumps(тело, ensure_ascii=False).encode() if тело is not None else None
    з = urllib.request.Request(base + путь, data=данные, headers={"Content-Type": "application/json; charset=utf-8"}, method=метод)
    try:
        with _opener.open(з, timeout=120) as о:
            сырое = о.read().decode()
            return json.loads(сырое) if сырое else {}
    except urllib.error.HTTPError as e:
        raise SystemExit(f"{метод} {путь} → {e.code}: {e.read().decode()[:300]}")


def норм(s: str) -> str:
    return re.sub(r"[^\wа-яё]+", " ", (s or "").lower().replace("ё", "е")).strip()


def найти_сторону(имя: str, стороны: list[dict]) -> dict | None:
    н = норм(имя)
    части = [норм(ч) for ч in re.split(r"\s*/\s*", имя)]
    for с in стороны:
        if норм(с["doc"].get("name", "")) == н:
            return с
    for с in стороны:
        сн = норм(с["doc"].get("name", ""))
        if сн in части or (len(сн) > 8 and сн in н):
            return с
    return None


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--base", default="http://localhost:8080/api")
    ap.add_argument("--project", required=True)
    ap.add_argument("--packet", default=str(ПАКЕТ))
    ap.add_argument("--apply", action="store_true", help="заводить; без флага — только план")
    a = ap.parse_args()
    пакет = json.loads(pathlib.Path(a.packet).read_text(encoding="utf-8"))
    stand_session.войти(a.base, _opener, "ivanov")
    p = a.project
    стороны = вызов(a.base, "GET", f"/v2/entities?project={p}&kind=stakeholder").get("items", [])
    нужды = вызов(a.base, "GET", f"/v2/entities?project={p}&kind=need").get("items", [])
    # носитель нужды — связью owns: список сущностей отдаёт id носителей в owned_by
    код_по_id = {с["id"]: с["code"] for с in стороны}
    def носитель(н: dict) -> str:
        владельцы = н.get("owned_by") or []
        return код_по_id.get(владельцы[0], владельцы[0]) if владельцы else ""
    имеющиеся = {(норм((н.get("doc") or {}).get("statement", "")), носитель(н)) for н in нужды}
    план: list[str] = []
    заведено_сторон = заведено_нужд = найдено_сторон = пропущено_нужд = 0
    for item in пакет["items"]:
        сторона = найти_сторону(item["name"], стороны)
        код = сторона["code"] if сторона else None
        if сторона:
            найдено_сторон += 1
            план.append(f"  = сторона «{item['name'][:50]}» → {код} ({сторона['doc'].get('name','')[:40]})")
        else:
            интерес = "; ".join(n["statement"] for n in item["needs"] if n.get("source_mark") == "П")[:400] or item["needs"][0]["statement"]
            тело = {
                "name": item["name"], "role": РОЛИ.get(item["role"], "consumer"), "interest": интерес,
                "notes": f"масштаб: {item.get('scale','')}; влияние: {item.get('influence','')} ({ВЛИЯНИЕ.get(item.get('influence',''), '')}), сила {item.get('power','')} — пакет ПМИ-5 сцена 3 (записка §3)",
                "author": АВТОР,
            }
            if тело["role"] == "established":
                тело["establishes"] = True
            план.append(f"  + сторона «{item['name'][:50]}» ({тело['role']})")
            if a.apply:
                ответ = вызов(a.base, "POST", f"/v2/stakeholders?project={p}", тело)
                код = ответ["code"]
                стороны.append({"id": ответ.get("id", ""), "code": код, "doc": {"name": item["name"]}})
                заведено_сторон += 1
        for n in item["needs"]:
            ключ = (норм(n["statement"]), код or "")
            if код and ключ in имеющиеся:
                пропущено_нужд += 1
                continue
            метка = f"[{n.get('source_mark','')}] {n.get('source','')}" + (" · общая нужда §2.1" if n.get("shared") else "")
            план.append(f"      + нужда [{n.get('source_mark','')}] {n['statement'][:70]}")
            if a.apply and код:
                вызов(a.base, "POST", f"/v2/needs?project={p}", {"statement": n["statement"], "owner": код, "notes": метка + " — пакет ПМИ-5 сцена 3", "author": АВТОР})
                имеющиеся.add(ключ)
                заведено_нужд += 1
    print("\n".join(план))
    print(f"\nсторон в пакете {len(пакет['items'])}: найдено {найдено_сторон}, заведено {заведено_сторон}; нужд заведено {заведено_нужд}, уже были {пропущено_нужд}" + ("" if a.apply else " (план; добавьте --apply)"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
