#!/usr/bin/env python3
"""Режим `tor` на настоящем ТЗ (ответ владельца 09.09, п. 1).

Проект заводится с нуждами из пакета разбора записки; текст ТЗ кладётся
материалом типа `tor` и разбирается живой моделью с заданием «это ТЗ —
оцени против нужд». Печатается: факты и действия по видам, оценка
против нужд (покрыто · не покрыто · требование без нужды), сверка с
эталонным пакетом `ПАКЕТ-РАЗБОР-ТЗ.json` по классам и с ожидаемыми
дырами владельца.

    python3 tools/v2/check_tor_mode.py --tz <txt> [--project PJ-V2-TZ]
"""
import argparse
import json
import pathlib
import sys
import urllib.error
import urllib.request

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import stand_session  # noqa: E402
from collections import Counter

КОРЕНЬ = pathlib.Path(__file__).resolve().parent.parent.parent
ЗАПИСКА = КОРЕНЬ / "docs/tz/manual-run-2/пачка-1/ПАКЕТ-РАЗБОР-ЗАПИСКИ.json"
ЭТАЛОН = КОРЕНЬ / "docs/tz/v2/поставка-09-09/ПАКЕТ-РАЗБОР-ТЗ.json"
# Дыры владельца — по СМЫСЛУ, а не по словоформе: «нет гарантии доставки» и
# «гарантированная доставка не задана» — одна дыра. Слово владельца → основы.
ОЖИДАЕМЫЕ_ДЫРЫ = {"Арктик": ["арктик"], "гарантирован": ["гарантир", "гарант"], "PNT": ["pnt"], "180": ["180"]}

_opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(__import__("http.cookiejar", fromlist=["CookieJar"]).CookieJar()))


def вызов(base, метод, путь, тело=None):
    данные = json.dumps(тело, ensure_ascii=False).encode() if тело is not None else None
    з = urllib.request.Request(base + путь, data=данные, headers={"Content-Type": "application/json; charset=utf-8"}, method=метод)
    try:
        with _opener.open(з, timeout=600) as о:
            return json.loads(о.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        raise SystemExit(f"{метод} {путь} → {e.code}: {e.read().decode()[:400]}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--base", default="http://localhost:8080/api")
    ap.add_argument("--project", default="PJ-V2-TZ")
    ap.add_argument("--tz", required=True, help="текст ТЗ (txt/md)")
    a = ap.parse_args()
    записка = json.loads(ЗАПИСКА.read_text(encoding="utf-8"))
    эталон = json.loads(ЭТАЛОН.read_text(encoding="utf-8"))
    текст = pathlib.Path(a.tz).read_text(encoding="utf-8")

    stand_session.войти(a.base, _opener, "chernov")
    портфель = вызов(a.base, "GET", "/v2/projects").get("items", [])
    if not any(п.get("code") == a.project for п in портфель):
        вызов(a.base, "POST", "/v2/projects", {"name": f"Проверка ТЗ МКУСС · {a.project}", "code": a.project})
        вызов(a.base, "POST", f"/v2/intent?project={a.project}",
              {"for_whom": "заказчик МКУСС", "what": "узкополосная связь и передача данных", "where": "РФ, Арктика, СМП",
               "horizon": "2030", "accepted": True, "author": "Чернов Д."})
    stand_session.войти(a.base, _opener, "ivanov")
    стороны = {с["doc"].get("name"): с["code"] for с in вызов(a.base, "GET", f"/v2/entities?project={a.project}&kind=stakeholder").get("items", [])}
    if not стороны:
        for с in [i for i in записка["items"] if i["class"] == "stakeholder"][:6]:
            r = вызов(a.base, "POST", f"/v2/stakeholders?project={a.project}", {"name": с["name"], "role": "customer" if "заказчик" in с.get("role", "") else "consumer", "author": "Иванов И."})
            стороны[с["name"]] = r.get("code")
    нужды = вызов(a.base, "GET", f"/v2/entities?project={a.project}&kind=need").get("items", [])
    if not нужды:
        владелец = next(iter(стороны.values()))
        for н in [i for i in записка["items"] if i["class"] == "need"]:
            вызов(a.base, "POST", f"/v2/needs?project={a.project}", {"statement": н["statement"], "owner": владелец, "author": "Иванов И."})
        нужды = вызов(a.base, "GET", f"/v2/entities?project={a.project}&kind=need").get("items", [])
    print(f"проект {a.project}: нужд {len(нужды)}")
    for н in нужды:
        print("  ", н["code"], н["doc"].get("statement", "")[:80])

    материалы = {м["name"]: м["code"] for м in вызов(a.base, "GET", f"/v2/materials?project={a.project}").get("items", [])}
    имя = "ТЗ МКУСС ПД (владелец)"
    код = материалы.get(имя) or вызов(a.base, "POST", f"/v2/materials?project={a.project}",
                                        {"name": имя, "kind": "tor", "text": текст, "author": "Иванов И."})["code"]
    print(f"материал {код}: {len(текст)} знаков; разбор живой моделью…")
    разбор = вызов(a.base, "POST", f"/v2/intake/atomize?project={a.project}",
                   {"material": код, "intent": "это ТЗ — оцени против нужд", "author": "Иванов И."})
    print("разбор:", разбор.get("note", "")[:200])
    if разбор.get("refusals"):
        print("  отклонено:", len(разбор["refusals"]), "·", "; ".join(разбор["refusals"][:3])[:200])
    задание = разбор.get("task")
    if not задание:
        print("!!! задания с планом нет — оценка не построена"); return 1
    план = вызов(a.base, "GET", f"/v2/intake/{задание}?project={a.project}")
    факты = [ф for ф in вызов(a.base, "GET", f"/v2/facts?project={a.project}").get("items", []) if ф.get("material") == код]
    print(f"факты: {len(факты)} —", dict(Counter(ф.get("kind") for ф in факты)))
    print(f"действия плана: {len(план['actions'])} —", dict(Counter(д.get("target_kind") for д in план["actions"])))
    оценка = план.get("assessment") or {}
    строки = оценка.get("lines", [])
    print(f"оценка: строк {len(строки)}, вердикты {dict(Counter(л.get('verdict') for л in строки))}")
    непокрытые = оценка.get("uncovered_needs", [])
    print("  непокрытые нужды:", ", ".join(непокрытые) or "нет")
    for н in нужды:
        if н["code"] in непокрытые:
            print("     ←", н["code"], н["doc"].get("statement", "")[:80])
    сироты = оценка.get("orphan_requirements", [])
    print("  требования без нужды:", len(сироты))
    по_коду = {ф["id"]: ф for ф in факты}
    for к in сироты[:12]:
        ф = по_коду.get(к, {})
        print("     ←", к, (ф.get("subject", "") + ": " + ф.get("predicate", ""))[:110])
    частичные = [л for л in строки if л.get("verdict") == "partial"]
    for л in частичные[:8]:
        print("  частично:", (л.get("text") or л.get("requirement", ""))[:80], "→", ", ".join(л.get("needs", [])), "·", л.get("note", "")[:90])
    формулировки = {н["code"]: н["doc"].get("statement", "") for н in нужды}
    print("\nот нужды (вердикт · дыра словами):")
    for н in оценка.get("needs", []):
        print(f"   {н.get('need')} «{формулировки.get(н.get('need'), '')[:60]}»")
        print(f"   {н['need']} {н.get('verdict'):<10} {н.get('gap','')[:110]}")
    дыры = оценка.get("gaps", [])
    print(f"\nдыры ТЗ ({len(дыры)}):")
    for д in дыры[:30]:
        print("   -", д[:140])

    # Правило атомизации (ШИП-G-ПРИНЯТ): каждое требование, функция и сервис ТЗ —
    # отдельный факт; сверка живого разбора с пакетом — ПО КЛАССАМ (42 сущности):
    # классы фактов и действия плана против классов пакета. Вехи действий не дают
    # (даты точек задаёт план фазы) — сравниваются фактами.
    print(f"\nсверка с эталонным пакетом по классам ({len(эталон['items'])} сущностей; факты [класс] · действия плана):")
    эт = Counter(i["class"] for i in эталон["items"])
    наши = Counter(д.get("target_kind") for д in план["actions"])
    классы = Counter(ф.get("entity_class") or "" for ф in факты)
    соответствие = {"requirement": "requirement", "stakeholder": "stakeholder", "service": "service", "constraint": "constraint",
                    "composition_node": "component", "milestone": "gate", "normative_ref": "normative_document", "function": "requirement"}
    пусто = []
    for кл, n in sorted(эт.items(), key=lambda x: -x[1]):
        вид = соответствие.get(кл, кл)
        фактов = классы.get(кл, 0)
        действий = наши.get(вид, 0)
        помета = ""
        if фактов == 0 and n > 0:
            помета = "  !!! фактов этого класса нет"
            пусто.append(кл)
        elif действий == 0 and кл != "milestone":
            помета = "  ! действий плана нет"
        print(f"   {кл:<18} пакет {n:>2} · фактов [{кл}] {фактов:>2} · действий {вид}: {действий}{помета}")
    print("   фактов без класса:", классы.get("", 0))
    if пусто:
        print(f"!!! классы пакета без единого факта: {', '.join(пусто)} — разбор не атомизировал ТЗ по правилу")
    print("\nожидаемые дыры владельца (по словам в дырах и причинах строк):")
    # Дыра — это и слова самого факта, если он не покрывает нужду полностью:
    # «состав ОГ — не менее 180 МКА» частично без обоснования нуждой = дыра «180 МКА».
    текст_дыр = "\n".join(дыры) + "\n" + "\n".join(л.get("note", "") for л in строки) + "\n" + \
        "\n".join(н.get("gap", "") for н in оценка.get("needs", [])) + "\n" + \
        "\n".join(л.get("text", "") for л in строки if л.get("verdict") != "covers")
    не_видно = 0
    for слово, основы in ОЖИДАЕМЫЕ_ДЫРЫ.items():
        есть = any(о in текст_дыр.lower() for о in основы)
        не_видно += 0 if есть else 1
        print(f"   «{слово}»: {'есть' if есть else 'НЕТ'}")
    if не_видно:
        print(f"!!! {не_видно} из {len(ОЖИДАЕМЫЕ_ДЫРЫ)} ожидаемых дыр оценка не назвала")
    return 1 if не_видно else 0


if __name__ == "__main__":
    sys.exit(main())
