#!/usr/bin/env python3
"""Сборка пакетов полок из ПОСТАВОК ВЛАДЕЛЬЦА (ADR-051, ADR-052).

Три файла владельца — каркас PBS, интерфейсы, архитектура по Arcadia — лежат
рядом с пакетами и остаются ИСТИНОЙ: пакеты полки собираются из них этим
скриптом, а не правятся руками. Так поставка ред. N+1 не требует ручной
сверки: перезапуск даёт новый пакет, а `git diff` показывает, что изменилось.

Ссылки между полками пишутся КОДАМИ через «@»: `"owners": ["@PL-S", "@UT"]`.
Идентификаторов проекта в пачке быть не может — они появляются при взятии, и
канал полки (LibraryChannel) разрешает «@код» в узел или стык ТОГО проекта,
куда полка берётся. Пачка, разложенная по id, была бы верна ровно для одного
проекта.

Запуск: python3 tools/build_shelf_packets.py [--check]
    --check — не писать файлы, а сверить, что пакеты совпадают со сборкой
              (сторож CI: пакет, разошедшийся с поставкой, — это правка руками)
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PACKETS = ROOT / "docs/tz/manual-run/packets"
DELIVERY = ROOT / "docs/tz/manual-run-4"

AUTHOR = "поставка владельца 03.09: ПОЛКА-PBS.json · ПОЛКА-ИНТЕРФЕЙСЫ.json · ПОЛКА-АРХИТЕКТУРА-ARCADIA.json"
DRAFT = {"status": "Draft", "version": "1"}


def read(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def expects(params: list) -> list:
    """Анкета: поле, единица справочника, точка зрелости. Значения — в parameters."""
    out = []
    for p in params:
        field = {"key": p["key"], "name": p["name"]}
        if p.get("unit"):
            field["unit"] = p["unit"]
        if p.get("required_to"):
            field["required_to"] = p["required_to"]
        if p.get("hint") or p.get("note"):
            field["hint"] = p.get("hint") or p.get("note")
        out.append(field)
    return out


def pbs_fragment(src: dict) -> dict:
    """Каркас PBS ред. 3: только узлы. Стыки ушли на свою полку — держать их
    в двух полках значило бы заводить один стык дважды при взятии обеих."""
    ids = {}
    for n, node in enumerate(src["nodes"], start=9101):
        ids[node["code"]] = f"CM-{n}"
    objects = []
    for node in src["nodes"]:
        obj = {
            "id": ids[node["code"]],
            "name": node["name"],
            "kind": node["kind"],
            "code": node["code"],
            "lifecycle": dict(DRAFT),
        }
        if node.get("parent"):
            obj["parent"] = ids[node["parent"]]
        if node.get("level") is not None:
            obj["level"] = node["level"]
        if node.get("external"):
            obj["external"] = True
        # решение Б3-01 ред. 2: данные полны, флагов зависимости нет; элемент вне
        # рекомендованного набора класса несёт default_take=false — выбор у
        # инженера в окне взятия, канал снимает поле перед записью
        if node.get("default_take") is False:
            obj["default_take"] = False
        if node.get("default_quantity"):
            obj["default_quantity"] = node["default_quantity"]
        if node.get("params"):
            obj["expects"] = expects(node["params"])
        if node.get("desc"):
            obj.setdefault("expects", [])
            obj["description"] = node["desc"] if False else None
            del obj["description"]
        objects.append(obj)
    return {
        "id": "LF-9002",
        "name": f"{src['title']} (ред. 3 — узлы)",
        "shelf": "B5",
        "mission_class_ref": "MC-0001",
        "counters": {"component": len(objects)},
        "origin": {"project": "полка", "author": "поставка владельца: ШАБЛОН-PBS.md + ПОЛКА-PBS.json", "date": "2026-09-03"},
        "anonymized": False,
        "payload": {"objects": objects},
        "lifecycle": dict(DRAFT),
    }


def interfaces_fragment(src: dict) -> dict:
    """Стыки — рёбра дерева состава: стороны названы КОДАМИ узлов каркаса."""
    objects = []
    for n, iface in enumerate(src["interfaces"], start=9201):
        obj = {
            "id": f"IF-{n}",
            "name": iface["name"],
            "code": iface["code"],
            "type": iface["type"],
            "kind": iface["type"],
            "owners": [f"@{iface['a']}", f"@{iface['b']}"],
            "lifecycle": dict(DRAFT),
        }
        for field in ("direction", "standard", "icd_section"):
            if iface.get(field):
                obj[field] = iface[field]
        if iface.get("external"):
            obj["external"] = True
        if iface.get("default_take") is False:
            obj["default_take"] = False
        if iface.get("params"):
            obj["expects"] = expects(iface["params"])
        if iface.get("models"):
            obj["models"] = iface["models"]
        if iface.get("requirement_classes"):
            obj["requirement_classes"] = iface["requirement_classes"]
        objects.append(obj)
    return {
        "id": "LF-9004",
        "name": src["title"],
        "shelf": "B7",
        "mission_class_ref": "MC-0001",
        "counters": {"interface": len(objects)},
        "origin": {"project": "полка", "author": "поставка владельца: ПОЛКА-ИНТЕРФЕЙСЫ.json", "date": "2026-09-03"},
        "anonymized": False,
        "payload": {"objects": objects},
        "lifecycle": dict(DRAFT),
    }


def architecture_fragment(src: dict) -> dict:
    """Слои OA · SA · LA: способности, акторы стейкхолдерами, функции с
    распределением на узлы каркаса, обмены по стыкам, цепочки, логические
    компоненты. PA не дублируется: физическая правда — две другие полки."""
    objects = []
    caps = {}
    for n, cap in enumerate(src["OA"]["capabilities"], start=9301):
        caps[cap["code"]] = f"OC-{n}"
        obj = {
            "id": f"OC-{n}",
            "code": cap["code"],
            "name": cap["name"],
            "lifecycle": dict(DRAFT),
        }
        if cap.get("traced_to"):
            obj["traced_to_hint"] = cap["traced_to"]
        actors = [a["code"] for a in src["OA"]["actors"]]
        obj["actors"] = [a for a in actors if a in json.dumps(cap, ensure_ascii=False)] or []
        if not obj["actors"]:
            del obj["actors"]
        objects.append(obj)

    # актор — предложение в стейкхолдеры: заводится черновиком, роль из полки
    roles = {
        "потребитель": "consumer", "заказчик/потребитель": "customer", "оператор": "operator",
        "регулятор": "regulator", "поставщик": "supplier", "учреждаемый": "established",
        "партнёр": "partner",
    }
    activities = {}
    for act in src["OA"].get("activities", []):
        activities.setdefault(act["actor"], []).append(f"{act['code']} {act['name']}")
    for n, actor in enumerate(src["OA"]["actors"], start=9301):
        obj = {
            "id": f"SK-{n}",
            "name": actor["name"],
            "role": roles.get(actor.get("stakeholder_role", ""), "partner"),
            "lifecycle": dict(DRAFT),
        }
        доли = activities.get(actor["code"], [])
        obj["note"] = "актор архитектурной полки " + actor["code"] + (
            "; операционные активности: " + "; ".join(доли) if доли else ""
        )
        objects.append(obj)

    fns = {}
    for n, fn in enumerate(src["SA"]["functions"], start=9301):
        fns[fn["code"]] = f"FN-{n}"
    # обмен наружу заканчивается на АКТОРЕ, а не на функции системы: адресат
    # такого обмена — операционная активность (OA-03), её ведёт актор
    actor_ids = {a["code"]: f"SK-{9301 + i}" for i, a in enumerate(src["OA"]["actors"])}
    activity_owner = {a["code"]: a["actor"] for a in src["OA"].get("activities", [])}
    # цепочка называет способность: функция шага трассируется к ней
    cap_of_function = {}
    for chain in src["SA"]["functional_chains"]:
        for step in chain["steps"] + chain.get("ack", []):
            cap_of_function.setdefault(step, chain.get("capability"))
    exchanges_by_src = {}
    for ex in src["SA"]["exchanges"]:
        exchanges_by_src.setdefault(ex["src"], []).append(ex)
    for fn in src["SA"]["functions"]:
        obj = {
            "id": fns[fn["code"]],
            "code": fn["code"],
            "name": fn["name"],
            "level": "system",
            "traces_up": [],
            "allocated_to": [{"component": f"@{fn['allocated_to']}"}],
            "lifecycle": dict(DRAFT),
        }
        cap = cap_of_function.get(fn["code"])
        if cap:
            obj["traces_up"] = [{"ref": caps[cap]}]
        if fn.get("default_take") is False:
            obj["default_take"] = False
        for ex in exchanges_by_src.get(fn["code"], []):
            dst = ex["dst"]
            обмен = {"code": ex["code"], "name": ex["name"], "interface": f"@{ex['interface']}"}
            # обмен вне рекомендованного набора — зависимость видна по стыку,
            # флага у него нет: окно взятия считает её по ссылке
            if dst in fns:
                обмен["to"] = fns[dst]
            else:
                обмен["to"] = actor_ids[activity_owner[dst]]
                обмен["to_activity"] = dst
            obj.setdefault("exchanges", []).append(обмен)
        objects.append(obj)

    for n, chain in enumerate(src["SA"]["functional_chains"], start=9301):
        obj = {
            "id": f"FC-{n}",
            "code": chain["code"],
            "name": chain["name"],
            "steps": [{"function": fns[s]} for s in chain["steps"]],
            "traces_up": [],
            "lifecycle": dict(DRAFT),
        }
        if chain.get("capability"):
            obj["capability"] = caps[chain["capability"]]
            obj["traces_up"] = [caps[chain["capability"]]]
        if chain.get("ack"):
            obj["ack"] = [{"function": fns[a]} for a in chain["ack"]]
        if chain.get("requirement_kinds"):
            obj["requirement_kinds"] = chain["requirement_kinds"]
        if chain.get("default_take") is False:
            obj["default_take"] = False
        objects.append(obj)

    for n, lc in enumerate(src["LA"]["logical_components"], start=9301):
        lc_obj = {
            "id": f"LC-{n}",
            "code": lc["code"],
            "name": lc["name"],
            "functions": [fns[f] for f in lc["functions"]],
            "deployed_to": [f"@{c}" for c in lc["deployed_to"]],
            "lifecycle": dict(DRAFT),
        }
        if lc.get("default_take") is False:
            lc_obj["default_take"] = False
        objects.append(lc_obj)

    counters = {}
    for o in objects:
        kind = {"OC": "capability", "SK": "stakeholder", "FN": "function",
                "FC": "function_chain", "LC": "logical_component"}[o["id"][:2]]
        counters[kind] = counters.get(kind, 0) + 1
    return {
        "id": "LF-9005",
        "name": src["title"],
        "shelf": "B9",
        "mission_class_ref": "MC-0001",
        "counters": counters,
        "origin": {"project": "полка", "author": "поставка владельца: ПОЛКА-АРХИТЕКТУРА-ARCADIA.json", "date": "2026-09-03"},
        "anonymized": False,
        "payload": {"objects": objects},
        "lifecycle": dict(DRAFT),
        "diagrams": src.get("diagrams_for_reviews", []),
    }


def wbs_fragment(src: dict) -> dict:
    """Пакеты работ с ПАРАМИ к узлам состава: узел назван кодом («@PL-S»), как
    у стыков. Сквозной пакет (управление, СИ, SMA) узлов не имеет по
    построению — его стоимость идёт по задачам фазы, а не по составу."""
    ids = {}
    for n, pkg in enumerate(src["packages"], start=9401):
        ids[pkg["code"]] = f"WB-{n}"
    objects = []
    for pkg in src["packages"]:
        obj = {
            "id": ids[pkg["code"]],
            "code": pkg["code"],
            "name": pkg["name"],
            "lifecycle": dict(DRAFT),
        }
        if pkg.get("parent"):
            obj["parent"] = ids[pkg["parent"]]
        if pkg.get("scope"):
            obj["scope"] = pkg["scope"]
        if pkg.get("pbs_refs"):
            obj["component_refs"] = [f"@{c}" for c in pkg["pbs_refs"]]
        if pkg.get("phase_tasks"):
            obj["phase_tasks"] = pkg["phase_tasks"]
        if pkg.get("cross_cutting"):
            obj["cross_cutting"] = True
        if pkg.get("default_take") is False:
            obj["default_take"] = False
        objects.append(obj)
    return {
        "id": "LF-9006",
        "name": src["title"],
        "shelf": "B6",
        "mission_class_ref": "MC-0001",
        "counters": {"wbs_element": len(objects)},
        "origin": {"project": "полка", "author": "поставка владельца: ПОЛКА-WBS.json", "date": "2026-09-03"},
        "anonymized": False,
        "payload": {"objects": objects},
        "lifecycle": dict(DRAFT),
    }


def packet(comment: str, fragment: dict) -> dict:
    return {"_comment": comment, "author": AUTHOR, "objects": [fragment]}


BUILD = [
    (
        "18-каркас-pbs.json",
        "ПОЛКА-PBS.json",
        pbs_fragment,
        "ADR-051: каркас PBS ред. 3 — 135 узлов до шестого уровня с анкетами (expects), "
        "кодами, признаками «внешний» и «необязательный». Стыки ушли на полку интерфейсов "
        "(ADR-052): один стык в двух полках при взятии обеих завёлся бы дважды.",
    ),
    (
        "19-интерфейсы.json",
        "ПОЛКА-ИНТЕРФЕЙСЫ.json",
        interfaces_fragment,
        "ADR-052: полка интерфейсов — 26 стыков восьми типов, стороны названы КОДАМИ узлов "
        "каркаса («@PL-S»), анкета стыка в expects, раздел ICD и читающие модели. Интерфейс — "
        "ребро дерева состава и носитель интерфейсных требований.",
    ),
    (
        "20-архитектура-arcadia.json",
        "ПОЛКА-АРХИТЕКТУРА-ARCADIA.json",
        architecture_fragment,
        "ADR-052: архитектурная полка по Arcadia — способности (OA), акторы предложением в "
        "стейкхолдеры, 25 функций с распределением на узлы каркаса и обменами по стыкам, "
        "6 цепочек, 9 логических компонентов с развёртыванием. PA не дублируется.",
    ),
    (
        "22-wbs.json",
        "ПОЛКА-WBS.json",
        wbs_fragment,
        "ADR-053: полка WBS — 54 пакета работ (NPR 7120.5 прил. G с адаптацией) и 44 пары "
        "с узлами каркаса кодами («@PL-S»); сквозные пакеты узлов не имеют, их стоимость "
        "идёт по задачам фазы. Узел L4/L5 наследует пакет родителя.",
    ),
]


def main() -> int:
    check = "--check" in sys.argv
    bad = 0
    for name, source, build, comment in BUILD:
        src = read(DELIVERY / source if (DELIVERY / source).exists() else PACKETS / source)
        built = packet(comment, build(src))
        target = PACKETS / name
        text = json.dumps(built, ensure_ascii=False, indent=2) + "\n"
        if check:
            current = target.read_text(encoding="utf-8") if target.exists() else ""
            if current != text:
                print(f"РАЗОШЛОСЬ: {name} не совпадает со сборкой из {source}")
                bad += 1
            else:
                print(f"ok: {name}")
        else:
            target.write_text(text, encoding="utf-8")
            counters = built["objects"][0]["counters"]
            print(f"{name}: {sum(counters.values())} объектов {counters}")
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
