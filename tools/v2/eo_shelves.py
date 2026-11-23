"""Стартовые полки группировки ДЗЗ. Общесистемные позиции приходят из поставки IoT по коду."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2] / "docs/tz/v2"


def read(path):
    return json.loads((ROOT / path).read_text(encoding="utf-8"))


def inherited(file, field, codes):
    by_code = {row["code"]: row for row in read(file)[field]}
    return [by_code[code].copy() for code in codes]


def records():
    profile = read("ДЗЗ/profile.json")
    mission = profile["mission_class"]
    tag = "mission:" + mission["code"]

    # Новый вид идёт первым: прежний сервер отвергнет его до записи второй WBS/PBS.
    for row in read("ДЗЗ/tasks.json"):
        yield "observation_task", dict(row, code="EO-TASK-" + row["source_id"], tags=[tag])

    nodes = inherited("ПОЛКА-PBS.json", "nodes", profile["common_pbs"])
    nodes[0]["name"] = "Система дистанционного зондирования Земли"
    for node in nodes:
        if node["code"] == "MCC-FD":
            node["name"] = "Баллистическое обеспечение и планирование манёвров"
            node.pop("desc", None)
    levels = {n["code"]: n["level"] for n in nodes}
    for node in profile["pbs"]:
        level = levels[node["parent"]] + 1
        levels[node["code"]] = level
        nodes.append(dict(node, level=level, kind=["system", "segment", "element", "subsystem", "assembly"][level]))
    for node in nodes:
        node["default_take"] = node["level"] <= 3

    def shelf(code, title, **content):
        return dict(code=code, title=title, tags=[tag], **content)
    yield "pbs_template", shelf("PBS-EO", "ДЗЗ · состав группировки и системы наблюдения",
                                rules=["Взятие создаёт уровни 0–3 всегда, детали уровня 4 выбирает проект."], nodes=nodes)
    packages = inherited("ПОЛКА-WBS.json", "packages", profile["common_wbs"])
    pairs = {"05": ["SC-PL"], "06": ["SC-PLT"], "07": ["MCC", "EO-PLAN"], "08": ["SEG-LV"],
             "09": ["SEG-GC", "EO-GROUND"], "10.03": ["SC", "MCC"], "11": ["EO-SVC"]}
    for package in packages:
        if package["code"] in pairs:
            package["pbs_refs"] = pairs[package["code"]]
        if package["code"] == "09":
            package["scope"] = "НКУ, приём целевой информации, архив, обработка и калибровка"
        if package["code"] == "11":
            package["scope"] = "приём заявок, условия качества, выдача продуктов и приёмка"
        if package["code"] == "07.01":
            package["name"] = "Баллистическое обеспечение орбитальной группировки"
        package["pbs_refs"] = [c for c in package["pbs_refs"] if c.removeprefix("@") in levels]
        package["default_take"] = package.get("cross_cutting", False) or any(
            levels[c.removeprefix("@")] <= 3 for c in package["pbs_refs"])
    for package in profile["wbs"]:
        recommended = any(levels[ref.removeprefix("@")] <= 3 for ref in package["pbs_refs"])
        packages.append(dict(package, cross_cutting=False, default_take=recommended))
    yield "wbs_template", shelf("WBS-EO", "ДЗЗ · пакеты работ", packages=packages)
    interfaces = inherited("ПОЛКА-ИНТЕРФЕЙСЫ.json", "interfaces", profile["common_interfaces"])
    shared_models = {"IF-TTC": ["EO-M-RES"], "IF-SC-LV": ["EO-M-RES"], "IF-GCS-MCC": ["EO-M-LATENCY"]}
    for interface in interfaces:
        interface["models"] = shared_models[interface["code"]]
    yield "interface_template", shelf("IFT-EO", "ДЗЗ · типовые интерфейсы", interfaces=interfaces + profile["interfaces"])
    yield "architecture_template", shelf("ARC-EO", "ДЗЗ · участники, функции и цепочки", **profile["architecture"])
    yield "model_template", shelf("MDL-EO", "ДЗЗ · инженерные модели", models=profile["models"])
    for field, kind in [("components", "typical_component"), ("constraints", "constraint"), ("glossary", "glossary_term")]:
        for row in profile[field]:
            yield kind, dict(row, tags=[tag])
    # В форме открытия класс появляется последним, когда загружены его зависимости.
    yield "mission_class", dict(mission, tags=[tag, "prefill:engineering"])


if __name__ == "__main__":
    print(json.dumps(list(records()), ensure_ascii=False))
