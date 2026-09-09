#!/usr/bin/env python3
"""Обмен на стенде (шип F): мера задания живьём, через маршруты v2.

  1. экспорт → импорт → экспорт совпадает: .sdoc проекта импортируется в
     свежий проект кандидатами, план принимается, повторный экспорт
     сравнивается с исходным по блокам требований;
  2. отпечаток сверяется: GET /v2/export/knowledge даёт отпечаток, POST
     /v2/export/knowledge/verify с ним — ok, с чужим — предупреждение;
     архив knowledge.zip несёт шапку с тем же отпечатком в каждом файле;
  3. пакет MCR — одним архивом: GET /v2/points/MCR/package.zip; печатается
     состав.

    python3 tools/v2/check_exchange.py --project PJ-V2-E2 [--base http://localhost:8080/api]
"""
import argparse
import http.cookiejar
import io
import json
import pathlib
import sys
import urllib.error
import urllib.request

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import stand_session  # noqa: E402
import zipfile

_opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))


def вызов(base, метод, путь, тело=None, сырой=False):
    данные = json.dumps(тело, ensure_ascii=False).encode() if тело is not None else None
    з = urllib.request.Request(base + путь, data=данные, headers={"Content-Type": "application/json; charset=utf-8"}, method=метод)
    try:
        with _opener.open(з, timeout=300) as о:
            сырое = о.read()
            return сырое if сырой else json.loads(сырое.decode() or "{}")
    except urllib.error.HTTPError as e:
        raise SystemExit(f"{метод} {путь} → {e.code}: {e.read().decode()[:400]}")


def войти(base, login):
    """Учётка стенда — в режиме stand; на стенде за входом через Telegram —
    одна учётка владельца через бота и роль «от имени» (stand_session)."""
    stand_session.войти(base, _opener, login)


def блоки_требований(sdoc: str) -> str:
    return sdoc[sdoc.find("[REQUIREMENT]"):] if "[REQUIREMENT]" in sdoc else ""


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--base", default="http://localhost:8080/api")
    ap.add_argument("--project", default="PJ-V2-E2")
    ap.add_argument("--into", default=None, help="код свежего проекта для импорта (по умолчанию <project>-IMP)")
    ap.add_argument("--reqif", default=None, help="чужой ReqIF (например, образец ReqPilot): импорт кандидатами, ничего не теряя")
    a = ap.parse_args()
    into = a.into or (a.project + "-IMP")
    войти(a.base, "chernov")
    ошибки = []

    # 1. экспорт → импорт → экспорт
    sdoc = вызов(a.base, "GET", f"/v2/export/sdoc?project={a.project}", сырой=True).decode()
    sgra = вызов(a.base, "GET", f"/v2/export/sdoc?project={a.project}&grammar=1", сырой=True).decode()
    print(f"экспорт {a.project}: .sdoc {len(sdoc)} знаков, требований {sdoc.count('[REQUIREMENT]')}, грамматика {len(sgra)} знаков")
    портфель = вызов(a.base, "GET", "/v2/projects").get("items", [])
    if not any(п.get("code") == into for п in портфель):
        вызов(a.base, "POST", "/v2/projects", {"name": f"Импорт .sdoc из {a.project}", "code": into})
    войти(a.base, "ivanov")
    импорт = вызов(a.base, "POST", f"/v2/import/sdoc?project={into}", {"sdoc": sdoc, "sgra": sgra, "author": "Иванов И."})
    print(f"импорт в {into}: кандидатов {импорт['count']}, задание {импорт.get('task')}, материал {импорт.get('material')}")
    if импорт.get("task"):
        план = вызов(a.base, "GET", f"/v2/intake/{импорт['task']}?project={into}")
        принято = вызов(a.base, "POST", f"/v2/intake/{импорт['task']}/accept?project={into}",
                        {"chosen": [д["index"] for д in план["actions"]], "author": "Иванов И."})
        print(f"принято: {принято.get('created')} → {', '.join(принято.get('codes', [])[:6])}")
    снова = вызов(a.base, "GET", f"/v2/export/sdoc?project={into}", сырой=True).decode()
    if блоки_требований(снова) == блоки_требований(sdoc):
        print("  экспорт → импорт → экспорт: блоки требований совпали побайтно")
    else:
        ошибки.append("экспорт после импорта разошёлся с исходным")
        a1, a2 = блоки_требований(sdoc).splitlines(), блоки_требований(снова).splitlines()
        for i, (x, y) in enumerate(zip(a1, a2)):
            if x != y:
                print(f"  !!! строка {i}: «{x[:80]}» ≠ «{y[:80]}»"); break
        if len(a1) != len(a2):
            print(f"  !!! строк {len(a1)} ≠ {len(a2)}")

    # 2. отпечаток
    войти(a.base, "chernov")
    знания = вызов(a.base, "GET", f"/v2/export/knowledge?project={a.project}")
    отпечаток = знания["fingerprint"]
    print(f"знания: отпечаток {отпечаток}, частей {len(знания['parts'])}: " + ", ".join(f"{ч['file']} {ч['size_kb']} КБ" for ч in знания["parts"]))
    свой = вызов(a.base, "POST", f"/v2/export/knowledge/verify?project={a.project}", {"knowledge_fingerprint": отпечаток})
    чужой = вызов(a.base, "POST", f"/v2/export/knowledge/verify?project={a.project}", {"knowledge_fingerprint": "0000000000000000"})
    print(f"  сверка своего: ok={свой['ok']}; чужого: ok={чужой['ok']} — {чужой.get('warning', '')[:90]}")
    if not свой["ok"] or чужой["ok"]:
        ошибки.append("сверка отпечатка не различает свой и чужой")
    архив = вызов(a.base, "GET", f"/v2/export/knowledge.zip?project={a.project}", сырой=True)
    with zipfile.ZipFile(io.BytesIO(архив)) as z:
        имена = z.namelist()
        шапки = [z.read(n).decode().splitlines()[0] for n in имена]
    if all(отпечаток in ш for ш in шапки):
        print(f"  архив knowledge.zip: {len(имена)} файлов, в каждом шапка с отпечатком {отпечаток}")
    else:
        ошибки.append("в архиве знаний шапка без отпечатка")
    факты = next((n for n in имена if "факты" in n), None)
    if факты:
        with zipfile.ZipFile(io.BytesIO(архив)) as z:
            текст = z.read(факты).decode()
        print("  факты:", " · ".join(l.strip("# ") for l in текст.splitlines() if l.startswith("## ")))

    # 3. пакет MCR
    пакет = вызов(a.base, "GET", f"/v2/points/MCR/package.zip?project={a.project}", сырой=True)
    with zipfile.ZipFile(io.BytesIO(пакет)) as z:
        состав = z.namelist()
        манифест = z.read("00-МАНИФЕСТ.md").decode()
    print(f"пакет MCR: {len(пакет)} байт, {len(состав)} файлов:")
    for имя in состав:
        print("   -", имя)
    if not any(n.startswith("документы/") for n in состав):
        print("  (документов нет — в проекте не заведён ни один документ фазы)")
    if "требования/" not in "".join(состав):
        print("  !!! .sdoc не приложен:", [l for l in манифест.splitlines() if "StrictDoc" in l])
        ошибки.append("в пакете точки нет .sdoc")
    if отпечаток not in манифест:
        ошибки.append("манифест пакета без отпечатка знаний")

    # 4. чужой ReqIF — кандидаты со всеми атрибутами, в модель ничего до приёма
    if a.reqif:
        xml = pathlib.Path(a.reqif).read_text(encoding="utf-8")
        войти(a.base, "ivanov")
        было = len(вызов(a.base, "GET", f"/v2/entities?project={into}&kind=requirement").get("items", []))
        чужой = вызов(a.base, "POST", f"/v2/import/reqif?project={into}", {"xml": xml, "author": "Иванов И."})
        стало = len(вызов(a.base, "GET", f"/v2/entities?project={into}&kind=requirement").get("items", []))
        к = чужой.get("candidates", [])
        print(f"чужой ReqIF {pathlib.Path(a.reqif).name}: кандидатов {чужой.get('count')}, задание {чужой.get('task')}")
        for c in к[:4]:
            print(f"   - {c['code']} [{c['type']}] «{c['statement'][:60]}» · код из {c['used'].get('code')}, формулировка из {c['used'].get('statement')}, чужих полей {len(c['foreign_attributes'])}")
        if стало != было:
            ошибки.append("импорт чужого ReqIF записал в модель до приёма")
        if not к or any(not c["foreign_attributes"] for c in к):
            ошибки.append("кандидаты без чужих атрибутов — что-то потеряно")
    print("\nитог:", "всё сошлось" if not ошибки else "; ".join(ошибки))
    return 1 if ошибки else 0


if __name__ == "__main__":
    sys.exit(main())
